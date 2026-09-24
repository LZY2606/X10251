package jtag

import kotlinx.serialization.Serializable

@Serializable
data class IrLengthCandidate(
    val id: String,
    val deviceRef: String,        // device name or chain position
    val length: Int,
    val source: String,           // bsdl-declared | idcode-guess | chain-scan | manual
)

@Serializable
data class BoardRevision(
    val id: String,
    val rev: String,
    /** expected device names in chain order, nearest TDI first */
    val expectedDevices: List<String>,
)

@Serializable
data class ChainSegment(
    val position: Int,            // 0 = nearest TDO
    val deviceName: String?,
    val idcode: String?,          // 32-bit binary string
    val bypass: Boolean,
    val irLength: Int?,
    val missing: Boolean = false, // placeholder for a device expected but not observed
    val drOffsetFromTdo: Int,     // coordinate basis: DR bits from TDO
)

@Serializable
data class ChainSolution(
    val id: String,
    val segments: List<ChainSegment>,
    val status: String,           // UNIQUE | AMBIGUOUS | MISSING_DEVICE
    val notes: List<String> = emptyList(),
)

@Serializable
data class CellPinRow(
    val solutionId: String,
    val device: String,
    val position: Int,
    val cellIndex: Int,
    val port: String,
    val pin: Int?,
    val function: String,
    val basis: String = "cell index in device boundary register; position 0 = nearest TDO",
)

@Serializable
data class ChainResult(
    val solutions: List<ChainSolution>,
    val events: List<JudgmentEvent>,
)

object ChainSolver {

    private data class DrSeg(val idcode: String?, val bypass: Boolean)

    /** Parse the first valid DR shift after reset: 1 -> 32-bit IDCODE follows, 0 -> bypass bit. */
    private fun parseDrChain(bits: List<Int?>): List<DrSeg>? {
        val segs = mutableListOf<DrSeg>()
        var i = 0
        while (i < bits.size) {
            val b = bits[i] ?: return null
            if (b == 1) {
                if (i + 32 > bits.size) return null
                val code = bits.subList(i, i + 32)
                if (code.any { it == null }) return null
                // IDCODE LSB must be 1; bit 31..0 shifted LSB first
                val bin = code.reversed().joinToString("") { it.toString() }
                segs += DrSeg(bin, false)
                i += 32
            } else {
                segs += DrSeg(null, true)
                i += 1
            }
        }
        return segs
    }

    /** Enumerate all splits of [total] into [parts] lengths each >= 2 consistent with TDO capture bits. */
    fun irSplits(total: Int, parts: Int, tdo: List<Int?>): List<List<Int>> {
        if (parts <= 0 || total < parts * 2) return emptyList()
        val out = mutableListOf<List<Int>>()
        fun ok(start: Int, len: Int): Boolean {
            // Capture-IR loads ...01 at the two LSBs; first bit out is bit0=1, second is bit1=0.
            val b0 = tdo.getOrNull(start)
            val b1 = tdo.getOrNull(start + 1)
            return (b0 == null || b0 == 1) && (b1 == null || b1 == 0)
        }
        fun rec(remaining: Int, left: Int, start: Int, acc: List<Int>) {
            if (left == 0) { if (remaining == 0) out += acc; return }
            val maxLen = remaining - 2 * (left - 1)
            for (len in 2..maxLen) if (ok(start, len)) rec(remaining - len, left - 1, start + len, acc + len)
        }
        rec(total, parts, 0, emptyList())
        return out
    }

