package jtap.model

/**
 * All physical quantities carry an explicit coordinate/unit basis.
 * A quantity can stay UNCONVERTED when the import representation cannot be
 * normalised; in that case [raw] is preserved verbatim and [diagnostic]
 * explains why. Such values must never be dropped for visualisation.
 */
enum class UnitTag(val canonical: String) {
    NANOSECOND("ns"), MICROSECOND("us"), MILLISECOND("ms"), SECOND("s"),
    TCK_CYCLE("tck"), BIT_INDEX("bit"), CHAIN_POSITION("pos"),
    PIN_NAME("pin"), BSDL_CELL_NUMBER("cell"), DIMENSIONLESS(""),
    UNCONVERTED("unconverted");
}

data class Quantity(
    val value: Double?,
    val unit: UnitTag,
    val raw: String,
    val diagnostic: String? = null,
) {
    val converted: Boolean get() = value != null && unit != UnitTag.UNCONVERTED

    fun asNanoseconds(): Double? = when (unit) {
        UnitTag.NANOSECOND -> value
        UnitTag.MICROSECOND -> value?.times(1_000.0)
        UnitTag.MILLISECOND -> value?.times(1_000_000.0)
        UnitTag.SECOND -> value?.times(1_000_000_000.0)
        UnitTag.TCK_CYCLE -> null
        else -> null
    }

    companion object {
        private val UNIT_PATTERN = Regex("""^([-+0-9.eE]+)\s*([a-zA-Z]*)$""")

        fun parse(raw0: String?): Quantity {
            val raw = raw0?.trim().orEmpty()
            if (raw.isEmpty()) {
                return Quantity(null, UnitTag.UNCONVERTED, raw, "empty value")
            }
            val m = UNIT_PATTERN.matchEntire(raw)
                ?: return Quantity(null, UnitTag.UNCONVERTED, raw, "unparseable quantity")
            val num = m.groupValues[1].toDoubleOrNull()
                ?: return Quantity(null, UnitTag.UNCONVERTED, raw, "numeric part out of range")
            val tag = when (m.groupValues[2].lowercase()) {
                "ns" -> UnitTag.NANOSECOND
                "us", "µs" -> UnitTag.MICROSECOND
                "ms" -> UnitTag.MILLISECOND
                "s" -> UnitTag.SECOND
                "" -> UnitTag.DIMENSIONLESS
                else -> return Quantity(null, UnitTag.UNCONVERTED, raw, "unknown unit '${m.groupValues[2]}'")
            }
            return Quantity(num, tag, raw)
        }

        fun tckCycle(index: Long): Quantity =
            Quantity(index.toDouble(), UnitTag.TCK_CYCLE, index.toString())
    }
}

/** Coordinate basis for a pin: BSDL port name plus optional board net. */
data class PinCoordinate(val bsdlPort: String, val boardNet: String?, val boardRevision: String)
