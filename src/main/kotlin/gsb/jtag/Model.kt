package gsb.jtag

import kotlinx.serialization.Serializable

// ---------- raw source layer (stored separately, never normalized) ----------

@Serializable
data class SampleEdge(
    val id: String,          // stable identity, e.g. "E0001"
    val ord: Int,            // order within the capture
    val t: Quant,            // timestamp with unit + basis, raw preserved
    val tms: Int, val tdi: Int, val tdo: Int?,
    val decodedState: String?, // optional vendor-decoder claim, may contradict FSM
    val src: String          // provenance: file#import
)

@Serializable
data class ResetEvent(
    val id: String,
    val edgeOrd: Int,        // edge index at which the reset takes effect
    val kind: Kind,
    val note: String
) { enum class Kind { TRST_ASYNC, TMS5 } }

@Serializable
data class IrLengthCandidate(
    val id: String,
    val devicePos: Int,      // chain position (0 = closest to TDI)
    val length: Int,
    val provenance: String   // "bsdl:U1@v1", "measured:ir-scan", "user:alice"
)

@Serializable
data class BsdlCell(val index: Int, val port: String, val function: String, val safe: String)

@Serializable
data class BsdlMap(
    val id: String,
    val device: String,
    val bsdlVersion: String,
    val irLength: Int,
    val idcode: String?,
    val cells: List<BsdlCell>,
    val pins: Map<String, String> // port -> package pin
)

@Serializable
data class BoardRevision(
    val id: String,
    val rev: String,
    val expectedDevices: List<String> // chain order, closest to TDI first
)

// ---------- derived layer ----------

@Serializable
enum class EventKind {
    ILLEGAL_TRANSITION, MID_SHIFT_RESET, IR_LENGTH_AMBIGUOUS,
    DEVICE_MISSING, POLARITY_SUSPECT, MAPPING_VERSION_CHANGED, UNIT_UNCONVERTIBLE
}

@Serializable
enum class Decision { PENDING, ACCEPTED, REJECTED }

/** Machine-readable judgment event. Accepted AND rejected outcomes are both kept. */
@Serializable
data class DeriveEvent(
    val id: String,
    val kind: EventKind,
    val message: String,
    val refs: List<String>,  // stable ids of involved source/derived objects
    val decision: Decision = Decision.PENDING,
    val rationale: String = ""
)

@Serializable
data class TraceSegment(
    val index: Int,
    val startOrd: Int,
    val endOrd: Int,
    val states: List<TapState>,   // state AFTER each edge, aligned with edge ords
    val endedBy: String?          // reset event id, "illegal", or null (ran to end)
)

@Serializable
data class TapTrace(
    val segments: List<TraceSegment>,
    val events: List<DeriveEvent>
) {
    val states: List<TapState> get() = segments.flatMap { it.states }
}

@Serializable
enum class RegKind { IR, DR }

@Serializable
data class ShiftStream(
    val id: String,
    val kind: RegKind,
    val segmentIndex: Int,
    val startOrd: Int,
    val tdiBits: List<Int>,
    val tdoBits: List<Int>
)

@Serializable
data class DevicePlacement(
    val pos: Int,
    val name: String?,       // null when unidentified
    val idcode: String?,     // hex string when captured
    val irLength: Int?,      // null while ambiguous
    val bypassObserved: Boolean
)

/** One legal way to place devices on the chain; all legal candidates are kept. */
@Serializable
data class ChainCandidate(
    val id: String,
    val devices: List<DevicePlacement>,
    val cellOffset: Map<String, Int>, // device name -> boundary-cell offset from TDO end
    val note: String
)

@Serializable
data class ChainModel(
    val candidates: List<ChainCandidate>,
    val events: List<DeriveEvent>
) {
    val primary get() = candidates.first()
}

@Serializable
data class CellPinEvidence(
    val id: String,
    val device: String,
    val cellIndex: Int,
    val port: String,
    val pin: String?,
    val function: String,
    val chainOffset: Int,    // bit offset from TDO end of the boundary-scan register
    val bsdlVersion: String,
    val src: String          // provenance: bsdl map id
)
