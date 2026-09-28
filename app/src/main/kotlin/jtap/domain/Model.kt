package jtap.domain

import kotlinx.serialization.Serializable

/* ----------------------------- 基础领域量 ----------------------------- */

/** 采样位：除 0/1 外保留 X（未知）与 Z（高阻）原始表示，不做静默二值化。 */
@Serializable
enum class Bit { ZERO, ONE, X, Z }

fun Bit?.bitChar(): Char = when (this) {
    Bit.ZERO -> '0'; Bit.ONE -> '1'; Bit.X -> 'X'; Bit.Z -> 'Z'; null -> '?'
}

fun parseBit(token: String): Bit? = when (token.trim().uppercase()) {
    "0" -> Bit.ZERO
    "1" -> Bit.ONE
    "X" -> Bit.X
    "Z" -> Bit.Z
    else -> null
}

/**
 * 采样边沿：原始表示逐字段保留。tck 携带时间单位（默认 ns），坐标基准为“本 trace 内边沿序号”。
 * 无法转换的值（tck 非数值、位信号非 0/1/X/Z）留在原位并写入 diagnostics，绝不丢弃。
 */
@Serializable
data class RawEdge(
    val index: Int,
    val rawLine: String,
    val tck: String,
    val tckUnit: String,
    val tms: String,
    val tdi: String,
    val tdo: String,
    val trst: String,
    val tckNs: Double?,
    val tmsBit: Bit?,
    val tdiBit: Bit?,
    val tdoBit: Bit?,
    val trstBit: Bit?,
    val diagnostics: List<String>
) {
    val hasIllegalSample: Boolean
        get() = tmsBit == null || tdiBit == null || tdoBit == null ||
            tmsBit == Bit.X || tmsBit == Bit.Z
}

@Serializable
enum class SampleEdge { RISING, FALLING }

@Serializable
data class SessionConfig(
    val id: String,
    val version: Int,
    val sampleEdge: SampleEdge,
    val tdoPolarityInverted: Boolean,
    val basis: String = "TCK 边沿采样；TDO 与 TMS 在同一采样点锁存"
)

/* ------------------------------- TAP FSM ------------------------------ */

@Serializable
enum class TapState {
    TLR, IDLE,
    SELECT_DR, CAPTURE_DR, SHIFT_DR, EXIT1_DR, PAUSE_DR, EXIT2_DR, UPDATE_DR,
    SELECT_IR, CAPTURE_IR, SHIFT_IR, EXIT1_IR, PAUSE_IR, EXIT2_IR, UPDATE_IR,
    UNKNOWN;

    val stableName: String get() = "tapstate:${name}"
}

@Serializable
data class TapStep(
    val edgeIndex: Int,
    val fromState: TapState,
    val toState: TapState,
    val tms: Bit,
    val tdi: Bit,
    val tdoRaw: Bit,
    val tdoEffective: Bit,
    val flags: List<String>
)

@Serializable
enum class StreamKind { IR, DR }

@Serializable
data class ShiftStream(
    val id: String,
    val traceId: String,
    val segment: Int,
    val kind: StreamKind,
    val captureEdge: Int,
    val firstShiftEdge: Int,
    val lastEdge: Int,
    val tdiBits: List<Bit>,
    val tdoBits: List<Bit>,
    val completed: Boolean,
    val abortedByResetEdge: Int? = null
)

/* -------------------------------- 事件 -------------------------------- */

@Serializable
enum class Severity { INFO, WARNING, ANOMALY, ERROR }

@Serializable
enum class EventStatus { OPEN, ADOPTED, REJECTED, SUPERSEDED }

/** 机器可读事件：code 绑定规则编号，affected 指向受影响对象的稳定身份，candidates 指向候选。 */
@Serializable
data class ReplayEvent(
    val id: String,
    val code: String,
    val severity: Severity,
    val ruleId: String,
    val traceId: String? = null,
    val edgeIndex: Int? = null,
    val edgeIndexTo: Int? = null,
    val message: String,
    val affected: List<String> = emptyList(),
    val candidateRefs: List<String> = emptyList(),
    val status: EventStatus = EventStatus.OPEN,
    val basis: String
)

