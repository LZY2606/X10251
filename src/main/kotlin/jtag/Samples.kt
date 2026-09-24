package jtag

import kotlinx.serialization.Serializable

enum class Edge { RISING, FALLING }

@Serializable
data class SampleConfig(
    val edge: Edge = Edge.RISING,
    val thresholdV: Double = 1.65,
    val setupWindowNs: Double = 150.0,
    val minPeriodNs: Double = 400.0,
)

@Serializable
data class SampleRow(
    val tNs: Double,
    val tckV: Double?, val tmsV: Double?, val tdiV: Double?, val tdoV: Double?,
)

/** Raw imported sample batch. Raw CSV text is preserved verbatim. */
@Serializable
data class SampleBatch(
    val id: String,
    val boardRev: String,
    val meta: Map<String, String>,
    val rows: List<SampleRow>,
    val rawCsv: String,
)

@Serializable
data class ResetEvent(
    val id: String,
    val kind: String,           // TRST | TMS5 | POWER_CYCLE
    val time: Quantity,
    val note: String? = null,
)

@Serializable
data class TraceStep(
    val index: Int,
    val tNs: Double,
    val tms: Int?, val tdi: Int?, val tdo: Int?,
    val from: TapState?, val to: TapState?,
    /** when the TMS bit could not be determined, both legal successors are kept here */
    val candidates: List<TapState> = emptyList(),
    val tainted: Boolean = false,
    val eventIds: List<String> = emptyList(),
)

@Serializable
data class TapTrace(
    val steps: List<TraceStep>,
    val events: List<JudgmentEvent>,
    val config: SampleConfig,
    val stats: Map<String, String> = emptyMap(),
)

object SampleParser {
    fun parseCsv(id: String, boardRev: String, text: String): SampleBatch {
        val meta = mutableMapOf<String, String>()
        val rows = mutableListOf<SampleRow>()
        var header: List<String>? = null
        for (line in text.lines()) {
            val s = line.trim()
            if (s.isEmpty()) continue
            if (s.startsWith("#")) {
                val kv = s.removePrefix("#").split(":", limit = 2)
                if (kv.size == 2) meta[kv[0].trim()] = kv[1].trim()
                continue
            }
            val cols = s.split(",").map { it.trim() }
            if (header == null) { header = cols; continue }
            fun cell(name: String): String? {
                val i = header!!.indexOf(name)
                return if (i >= 0 && i < cols.size) cols[i].ifEmpty { null } else null
            }
            fun num(name: String): Double? = cell(name)?.toDoubleOrNull()
            val t = num("t_ns") ?: continue
            rows.add(SampleRow(t, num("tck_v"), num("tms_v"), num("tdi_v"), num("tdo_v")))
        }
        return SampleBatch(id, boardRev, meta.toMap(), rows, text)
    }
}

object TapReconstructor {
    private var eventCounter = 0

    private fun digitize(v: Double?, cfg: SampleConfig): Int? =
        v?.let { if (it >= cfg.thresholdV) 1 else 0 }

