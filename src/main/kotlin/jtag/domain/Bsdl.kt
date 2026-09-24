package jtag.domain

/** Parsed subset of a BSDL file. The raw text is always retained alongside. */
data class BsdlPackage(
    val entity: String,
    val version: String,
    val instructionLength: Int?,
    val opcodes: Map<String, String>,      // mnemonic -> bit pattern
    val idcodes: List<String>,             // hex patterns
    val boundaryLength: Int?,
    val cells: List<BsdlCell>,
    val pinMap: Map<String, String>,       // logical port -> package pin (coordinate basis: package)
    val rawText: String
)

data class BsdlCell(
    val index: Int,
    val cellType: String,  // e.g. BC_1, BC_2
    val port: String,      // "*" for control cells
    val function: String,  // input, output2, output3, control, observe_only
    val safe: String
)

object BsdlParser {
    private val attrRegex = Regex("""attribute\s+(\w+)\s+of\s+(\w+)\s*:\s*entity\s+is\s*"(.*?)"\s*;""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val entityRegex = Regex("""entity\s+(\w+)\s+is""", RegexOption.IGNORE_CASE)
    private val cellRegex = Regex("""\(\s*(\w+)\s*,\s*([\w*]+)\s*,\s*(\w+)\s*,\s*(\w+)\s*\)""")
    private val pinPairRegex = Regex("""([\w.]+)\s*:\s*([A-Za-z]?[\w]+)""")
    private val opcodeRegex = Regex("""(\w+)\s*\(\s*([01]+)\s*\)""")

    fun parse(text: String, version: String): BsdlPackage {
        val entity = entityRegex.find(text)?.groupValues?.get(1) ?: "UNKNOWN"
        val attrs = attrRegex.findAll(text).associate { it.groupValues[1].uppercase() to it.groupValues[3] }
        val instrLen = attrs["INSTRUCTION_LENGTH"]?.trim()?.toIntOrNull()
        val opcodes = attrs["INSTRUCTION_OPCODE"]?.let { body ->
            opcodeRegex.findAll(body).associate { it.groupValues[1].uppercase() to it.groupValues[2] }
        } ?: emptyMap()
        val idcodes = attrs["IDCODE_REGISTER"]?.let { body ->
            Regex("""[0-9A-Fa-f]{8}""").findAll(body).map { "0x" + it.value.lowercase() }.toList()
        } ?: emptyList()
        val boundaryLen = attrs["BOUNDARY_LENGTH"]?.trim()?.toIntOrNull()
        val cells = attrs["BOUNDARY_REGISTER"]?.let { body ->
            cellRegex.findAll(body).mapIndexed { i, m ->
                BsdlCell(i, m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
            }.toList()
        } ?: emptyList()
        val pinMap = attrs["PIN_MAP_STRING"]?.let { body ->
            pinPairRegex.findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
        } ?: emptyMap()
        return BsdlPackage(entity, version, instrLen, opcodes, idcodes, boundaryLen, cells, pinMap, text)
    }
}

/** Difference between two BSDL versions, limited to what actually changed. */
data class BsdlDiff(
    val fromVersion: String,
    val toVersion: String,
    val changedPins: Map<String, Pair<String?, String?>>, // port -> (old pin, new pin)
    val changedCells: List<Int>,                          // cell indices differing
    val instructionLengthChanged: Boolean
)

object BsdlCompare {
    fun diff(a: BsdlPackage, b: BsdlPackage): BsdlDiff {
        val ports = a.pinMap.keys + b.pinMap.keys
        val pins = ports.mapNotNull { p ->
            val old = a.pinMap[p]; val new = b.pinMap[p]
            if (old != new) p to (old to new) else null
        }.toMap()
        val cellIdx = (0 until maxOf(a.cells.size, b.cells.size)).filter { i ->
            a.cells.getOrNull(i) != b.cells.getOrNull(i)
        }
        return BsdlDiff(a.version, b.version, pins, cellIdx,
            a.instructionLength != b.instructionLength)
    }
}
