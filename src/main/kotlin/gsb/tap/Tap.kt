package gsb.tap

/** IEEE 1149.1 TAP controller states. */
enum class TapState {
    TEST_LOGIC_RESET, RUN_TEST_IDLE,
    SELECT_DR_SCAN, CAPTURE_DR, SHIFT_DR, EXIT1_DR, PAUSE_DR, EXIT2_DR, UPDATE_DR,
    SELECT_IR_SCAN, CAPTURE_IR, SHIFT_IR, EXIT1_IR, PAUSE_IR, EXIT2_IR, UPDATE_IR;

    companion object {
        /** Standard next-state table: NEXT[state][0] = TMS low, [1] = TMS high. */
        val NEXT: Map<TapState, Array<TapState>> = mapOf(
            TEST_LOGIC_RESET to arrayOf(RUN_TEST_IDLE, TEST_LOGIC_RESET),
            RUN_TEST_IDLE to arrayOf(RUN_TEST_IDLE, SELECT_DR_SCAN),
            SELECT_DR_SCAN to arrayOf(CAPTURE_DR, SELECT_IR_SCAN),
            CAPTURE_DR to arrayOf(SHIFT_DR, EXIT1_DR),
            SHIFT_DR to arrayOf(SHIFT_DR, EXIT1_DR),
            EXIT1_DR to arrayOf(PAUSE_DR, UPDATE_DR),
            PAUSE_DR to arrayOf(PAUSE_DR, EXIT2_DR),
            EXIT2_DR to arrayOf(SHIFT_DR, UPDATE_DR),
            UPDATE_DR to arrayOf(RUN_TEST_IDLE, SELECT_DR_SCAN),
            SELECT_IR_SCAN to arrayOf(CAPTURE_IR, TEST_LOGIC_RESET),
            CAPTURE_IR to arrayOf(SHIFT_IR, EXIT1_IR),
            SHIFT_IR to arrayOf(SHIFT_IR, EXIT1_IR),
            EXIT1_IR to arrayOf(PAUSE_IR, UPDATE_IR),
            PAUSE_IR to arrayOf(PAUSE_IR, EXIT2_IR),
            EXIT2_IR to arrayOf(SHIFT_IR, UPDATE_IR),
            UPDATE_IR to arrayOf(RUN_TEST_IDLE, SELECT_DR_SCAN),
        )

        fun next(state: TapState, tms: Boolean): TapState = NEXT.getValue(state)[if (tms) 1 else 0]
    }
}
