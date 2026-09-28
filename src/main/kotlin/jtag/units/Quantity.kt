package jtag.units

/**
 * A domain quantity that always carries its unit or coordinate basis.
 * The raw representation is preserved at import; normalization happens
 * only in the derived layer. Values that cannot be converted stay raw
 * and carry a diagnostic instead of being dropped.
 */
data class Quantity(
    val raw: String,
    val unit: String,
    val basis: String = ""
) {
    override fun toString(): String = if (basis.isEmpty()) "$raw[$unit]" else "$raw[$unit@$basis]"
}

data class Normalized(val value: Double, val unit: String)

data class ConversionResult(
    val normalized: Normalized?,
    val diagnostic: String?   // non-null when conversion is impossible
)

object Units {
    private val timeToNs = mapOf("ps" to 0.001, "ns" to 1.0, "us" to 1_000.0, "ms" to 1_000_000.0, "s" to 1_000_000_000.0)
    private val voltToMv = mapOf("uV" to 0.001, "mV" to 1.0, "V" to 1_000.0)

    fun normalize(q: Quantity, target: String): ConversionResult {
        val numeric = q.raw.trim().toDoubleOrNull()
            ?: return ConversionResult(null, "unparseable numeric value '${q.raw}' kept raw at unit '${q.unit}'")
        val table = when {
            timeToNs.containsKey(q.unit) && timeToNs.containsKey(target) -> timeToNs
            voltToMv.containsKey(q.unit) && voltToMv.containsKey(target) -> voltToMv
            else -> return ConversionResult(
                null,
                "no conversion from '${q.unit}' (basis '${q.basis}') to '$target'; value kept raw at original position"
            )
        }
        val factor = table.getValue(q.unit) / table.getValue(target)
        return ConversionResult(Normalized(numeric * factor, target), null)
    }
}
