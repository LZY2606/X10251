package jtap.core

import jtap.model.JudgementEvent
import jtap.model.PolarityConfig
import jtap.model.Quantity
import jtap.model.ResetEvent
import jtap.model.RuleCode
import jtap.model.SampleSource
import jtap.model.EventSeverity

data class ShiftedBit(
    val edgeIndex: Long,
    val tdiRaw: String,
    val tdoRaw: String,
    val tdiBit: Int?,
    val tdoBit: Int?,
)

data class ScanSegment(
    val segmentId: String,
    val kind: String,
    val enterEdge: Long,
    val shiftEdges: List<Long>,
    val bits: List<ShiftedBit>,
    val tdiLsbFirst: String,
    val tdoLsbFirst: String,
    val aborted: Boolean,
    val abandonedBits: Int,
    val resetEdge: Long?,
)

data class TapTrace(
    val polarity: PolarityConfig,
    val transitions: List<TapTransition>,
    val segments: List<ScanSegment>,
    val resetEvents: List<ResetEvent>,
    val events: List<JudgementEvent>,
    val deterministic: Boolean,
)

object Reconstructor {
    private fun level(raw: String, invert: Boolean): Int? =
        TapFsm.normalizeLevel(raw)?.let { if (invert) it xor 1 else it }

    fun reconstruct(
        caseId: String,
        source: SampleSource,
        polarityOverride: PolarityConfig? = null,
    ): TapTrace {
        val polarity = polarityOverride ?: source.polarity
        val fsm = TapFsm()
        val transitions = mutableListOf<TapTransition>()
        val events = mutableListOf<JudgementEvent>()
        val resets = mutableListOf<ResetEvent>()
        val segments = mutableListOf<ScanSegment>()

        var segment: MutableList<ShiftedBit>? = null
        var segmentKind = ""
        var segmentEnter = -1L

        source.edges.forEach { edge ->
            val tmsBit = level(edge.tms, polarity.invertTms)
            val tdiBit = level(edge.tdi, polarity.invertTdi)
            val tdoBit = level(edge.tdo, polarity.invertTdo)
            val tmsForFsm = if (tmsBit == null) edge.tms else tmsBit.toString()
            val declared = edge.declaredState?.let { raw ->
                runCatching { jtap.core.TapState.valueOf(raw.uppercase()) }.getOrNull()
            }
            val before = fsm.state
            val tr = fsm.step(
                edge.edgeIndex, edge.at, tmsForFsm, edge.tck, edge.tdi, edge.tdo, declared,
            )
            transitions += tr

            if ((before == TapState.CAPTURE_IR && tr.to == TapState.SHIFT_IR) ||
                (before == TapState.CAPTURE_DR && tr.to == TapState.SHIFT_DR)) {
                segment = mutableListOf()
                segmentKind = if (tr.to == TapState.SHIFT_IR) "IR" else "DR"
                segmentEnter = edge.edgeIndex
            }

            if (before == TapState.SHIFT_IR || before == TapState.SHIFT_DR) {
                segment?.add(ShiftedBit(edge.edgeIndex, edge.tdi, edge.tdo, tdiBit, tdoBit))
            }

            when (tr.kind) {
                TransitionKind.ILLEGAL_DECLARATION -> events += JudgementEvent(
                    eventId = jtap.model.StableIds.event(caseId, RuleCode.ILLEGAL_TAP_TRANSITION.machineRule,
                        edge.edgeIndex.toString()),
                    rule = RuleCode.ILLEGAL_TAP_TRANSITION,
                    severity = EventSeverity.ANOMALY,
                    message = tr.diagnostic ?: "illegal declared TAP transition",
                    edgeIndex = edge.edgeIndex,
                    subjectIds = listOf(jtap.model.StableIds.edge(caseId, source.revision, edge.edgeIndex)),
                    adopted = false,
                    basis = listOf("IEEE1149.1 Table 6-1", "sample:${source.sourceId}#${source.revision}"),
                )
                TransitionKind.MID_SCAN_RESET, TransitionKind.MID_SHIFT_RESET -> {
                    val rule = RuleCode.MID_SCAN_RESET
                    val eid = jtap.model.StableIds.event(caseId, rule.machineRule, edge.edgeIndex.toString())
                    events += JudgementEvent(
                        eventId = eid, rule = rule, severity = EventSeverity.ANOMALY,
                        message = tr.diagnostic ?: "reset inside scan path",
                        edgeIndex = edge.edgeIndex,
                        subjectIds = listOf(jtap.model.StableIds.edge(caseId, source.revision, edge.edgeIndex)),
                        adopted = false,
                        basis = listOf("TMS=1 run length ${tr.onesRunLength}", "sample:${source.sourceId}#${source.revision}"),
                    )
                    resets += ResetEvent(
                        eventId = jtap.model.StableIds.reset(caseId, source.revision, edge.edgeIndex),
                        sourceId = source.sourceId, edgeIndex = edge.edgeIndex, at = edge.at,
                        cause = "tms-ones-run", midScan = true,
                        abandonedBits = tr.shiftBitsAbandoned,
                        detail = tr.diagnostic ?: "",
                    )
                    if (segment != null) {
                        segments += finalize(caseId, segmentKind, segmentEnter, segment!!,
                            aborted = true, abandoned = tr.shiftBitsAbandoned, resetEdge = edge.edgeIndex)
                        segment = null
                    }
                }
                else -> Unit
            }

            if ((before == TapState.SHIFT_IR || before == TapState.SHIFT_DR) &&
                tr.to != before && segment != null &&
                tr.kind != TransitionKind.MID_SCAN_RESET && tr.kind != TransitionKind.MID_SHIFT_RESET) {
                segments += finalize(caseId, segmentKind, segmentEnter, segment!!,
                    aborted = false, abandoned = 0, resetEdge = null)
                segment = null
            }
        }
        segment?.let {
            segments += finalize(caseId, segmentKind, segmentEnter, it,
                aborted = true, abandoned = it.size, resetEdge = null)
            events += JudgementEvent(
                eventId = jtap.model.StableIds.event(caseId, "scan.unterminated", segmentEnter.toString()),
                rule = RuleCode.MID_SCAN_RESET,
                severity = EventSeverity.WARNING,
                message = "scan entered at edge $segmentEnter never leaves Shift state in the capture window",
                edgeIndex = segmentEnter,
            )
        }
        return TapTrace(polarity, transitions, segments, resets, events, fsm.deterministic)
    }

    private fun finalize(
        caseId: String, kind: String, enter: Long, bits: List<ShiftedBit>,
        aborted: Boolean, abandoned: Int, resetEdge: Long?,
    ): ScanSegment {
        val tdi = bits.joinToString("") { it.tdiBit?.toString() ?: "X" }
        val tdo = bits.joinToString("") { it.tdoBit?.toString() ?: "X" }
        return ScanSegment(
            segmentId = "seg:${kind}:$enter",
            kind = kind, enterEdge = enter,
            shiftEdges = bits.map { it.edgeIndex },
            bits = bits,
            tdiLsbFirst = tdi, tdoLsbFirst = tdo,
            aborted = aborted, abandonedBits = abandoned, resetEdge = resetEdge,
        )
    }
}
