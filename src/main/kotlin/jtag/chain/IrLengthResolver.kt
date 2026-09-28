package jtag.chain

import jtag.derive.JudgmentEvent
import jtag.derive.Rules
import java.util.UUID

data class IrLengthCandidate(
    val id: String,
    val deviceId: String,
    val length: Int,
    val source: String,       // BSDL | CAPTURE_PATTERN | IDCODE_GUESS
    val confidence: String,   // AUTHORITATIVE | OBSERVED | INSUFFICIENT
    var status: String = "RETAINED"   // RETAINED | ACCEPTED | REJECTED
)

/**
 * Resolves IR length candidates. IDCODE alone is never sufficient to fix the
 * IR length (it describes the DR path); ambiguity keeps every legal candidate.
 */
object IrLengthResolver {
    fun resolve(
        deviceId: String,
        bsdlLength: Int?,
        capturePatternLengths: List<Int>,
        idcodeGuess: Int?
    ): Pair<List<IrLengthCandidate>, List<JudgmentEvent>> {
        val out = mutableListOf<IrLengthCandidate>()
        val events = mutableListOf<JudgmentEvent>()
        var n = 0
        fun cid() = "irc-%s-%02d".format(deviceId, ++n)

        bsdlLength?.let { out += IrLengthCandidate(cid(), deviceId, it, "BSDL", "AUTHORITATIVE") }
        capturePatternLengths.distinct().forEach { out += IrLengthCandidate(cid(), deviceId, it, "CAPTURE_PATTERN", "OBSERVED") }
        idcodeGuess?.let {
            out += IrLengthCandidate(cid(), deviceId, it, "IDCODE_GUESS", "INSUFFICIENT")
            events += JudgmentEvent(
                id = "ev-" + UUID.randomUUID(), kind = "IR_IDCODE_INSUFFICIENT", severity = "WARNING",
                message = "IR length candidate $it from IDCODE for $deviceId marked INSUFFICIENT; IDCODE alone cannot determine IR length",
                subjects = listOf(deviceId), evidence = mapOf("candidate" to it.toString(), "source" to "IDCODE_GUESS"),
                basis = Rules.IR_NOT_FROM_IDCODE
            )
        }

        val lengths = out.map { it.length }.distinct()
        if (lengths.size > 1) {
            events += JudgmentEvent(
                id = "ev-" + UUID.randomUUID(), kind = "IR_LENGTH_AMBIGUOUS", severity = "WARNING",
                message = "IR length for $deviceId not uniquely decidable; retaining candidates $lengths",
                subjects = listOf(deviceId), evidence = mapOf("candidates" to lengths.joinToString(",")),
                basis = Rules.IR_AMBIGUITY_KEPT
            )
        }
        return out to events
    }
}
