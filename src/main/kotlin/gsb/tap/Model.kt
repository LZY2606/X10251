package gsb.tap

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

/** A domain quantity that keeps its original textual representation plus unit/coordinate basis.
 *  Normalization happens only in the derived layer; unconvertible values stay raw with a diagnostic. */
@Serializable
data class Quantity(
    val raw: String,
    val unit: String? = null,          // e.g. "ns", "V", "ball" (coordinate basis)
    val normalized: Double? = null,    // derived layer only; null when unconvertible
    val diagnostic: String? = null     // why it could not be converted
) {
    companion object {
        fun parse(raw: String, expectedUnit: String): Quantity {
            val m = Regex("""^\s*([-+0-9.eE]+)\s*([a-zA-Z/%]*)\s*$""").find(raw)
                ?: return Quantity(raw, expectedUnit, null, "unparseable quantity: '$raw'")
            val value = m.groupValues[1].toDoubleOrNull()
                ?: return Quantity(raw, expectedUnit, null, "non-numeric value in '$raw'")
            val unit = m.groupValues[2].ifEmpty { expectedUnit }
            val factor = when (unit) {
                expectedUnit -> 1.0
                "ps" -> if (expectedUnit == "ns") 0.001 else Double.NaN
                "us" -> if (expectedUnit == "ns") 1000.0 else Double.NaN
                "ms" -> if (expectedUnit == "ns") 1e6 else Double.NaN
                "mV" -> if (expectedUnit == "V") 0.001 else Double.NaN
                "kV" -> if (expectedUnit == "V") 1000.0 else Double.NaN
                else -> Double.NaN
            }
            return if (factor.isNaN()) Quantity(raw, unit, null, "cannot convert unit '$unit' to '$expectedUnit'")
            else Quantity(raw, unit, value * factor)
        }
    }
}

/** Machine-readable judgment event. Kinds are stable strings consumed by tests and the UI. */
@Serializable
data class TapEvent(
    val id: String,
    val kind: String,            // MID_STREAM_RESET | ILLEGAL_TRANSITION | IR_LENGTH_AMBIGUOUS | BYPASS_DEVICE |
                                 // CHAIN_DEVICE_MISSING | POLARITY_AMBIGUOUS | MAP_VERSION_CHANGE | DECISION_ACCEPT |
                                 // DECISION_REJECT | DERIVATION_STALE | DERIVATION_VALID | IMPORT | EXPORT | CONFLICT | CONFLICT_RESOLVED
    val versionId: String,
    val summary: String,
    val subjects: List<String> = emptyList(),   // stable ids of affected objects
    val payload: Map<String, String> = emptyMap()
)

@Serializable
data class IrLengthCandidate(
    val id: String,
    val device: String,
    val length: Int,
    val basis: String,          // "observed:seg-A" | "bsdl:cpu.bsdl" | "user"
    val status: String = "open" // open | accepted | rejected  (rejected candidates are kept, never deleted)
)

@Serializable
data class OffsetCandidate(
    val id: String,
    val hypothesis: String,             // e.g. "missing=FPGA" 
    val deviceOffsets: Map<String, Int>, // device -> first boundary-cell offset in chain coordinates
    val retained: Boolean = true
)
