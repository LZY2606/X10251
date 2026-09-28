package jtap.engine

import jtap.domain.*

object Rules {
    const val ILLEGAL_SAMPLE = "R-TAP-001"   // 非法采样/非法转移必须显式记录
    const val MID_RESET = "R-TAP-002"       // 中途复位不得静默跳过
    const val IR_IDCODE_ONLY = "R-IR-001"   // IR 长度不可仅靠 IDCODE 猜定
    const val IR_AMBIGUITY = "R-IR-002"     // 无法唯一决定时保留全部合法候选
    const val MISSING_OFFSET = "R-CHAIN-001" // 链上缺件保留偏移候选
    const val POLARITY = "R-POL-001"        // 采样极性冲突保留双解
    const val MAPPING = "R-BSDL-001"        // 映射版本变更
}

class ReplayContext(val trace: SampleTrace, val config: SessionConfig) {
    val events = mutableListOf<ReplayEvent>()
    val streams = mutableListOf<ShiftStream>()
    val resets = mutableListOf<ResetRecord>()
    var segment = 0
    private var seq = 0
    fun nextId(prefix: String) = "$prefix:${trace.id}:${seq++}"
}

/**
 * TAP 重放：严格逐边沿推进 16 态状态机。
 * - 非法采样（TMS/TDI/TDO 非 0/1/X/Z、TCK 无法解释）→ UNKNOWN，事件 ERROR，不静默跳过；
 * - TRST 或在 UNKNOWN 外由 TMS 进入 TLR 均产生独立复位记录；移位途中的复位标记 midStream；
 * - UNKNOWN 只有 TRST（硬复位）能退出，TMS 在 UNKNOWN 上不做“正常转移”。
 */
object TapReplay {

    private val next: Map<TapState, Pair<TapState, TapState>> = mapOf(
        TapState.TLR to (TapState.IDLE to TapState.TLR),
        TapState.IDLE to (TapState.SELECT_DR to TapState.IDLE),
        TapState.SELECT_DR to (TapState.CAPTURE_DR to TapState.SELECT_IR),
        TapState.CAPTURE_DR to (TapState.SHIFT_DR to TapState.EXIT1_DR),
        TapState.SHIFT_DR to (TapState.SHIFT_DR to TapState.EXIT1_DR),
        TapState.EXIT1_DR to (TapState.PAUSE_DR to TapState.UPDATE_DR),
        TapState.PAUSE_DR to (TapState.PAUSE_DR to TapState.EXIT2_DR),
        TapState.EXIT2_DR to (TapState.SHIFT_DR to TapState.UPDATE_DR),
        TapState.UPDATE_DR to (TapState.IDLE to TapState.SELECT_DR),
        TapState.SELECT_IR to (TapState.CAPTURE_IR to TapState.TLR),
        TapState.CAPTURE_IR to (TapState.SHIFT_IR to TapState.EXIT1_IR),
        TapState.SHIFT_IR to (TapState.SHIFT_IR to TapState.EXIT1_IR),
        TapState.EXIT1_IR to (TapState.PAUSE_IR to TapState.UPDATE_IR),
        TapState.PAUSE_IR to (TapState.PAUSE_IR to TapState.EXIT2_IR),
        TapState.EXIT2_IR to (TapState.SHIFT_IR to TapState.UPDATE_IR),
        TapState.UPDATE_IR to (TapState.IDLE to TapState.SELECT_IR)
    )

