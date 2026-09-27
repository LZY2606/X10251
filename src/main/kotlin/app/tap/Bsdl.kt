package app.tap

import app.model.BoundaryCell
import app.model.BsdlPinMap

data class ParsedBsdl(
    val entity: String,
    val idcode: String,
    val irLength: Int?,
    val idcodeOpcode: String?,
    val boundaryLength: Int,
    val cells: List<BoundaryCell>,
    val diagnostics: List<String>,
)

/** Minimal BSDL subset parser: only the fields the scan-chain evidence needs. */
object BsdlParser {

    fun parse(raw: String, id: String, version: String, createdAt: Long, rawRef: String): ParsedBsdl {
        val diag = mutableListOf<String>()
        fun attr(name: String): String? {
            val regex = Regex("(?is)attribute\\s+$name\\s+(?:of\\s+\\S+\\s*:\\s*entity\\s+is\\s+)?([^;]+);")
            val m = regex.find(raw) ?: return null
            return m.groupValues[1].trim()
        }
        fun lit(v: String?): String? = v?.replace(Regex("[\"\\s]"), "")

        val entity = Regex("(?is)entity\\s+(\\w+)\\s+is").find(raw)?.groupValues?.get(1) ?: "unknown"
        val idcode = lit(attr("IDCODE_REGISTER"))?.lowercase()
            ?: run { diag += "IDCODE_REGISTER absent"; "" }
        val irLen = attr("INSTRUCTION_LENGTH")?.filter { it.isDigit() }?.toIntOrNull()
        if (irLen == null) diag += "INSTRUCTION_LENGTH absent or unconvertible"

        val opcodesRaw = attr("INSTRUCTION_OPCODE")
        val idcodeOpcode = opcodesRaw?.let {
            Regex("(?is)IDCODE\\s*\\(\\s*\"([01xX]+)\"").find(it)?.groupValues?.get(1)
        }

        val boundaryLength = attr("BOUNDARY_LENGTH")?.filter { it.isDigit() }?.toIntOrNull() ?: 0
        val reg = attr("BOUNDARY_REGISTER") ?: ""
        val cells = mutableListOf<BoundaryCell>()
        //  num ( port, cell_type, control, safe [, ccell, disable] )
        val rowRegex = Regex("""(?m)^\s*(\d+)\s*\(\s*([^,()]+?)\s*,\s*([^,()]+?)\s*,([^,()]*,[^()]*)?\)\s*,?""")
        for (m in rowRegex.findAll(reg)) {
            val num = m.groupValues[1].toInt()
            val port = m.groupValues[2].trim().trim('"')
            val cellType = m.groupValues[3].trim().trim('"')
            val rest = (m.groupValues[4] ?: "").split(",").map { it.trim().trim('"') }.filter { it.isNotEmpty() }
            val safe = rest.getOrNull(1) ?: "?"
            val function = when {
                cellType.contains("BC_1", true) && port != "*" -> "io"
                port == "*" -> "control"
                else -> cellType.lowercase()
            }
            val pin = if (port.startsWith("IO", true) || port.startsWith("PIN", true)) port else port
            cells += BoundaryCell(num, port, pin, function, safe)
        }
        if (cells.size != boundaryLength && boundaryLength > 0)
            diag += "boundary cells parsed (${cells.size}) != BOUNDARY_LENGTH ($boundaryLength)"
        return ParsedBsdl(entity, idcode, irLen, idcodeOpcode, boundaryLength,
            cells.sortedBy { it.cellNumber }, diag)
    }

    fun toPinMap(p: ParsedBsdl, id: String, rev: Int, version: String, createdAt: Long, rawRef: String) =
        BsdlPinMap(id, rev, version, p.idcode, p.irLength ?: -1,
            p.idcodeOpcode ?: "", p.cells, rawRef, createdAt)
}
