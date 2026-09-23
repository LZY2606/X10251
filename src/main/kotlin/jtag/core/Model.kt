package jtag.core

/** Sampling polarity: which TCK edge the TMS/TDI/TDO values were captured on. */
enum class SamplingEdge { RISING, FALLING }

/** One sampled edge row, raw representation preserved from import. */
data class EdgeSample(
    val id: String,
    val clockIndex: Int,
    val tck: Int, val tms: Int, val tdi: Int, val tdo: Int,
    val timeValue: Double?, val timeUnit: String?, // domain quantity keeps its unit
    val raw: String
)

data class ResetEvent(
    val id: String,
    val clockIndex: Int,
    val kind: String, // TRST_PULSE | TMS_FIVE_ONES
    val raw: String
)

data class IrLengthCandidate(
    val id: String,
    val devicePos: Int,
    val lengthBits: Int,
    val basis: String, // BSDL | IDCODE_GUESS | CHAIN_REMAINDER | MEASURED
    val status: String, // PROPOSED | ACCEPTED | REJECTED
    val raw: String
)

data class BsdlCell(
    val id: String,
    val device: String,
    val bsdlVersion: String,
    val cell: Int,
    val pin: String,
    val function: String, // INPUT | OUTPUT2 | OUTPUT3 | BIDIR | CLOCK | INTERNAL
    val safe: String,
    val raw: String
)

data class BoardDevice(
    val id: String,
    val revision: String,
    val position: Int,
    val device: String,
    val expectedPresent: Boolean,
    val raw: String
)

data class BoardRevision(val revision: String, val devices: List<BoardDevice>)

/** A single step in the reconstructed TAP trajectory. */
data class TapStep(
    val id: String,
    val seq: Int,
    val clockIndex: Int,
    val fromState: TapState,
    val toState: TapState, // UNKNOWN if desynced
    val tms: Int,
    val status: String, // OK | ILLEGAL_TRANSITION | MID_SCAN_RESET | RESET
    val anomaly: String?
)

/** A shift segment (register bitstream) recovered from the trajectory. */
data class ShiftSegment(
    val id: String,
    val seq: Int,
    val register: String, // DR | IR
    val startClock: Int,
    val bitsTdo: String, // LSB-first as shifted out
    val bitsTdi: String,
    val status: String // VALID | INVALIDATED | SUSPECT
)

data class ChainDevice(
    val id: String,
    val position: Int, // 0 = closest to TDI
    val name: String?,
    val idcode: String?, // hex or null when bypass-only
    val bypass: Boolean,
    val irLength: Int?, // null while ambiguous
    val status: String // IDENTIFIED | BYPASS_ONLY | MISSING | AMBIGUOUS
)

data class OffsetCandidate(
    val id: String,
    val devicePos: Int,
    val cellOffset: Int,
    val basis: String
)

data class CellPin(
    val id: String,
    val device: String,
    val cell: Int,
    val pin: String,
    val function: String,
    val bsdlVersion: String,
    val observedBit: Int? // bit observed on TDO for this cell, if mapped
)

/** Machine-readable judgement / anomaly event. */
data class JudgeEvent(
    val id: String,
    val seq: Int,
    val kind: String,
    val subject: String,
    val accepted: Boolean?,
    val rationale: String,
    val payload: String
)

data class RecomputeEntry(val node: String, val action: String, val reason: String)

/** Diagnostic for a domain value that could not be normalized; kept in place. */
data class ValueDiagnostic(val sourceId: String, val field: String, val rawValue: String, val message: String)
