package com.boardscan.derive

import com.boardscan.bsdl.BoardRevision
import com.boardscan.bsdl.CellPinEvidence
import com.boardscan.bsdl.ParsedBsdl
import com.boardscan.chain.ChainDeviceDeclaration
import com.boardscan.chain.ChainInferenceResult
import com.boardscan.model.*
import com.boardscan.tap.ScanSegment
import com.boardscan.tap.TapStep
import com.boardscan.tap.TapStep
import kotlinx.serialization.Serializable

@Serializable
data class RunInputs(
    val sample: SampleImport,
    val polarity: PolarityConfig,
    val bsdls: List<ParsedBsdl>,
    val boards: List<BoardRevision>,
    val declarations: List<DeclarationSpec>,
    val measuredIdcodeCount: Int? = null,
)

@Serializable
data class DeclarationSpec(
    val name: String,
    val position: Int,
    val bsdlId: String,
    val bsdlVersion: String,
    val idcode: String? = null,
    val instruction: String = "SAMPLE",
)

@Serializable
data class DerivationResult(
    val runId: String,
    val parentRunId: String,
    val branchLabel: String,
    val createdAt: String,
    val fingerprint: String,
    val inputs: RunInputs,
    val decodedEdges: List<DecodedEdge>,
    val steps: List<TapStep>,
    val scans: List<ScanSegment>,
    val resets: List<ResetEvent>,
    val chain: ChainInferenceResult,
    val graph: DependencyGraph,
    val findings: List<Finding>,
)

@Serializable
data class DependencyNode(
    val nodeId: String,
    val kind: String,
    val inputs: List<String>,
    val state: NodeState,
    val reason: String,
    val versionHash: String,
)

@Serializable
enum class NodeState { REUSED, RECOMPUTED, FAILED, BLOCKED }

@Serializable
data class DependencyGraph(
    val nodes: List<DependencyNode>,
    val changedInputs: List<String>,
)

/**
 * 依赖图：
 *  samples -> edges -> tap -> scans -> chain -> evidence
 * bsdl/board/polarity 作为叶子输入。
 * 通过比较输入指纹，决定每个节点 REUSED 还是 RECOMPUTED；叶子 FAILED（如不可转换）会 BLOCKED 下游。
 */
object DependencyGraphBuilder {
    fun build(inputFingerprints: Map<String, String>, changed: Set<String>): DependencyGraph {
        val blocked = inputFingerprints.any { (k, v) -> v.startsWith("FAIL:") && k in leafKinds }
        val nodes = mutableListOf<DependencyNode>()
        fun add(id: String, kind: String, deps: List<String>, failed: Boolean = false) {
            val state = when {
                failed || blocked && id != "edges" -> NodeState.BLOCKED
                deps.any { d -> nodes.firstOrNull { it.nodeId == d }?.state == NodeState.RECOMPUTED } ->
                    NodeState.RECOMPUTED
                changed.any { id.startsWith(it) || id == it } -> NodeState.RECOMPUTED
                deps.isEmpty() && id.removePrefix("in:") in changed -> NodeState.RECOMPUTED
                else -> NodeState.REUSED
            }
            val reason = when (state) {
                NodeState.REUSED -> "输入指纹未变，结果仍有效"
                NodeState.RECOMPUTED -> "上游 $deps 或输入发生变化，需要重算"
                NodeState.FAILED -> "直接来源无法规范化（保留原值与诊断）"
                NodeState.BLOCKED -> "上游存在未解决的不可转换/非法转移，本节点结果不可用"
            }
            nodes += DependencyNode(id, kind, deps, state, reason,
                inputFingerprints[id] ?: inputFingerprints["in:$id"] ?: "")
        }
        add("in:samples", "samples", emptyList())
        add("in:polarity", "polarity", emptyList())
        add("in:bsdl", "bsdl", emptyList())
        add("in:board", "board", emptyList())
        add("edges", "edges", listOf("in:samples", "in:polarity"))
        add("tap", "tap", listOf("edges"))
        add("scans", "scans", listOf("tap"))
        add("resets", "resets", listOf("tap"))
        add("chain", "chain", listOf("scans", "in:bsdl"))
        add("evidence", "evidence", listOf("chain", "in:board", "in:bsdl"))
        return DependencyGraph(nodes, changed.toList())
    }

    private val leafKinds = setOf("in:samples", "in:polarity", "in:bsdl", "in:board")
}

fun declarationsFromInputs(inputs: RunInputs): List<ChainDeviceDeclaration> {
    return inputs.declarations.map { spec ->
        val bsdl = inputs.bsdls.firstOrNull { it.bsdlId == spec.bsdlId && it.version == spec.bsdlVersion }
            ?: error("声明引用的 BSDL ${spec.bsdlId}@${spec.bsdlVersion} 不存在")
        val board = inputs.boards.firstOrNull { b ->
            b.boardRevisionId == inputs.sample.boardRevisionId
        } ?: error("板卡修订 ${inputs.sample.boardRevisionId} 不存在")
        ChainDeviceDeclaration(
            name = spec.name,
            position = spec.position,
            bsdlId = bsdl.bsdlId,
            bsdlVersion = bsdl.version,
            irLengthBits = bsdl.irLengthBits,
            boundaryLengthBits = bsdl.cells.size,
            idcode = spec.idcode ?: bsdl.idcode,
            instruction = spec.instruction,
            cells = bsdl.cells,
            board = board,
        )
    }
}
