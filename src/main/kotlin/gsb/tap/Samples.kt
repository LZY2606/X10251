package gsb.tap

/** Raw logic level as sampled; X/Z are preserved, never silently coerced. */
enum class Level { LOW, HIGH, X, Z;
    companion object {
        fun of(token: String): Level = when (token.trim().uppercase()) {
            "0", "L", "LOW" -> LOW
            "1", "H", "HIGH" -> HIGH
            "X" -> X
            "Z" -> Z
            else -> X
        }
    }
    val isDefined get() = this == LOW || this == HIGH
    val bit get() = this == HIGH
}

data class Diagnostic(val code: String, val message: String, val position: String)

/** One raw sample row. [timeRaw] keeps the original representation; [timeNs] is the
 *  normalized derived value (null when not convertible, see diagnostics). */
data class SampleRow(
    val index: Int,
    val timeRaw: String,
    val timeNs: Double?,
    val tck: Level, val tms: Level, val tdi: Level, val tdo: Level, val trst: Level,
)

data class Capture(
    val name: String,
    /** Header metadata exactly as imported (units, level thresholds, edge basis). */
    val meta: Map<String, String>,
    val rows: List<SampleRow>,
    val diagnostics: List<Diagnostic>,
) {
    val edgeBasis: String get() = meta["edge_basis"] ?: "rising"
}

object CaptureParser {
    /** Parse a capture CSV. Raw text is kept by callers for the source store; here we
     *  only normalize what we safely can and attach diagnostics for the rest. */
    fun parse(name: String, text: String): Capture {
        val meta = mutableMapOf<String, String>()
        val rows = mutableListOf<SampleRow>()
        val diags = mutableListOf<Diagnostic>()
        var headerSeen = false
        text.lines().forEachIndexed { lineNo, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEachIndexed
            if (line.startsWith("#")) {
                val body = line.removePrefix("#").trim()
                val kv = body.split(":", limit = 2)
                if (kv.size == 2) meta[kv[0].trim()] = kv[1].trim()
                return@forEachIndexed
            }
            val cols = line.split(",").map { it.trim() }
            if (!headerSeen) { headerSeen = true; return@forEachIndexed } // column header
            if (cols.size < 7) {
                diags += Diagnostic("BAD_ROW", "expected 7 columns, got ${cols.size}", "line ${lineNo + 1}")
                return@forEachIndexed
            }
            val idx = cols[0].toIntOrNull() ?: -1
            val timeRaw = cols[1]
            val timeNs = normalizeTimeNs(timeRaw)
            if (timeNs == null)
                diags += Diagnostic("UNIT_UNCONVERTIBLE", "cannot normalize time '$timeRaw' to ns; raw value kept in place", "row $idx")
            rows += SampleRow(
                index = idx, timeRaw = timeRaw, timeNs = timeNs,
                tck = Level.of(cols[2]), tms = Level.of(cols[3]), tdi = Level.of(cols[4]),
                tdo = Level.of(cols[5]), trst = Level.of(cols[6]),
            )
        }
        return Capture(name, meta, rows, diags)
    }

    /** Normalize a time literal to nanoseconds. Supports ns/us/ms/suffix-less (assumed ns). */
    fun normalizeTimeNs(raw: String): Double? {
        val m = Regex("""^([0-9]*\.?[0-9]+)\s*(ns|us|µs|ms|s)?$""").match(raw.trim()) ?: return null
        val v = m.groupValues[1].toDoubleOrNull() ?: return null
        return when (m.groupValues[2]) {
            "", "ns" -> v
            "us", "µs" -> v * 1_000.0
            "ms" -> v * 1_000_000.0
            "s" -> v * 1_000_000_000.0
            else -> null
        }
    }
}

enum class SampleEdge { RISING, FALLING;
    companion object { fun of(s: String) = if (s.trim().lowercase() == "falling") FALLING else RISING }
}

/** One TCK active-edge sample presented to the TAP reconstruction. */
data class EdgeSample(
    val clock: Int,
    val timeNs: Double?,
    val tms: Level, val tdi: Level, val tdo: Level, val trst: Level,
)

object EdgeExtractor {
    /** Extract active-edge samples according to the sampling polarity parameter. */
    fun extract(capture: Capture, edge: SampleEdge): List<EdgeSample> {
        val out = mutableListOf<EdgeSample>()
        var clock = 0
        var prev: SampleRow? = null
        for (row in capture.rows) {
            val p = prev
            if (p != null && p.tck.isDefined && row.tck.isDefined && p.tck != row.tck) {
                val rising = p.tck == Level.LOW && row.tck == Level.HIGH
                val active = (edge == SampleEdge.RISING && rising) || (edge == SampleEdge.FALLING && !rising)
                if (active) {
                    // TDO changes on the inactive edge; the value at the active edge is the
                    // stable output of the previous stage, which is what we record.
                    out += EdgeSample(clock++, row.timeNs, row.tms, row.tdi, row.tdo, row.trst)
                }
            }
            prev = row
        }
        return out
    }
}
