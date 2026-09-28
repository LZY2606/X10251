package jtap.engine

import jtap.domain.*
import kotlinx.serialization.Serializable

/** 复位事件独立记录（与采样边沿分开存储）。 */
@Serializable
data class ResetRecord(
    val id: String,
    val traceId: String,
    val segment: Int,
    val kind: String,            // POWER_ON | TRST | TMS_TLR
    val edgeIndexFrom: Int?,
    val edgeIndexTo: Int?,
    val midStream: Boolean,
    val abortedStreamId: String?,
    val basis: String
)

@Serializable
data class PolarityOption(val name: String, val tdoInverted: Boolean, val idcodeMatches: Int)

@Serializable
data class ReplayResult(
    val traceId: String,
    val configId: String,
    val steps: List<TapStep>,
    val streams: List<ShiftStream>,
    val resets: List<ResetRecord>,
    val events: List<ReplayEvent>,
    val polarityOptions: List<PolarityOption>,
    val effectiveTdoInverted: Boolean,
    val endedInUnknown: Boolean,
    val fingerprints: Map<String, String>
)

@Serializable
data class IrAnalysisResult(
    val candidates: List<IrLengthCandidate>,
    val adoptedCandidateId: String?,
    val events: List<ReplayEvent>
)

@Serializable
data class ChainAnalysisResult(
    val slots: List<DeviceSlot>,
    val hypotheses: List<ChainHypothesis>,
    val events: List<ReplayEvent>,
    val observedDrStreams: List<String>
)

@Serializable
data class MappingResult(
    val leftBsdlVersion: String?,
    val rightBsdlVersion: String?,
    val cells: List<BoundaryCell>,
    val diffs: List<CellMappingDiff>,
    val events: List<ReplayEvent>
)

@Serializable
data class DerivationBundle(
    val id: String,
    val caseId: String,
    val parentDerivationId: String?,
    val createdAt: Long,
    val traceSourceId: String,
    val configSourceId: String?,
    val boardSourceId: String?,
    val bsdlSourceIds: List<String>,
    val replay: ReplayResult,
    val ir: IrAnalysisResult,
    val chain: ChainAnalysisResult,
    val mapping: MappingResult,
    val dependencyNodes: List<DependencyNode>,
    val sourceFingerprints: Map<String, String>
)

/** 写入中断模拟器：提交回调之间可被 kill。 */
fun interface CrashHook {
    fun afterStage(stage: String)
}
