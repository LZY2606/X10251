package gsb.domain

/**
 * A domain quantity. The raw representation is always preserved; numeric
 * normalization happens only in the derived layer. Values that cannot be
 * converted stay in place and carry a diagnostic.
 */
data class Quantity(
    val raw: String,
    val value: Double?,
    val unit: String?,
    val basis: String?,
    val diagnostic: String?,
) {
    val convertible: Boolean get() = value != null && diagnostic == null
}

object Units {
    private val frequency = mapOf("Hz" to 1.0, "kHz" to 1e3, "MHz" to 1e6, "GHz" to 1e9)
    private val time = mapOf("s" to 1.0, "ms" to 1e-3, "us" to 1e-6, "ns" to 1e-9, "ps" to 1e-12)
    private val voltage = mapOf("V" to 1.0, "mV" to 1e-3)

    private val pattern = Regex("""^([+-]?[0-9]*\.?[0-9]+)\s*([A-Za-z]+)?$""")

    /** Parse a raw textual quantity. Never throws; unconvertible values get a diagnostic. */
    fun parse(raw: String, basis: String? = null): Quantity {
        val m = pattern.matchEntire(raw.trim())
            ?: return Quantity(raw, null, null, basis, "unparseable-quantity")
        val value = m.groupValues[1].toDoubleOrNull()
            ?: return Quantity(raw, null, null, basis, "unparseable-number")
        val unit = m.groupValues[2].ifEmpty { null }
        if (unit == null) return Quantity(raw, value, null, basis, "missing-unit")
        if (frequency.containsKey(unit) || time.containsKey(unit) || voltage.containsKey(unit)) {
            return Quantity(raw, value, unit, basis, null)
        }
        return Quantity(raw, value, unit, basis, "unknown-unit:$unit")
    }

    /** A coordinate (e.g. package pin grid "B3") carries a basis, not a numeric unit. */
    fun coordinate(raw: String, basis: String?): Quantity =
        Quantity(raw, null, null, basis, if (basis == null) "missing-coordinate-basis" else null)

    /** Normalize a time quantity to nanoseconds (derived layer only). */
    fun toNanos(q: Quantity): Double? = when (q.unit) {
        "s" -> q.value?.times(1e9)
        "ms" -> q.value?.times(1e6)
        "us" -> q.value?.times(1e3)
        "ns" -> q.value
        "ps" -> q.value?.div(1e3)
        else -> null
    }

    /** Normalize a frequency quantity to its period in nanoseconds (derived layer only). */
    fun periodNanos(q: Quantity): Double? = when (q.unit) {
        "Hz" -> q.value?.let { 1e9 / it }
        "kHz" -> q.value?.let { 1e6 / it }
        "MHz" -> q.value?.let { 1e3 / it }
        "GHz" -> q.value?.let { 1.0 / it }
        else -> null
    }
}
