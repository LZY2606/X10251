package jtag.derive

import kotlinx.serialization.Serializable

/**
 * Machine-readable judgment event. Every rule firing (illegal transition,
 * mid-scan reset, ambiguity retained, polarity mismatch, BSDL version change,
 * human decision, derivation reuse/recompute) is recorded as one of these.
 */
@Serializable
data class JudgmentEvent(
    val id: String,
    val kind: String,
    val severity: String,            // INFO | WARNING | ERROR | DECISION
    val message: String,
    val subjects: List<String> = emptyList(),   // stable ids of affected objects
    val evidence: Map<String, String> = emptyMap(),
    val basis: String                // machine-readable rule id backing the judgment
)

object Rules {
    const val ILLEGAL_TRANSITION = "R-TAP-001: illegal TAP transitions must be surfaced, never silently skipped"
    const val MID_SCAN_RESET = "R-TAP-002: mid-scan reset aborts in-progress shift; partial capture preserved as evidence"
    const val IR_NOT_FROM_IDCODE = "R-IR-001: IR length must not be decided from IDCODE alone"
    const val IR_AMBIGUITY_KEPT = "R-IR-002: all legal IR length candidates retained when not uniquely decidable"
    const val MISSING_DEVICE_OFFSETS = "R-CHAIN-001: missing device on chain keeps all offset candidates"
    const val POLARITY_CHECK = "R-SAMPLE-001: sampling polarity inconsistency reported, decode not silently flipped"
    const val BSDL_VERSION_DELTA = "R-BSDL-001: pin-mapping version change propagates only to dependent derivations"
    const val UNIT_RAW_KEPT = "R-UNIT-001: unconvertible values stay raw at original position with diagnostic"
    const val DECISION_KEEPS_LOSERS = "R-DEC-001: unchosen candidates are retained; confirmation is never an irreversible overwrite"
    const val CRASH_ATOMICITY = "R-STORE-001: current pointer switches only after a fully successful write"
    const val CONFLICT_BOTH_KEPT = "R-STORE-002: concurrent edits from one version yield a resolvable conflict, both branches stored"
}
