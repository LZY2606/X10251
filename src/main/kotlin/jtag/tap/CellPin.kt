package jtag.tap

import jtag.model.*
import kotlinx.serialization.json.*

data class BsdlCell(val cell: Int, val port: String, val pinRaw: String, val pinUnit: String?, val function: String)
data class BsdlMapping(val device: String, val devicePosition: Int, val version: String, val cells: List<BsdlCell>)

data class CellEvidence(
    val device: String, val cell: Int, val port: String, val pinRaw: String, val pinUnit: String?,
    val function: String, val bitOffsetCandidates: List<Int>, val bsdlVersion: String
)

/** 边界扫描单元 -> 引脚证据；引脚坐标保留原始表示，无法换算时附诊断。 */
class CellPinMapper {
    fun map(mappings: List<BsdlMapping>, chain: List<ChainDevice>, baseMappings: List<BsdlMapping>): Pair<List<CellEvidence>, List<Evt>> {
        val events = mutableListOf<Evt>()
        val out = mutableListOf<CellEvidence>()
        for (m in mappings) {
            val dev = chain.firstOrNull { it.position == m.devicePosition }
            val base = baseMappings.firstOrNull { it.device == m.device }
            if (base != null && base.version != m.version) {
                val changed = m.cells.filter { c ->
                    base.cells.firstOrNull { it.cell == c.cell }?.let { it.pinRaw != c.pinRaw || it.function != c.function } ?: true
                }.map { it.cell }
                events += Evt("MappingVersionChanged", jsonOf(
                    "device" to m.device, "from" to base.version, "to" to m.version,
                    "affectedCells" to changed
                ))
            }
            for (c in m.cells) {
                val offsets = dev?.drOffsetCandidates?.map { it + c.cell } ?: listOf(c.cell)
                if (normalizeTimeToPs(Quantity(c.pinRaw, c.pinUnit)).diagnostic != null) {
                    events += Evt("UnitDiagnostic", jsonOf(
                        "item" to "cell:${m.device}:${c.cell}", "raw" to c.pinRaw,
                        "unit" to c.pinUnit, "diagnostic" to "coordinate-kept-raw"
                    ))
                }
                out += CellEvidence(m.device, c.cell, c.port, c.pinRaw, c.pinUnit, c.function, offsets, m.version)
            }
        }
        return out to events
    }
}
