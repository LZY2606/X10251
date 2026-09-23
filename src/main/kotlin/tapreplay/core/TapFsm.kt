package tapreplay.core

/**
 * IEEE 1149.1 TAP 控制器状态转移规则。
 *
 * 语义保证：
 *  - 非法/未知 TMS（X/Z/空）绝不静默跳过：产生显式 ERROR 事件并进入 UNKNOWN，
 *    直到一次被复位源证据支持的 TEST_LOGIC_RESET 才能恢复。
 *  - 复位源（nTRST/上电/TMS=1 五次）分开判定：
 *    * 五次 TMS=1 的自复位合法；
 *    * 一次 TMS=1 却伴随“nTRST 已断言”的复位事件属于与采样矛盾的非法复位，
 *      不得静默吃掉。
 */
object TapFsm {

    /** 标准状态表；UNKNOWN 不参与标准转移。 */
    fun next(state: TapState, tms: Int): TapState = when (state) {
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
        TapState.UNKNOWN -> TapState.UNKNOWN
    }

    fun isStandard(s: TapState): Boolean = s != TapState.UNKNOWN
}
