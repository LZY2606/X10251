package jtap.core

import jtap.model.Quantity

/**
 * IEEE 1149.1 TAP controller state machine.
 *
 * The transition function itself is total: every (state, TMS) pair has a
 * defined successor in the standard. What must NEVER be silently skipped are
 * higher-level semantic anomalies, expressed explicitly in [TransitionKind]:
 *  - [TransitionKind.ILLEGAL_DECLARATION]: the import feed declared a
 *    transition that contradicts the 1149.1 table (corrupt/manipulated trace)
 *  - [TransitionKind.AMBIGUOUS_TMS]: the sampled TMS level is non-binary
 *    (X/Z), so the next state cannot be determined uniquely
 *  - [TransitionKind.MID_SCAN_RESET]: >=5 consecutive TMS=1 TCK edges occur
 *    while the controller is inside an IR/DR scan path (Capture/Shift/.../UpdateIR/DR).
 *  - [TransitionKind.MID_SHIFT_RESET]: a reset occurs while ShiftIR/ShiftDR
 *    still holds [shiftBitsAbandoned] queued bits, i.e. an aborted scan.
 */
enum class TapState(val stable: Boolean, val irScan: Boolean, val drScan: Boolean) {
    TEST_LOGIC_RESET(true, false, false),
    RUN_TEST_IDLE(true, false, false),
    SELECT_DR_SCAN(false, false, true),
    CAPTURE_DR(false, false, true),
    SHIFT_DR(false, false, true),
    EXIT1_DR(false, false, true),
    PAUSE_DR(false, false, true),
    EXIT2_DR(false, false, true),
    UPDATE_DR(true, false, true),
    SELECT_IR_SCAN(false, true, false),
    CAPTURE_IR(false, true, false),
    SHIFT_IR(false, true, false),
    EXIT1_IR(false, true, false),
    PAUSE_IR(false, true, false),
    EXIT2_IR(false, true, false),
    UPDATE_IR(true, true, false);

    val inDrPath: Boolean get() = drScan && this != SELECT_DR_SCAN
    val inIrPath: Boolean get() = irScan && this != SELECT_IR_SCAN
    val inAnyScanPath: Boolean get() = inIrPath || inDrPath
}

enum class TransitionKind { NORMAL, MID_SCAN_RESET, MID_SHIFT_RESET, ILLEGAL_DECLARATION, AMBIGUOUS_TMS }

data class TapTransition(
    val edgeIndex: Long,
    val at: Quantity,
    val from: TapState,
    val tmsRaw: String,
    val tckRaw: String,
    val tdiRaw: String,
    val tdoRaw: String,
    val to: TapState?,
    val kind: TransitionKind,
    val onesRunLength: Int,
    val shiftBitsAbandoned: Int,
    val diagnostic: String?,
)

class TapFsm(private val startState: TapState = TapState.TEST_LOGIC_RESET) {
    var state: TapState = startState
        private set

    /** Reconstruction stops being deterministic once an anomaly appears. */
    var deterministic: Boolean = true
        private set

    private var consecutiveOnes = 0
    private var shiftedInCurrentScan = 0

