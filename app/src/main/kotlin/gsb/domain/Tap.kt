package gsb.domain

/** IEEE 1149.1 TAP controller state machine (16 states). */
enum class TapState {
    TEST_LOGIC_RESET, RUN_TEST_IDLE,
    SELECT_DR_SCAN, CAPTURE_DR, SHIFT_DR, EXIT1_DR, PAUSE_DR, EXIT2_DR, UPDATE_DR,
    SELECT_IR_SCAN, CAPTURE_IR, SHIFT_IR, EXIT1_IR, PAUSE_IR, EXIT2_IR, UPDATE_IR;

    val isShift: Boolean get() = this == SHIFT_DR || this == SHIFT_IR

    fun next(tms: Boolean): TapState = when (this) {
        TEST_LOGIC_RESET -> if (tms) TEST_LOGIC_RESET else RUN_TEST_IDLE
        RUN_TEST_IDLE -> if (tms) SELECT_DR_SCAN else RUN_TEST_IDLE
        SELECT_DR_SCAN -> if (tms) SELECT_IR_SCAN else CAPTURE_DR
        CAPTURE_DR -> if (tms) EXIT1_DR else SHIFT_DR
        SHIFT_DR -> if (tms) EXIT1_DR else SHIFT_DR
        EXIT1_DR -> if (tms) UPDATE_DR else PAUSE_DR
        PAUSE_DR -> if (tms) EXIT2_DR else PAUSE_DR
        EXIT2_DR -> if (tms) UPDATE_DR else SHIFT_DR
        UPDATE_DR -> if (tms) SELECT_DR_SCAN else RUN_TEST_IDLE
        SELECT_IR_SCAN -> if (tms) TEST_LOGIC_RESET else CAPTURE_IR
        CAPTURE_IR -> if (tms) EXIT1_IR else SHIFT_IR
        SHIFT_IR -> if (tms) EXIT1_IR else SHIFT_IR
        EXIT1_IR -> if (tms) UPDATE_IR else PAUSE_IR
        PAUSE_IR -> if (tms) EXIT2_IR else PAUSE_IR
        EXIT2_IR -> if (tms) UPDATE_IR else SHIFT_IR
        UPDATE_IR -> if (tms) SELECT_DR_SCAN else RUN_TEST_IDLE
    }
}
