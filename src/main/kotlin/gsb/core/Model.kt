package gsb.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

/** One row of a TMS/TCK/TDI/TDO sample log. Raw text is always preserved. */
@Serializable
data class SampleRow(
    val ordinal: Int,
    val timeRaw: String,          // original representation, e.g. "12.5ns" or "37" (unitless)
    val timeUnit: String,         // "ps" | "ns" | "us" | "samples" | "" (unknown)
    val tms: Int, val tck: Int, val tdi: Int, val tdo: Int
)

@Serializable
data class SampleLog(
    val name: String,
    val sampleEdge: String,       // "rising" | "falling" : TCK edge the row was captured on
    val tdoPolarity: String,      // "normal" | "inverted" as recorded by the probe
    val rows: List<SampleRow>
)

@Serializable
data class ResetEvent(val id: String, val fromOrdinal: Int, val toOrdinal: Int, val cause: String)
@Serializable
data class ResetEventSet(val name: String, val events: List<ResetEvent>)

/** IR length hypotheses per device position; never collapsed to IDCODE-only guesses. */
@Serializable
data class IrLengthHypothesis(val deviceRef: String, val length: Int, val evidence: List<String>)
@Serializable
data class IrLengthCandidates(val name: String, val hypotheses: List<IrLengthHypothesis>)

@Serializable
data class BsdlPinMap(
    val name: String,             // e.g. "xc7a35t-csg324"
    val bsdlVersion: String,      // e.g. "v1.2" / "v2.0"
    val entity: String,
    val instructionLength: Int?,
    val idcode: String?,          // 32-bit binary string
    val boundaryLength: Int?,
    val pinMap: Map<String, String>,          // port -> package pin
    val boundaryCells: List<BoundaryCell>
)
@Serializable
data class BoundaryCell(val index: Int, val cell: String, val port: String, val function: String, val safe: String)

@Serializable
data class BoardDevice(val ref: String, val part: String, val bsdlMap: String, val chainPosition: Int, val xMm: Double, val yMm: Double)
@Serializable
data class BoardRevision(val name: String, val revision: String, val devices: List<BoardDevice>)

// ---------- derived artifacts ----------

@Serializable
data class Trajectory(val steps: List<TapStepSer>, val anomalies: List<Anomaly>)
@Serializable
data class TapStepSer(val index: Int, val timePs: Long?, val state: String, val tms: Int, val tdi: Int, val tdo: Int, val reset: Boolean)

@Serializable
data class Anomaly(
    val rule: String,             // machine-readable rule id
    val severity: String,         // "error" | "warning"
    val atOrdinal: Int,
    val message: String,
    val evidence: List<Int>       // sample ordinals
)

@Serializable
data class ShiftScan(val kind: String, val startOrdinal: Int, val endOrdinal: Int,
                     val tdiBits: String, val tdoBits: String, val bitCount: Int)
@Serializable
data class ShiftBitstreams(val scans: List<ShiftScan>)

@Serializable
data class DeviceSlot(val chainPosition: Int, val ref: String, val part: String,
                      val irLength: Int?, val irCandidates: List<Int>,
                      val bypass: Boolean, val missing: Boolean)
@Serializable
data class OffsetCandidate(val cellIndex: Int, val options: List<String>) // "ref:port" alternatives
@Serializable
data class DeviceChain(val slots: List<DeviceSlot>, val offsetCandidates: List<OffsetCandidate>, val notes: List<String>)

@Serializable
data class CellPinEvidence(val cellIndex: Int, val deviceRef: String, val port: String,
                           val pin: String, val bsdlMap: String, val bsdlVersion: String,
                           val boardXmm: Double, val boardYmm: Double)
@Serializable
data class CellPinEvidenceSet(val rows: List<CellPinEvidence>, val diagnostics: List<String>)