    fun step(
        edgeIndex: Long,
        at: Quantity,
        tmsRaw: String,
        tckRaw: String,
        tdiRaw: String,
        tdoRaw: String,
        declaredNext: TapState? = null,
    ): TapTransition {
        val tms = normalizeLevel(tmsRaw)
        var kind = TransitionKind.NORMAL
        var diagnostic: String? = null
        var abandoned = 0

        if (tms == null) {
            deterministic = false
            kind = TransitionKind.AMBIGUOUS_TMS
            diagnostic = "TMS level '$tmsRaw' is not binary at edge $edgeIndex; " +
                "all standard-compliant successor candidates are retained"
            val tr = TapTransition(edgeIndex, at, state, tmsRaw, tckRaw, tdiRaw, tdoRaw,
                null, kind, consecutiveOnes, shiftedInCurrentScan, diagnostic)
            return tr
        }

        val successor = next(state, tms)

        if (declaredNext != null && declaredNext != successor) {
            kind = TransitionKind.ILLEGAL_DECLARATION
            diagnostic = "feed declares TMS=$tms transition $state->$declaredNext but 1149.1 " +
                "requires $state->$successor at edge $edgeIndex"
        }

        if (tms == 1) consecutiveOnes += 1 else consecutiveOnes = 0

        if (state == TapState.SHIFT_IR || state == TapState.SHIFT_DR) {
            shiftedInCurrentScan += 1
        }

        if (successor == TapState.TEST_LOGIC_RESET && state != TapState.TEST_LOGIC_RESET) {
            if (state.inAnyScanPath) {
                kind = if (shiftedInCurrentScan > 0) TransitionKind.MID_SHIFT_RESET
                else TransitionKind.MID_SCAN_RESET
                abandoned = shiftedInCurrentScan
                if (diagnostic == null) {
                    diagnostic = "reset to Test-Logic-Reset after $consecutiveOnes TMS=1 edges " +
                        "from inside ${if (state.inIrPath) "IR" else "DR"} scan path at edge $edgeIndex" +
                        if (abandoned > 0) "; $abandoned shifted bits abandoned mid-scan" else ""
                }
            }
        }

        if (successor == TapState.TEST_LOGIC_RESET) shiftedInCurrentScan = 0
        if (state in listOf(TapState.SELECT_IR_SCAN, TapState.SELECT_DR_SCAN) &&
            successor in listOf(TapState.CAPTURE_IR, TapState.CAPTURE_DR)) {
            shiftedInCurrentScan = 0
        }

        state = successor
        if (kind == TransitionKind.ILLEGAL_DECLARATION) deterministic = false
        return TapTransition(edgeIndex, at, state, tmsRaw, tckRaw, tdiRaw, tdoRaw,
            successor, kind, consecutiveOnes, abandoned, diagnostic)
    }

    companion object {
        /** Standard IEEE 1149.1 Table 6-1. */
        fun next(s: TapState, tms: Int): TapState = when (s) {
            TapState.TEST_LOGIC_RESET -> if (tms == 0) TapState.RUN_TEST_IDLE else TapState.TEST_LOGIC_RESET
            TapState.RUN_TEST_IDLE -> if (tms == 0) TapState.RUN_TEST_IDLE else TapState.SELECT_DR_SCAN
            TapState.SELECT_DR_SCAN -> if (tms == 0) TapState.CAPTURE_DR else TapState.SELECT_IR_SCAN
            TapState.CAPTURE_DR -> if (tms == 0) TapState.SHIFT_DR else TapState.EXIT1_DR
            TapState.SHIFT_DR -> if (tms == 0) TapState.SHIFT_DR else TapState.EXIT1_DR
            TapState.EXIT1_DR -> if (tms == 0) TapState.PAUSE_DR else TapState.UPDATE_DR
            TapState.PAUSE_DR -> if (tms == 0) TapState.PAUSE_DR else TapState.EXIT2_DR
            TapState.EXIT2_DR -> if (tms == 0) TapState.SHIFT_DR else TapState.UPDATE_DR
            TapState.UPDATE_DR -> if (tms == 0) TapState.RUN_TEST_IDLE else TapState.SELECT_DR_SCAN
            TapState.SELECT_IR_SCAN -> if (tms == 0) TapState.CAPTURE_IR else TapState.TEST_LOGIC_RESET
            TapState.CAPTURE_IR -> if (tms == 0) TapState.SHIFT_IR else TapState.EXIT1_IR
            TapState.SHIFT_IR -> if (tms == 0) TapState.SHIFT_IR else TapState.EXIT1_IR
            TapState.EXIT1_IR -> if (tms == 0) TapState.PAUSE_IR else TapState.UPDATE_IR
            TapState.PAUSE_IR -> if (tms == 0) TapState.PAUSE_IR else TapState.EXIT2_IR
            TapState.EXIT2_IR -> if (tms == 0) TapState.SHIFT_IR else TapState.UPDATE_IR
            TapState.UPDATE_IR -> if (tms == 0) TapState.RUN_TEST_IDLE else TapState.SELECT_DR_SCAN
        }

        fun normalizeLevel(raw: String?): Int? = when (raw?.trim()?.uppercase()) {
            "0", "L", "LOW" -> 0
            "1", "H", "HIGH" -> 1
            else -> null
        }
    }
}
