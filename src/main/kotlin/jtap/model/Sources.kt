package jtap.model

/**
 * Source documents are stored separately and append-only:
 * sample edges, reset events, IR-length candidates, BSDL pin maps and board
 * revisions never share a table/record. Each revision is immutable; only the
 * current pointer moves.
 */

enum class CaptureEdge { RISING, FALLING, UNCONVERTED }

data class PolarityConfig(
    val captureEdge: CaptureEdge,
    val invertTms: Boolean = false,
    val invertTdi: Boolean = false,
    val invertTdo: Boolean = false,
    val rawText: String,
    val diagnostic: String? = null,
)

/** One sampled TCK edge with its original textual representation preserved. */
data class SampleEdge(
    val edgeIndex: Long,
    val at: Quantity,
    val tms: String,
    val tck: String,
    val tdi: String,
    val tdo: String,
    val declaredState: String? = null,
    val diagnostics: List<String> = emptyList(),
)

data class SampleSource(
    val sourceId: String,
    val revision: Int,
    val label: String,
    val polarity: PolarityConfig,
    val edges: List<SampleEdge>,
    val rawPayload: String,
)

/** Reset events are their own source family, never folded into the edge table. */
data class ResetEvent(
    val eventId: String,
    val sourceId: String,
    val edgeIndex: Long,
    val at: Quantity,
    val cause: String,
    val midScan: Boolean,
    val abandonedBits: Int,
    val detail: String,
)

/** BSDL pin/cell mapping as a separately versioned source. */
data class BsdlCell(
    val number: Int,
    val portName: String,
    val cellType: String,
    val function: String,
    val safeBit: String?,
    val coordinate: PinCoordinate,
)

data class BsdlSource(
    val sourceId: String,
    val revision: Int,
    val deviceName: String,
    val bsdlVersion: String,
    val packageBody: String,
    val idcodeMask: String?,
    val instructionLength: Int?,
    val instructionLengthRaw: String?,
    val opcodes: Map<String, Int>,
    val cells: List<BsdlCell>,
    val rawPayload: String,
    val diagnostics: List<String> = emptyList(),
)

data class DeviceManifestEntry(
    val position: Int,
    val expectedName: String,
    val bsdlSourceId: String?,
    val expectedIdcode: String?,
)

/** Board revision + ordered device manifest; independent of BSDL revisions. */
data class BoardRevisionSource(
    val sourceId: String,
    val revision: Int,
    val boardRevision: String,
    val entries: List<DeviceManifestEntry>,
    val rawPayload: String,
)

/** IR-length candidates are stored independently of any single answer. */
data class IrLengthCandidate(
    val candidateId: String,
    val perDeviceLengths: List<Int>,
    val total: Int,
    val evidence: List<String>,
    val adopted: Boolean,
    val reason: String,
)