    fun solve(
        shifts: List<ShiftRecord>,
        bsdl: List<BsdlDevice>,
        irCandidates: List<IrLengthCandidate>,
        board: BoardRevision?,
    ): ChainResult {
        val events = mutableListOf<JudgmentEvent>()
        fun evt(type: String, rule: String, payload: Map<String, String>, sources: List<String> = emptyList()) {
            events += JudgmentEvent("evt-chain-${events.size}", type, rule, payload, sources)
        }

        val drShift = shifts.firstOrNull { it.kind == "DR" && !it.invalidated && !it.tainted }
        val irShift = shifts.firstOrNull { it.kind == "IR" && !it.invalidated && !it.tainted }
        if (drShift == null) {
            evt(EventTypes.CANDIDATE_RETAINED, Rules.CANDIDATES_KEPT_WHEN_UNDECIDABLE,
                mapOf("reason" to "no valid DR shift; chain unsolved"))
            return ChainResult(emptyList(), events)
        }
        val drSegs = parseDrChain(drShift.tdoBits) ?: run {
            evt(EventTypes.CANDIDATE_RETAINED, Rules.CANDIDATES_KEPT_WHEN_UNDECIDABLE,
                mapOf("reason" to "DR shift not parseable as IDCODE/bypass stream"))
            return ChainResult(emptyList(), events)
        }

        val byIdcode = bsdl.filter { it.idcode != null }.associateBy { it.idcode }
        val observedCount = drSegs.size
        val expected = board?.expectedDevices ?: emptyList()
        val missingCount = (expected.size - observedCount).coerceAtLeast(0)

        // IR splits across observed devices
        val splits: List<List<Int>> = if (irShift != null)
            irSplits(irShift.length, observedCount, irShift.tdoBits)
        else emptyList()

        // Rule: IR length must never be fixed from IDCODE alone.
        val nonIdcodeEvidence = irCandidates.any { it.source != "idcode-guess" } || bsdl.isNotEmpty()
        if (irCandidates.isNotEmpty() && irCandidates.all { it.source == "idcode-guess" } && bsdl.isEmpty()) {
            evt(EventTypes.IR_LENGTH_AMBIGUOUS, Rules.IR_LENGTH_NOT_FROM_IDCODE,
                mapOf("reason" to "only idcode-guess evidence available; IDCODE cannot determine IR length",
                      "candidates" to irCandidates.joinToString("|") { "${it.deviceRef}:${it.length}" }),
                irCandidates.map { it.id })
        }
        if (splits.size > 1) {
            evt(EventTypes.IR_LENGTH_AMBIGUOUS, Rules.CANDIDATES_KEPT_WHEN_UNDECIDABLE,
                mapOf("total_ir_bits" to (irShift?.length ?: 0).toString(),
                      "splits" to splits.joinToString("|") { s -> s.joinToString("+") }))
        }

        // BSDL-declared lengths may pin a split, but non-matching splits are retained.
        fun splitScore(split: List<Int?>): Int {
            var score = 0
            for ((i, len) in split.withIndex()) {
                val seg = drSegs[i]
                val dev = seg.idcode?.let { byIdcode[it] }
                if (dev != null && dev.irLength == len) score += 2
                if (irCandidates.any { it.source != "idcode-guess" && it.length == len }) score += 1
            }
            return score
        }

        val baseSplits: List<List<Int?>> = if (splits.isEmpty()) listOf(List(observedCount) { null }) else splits.map { l -> l.map { it as Int? } }
        val solutions = mutableListOf<ChainSolution>()
        // hypothesis sets: observed chain, plus one per missing-device insertion position
        val hypotheses: List<Pair<Int?, List<DrSeg>>> = buildList {
            add(null to drSegs)
            if (missingCount > 0) {
                for (pos in 0..observedCount) {
                    val l = drSegs.toMutableList()
                    repeat(missingCount) { l.add(pos, DrSeg(null, true)) }
                    add(pos to l)
                }
            }
        }

        for ((missingPos, segs) in hypotheses) {
            val splitList = if (segs.size == observedCount) baseSplits
            else baseSplits.map { split ->
                val l = split.toMutableList()
                repeat(missingCount) { l.add(missingPos!!, null) }
                l
            }
            for (split in splitList) {
                var drOffset = 0
                val segments = segs.mapIndexed { i, seg ->
                    val dev = seg.idcode?.let { byIdcode[it] }
                    val isMissing = missingPos != null && i >= missingPos && i < missingPos + missingCount
                    if (dev == null && !seg.bypass && seg.idcode != null) {
                        // unknown idcode, keep as anonymous device
                    }
                    if (seg.bypass && !isMissing) {
                        evt(EventTypes.BYPASS_DEVICE_OBSERVED, Rules.CANDIDATES_KEPT_WHEN_UNDECIDABLE,
                            mapOf("position" to i.toString()))
                    }
                    val s = ChainSegment(
                        position = i,
                        deviceName = if (isMissing) expected.getOrNull(expected.size - missingCount) else dev?.name,
                        idcode = seg.idcode,
                        bypass = seg.bypass,
                        irLength = split.getOrNull(i),
                        missing = isMissing,
                        drOffsetFromTdo = drOffset,
                    )
                    drOffset += if (seg.bypass) 1 else 32
                    s
                }
                val status = when {
                    missingPos != null -> "MISSING_DEVICE"
                    solutions.isNotEmpty() -> "AMBIGUOUS"
                    else -> "UNIQUE"
                }
                solutions += ChainSolution("sol-${solutions.size}", segments, status,
                    notes = buildList {
                        if (missingPos != null) add("device missing at position $missingPos hypothesis")
                        if (!nonIdcodeEvidence) add("IR length evidence limited; IDCODE alone insufficient")
                    })
            }
        }

        if (missingCount > 0) {
            evt(EventTypes.CHAIN_DEVICE_MISSING, Rules.MISSING_DEVICE_KEEPS_OFFSETS,
                mapOf("expected" to expected.size.toString(), "observed" to observedCount.toString(),
                      "offset_candidates" to (0..observedCount).joinToString(",")),
                listOfNotNull(board?.id))
        }

        // rank: best-scoring split first, but every candidate is retained
        val ranked = solutions.sortedByDescending { sol ->
            splitScore(sol.segments.map { it.irLength })
        }
        return ChainResult(ranked, events)
    }

    /** Cell -> pin evidence for every retained solution. */
    fun cellPinEvidence(solutions: List<ChainSolution>, bsdl: List<BsdlDevice>): List<CellPinRow> {
        val rows = mutableListOf<CellPinRow>()
        for (sol in solutions) {
            for (seg in sol.segments) {
                val dev = bsdl.firstOrNull { it.name == seg.deviceName } ?: continue
                for (cell in dev.boundaryCells) {
                    rows += CellPinRow(sol.id, dev.name, seg.position, cell.index,
                        cell.port, dev.pinMap[cell.port], cell.function)
                }
            }
        }
        return rows
    }
}
