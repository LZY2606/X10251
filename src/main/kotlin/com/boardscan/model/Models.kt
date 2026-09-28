package com.boardscan.model

import kotlinx.serialization.Serializable

@Serializable
enum class Edge { RISING, FALLING }

@Serializable
enum class Signal { TCK, TMS, TDI, TDO }

/** X = 无法唯一判定（高阻/未定义/不可转换）。不为画图而把它压成 0/1。 */
@Serializable
enum class LogicLevel { X, ZERO, ONE }

@Serializable
data class LevelDecode(
    val level: LogicLevel,
    val raw: String,
    val diagnostic: String? = null,
)

@Serializable
data class ValueWithUnit(
    val raw: String,
    val normalized: Double? = null,
    val unit: String? = null,
    val basis: String? = null,
    val diagnostic: String? = null,
)

/** 采样极性：在哪条 TCK 边沿采样、判定高/低的电压门限。每次修订独立成版。 */
@Serializable
data class PolarityConfig(
    val polarityId: String,
    val version: Int,
    val activeEdge: Edge = Edge.RISING,
    val tckThresholdV: Double = 1.4,
    val signalThresholdV: Double = 1.4,
    val timeUnitBasis: String = "ns",
    val note: String = "",
)

/** 原始采样行：导入时原样保留，任何规范化都在派生层进行。 */
@Serializable
data class RawSample(
    val edgeId: String,
    val order: Long,
    val timeRaw: String,
    val tckRaw: String,
    val tmsRaw: String,
    val tdiRaw: String,
    val tdoRaw: String,
)

@Serializable
data class SampleImport(
    val sampleId: String,
    val boardRevisionId: String,
    val polarityId: String,
    val importedAt: String,
    val rows: List<RawSample>,
)

@Serializable
data class DecodedLevel(
    val signal: Signal,
    val decode: LevelDecode,
)

@Serializable
data class DecodedEdge(
    val edgeId: String,
    val order: Long,
    val time: ValueWithUnit,
    val tck: LevelDecode,
    val tms: LevelDecode,
    val tdi: LevelDecode,
    val tdo: LevelDecode,
    val activeEdge: Edge,
)

@Serializable
enum class FindingSeverity { INFO, WARNING, ERROR }

/** 机器可读取的判定依据。disposition 由人工决定事件驱动，判定本身永不删除。 */
@Serializable
data class Finding(
    val findingId: String,
    val code: FindingCode,
    val severity: FindingSeverity,
    val message: String,
    val runId: String,
    val branchLabel: String,
    val edgeId: String? = null,
    val refs: Map<String, String> = emptyMap(),
    val disposition: FindingDisposition = FindingDisposition.OPEN,
    val decisionId: String? = null,
    val seq: Long = 0,
)

@Serializable
enum class FindingDisposition { OPEN, ACCEPTED, REJECTED }

@Serializable
enum class FindingCode {
    UNDEFINED_SIGNAL_LEVEL,
    ILLEGAL_TAP_TRANSITION,
    INCONSISTENT_EDGE_ORDER,
    MID_SCAN_RESET,
    UNKNOWN_INITIAL_STATE,
    SAMPLING_POLARITY_ANOMALY,
    UNCONVERTIBLE_VALUE,
    IR_LENGTH_AMBIGUOUS,
    IR_LENGTH_FROM_IDCODE_REJECTED,
    IR_LENGTH_CANDIDATE,
    MISSING_CHAIN_DEVICE,
    OFFSET_CANDIDATES_RETAINED,
    BYPASS_DEVICE_PRESENT,
    IDCODE_DEVICE_COUNT,
    CHAIN_LENGTH_MISMATCH,
    BSDL_VERSION_MAPPING_CHANGED,
    ORPHAN_CELL_PIN,
}

@Serializable
enum class DecisionAction { ACCEPT_FINDING, REJECT_FINDING, SET_PARAMETER, CHOOSE_CANDIDATE, RESOLVE_CONFLICT }

@Serializable
data class Decision(
    val decisionId: String,
    val runId: String,
    val branchLabel: String,
    val action: DecisionAction,
    val targetFindingId: String? = null,
    val targetObjectId: String? = null,
    val payload: Map<String, String> = emptyMap(),
    val author: String,
    val createdAt: String,
    val parentRunId: String,
    val expectedParentRunId: String? = null,
    val seq: Long = 0,
)

@Serializable
data class ResetEvent(
    val resetId: String,
    val sampleId: String,
    val kind: ResetKind,
    val enterEdgeId: String,
    val tmsRunEdges: List<String>,
    val midScan: Boolean,
    val openScanSegmentId: String? = null,
    val seq: Long = 0,
)

@Serializable
enum class ResetKind { POWERUP_ESTABLISHED, MID_SCAN_TLR, TLR_RE_ENTRY }

@Serializable
data class IrLengthCandidate(
    val candidateId: String,
    val deviceRef: String,
    val lengthBits: Int,
    val source: IrCandidateSource,
    val sourceRef: String,
    val consistent: Boolean,
    val note: String = "",
)

@Serializable
enum class IrCandidateSource { BSDL_DECLARED, SCAN_SIGNATURE, IDCODE_ONLY_REJECTED }

@Serializable
data class ChainDevice(
    val deviceId: String,
    val position: Int,
    val bsdlId: String,
    val bsdlVersion: String,
    val name: String,
    val idcode: String?,
    val irLengthBits: Int?,
    val instruction: String,
    val drLengthBits: Int,
    val bitOffsetFromTdi: Int?,
    val present: Boolean,
    val note: String = "",
)

@Serializable
data class OffsetCandidate(
    val forDeviceId: String,
    val assumedMissing: List<String>,
    val offsetBits: Int,
    val rationale: String,
)

@Serializable
enum class ScanReg { IR, DR }

/** 一次 SHIFT_x 移位：进入位（CAPTURE->SHIFT）到 EXIT1 位都计入位流。 */
@Serializable
data class ScanSegment(
    val segmentId: String,
    val runId: String,
    val branchLabel: String,
    val reg: ScanReg,
    val position: Int,
    val startEdgeId: String,
    val exitEdgeId: String?,
    val completed: Boolean,
    val abortedByResetId: String?,
    val tdiBits: List<LogicLevel>,
    val tdoBits: List<LogicLevel>,
    val edgeIds: List<String>,
    val note: String = "",
)
