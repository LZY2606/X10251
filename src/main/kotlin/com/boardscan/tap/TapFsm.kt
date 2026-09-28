package com.boardscan.tap

import com.boardscan.model.*

/** IEEE 1149.1 TAP 控制器的 16 个状态。 */
enum class TapState {
    TLR, RTI, SELECT_DR_SCAN, CAPTURE_DR, SHIFT_DR, EXIT1_DR, PAUSE_DR, EXIT2_DR, UPDATE_DR,
    SELECT_IR_SCAN, CAPTURE_IR, SHIFT_IR, EXIT1_IR, PAUSE_IR, EXIT2_IR, UPDATE_IR,
    UNKNOWN
}

object TapFsm {
    fun next(state: TapState, tms: Boolean): TapState = when (state) {
        TapState.TLR -> if (tms) TapState.TLR else TapState.RTI
        TapState.RTI -> if (tms) TapState.SELECT_DR_SCAN else TapState.RTI
        TapState.SELECT_DR_SCAN -> if (tms) TapState.SELECT_IR_SCAN else TapState.CAPTURE_DR
        TapState.CAPTURE_DR -> if (tms) TapState.EXIT1_DR else TapState.SHIFT_DR
        TapState.SHIFT_DR -> if (tms) TapState.EXIT1_DR else TapState.SHIFT_DR
        TapState.EXIT1_DR -> if (tms) TapState.UPDATE_DR else TapState.PAUSE_DR
        TapState.PAUSE_DR -> if (tms) TapState.EXIT2_DR else TapState.PAUSE_DR
        TapState.EXIT2_DR -> if (tms) TapState.UPDATE_DR else TapState.SHIFT_DR
        TapState.UPDATE_DR -> if (tms) TapState.SELECT_DR_SCAN else TapState.RTI
        TapState.SELECT_IR_SCAN -> if (tms) TapState.TLR else TapState.CAPTURE_IR
        TapState.CAPTURE_IR -> if (tms) TapState.EXIT1_IR else TapState.SHIFT_IR
        TapState.SHIFT_IR -> if (tms) TapState.EXIT1_IR else TapState.SHIFT_IR
        TapState.EXIT1_IR -> if (tms) TapState.UPDATE_IR else TapState.PAUSE_IR
        TapState.PAUSE_IR -> if (tms) TapState.EXIT2_IR else TapState.PAUSE_IR
        TapState.EXIT2_IR -> if (tms) TapState.UPDATE_IR else TapState.SHIFT_IR
        TapState.UPDATE_IR -> if (tms) TapState.SELECT_DR_SCAN else TapState.RTI
        TapState.UNKNOWN -> TapState.UNKNOWN
    }
}

@kotlinx.serialization.Serializable
data class TapStep(
    val edgeId: String,
    val order: Long,
    val fromState: TapState,
    val tms: LogicLevel,
    val tdi: LogicLevel,
    val tdo: LogicLevel,
    val toState: TapState,
    val established: Boolean,
)

data class TapReplayResult(
    val steps: List<TapStep>,
    val findings: List<Finding>,
    val resetEdges: List<String>,
    val establishedAt: String?,
)

/**
 * 从沿序列重建 TAP 轨迹。
 *
 * 语义（不可静默跳过）：
 *  - 信号为 X 时，FSM 无法唯一推进：toState=UNKNOWN，产生 ILLEGAL_TAP_TRANSITION。
 *  - UNKNOWN 之后不猜测，直到一次进入 TLR 的复位序列重建已知状态。
 *  - 任何进入 TLR 的沿都记为复位；若进入前沿处于 SHIFT_DR/SHIFT_IR，则记 MID_SCAN_RESET。
 *  - 连续 TMS=1 计数 >=5 是 TLR 保持/复位序列证据；SELECT_IR_SCAN + TMS=1 也是进入 TLR。
 */
class TapReplayer(private val runId: String, private val branchLabel: String) {
    private var seq = 0L

