package jtag

/** Minimal tolerant BSDL subset parser: instruction length/opcodes, pin map, boundary register. */
data class BsdlPackage(
    val entity: String,
    val version: String,
    val instructionLength: Int?,
    val opcodes: Map<String, String>, // name -> bit pattern
    val pinMap: Map<String, String>,  // port -> pin
    val boundary: List<BoundaryCell>
) {
    data class BoundaryCell(val cell: Int, val type: String, val port: String, val function: String, val safe: String)
}

object BsdlParser {
    fun parse(text: String, version: String): BsdlPackage {
        val entity = Regex("""entity\s+(\w+)\s+is""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1) ?: "UNKNOWN"
        val irLen = Regex("""INSTRUCTION_LENGTH\s+of\s+\w+\s+is\s+(\d+)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toInt()
        val opcodes = mutableMapOf<String, String>()
        Regex("""INSTRUCTION_OPCODE\s+of\s+\w+\s+is\s*"([^"]+)"""", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            Regex("""(\w+)\s*\(([^)]+)\)""").findAll(m.groupValues[1]).forEach { om ->
                opcodes[om.groupValues[1].uppercase()] = om.groupValues[2].replace(" ", "").split(",").first()
            }
        }
        val pinMap = mutableMapOf<String, String>()
        Regex("""PIN_MAP_STRING\s*:=\s*"([^"]+)"""", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            Regex("""(\w+)\s*:\s*\(([^)]+)\)""").findAll(m.groupValues[1]).forEach { pm ->
                val pkg = pm.groupValues[1]
                pm.groupValues[2].split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    .forEachIndexed { i, port -> pinMap[port] = "$pkg-${i + 1}" }
            }
        }
        val boundary = mutableListOf<BsdlPackage.BoundaryCell>()
        Regex("""BOUNDARY_REGISTER\s+of\s+\w+\s+is\s*"([^"]+)"""", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            Regex("""(\d+)\s*\(\s*(\w+)\s*,\s*(\w+)\s*,\s*(\w+)\s*,\s*(\w)\s*\)""").findAll(m.groupValues[1]).forEach { bm ->
                boundary += BsdlPackage.BoundaryCell(
                    bm.groupValues[1].toInt(), bm.groupValues[2],
                    bm.groupValues[3], bm.groupValues[4], bm.groupValues[5])
            }
        }
        return BsdlPackage(entity, version, irLen, opcodes, pinMap, boundary.sortedBy { it.cell })
    }
}
