package jtag.tap

import kotlinx.serialization.Serializable

@Serializable
data class DeviceSpec(
    val index: Int,
    val name: String,
    val idcode: String?,          // 32-bit hex, null if unknown
    val irLengthBits: Int,
    val bypassOnly: Boolean = false,  // device only supports BYPASS (1-bit DR)
    val bsdlCells: Int = 0            // boundary-scan register length from BSDL
)

@Serializable
data class BoardRevision(val rev: String, val devices: List<DeviceSpec>)

/** One possible placement of a device on the observed chain. */
@Serializable
data class OffsetCandidate(
    val deviceIndex: Int,
    val chainPosition: Int,     // position in observed chain (0 = closest to TDI)
    val drOffsetBits: Int,      // bit offset of this device's DR in the chain
    val rationale: String
)

@Serializable
data class ChainResult(
    val observedBypassBits: Int,
    val devicesPresent: List<Int>,
    val devicesMissing: List<Int>,
    val offsetCandidates: List<OffsetCandidate>,
    val events: List<TapEvent>
)

/**
 * Builds the device chain view from a board revision and the observed
 * BYPASS-mode DR chain length.
 *
 * Rule: when a device is missing from the chain, offset candidates for the
 * remaining devices are ALL preserved; nothing is collapsed to one answer.
 */
object ChainBuilder {

    fun build(board: BoardRevision, observedBypassBits: Int): ChainResult {
        val events = mutableListOf<TapEvent>()
        val expected = board.devices.size
        val present = mutableListOf<Int>()
        val missing = mutableListOf<Int>()

        if (observedBypassBits == expected) {
            present += board.devices.map { it.index }
        } else if (observedBypassBits < expected) {
            // conservatively: cannot tell WHICH devices are missing -> mark tail
            // candidates missing but keep offsets for every surviving prefix
            val missingCount = expected - observedBypassBits
            present += board.devices.take(observedBypassBits).map { it.index }
            missing += board.devices.takeLast(missingCount).map { it.index }
            events += TapEvent(
                "CHAIN_DEVICE_MISSING", 0, "WARN",
                "Observed BYPASS chain is $observedBypassBits bits but board rev ${board.rev} expects $expected devices; " +
                    "$missingCount device(s) missing. Offset candidates preserved for all placements.",
                mapOf("expected" to expected.toString(), "observed" to observedBypassBits.toString(),
                      "missingCandidates" to missing.joinToString(","))
            )
        } else {
            events += TapEvent(
                "CHAIN_LONGER_THAN_EXPECTED", 0, "WARN",
                "Observed BYPASS chain ($observedBypassBits bits) is longer than board rev ${board.rev} expects ($expected)",
                mapOf("expected" to expected.toString(), "observed" to observedBypassBits.toString())
            )
            present += board.devices.map { it.index }
        }

        // offset candidates: for each present device, every chain position consistent
        // with the observed length is kept (missing devices make placement ambiguous)
        val offsets = mutableListOf<OffsetCandidate>()
        if (missing.isEmpty()) {
            var off = 0
            for (dev in board.devices) {
                offsets += OffsetCandidate(dev.index, dev.index, off, "unique placement, chain complete")
                off += 1 // BYPASS register is 1 bit per device
            }
        } else {
            for (dev in board.devices.filter { it.index in present }) {
                for (pos in 0 until observedBypassBits) {
                    offsets += OffsetCandidate(
                        dev.index, pos, pos,
                        "candidate placement ${dev.index}->pos$pos under ${missing.size} missing device(s)"
                    )
                }
            }
        }

        return ChainResult(observedBypassBits, present, missing, offsets, events)
    }
}
