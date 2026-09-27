package jtagreplay.units

/**
 * 领域量：导入时保留原始表示（raw + unit + basis），派生层才规范化。
 * 无法转换的值留在原位置并附诊断，绝不为了画图丢弃。
 */
data class Quantity(
    val raw: String,
    val unit: String,
    val basis: String = ""
) {
    fun toCanonicalNs(): Double? = normalizeTime(raw, unit)
    fun toCanonicalVolts(): Double? = normalizeVoltage(raw, unit)
}

data class UnitDiagnostic(
    val where: String,
    val raw: String,
    val unit: String,
    val reason: String
)

fun normalizeTime(raw: String, unit: String): Double? {
    val v = raw.toDoubleOrNull() ?: return null
    return when (unit.lowercase()) {
        "ns" -> v
        "us", "µs" -> v * 1_000.0
        "ms" -> v * 1_000_000.0
        "s" -> v * 1_000_000_000.0
        "samples" -> null // 需要采样率基准，不能凭空换算
        else -> null
    }
}

fun normalizeVoltage(raw: String, unit: String): Double? {
    val v = raw.toDoubleOrNull() ?: return null
    return when (unit.lowercase()) {
        "v" -> v
        "mv" -> v / 1000.0
        else -> null
    }
}
