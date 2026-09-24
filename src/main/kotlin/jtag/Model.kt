package jtag

import kotlinx.serialization.Serializable

/** IEEE 1149.1 TAP controller states. */
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
}

/** Raw sample edge, value kept exactly as imported (raw representation preserved). */
@Serializable
data class SampleEdge(
    val tRaw: String,          // raw timestamp text, e.g. "100" or "1.5e2"
    val tms: Int, val tdi: Int, val tdo: Int
)

@Serializable
data class SampleEdgeSet(
    val id: String, val label: String,
    val clockUnit: String,          // e.g. "ns", "ps", "samples"
    val activeEdge: String,         // "RISING" or "FALLING" (sampling polarity)
    val minPeriodRaw: String,       // raw min TCK period, same unit as clockUnit
    val edges: List<SampleEdge>
)

@Serializable
data class ResetEvent(val atEdgeIndex: Int, val kind: String) // TRST_ASSERT | FIVE_TMS_HIGH

@Serializable
data class ResetEventSet(val id: String, val label: String, val events: List<ResetEvent>)

@Serializable
data class IrLengthCandidate(val length: Int, val source: String) // BSDL | IDCODE_GUESS | MANUAL

@Serializable
data class IrLengthCandidateSet(
    val id: String, val label: String,
    val perDevice: Map<String, List<IrLengthCandidate>> // deviceName -> candidates
)

@Serializable
data class BoundaryCell(val cellIndex: Int, val port: String, val pin: String, val function: String)

@Serializable
data class BsdlMapping(
    val id: String, val label: String, val device: String,
    val mappingVersion: String, val cells: List<BoundaryCell>
)

@Serializable
data class ExpectedDevice(val position: Int, val name: String, val idcode: String?, val bypassOnly: Boolean)

@Serializable
data class BoardRevision(
    val id: String, val label: String, val revision: String,
    val expectedDevices: List<ExpectedDevice>
)

/** Derivation parameters chosen by the reviewer. */
@Serializable
data class DeriveParams(
    val sampleSetId: String,
    val resetSetId: String,
    val irCandidateSetId: String,
    val bsdlMappingIds: List<String>,
    val boardRevisionId: String,
    val polarityOverride: String? = null,        // RISING | FALLING | null = use source
    val chosenIrLengths: Map<String, Int> = emptyMap(),
    val label: String = ""
)

// ---------- derived results ----------

@Serializable
data class TracePoint(val edgeIndex: Int, val tPs: Long?, val tRaw: String, val state: String, val tms: Int)

@Serializable
data class ShiftSegment(
    val kind: String,            // IR | DR
    val startEdge: Int, val bitCount: Int,
    val tdiBits: String,         // LSB-first as captured
    val tdoBits: String
)

@Serializable
data class ChainDevice(
    val position: Int, val name: String, val irLength: Int,
    val idcode: String?, val bypass: Boolean, val status: String // OK | BYPASS_DEVICE | MISSING | AMBIGUOUS
)

@Serializable
data class ChainHypothesis(
    val id: String, val devices: List<ChainDevice>,
    val offsetCandidates: List<Int> = emptyList(), // kept when a device is missing
    val status: String            // UNIQUE | AMBIGUOUS | MISSING_DEVICE
)

@Serializable
data class CellPinEvidence(val device: String, val cellIndex: Int, val port: String, val pin: String, val mappingVersion: String)

@Serializable
data class DerivationResult(
    val trace: List<TracePoint> = emptyList(),
    val segments: List<ShiftSegment> = emptyList(),
    val hypotheses: List<ChainHypothesis> = emptyList(),
    val evidence: List<CellPinEvidence> = emptyList()
)

/** Machine-readable judgment event. */
@Serializable
data class JudgeEvent(
    val kind: String,            // MID_STREAM_RESET | ILLEGAL_TRANSITION | IR_LENGTH_AMBIGUOUS | ...
    val severity: String,        // INFO | WARN | ERROR
    val message: String,
    val affected: List<String> = emptyList(),   // stable ids of affected objects
    val stage: String = ""
)

object EventKinds {
    const val MID_STREAM_RESET = "MID_STREAM_RESET"
    const val ILLEGAL_TRANSITION = "ILLEGAL_TRANSITION"
    const val IR_LENGTH_AMBIGUOUS = "IR_LENGTH_AMBIGUOUS"
    const val IR_LENGTH_UNRESOLVED = "IR_LENGTH_UNRESOLVED"
    const val BYPASS_DEVICE = "BYPASS_DEVICE"
    const val MISSING_DEVICE = "MISSING_DEVICE"
    const val POLARITY_MISMATCH = "POLARITY_MISMATCH"
    const val CAPTURE_VIOLATION = "CAPTURE_VIOLATION"
    const val MAPPING_VERSION_CHANGED = "MAPPING_VERSION_CHANGED"
    const val UNIT_UNCONVERTIBLE = "UNIT_UNCONVERTIBLE"
    const val STAGE_REUSED = "STAGE_REUSED"
    const val STAGE_RECOMPUTED = "STAGE_RECOMPUTED"
}
