package jtag

/** IEEE 1149.1 TAP controller states. */
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
        EXIT1_DR -> if (tms == 1) UPDATE_DR else PAUSE_DR
        PAUSE_DR -> if (tms == 1) EXIT2_DR else PAUSE_DR
        EXIT2_DR -> if (tms == 1) UPDATE_DR else SHIFT_DR
        UPDATE_DR -> if (tms == 1) SELECT_DR_SCAN else RUN_TEST_IDLE
        SELECT_IR_SCAN -> if (tms == 1) TEST_LOGIC_RESET else CAPTURE_IR
        CAPTURE_IR -> if (tms == 1) EXIT1_IR else SHIFT_IR
        EXIT1_IR -> if (tms == 1) UPDATE_IR else PAUSE_IR
        PAUSE_IR -> if (tms == 1) EXIT2_IR else PAUSE_IR
        EXIT2_IR -> if (tms == 1) UPDATE_IR else SHIFT_IR
        UPDATE_IR -> if (tms == 1) SELECT_DR_SCAN else RUN_TEST_IDLE
        UNKNOWN -> UNKNOWN
    }
    val isShift get() = this == SHIFT_IR || this == SHIFT_DR
}

/** One sampled TCK edge. Raw time representation is preserved. */
data class Edge(
    val index: Int,
    val time: Qty,
    val edgeKind: String,        // "rising" | "falling"
    val tms: Int, val tdi: Int, val tdo: Int,   // -1 = unknown ('x')
    val expect: String? = null   // optional decoder annotation from the capture
)

data class TraceStep(val edge: Edge, val state: TapState, val events: List<Finding> = emptyList())

data class ShiftSegment(
    val id: String,
    val isIr: Boolean,
    val startEdge: Int,
    val bits: List<Int>,        // TDI bits shifted in, first-first
    val tdoBits: List<Int>,     // TDO bits shifted out, first-out-first; -1 unknown
    val aborted: Boolean = false
)

data class TapTrace(val steps: List<TraceStep>, val shifts: List<ShiftSegment>, val findings: List<Finding>)

object TapAnalyzer {
    const val RESET_TMS_RUN = 5

    /** Rebuild the TAP trajectory. Mid-scan resets and illegal transitions are
     *  surfaced as findings and split the trace into segments; they are never
     *  silently skipped. */
    fun reconstruct(edges: List<Edge>): TapTrace {
        val steps = mutableListOf<TraceStep>()
        val findings = mutableListOf<Finding>()
        val shifts = mutableListOf<ShiftSegment>()
        var state = TapState.TEST_LOGIC_RESET
        var tmsRun = 0
        var seg = 0
        var curBits = mutableListOf<Int>()
        var curTdo = mutableListOf<Int>()
        var curStart = -1
        var curIsIr = false

        fun closeShift(aborted: Boolean) {
            if (curStart >= 0 && curBits.isNotEmpty())
                shifts += ShiftSegment("seg${seg++}", curIsIr, curStart, curBits.toList(), curTdo.toList(), aborted)
            curBits = mutableListOf(); curTdo = mutableListOf(); curStart = -1
        }

        for (e in edges) {
            val ev = mutableListOf<Finding>()
            tmsRun = if (e.tms == 1) tmsRun + 1 else 0
            if (tmsRun >= RESET_TMS_RUN && curStart >= 0) {
                val f = Finding("MID_SCAN_RESET", Severity.ERROR,
                    "reset (>=$RESET_TMS_RUN TMS=1) during active ${if (curIsIr) "IR" else "DR"} shift at edge ${e.index}; segment kept as aborted, not skipped",
                    "tap_trace#edge${e.index}")
                ev += f; findings += f
                closeShift(aborted = true)
            }
            val prev = state
            state = state.next(e.tms)
            if (tmsRun >= RESET_TMS_RUN) state = TapState.TEST_LOGIC_RESET

            if (e.expect != null) {
                val want = runCatching { TapState.valueOf(e.expect) }.getOrNull()
                if (want != null && want != state) {
                    val f = Finding("ILLEGAL_TRANSITION", Severity.ERROR,
                        "edge ${e.index}: capture annotation expects $want but TMS=${e.tms} drives $prev -> $state; flagged, not skipped",
                        "tap_trace#edge${e.index}")
                    ev += f; findings += f
                }
            }
            // a bit shifts on every edge that leaves a SHIFT state (stay or exit)
            if (prev.isShift) { curBits += e.tdi; curTdo += e.tdo }
            if (prev.isShift && state != prev) closeShift(aborted = false)
            if (state.isShift && curStart < 0) {
                curStart = e.index; curIsIr = state == TapState.SHIFT_IR
                curBits = mutableListOf(); curTdo = mutableListOf()
            }
            steps += TraceStep(e, state, ev)
        }
        if (curStart >= 0) closeShift(aborted = true)
        return TapTrace(steps, shifts, findings)
    }
}
