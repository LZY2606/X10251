package jtag.tap

import jtag.model.*
import kotlinx.serialization.Serializable

@Serializable
enum class TapState {
    TEST_LOGIC_RESET, RUN_TEST_IDLE,
    SELECT_DR_SCAN, CAPTURE_DR, SHIFT_DR, EXIT1_DR, PAUSE_DR, EXIT2_DR, UPDATE_DR,
    SELECT_IR_SCAN, CAPTURE_IR, SHIFT_IR, EXIT1_IR, PAUSE_IR, EXIT2_IR, UPDATE_IR;

    val isShift: Boolean get() = this == SHIFT_DR || this == SHIFT_IR
}

/** Standard IEEE 1149.1 state transition. */
fun nextState(s: TapState, tms: Boolean): TapState = when (s) {
    TapState.TEST_LOGIC_RESET -> if (tms) TapState.TEST_LOGIC_RESET else TapState.RUN_TEST_IDLE
    TapState.RUN_TEST_IDLE -> if (tms) TapState.SELECT_DR_SCAN else TapState.RUN_TEST_IDLE
    TapState.SELECT_DR_SCAN -> if (tms) TapState.SELECT_IR_SCAN else TapState.CAPTURE_DR
    TapState.CAPTURE_DR -> if (tms) TapState.EXIT1_DR else TapState.SHIFT_DR
    TapState.EXIT1_DR -> if (tms) TapState.UPDATE_DR else TapState.PAUSE_DR
    TapState.PAUSE_DR -> if (tms) TapState.EXIT2_DR else TapState.PAUSE_DR
    TapState.EXIT2_DR -> if (tms) TapState.UPDATE_DR else TapState.SHIFT_DR
    TapState.UPDATE_DR -> if (tms) TapState.SELECT_DR_SCAN else TapState.RUN_TEST_IDLE
    TapState.SELECT_IR_SCAN -> if (tms) TapState.TEST_LOGIC_RESET else TapState.CAPTURE_IR
    TapState.CAPTURE_IR -> if (tms) TapState.EXIT1_IR else TapState.SHIFT_IR
    TapState.EXIT1_IR -> if (tms) TapState.UPDATE_IR else TapState.PAUSE_IR
    TapState.PAUSE_IR -> if (tms) TapState.EXIT2_IR else TapState.PAUSE_IR
    TapState.EXIT2_IR -> if (tms) TapState.UPDATE_IR else TapState.SHIFT_IR
    TapState.UPDATE_IR -> if (tms) TapState.SELECT_DR_SCAN else TapState.RUN_TEST_IDLE
}

@Serializable
enum class EdgeAnomaly { NONE, MID_STREAM_RESET, ILLEGAL_TRANSITION_HINT }

@Serializable
data class TrajectoryStep(
    val seq: Int,
    val tms: Boolean,
    val tdi: Boolean,
    val tdo: Boolean,
    val state: TapState,
    val anomaly: EdgeAnomaly = EdgeAnomaly.NONE,
    val note: String? = null
)

@Serializable
data class TrajectorySegment(
    val index: Int,
    val steps: List<TrajectoryStep>,
    val abortedByReset: Boolean = false
)

@Serializable
data class TapTrajectory(
    val polarity: ClockPhase,
    val segments: List<TrajectorySegment>,
    val resets: List<ResetEvent>,
    val illegalHints: List<IllegalTransition>,
    val diagnostics: List<String> = emptyList()
) {
    val steps: List<TrajectoryStep> get() = segments.flatMap { it.steps }
}

@Serializable
data class IllegalTransition(
    val atSeq: Int,
    val fsmState: TapState,
    val hintedState: String,
    val hintSource: String,
    val resolution: String = "FSM-derived state kept; hint retained as rejected candidate"
)

object TrajectoryBuilder {

    /** Extract active edges from raw half-period samples according to polarity. */
    fun activeEdges(samples: List<EdgeSample>, polarity: ClockPhase): List<EdgeSample> =
        samples.filter { it.phase == polarity }.sortedBy { it.seq }

    /** Detect resets: >=5 consecutive TMS=1, or TRST asserted. Never silently dropped. */
    fun detectResets(edges: List<EdgeSample>): List<ResetEvent> {
        val out = mutableListOf<ResetEvent>()
        var run = 0
        var runStart = -1
        for (e in edges) {
            if (e.trst) {
                out += ResetEvent(kind = ResetKind.TRST_ASSERTED, atSeq = e.seq, span = 1, midStream = false)
                run = 0
                continue
            }
            if (e.tms) {
                if (run == 0) runStart = e.seq
                run++
                if (run == 5) {
                    out += ResetEvent(kind = ResetKind.TMS_FIVE_HIGH, atSeq = runStart, span = 5, midStream = false)
                }
            } else run = 0
        }
        return out
    }

    fun build(bundle: EdgeBundle, polarity: ClockPhase, resets: List<ResetEvent>): TapTrajectory {
        val edges = activeEdges(bundle.samples, polarity)
        val resetAt = resets.associateBy { it.atSeq }
        val hints = bundle.hints.associateBy { it.atSeq }
        val segments = mutableListOf<TrajectorySegment>()
        val illegal = mutableListOf<IllegalTransition>()
        var steps = mutableListOf<TrajectoryStep>()
        var state = TapState.TEST_LOGIC_RESET
        var inTransaction = false
        var segIndex = 0
        val markedResets = resets.toMutableList()

        fun flush(aborted: Boolean) {
            if (steps.isNotEmpty()) {
                segments += TrajectorySegment(segIndex++, steps.toList(), abortedByReset = aborted)
                steps = mutableListOf()
            }
        }

        for (e in edges) {
            val reset = resetAt[e.seq]
            var anomaly = EdgeAnomaly.NONE
            var note: String? = null
            if (reset != null) {
                // A reset is never skipped: it terminates the current segment and restarts from TLR.
                if (inTransaction || state.isShift) {
                    anomaly = EdgeAnomaly.MID_STREAM_RESET
                    val i = markedResets.indexOfFirst { it.atSeq == reset.atSeq }
                    if (i >= 0) markedResets[i] = markedResets[i].copy(midStream = true)
                    note = "reset interrupted in-progress transaction; segment aborted, not silently skipped"
                }
                flush(aborted = true)
                state = TapState.TEST_LOGIC_RESET
                inTransaction = false
            }
            val next = nextState(state, e.tms)
            val hint = hints[e.seq]
            if (hint != null && hint.state != next.name) {
                val legal = hint.state == state.name || runCatching { TapState.valueOf(hint.state) }.getOrNull() != null
                illegal += IllegalTransition(e.seq, next, hint.state, hint.source)
                anomaly = EdgeAnomaly.ILLEGAL_TRANSITION_HINT
                note = (note?.plus("; ") ?: "") +
                    "illegal transition hint '${hint.state}' from ${hint.source}; FSM legal next is '${next.name}'" +
                    if (!legal) " (hint names no reachable state)" else ""
            }
            steps += TrajectoryStep(e.seq, e.tms, e.tdi, e.tdo, next, anomaly, note)
            state = next
            if (state.isShift) inTransaction = true
            if (state == TapState.UPDATE_DR || state == TapState.UPDATE_IR || state == TapState.RUN_TEST_IDLE)
                inTransaction = false
        }
        flush(aborted = false)
        val diagnostics = bundle.samples.mapNotNull { it.time.diagnostic }.distinct()
        return TapTrajectory(polarity, segments, markedResets, illegal, diagnostics)
    }
}