    fun replay(edges: List<DecodedEdge>): TapReplayResult {
        val steps = mutableListOf<TapStep>()
        val findings = mutableListOf<Finding>()
        val resetEdges = mutableListOf<String>()
        var state: TapState = TapState.UNKNOWN
        var tmsOneRun = 0
        var establishedAt: String? = null
        var scanDepth = 0
        // openScan: SHIFT_x 经 EXIT1 离开但尚未 UPDATE_x；此时进 TLR 即中途复位。
        var openScan = false

        fun finding(
            code: FindingCode,
            severity: FindingSeverity,
            message: String,
            edgeId: String?,
            refs: Map<String, String> = emptyMap(),
        ): Finding {
            val f = Finding(
                findingId = "f-$runId-$branchLabel-${code.name.lowercase()}-$seq",
                code = code,
                severity = severity,
                message = message,
                runId = runId,
                branchLabel = branchLabel,
                edgeId = edgeId,
                refs = refs,
                seq = seq,
            )
            seq += 1
            findings += f
            return f
        }

        for (edge in edges) {
            val from = state
            val tmsLevel = edge.tms.level
            val midScan = from == TapState.SHIFT_DR || from == TapState.SHIFT_IR

            if (tmsLevel == LogicLevel.X || from == TapState.UNKNOWN) {
                if (tmsLevel == LogicLevel.X) {
                    finding(
                        FindingCode.ILLEGAL_TAP_TRANSITION,
                        FindingSeverity.ERROR,
                        "沿 ${edge.edgeId} 上 TMS 不可判定(原始值 '${edge.tms.raw}')，TAP 转移无法唯一确定；不做静默猜测。",
                        edge.edgeId,
                        mapOf("signal" to "TMS", "raw" to edge.tms.raw, "fromState" to from.name),
                    )
                } else if (from == TapState.UNKNOWN) {
                    finding(
                        FindingCode.UNKNOWN_INITIAL_STATE,
                        FindingSeverity.WARNING,
                        "沿 ${edge.edgeId} 上 TMS='${edge.tms.raw}' 时 TAP 仍处于 UNKNOWN，缺复位依据。",
                        edge.edgeId,
                    )
                }
                steps += TapStep(edge.edgeId, edge.order, from, tmsLevel, edge.tdi.level, edge.tdo.level,
                    TapState.UNKNOWN, establishedAt != null)
                state = TapState.UNKNOWN
                tmsOneRun = if (tmsLevel == LogicLevel.ONE) tmsOneRun + 1 else 0
                continue
            }

            val tms = tmsLevel == LogicLevel.ONE
            val to = TapFsm.next(from, tms)
            val enteringTlr = to == TapState.TLR && from != TapState.TLR

            if (tms) tmsOneRun += 1 else tmsOneRun = 0

            if (to == TapState.TLR) {
                resetEdges += edge.edgeId
                if (establishedAt == null) establishedAt = edge.edgeId
                if (openScan) {
                    finding(
                        FindingCode.MID_SCAN_RESET,
                        FindingSeverity.ERROR,
                        "沿 ${edge.edgeId} 从 ${from.name} 进入 TLR（连续 TMS=1 $tmsOneRun 个），" +
                            "距最近一次 SHIFT 未经过 UPDATE，属中途复位；未完成的移位片段保留为开放扫描。",
                        edge.edgeId,
                        mapOf("fromState" to from.name, "scanDepthBits" to scanDepth.toString(),
                            "tmsOneRun" to tmsOneRun.toString()),
                    )
                }
            }
            when {
                to == TapState.SHIFT_DR || to == TapState.SHIFT_IR -> {
                    openScan = false
                }
                from == TapState.SHIFT_DR || from == TapState.SHIFT_IR -> {
                    if (tms) openScan = true
                }
                to == TapState.UPDATE_DR || to == TapState.UPDATE_IR -> {
                    openScan = false
                }
            }

            steps += TapStep(edge.edgeId, edge.order, from, tmsLevel, edge.tdi.level, edge.tdo.level,
                to, establishedAt != null)
            state = to

            when (to) {
                TapState.SHIFT_DR, TapState.SHIFT_IR -> scanDepth += 1
                TapState.TLR, TapState.RTI -> scanDepth = 0
                else -> {}
            }
            if (to == TapState.TLR) openScan = false
        }
        return TapReplayResult(steps, findings, resetEdges, establishedAt)
    }
}
