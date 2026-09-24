package jtag.bsdl

import kotlinx.serialization.Serializable

@Serializable
data class BoundaryCell(
    val index: Int,
    val cellName: String,     // e.g. BC_1, BC_4
    val port: String,         // port name or "*"
    val function: String,     // input, output2, output3, control, observe_only
    val safe: String
)

@Serializable
data class BsdlPackage(
    val entity: String,
    val version: String,          // source version tag, e.g. "v1"
    val pinMap: Map<String, String>,   // logical port -> package pin
    val boundaryLength: Int,
    val cells: List<BoundaryCell>,
    val rawText: String           // original representation preserved
)

/** Tolerant parser for the BSDL subset used by this tool's fixtures. */
object BsdlParser {

    fun parse(text: String, version: String): BsdlPackage {
        val entity = Regex("""entity\s+(\w+)\s+is""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1) ?: "UNKNOWN"

        val pinMap = mutableMapOf<String, String>()
        val pinMapBody = Regex("""attribute\s+PIN_MAP\s+of\s+\w+\s*:\s*entity\s+is\s+PHYSICAL_PIN_MAP\s*;""", RegexOption.IGNORE_CASE)
        // parse "<port> : <pin>," pairs inside the PIN_MAP string
        val pinPairs = Regex("""(\w+)\s*:\s*([A-Za-z0-9_]+)\s*[,)]""")
        val pinSection = Regex("""PIN_MAP_STRING\s*is\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)
        if (pinSection != null) {
            for (m in pinPairs.findAll(pinSection)) {
                pinMap[m.groupValues[1]] = m.groupValues[2]
            }
        }

        val boundaryLength = Regex("""attribute\s+BOUNDARY_LENGTH\s+of\s+\w+\s*:\s*entity\s+is\s+(\d+)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toInt() ?: 0

        val cells = mutableListOf<BoundaryCell>()
        val cellRe = Regex("""(\d+)\s*\(\s*(\w+)\s*,\s*([\w*]+)\s*,\s*(\w+)\s*,\s*(\w+)\s*\)""")
        for (m in cellRe.findAll(text)) {
            cells += BoundaryCell(
                index = m.groupValues[1].toInt(),
                cellName = m.groupValues[2],
                port = m.groupValues[3],
                function = m.groupValues[4],
                safe = m.groupValues[5]
            )
        }
        return BsdlPackage(entity, version, pinMap, boundaryLength, cells.sortedBy { it.index }, text)
    }
}

/** Cell -> pin evidence row, joining BSDL cells with the package pin map. */
@Serializable
data class CellPinEvidence(
    val cellIndex: Int,
    val port: String,
    val function: String,
    val packagePin: String?,      // null when port has no pin mapping (kept, with diagnostic)
    val diagnostic: String? = null
)

object CellPinMapper {
    fun map(bsdl: BsdlPackage): List<CellPinEvidence> =
        bsdl.cells.map { cell ->
            if (cell.port == "*") {
                CellPinEvidence(cell.index, cell.port, cell.function, null, "control/observe cell without port")
            } else {
                val pin = bsdl.pinMap[cell.port]
                CellPinEvidence(
                    cell.index, cell.port, cell.function, pin,
                    if (pin == null) "no package pin mapping for port ${cell.port} in BSDL ${bsdl.version}" else null
                )
            }
        }
}