    fun replay(trace: SampleTrace, config: SessionConfig): ReplayResult {
        val ctx = ReplayContext(trace, config)
        // 上电复位：进入重放前 TAP 处于 TLR 的基线事实。
        ctx.resets += ResetRecord(
            id = ctx.nextId("reset"), traceId = trace.id, segment = 0, kind = "POWER_ON",
            edgeIndexFrom = null, edgeIndexTo = trace.edges.firstOrNull()?.index,
            midStream = false, abortedStreamId = null,
            basis = "上电默认 Test-Logic-Reset（IEEE 1149.1 TAP 初始态）"
        )

        var state = TapState.TLR
        var openStream: ShiftStreamBuilder? = null
        var prevTrst = Bit.ONE
        var tmsHighRun = 0

        for (edge in trace.edges) {
            val flags = mutableListOf<String>()

            // 1) 硬复位 TRST：1→0 断言（低有效），任何状态都直接回 TLR，包括 UNKNOWN。
            val trst = edge.trstBit ?: Bit.ONE
            val hardReset = prevTrst == Bit.ONE && (trst == Bit.ZERO)
            prevTrst = trst

            // 2) 非法采样：信号无法解释（X/Z 的 TMS 也不可驱动状态机）。
            if (edge.hasIllegalSample || edge.tmsBit == null || edge.tdiBit == null || edge.tdoBit == null) {
                state = enterIllegal(ctx, edge, state, openStream)
                openStream = null
                continue
            }
            if (state == TapState.UNKNOWN) {
                // UNKNOWN 下的每个正常边沿仍显式记录为“未恢复”，直到硬复位。
                if (!hardReset) {
                    ctx.events += ReplayEvent(
                        id = ctx.nextId("evt"), code = "ILLEGAL_TRANSITION", severity = Severity.ANOMALY,
                        ruleId = Rules.ILLEGAL_SAMPLE, traceId = trace.id, edgeIndex = edge.index,
                        message = "UNKNOWN 状态下 TMS=${edge.tms} 不构成合法 TAP 转移，保持 UNKNOWN（等待 TRST）",
                        affected = listOf("trajectory:${trace.id}"),
                        basis = "16 态 TAP 仅在已定义状态间转移；UNKNOWN 只能由硬复位清除"
                    )
                    continue
                }
            }

            if (hardReset) {
                val aborted = openStream
                state = applyReset(ctx, edge, state, "TRST", aborted)
                openStream = null
                continue
            }

            val tms = edge.tmsBit!!
            val from = state
            val target = next[state]!!.let { if (tms == Bit.ONE) it.second else it.first }

            // 进入 capture：开流（capture 边沿记录 TDO，但 capture 出来的位在首个 shift 边沿读出）
            if (target == TapState.CAPTURE_DR || target == TapState.CAPTURE_IR) {
                openStream = ShiftStreamBuilder(
                    id = ctx.nextId("stream"),
                    kind = if (target == TapState.CAPTURE_IR) StreamKind.IR else StreamKind.DR,
                    captureEdge = edge.index, segment = ctx.segment
                )
                flags += "capture"
            }

            // shift 边沿：采样 (TDI 入, TDO 出)
            val effectiveTdo = applyPolarity(edge.tdoBit!!, config.tdoPolarityInverted)
            if (from == TapState.SHIFT_DR || from == TapState.SHIFT_IR) {
                val b = openStream ?: ShiftStreamBuilder(
                    id = ctx.nextId("stream"),
                    kind = if (from == TapState.SHIFT_IR) StreamKind.IR else StreamKind.DR,
                    captureEdge = edge.index, segment = ctx.segment
                )
                b.tdi += edge.tdiBit!!
                b.tdo += effectiveTdo
                b.firstShiftEdge = b.firstShiftEdge ?: edge.index
                openStream = b
                flags += "shift"
                if (tms == Bit.ONE) flags += "last-bit"
            }

            // 离开 SHIFT：若直接进入 update（标准路径）则正常完成；异常退出也完成记录
            if ((from == TapState.SHIFT_DR || from == TapState.SHIFT_IR)) {
                if (target != TapState.SHIFT_DR && target != TapState.SHIFT_IR) {
                    openStream?.let { closeStream(ctx, it, edge.index, completed = true) }
                    openStream = null
                }
            }

            // TMS 进入 TLR（软复位）。计数连续 TMS=1 仅用于诊断，不用于 UNKNOWN 恢复。
            if (tms == Bit.ONE) tmsHighRun++ else tmsHighRun = 0
            if (target == TapState.TLR && from != TapState.TLR) {
                val aborted = openStream?.takeIf { it.tdi.isNotEmpty() && from != TapState.SHIFT_DR && from != TapState.SHIFT_IR }
                state = applyReset(ctx, edge, from, "TMS_TLR", aborted)
                openStream = null
                // 复位后该边沿本身停在 TLR
                ctx.steps += TapStep(edge.index, from, TapState.TLR, tms, edge.tdiBit, edge.tdoBit, effectiveTdo, flags + "soft-reset")
                continue
            }

            state = target
            ctx.steps += TapStep(edge.index, from, target, tms, edge.tdiBit, edge.tdoBit, effectiveTdo, flags)
        }

        openStream?.let {
            closeStream(ctx, it, trace.edges.lastOrNull()?.index ?: -1, completed = false)
            ctx.events += ReplayEvent(
                id = ctx.nextId("evt"), code = "SHIFT_ABORTED", severity = Severity.WARNING,
                ruleId = Rules.MID_RESET, traceId = trace.id, edgeIndex = it.firstShiftEdge,
                message = "移位流 ${it.id} 未走 Update-* 即结束（可能被中途打断）",
                affected = listOf(it.id),
                basis = "未闭合的 ShiftDR/ShiftIR 路径"
            )
        }

        val options = polarityOptions(ctx.streams)
        return ReplayResult(
            traceId = trace.id, configId = config.id,
            steps = ctx.steps, streams = ctx.streams, resets = ctx.resets,
            events = ctx.events, polarityOptions = options,
            effectiveTdoInverted = config.tdoPolarityInverted,
            endedInUnknown = state == TapState.UNKNOWN,
            fingerprints = mapOf(
                "trace" to Fingerprints.trace(trace),
                "config" to Fingerprints.config(config)
            )
        )
    }

    private fun applyPolarity(bit: Bit, inverted: Boolean): Bit = when {
        !inverted -> bit
        bit == Bit.ZERO -> Bit.ONE
        bit == Bit.ONE -> Bit.ZERO
        else -> bit
    }

