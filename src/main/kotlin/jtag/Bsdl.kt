package jtag

/** Parsed subset of a BSDL file: instruction register, opcodes, boundary
 *  register cells and the physical pin map. Raw text is kept by the store;
 *  this is the derived, normalized view. */
data class BoundaryCell(
    val index: Int,             // basis: boundary-register bit number
    val cell: String,           // e.g. BC_1
    val port: String,           // "*" for control cells
    val function: String,       // input | output2 | output3 | control | internal
    val safe: String
)

data class Bsdl(
    val entity: String,
    val version: String,
    val irLength: Int,
    val opcodes: Map<String, String>,       // name -> bit pattern
    val boundary: List<BoundaryCell>,
    val pinMap: Map<String, String>         // port -> physical pin
) {
    fun cellPinEvidence(): List<Triple<Int, String, String>> =
        boundary.filter { it.port != "*" }.map { Triple(it.index, it.port, pinMap[it.port] ?: "?") }
}

object BsdlParser {
    private fun attr(text: String, name: String): String? {
        val m = Regex("attribute\\s+$name\\s+of\\s+(\\w+)\\s*:\\s*\\w+\\s+is\\s+\"([^\"]*)\"",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(text)
        return m?.groupValues?.get(2)
    }

    fun parse(text: String, version: String): Bsdl {
        val entity = Regex("entity\\s+(\\w+)\\s+is", RegexOption.IGNORE_CASE).find(text)
            ?.groupValues?.get(1) ?: "UNKNOWN"
        val irLen = attr(text, "INSTRUCTION_LENGTH")?.trim()?.toIntOrNull()
            ?: throw IllegalArgumentException("BSDL $entity: missing INSTRUCTION_LENGTH")
        val opcodes = linkedMapOf<String, String>()
        attr(text, "INSTRUCTION_OPCODE")?.let { body ->
            Regex("(\\w+)\\s*\\(\\s*([01]+)\\s*\\)").findAll(body).forEach {
                opcodes[it.groupValues[1].uppercase()] = it.groupValues[2]
            }
        }
        val cells = mutableListOf<BoundaryCell>()
        attr(text, "BOUNDARY_REGISTER")?.let { body ->
            Regex("(\\d+)\\s*\\(\\s*(\\w+)\\s*,\\s*(\\*|[\\w\\[\\]]+)\\s*,\\s*(\\w+)\\s*,\\s*(\\w)").findAll(body).forEach {
                cells += BoundaryCell(it.groupValues[1].toInt(), it.groupValues[2],
                    it.groupValues[3], it.groupValues[4], it.groupValues[5])
            }
        }
        val pins = linkedMapOf<String, String>()
        attr(text, "PIN_MAP")?.let { body ->
            Regex("([\\w\\[\\]]+)\\s*:\\s*([\\w-]+)").findAll(body).forEach {
                pins[it.groupValues[1]] = it.groupValues[2]
            }
        }
        return Bsdl(entity, version, irLen, opcodes, cells.sortedBy { it.index }, pins)
    }
}
