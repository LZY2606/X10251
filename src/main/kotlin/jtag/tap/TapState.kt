package jtag.tap

/** IEEE 1149.1 TAP 16 状态机。 */
enum class TapState {
    TEST_LOGIC_RESET, RUN_TEST_IDLE,
    SELECT_DR_SCAN, CAPTURE_DR, SHIFT_DR, EXIT1_DR, PAUSE_DR, EXIT2_DR, UPDATE_DR,
    SELECT_IR_SCAN, CAPTURE_IR, SHIFT_IR, EXIT1_IR, PAUSE_IR, EXIT2_IR, UPDATE_IR;

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
    }

    /** 移位族状态：TDO 在这些状态之间变化属于正常协议行为。 */
    val isShiftFamily: Boolean
        get() = this == SHIFT_DR || this == SHIFT_IR || this == CAPTURE_DR || this == CAPTURE_IR ||
                this == EXIT1_DR || this == EXIT1_IR || this == EXIT2_DR || this == EXIT2_IR
    val isShift: Boolean get() = this == SHIFT_DR || this == SHIFT_IR
    val isPause: Boolean get() = this == PAUSE_DR || this == PAUSE_IR
}
