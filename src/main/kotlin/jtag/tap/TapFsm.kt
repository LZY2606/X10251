package jtag.tap

/** IEEE 1149.1 TAP controller states. */
enum class TapState {
    TEST_LOGIC_RESET, RUN_TEST_IDLE,
    SELECT_DR_SCAN, CAPTURE_DR, SHIFT_DR, EXIT1_DR, PAUSE_DR, EXIT2_DR, UPDATE_DR,
    SELECT_IR_SCAN, CAPTURE_IR, SHIFT_IR, EXIT1_IR, PAUSE_IR, EXIT2_IR, UPDATE_IR;

    /** TMS-driven next state. Null means the (state,tms) pair is not a legal FSM arc. */
    fun next(tms: Int): TapState? = when (this) {
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
    }
}
