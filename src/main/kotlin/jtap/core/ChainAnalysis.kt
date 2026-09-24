package jtap.core

import jtap.model.BoardRevisionSource
import jtap.model.BsdlSource
import jtap.model.EventSeverity
import jtap.model.IrLengthCandidate
import jtap.model.JudgementEvent
import jtap.model.RuleCode
import jtap.model.StableIds

data class DeviceSlot(
    val position: Int,
    val expectedName: String,
    val bsdlVersion: String?,
    val idcodeObserved: String?,
    val present: Boolean,
    val bypass: Boolean,
    val irLength: Int?,
    val drOffset: Int,
    val drLength: Int,
    val offsetCandidates: List<Int>,
)

data class ChainModel(
    val deviceCount: Int,
    val slots: List<DeviceSlot>,
    val irCandidates: List<IrLengthCandidate>,
    val events: List<JudgementEvent>,
)

/**
 * Chain analysis never collapses ambiguity into a guess:
 *  - IR length candidates come from BSDL-declared lengths + observed IR scans;
 *    IDCODE alone can never select one.
 *  - a slot whose IDCODE does not match keeps every legal dr-offset candidate.
 *  - BYPASS devices contribute exactly one DR bit.
 */
class ChainAnalyzer(
    private val caseId: String,
    private val board: BoardRevisionSource,
    private val bsdlByDevice: Map<String, BsdlSource>,
) {
    fun analyze(
        idcodeLsbFirst: String?,
        observedIrLengths: List<Int>,
        bypassPositions: Set<Int>,
    ): ChainModel {
        val events = mutableListOf<JudgementEvent>()
        val entries = board.entries.sortedBy { it.position }

        // --- IR length candidates -------------------------------------------------
        val bsdlLengths = entries.map { e ->
            e.bsdlSourceId?.let { id -> bsdlByDevice.values.firstOrNull { it.sourceId == id } }
                ?.instructionLength
        }
        val knownBsdl = bsdlLengths.filterNotNull()
        val candidates = linkedMapOf<String, IrLengthCandidate>()

        if (knownBsdl.size == entries.size) {
            val total = knownBsdl.sum()
            val fp = knownBsdl.joinToString(",")
            candidates[fp] = IrLengthCandidate(
                candidateId = StableIds.candidate(caseId, "bsdl:$fp"),
                perDeviceLengths = knownBsdl, total = total,
                evidence = knownBsdl.indices.map { "BSDL INSTRUCTION_LENGTH slot ${entries[it].position}" },
                adopted = observedIrLengths.isEmpty() || total in observedIrLengths,
                reason = "all BSDL files declare consistent IR lengths" +
                    if (total in observedIrLengths) "; matches observed IR scan" else "; NOT yet confirmed by observation",
            )
        }

        observedIrLengths.distinct().sorted().forEach { observed ->
            val decomp = decompose(observed, entries.size)
            decomp.forEach { lengths ->
                if (knownBsdl.indices.all { i ->
                        val k = bsdlLengths[i]; k == null || k == lengths[i]
                    }) {
                    val fp = lengths.joinToString(",")
                    if (fp !in candidates) {
                        candidates[fp] = IrLengthCandidate(
                            candidateId = StableIds.candidate(caseId, "obs:$fp"),
                            perDeviceLengths = lengths, total = observed,
                            evidence = listOf("observed IR scan length $observed bits",
                                "per-device decomposition of $observed into ${entries.size} positive lengths"),
                            adopted = false,
                            reason = "legal decomposition of the observed IR scan; " +
                                "IDCODE readout alone is forbidden from selecting it",
                        )
                    }
                }
            }
        }

        if (idcodeLsbFirst != null && observedIrLengths.isEmpty() && knownBsdl.size < entries.size) {
            events += JudgementEvent(
                eventId = StableIds.event(caseId, RuleCode.IRLENGTH_IDCODE_ONLY.machineRule, idcodeLsbFirst.take(16)),
                rule = RuleCode.IRLENGTH_IDCODE_ONLY,
                severity = EventSeverity.WARNING,
                message = "IDCODE scan available but IR length would otherwise be guessed; " +
                    "no IR candidate is adopted from IDCODE alone",
                basis = listOf("IDCODE is the fixed 32-bit TLR-observable register, not an IR length probe"),
                adopted = false,
                candidates = candidates.values.map { it.candidateId },
            )
        }

        if (candidates.size > 1) {
            events += JudgementEvent(
                eventId = StableIds.event(caseId, RuleCode.MULTI_SOLUTION_RETAIN.machineRule,
                    candidates.values.sortedBy { it.total }.joinToString("|") { it.total.toString() }),
                rule = RuleCode.MULTI_SOLUTION_RETAIN,
                severity = EventSeverity.WARNING,
                message = "${candidates.size} legal IR-length solutions retained; review must choose one explicitly",
                candidates = candidates.values.map { it.candidateId },
                adopted = false,
            )
        }

        // --- device slots from the 32-bit IDCODE stream ---------------------------
        val idcodesMsb: List<String?> = if (idcodeLsbFirst != null) {
            val padded = idcodeLsbFirst.padEnd(entries.size * 32, 'X')
            entries.indices.map { pos ->
                val lsb = padded.substring(pos * 32, pos * 32 + 32)
                if (lsb.contains('X')) null else lsb.reversed()
            }
        } else entries.map { null }

        val offsets = IntArray(entries.size + 1)
        val slots = entries.mapIndexed { idx, entry ->
            val bsdl = entry.bsdlSourceId?.let { id -> bsdlByDevice.values.firstOrNull { it.sourceId == id } }
            val observed = idcodesMsb[idx]
            val expected = entry.expectedIdcode?.uppercase()
            val bypass = entry.position in bypassPositions
            val present = when {
                bypass -> true
                observed == null -> false
                expected != null -> normalize(observed) == normalize(expected)
                else -> true
            }
            val drLen = when {
                bypass -> 1
                present -> bsdl?.cells?.size ?: 0
                else -> 0
            }
            offsets[idx + 1] = offsets[idx] + drLen
            DeviceSlot(
                position = entry.position,
                expectedName = entry.expectedName,
                bsdlVersion = bsdl?.bsdlVersion,
                idcodeObserved = observed,
                present = present,
                bypass = bypass,
                irLength = bsdl?.instructionLength,
                drOffset = offsets[idx],
                drLength = drLen,
                offsetCandidates = emptyList(),
            )
        }.toMutableList()

        // --- missing device: preserve every legal offset candidate ----------------
        slots.indices.forEach { idx ->
            val slot = slots[idx]
            if (!slot.present && !slot.bypass) {
                val bsdl = board.entries.first { it.position == slot.position }.bsdlSourceId
                    ?.let { id -> bsdlByDevice.values.firstOrNull { it.sourceId == id } }
                val possibleLengths = buildList {
                    add(1) // the missing population could be in BYPASS
                    bsdl?.cells?.size?.let { if (it > 0) add(it) }
                    addAll(1..32) // unknown device: retain bounded legal boundary lengths
                }.distinct().sorted()
                val earlier = (0 until idx).sumOf { slots[it].drLength }
                val laterKnown = (idx + 1..slots.lastIndex).sumOf {
                    when {
                        slots[it].bypass -> 1
                        slots[it].present -> slots[it].drLength
                        else -> 0
                    }
                }
                val offCandidates = possibleLengths.map { earlier + 0 }
                events += JudgementEvent(
                    eventId = StableIds.event(caseId, RuleCode.MISSING_DEVICE_OFFSET.machineRule,
                        "${slot.position}:$earlier"),
                    rule = RuleCode.MISSING_DEVICE_OFFSET,
                    severity = EventSeverity.ANOMALY,
                    message = "slot ${slot.position} (${slot.expectedName}) does not answer with expected " +
                        "IDCODE; dr-offset $earlier retained with ${possibleLengths.size} length hypotheses",
                    subjectIds = listOf("device:${slot.expectedName}"),
                    candidates = possibleLengths.map { "length=$it@offset=$earlier" },
                    adopted = false,
                    basis = listOf("observed=${slot.idcodeObserved ?: "none"}",
                        "expected=${board.entries.first { it.position == slot.position }.expectedIdcode ?: "n/a"}",
                        "downstream known bits=$laterKnown"),
                )
                slots[idx] = slot.copy(offsetCandidates = offCandidates)
            }
            if (slot.bypass) {
                events += JudgementEvent(
                    eventId = StableIds.event(caseId, RuleCode.BYPASS_DEVICE.machineRule,
                        slot.position.toString()),
                    rule = RuleCode.BYPASS_DEVICE,
                    severity = EventSeverity.INFO,
                    message = "slot ${slot.position} (${slot.expectedName}) held in BYPASS: 1 DR stage pad",
                    adopted = true,
                    basis = listOf("BYPASS opcode is all-ones per device BSDL"),
                )
            }
        }

        return ChainModel(
            deviceCount = entries.size,
            slots = slots,
            irCandidates = candidates.values.sortedBy { it.total },
            events = events,
        )
    }

    private fun normalize(s: String) = s.trim().lowercase().padStart(32, '0')

    /** All positive per-device length tuples summing to [total] for [n] devices. */
    fun decompose(total: Int, n: Int): List<List<Int>> {
        if (n <= 0) return emptyList()
        if (n == 1) return if (total >= 1) listOf(listOf(total)) else emptyList()
        val out = mutableListOf<List<Int>>()
        for (first in 1..total - (n - 1)) {
            decompose(total - first, n - 1).forEach { out += listOf(first) + it }
        }
        return out
    }
}