/* ------------------------------ IR 长度候选 ----------------------------- */

@Serializable
data class IrLengthCandidate(
    val id: String,
    val totalBits: Int,
    val slotLengthBits: Map<Int, Int>,
    val slotOffsetBits: Map<Int, Int>,
    val support: List<String>,
    val warnings: List<String>,
    val idcodeOnly: Boolean,
    val rank: Double,
    val adopted: Boolean,
    val rejectedReason: String? = null
)

/* -------------------------------- 器件链 ------------------------------- */

@Serializable
enum class SlotStatus { KNOWN_IDCODE, BYPASS, MISSING, UNKNOWN }

@Serializable
data class DeviceSlot(
    val chainIndexFromTdi: Int,
    val status: SlotStatus,
    val idcode: String?,
    val deviceRef: String?,
    val expectedDeviceRef: String?,
    val widthBits: Int?,
    val widthCandidates: List<Int>,
    val offsetFromTdoBits: Int?,
    val offsetCandidates: List<Int>,
    val irLengthBits: Int?,
    val evidence: List<String>
)

@Serializable
data class ChainHypothesis(
    val id: String,
    val slotWidths: List<Int>,
    val offsetsFromTdo: List<Int>,
    val fit: Boolean,
    val reason: String
)

/* ---------------------------- BSDL / 边界单元 --------------------------- */

@Serializable
data class BoundaryCell(
    val stableId: String,
    val deviceRef: String,
    val bsdlVersionId: String,
    val cellNumber: Int,
    val portId: String,
    val function: String,
    val safe: String,
    val controlCell: Int? = null,
    val boardNet: String? = null,
    val coordinateBasis: String,
    val sourceLine: Int
)

@Serializable
data class BsdlDoc(
    val id: String,
    val deviceRef: String,
    val version: String,
    val instructionLengthBits: Int?,
    val idcode: String?,
    val cells: List<BoundaryCell>,
    val rawText: String,
    val diagnostics: List<String>
)

@Serializable
data class CellMappingDiff(
    val cellStableId: String,
    val deviceRef: String,
    val cellNumber: Int,
    val leftVersion: String?,
    val rightVersion: String?,
    val leftPin: String?,
    val rightPin: String?,
    val changed: Boolean
)

/* ------------------------------- 板卡修订 ------------------------------ */

@Serializable
data class ExpectedDevice(
    val chainIndexFromTdi: Int,
    val deviceRef: String,
    val boardNetPrefix: String,
    val populated: Boolean
)

@Serializable
data class BoardRevision(
    val id: String,
    val boardRef: String,
    val revision: String,
    val coordinateBasis: String,
    val expectedDevices: List<ExpectedDevice>
)

/* ------------------------------ 依赖图节点 ----------------------------- */

@Serializable
enum class NodeStatus { COMPUTED, REUSED_VALID, RECOMPUTED, STALE }

@Serializable
data class DependencyNode(
    val node: String,
    val status: NodeStatus,
    val inputFingerprints: Map<String, String>,
    val reason: String,
    val changedInputs: List<String>
)

/* ------------------------------ 输入源记录 ----------------------------- */

@Serializable
enum class SourceKind { SAMPLE_TRACE, BSDL, BOARD_REVISION, SESSION_CONFIG, DECISION }

@Serializable
data class SampleTrace(
    val id: String,
    val name: String,
    val version: Int,
    val parentId: String?,
    val tckUnit: String,
    val declaredEdge: SampleEdge,
    val edges: List<RawEdge>,
    val rawText: String,
    val diagnostics: List<String>
)

@Serializable
data class SourceRecord(
    val id: String,
    val caseId: String,
    val kind: SourceKind,
    val name: String,
    val version: Int,
    val parentId: String?,
    val author: String,
    val createdAt: Long,
    val rawText: String,
    val originalRepresentation: String,
    val unitOrBasis: String
)