    fun reconstruct(batch: SampleBatch, resets: List<ResetEvent>, cfg: SampleConfig): TapTrace {
        val events = mutableListOf<JudgmentEvent>()
        fun evt(type: String, rule: String, payload: Map<String, String>, sources: List<String>): String {
            val id = "evt-${batch.id}-${events.size}"
            events += JudgmentEvent(id, type, rule, payload, sources)
            return id
        }

        val rows = batch.rows
        val tck = rows.map { digitize(it.tckV, cfg) }
        val tms = rows.map { digitize(it.tmsV, cfg) }
        val tdi = rows.map { digitize(it.tdiV, cfg) }
        val tdo = rows.map { digitize(it.tdoV, cfg) }

        // locate active TCK edges
        val edges = mutableListOf<Int>()
        for (i in 1 until rows.size) {
            val a = tck[i - 1] ?: continue
            val b = tck[i] ?: continue
            val active = when (cfg.edge) {
                Edge.RISING -> a == 0 && b == 1
                Edge.FALLING -> a == 1 && b == 0
            }
            if (active) edges.add(i)
        }

        // TCK glitch detection
        for (k in 1 until edges.size) {
            val dt = rows[edges[k]].tNs - rows[edges[k - 1]].tNs
            if (dt < cfg.minPeriodNs) {
                evt(EventTypes.ILLEGAL_TRANSITION, Rules.ILLEGAL_TRANSITION_NOT_SKIPPABLE,
                    mapOf("cause" to "tck_glitch", "at_ns" to rows[edges[k]].tNs.toString(),
                          "period_ns" to dt.toString()),
                    listOf(batch.id))
            }
        }

        // reset events converted to ns; unconvertible stay raw with diagnostic
        data class RAt(val tNs: Double, val kind: String, val srcId: String)
        val resetAts = mutableListOf<RAt>()
        for (r in resets) {
            val t = r.time.toNs()
            if (t == null) {
                evt(EventTypes.QUANTITY_UNCONVERTIBLE, Rules.CANDIDATES_KEPT_WHEN_UNDECIDABLE,
                    mapOf("reset_id" to r.id, "raw" to r.time.raw,
                          "diagnostic" to (r.time.diagnostic ?: "no time basis")),
                    listOf(batch.id, r.id))
            } else resetAts += RAt(t, r.kind, r.id)
        }

        fun stableBit(bits: List<Int?>, atNs: Double, window: Double): Pair<Int?, Boolean> {
            var first: Int? = null
            var ambiguous = false
            for (i in rows.indices) {
                val t = rows[i].tNs
                if (t < atNs - window || t > atNs + window) continue
                val b = bits[i] ?: return null to true
                if (first == null) first = b else if (first != b) ambiguous = true
            }
            return (first ?: null) to ambiguous
        }

        val steps = mutableListOf<TraceStep>()
        var state: TapState = TapState.RESET
        var tmsRun = 0
        var tainted = false
        var resetIdx = 0
        var lastTNs = 0.0

        for ((stepIdx, edgeRow) in edges.withIndex()) {
            val t = rows[edgeRow].tNs
            lastTNs = t
            // external reset events between previous edge and this one
            while (resetIdx < resetAts.size && resetAts[resetIdx].tNs <= t) {
                val r = resetAts[resetIdx++]
                val midScan = state != TapState.RESET && state != TapState.IDLE
                val id = evt(EventTypes.MID_SCAN_RESET, Rules.MID_RESET_NOT_SKIPPABLE,
                    mapOf("kind" to r.kind, "at_ns" to r.tNs.toString(),
                          "state" to state.name, "mid_scan" to midScan.toString()),
                    listOf(batch.id, r.srcId))
                steps += TraceStep(steps.size, r.tNs, null, null, null, state, TapState.RESET,
                    eventIds = listOf(id))
                state = TapState.RESET
                tmsRun = 0
                tainted = false
            }

            val (tmsBit, tmsAmb) = stableBit(tms, t, cfg.setupWindowNs)
            val (tdiBit, _) = stableBit(tdi, t, cfg.setupWindowNs)
            val (tdoBit, _) = stableBit(tdo, t, cfg.setupWindowNs)

            val stepEvents = mutableListOf<String>()
            var candidates = listOf<TapState>()
            val useBit: Int?
            if (tmsAmb || tmsBit == null) {
                candidates = listOf(TapFsm.next(state, 0), TapFsm.next(state, 1))
                val id = evt(EventTypes.ILLEGAL_TRANSITION, Rules.ILLEGAL_TRANSITION_NOT_SKIPPABLE,
                    mapOf("at_ns" to t.toString(), "state" to state.name,
                          "cause" to if (tmsBit == null) "tms_unknown" else "tms_unstable_in_setup_window",
                          "candidate_next" to candidates.joinToString("|") { it.name }),
                    listOf(batch.id))
                stepEvents += id
                tainted = true
                // working value: bit settled after the window, so the trace can continue
                useBit = digitize(rows[minOf(edgeRow + 2, rows.size - 1)].tmsV, cfg)
            } else useBit = tmsBit

            val from = state
            state = TapFsm.next(state, useBit ?: 1)
            if (useBit == 1) {
                tmsRun++
                if (tmsRun >= TapFsm.RESET_TMS_COUNT && from != TapState.RESET) {
                    val id = evt(EventTypes.MID_SCAN_RESET, Rules.MID_RESET_NOT_SKIPPABLE,
                        mapOf("kind" to "TMS5", "at_ns" to t.toString(),
                              "state" to from.name, "mid_scan" to (from != TapState.IDLE).toString()),
                        listOf(batch.id))
                    stepEvents += id
                    tainted = false
                }
            } else tmsRun = 0
            if (state == TapState.RESET) tainted = false

            steps += TraceStep(stepIdx, t, useBit, tdiBit, tdoBit, from, state,
                candidates = candidates, tainted = tainted, eventIds = stepEvents)
        }

        val ambiguous = steps.count { it.candidates.isNotEmpty() }
        val stats = mutableMapOf(
            "edges" to edges.size.toString(),
            "ambiguous_steps" to ambiguous.toString(),
            "last_t_ns" to lastTNs.toString(),
        )
        if (ambiguous > 0) {
            val alt = if (cfg.edge == Edge.RISING) Edge.FALLING else Edge.RISING
            evt(EventTypes.SAMPLING_POLARITY_VIOLATION, Rules.CANDIDATES_KEPT_WHEN_UNDECIDABLE,
                mapOf("edge_config" to cfg.edge.name, "ambiguous_steps" to ambiguous.toString(),
                      "suggested_edge" to alt.name),
                listOf(batch.id))
        }
        return TapTrace(steps, events.toList(), cfg, stats)
    }
}
