package jtag.core

import kotlinx.serialization.Serializable

@Serializable
enum class AnomalyKind {
    ILLEGAL_TRANSITION,   // FSM-derived next state contradicts analyzer annotation
    MID_SCAN_RESET,       // reset event landed while a shift segment was open
    RESET_SYNC,           // >=5 consecutive TMS=1 (informational)
}

@Serializable
data class Anomaly(
    val id: String,
    val kind: AnomalyKind,
    val edgeIndex: Int,
    val detail: String,
)

/** Reset events are stored separately from sample edges (separate source kind). */
@Serializable
data class ResetEvent(
    val edgeIndex: Int,
    val cause: String,   // TRST_ASSERT | TMS_SYNC | POWER_CYCLE
)

@Serializable
data class TraceStep(
    val edgeIndex: Int,
    val tms: Int, val tdi: Int, val tdo: Int,
    val fromState: TapState,
    val toState: TapState,
    val expectedState: String? = null,
    val forcedReset: Boolean = false,
    val anomalyIds: List<String> = emptyList(),
)

@Serializable
enum class SegmentStatus { COMPLETE, ABORTED_BY_RESET }

@Serializable
data class ShiftSegment(
    val id: String,
    val register: String,          // "IR" or "DR"
    val startEdge: Int,
    val endEdge: Int,
    val tdiBits: List<Int>,        // in shift order (first shifted first)
    val tdoBits: List<Int>,
    val status: SegmentStatus,
)

@Serializable
data class TraceResult(
    val steps: List<TraceStep>,
    val segments: List<ShiftSegment>,
    val anomalies: List<Anomaly>,
)

/**
 * Reconstruct the TAP trajectory from normalized samples plus separately-stored
 * reset events. Guardrail semantics:
 *  - illegal transitions (annotation mismatch) are recorded as anomalies; the
 *    FSM path and the annotation are both retained; nothing is silently skipped;
 *  - a reset event landing inside an open shift segment aborts it with
 *    ABORTED_BY_RESET; the partial bits are retained, never dropped.
 */
fun reconstructTrace(
    samples: List<NormSample>,
    resetEvents: List<ResetEvent> = emptyList(),
    startState: TapState = TapState.TEST_LOGIC_RESET,
): TraceResult {
    val resetsByEdge = resetEvents.associateBy { it.edgeIndex }
    val steps = mutableListOf<TraceStep>()
    val anomalies = mutableListOf<Anomaly>()
    val segments = mutableListOf<ShiftSegment>()
    var state = startState
    var onesRun = 0
    var segReg: String? = null
    var segStart = 0
    val segTdi = mutableListOf<Int>()
    val segTdo = mutableListOf<Int>()

    fun closeSegment(endEdge: Int, status: SegmentStatus) {
        val reg = segReg ?: return
        segments.add(ShiftSegment("seg-${segments.size}", reg, segStart, endEdge, segTdi.toList(), segTdo.toList(), status))
        segReg = null; segTdi.clear(); segTdo.clear()
    }

    for (s in samples) {
        if (s.tms == 1) onesRun++ else onesRun = 0
        val reset = resetsByEdge[s.edgeIndex]
        val fsmNext = tapNext(state, s.tms)
        val ids = mutableListOf<String>()

        if (s.expectedState != null && s.expectedState != fsmNext.name && reset == null) {
            val a = Anomaly("anom-${anomalies.size}", AnomalyKind.ILLEGAL_TRANSITION, s.edgeIndex,
                "FSM derives $fsmNext from $state/TMS=${s.tms} but analyzer annotated ${s.expectedState}; both retained")
            anomalies.add(a); ids.add(a.id)
        }
        if (onesRun == 5) {
            val a = Anomaly("anom-${anomalies.size}", AnomalyKind.RESET_SYNC, s.edgeIndex,
                "5 consecutive TMS=1: synchronized to TEST_LOGIC_RESET")
            anomalies.add(a); ids.add(a.id)
        }

        var next = fsmNext
        var forced = false
        if (reset != null) {
            forced = true
            next = TapState.TEST_LOGIC_RESET
            if (segReg != null) {
                val a = Anomaly("anom-${anomalies.size}", AnomalyKind.MID_SCAN_RESET, s.edgeIndex,
                    "reset (${reset.cause}) landed while $segReg shift in progress (${segTdi.size} bits); partial segment retained, not skipped")
                anomalies.add(a); ids.add(a.id)
                closeSegment(s.edgeIndex, SegmentStatus.ABORTED_BY_RESET)
            }
        }

        // shift bookkeeping: bits shift on edges that leave a shift state
        if (state.isShift && !forced) { segTdi.add(s.tdi); segTdo.add(s.tdo) }
        if (!state.isShift && next.isShift) { segReg = if (next == TapState.SHIFT_IR) "IR" else "DR"; segStart = s.edgeIndex }
        if (state.isShift && !next.isShift) closeSegment(s.edgeIndex, SegmentStatus.COMPLETE)

        steps.add(TraceStep(s.edgeIndex, s.tms, s.tdi, s.tdo, state, next, s.expectedState, forced, ids))
        state = next
    }
    if (segReg != null) closeSegment(samples.lastOrNull()?.edgeIndex ?: 0, SegmentStatus.ABORTED_BY_RESET)
    return TraceResult(steps, segments, anomalies)
}
