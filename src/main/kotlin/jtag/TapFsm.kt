package jtag

/** IEEE 1149.1 TAP controller states. */
enum class TapState {
    RESET, IDLE,
    SELECT_DR, CAPTURE_DR, SHIFT_DR, EXIT1_DR, PAUSE_DR, EXIT2_DR, UPDATE_DR,
    SELECT_IR, CAPTURE_IR, SHIFT_IR, EXIT1_IR, PAUSE_IR, EXIT2_IR, UPDATE_IR
}

object TapFsm {
    private val table: Map<TapState, Pair<TapState, TapState>> = mapOf(
        TapState.RESET to (TapState.IDLE to TapState.RESET),
        TapState.IDLE to (TapState.IDLE to TapState.SELECT_DR),
        TapState.SELECT_DR to (TapState.CAPTURE_DR to TapState.SELECT_IR),
        TapState.CAPTURE_DR to (TapState.SHIFT_DR to TapState.EXIT1_DR),
        TapState.SHIFT_DR to (TapState.SHIFT_DR to TapState.EXIT1_DR),
        TapState.EXIT1_DR to (TapState.PAUSE_DR to TapState.UPDATE_DR),
        TapState.PAUSE_DR to (TapState.PAUSE_DR to TapState.EXIT2_DR),
        TapState.EXIT2_DR to (TapState.SHIFT_DR to TapState.UPDATE_DR),
        TapState.UPDATE_DR to (TapState.IDLE to TapState.SELECT_DR),
        TapState.SELECT_IR to (TapState.CAPTURE_IR to TapState.RESET),
        TapState.CAPTURE_IR to (TapState.SHIFT_IR to TapState.EXIT1_IR),
        TapState.SHIFT_IR to (TapState.SHIFT_IR to TapState.EXIT1_IR),
        TapState.EXIT1_IR to (TapState.PAUSE_IR to TapState.UPDATE_IR),
        TapState.PAUSE_IR to (TapState.PAUSE_IR to TapState.EXIT2_IR),
        TapState.EXIT2_IR to (TapState.SHIFT_IR to TapState.UPDATE_IR),
        TapState.UPDATE_IR to (TapState.IDLE to TapState.SELECT_DR),
    )

    /** tms: 0 or 1. */
    fun next(state: TapState, tms: Int): TapState {
        val pair = table.getValue(state)
        return if (tms == 0) pair.first else pair.second
    }

    /** Number of consecutive TMS=1 clocks that forces Test-Logic-Reset from any state. */
    const val RESET_TMS_COUNT = 5
}
