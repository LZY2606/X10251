package tapreplay.core

/**
 * 导入解析器：只做“原始表示 -> 可规范化量”，绝不丢弃无法转换的值。
 */
object Parsers {

    private val timeUnitNanos = mapOf(
        "ns" to 1L,
        "us" to 1_000L,
        "µs" to 1_000L,
        "ms" to 1_000_000L,
        "s" to 1_000_000_000L,
    )

    /** 规范化时间为纳秒；失败时返回带诊断的 UnitTag，原始字符串仍保留在 edge.rawTime。 */
    fun normalizeTime(raw: String): UnitTag? {
        val t = raw.trim()
        if (t.isEmpty() || t.equals("N/A", true) || t == "-") return null
        val m = Regex("^([+-]?\\d+(?:\\.\\d+)?)\\s*([a-zA-Zµ]+)$").matchEntire(t)
            ?: return UnitTag(t, null, "UNPARSEABLE_TIME_UNIT")
        val value = m.groupValues[1].toDoubleOrNull()
            ?: return UnitTag(t, null, "UNPARSEABLE_TIME_VALUE")
        val factor = timeUnitNanos[m.groupValues[2].lowercase()]
            ?: return UnitTag(t, null, "UNKNOWN_TIME_UNIT:${m.groupValues[2]}")
        return UnitTag(m.groupValues[2].lowercase(), (value * factor).toLong(), null)
    }

    fun parseLogic(raw: String): Int? {
        val t = raw.trim().lowercase()
        return when (t) {
            "1", "h", "high" -> 1
            "0", "l", "low" -> 0
            else -> null // X/Z/空 都保留为 null，并由上层登记诊断
        }
    }

    fun parsePolarity(raw: String): SamplePolarity = when (raw.trim().lowercase()) {
        "rising", "r", "rising_edge", "posedge" -> SamplePolarity.SAMPLE_ON_TCK_RISING
        "falling", "f", "falling_edge", "negedge" -> SamplePolarity.SAMPLE_ON_TCK_FALLING
        else -> SamplePolarity.UNKNOWN
    }
}

/** 极简 CSV（支持引号与逗号转义），返回表头 -> 行。 */
object Csv {
    fun parse(content: String): Pair<List<String>, List<Map<String, String>>> {
        val rows = splitRows(content).map { splitLine(it) }
        if (rows.isEmpty()) return emptyList<String>() to emptyList()
        val header = rows.first().map { it.trim() }
        val data = rows.drop(1)
            .filter { row -> row.any { it.isNotBlank() } }
            .map { row -> header.mapIndexed { i, h -> h to (row.getOrNull(i) ?: "") }.toMap() }
        return header to data
    }

    private fun splitRows(content: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQ = false
        var i = 0
        while (i < content.length) {
            val c = content[i]
            when {
                c == '"' -> {
                    inQ = !inQ
                    cur.append(c)
                }
                c == '\n' && !inQ -> { out.add(cur.toString()); cur.clear() }
                c == '\r' -> {}
                else -> cur.append(c)
            }
            i++
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private fun splitLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQ = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> inQ = !inQ
                c == ',' && !inQ -> { out.add(cur.toString()); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out.add(cur.toString())
        return out
    }
}
