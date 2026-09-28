package gsb.core

/** Tolerant parser for the BSDL subset used by this tool (entity, INSTRUCTION_LENGTH,
 *  IDCODE_REGISTER, BOUNDARY_LENGTH, BOUNDARY_REGISTER, PIN_MAP_STRING). */
object BsdlParser {

    fun parse(name: String, version: String, text: String): BsdlPinMap {
        val entity = Regex("""entity\s+(\w+)\s+is""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1) ?: "UNKNOWN"
        val instrLen = attrInt(text, "INSTRUCTION_LENGTH")
        val boundaryLen = attrInt(text, "BOUNDARY_LENGTH")
        val idcode = attrString(text, "IDCODE_REGISTER")
            ?.replace(Regex("""[^01]"""), "")?.takeIf { it.length == 32 }
        val pinMap = parsePinMap(attrString(text, "PIN_MAP_STRING") ?: "")
        val cells = parseBoundary(attrString(text, "BOUNDARY_REGISTER") ?: "")
        return BsdlPinMap(name, version, entity, instrLen, idcode, boundaryLen, pinMap, cells)
    }

    private fun attrInt(text: String, attr: String): Int? =
        Regex("""attribute\s+$attr\s+of\s+\w+\s*:\s*entity\s+is\s+(\d+)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toInt()

    /** Extracts the (possibly multi-line) quoted body of an attribute. */
    private fun attrString(text: String, attr: String): String? {
        val m = Regex("""attribute\s+$attr\s+of\s+\w+\s*:\s*entity\s+is\s+"""", RegexOption.IGNORE_CASE)
            .find(text) ?: return null
        val sb = StringBuilder()
        var i = m.range.last + 1
        while (i < text.length) {
            val c = text[i]
            if (c == '"') {
                // doubled quote inside BSDL string = literal quote; we do not need it
                if (i + 1 < text.length && text[i + 1] == '"') { i += 2; continue }
                return sb.toString()
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    private fun parsePinMap(body: String): Map<String, String> =
        Regex("""(\w+)\s*:\s*([A-Za-z]\w*)""").findAll(body)
            .associate { it.groupValues[1] to it.groupValues[2] }

    private fun parseBoundary(body: String): List<BoundaryCell> =
        Regex("""(\d+)\s*\(\s*(\w+)\s*,\s*([\w*]+)\s*,\s*(\w+)\s*,\s*(\w+)""")
            .findAll(body).map {
                BoundaryCell(it.groupValues[1].toInt(), it.groupValues[2],
                    it.groupValues[3], it.groupValues[4], it.groupValues[5])
            }.toList()
}
