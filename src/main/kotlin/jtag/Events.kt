package jtag

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Machine-readable judgment event. Every rule decision that must not be
 * silently skipped is recorded as one of these.
 */
@Serializable
data class JudgmentEvent(
    val id: String,
    val type: String,
    /** stable rule identifier, e.g. "illegal-transition-not-skippable" */
    val rule: String,
    val payload: Map<String, String> = emptyMap(),
    /** ids of source materials / artifacts / decisions this judgment is based on */
    val sources: List<String> = emptyList(),
)

object EventTypes {
    const val MID_SCAN_RESET = "MID_SCAN_RESET"
    const val ILLEGAL_TRANSITION = "ILLEGAL_TRANSITION"
    const val IR_LENGTH_AMBIGUOUS = "IR_LENGTH_AMBIGUOUS"
    const val CHAIN_DEVICE_MISSING = "CHAIN_DEVICE_MISSING"
    const val BYPASS_DEVICE_OBSERVED = "BYPASS_DEVICE_OBSERVED"
    const val SAMPLING_POLARITY_VIOLATION = "SAMPLING_POLARITY_VIOLATION"
    const val MAPPING_VERSION_CHANGED = "MAPPING_VERSION_CHANGED"
    const val QUANTITY_UNCONVERTIBLE = "QUANTITY_UNCONVERTIBLE"
    const val SHIFT_INVALIDATED = "SHIFT_INVALIDATED"
    const val DECISION_MADE = "DECISION_MADE"
    const val CANDIDATE_RETAINED = "CANDIDATE_RETAINED"
}

object Rules {
    const val ILLEGAL_TRANSITION_NOT_SKIPPABLE = "illegal-transition-not-skippable"
    const val MID_RESET_NOT_SKIPPABLE = "mid-reset-not-skippable"
    const val IR_LENGTH_NOT_FROM_IDCODE = "ir-length-not-from-idcode"
    const val MISSING_DEVICE_KEEPS_OFFSETS = "missing-device-keeps-offset-candidates"
    const val CANDIDATES_KEPT_WHEN_UNDECIDABLE = "candidates-kept-when-undecidable"
}