    private fun enterIllegal(
        ctx: ReplayContext, edge: RawEdge, state: TapState, openStream: ShiftStreamBuilder?
    ): TapState {
        val detail = edge.diagnostics.firstOrNull() ?: "非法 TMS 采样"
        if (openStream != null && openStream.tdi.isNotEmpty()) {
            closeStream(ctx, openStream, edge.index, completed = false)
            ctx.events += ReplayEvent(
                id = ctx.nextId("evt"), code = "SHIFT_ABORTED", severity = Severity.ANOMALY,
                ruleId = Rules.MID_RESET, traceId = ctx.trace.id, edgeIndex = edge.index,
                message = "非法采样使移位流 ${openStream.id} 中断",
                affected = listOf(openStream.id),
                basis = detail
            )
        }
        ctx.events += ReplayEvent(
            id = ctx.nextId("evt"), code = "ILLEGAL_TRANSITION", severity = Severity.ERROR,
            ruleId = Rules.ILLEGAL_SAMPLE, traceId = ctx.trace.id, edgeIndex = edge.index,
            message = "边沿 ${edge.index} 存在非法采样（$detail），TAP 进入 UNKNOWN，转移被拒绝而非跳过",
            affected = listOf("trajectory:${ctx.trace.id}", "edge:${edge.index}"),
            basis = "TMS/TDI/TDO 必须为 0/1；X/Z 不能驱动确定性状态转移"
        )
        return TapState.UNKNOWN
    }

    private fun applyReset(
        ctx: ReplayContext, edge: RawEdge, from: TapState, kind: String, aborted: ShiftStreamBuilder?
    ): TapState {
        if (aborted != null) closeStream(ctx, aborted, edge.index, completed = false)
        val mid = aborted != null
        ctx.segment += 1
        ctx.resets += ResetRecord(
            id = ctx.nextId("reset"), traceId = ctx.trace.id, segment = ctx.segment, kind = kind,
            edgeIndexFrom = edge.index, edgeIndexTo = edge.index, midStream = mid,
            abortedStreamId = aborted?.id,
            basis = when (kind) {
                "TRST" -> "TRST 断言（低有效），异步强制 Test-Logic-Reset"
                else -> "TMS=1 经 Select-IR 进入 Test-Logic-Reset"
            }
        )
        ctx.events += ReplayEvent(
            id = ctx.nextId("evt"), code = if (mid) "MID_STREAM_RESET" else "RESET",
            severity = if (mid) Severity.WARNING else Severity.INFO,
            ruleId = Rules.MID_RESET, traceId = ctx.trace.id, edgeIndex = edge.index,
            message = if (mid) "中途复位（$kind）于边沿 ${edge.index}，移位流 ${aborted?.id} 被中止，前后分段保留"
                      else "$kind 复位于边沿 ${edge.index}（$from → TLR）",
            affected = listOfNotNull(aborted?.id, "trajectory:${ctx.trace.id}"),
            basis = "复位事件独立记录，不并入普通转移"
        )
        return TapState.TLR
    }

    private fun closeStream(ctx: ReplayContext, b: ShiftStreamBuilder, lastEdge: Int, completed: Boolean) {
        val exists = ctx.streams.any { it.id == b.id }
        if (!exists) ctx.streams += ShiftStream(
            id = b.id, traceId = ctx.trace.id, segment = b.segment, kind = b.kind,
            captureEdge = b.captureEdge, firstShiftEdge = b.firstShiftEdge ?: b.captureEdge,
            lastEdge = lastEdge, tdiBits = b.tdi, tdoBits = b.tdo, completed = completed,
            abortedByResetEdge = if (!completed) lastEdge else null
        )
    }

    /**
     * 采样极性：若声明极性下没有任何 32 位 TDO 段像 IDCODE（首位移出必为 1），
     * 而翻转后可以，则给出双候选。不静默改写，只做证据并列。
     */
    private fun polarityOptions(streams: List<ShiftStream>): List<PolarityOption> {
        fun matches(inverted: Boolean): Int = streams.filter { it.kind == StreamKind.DR && it.tdoBits.size >= 32 }
            .map { seg -> seg.tdoBits.map { b -> if (inverted) invert(b) else b } }
            .count { it.firstOrNull() == Bit.ONE }
        val normal = matches(false)
        val flipped = matches(true)
        val list = mutableListOf(PolarityOption("normal(TDO 同相)", false, normal),
            PolarityOption("inverted(TDO 反相)", true, flipped))
        return list
    }

    private fun invert(b: Bit) = when (b) { Bit.ZERO -> Bit.ONE; Bit.ONE -> Bit.ZERO; else -> b }

    private class ShiftStreamBuilder(
        val id: String, val kind: StreamKind, val captureEdge: Int, val segment: Int
    ) {
        val tdi = mutableListOf<Bit>()
        val tdo = mutableListOf<Bit>()
        var firstShiftEdge: Int? = null
    }
}

object Fingerprints {
    private fun sha(s: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }
    fun trace(t: SampleTrace) = sha("${t.id}|${t.version}|${t.rawText}")
    fun config(c: SessionConfig) = sha("${c.id}|${c.version}|${c.sampleEdge}|${c.tdoPolarityInverted}")
    fun raw(id: String, version: Int, raw: String) = sha("$id|$version|$raw")
}
