package gsb.core

/** Parses CSV sample logs. Header carries units and probe settings; raw text is kept
 *  verbatim in SampleRow.timeRaw so nothing is lost before normalization. */
object SampleLogParser {

    data class ParseResult(val log: SampleLog, val diagnostics: List<String>)

    fun parse(name: String, text: String): ParseResult {
        val diagnostics = mutableListOf<String>()
        var edge = "rising"; var polarity = "normal"
        val rows = mutableListOf<SampleRow>()
        var ordinal = 0
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                val meta = Regex("""(\w+)\s*=\s*([\w-]+)""").findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
                meta["sample_edge"]?.let { edge = it }
                meta["tdo_polarity"]?.let { polarity = it }
                continue
            }
            if (line.startsWith("ordinal")) continue   // header row
            val cols = line.split(",").map { it.trim() }
            if (cols.size < 6) { diagnostics.add("unparseable line kept raw: '$line'"); continue }
            val timeRaw = cols[1]
            val unit = Regex("""[a-zA-Z]+$""").find(timeRaw)?.value ?: ""
            if (unit.isEmpty()) diagnostics.add("ordinal $ordinal: time '$timeRaw' has no unit; kept raw, excluded from ps normalization")
            rows.add(SampleRow(ordinal++, timeRaw, unit,
                cols[2].toInt(), cols[3].toInt(), cols[4].toInt(), cols[5].toInt()))
        }
        return ParseResult(SampleLog(name, edge, polarity, rows), diagnostics)
    }

    /** Normalize a raw time to picoseconds; null when the unit is unknown. */
    fun timeToPs(raw: String): Long? {
        val m = Regex("""^([\d.]+)(ps|ns|us|ms)?$""").find(raw) ?: return null
        val v = m.groupValues[1].toDoubleOrNull() ?: return null
        return when (m.groupValues[2]) {
            "ps" -> v.toLong()
            "ns" -> (v * 1_000).toLong()
            "us" -> (v * 1_000_000).toLong()
            "ms" -> (v * 1_000_000_000).toLong()
            else -> null
        }
    }
}
