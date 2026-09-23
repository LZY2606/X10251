package tapreplay.core

import kotlinx.serialization.Serializable

/**
 * 领域模型。
 *
 * 关键约定：
 *  - 导入层只保留原始表示（raw* 字段），规范化只发生在派生层（normalizeNanos 等）。
 *  - 无法转换的值留在原位置并附诊断，绝不为画图而丢弃。
 *  - 所有时间量携带单位，所有引脚坐标携带基准（datum/origin）。
 *  - 每个对象都有稳定身份（id），且身份不随显示语言/重算改变。
 */

/** 可在界面上稳定引用的身份。 */
typealias StableId = String

/** 单位标注。规范化失败时 unit 保留原始字符串、normalizedNanos 为 null。 */
@Serializable
data class UnitTag(
    val unit: String,
    val normalizedNanos: Long? = null,
    val conversionDiagnostic: String? = null,
)

/** 一条采样边沿。rawTime/rawTms/... 是逻辑分析仪导入的原始表示，永不被覆盖。 */
@Serializable
data class SampleEdge(
    val id: StableId,
    val index: Int,
    val rawTime: String,
    val time: UnitTag?,
    val rawTms: String,
    val rawTck: String,
    val rawTdi: String,
    val rawTdo: String,
    val tms: Int?,
    val tck: Int?,
    val tdi: Int?,
    val tdo: Int?,
    val decodeDiagnostics: List<String> = emptyList(),
)

/** 采样配置。采样极性（在哪个 TCK 边沿采样 TMS/TDI）来自导入片段。 */
@Serializable
enum class SamplePolarity { SAMPLE_ON_TCK_RISING, SAMPLE_ON_TCK_FALLING, UNKNOWN }

@Serializable
data class SampleSource(
    val id: StableId,
    val revisionId: StableId,
    val boardRevisionId: StableId,
    val branchId: StableId,
    val polarity: SamplePolarity,
    val polarityRaw: String,
    val tckPeriodNanos: Long?,
    val tckPeriodRaw: String,
    val edges: List<SampleEdge>,
    val diagnostics: List<String> = emptyList(),
)

/** 复位事件源（逻辑分析仪的 nTRST/上电标记等），与采样边沿分开存储。 */
@Serializable
data class ResetEvent(
    val id: StableId,
    val revisionId: StableId,
    val branchId: StableId,
    val kind: String, // POWER_ON | NTRST_ASSERTED | EXPECTED_BY_TMS
    val rawKind: String,
    val rawAtEdgeIndex: String,
    val atEdgeIndex: Int?, // null => 无法定位，保留诊断
    val diagnostic: String? = null,
)

/** IR 长度候选（来自 BSDL / 数据手册 / 人工猜测），不会仅因 IDCODE 而被确定。 */
@Serializable
data class IrLengthCandidate(
    val id: StableId,
    val revisionId: StableId,
    val branchId: StableId,
    val deviceId: StableId,
    val irLengthBits: Int?,
    val rawIrLength: String,
    val source: String, // BSDL | DATASHEET | HUMAN_GUESS | INFERRED
    val confidence: String, // HARD | SOFT | GUESS
    val diagnostic: String? = null,
)

/** 边界扫描单元 -> 引脚的证据，坐标带基准。 */
@Serializable
data class CellBinding(
    val cellIndex: Int,
    val port: String,
    val pin: String,
    val function: String, // input | output2 | internal ...
    val safeBit: Int?,
    val coordinateDatum: String?,
    val coordinateX: String?,
    val coordinateY: String?,
)

/** BSDL 引脚映射，按版本分开存储。rawBody 保留完整原始表示。 */
@Serializable
data class BsdlMapping(
    val id: StableId,
    val revisionId: StableId,
    val deviceId: StableId,
    val bsdlVersion: String,
    val rawBody: String,
    val idcodeHex: String?,
    val irLengthBits: Int?,
    val irCapture: String?,
    val bypassOpcodeHex: String?,
    val tapPorts: Map<String, String>,
    val cells: List<CellBinding>,
    val diagnostics: List<String> = emptyList(),
)

/** 板卡修订。期望器件清单与坐标基准独立于采样与 BSDL 存储。 */
@Serializable
data class BoardDevice(
    val deviceId: StableId,
    val designator: String,
    val expectedIdcodeHex: String?,
    val coordinateDatum: String,
    val xMm: String,
    val yMm: String,
    /** 板上同一可选贴装位（do-not-stuff 互斥）。缺件时偏移候选的板级依据。 */
    val optionalGroup: String? = null,
    /** 板级预期 TDI->TDO 顺序（从网表来，空表示未知，仅用于排序约束）。 */
    val chainPosition: Int? = null,
)

@Serializable
data class BoardRevision(
    val id: StableId,
    val revisionId: StableId,
    val name: String,
    val datum: String,
    val populatedDevices: List<BoardDevice>,
    val diagnostics: List<String> = emptyList(),
)

