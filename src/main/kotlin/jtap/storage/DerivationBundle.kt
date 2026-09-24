package jtap.storage

import jtap.core.DeviceSlot
import jtap.core.ScanSegment
import jtap.core.TapTransition
import jtap.model.IrLengthCandidate
import jtap.model.JudgementEvent

data class DepNodeDto(
    val nodeId: String,
    val kind: String,
    val sourceFingerprint: String,
    val status: String,
    val rationale: String,
)

data class DepEdgeDto(val from: String, val to: String)

/** Immutable snapshot of one derivation, written and pointer-flipped atomically. */
data class DerivationBundle(
    val caseId: String,
    val parentDerivationId: String?,
    val basis: Map<String, String>,
    val fingerprint: String,
    val parentFingerprint: String?,
    val transitions: List<TapTransition>,
    val segments: List<ScanSegment>,
    val slots: List<DeviceSlot>,
    val irCandidates: List<IrLengthCandidate>,
    val events: List<JudgementEvent>,
    val displayValues: List<Triple<String, String, String>>, // kind, stableKey, json
    val nodes: List<DepNodeDto>,
    val edges: List<DepEdgeDto>,
)

data class StoredDerivation(
    val derivationId: String,
    val ordinal: Long,
    val parentDerivationId: String?,
    val status: String,
    val basis: Map<String, String>,
    val fingerprint: String,
    val parentFingerprint: String?,
    val createdAt: String,
)

data class DecisionDto(
    val decisionId: String,
    val ordinal: Long,
    val atEventId: String?,
    val action: String,
    val chosenCandidateId: String?,
    val rejected: List<String>,
    val parentDerivationId: String?,
    val note: String,
    val actor: String,
)
