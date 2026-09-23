package gsb.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class BsdlCell(
    val index: Int,
    val port: String,
    val pin: String,
    val function: String,
    val safe: String,
    val coord: Quantity,
)

data class BsdlMapping(
    val id: String,
    val revision: Int,
    val version: String,
    val entity: String,
    val pkg: String,
    val irLength: Int?,
    val instructions: Map<String, String>,
    val idcode: String?,
    val cells: List<BsdlCell>,
)

object BsdlParser {
    fun parse(src: Source): BsdlMapping {
        val c = src.content
        val coords = c.obj("pinCoords")
        val cells = (c.arr("cells") ?: JsonArray(emptyList())).map { el ->
            val o = el as JsonObject
            val pin = o.str("pin") ?: "?"
            val coordRaw = (coords?.get(pin) as? JsonPrimitive)?.contentOrNull
            val coordBasis = coords?.obj("basis")?.str(pin) ?: c.str("coordBasis")
            BsdlCell(
                index = o.int("index") ?: 0,
                port = o.str("port") ?: "?",
                pin = pin,
                function = o.str("function") ?: "?",
                safe = o.str("safe") ?: "X",
                coord = if (coordRaw != null) Units.coordinate(coordRaw, coordBasis)
                else Quantity("?", null, null, null, "missing-coordinate"),
            )
        }
        val instr = mutableMapOf<String, String>()
        c.obj("instructions")?.forEach { (k, v) ->
            (v as? JsonPrimitive)?.contentOrNull?.let { instr[k] = it }
        }
        return BsdlMapping(
            id = src.id,
            revision = src.revision,
            version = c.str("version") ?: "?",
            entity = c.str("entity") ?: "?",
            pkg = c.str("package") ?: "?",
            irLength = c.int("irLength"),
            instructions = instr,
            idcode = c.str("idcode"),
            cells = cells,
        )
    }
}

data class CellDiff(
    val cellIndex: Int,
    val port: String,
    val field: String,
    val a: String,
    val b: String,
)

object BsdlCompare {
    fun diff(a: BsdlMapping, b: BsdlMapping): List<CellDiff> {
        val out = mutableListOf<CellDiff>()
        val byIndexB = b.cells.associateBy { it.index }
        for (ca in a.cells) {
            val cb = byIndexB[ca.index] ?: continue
            fun chk(field: String, x: String, y: String) {
                if (x != y) out += CellDiff(ca.index, ca.port, field, x, y)
            }
            chk("pin", ca.pin, cb.pin)
            chk("function", ca.function, cb.function)
            chk("safe", ca.safe, cb.safe)
            chk("coord", ca.coord.raw, cb.coord.raw)
            chk("coordBasis", ca.coord.basis ?: "?", cb.coord.basis ?: "?")
        }
        return out
    }
}

data class CellPinEvidence(
    val deviceId: String,
    val bsdlRef: String,
    val cell: BsdlCell,
    val chainOffset: Int?,
    val offsetCandidates: List<Int>,
    val diagnostics: List<String>,
)

object CellPinProjection {
    /**
     * Join chain topology with BSDL mappings. Boundary cells get chain offsets
     * based on boundary-register lengths. When devices are missing/depopulated,
     * every legal offset candidate is retained instead of collapsing to one.
     */
    fun project(
        devices: List<DeviceEntry>,
        chain: ChainResult,
        mappings: Map<String, BsdlMapping>,
    ): List<CellPinEvidence> {
        val out = mutableListOf<CellPinEvidence>()
        val sorted = devices.sortedBy { it.position }
        val gaps = (chain.missingDevices).toSet()
        var base = 0
        var pendingGapLen = 0 // boundary length of missing devices seen so far
        for (d in sorted) {
            val map = d.bsdlRef?.let { mappings[it] }
            if (d.id in gaps) {
                // device absent on the physical chain: contributes 0 observed cells,
                // but its nominal length stays a candidate for downstream offsets
                pendingGapLen += map?.cells?.size ?: 0
                continue
            }
            if (map != null) {
                for (cell in map.cells) {
                    val offsets = if (pendingGapLen > 0)
                        listOf(base + cell.index, base + pendingGapLen + cell.index)
                    else listOf(base + cell.index)
                    out += CellPinEvidence(
                        deviceId = d.id,
                        bsdlRef = "${map.id}@${map.revision}",
                        cell = cell,
                        chainOffset = offsets.first(),
                        offsetCandidates = offsets,
                        diagnostics = listOfNotNull(cell.coord.diagnostic),
                    )
                }
                base += map.cells.size
            }
        }
        return out
    }
}
