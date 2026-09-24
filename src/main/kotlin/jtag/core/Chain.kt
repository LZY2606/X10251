package jtag.core

import kotlinx.serialization.Serializable

/** IR length candidate as imported (separate source kind). */
@Serializable
data class IrLengthCandidate(
    val device: String,
    val length: Int,
    val origin: String,   // BSDL | USER | BOARD_DEFAULT | IDCODE_GUESS
)

@Serializable
enum class IrStatus { RESOLVED, AMBIGUOUS, UNRESOLVED }

@Serializable
data class IrResolution(
    val device: String,
    val status: IrStatus,
    val chosen: Int?,                 // only when RESOLVED
    val candidates: List<IrLengthCandidate>,  // ALL legal candidates retained
    val rationale: String,
)

/**
 * Resolve IR lengths. Guardrail: IR length must NOT be guessed from IDCODE
 * alone — IDCODE identifies the part, not its IR register. Without BSDL or an
 * explicit candidate, every legal candidate is kept and status stays AMBIGUOUS.
 */
fun resolveIrLengths(
    devices: List<String>,
    candidates: List<IrLengthCandidate>,
    bsdl: Map<String, BsdlPackage>,
): List<IrResolution> = devices.map { dev ->
    val pkg = bsdl[dev]
    val cands = candidates.filter { it.device == dev }
    when {
        pkg?.instructionLength != null ->
            IrResolution(dev, IrStatus.RESOLVED, pkg.instructionLength,
                listOf(IrLengthCandidate(dev, pkg.instructionLength, "BSDL")) + cands,
                "INSTRUCTION_LENGTH=${pkg.instructionLength} from BSDL ${pkg.name} v${pkg.version}")
        cands.count { it.origin != "IDCODE_GUESS" } == 1 -> {
            val c = cands.first { it.origin != "IDCODE_GUESS" }
            IrResolution(dev, IrStatus.RESOLVED, c.length, cands, "single explicit candidate from ${c.origin}")
        }
        cands.isEmpty() || cands.all { it.origin == "IDCODE_GUESS" } ->
            IrResolution(dev, IrStatus.AMBIGUOUS, null, cands,
                "IR length cannot be determined from IDCODE alone; no BSDL/USER candidate available")
        else ->
            IrResolution(dev, IrStatus.AMBIGUOUS, null, cands,
                "multiple explicit candidates; all legal values retained")
    }
}

@Serializable
data class ChainDevice(
    val position: Int,
    val name: String,
    val idcode: String? = null,
    val bypass: Boolean = false,
    val present: Boolean = true,
)

/** Board revision: the expected chain as designed (separate source kind). */
@Serializable
data class BoardRevision(
    val revision: String,
    val devices: List<ChainDevice>,
)

@Serializable
data class OffsetCandidate(
    val missingDevice: String,
    val assumedPosition: Int,          // where the missing device is assumed to sit
    val downstreamShift: Int,          // cell offset shift implied for later devices
    val rationale: String,
)

@Serializable
data class ChainTopology(
    val observed: List<ChainDevice>,
    val missing: List<String>,
    val offsetCandidates: List<OffsetCandidate>,   // all retained when a device is missing
    val diagnostics: List<String>,
)

/**
 * Reconcile the observed scan (parsed from a capture-DR bitstream: 32-bit
 * IDCODEs where LSB=1, single bypass bits where LSB=0) against the board
 * revision. Guardrail: when a device is missing, keep ALL offset candidates.
 */
fun reconcileChain(observed: List<ChainDevice>, board: BoardRevision): ChainTopology {
    val expected = board.devices
    val missing = expected.filter { e -> observed.none { it.name == e.name } }.map { it.name }
    val diags = mutableListOf<String>()
    val offsets = mutableListOf<OffsetCandidate>()
    for (m in missing) {
        val expPos = expected.indexOfFirst { it.name == m }
        // every legal insertion position among the observed chain is a candidate
        for (pos in 0..observed.size) {
            val shift = expected.drop(expPos + 1).sumOf { if (it.bypass) 1 else 32 }
            offsets.add(OffsetCandidate(m, pos, shift,
                "assuming '$m' sits at observed position $pos shifts downstream cells by $shift bits"))
        }
        diags.add("device '$m' expected by board rev ${board.revision} not observed; ${observed.size + 1} offset candidates retained")
    }
    return ChainTopology(observed, missing, offsets, diags)
}

/** Parse a capture-DR bitstream (LSB-first) into observed devices. */
fun parseCaptureDr(bits: List<Int>, knownByIdcode: Map<String, String>): List<ChainDevice> {
    val out = mutableListOf<ChainDevice>()
    var i = 0
    while (i < bits.size) {
        if (bits[i] == 0) { // bypass register: single 0 bit
            out.add(ChainDevice(out.size, "unknown-bypass@${out.size}", bypass = true))
            i += 1
        } else {
            if (i + 32 > bits.size) break
            val code = bits.subList(i, i + 32).reversed().joinToString("") { it.toString() }
            val name = knownByIdcode[code] ?: "unknown-${code.take(8)}"
            out.add(ChainDevice(out.size, name, idcode = code))
            i += 32
        }
    }
    return out
}
