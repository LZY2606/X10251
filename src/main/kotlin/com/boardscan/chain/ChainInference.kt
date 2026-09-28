package com.boardscan.chain

import com.boardscan.bsdl.*
import com.boardscan.model.*
import kotlinx.serialization.Serializable

@Serializable
data class ChainInferenceResult(
    val irCandidates: List<IrLengthCandidate>,
    val devices: List<ChainDevice>,
    val offsets: List<OffsetCandidate>,
    val cellEvidence: List<CellPinEvidence>,
    val findings: List<Finding>,
    val declaredTotalIrBits: Int?,
    val measuredTotalIrBits: Int?,
)

/**
 * 链重建。刻意保留多解与不确定：
 *  - IR 长度：BSDL 声明 + 扫描签名候选都保留；二者矛盾不掩盖。
 *  - 仅凭 IDCODE 推 IR 长度一律拒绝（IR_LENGTH_FROM_IDCODE_REJECTED）。
 *  - DR 总长与按器件相加不符 -> 缺件/偏移候选全部保留，不选单一答案。
 */
class ChainInference(
    private val runId: String,
    private val branchLabel: String,
) {
    private var seq = 0L
    private fun finding(
        code: FindingCode,
        severity: FindingSeverity,
        message: String,
        edgeId: String? = null,
        refs: Map<String, String> = emptyMap(),
    ): Finding = Finding(
        findingId = "f-$runId-$branchLabel-${code.name.lowercase()}-$seq",
        code = code, severity = severity, message = message,
        runId = runId, branchLabel = branchLabel, edgeId = edgeId, refs = refs, seq = seq++,
    )

    fun infer(
        scans: List<ScanSegment>,
        declaredChain: List<ChainDeviceDeclaration>,
        measuredIdcodeCount: Int?,
    ): ChainInferenceResult {
        val findings = mutableListOf<Finding>()
        val irCandidates = mutableListOf<IrLengthCandidate>()

        val sirSegments = scans.filter { it.reg == ScanReg.IR && it.completed }
        val sdrSegments = scans.filter { it.reg == ScanReg.DR }

        // 1) BSDL 声明候选（直接来源：BSDL 文件版本）。
        var declaredTotal = 0
        for (decl in declaredChain) {
            if (decl.irLengthBits > 0) {
                declaredTotal += decl.irLengthBits
                irCandidates += IrLengthCandidate(
                    candidateId = "irc-$runId-$branchLabel-bsdl-${decl.position}",
                    deviceRef = decl.name,
                    lengthBits = decl.irLengthBits,
                    source = IrCandidateSource.BSDL_DECLARED,
                    sourceRef = decl.bsdlId,
                    consistent = true,
                    note = "BSDL ${decl.bsdlVersion} INSTRUCTION_LENGTH",
                )
            }
        }

        // 2) 扫描签名候选。逐 SIR 段找所有满足 Capture-IR 01 尾模式的总 IR 长度。
        var measuredTotal: Int? = null
        val perSegmentSolutions = mutableListOf<List<Int>>()
        for (seg in sirSegments) {
            val tdo = seg.tdoBits
            val n = tdo.size
            val solutions = irLengthSolutions(tdo)
            perSegmentSolutions += solutions
            if (solutions.isEmpty()) {
                findings += finding(
                    FindingCode.IR_LENGTH_AMBIGUOUS, FindingSeverity.WARNING,
                    "IR 扫描段 ${seg.segmentId}（$n 位）未找到满足 Capture-IR '01' 尾模式的长度，" +
                        "TDO 前两位='${bitChar(tdo.getOrNull(0))}${bitChar(tdo.getOrNull(1))}'。",
                    seg.startEdgeId,
                    mapOf("segmentId" to seg.segmentId, "clockCount" to n.toString()),
                )
            } else {
                solutions.forEach { length ->
                    irCandidates += IrLengthCandidate(
                        candidateId = "irc-$runId-$branchLabel-sig-${seg.position}-$length",
                        deviceRef = "<chain-total>",
                        lengthBits = length,
                        source = IrCandidateSource.SCAN_SIGNATURE,
                        sourceRef = seg.segmentId,
                        consistent = true,
                        note = "段 ${seg.segmentId} 中长度 $length 满足尾 0 模式",
                    )
                }
            }
        }
        if (perSegmentSolutions.isNotEmpty()) {
            val common = perSegmentSolutions.drop(1).fold(perSegmentSolutions[0].toSet()) { acc, list ->
                acc intersect list.toSet()
            }
            if (common.size == 1) {
                measuredTotal = common.single()
            } else if (common.size > 1) {
                findings += finding(
                    FindingCode.IR_LENGTH_AMBIGUOUS, FindingSeverity.WARNING,
                    "跨 ${perSegmentSolutions.size} 个 IR 扫描段仍有多个合法总长度：${common.sorted()}，保留全部候选。",
                    refs = mapOf("candidates" to common.sorted().joinToString(","),
                        "perSegment" to perSegmentSolutions.joinToString("/") { it.joinToString("-") }),
                )
            }
        }

        // 3) 声明与测量交叉一致性。
        if (declaredTotal > 0 && measuredTotal != null && measuredTotal != declaredTotal) {
            findings += finding(
                FindingCode.CHAIN_LENGTH_MISMATCH, FindingSeverity.ERROR,
                "BSDL 声明总 IR 长度 $declaredTotal 位与扫描测量 $measuredTotal 位不一致，链上器件可能缺失或型号有误。",
                refs = mapOf("declaredBits" to "$declaredTotal", "measuredBits" to "$measuredTotal"),
            )
        }

        // 4) IDCODE 只能给出器件数量，不能定 IR 长度。
        if (measuredIdcodeCount != null) {
            findings += finding(
                FindingCode.IDCODE_DEVICE_COUNT, FindingSeverity.INFO,
                "IDCODE 扫描读出 $measuredIdcodeCount 个器件（含可能的 bypass 迹象），该证据仅用于器件计数。",
                refs = mapOf("deviceCount" to "$measuredIdcodeCount"),
            )
            findings += finding(
                FindingCode.IR_LENGTH_FROM_IDCODE_REJECTED, FindingSeverity.WARNING,
                "不得仅凭 IDCODE 猜定 IR 长度；IDCODE 不含 INSTRUCTION_LENGTH 信息。",
                refs = mapOf("deviceCount" to "$measuredIdcodeCount"),
            )
            irCandidates += IrLengthCandidate(
                candidateId = "irc-$runId-$branchLabel-idcode-rejected",
                deviceRef = "<chain-total>",
                lengthBits = -1,
                source = IrCandidateSource.IDCODE_ONLY_REJECTED,
                sourceRef = "idcode-scan",
                consistent = false,
                note = "占位拒绝记录：IDCODE 不能裁定 IR 长度",
            )
            if (measuredIdcodeCount != declaredChain.size && declaredChain.isNotEmpty()) {
                findings += finding(
                    FindingCode.MISSING_CHAIN_DEVICE, FindingSeverity.ERROR,
                    "IDCODE 器件数 $measuredIdcodeCount 与 BSDL 声明器件数 ${declaredChain.size} 不符，按缺件保留偏移候选。",
                    refs = mapOf("measured" to "$measuredIdcodeCount",
                        "declared" to "${declaredChain.size}"),
                )
            }
        }

        // 5) 重建器件与 DR 长度；bypass 器件 DR=1。
        val devices = mutableListOf<ChainDevice>()
        var declaredDrTotal = 0
        for ((index, decl) in declaredChain.withIndex()) {
            val instrLen = if (decl.irLengthBits > 0) decl.irLengthBits else null
            val drLen = when {
                decl.instruction.equals("BYPASS", true) -> 1
                decl.boundaryLengthBits >= 0 -> decl.boundaryLengthBits
                else -> 0
            }
            declaredDrTotal += drLen
            devices += ChainDevice(
                deviceId = "dev-$runId-$branchLabel-${decl.position}",
                position = decl.position,
                bsdlId = decl.bsdlId,
                bsdlVersion = decl.bsdlVersion,
                name = decl.name,
                idcode = decl.idcode,
                irLengthBits = instrLen,
                instruction = decl.instruction.ifEmpty { "UNKNOWN" },
                drLengthBits = drLen,
                bitOffsetFromTdi = null,
                present = true,
                note = if (decl.instruction.equals("BYPASS", true)) "BYPASS 器件，DR 仅 1 位" else "",
            )
            if (decl.instruction.equals("BYPASS", true)) {
                findings += finding(
                    FindingCode.BYPASS_DEVICE_PRESENT, FindingSeverity.INFO,
                    "器件 ${decl.name}（位置 ${decl.position}）当前为 BYPASS，对扫描链贡献 1 个 DR 位。",
                    refs = mapOf("device" to decl.name, "position" to "${decl.position}"),
                )
            }
        }

        // 6) 用 SDR 实测 DR 长度核对，缺件时生成偏移候选。
        val measuredDrLengths = sdrSegments.filter { it.completed }.map { it.tdoBits.size }.distinct()
        val offsetCandidates = mutableListOf<OffsetCandidate>()
        if (measuredDrLengths.isNotEmpty()) {
            val measuredDr = measuredDrLengths.maxOrNull()!!
            if (measuredDr != declaredDrTotal) {
                findings += finding(
                    FindingCode.CHAIN_LENGTH_MISMATCH, FindingSeverity.ERROR,
                    "DR 扫描实测 $measuredDr 位，按当前指令声明合计 $declaredDrTotal 位；" +
                        "差 ${kotlin.math.abs(measuredDr - declaredDrTotal)} 位，按链上缺件保留偏移候选。",
                    refs = mapOf("measuredDrBits" to "$measuredDr", "declaredDrBits" to "$declaredDrTotal"),
                )
                generateOffsetCandidates(declaredChain, measuredDr).forEach { offsetCandidates += it }
                findings += finding(
                    FindingCode.OFFSET_CANDIDATES_RETAINED, FindingSeverity.WARNING,
                    "已保留 ${offsetCandidates.size} 个缺件偏移假设，不收敛到单一答案。",
                    refs = mapOf("candidateCount" to "${offsetCandidates.size}"),
                )
            }
        }

        // 7) 单元到引脚证据链。
        val evidence = mutableListOf<CellPinEvidence>()
        for (decl in declaredChain) {
            for (cell in decl.cells) {
                val mapping = cell.portName?.let { decl.board.portToPin[it] }
                val diag = when {
                    cell.portName == null -> "内部单元，无逻辑端口"
                    mapping == null -> "端口 ${cell.portName} 在板卡修订 ${decl.board.revision} 中无引脚映射"
                    mapping.diagnostic != null -> mapping.diagnostic
                    mapping.packagePin == null -> "缺封装引脚"
                    mapping.netName == null -> "缺板上网络"
                    else -> null
                }
                if (diag != null && cell.portName != null &&
                    (mapping == null || mapping.packagePin == null || mapping.netName == null)) {
                    // 端口级孤儿只报一次
                }
                evidence += CellPinEvidence(
                    cellNumber = cell.cellNumber,
                    portName = cell.portName,
                    packagePin = mapping?.packagePin,
                    netName = mapping?.netName,
                    coordinateBasis = mapping?.coordinateBasis ?: decl.board.coordinateBasis,
                    xMm = mapping?.xMm,
                    yMm = mapping?.yMm,
                    bsdlVersion = decl.bsdlVersion,
                    boardRevisionId = decl.board.boardRevisionId,
                    diagnostic = diag,
                )
            }
        }
        val orphanPorts = declaredChain.flatMap { decl ->
            decl.cells.mapNotNull { it.portName }.distinct().filter { it !in decl.board.portToPin }
                .map { decl.name to it }
        }
        orphanPorts.forEach { (name, port) ->
            findings += finding(
                FindingCode.ORPHAN_CELL_PIN, FindingSeverity.WARNING,
                "器件 $name 的端口 $port 没有板卡引脚映射（BSDL 端口存在，但板卡修订未提供坐标/网络）。",
                refs = mapOf("device" to name, "port" to port),
            )
        }

        return ChainInferenceResult(
            irCandidates = irCandidates,
            devices = devices,
            offsets = offsetCandidates,
            cellEvidence = evidence,
            findings = findings,
            declaredTotalIrBits = if (declaredTotal > 0) declaredTotal else null,
            measuredTotalIrBits = measuredTotal,
        )
    }

    /**
     * Capture-IR 尾模式（链总长度 L，共扫描 N 拍，LSB 先发）：
     *   TDO[0]=1, TDO[1]=0, TDO[L..N-1]=0（L<N 部分是新载入指令的尾 0）。
     * X 位不参与比对；任意一位明确为 1 出现在要求 0 的位置则排除。
     */
    fun irLengthSolutions(tdo: List<LogicLevel>): List<Int> {
        val n = tdo.size
        val result = mutableListOf<Int>()
        for (length in 1..n) {
            var ok = true
            if (tdo[0] == LogicLevel.ZERO) ok = false
            if (n >= 2 && tdo[1] == LogicLevel.ONE) ok = false
            for (idx in length until n) {
                if (tdo[idx] == LogicLevel.ONE) { ok = false; break }
            }
            if (ok) result += length
        }
        return result
    }

    /** 枚举“声明链中缺一个器件”的偏移假设，给出每个器件相对 TDI 的候选偏移。 */
    private fun generateOffsetCandidates(
        declared: List<ChainDeviceDeclaration>,
        measuredDrBits: Int,
    ): List<OffsetCandidate> {
        val out = mutableListOf<OffsetCandidate>()
        for (missing in declared) {
            val remaining = declared.filter { it.name != missing.name }
            val total = remaining.sumOf { decl ->
                when {
                    decl.instruction.equals("BYPASS", true) -> 1
                    decl.boundaryLengthBits >= 0 -> decl.boundaryLengthBits
                    else -> 0
                }
            }
            if (total == measuredDrBits) {
                var offset = 0
                for (d in remaining.sortedBy { it.position }) {
                    val len = if (d.instruction.equals("BYPASS", true)) 1 else d.boundaryLengthBits
                    out += OffsetCandidate(
                        forDeviceId = d.name,
                        assumedMissing = listOf(missing.name),
                        offsetBits = offset,
                        rationale = "假设缺失 ${missing.name}（位置 ${missing.position}）时，DR 合计=$total 与实测一致",
                    )
                    offset += len
                }
            }
        }
        return out
    }

    private fun bitChar(level: LogicLevel?): String = when (level) {
        LogicLevel.ONE -> "1"
        LogicLevel.ZERO -> "0"
        LogicLevel.X, null -> "x"
    }
}

/** 推导输入中“按位置声明的器件”，自带其 BSDL 与板卡修订，强调两类来源独立。 */
data class ChainDeviceDeclaration(
    val name: String,
    val position: Int,
    val bsdlId: String,
    val bsdlVersion: String,
    val irLengthBits: Int,
    val boundaryLengthBits: Int,
    val idcode: String?,
    val instruction: String,
    val cells: List<BoundaryCell>,
    val board: BoardRevision,
)
