package jtag

import kotlinx.serialization.Serializable

/**
 * A domain quantity that always carries its unit or coordinate basis.
 * Raw representation is preserved at import; normalization happens only in the
 * derived layer. Values that cannot be converted keep [value] == null and carry
 * a diagnostic instead of being dropped.
 */
@Serializable
data class Quantity(
    val raw: String,
    val value: Double? = null,
    val unit: String? = null,
    val basis: String? = null,
    val diagnostic: String? = null,
) {
    val convertible: Boolean get() = value != null

    companion object {
        private val NUMERIC = Regex("^\\s*(-?[0-9]+(?:\\.[0-9]+)?)\\s*([a-zA-Z#/%]*)\\s*$")

        /** Parse a raw string like "4200 ns", "3.3 V", "edge#17", "high". */
        fun parse(raw: String, defaultBasis: String? = null): Quantity {
            val m = NUMERIC.matchEntire(raw) ?: return Quantity(
                raw = raw, diagnostic = "unconvertible: no numeric value in '$raw'; kept at original position"
            )
            val num = m.groupValues[1].toDouble()
            val unit = m.groupValues[2].ifEmpty { null }
            return when (unit) {
                null, "ns", "us", "ms", "V", "mV", "bit", "bits" ->
                    Quantity(raw = raw, value = num, unit = unit, basis = defaultBasis)
                else ->
                    if (unit.startsWith("#")) Quantity(raw = raw, value = num, unit = null, basis = "sample_index")
                    else Quantity(raw = raw, diagnostic = "unconvertible: unknown unit '$unit' in '$raw'; kept at original position")
            }
        }

        fun of(value: Double, unit: String, basis: String? = null) =
            Quantity(raw = "$value $unit", value = value, unit = unit, basis = basis)
    }
}

/** Convert to nanoseconds when possible, else null (caller keeps raw + diagnostic). */
fun Quantity.toNs(): Double? = when (unit) {
    "ns" -> value
    "us" -> value?.times(1_000.0)
    "ms" -> value?.times(1_000_000.0)
    else -> null
}
