package jtag

import kotlinx.serialization.Serializable

@Serializable
data class BoundaryCell(
    val index: Int,
    val cellType: String,     // BC_1, BC_2, ...
    val port: String,         // port name or "*"
    val function: String,     // input | output2 | output3 | control | observe_only
)

@Serializable
data class BsdlDevice(
    val id: String,
    val name: String,
    val bsdlVersion: String,
    val irLength: Int,
    val idcode: String?,                 // 32-bit binary string, null if none
    val opcodes: Map<String, String>,    // instruction -> opcode bits
    val boundaryCells: List<BoundaryCell>,
    val pinMap: Map<String, Int>,        // port -> package pin
    val rawText: String,
) {
    /** cell -> package pin evidence rows */
    fun cellPinEvidence(): List<Triple<Int, String, Int?>> =
        boundaryCells.map { Triple(it.index, it.port, pinMap[it.port]) }
}

/** Minimal BSDL subset parser, sufficient for the repo fixtures. */
object BsdlParser {
    fun parse(id: String, bsdlVersion: String, text: String): BsdlDevice {
        val entity = Regex("entity\\s+(\\w+)\\s+is", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1) ?: "UNKNOWN"
        fun attr(name: String): String? {
            val m = Regex(
                "attribute\\s+$name\\s+of\\s+\\w+\\s*:\\s*entity\\s+is\\s+",
                RegexOption.IGNORE_CASE
            ).find(text) ?: return null
            val rest = text.substring(m.range.last + 1)
            val end = rest.indexOf(';')
            return rest.substring(0, end).trim()
        }
        val irLength = attr("INSTRUCTION_LENGTH")?.trim()?.toIntOrNull() ?: 0
        val idcode = attr("IDCODE_REGISTER")?.let { body ->
            Regex("\"([01]{32})\"").find(body)?.groupValues?.get(1)
        }
        val opcodes = mutableMapOf<String, String>()
        attr("INSTRUCTION_OPCODE")?.let { body ->
            for (m in Regex("(\\w+)\\s*\\(\\s*([01]+)\\s*\\)").findAll(body))
                opcodes[m.groupValues[1].uppercase()] = m.groupValues[2]
        }
        val cells = mutableListOf<BoundaryCell>()
        attr("BOUNDARY_REGISTER")?.let { body ->
            for (m in Regex("(\\d+)\\s*\\(\\s*(\\w+)\\s*,\\s*(\\*|\\w+)\\s*,\\s*(\\w+)").findAll(body))
                cells += BoundaryCell(m.groupValues[1].toInt(), m.groupValues[2],
                    m.groupValues[3], m.groupValues[4])
        }
        val pins = mutableMapOf<String, Int>()
        attr("PIN_MAP")?.let { body ->
            for (m in Regex("(\\w+)\\s*:\\s*(\\d+)").findAll(body))
                pins[m.groupValues[1]] = m.groupValues[2].toInt()
        }
        return BsdlDevice(id, entity, bsdlVersion, irLength, idcode, opcodes,
            cells.sortedBy { it.index }, pins.toMap(), text)
    }
}

/** Structural diff between two BSDL mapping versions. */
data class BsdlDiff(
    val irLengthChanged: Pair<Int, Int>?,
    val addedCells: List<BoundaryCell>,
    val removedCells: List<BoundaryCell>,
    val pinChanges: List<Triple<String, Int?, Int?>>,
)

fun diffBsdl(a: BsdlDevice, b: BsdlDevice): BsdlDiff {
    val aCells = a.boundaryCells.associateBy { it.index }
    val bCells = b.boundaryCells.associateBy { it.index }
    val added = bCells.filter { it.key !in aCells }.values.toList()
    val removed = aCells.filter { it.key !in bCells }.values.toList()
    val ports = (a.pinMap.keys + b.pinMap.keys)
    val pinChanges = ports.mapNotNull { p ->
        val pa = a.pinMap[p]; val pb = b.pinMap[p]
        if (pa != pb) Triple(p, pa, pb) else null
    }
    return BsdlDiff(
        if (a.irLength != b.irLength) a.irLength to b.irLength else null,
        added, removed, pinChanges)
}
