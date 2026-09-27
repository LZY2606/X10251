package jtagreplay.tap

/** IEEE 1149.1 TAP 16 状态 */
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

    val isShift get() = this == SHIFT_DR || this == SHIFT_IR
}

/** 采样边沿（已按极性配置解释后的逻辑值） */
data class SampleEdge(
    val seq: Int,
    val edge: String,          // rising | falling —— 采样基准
    val tms: Int, val tdi: Int, val tdo: Int,
    val time: jtagreplay.units.Quantity? = null,
    val declaredState: String? = null  // 采集端附带的状态标注，用于非法转移检验
)

sealed class TapEvent {
    abstract val seq: Int
    /** 中途复位：移位/扫描途中出现复位，不得静默跳过 */
    data class MidStreamReset(override val seq: Int, val fromState: TapState, val resetKind: String) : TapEvent()
    /** 非法转移：采集标注状态与 FSM 推导不一致 */
    data class IllegalTransition(override val seq: Int, val expected: TapState, val declared: String) : TapEvent()
    /** 采样极性可疑：TDO 捕获模式与 1149.1 期望不符 */
    data class PolaritySuspect(override val seq: Int, val detail: String) : TapEvent()
}

data class TapTrace(
    val states: List<TapState>,   // states[i] = 第 i 条边沿之后的状态；states[0] 前为 TEST_LOGIC_RESET
    val events: List<TapEvent>
)

object TapReplayer {
    /**
     * 重放采样边沿。复位（5 个连续 TMS=1 或显式 TRST）产生事件且不被静默跳过；
     * 采集端 declaredState 与 FSM 推导不符时产生 IllegalTransition。
     */
    fun replay(edges: List<SampleEdge>, invertTms: Boolean = false, invertTdo: Boolean = false): TapTrace {
        val states = ArrayList<TapState>(edges.size)
        val events = ArrayList<TapEvent>()
        var state = TapState.TEST_LOGIC_RESET
        var tmsRun = 0
        var prevShiftState: TapState? = null
        edges.forEach { e ->
            val tms = if (invertTms) 1 - e.tms else e.tms
            if (tms == 1) tmsRun++ else tmsRun = 0
            val wasShift = state.isShift || state == TapState.PAUSE_DR || state == TapState.PAUSE_IR
            val next = state.next(tms)
            if (next == TapState.TEST_LOGIC_RESET && state != TapState.TEST_LOGIC_RESET) {
                val kind = if (tmsRun >= 5) "TMS_HELD_HIGH_5CK" else "TRST_OR_ANNOTATION"
                if (wasShift || prevShiftState != null) {
                    events.add(TapEvent.MidStreamReset(e.seq, state, kind))
                }
            }
            e.declaredState?.let { d ->
                val parsed = runCatching { TapState.valueOf(d) }.getOrNull()
                if (parsed == null || parsed != next) {
                    events.add(TapEvent.IllegalTransition(e.seq, next, d))
                }
            }
            if (next.isShift) prevShiftState = next
            if (next == TapState.UPDATE_DR || next == TapState.UPDATE_IR || next == TapState.TEST_LOGIC_RESET) {
                if (!next.isShift) prevShiftState = null
            }
            states.add(next)
            state = next
        }
        if (invertTdo) {
            events.add(TapEvent.PolaritySuspect(edges.firstOrNull()?.seq ?: 0, "TDO polarity inverted by configuration"))
        }
        return TapTrace(states, events)
    }
}
