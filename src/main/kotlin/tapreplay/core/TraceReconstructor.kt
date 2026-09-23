package tapreplay.core

/** 采样解码 + TAP 轨迹重建（在派生层规范化，导入层只保留原始表示）。 */
object TraceReconstructor {

    data class DecodedEdge(
        val edge: SampleEdge,
        val phase: String,      // RISING | FALLING | NONE
        val sampleTms: Int?,    // 在该相位采样到的 TMS（UNKNOWN 信号为 null）
        val sampleTdi: Int?,
        val sampleTdo: Int?,
    )

    /**
     * 按采样极性，在 TCK 边沿上采样。
     * RISING：在 tck 0->1 时取“当前”TMS（与该行同时被捕获）。
     * FALLING：在 1->0 时取“上一驱动”的 TMS（建模为上一记录行的值）。
     * 非二值 TCK/TMS 都登记诊断，绝不丢弃。
     */
    fun decode(source: SampleSource, events: MutableList<EngineEvent>, seq: String): List<DecodedEdge> {
        val wantRise = source.polarity != SamplePolarity.SAMPLE_ON_TCK_FALLING
        val out = mutableListOf<DecodedEdge>()
        var prev: SampleEdge? = null
        source.edges.forEach { e ->
            val cur = e.tck
            val prevV = prev?.tck
            val rise = prevV != null && cur != null && prevV == 0 && cur == 1
            val fall = prevV != null && cur != null && prevV == 1 && cur == 0
            val active = (wantRise && rise) || (!wantRise && fall)
            if (cur == null) {
                events += EngineEvent(
                    id = "evt-$seq-edge-${e.index}-tck",
                    type = "NON_BINARY_TCK",
                    severity = "WARN",
                    edgeId = e.id, edgeIndex = e.index,
                    message = "边沿 ${e.index} 的 TCK 原始表示 '${e.rawTck}' 非二值，该时刻无采样相位",
                    judgementBasis = mapOf("raw" to e.rawTck, "kept" to "true"),
                )
            }
            if (active) {
                val sampleEdge = if (wantRise) e else (prev ?: e)
                val phase = if (wantRise) "RISING" else "FALLING"
                if (sampleEdge.tms == null) {
                    events += EngineEvent(
                        id = "evt-$seq-edge-${e.index}-tmsx",
                        type = "ILLEGAL_TAP_TRANSITION",
                        severity = "ERROR",
                        edgeId = e.id, edgeIndex = e.index,
                        message = "在 $phase 采样相位遇到非二值 TMS '${sampleEdge.rawTms}'，TAP 下一状态不可定义",
                        judgementBasis = mapOf(
                            "rule" to "UNKNOWN_TMS_IS_NOT_A_LEGAL_IEEE1149.1_TRANSITION",
                            "rawTms" to sampleEdge.rawTms,
                        ),
                    )
                }
                out += DecodedEdge(sampleEdge, phase, sampleEdge.tms, sampleEdge.tdi, sampleEdge.tdo)
            }
            prev = e
        }
        if (out.isEmpty()) {
            events += EngineEvent(
                "evt-$seq-noactive", "NO_ACTIVE_SAMPLE_EDGE", "ERROR", null, null, null,
                "在给定极性 ${source.polarityRaw} 下没有任何活动 TCK 边沿；采样极性可能错误或波形损坏",
                mapOf("polarity" to source.polarity.name),
            )
        }
        return out
    }

