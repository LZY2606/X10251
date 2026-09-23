package jtag.bsdl

import jtag.model.*
import kotlinx.serialization.Serializable

/** Tolerant parser for the BSDL subset used by this tool. Raw text is always preserved. */
object BsdlParser {
    fun parse(text: String, deviceRef: String, bsdlVersion: String): BsdlMapping {
        val entity = Regex("""entity\s+(\w+)\s+is""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1) ?: "UNKNOWN"
        val pkg = Regex("""PHYSICAL_PIN_MAP\s*:\s*string\s*:=\s*"(\w+)"""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1) ?: "UNKNOWN_PACKAGE"
        val idcode = Regex("""IDCODE_REGISTER\s+[^"]*"([01]{32})"""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1) ?: "0".repeat(32)
        val irLen = Regex("""INSTRUCTION_LENGTH\s*:\s*integer\s*:=\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toInt() ?: 0
        val pinMap = mutableMapOf<String, String>()
        val pinBlock = Regex("""PIN_MAP_STRING\s*:\s*string\s*:=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)
        pinBlock?.replace("\n", " ")?.split(",")?.forEach { entry ->
            val m = Regex("""(\w+)\s*:\s*(\w+)""").find(entry.trim())
            if (m != null) pinMap[m.groupValues[1]] = m.groupValues[2]
        }
        val cells = mutableListOf<BoundaryCell>()
        val cellRe = Regex("""(\d+)\s*\(\s*(\w+)\s*,\s*([*\w]+)\s*,\s*(\w+)\s*,\s*(\w+)\s*\)""")
        val regBlock = Regex("""BOUNDARY_REGISTER\s*:\s*string\s*:=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1) ?: ""
        for (m in cellRe.findAll(regBlock)) {
            val port = m.groupValues[3]
            cells += BoundaryCell(
                index = m.groupValues[1].toInt(),
                cellType = m.groupValues[2],
                port = port,
                function = m.groupValues[4],
                safe = m.groupValues[5],
                pin = pinMap[port]
            )
        }
        return BsdlMapping(
            deviceRef = deviceRef, bsdlVersion = bsdlVersion, entity = entity,
            packageBasis = pkg, idcode = idcode, irLength = irLen,
            cells = cells.sortedBy { it.index }, rawText = text
        )
    }
}

@Serializable
data class CellPinDiff(
    val cellIndex: Int,
    val port: String,
    val pinA: String?,
    val pinB: String?,
    val kind: String   // PIN_CHANGED | CELL_ADDED | CELL_REMOVED | UNCHANGED
)

@Serializable
data class BsdlCompare(
    val deviceRef: String,
    val versionA: String,
    val versionB: String,
    val diffs: List<CellPinDiff>
) {
    val changed: List<CellPinDiff> get() = diffs.filter { it.kind != "UNCHANGED" }
}

object BsdlCompareEngine {
    fun compare(a: BsdlMapping, b: BsdlMapping): BsdlCompare {
        val byIndexA = a.cells.associateBy { it.index }
        val byIndexB = b.cells.associateBy { it.index }
        val all = (byIndexA.keys + byIndexB.keys).sorted()
        val diffs = all.map { idx ->
            val ca = byIndexA[idx]; val cb = byIndexB[idx]
            when {
                ca == null -> CellPinDiff(idx, cb!!.port, null, cb.pin, "CELL_ADDED")
                cb == null -> CellPinDiff(idx, ca.port, ca.pin, null, "CELL_REMOVED")
                ca.pin != cb.pin || ca.port != cb.port -> CellPinDiff(idx, ca.port, ca.pin, cb.pin, "PIN_CHANGED")
                else -> CellPinDiff(idx, ca.port, ca.pin, cb.pin, "UNCHANGED")
            }
        }
        return BsdlCompare(a.deviceRef, a.bsdlVersion, b.bsdlVersion, diffs)
    }
}

@Serializable
data class CellPinEvidence(
    val device: String,
    val bsdlVersion: String,
    val packageBasis: String,
    val cellIndex: Int,
    val port: String,
    val pin: String?,
    val observedBit: Boolean?,
    val note: String? = null
)

object EvidenceBuilder {
    /** Join observed DR bits onto boundary cells of each mapped device. */
    fun build(chain: ChainAnalysis, shifts: List<ShiftBitstream>, mappings: Map<String, BsdlMapping>): List<CellPinEvidence> {
        val dr = shifts.filter { it.kind == ShiftKind.DR && !it.abortedByReset }.maxByOrNull { it.clockCount }
        val out = mutableListOf<CellPinEvidence>()
        var bitOffset = 0
        for (dev in chain.devices) {
            val mapping = dev.bsdlRef?.let { mappings[it] }
            if (dev.missing) {
                out += CellPinEvidence(dev.name, mapping?.bsdlVersion ?: "?", mapping?.packageBasis ?: "?",
                    -1, "-", null, null, "device missing on chain; cell evidence withheld, offset candidates retained")
                continue
            }
            if (dev.bypass || mapping == null) { bitOffset += 1; continue }
            for (cell in mapping.cells) {
                val bit = dr?.tdoBits?.getOrNull(bitOffset + cell.index)
                out += CellPinEvidence(
                    dev.name, mapping.bsdlVersion, mapping.packageBasis,
                    cell.index, cell.port, cell.pin, bit,
                    if (cell.pin == null) "no pin for port '${cell.port}' in ${mapping.packageBasis} map" else null
                )
            }
            bitOffset += mapping.cells.size
        }
        return out
    }
}
