package jtag.core

/** IEEE 1149.1 TAP controller states. */
enum class TapState {
    TEST_LOGIC_RESET, RUN_TEST_IDLE,
    SELECT_DR_SCAN, CAPTURE_DR, SHIFT_DR, EXIT1_DR, PAUSE_DR, EXIT2_DR, UPDATE_DR,
    SELECT_IR_SCAN, CAPTURE_IR, SHIFT_IR, EXIT1_IR, PAUSE_IR, EXIT2_IR, UPDATE_IR;

    val isShift get() = this == SHIFT_DR || this == SHIFT_IR
    val isDrPath get() = ordinal in SELECT_DR_SCAN.ordinal..UPDATE_DR.ordinal
    val isIrPath get() = ordinal in SELECT_IR_SCAN.ordinal..UPDATE_IR.ordinal
}

/** Standard TAP state transition table: next(state, tms). */
fun tapNext(state: TapState, tms: Int): TapState = when (state) {
    TapState.TEST_LOGIC_RESET -> if (tms == 1) TapState.TEST_LOGIC_RESET else TapState.RUN_TEST_IDLE
    TapState.RUN_TEST_IDLE -> if (tms == 1) TapState.SELECT_DR_SCAN else TapState.RUN_TEST_IDLE
    TapState.SELECT_DR_SCAN -> if (tms == 1) TapState.SELECT_IR_SCAN else TapState.CAPTURE_DR
    TapState.CAPTURE_DR -> if (tms == 1) TapState.EXIT1_DR else TapState.SHIFT_DR
    TapState.EXIT1_DR -> if (tms == 1) TapState.UPDATE_DR else TapState.PAUSE_DR
    TapState.SHIFT_DR -> if (tms == 1) TapState.EXIT1_DR else TapState.SHIFT_DR
    TapState.PAUSE_DR -> if (tms == 1) TapState.EXIT2_DR else TapState.PAUSE_DR
    TapState.EXIT2_DR -> if (tms == 1) TapState.UPDATE_DR else TapState.SHIFT_DR
    TapState.UPDATE_DR -> if (tms == 1) TapState.SELECT_DR_SCAN else TapState.RUN_TEST_IDLE
    TapState.SELECT_IR_SCAN -> if (tms == 1) TapState.TEST_LOGIC_RESET else TapState.CAPTURE_IR
    TapState.CAPTURE_IR -> if (tms == 1) TapState.EXIT1_IR else TapState.SHIFT_IR
    TapState.SHIFT_IR -> if (tms == 1) TapState.EXIT1_IR else TapState.SHIFT_IR
    TapState.EXIT1_IR -> if (tms == 1) TapState.UPDATE_IR else TapState.PAUSE_IR
    TapState.PAUSE_IR -> if (tms == 1) TapState.EXIT2_IR else TapState.PAUSE_IR
    TapState.EXIT2_IR -> if (tms == 1) TapState.UPDATE_IR else TapState.SHIFT_IR
    TapState.UPDATE_IR -> if (tms == 1) TapState.SELECT_DR_SCAN else TapState.RUN_TEST_IDLE
}

/** Rule identifiers with explicit versions, recorded on every derivation. */
object Rules {
    const val TAP_FSM = "tap-fsm/1.1"
    const val IR_RESOLUTION = "ir-resolution/1.2"
    const val CHAIN_TOPOLOGY = "chain-topology/1.1"
    const val CELL_PIN_MAP = "cell-pin-map/1.0"
    const val SAMPLE_NORMALIZE = "sample-normalize/1.1"
}
