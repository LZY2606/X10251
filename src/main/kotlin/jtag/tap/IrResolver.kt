package jtag.tap

import kotlinx.serialization.Serializable

@Serializable
data class IrLengthCandidate(val deviceIndex: Int, val lengthBits: Int, val rationale: String)

@Serializable
data class IrCandidateSet(val candidates: List<IrLengthCandidate>)

@Serializable
data class IrResolution(
    val consistent: List<IrLengthCandidate>,   // all candidates consistent with evidence
    val events: List<TapEvent>
)

/**
 * Resolves IR lengths against observed Shift-IR capture patterns.
 *
 * Rule: IR length must NOT be guessed from IDCODE alone. All candidates that
 * remain consistent with the observed capture pattern (LSB bits "01" per
 * IEEE 1149.1) are kept; if more than one survives, an IR_LENGTH_AMBIGUOUS
 * event is emitted and every candidate is preserved.
 */
object IrResolver {

    fun resolve(irSegment: ShiftSegment, candidates: IrCandidateSet): IrResolution {
        val events = mutableListOf<TapEvent>()
        val tdo = irSegment.tdoBits
        val consistent = candidates.candidates.filter { cand ->
            // Capture-IR loads ...01 in the two LSBs; those are the first two bits shifted out.
            tdo.size >= cand.lengthBits &&
                tdo[0] == 1 && tdo[1] == 0 &&
                // segment length must be compatible with total chain IR length assumptions
                cand.lengthBits <= tdo.size
        }
        when {
            consistent.isEmpty() -> events += TapEvent(
                "IR_LENGTH_NO_CONSISTENT_CANDIDATE", irSegment.startEdge, "ERROR",
                "No IR length candidate is consistent with observed capture pattern; all candidates retained for review",
                mapOf("candidates" to candidates.candidates.joinToString(";") { "${it.deviceIndex}:${it.lengthBits}" })
            )
            consistent.size > 1 -> events += TapEvent(
                "IR_LENGTH_AMBIGUOUS", irSegment.startEdge, "WARN",
                "IR length is ambiguous; ${consistent.size} candidates consistent with evidence. IDCODE alone must not decide IR length.",
                mapOf("candidates" to consistent.joinToString(";") { "dev${it.deviceIndex}=${it.lengthBits}bits" })
            )
            else -> events += TapEvent(
                "IR_LENGTH_RESOLVED", irSegment.startEdge, "INFO",
                "IR length uniquely resolved to ${consistent[0].lengthBits} bits for device ${consistent[0].deviceIndex}"
            )
        }
        return IrResolution(if (consistent.isEmpty()) candidates.candidates else consistent, events)
    }
}
