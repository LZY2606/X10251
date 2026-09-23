package jtag.core

data class ChainAnalysis(
    val devices: List<ChainDevice>,
    val offsets: List<OffsetCandidate>,
    val cells: List<CellPin>,
    val events: List<JudgeEvent>
)

data class BsdlPart(val device: String, val version: String, val idcode: String?, val irLength: Int?,
                    val cells: List<BsdlCell>)

/**
 * Walks valid DR shift streams to rebuild the device chain, then maps boundary
 * scan cells to pins through the BSDL revision.
 * Ambiguity is preserved, never collapsed:
 *  - DR capture bit 0 = 1 -> 32-bit IDCODE; 0 -> BYPASS device (1 bit);
 *  - an expected board device absent from the scan stays MISSING and yields
 *    cell-offset candidates rather than a forced mapping;
 *  - IR length is taken only from BSDL or the measured chain remainder;
 *    IDCODE_GUESS candidates are never auto-accepted.
 */
class ChainAnalyzer(
    private val segments: List<ShiftSegment>,
    private val board: BoardRevision,
    private val bsdl: Map<String, BsdlPart>, // device -> part
    private val irCandidates: List<IrLengthCandidate>
) {
    private var eventSeq = 0
    private fun evt(kind: String, subject: String, accepted: Boolean?, why: String, payload: String) =
        JudgeEvent(newId("evt"), eventSeq++, kind, subject, accepted, why, payload)

    /** Scanned device in TDI-first order (index 0 closest to TDI). */
    private data class Scanned(val scanPos: Int, val idcode: String?, val bypass: Boolean)

    fun analyze(): ChainAnalysis {
        val events = mutableListOf<JudgeEvent>()
        val drSegments = segments.filter { it.register == "DR" }
        val idSegment = drSegments.firstOrNull { it.status == "VALID" }
        val boundarySegment = drSegments.filter { it.status == "VALID" }.getOrNull(1)

        // --- walk the IDCODE/bypass capture stream (TDO-first) ---
        val scannedTdoFirst = mutableListOf<Pair<String?, Boolean>>()
        if (idSegment != null) {
            val bits = idSegment.bitsTdo
            var pos = 0
            while (pos < bits.length) {
                if (bits[pos] == '1') {
                    if (pos + 32 > bits.length) {
                        events += evt("CHAIN_PARSE_ERROR", "segment@${idSegment.startClock}", null,
                            "Truncated 32-bit IDCODE stream at bit $pos; chain walk stopped early.", "{}")
                        break
                    }
                    val raw = bits.substring(pos, pos + 32)
                    scannedTdoFirst += (String.format("%08x", java.lang.Long.parseLong(raw, 2)) to false)
                    pos += 32
                } else {
                    scannedTdoFirst += (null to true)
                    pos += 1
                }
            }
        }
        val scanned = scannedTdoFirst.reversed()
            .mapIndexed { i, p -> Scanned(i, p.first, p.second) }

        // --- match scanned devices to expected board devices by identity ---
        val byIdcode = bsdl.values.associateBy { it.idcode }
        val expected = board.devices.sortedBy { it.position }
        val claimed = mutableSetOf<String>() // expected device names claimed by scanned entries
        val devices = mutableListOf<ChainDevice>()
        for (s in scanned) {
            if (s.bypass) {
                val cand = expected.firstOrNull { e ->
                    e.device !in claimed && (bsdl[e.device]?.idcode == null)
                }
                if (cand != null) claimed += cand.device
                devices += ChainDevice(newId("dev"), s.scanPos, cand?.device, null, true, null, "BYPASS_ONLY")
            } else {
                val part = byIdcode[s.idcode]
                if (part != null) {
                    claimed += part.device
                    devices += ChainDevice(newId("dev"), s.scanPos, part.device, s.idcode, false,
                        part.irLength, "IDENTIFIED")
                } else {
                    devices += ChainDevice(newId("dev"), s.scanPos, null, s.idcode, false, null, "AMBIGUOUS")
                    events += evt("CHAIN_DEVICE_MISMATCH", "scanpos:${s.scanPos}", null,
                        "Scanned IDCODE ${s.idcode} matches no BSDL part in the imported mapping set.", "{}")
                }
            }
        }
        val missing = expected.filter { it.device !in claimed }
        for (m in missing) {
            val part = bsdl[m.device]
            devices += ChainDevice(newId("dev"), m.position, m.device, part?.idcode,
                part?.idcode == null, part?.irLength, "MISSING")
            events += evt("CHAIN_DEVICE_MISSING", "pos:${m.position}", null,
                "Expected device ${m.device} at board position ${m.position} is absent from the scan; " +
                    "position and cell offsets kept as candidates instead of forcing a mapping.",
                "{\"device\":\"${m.device}\",\"position\":${m.position}}")
        }
        devices.sortBy { it.position }

        // --- IR length: measured total minus BSDL-known lengths ---
        val measuredIr = segments
            .filter { it.register == "IR" && it.status == "VALID" && it.bitsTdi.all { c -> c == '1' } }
            .mapNotNull { measureLength(it.bitsTdo) }.firstOrNull()
        val present = devices.filter { it.status != "MISSING" }
        val knownSum = present.mapNotNull { it.irLength }.sum()
        val unresolved = present.filter { it.irLength == null }
        if (measuredIr != null && unresolved.size == 1) {
            val remainder = measuredIr - knownSum
            val target = unresolved.first()
            events += evt("IR_LENGTH_REMAINDER", "scanpos:${target.position}", null,
                "Measured IR chain length $measuredIr bits minus BSDL-known $knownSum leaves " +
                    "$remainder bits for ${target.name ?: "scanpos ${target.position}"}; recorded as a " +
                    "candidate, not a forced value.",
                "{\"measured\":$measuredIr,\"remainder\":$remainder}")
            irCandidates.filter { it.devicePos == target.position }.forEach { c ->
                when {
                    c.basis == "IDCODE_GUESS" -> events += evt("IR_CANDIDATE_NOT_ADOPTED",
                        "candidate:${c.id}", false,
                        "IR length guess ${c.lengthBits} based on IDCODE alone is not adopted: IR length " +
                            "cannot be inferred from IDCODE; candidate retained as rejected.",
                        "{\"length\":${c.lengthBits},\"basis\":\"${c.basis}\"}")
                    c.lengthBits == remainder -> events += evt("IR_CANDIDATE_CONSISTENT",
                        "candidate:${c.id}", null,
                        "Candidate ${c.lengthBits} bits is consistent with measured remainder $remainder.",
                        "{\"length\":${c.lengthBits},\"basis\":\"${c.basis}\"}")
                    else -> events += evt("IR_CANDIDATE_REJECTED", "candidate:${c.id}", false,
                        "Candidate ${c.lengthBits} bits conflicts with measured remainder $remainder; " +
                            "rejected but retained for audit.",
                        "{\"length\":${c.lengthBits},\"measuredRemainder\":$remainder}")
                }
            }
        } else if (unresolved.size > 1) {
            events += evt("IR_LENGTH_AMBIGUOUS", "ir", null,
                "${unresolved.size} devices lack BSDL IR lengths; all legal compositions consistent " +
                    "with the measured total are retained, not collapsed to one answer.", "{}")
        }

        // --- cell-offset candidates for missing devices ---
        val offsets = mutableListOf<OffsetCandidate>()
        for (m in missing) {
            val missingCells = bsdl[m.device]?.cells?.size ?: 0
            offsets += OffsetCandidate(newId("off"), m.position, 0, "BOARD_ABSENT_${m.device}")
            if (missingCells > 0) {
                val baseOffset = present.filter { it.position < m.position }
                    .sumOf { d -> bsdl[d.name]?.cells?.size ?: if (d.bypass) 1 else 0 }
                offsets += OffsetCandidate(newId("off"), m.position, baseOffset + missingCells,
                    "IF_${m.device}_WERE_PRESENT")
            }
        }

        // --- boundary cell -> pin evidence (TDO-first over present devices) ---
        val cells = mutableListOf<CellPin>()
        if (boundarySegment != null) {
            val bits = boundarySegment.bitsTdo
            var bit = 0
            for (d in present.sortedByDescending { it.position }) {
                val part = bsdl[d.name]
                val width = when {
                    d.bypass -> 1
                    part != null -> part.cells.size
                    else -> 0
                }
                repeat(width) { cellIdx ->
                    val observed = bits.getOrNull(bit)?.digitToIntOrNull()
                    when {
                        d.bypass -> cells += CellPin(newId("cell"), d.name ?: "bypass@${d.position}",
                            -1, "BYPASS", "BYPASS", "", observed)
                        part != null && cellIdx < part.cells.size -> {
                            val c = part.cells[cellIdx]
                            cells += CellPin(newId("cell"), d.name!!, c.cell, c.pin, c.function,
                                part.version, observed)
                        }
                        else -> events += evt("CELL_MAP_GAP", "scanpos:${d.position}", null,
                            "No BSDL cells available for ${d.name}; observed bits retained at the raw " +
                                "offset and not discarded to make the picture draw cleanly.", "{}")
                    }
                    bit++
                }
            }
        } else {
            events += evt("BOUNDARY_SCAN_UNAVAILABLE", "dr", null,
                "No valid boundary scan segment; cell-to-pin observations left unset rather than invented.", "{}")
        }
        return ChainAnalysis(devices, offsets, cells, events)
    }

    /** IR length measurement: first k where bits[k..k+3] are all 1 (capture
     *  pattern flushed, TDI ones returning). */
    private fun measureLength(bits: String): Int? {
        for (k in 0..bits.length - 4) {
            if ((k..k + 3).all { bits[it] == '1' }) return k
        }
        return null
    }
}
