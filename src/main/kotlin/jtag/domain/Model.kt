package jtag.domain

import kotlinx.serialization.Serializable

/**
 * A raw imported value. The original textual representation and unit are kept
 * verbatim at the source layer; normalization happens only in the derived layer.
 * Values that cannot be converted stay in place with a diagnostic and are never
 * dropped to make a plot work.
 */
@Serializable
data class RawValue(
    val raw: String,
    val unit: String? = null,
    val normalized: Double? = null,      // base unit: ns for time, V for voltage
    val diagnostic: String? = null,      // set when conversion failed
) {
    companion object {
        private val timeFactors = mapOf("ps" to 0.001, "ns" to 1.0, "us" to 1e3, "ms" to 1e6, "s" to 1e9)
        private val voltFactors = mapOf("mV" to 0.001, "V" to 1.0)

        fun of(raw: String, unit: String?): RawValue {
            val n = raw.trim().toDoubleOrNull()
                ?: return RawValue(raw, unit, null, "unparseable-number:'$raw'")
            val u = unit?.trim()
            if (u == null) return RawValue(raw, unit, n, "missing-unit; kept raw")
            val f = timeFactors[u] ?: voltFactors[u]
                ?: return RawValue(raw, unit, null, "unknown-unit:'$u'; kept raw")
            return RawValue(raw, unit, n * f, null)
        }
    }
}

enum class Polarity { RISING, FALLING }

/** Source record: one sampled TCK edge with TMS/TDI/TDO levels. */
@Serializable
data class SampleEdge(
    val id: String,
    val edgeIndex: Int,
    val tms: Int,               // 0/1, or -1 = unstable/ambiguous
    val tdi: Int,
    val tdo: Int,               // -1 = unknown (hi-z)
    val time: RawValue,         // carries unit + coordinate basis (probe clock)
    val clockBasis: String,     // e.g. "probe0.tck" — coordinate reference for the sample
    val levelTms: RawValue? = null,  // optional analog level in V
)

/** Source record: reset events are stored separately from sampling edges. */
@Serializable
data class ResetEvent(
    val id: String,
    val kind: String,           // TRST_ASSERT | TMS5_SEQUENCE | TCK_GAP
    val atEdgeIndex: Int,
    val time: RawValue? = null,
)

/** Source record: IR length candidate. Never guessed from IDCODE alone. */
@Serializable
data class IrLengthCandidate(
    val id: String,
    val device: String,
    val length: Int,
    val basis: String,          // BSDL | DATASHEET | IDCODE_GUESS | MANUAL
    val status: String = "CANDIDATE",   // CANDIDATE | ADOPTED | REJECTED
)

/** One boundary-scan cell → pin mapping entry from a BSDL revision. */
@Serializable
data class BsdlCell(val cellIndex: Int, val port: String, val pin: String?, val func: String, val safe: String)

/** Source record: a BSDL pin mapping, versioned; revisions coexist. */
@Serializable
data class BsdlMapping(
    val id: String,
    val device: String,
    val version: String,
    val instructionOpCodes: Map<String, String> = emptyMap(),  // name -> opcode bits, MSB first
    val irLength: Int? = null,
    val cells: List<BsdlCell> = emptyList(),
)

/** Source record: board revision with chain order; stored separately. */
@Serializable
data class BoardRevision(
    val id: String,
    val board: String,
    val revision: String,
    val chainOrder: List<String>,          // device refs, TDI -> TDO order
    val expectedDevices: List<String> = chainOrder,
)

// ---------------- derived layer ----------------

@Serializable
data class TapStep(
    val index: Int,
    val edgeId: String,          // stable identity back to the source edge
    val from: TapState,
    val to: TapState,
    val tms: Int,
    val legal: Boolean = true,
    val anomaly: String? = null, // e.g. CLOCK_GAP, MID_RESET, UNSTABLE_TMS
)

@Serializable
data class TapSegment(val id: String, val startIndex: Int, val steps: List<TapStep>, val endedBy: String?)

@Serializable
data class TapTrace(
    val polarity: Polarity,
    val segments: List<TapSegment>,
    val anomalies: List<String>,   // machine-readable anomaly codes with edge ids
)

@Serializable
data class ShiftBit(val edgeId: String, val tdi: Int, val tdo: Int, val timeNs: Double?)

@Serializable
data class ShiftBlock(
    val id: String,
    val kind: String,              // IR | DR
    val segmentId: String,
    val bits: List<ShiftBit>,      // first bit shifted first (LSB-first on wire)
)

@Serializable
data class ShiftBitstreams(val blocks: List<ShiftBlock>)

@Serializable
data class ChainDevice(
    val ref: String,
    val irLength: Int,             // adopted or candidate length
    val irLengthStatus: String,    // ADOPTED | CANDIDATE | UNKNOWN
    val bypass: Boolean = false,
    val missing: Boolean = false,
)

@Serializable
data class OffsetCandidate(val atPosition: Int, val missingDevice: String, val drWidthContribution: Int)

@Serializable
data class DeviceChain(
    val hypothesisId: String,
    val devices: List<ChainDevice>,
    val offsetCandidates: List<OffsetCandidate> = emptyList(),  // kept when devices missing
    val alternatives: List<String> = emptyList(),               // ids of other legal hypotheses
)

@Serializable
data class CellPinEvidence(
    val device: String,
    val mappingVersion: String,
    val rows: List<CellPinRow>,
)

@Serializable
data class CellPinRow(
    val cellIndex: Int,
    val pin: String?,            // coordinate basis: package ball / pin number of the mapping revision
    val func: String,
    val observedEdgeIds: List<String>,   // evidence: sample edges that exercised this cell
    val unmapped: Boolean = false,
)
