package gsb.jtag

import kotlinx.serialization.Serializable

/**
 * A domain quantity. The raw representation from the source file is always
 * preserved; normalization happens only in the derived layer. Values that
 * cannot be converted stay in place with a diagnostic and are never dropped.
 */
@Serializable
data class Quant(
    val raw: String,
    val value: Double?,      // normalized SI value, null when unconvertible
    val unit: String?,       // canonical unit ("s", "V", "edge#", ...)
    val basis: String?,      // coordinate basis, e.g. "t0=trigger" or "sample-clock"
    val diagnostic: String?  // non-null when conversion failed
) {
    val convertible get() = diagnostic == null

    companion object {
        private val TIME = mapOf("ps" to 1e-12, "ns" to 1e-9, "us" to 1e-6, "ms" to 1e-3, "s" to 1.0)
        private val VOLT = mapOf("mV" to 1e-3, "V" to 1.0)

        fun parse(raw: String, basis: String? = null): Quant {
            val t = raw.trim()
            val m = Regex("""^([+-]?\d+(?:\.\d+)?)\s*([a-zA-Zμ#]+)?$""").matchEntire(t)
                ?: return Quant(raw, null, null, basis, "unparseable quantity: '$raw'")
            val num = m.groupValues[1].toDoubleOrNull()
                ?: return Quant(raw, null, null, basis, "unparseable number in '$raw'")
            val u = m.groupValues[2]
            return when {
                u.isEmpty() -> Quant(raw, num, "1", basis, null)
                u == "edge#" -> Quant(raw, num, "edge#", basis ?: "edge-index", null)
                TIME.containsKey(u) -> Quant(raw, num * TIME[u]!!, "s", basis, null)
                VOLT.containsKey(u) -> Quant(raw, num * VOLT[u]!!, "V", basis, null)
                else -> Quant(raw, null, null, basis, "unknown unit '$u' in '$raw'")
            }
        }
    }
}
