package jtap.model

/**
 * Machine-readable judgement events. These are the auditable basis for the
 * three non-negotiable rules of the tool:
 *  - illegal TAP transitions / mid-scan resets are never silently skipped
 *  - IR length is never guessed from IDCODE alone
 *  - missing chain devices keep their offset candidates
 */
enum class EventSeverity { INFO, WARNING, ANOMALY, CONFLICT, DECISION }

enum class RuleCode(val machineRule: String) {
    ILLEGAL_TAP_TRANSITION("tap.illegal_transition.must_surface"),
    MID_SCAN_RESET("tap.mid_scan_reset.must_surface"),
    IRLENGTH_IDCODE_ONLY("irlength.idcode_only_guess.forbidden"),
    MISSING_DEVICE_OFFSET("chain.missing_device.preserve_offset_candidates"),
    POLARITY_UNVERIFIED("sampling.polarity.keep_invalid_hypothesis"),
    MAPPING_VERSION_DRIFT("bsdl.mapping_version.drift_surface"),
    BYPASS_DEVICE("chain.bypass_device.one_bit_pad"),
    MULTI_SOLUTION_RETAIN("solve.ambiguous.retain_all_legal_candidates"),
}

data class JudgementEvent(
    val eventId: String,
    val rule: RuleCode,
    val severity: EventSeverity,
    val message: String,
    val edgeIndex: Long? = null,
    val subjectIds: List<String> = emptyList(),
    val candidates: List<String> = emptyList(),
    val adopted: Boolean? = null,
    val basis: List<String> = emptyList(),
)

/** A review decision (accept / reject / retune) — never a destructive overwrite. */
data class DecisionRecord(
    val decisionId: String,
    val atEventId: String?,
    val action: String,
    val chosenCandidateId: String?,
    val rejectedCandidateIds: List<String>,
    val parentDerivationId: String?,
    val note: String,
    val actor: String,
    val ordinal: Long,
)
