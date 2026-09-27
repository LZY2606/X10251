package app.model

import kotlinx.serialization.Serializable

/**
 * Everything shown in the UI and stored in the database carries a stable identity.
 * Source-domain objects (samples, resets, IR candidates, BSDL maps, board revisions)
 * live independently; derived-domain objects (trajectory steps, bits, chain nodes,
 * cell->pin evidence, derivations) reference them by id.
 */

// ---------------------------------------------------------------- sources ---

@Serializable
data class SampleSet(
    val id: String,
    val rev: Int,
    val name: String,
    /** "tms-rises-with-tck-rising" etc, kept verbatim from the import header. */
    val originalEncoding: String,
    /** Engineering units / coordinate basis, retained verbatim. */
    val timeUnit: String,
    val levels: List<String>,
    val polarity: String,            // "rising" | "falling" | "unknown"
    val sampleOn: String,            // "tck-rising" | "tck-falling"
    val samples: List<RawSample>,
    val createdAt: Long,
)

@Serializable
data class RawSample(
    val index: Int,
    val timeRaw: String,             // original textual representation, preserved
    val t: Double?,                  // normalized seconds; null => unconvertible
    val tms: Int, val tck: Int, val tdi: Int, val tdo: Int,
    val diagnostic: String? = null,
)

@Serializable
data class ResetEvent(
    val id: String,
    val rev: Int,
    val sampleIndex: Int,
    val kind: String,                // "power-on" | "tms-5-ones" | "trst"
    val sourceNote: String,
)

@Serializable
data class IrCandidate(
    val id: String,
    val rev: Int,
    val devicePosition: Int,
    val lengthBits: Int,
    val basis: List<String>,         // evidence ids / rationale refs
    val rank: Double,                // lower is better; ties => genuinely ambiguous
)

@Serializable
data class BsdlPinMap(
    val id: String,
    val rev: Int,
    val bsdlVersion: String,
    val deviceIdcode: String,
    val irLength: Int,
    val idcodeInstruction: String,
    val cells: List<BoundaryCell>,
    val rawTextRef: String,
    val createdAt: Long,
)

@Serializable
data class BoundaryCell(
    val cellNumber: Int,
    val portName: String,
    val pin: String,
    val function: String,            // input/output/bidir/control/observe
    val safe: String,
)

@Serializable
data class BoardRevision(
    val id: String,
    val rev: Int,
    val boardRev: String,
    /** Expected devices in chain order with expected IR length; null => slot may be empty. */
    val slots: List<ChainSlot>,
    val note: String,
)

@Serializable
data class ChainSlot(
    val position: Int,
    val expectedIdcode: String?,
    val expectedIrLength: Int?,
    val populated: Boolean,
)

// ---------------------------------------------------------------- derived ---

@Serializable
data class TapStep(
    val id: String,                  // stable: "step:<sampleSetId>:<edgeIndex>"
    val edgeIndex: Int,
    val sampleIndex: Int,
    val tms: Int, val tdi: Int, val tdo: Int,
    val fromState: String,
    val toState: String,
    val illegal: Boolean,
    val reset: Boolean,
    val note: String? = null,
)

@Serializable
data class ShiftBit(
    val id: String,                  // stable: "bit:<sampleSetId>:<edgeIndex>"
    val edgeIndex: Int,
    val reg: String,                 // "IR" | "DR"
    val position: Int,
    val tdi: Int,
    val tdo: Int,
    val resetDuringShift: Boolean,
)

@Serializable
data class ChainNode(
    val id: String,
    val position: Int,
    val idcode: String?,
    val name: String?,
    val irLength: Int?,
    val bypass: Boolean,
    val populated: Boolean,
    val missing: Boolean,
    val offsetCandidates: List<Int>,
    val bsdlPinMapId: String?,
)

@Serializable
data class CellPinEvidence(
    val id: String,
    val chainNodeId: String,
    val cellNumber: Int,
    val portName: String,
    val pin: String,
    val function: String,
    val bsdlPinMapId: String,
    val bsdlVersion: String,
)

@Serializable
data class Derivation(
    val id: String,                  // stable identity per logical result
    val version: Int,                // independent derivation version
    val parentVersion: Int?,
    val kind: String,                // trajectory|bitstream|chain|cellmap
    val status: String,              // committed | candidate | rejected | superseded
    val sourceRefs: Map<String, Int>, // source kind/table -> source rev
    val outputIds: List<String>,
    val reason: String,
    val createdAt: Long,
    val decisionId: String?,
    val stillValid: Boolean,
    val validityReason: String,
)

// ---------------------------------------------------------------- events ----

@Serializable
data class DomainEvent(
    val id: String,
    val ts: Long,
    val type: String,
    val severity: String,            // info | warning | error | decision
    val message: String,
    val sourceRefs: Map<String, String>,
    val affectedIds: List<String>,
    /** machine-readable judgement predicates, e.g. ["illegal_tap_transition_recorded"] */
    val predicates: List<String>,
    val accepted: Boolean,
    val derivationVersion: Int?,
)

@Serializable
data class Decision(
    val id: String,
    val ts: Long,
    val kind: String,                // accept-candidate | reject-candidate | param-adjust
    val targetDerivationId: String,
    val chosenVersion: Int,
    val rivalVersion: Int?,
    val comment: String,
    val basisEventIds: List<String>,
)
