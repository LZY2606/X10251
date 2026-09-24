package gsb.tap

/** Machine-readable reconstruction event. These are the archival judgment records:
 *  illegal transitions and mid-scan resets are never silently skipped. */
data class TapEvent(
    val kind: String,
    val clock: Int,
    val detail: String,
    val affected: List<String> = emptyList(),
)

data class TraceStep(
    val clock: Int,
    val tms: Level, val tdi: Level, val tdo: Level,
    /** State after this clock; null = UNKNOWN (an illegal/undefined transition occurred). */
    val state: TapState?,
)

data class TraceResult(
    val steps: List<TraceStep>,
    val events: List<TapEvent>,
    /** Clock indices where a shift episode was aborted by reset/illegal transition. */
    val abortedShiftClocks: List<Int>,
)

object TapTracer {
    /** Reconstruct TAP states from active-edge samples.
     *  Semantics enforced here:
     *  - X/Z on TMS at an active edge => ILLEGAL_TRANSITION event, state becomes UNKNOWN.
     *  - TRST asserted (LOW) => ASYNC_RESET event; if a shift was in progress, MID_SCAN_RESET.
     *  - Entering TEST_LOGIC_RESET from SHIFT_IR/SHIFT_DR => MID_SCAN_RESET.
     *  - Recovery from UNKNOWN needs 5 consecutive TMS=1 (standard resync), logged as RESYNC. */
    fun trace(edges: List<EdgeSample>): TraceResult {
        val steps = mutableListOf<TraceStep>()
        val events = mutableListOf<TapEvent>()
        val aborted = mutableListOf<Int>()
        var state: TapState? = TapState.TEST_LOGIC_RESET
        var tmsHighRun = 0
        var inShift = false

        for (e in edges) {
            var stepState: TapState?
            if (e.trst == Level.LOW) {
                events += TapEvent("ASYNC_RESET", e.clock, "TRST asserted (active low)")
                if (inShift) {
                    events += TapEvent("MID_SCAN_RESET", e.clock, "asynchronous reset aborted an in-progress shift",
                        listOf("shift@${e.clock}"))
                    aborted += e.clock
                    inShift = false
                }
                stepState = TapState.TEST_LOGIC_RESET
                tmsHighRun = 0
            } else if (!e.tms.isDefined) {
                events += TapEvent("ILLEGAL_TRANSITION", e.clock,
                    "TMS sampled as ${e.tms} at active edge; transition undefined, state forced UNKNOWN",
                    listOf("clock:${e.clock}"))
                if (inShift) { aborted += e.clock; inShift = false }
                stepState = null
                tmsHighRun = 0
            } else if (state == null) {
                // resynchronisation: 5 consecutive TMS=1 guarantee TEST_LOGIC_RESET
                tmsHighRun = if (e.tms.bit) tmsHighRun + 1 else 0
                if (tmsHighRun >= 5) {
                    stepState = TapState.TEST_LOGIC_RESET
                    events += TapEvent("RESYNC", e.clock, "resynchronised to TEST_LOGIC_RESET after 5 TMS=1")
                } else stepState = null
            } else {
                val next = TapState.next(state, e.tms.bit)
                if (next == TapState.TEST_LOGIC_RESET && inShift) {
                    events += TapEvent("MID_SCAN_RESET", e.clock,
                        "TMS sequence forced TEST_LOGIC_RESET while a shift was in progress",
                        listOf("shift@${e.clock}"))
                    aborted += e.clock
                    inShift = false
                }
                stepState = next
                tmsHighRun = if (e.tms.bit) tmsHighRun + 1 else 0
            }
            if (stepState == TapState.SHIFT_IR || stepState == TapState.SHIFT_DR) inShift = true
            else if (stepState != null && stepState != TapState.PAUSE_IR && stepState != TapState.PAUSE_DR &&
                stepState != TapState.EXIT1_IR && stepState != TapState.EXIT2_IR &&
                stepState != TapState.EXIT1_DR && stepState != TapState.EXIT2_DR) {
                if (stepState == TapState.UPDATE_IR || stepState == TapState.UPDATE_DR ||
                    stepState == TapState.RUN_TEST_IDLE || stepState == TapState.CAPTURE_DR ||
                    stepState == TapState.CAPTURE_IR) inShift = false
            }
            steps += TraceStep(e.clock, e.tms, e.tdi, e.tdo, stepState)
            state = stepState
        }
        return TraceResult(steps, events, aborted)
    }
}