    /**
     * 重建逐状态轨迹。复位事件与采样边沿是两类独立来源：
     *  - POWER_ON：合法，强制进入 TEST_LOGIC_RESET；
     *  - NTRST_ASSERTED 且与采样状态/五次 TMS 不一致：报 ERROR，不静默；
     *  - 五次 TMS=1 的标准自复位：合法；
     *  - UNKNOWN 后只有“被复位证据支持”的复位才能恢复。
     */
    fun reconstruct(
        decoded: List<DecodedEdge>,
        resets: List<ResetEvent>,
        branchId: String,
        events: MutableList<EngineEvent>,
    ): List<TraceStep> {
        val steps = mutableListOf<TraceStep>()
        var state = TapState.UNKNOWN
        var onesRun = 0
        var resetAppliedByPowerOn = false

        fun applyResetByEvidence(re: ResetEvent?, reason: String) {
            val before = state
            state = TapState.TEST_LOGIC_RESET
            onesRun = 0
            if (before != TapState.TEST_LOGIC_RESET) {
                steps += TraceStep(
                    edgeId = null, edgeIndex = re?.atEdgeIndex,
                    from = before, to = TapState.TEST_LOGIC_RESET,
                    tms = null, tdo = null, tdi = null,
                    samplePhase = "RESET_SOURCE",
                    annotation = reason,
                )
            }
        }

        resets.firstOrNull { it.kind == "POWER_ON" }?.let {
            applyResetByEvidence(it, "POWER_ON reset before active TCK")
            resetAppliedByPowerOn = true
        }
        if (!resetAppliedByPowerOn) state = TapState.UNKNOWN

        // 按边沿索引索引的异步复位
        val ntrstByEdge = resets.filter { it.kind == "NTRST_ASSERTED" }
            .mapNotNull { r -> r.atEdgeIndex?.let { it to r } }.toMap()

        decoded.forEach { d ->
            val e = d.edge
            // 先处理该边沿上声明的异步复位
            ntrstByEdge[e.index]?.let { re ->
                val consistent = state == TapState.TEST_LOGIC_RESET ||
                    (state.isShift && d.sampleTms == 1) || onesRun >= 4
                // nTRST 异步断言本身是合法硬件复位；但若与 TMS 轨迹描述的状态直接矛盾，
                // 例如在 TMS 显然把 TAP 留在 RTI 时却声称 nTRST 复位，属于证据冲突，必须显式报出。
                val contradictory = state == TapState.RUN_TEST_IDLE && onesRun == 0 &&
                    d.sampleTms == 0
                if (contradictory) {
                    events += EngineEvent(
                        "evt-$branchId-rst-${e.index}", "RESET_SAMPLE_CONTRADICTION", "ERROR",
                        e.id, e.index, re.id,
                        "边沿 ${e.index} 声明 nTRST 断言复位，但采样轨迹显示 TAP 处于 RUN_TEST_IDLE 且 TMS=0；复位与采样矛盾",
                        mapOf(
                            "rule" to "RESET_EVENT_MUST_BE_SUPPORTED_BY_SAMPLE_EVIDENCE",
                            "resetKind" to re.kind,
                            "state" to state.name,
                        ),
                    )
                    // 仍然应用复位（硬件证据优先），但不静默：上面已登记 ERROR。
                }
                applyResetByEvidence(re, "nTRST asserted @${e.index}")
            }

            val tms = d.sampleTms
            if (tms == null) {
                // 非二值 TMS：进入 UNKNOWN，保留这一步作为可见异常
                steps += TraceStep(e.id, e.index, state, TapState.UNKNOWN, null, d.sampleTdo, d.sampleTdi, d.phase,
                    "non-binary TMS: ${e.rawTms}")
                state = TapState.UNKNOWN
                onesRun = 0
                return@forEach
            }

            val from = state
            if (state == TapState.UNKNOWN) {
                // 仅允许“五次 TMS=1 自复位”恢复，其它转移保持 UNKNOWN
                if (tms == 1) {
                    onesRun++
                    steps += TraceStep(e.id, e.index, from, TapState.UNKNOWN, tms, d.sampleTdo, d.sampleTdi, d.phase,
                        "held TMS=1 while UNKNOWN ($onesRun/5)")
                    if (onesRun >= 5) {
                        state = TapState.TEST_LOGIC_RESET
                        steps += TraceStep(e.id, e.index, TapState.UNKNOWN, TapState.TEST_LOGIC_RESET, tms,
                            d.sampleTdo, d.sampleTdi, d.phase, "recovered via 5xTMS=1")
                    }
                } else {
                    onesRun = 0
                    steps += TraceStep(e.id, e.index, from, TapState.UNKNOWN, tms, d.sampleTdo, d.sampleTdi, d.phase,
                        "TMS=0 while UNKNOWN: state still undefined")
                }
                return@forEach
            }

            // 标准转移；同步检测“移位途中复位”
            val wasShift = state.isShift
            val to = TapFsm.next(state, tms)
            val midReset = wasShift && to == TapState.TEST_LOGIC_RESET
            steps += TraceStep(e.id, e.index, from, to, tms, d.sampleTdo, d.sampleTdi, d.phase,
                if (midReset) "mid-shift path to TEST_LOGIC_RESET" else null)
            if (midReset) {
                events += EngineEvent(
                    "evt-$branchId-midrst-${e.index}", "MID_SCAN_RESET", "WARN",
                    e.id, e.index, null,
                    "在 ${from.name} 移位过程中于边沿 ${e.index} 被带回 TEST_LOGIC_RESET；当前扫描被中止，已移入/移出位保留",
                    mapOf(
                        "rule" to "MID_SCAN_RESET_IS_RECORDED_NOT_SILENTLY_DROPPED",
                        "shiftState" to from.name,
                    ),
                )
            }

            // 五次 TMS=1 自复位检测（信息事件，便于复核）
            if (to == TapState.TEST_LOGIC_RESET) onesRun = 0
            else if (tms == 1) onesRun++ else onesRun = 0
            state = to
        }
        return steps
    }
}
