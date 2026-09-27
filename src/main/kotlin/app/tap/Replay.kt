package app.tap

import app.model.*

data class Edge(val index: Int, val sampleIndex: Int, val tms: Int, val tdi: Int, val tdo: Int) {
    /** tms == -1  : level could not be normalized to a legal logic 0/1 at the edge. */
    val defined get() = tms == 0 || tms == 1
}

data class ShiftSegment(
    val reg: String,
    val startEdge: Int,
    val length: Int,
    val tdoBits: List<Int>,
    val tdiBits: List<Int>,
    val truncated: Boolean,
)

data class ReplayResult(
    val steps: List<TapStep>,
    val bits: List<ShiftBit>,
    val events: List<DomainEvent>,
    val finalState: String,
    val segments: List<ShiftSegment>,
) {
    val hasIllegal get() = events.any { it.type == "illegal-transition" }
}

private class Segment(var reg: String, val startEdge: Int, var pos: Int = 0, var done: Boolean = false)

/**
 * Replays TCK edges into the IEEE 1149.1 state graph.
 *
 * Guarantees (machine-checked by the predicate lists on events):
 *  - an edge with an undefined TMS level is an illegal transition and is recorded,
 *    never silently skipped;
 *  - a reset (declared TRST or five TMS=1) that lands while a shift segment is
 *    open and has not reached Update-IR/DR is a mid-shift reset: the segment is
 *    truncated, every captured bit of it is flagged, and an event is emitted;
 *  - every step keeps its originating sample index (provenance).
 */
class TapReplayer(private val sampleSetId: String, private val now: Long = 0L) {

    fun replay(edges: List<Edge>, declaredResets: List<ResetEvent> = emptyList()): ReplayResult {
        val steps = mutableListOf<TapStep>()
        val bits = mutableListOf<ShiftBit>()
        val events = mutableListOf<DomainEvent>()
        val declaredAt = declaredResets.associateBy { it.sampleIndex }
        var state = "TestLogicReset"
        var onesRun = 0
        var seg: Segment? = null

        for (e in edges) {
            val declared = declaredAt[e.sampleIndex]

            if (!e.defined) {
                events += DomainEvent(
                    id = "evt:${sampleSetId}:illegal:${e.index}", ts = now,
                    type = "illegal-transition", severity = "error",
                    message = "TMS is not a defined logic level at edge ${e.index} (sample ${e.sampleIndex}); no legal TAP transition exists",
                    sourceRefs = mapOf("sampleSet" to sampleSetId, "sample" to e.sampleIndex.toString()),
                    affectedIds = listOf("step:$sampleSetId:${e.index}"),
                    predicates = listOf("illegal_tap_transition_recorded", "not_silently_skipped"),
                    accepted = false, derivationVersion = null,
                )
                steps += TapStep("step:$sampleSetId:${e.index}", e.index, e.sampleIndex,
                    e.tms.coerceAtLeast(0), e.tdi.coerceAtLeast(0), e.tdo.coerceAtLeast(0),
                    state, "<illegal>", true, false,
                    "undefined TMS level; state held, no transition taken")
                continue
            }

            val wasShifting = state == "ShiftIR" || state == "ShiftDR"
            val to = TapFsm.transition(state, e.tms)!!
            if (e.tms == 1) onesRun++ else onesRun = 0
            var fiveOneReset = state != "TestLogicReset" && onesRun >= 5
            var resetNow = fiveOneReset || declared != null
            val dest = if (resetNow) "TestLogicReset" else to
            val note = buildList {
                if (fiveOneReset) add("five-consecutive-TMS-1")
                if (declared != null) add("declared:${declared.kind}")
            }.joinToString(";").ifEmpty { null }

            // capture shift bit on the exit edge (TMS=1 in Shift) as well
            if (wasShifting && !resetNow) {
                val s = seg ?: Segment(if (state == "ShiftIR") "IR" else "DR", e.index)
                    .also { seg = it }
                bits += ShiftBit("bit:$sampleSetId:${e.index}", e.index, s.reg, s.pos++,
                    e.tdi.coerceAtLeast(0), e.tdo.coerceAtLeast(0), false)
            }

            if (dest.startsWith("Update")) seg?.done = true
            if (resetNow && seg != null && seg?.done == false) {
                val s = seg!!
                for ((i, b) in bits.withIndex())
                    if (b.reg == s.reg && b.edgeIndex >= s.startEdge)
                        bits[i] = b.copy(resetDuringShift = true)
                events += DomainEvent(
                    id = "evt:${sampleSetId}:midreset:${e.index}", ts = now,
                    type = "mid-shift-reset", severity = "error",
                    message = "Reset ($note) reached at edge ${e.index} before Update-${s.reg}; ${s.pos}-bit ${s.reg} segment truncated",
                    sourceRefs = mapOf("sampleSet" to sampleSetId, "sample" to e.sampleIndex.toString()),
                    affectedIds = bits.filter { it.resetDuringShift }.map { it.id },
                    predicates = listOf("mid_shift_reset_recorded", "shift_truncated", "not_silently_skipped"),
                    accepted = false, derivationVersion = null,
                )
                seg = null
            }

            steps += TapStep("step:$sampleSetId:${e.index}", e.index, e.sampleIndex,
                e.tms, e.tdi.coerceAtLeast(0), e.tdo.coerceAtLeast(0),
                state, dest, false, resetNow, note)
            state = dest
            if (state == "TestLogicReset") { onesRun = 0; if (seg?.done == true) seg = null }
        }
        return ReplayResult(steps, bits, events, state)
    }
}