/** 一次源数据修订：五类来源各自独立，互不嵌套。 */
@Serializable
data class SourceRevision(
    val id: StableId,
    val parentId: StableId?,
    val label: String,
    val createdAtEpochNanos: Long,
    val samples: List<SampleSource> = emptyList(),
    val resets: List<ResetEvent> = emptyList(),
    val irCandidates: List<IrLengthCandidate> = emptyList(),
    val bsdlMappings: List<BsdlMapping> = emptyList(),
    val boardRevisions: List<BoardRevision> = emptyList(),
)

/** 人工决定：接受/拒绝/参数修订，只追加，永不物理覆盖候选。 */
@Serializable
data class Decision(
    val id: StableId,
    val branchId: StableId,
    val createdAtEpochNanos: Long,
    val author: String,
    val action: String, // ACCEPT | REJECT | PARAMETER_CHANGE
    val targetStableId: StableId,
    val targetKind: String, // IR_CANDIDATE | CHAIN_CANDIDATE | POLARITY | MAPPING_VERSION ...
    val payload: Map<String, String>,
    val rationale: String,
    val supersedesDecisionId: StableId? = null,
)

/* ----------------------- TAP 状态机（IEEE 1149.1） ----------------------- */

@Serializable
enum class TapState {
    TEST_LOGIC_RESET, RUN_TEST_IDLE,
    SELECT_DR_SCAN, CAPTURE_DR, SHIFT_DR, EXIT1_DR, PAUSE_DR, EXIT2_DR, UPDATE_DR,
    SELECT_IR_SCAN, CAPTURE_IR, SHIFT_IR, EXIT1_IR, PAUSE_IR, EXIT2_IR, UPDATE_IR,
    UNKNOWN;

    val isShift: Boolean get() = this == SHIFT_DR || this == SHIFT_IR
}

@Serializable
data class TraceStep(
    val edgeId: StableId?,
    val edgeIndex: Int?,
    val from: TapState,
    val to: TapState,
    val tms: Int?,
    val tdo: Int?,
    val tdi: Int?,
    val samplePhase: String, // RISING | FALLING | RESET_SOURCE | NONE
    val annotation: String? = null,
)

/** 一次完整或中止的移位段。aborted 的段保留已移入/移出的位。 */
@Serializable
data class ScanSegment(
    val id: StableId,
    val kind: String, // IR | DR
    val startEdgeIndex: Int,
    val endEdgeIndex: Int?,
    val shiftTdiBits: List<Int>,
    val shiftTdoBits: List<Int>,
    val enteredFrom: TapState,
    val leftTo: TapState?,
    val aborted: Boolean,
    val abortReason: String? = null,
)

/** 机器可读事件。judgementBasis 记录“为什么这样判”，供审计/反查。 */
@Serializable
data class EngineEvent(
    val id: StableId,
    val type: String,
    val severity: String, // INFO | WARN | ERROR
    val edgeId: StableId? = null,
    val edgeIndex: Int? = null,
    val targetStableId: StableId? = null,
    val message: String,
    val judgementBasis: Map<String, String> = emptyMap(),
)

/** 链上单个器件的重建结果。 */
@Serializable
data class ChainDevice(
    val slotIndex: Int,                 // TDI->TDO 槽位，0 最靠近 TDI
    val deviceId: StableId?,            // 无法唯一映射时为 null
    val designator: String?,
    val idcodeHex: String?,
    val observedDrWidth: Int,           // 该槽在 IDCODE 扫描中观测到的 DR 宽度
    val irLengthBits: Int?,
    val irLengthResolved: Boolean,
    val irLengthCandidates: List<Int>,
    val bypass: Boolean,
    val present: Boolean?,              // null = 无法确定是否缺件
    val mappingVersion: String?,
    val cellCount: Int,
)

/** 一条合法的整条链候选（缺件场景可能有多条，全部保留）。 */
@Serializable
data class ChainCandidate(
    val id: StableId,
    val devices: List<ChainDevice>,
    val accepted: Boolean = false,
    val note: String? = null,
)

/** 某派生节点为何有效/为何需重算。 */
@Serializable
data class NodeStatus(
    val node: String,
    val state: String, // UP_TO_DATE | RECOMPUTED | STALE | BLOCKED
    val reason: String,
    val dependsOn: List<String>,
)

@Serializable
data class RecomputeReport(
    val changedNodes: List<String>,
    val unchangedNodes: List<String>,
    val nodeStatuses: List<NodeStatus>,
)

/** 一次完整派生（不可变版本）。current 指针只在完整成功后切换。 */
@Serializable
data class Derivation(
    val id: StableId,
    val branchId: StableId,
    val sourceRevisionId: StableId,
    val basisDecisionIds: List<StableId>,
    val createdAtEpochNanos: Long,
    val polarityUsed: SamplePolarity,
    val trace: List<TraceStep>,
    val scans: List<ScanSegment>,
    val chainCandidates: List<ChainCandidate>,
    val events: List<EngineEvent>,
    val recomputeReport: RecomputeReport,
    /** 各派生节点输入指纹；用于说明“为何仍有效/为何重算”。 */
    val nodeHashes: Map<String, String> = emptyMap(),
)
