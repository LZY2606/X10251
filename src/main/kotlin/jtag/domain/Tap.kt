package jtag.domain

/** IEEE 1149.1 TAP controller states. UNKNOWN models an indeterminate controller
 *  state (e.g. sampling started mid-stream or reset was lost); transitions out of
 *  UNKNOWN are never silently skipped, they are reported as illegal transitions. */
enum class TapState {
    TEST_LOGIC_RESET, RUN_TEST_IDLE,
    SELECT_DR_SCAN, CAPTURE_DR, SHIFT_DR, EXIT1_DR, PAUSE_DR, EXIT2_DR, UPDATE_DR,
    SELECT_IR_SCAN, CAPTURE_IR, SHIFT_IR, EXIT1_IR, PAUSE_IR, EXIT2_IR, UPDATE_IR,
    UNKNOWN;

    fun next(tms: Int): TapState = when (this) {
        TEST_LOGIC_RESET -> if (tms == 1) TEST_LOGIC_RESET else RUN_TEST_IDLE
        RUN_TEST_IDLE -> if (tms == 1) SELECT_DR_SCAN else RUN_TEST_IDLE
        SELECT_DR_SCAN -> if (tms == 1) SELECT_IR_SCAN else CAPTURE_DR
        CAPTURE_DR -> if (tms == 1) EXIT1_DR else SHIFT_DR
        SHIFT_DR -> if (tms == 1) EXIT1_DR else SHIFT_DR
        EXIT1_DR -> if (tms == 1) UPDATE_DR else PAUSE_DR
        PAUSE_DR -> if (tms == 1) EXIT2_DR else PAUSE_DR
        EXIT2_DR -> if (tms == 1) UPDATE_DR else SHIFT_DR
        UPDATE_DR -> if (tms == 1) SELECT_DR_SCAN else RUN_TEST_IDLE
        SELECT_IR_SCAN -> if (tms == 1) TEST_LOGIC_RESET else CAPTURE_IR
        CAPTURE_IR -> if (tms == 1) EXIT1_IR else SHIFT_IR
        SHIFT_IR -> if (tms == 1) EXIT1_IR else SHIFT_IR
        EXIT1_IR -> if (tms == 1) UPDATE_IR else PAUSE_IR
        PAUSE_IR -> if (tms == 1) EXIT2_IR else PAUSE_IR
        EXIT2_IR -> if (tms == 1) UPDATE_IR else SHIFT_IR
        UPDATE_IR -> if (tms == 1) SELECT_DR_SCAN else RUN_TEST_IDLE
        UNKNOWN -> UNKNOWN
    }
}

/** One sampled signal edge. Raw values are kept exactly as imported; [timeUnit]
 *  is the coordinate basis declared by the source (e.g. "ns", "sample"). */
data class SampleEdge(
    val id: String,
    val seq: Int,
    val tck: Int,
    val tms: Int,
    val tdi: Int,
    val tdo: Int,
    val timeValue: Double,
    val timeUnit: String,
    val raw: String = ""
)

enum class SamplePolarity { RISING, FALLING }

data class ResetEvent(
    val id: String,
    val edgeSeq: Int,
    val kind: String, // "trst", "tms-reset", "power-on"
    val note: String = "",
    val raw: String = ""
)

/** A single step of the reconstructed trajectory. */
data class TraceStep(
    val edgeSeq: Int,
    val from: TapState,
    val to: TapState,
    val tms: Int,
    val tdi: Int,
    val tdo: Int,
    val illegal: Boolean = false,
    val reset: Boolean = false
)

sealed class TraceEvent {
    abstract val edgeSeq: Int
    abstract val kind: String

    /** TMS held 1 for >=5 clocks while the trace was already in a known state. */
    data class MidReset(override val edgeSeq: Int, val runLength: Int) : TraceEvent() {
        override val kind = "mid-reset"
    }

    /** A clock edge arrived while the controller state was indeterminate. */
    data class IllegalTransition(override val edgeSeq: Int, val tms: Int) : TraceEvent() {
        override val kind = "illegal-transition"
    }

    /** Declared reset source event applied at an edge. */
    data class ExternalReset(override val edgeSeq: Int, val resetId: String) : TraceEvent() {
        override val kind = "external-reset"
    }
}

data class TapTrace(
    val steps: List<TraceStep>,
    val events: List<TraceEvent>
)

object TapTracer {
    private const val TMS_RESET_RUN = 5

    /**
     * Rebuild the TAP trajectory from sampled edges.
     * Semantics guaranteed here:
     *  - mid-stream resets are detected and surfaced as events, never skipped;
     *  - edges sampled while the state is indeterminate are recorded as illegal
     *    transitions, never silently dropped.
     */
    fun trace(
        edges: List<SampleEdge>,
        polarity: SamplePolarity,
        resets: List<ResetEvent> = emptyList()
    ): TapTrace {
        val sorted = edges.sortedBy { it.seq }
        val resetBySeq = resets.associateBy { it.edgeSeq }
        val steps = ArrayList<TraceStep>(sorted.size)
        val events = ArrayList<TraceEvent>()
        var state = TapState.UNKNOWN
        var tmsRun = 0
        for (edge in sorted) {
            // Sampling polarity selects which half of the clock the levels refer to;
            // a wrong polarity shifts TMS sampling off the clock and typically breaks
            // reset detection, which then surfaces as illegal transitions below.
            val tms = edge.tms
            tmsRun = if (tms == 1) tmsRun + 1 else 0
            val external = resetBySeq[edge.seq]
            if (external != null) {
                events += TraceEvent.ExternalReset(edge.seq, external.id)
                steps += TraceStep(edge.seq, state, TapState.TEST_LOGIC_RESET, tms, edge.tdi, edge.tdo, reset = true)
                state = TapState.TEST_LOGIC_RESET
                tmsRun = 0
                continue
            }
            if (tmsRun >= TMS_RESET_RUN) {
                if (state != TapState.UNKNOWN && state != TapState.TEST_LOGIC_RESET) {
                    events += TraceEvent.MidReset(edge.seq, tmsRun)
                }
                steps += TraceStep(edge.seq, state, TapState.TEST_LOGIC_RESET, tms, edge.tdi, edge.tdo, reset = true)
                state = TapState.TEST_LOGIC_RESET
                continue
            }
            if (state == TapState.UNKNOWN) {
                events += TraceEvent.IllegalTransition(edge.seq, tms)
                steps += TraceStep(edge.seq, state, TapState.UNKNOWN, tms, edge.tdi, edge.tdo, illegal = true)
                continue
            }
            val next = state.next(tms)
            steps += TraceStep(edge.seq, state, next, tms, edge.tdi, edge.tdo)
            state = next
        }
        return TapTrace(steps, events)
    }
}
