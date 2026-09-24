package jtap.core

import jtap.model.CaptureEdge
import jtap.model.PolarityConfig
import jtap.model.Quantity
import jtap.model.SampleEdge
import jtap.model.SampleSource

/**
 * Imports raw TMS/TCK/TDI/TDO sample text.
 *
 * Import keeps the ORIGINAL representation. Normalisation happens in the
 * derivation layer: values that cannot be converted stay at their original
 * position with a diagnostic instead of being dropped.
 */
object SampleImporter {
    private val HEADER =
        Regex("""index\s*,\s*time\s*,\s*tms\s*,\s*tck\s*,\s*tdi\s*,\s*tdo(\s*,\s*declared)?\s*""",
            RegexOption.IGNORE_CASE)

    fun parsePolarity(raw: String?): PolarityConfig {
        if (raw.isNullOrBlank()) {
            return PolarityConfig(CaptureEdge.RISING, rawText = "", diagnostic = "defaulted to rising-edge")
        }
        var edge = CaptureEdge.RISING
        var inv = BooleanArray(3)
        var diagnostic: String? = null
        raw.split(Regex("[,;\\s]+")).filter { it.isNotBlank() }.forEach { token ->
            val (k, v) = token.split('=', limit = 2).let {
                it.first().lowercase() to (it.getOrNull(1)?.lowercase() ?: "")
            }
            when (k) {
                "edge" -> edge = when (v) {
                    "rising", "rise", "posedge", "1" -> CaptureEdge.RISING
                    "falling", "fall", "negedge", "0" -> CaptureEdge.FALLING
                    else -> CaptureEdge.UNCONVERTED.also { diagnostic = "unknown capture edge '$v'" }
                }
                "invert_tms" -> inv[0] = v in setOf("1", "true", "yes", "inverted")
                "invert_tdi" -> inv[1] = v in setOf("1", "true", "yes", "inverted")
                "invert_tdo" -> inv[2] = v in setOf("1", "true", "yes", "inverted")
                else -> diagnostic = (diagnostic?.plus("; ") ?: "") + "unknown polarity key '$k'"
            }
        }
        return PolarityConfig(edge, inv[0], inv[1], inv[2], raw, diagnostic)
    }

    fun import(
        sourceId: String,
        revision: Int,
        label: String,
        polarityRaw: String?,
        csv: String,
    ): SampleSource {
        val polarity = parsePolarity(polarityRaw)
        val lines = csv.lines().filter { it.isNotBlank() }
        require(lines.isNotEmpty()) { "sample payload is empty" }
        require(HEADER.matches(lines.first().trim())) {
            "sample header must be 'index,time,tms,tck,tdi,tdo[,declared]' but was '${lines.first()}'"
        }
        val edges = lines.drop(1).mapIndexed { rowNo, line ->
            val cols = line.split(',').map { it.trim() }
            require(cols.size >= 6) { "sample row $rowNo has ${cols.size} columns, need >= 6" }
            val diagnostics = mutableListOf<String>()
            val index = cols[0].toLongOrNull()
                ?: error("sample row $rowNo: non-numeric index '${cols[0]}'")
            val at = Quantity.parse(cols[1])
            if (!at.converted) diagnostics += "time '${cols[1]}' left unconverted: ${at.diagnostic}"
            listOf("tms" to cols[2], "tck" to cols[3], "tdi" to cols[4], "tdo" to cols[5])
                .forEach { (name, v) ->
                    if (TapFsm.normalizeLevel(v) == null && v.isNotEmpty())
                        diagnostics += "$name level '$v' is non-binary"
                }
            SampleEdge(
                edgeIndex = index,
                at = at,
                tms = cols[2], tck = cols[3], tdi = cols[4], tdo = cols[5],
                declaredState = cols.getOrNull(6)?.ifBlank { null },
                diagnostics = diagnostics,
            )
        }
        return SampleSource(sourceId, revision, label, polarity, edges, csv)
    }
}
