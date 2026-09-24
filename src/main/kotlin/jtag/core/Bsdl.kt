package jtag.core

import kotlinx.serialization.Serializable

@Serializable
data class BoundaryCell(
    val index: Int,
    val function: String,   // input | output2 | output3 | control | bidir | observe_only
    val port: String?,      // port/pin name, null for control-only cells
    val safe: String? = null,
)

@Serializable
data class BsdlPackage(
    val name: String,
    val version: String,
    val instructionLength: Int?,
    val idcode: String? = null,          // 32-bit binary string if given
    val opcodes: Map<String, String> = emptyMap(),  // name -> bit pattern
    val cells: List<BoundaryCell> = emptyList(),
)

/**
 * Minimal BSDL subset parser. Understands the attributes this tool needs:
 *   attribute INSTRUCTION_LENGTH of <pkg> : entity is <n>;
 *   attribute INSTRUCTION_OPCODE of <pkg> : entity is "BYPASS 1111, IDCODE 0010";
 *   attribute IDCODE_REGISTER of <pkg> : entity is "0001...";
 *   attribute BOUNDARY_REGISTER of <pkg> : entity is "0 (BC_1, PIN_A, input, X), ...";
 * Anything unrecognized is ignored (raw text stays in the source record).
 */
fun parseBsdl(text: String, name: String, version: String): BsdlPackage {
    fun attr(name: String): String? {
        val m = Regex("attribute\\s+$name\\s+of\\s+\\w+\\s*:\\s*entity\\s+is\\s+", RegexOption.IGNORE_CASE)
            .find(text) ?: return null
        val rest = text.substring(m.range.last + 1)
        return rest.substringBefore(';').trim()
    }
    val instrLen = attr("INSTRUCTION_LENGTH")?.trim()?.toIntOrNull()
    val idcode = attr("IDCODE_REGISTER")?.let { raw ->
        Regex("\"([01]+)\"").find(raw)?.groupValues?.get(1)
    }
    val opcodes = mutableMapOf<String, String>()
    attr("INSTRUCTION_OPCODE")?.let { raw ->
        for (m in Regex("(\\w+)\\s*\\(?\\s*([01]+)\\s*\\)?").findAll(raw))
            opcodes[m.groupValues[1].uppercase()] = m.groupValues[2]
    }
    val cells = mutableListOf<BoundaryCell>()
    attr("BOUNDARY_REGISTER")?.let { raw ->
        for (m in Regex("(\\d+)\\s*\\(\\s*(\\w+)\\s*,\\s*([\\w*]+)\\s*,\\s*(\\w+)\\s*,\\s*(\\w+)").findAll(raw)) {
            cells.add(BoundaryCell(m.groupValues[1].toInt(), m.groupValues[4].lowercase(),
                m.groupValues[3].takeIf { it != "*" }, m.groupValues[5]))
        }
    }
    return BsdlPackage(name, version, instrLen, idcode, opcodes, cells.sortedBy { it.index })
}

@Serializable
data class CellDiff(val cellIndex: Int, val field: String, val before: String?, val after: String?)

@Serializable
data class BsdlDiff(
    val instructionLengthChanged: Pair<Int?, Int?>,
    val cellDiffs: List<CellDiff>,
    val opcodeDiffs: Map<String, Pair<String?, String?>>,
)

fun diffBsdl(a: BsdlPackage, b: BsdlPackage): BsdlDiff {
    val cellDiffs = mutableListOf<CellDiff>()
    val byIndexA = a.cells.associateBy { it.index }
    val byIndexB = b.cells.associateBy { it.index }
    for (idx in (byIndexA.keys + byIndexB.keys).sorted()) {
        val ca = byIndexA[idx]; val cb = byIndexB[idx]
        if (ca?.function != cb?.function) cellDiffs.add(CellDiff(idx, "function", ca?.function, cb?.function))
        if (ca?.port != cb?.port) cellDiffs.add(CellDiff(idx, "port", ca?.port, cb?.port))
    }
    val opDiffs = mutableMapOf<String, Pair<String?, String?>>()
    for (k in a.opcodes.keys + b.opcodes.keys) {
        val va = a.opcodes[k]; val vb = b.opcodes[k]
        if (va != vb) opDiffs[k] = va to vb
    }
    return BsdlDiff(a.instructionLength to b.instructionLength, cellDiffs, opDiffs)
}
