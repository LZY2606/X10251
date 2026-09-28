package jtag.chain

import jtag.derive.JudgmentEvent
import jtag.derive.Rules
import java.util.UUID

data class OffsetCandidate(
    val id: String,
    val missingDeviceId: String,
    val missingPosition: Int,
    val drOffsetShift: Int,     // bits the downstream offsets move by
    val rationale: String
)

data class ChainAssembly(
    val devices: List<ChainDevice>,
    val expectedDrLength: Int,
    val observedDrLength: Int,
    val offsetCandidates: List<OffsetCandidate>,
    val events: List<JudgmentEvent>
)

/**
 * Assembles the scan chain from a board revision and observed DR shift
 * length. When the observed length contradicts the expected chain (a device
 * missing or in BYPASS), every plausible offset explanation is retained.
 */
object ChainBuilder {
    fun assemble(board: BoardRevision, observedDrLength: Int): ChainAssembly {
        val events = mutableListOf<JudgmentEvent>()
        val expected = board.devices.sumOf { d ->
            when {
                !d.present -> 0
                d.bypass -> 1
                else -> 1 // BYPASS register minimum; boundary length resolved per-instruction
            }
        }
        val offsets = mutableListOf<OffsetCandidate>()
        if (observedDrLength != expected) {
            val delta = expected - observedDrLength
            var n = 0
            // Candidate: each present device could be the missing/short one.
            for (d in board.devices.filter { it.present }) {
                offsets += OffsetCandidate(
                    id = "off-%02d".format(++n),
                    missingDeviceId = d.id,
                    missingPosition = d.position,
                    drOffsetShift = -delta,
                    rationale = "if ${d.name} at position ${d.position} is absent or held in BYPASS, downstream offsets shift by $delta bit(s)"
                )
            }
            events += JudgmentEvent(
                id = "ev-" + UUID.randomUUID(), kind = "DEVICE_MISSING_OFFSETS", severity = "WARNING",
                message = "observed DR length $observedDrLength != expected $expected; retaining ${offsets.size} offset candidates",
                subjects = offsets.map { it.missingDeviceId },
                evidence = mapOf("expected" to expected.toString(), "observed" to observedDrLength.toString(),
                    "candidates" to offsets.joinToString(";") { "${it.missingDeviceId}@${it.missingPosition}" }),
                basis = Rules.MISSING_DEVICE_OFFSETS
            )
        }
        return ChainAssembly(board.devices, expected, observedDrLength, offsets, events)
    }
}
