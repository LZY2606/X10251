package jtag.core

/** IEEE 1149.1 TAP controller states. UNKNOWN models post-anomaly desync; it is
 *  never silently skipped: any transition out of UNKNOWN is reported illegal. */
enum class TapState {
    TEST_LOGIC_RESET, RUN_TEST_IDLE,
    SELECT_DR_SCAN, CAPTURE_DR, SHIFT_DR, EXIT1_DR, PAUSE_DR, EXIT2_DR, UPDATE_DR,
    SELECT_IR_SCAN, CAPTURE_IR, SHIFT_IR, EXIT1_IR, PAUSE_IR, EXIT2_IR, UPDATE_IR,
    UNKNOWN;

    val isShift get() = this == SHIFT_DR || this == SHIFT_IR
    val isMidOperation get() = this in setOf(
        SHIFT_DR, SHIFT_IR, PAUSE_DR, PAUSE_IR, EXIT1_DR, EXIT1_IR, EXIT2_DR, EXIT2_IR,
        CAPTURE_DR, CAPTURE_IR
    )
}

object TapFsm {
    private val table: Map<TapState, Pair<TapState, TapState>> = mapOf(
        TapState.TEST_LOGIC_RESET to (TapState.RUN_TEST_IDLE to TapState.TEST_LOGIC_RESET),
        TapState.RUN_TEST_IDLE to (TapState.RUN_TEST_IDLE to TapState.SELECT_DR_SCAN),
        TapState.SELECT_DR_SCAN to (TapState.CAPTURE_DR to TapState.SELECT_IR_SCAN),
        TapState.CAPTURE_DR to (TapState.SHIFT_DR to TapState.EXIT1_DR),
        TapState.SHIFT_DR to (TapState.SHIFT_DR to TapState.EXIT1_DR),
        TapState.EXIT1_DR to (TapState.PAUSE_DR to TapState.UPDATE_DR),
        TapState.PAUSE_DR to (TapState.PAUSE_DR to TapState.EXIT2_DR),
        TapState.EXIT2_DR to (TapState.SHIFT_DR to TapState.UPDATE_DR),
        TapState.UPDATE_DR to (TapState.RUN_TEST_IDLE to TapState.SELECT_DR_SCAN),
        TapState.SELECT_IR_SCAN to (TapState.CAPTURE_IR to TapState.TEST_LOGIC_RESET),
        TapState.CAPTURE_IR to (TapState.SHIFT_IR to TapState.EXIT1_IR),
        TapState.SHIFT_IR to (TapState.SHIFT_IR to TapState.EXIT1_IR),
        TapState.EXIT1_IR to (TapState.PAUSE_IR to TapState.UPDATE_IR),
        TapState.PAUSE_IR to (TapState.PAUSE_IR to TapState.EXIT2_IR),
        TapState.EXIT2_IR to (TapState.SHIFT_IR to TapState.UPDATE_IR),
        TapState.UPDATE_IR to (TapState.RUN_TEST_IDLE to TapState.SELECT_DR_SCAN),
    )

    /** null when no legal transition exists (e.g. leaving UNKNOWN). */
    fun next(state: TapState, tms: Int): TapState? =
        table[state]?.let { if (tms == 0) it.first else it.second }

    fun isLegal(from: TapState, to: TapState): Boolean =
        (0..1).any { tms -> next(from, tms) == to }
}
