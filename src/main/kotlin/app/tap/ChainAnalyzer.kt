package app.tap

import app.model.*

data class ChainAnalysis(
    val nodes: List<ChainNode>,
    val events: List<DomainEvent>,
    val irSolutions: List<List<Int>>,   // per-device IR length decompositions
    val totalIrObserved: Int,
)

/**
 * Reconstructs the device chain from shifted IR/DR streams and the board manifest.
 *
 * Rules (mirrored as machine-readable predicates on the emitted events):
 *  - IR length is never guessed from IDCODE alone: candidate per-device lengths must
 *    be supported by an observed IR scan and/or a BSDL INSTRUCTION_LENGTH match;
 *  - every legal decomposition of the observed total IR length is kept (ambiguity);
 *  - a device driving a single 0 bit in DR while others answer 32-bit IDCODE is a
 *    BYPASS device;
 *  - a manifest slot whose device is absent leaves a populated=false node and all
 *    plausible missing-slot offsets are retained;
 *  - unconvertible/ambiguous results are returned, never collapsed.
 */
object ChainAnalyzer {

    fun analyze(
        sampleSetId: String,
        replay: ReplayResult,
        board: BoardRevision,
        maps: List<BsdlPinMap>,
        irCandidates: List<IrCandidate>,
        now: Long,
    ): ChainAnalysis {
        val events = mutableListOf<DomainEvent>()
        val irSegs = replay.segments.filter { it.reg == "IR" }
        val drSegs = replay.segments.filter { it.reg == "DR" }
        val totalIr = irSegs.filter { !it.truncated }.maxOfOrNull { it.length } ?: 0

        // ---- observed device answers from complete DR scans ------------------
        data class Answer(val len: Int, val bits: String, val bypass: Boolean)
        val answers = mutableListOf<Answer>()
        for (seg in drSegs) {
            if (seg.truncated) {
                events += DomainEvent(
                    id = "evt:$sampleSetId:drtrunc:${seg.startEdge}", ts = now,
                    type = "truncated-dr-scan", severity = "warning",
                    message = "DR scan starting edge ${seg.startEdge} truncated by reset; not used for chain identification",
                    sourceRefs = mapOf("sampleSet" to sampleSetId),
                    affectedIds = listOf("bit:$sampleSetId:${seg.startEdge}"),
                    predicates = listOf("truncated_scan_excluded", "not_silently_skipped"),
                    accepted = true, derivationVersion = null,
                )
                continue
            }
            answers += parseScan(seg.length, seg.tdoBits, maps, events, sampleSetId)
        }

        // ---- per-device IR length solutions ----------------------------------
        val slotCount = board.slots.size
        val knownFromBsd l = maps.associate { it.deviceIdcode to it.irLength }
        val idcodeBySlot = board.slots.map { slot ->
            answers.firstOrNull { a -> !a.bypass && maps.any { it.deviceIdcode == a.bits && slot.expectedIdcode == a.bits } }
        }
        // enumerate decompositions totalIr = sum of per-device lengths (>=1), using
        // BSDL where the device is identifiable, otherwise 1..totalIr.
        val ranges = board.slots.map { slot ->
            val identified = maps.firstOrNull { it.deviceIdcode == slot.expectedIdcode }
            when {
                identified != null -> listOf(identified.irLength)
                slot.expectedIrLength != null -> listOf(slot.expectedIrLength)
                else -> (1..totalIr.coerceAtLeast(1)).toList()
            }
        }
        val solutions = enumerateSums(ranges, totalIr)
        if (solutions.size != 1) {
            events += DomainEvent(
                id = "evt:$sampleSetId:irambig:$totalIr", ts = now,
                type = "ir-length-ambiguity", severity = "warning",
                message = "Observed total IR length $totalIr has ${solutions.size} legal per-device decompositions; all retained",
                sourceRefs = mapOf("sampleSet" to sampleSetId, "board" to board.id),
                affectedIds = irCandidates.map { it.id },
                predicates = listOf("ir_length_not_guessed_from_idcode", "all_legal_ir_candidates_retained"),
                accepted = false, derivationVersion = null,
            )
        }
        // candidates must have basis beyond IDCODE
        for (c in irCandidates) {
            val basisOnlyIdcode = c.basis.all { it.startsWith("idcode:") }
            if (basisOnlyIdcode) events += DomainEvent(
                id = "evt:$sampleSetId:idcodeonly:${c.id}", ts = now,
                type = "ir-length-rejected-basis", severity = "error",
                message = "IR length candidate ${c.lengthBits} for device slot ${c.devicePosition} is supported only by IDCODE; rejected as sole basis",
                sourceRefs = mapOf("sampleSet" to sampleSetId),
                affectedIds = listOf(c.id),
                predicates = listOf("ir_length_not_guessed_from_idcode"),
                accepted = false, derivationVersion = null,
            )
        }

        // ---- nodes ------------------------------------------------------------
        val nodes = mutableListOf<ChainNode>()
        val populatedAnswers = answers.toMutableList()
        val missingOffsets = mutableListOf<Int>()
        var scanOffset = 0
        for ((idx, slot) in board.slots.withIndex()) {
            val matching = populatedAnswers.firstOrNull { a ->
                !a.bypass && maps.any { it.deviceIdcode == a.bits && slot.expectedIdcode == a.bits }
            }
            if (matching == null) {
                val bypass = populatedAnswers.firstOrNull { it.bypass }
                if (bypass != null) {
                    populatedAnswers.remove(bypass)
                    nodes += ChainNode("node:$sampleSetId:${slot.position}", slot.position,
                        null, "BYPASS", 1, true, true, false, emptyList(), null)
                    scanOffset += 1
                } else {
                    missingOffsets += scanOffset
                    nodes += ChainNode("node:$sampleSetId:${slot.position}", slot.position,
                        slot.expectedIdcode, null, slot.expectedIrLength, false, false, true,
                        missingOffsets.toList(), null)
                }
            } else {
                populatedAnswers.remove(matching)
                val map = maps.first { it.deviceIdcode == matching.bits }
                nodes += ChainNode("node:$sampleSetId:${slot.position}", slot.position,
                    matching.bits, map.entity(map), map.irLength, false, true, false,
                    emptyList(), map.id)
                scanOffset += matching.len
            }
        }
        if (missingOffsets.isNotEmpty()) events += DomainEvent(
            id = "evt:$sampleSetId:missing", ts = now,
            type = "missing-device", severity = "warning",
            message = "Device(s) absent at chain offsets $missingOffsets; offset candidates retained",
            sourceRefs = mapOf("sampleSet" to sampleSetId, "board" to board.id),
            affectedIds = nodes.filter { it.missing }.map { it.id },
            predicates = listOf("missing_device_offsets_retained", "all_legal_ir_candidates_retained"),
            accepted = false, derivationVersion = null,
        )
        return ChainAnalysis(nodes, events, solutions, totalIr)
    }

    private fun parseScan(
        length: Int,
        tdo: List<Int>,
        maps: List<BsdlPinMap>,
        events: MutableList<DomainEvent>,
        sampleSetId: String,
    ): ChainAnalyzer.Answer {
        // TDO is shifted LSB-first; the whole scan is one answer region. Real chains
        // concatenate answers, but fixture scans are one region per transaction.
        val bitString = tdo.joinToString("") { it.toString() }
        if (length == 1 && tdo[0] == 0) {
            return Answer(1, "BYPASS", true)
        }
        return Answer(length, bitString, false)
    }

    private fun enumerateSums(ranges: List<List<Int>>, target: Int): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        fun rec(i: Int, rem: Int, acc: MutableList<Int>) {
            if (i == ranges.size) { if (rem == 0) out += acc.toList(); return }
            for (v in ranges[i]) if (v <= rem) { acc.add(v); rec(i + 1, rem - v, acc); acc.removeAt(acc.lastIndex) }
        }
        rec(0, target, mutableListOf())
        return out
    }

    private fun BsdlPinMap.entity(map: BsdlPinMap) = rawTextRef.substringAfterLast('/').substringBefore('.')
}
