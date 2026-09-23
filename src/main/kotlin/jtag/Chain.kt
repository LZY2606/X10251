package jtag

/** A candidate IR length for one chain position, with its evidence source.
 *  IR length must never be fixed from IDCODE alone. */
data class IrLenCandidate(
    val position: Int,          // chain position, basis: 0 = closest to TDI
    val length: Qty,            // bits, basis "ir-shift"
    val source: String          // "bsdl:<id>" | "capture-pattern" | "manual" | "idcode-guess"
)

data class ChainDevice(
    val name: String,
    val bsdlId: String?,
    val irLength: Int,
    val bypass: Boolean,        // currently in BYPASS (1-bit DR)
    val idcode: Long?           // captured IDCODE if the IDCODE instruction was loaded
)

data class ChainHypothesis(
    val id: String,
    val devices: List<ChainDevice>,
    val missingPositions: List<Int>,   // positions the board revision lists but the chain does not show
    val offsetBasis: String = "chain bit 0 = first bit out of TDO",
    val rationale: String
)

data class ChainModel(
    val status: String,                 // UNIQUE | AMBIGUOUS | DEGRADED
    val hypotheses: List<ChainHypothesis>,
    val findings: List<Finding>
)

object ChainSolver {
    /** All compositions of total IR capture length into per-device lengths
     *  consistent with the `...01` capture terminator. Multi-solutions are
     *  kept; nothing is collapsed to a single answer. */
    fun irLengthCandidatesFromCapture(tdoBits: List<Int>, maxDevices: Int = 4): List<List<Int>> {
        val n = tdoBits.size
        val out = mutableListOf<List<Int>>()
        // capture pattern bit0 comes out first and equals 1, bit1 equals 0
        fun term(end: Int) = end >= 2 && tdoBits[end - 2] == 1 && tdoBits[end - 1] == 0
        fun rec(start: Int, acc: List<Int>) {
            if (start == n) { if (acc.isNotEmpty()) out += acc.toList(); return }
            if (acc.size >= maxDevices) return
            for (len in 2..(n - start)) {
                val end = start + len
                if (end <= n && term(end)) { acc += len; rec(end, acc); acc.removeAt(acc.size - 1) }
            }
        }
        rec(0, mutableListOf())
        return out.distinct()
    }

    /** Count devices from a DR capture after reset: IDCODE (bit0=1) or BYPASS (bit0=0).
     *  Device count bounds hypotheses but never fixes IR lengths by itself. */
    fun drCaptureDeviceBits(tdoBits: List<Int>): Pair<Int, List<Boolean>> {
        var count = 0; var i = 0; val hasId = mutableListOf<Boolean>()
        while (i < tdoBits.size && count < 16) {
            if (tdoBits[i] == 1) { // IDCODE: 32 bits
                hasId += true; count++; i += 32
            } else if (tdoBits[i] == 0) { // BYPASS: 1 bit
                hasId += false; count++; i += 1
            } else break
        }
        return count to hasId
    }

    /** Solve the chain. When the board revision lists more devices than the
     *  capture shows, offset candidates are preserved as separate hypotheses. */
    fun solve(
        irCapture: List<Int>?,
        drCapture: List<Int>?,
        bsdlIrLens: List<Pair<String, Int>>,   // (bsdlId, irLength) in board order, TDI-first
        boardDeviceCount: Int,
        boardNames: List<String>
    ): ChainModel {
        val findings = mutableListOf<Finding>()
        val (drCount, _) = drCapture?.let { drCaptureDeviceBits(it) } ?: (0 to emptyList())
        val captureCombos = irCapture?.let { irLengthCandidatesFromCapture(it) } ?: emptyList()

        val hypotheses = mutableListOf<ChainHypothesis>()
        val missing = boardDeviceCount - drCount
        if (missing > 0) {
            findings += Finding("CHAIN_DEVICE_MISSING", Severity.WARN,
                "board revision lists $boardDeviceCount devices but DR capture shows $drCount; keeping offset candidates",
                "chain")
            // one hypothesis per possible missing position (offset candidates)
            for (slot in 0..boardDeviceCount - 1) {
                val devs = mutableListOf<ChainDevice>()
                var bi = 0
                for (pos in 0 until boardDeviceCount) {
                    if (pos == slot) continue
                    val (bsdl, ir) = bsdlIrLens.getOrElse(bi) { "?" to 4 }
                    devs += ChainDevice(boardNames.getOrElse(bi) { "dev$bi" }, bsdl, ir, bypass = true, idcode = null)
                    bi++
                }
                hypotheses += ChainHypothesis("hyp-missing@$slot", devs, listOf(slot),
                    rationale = "device at board position $slot assumed unpopulated; offsets shifted accordingly")
            }
        }
        // capture-pattern-driven hypotheses (IR length multi-solution)
        if (captureCombos.size > 1) {
            findings += Finding("IRLEN_AMBIGUOUS", Severity.WARN,
                "IR capture pattern admits ${captureCombos.size} decompositions: ${captureCombos.joinToString()}; all kept",
                "irlen")
        }
        captureCombos.forEachIndexed { k, combo ->
            val devs = combo.mapIndexed { p, len ->
                ChainDevice(boardNames.getOrElse(p) { "dev$p" }, bsdlIrLens.getOrNull(p)?.first, len,
                    bypass = false, idcode = null)
            }
            hypotheses += ChainHypothesis("hyp-capture-$k", devs, emptyList(),
                rationale = "IR capture decomposition $combo")
        }
        if (hypotheses.isEmpty() && bsdlIrLens.isNotEmpty()) {
            val devs = bsdlIrLens.mapIndexed { p, (b, ir) ->
                ChainDevice(boardNames.getOrElse(p) { "dev$p" }, b, ir, bypass = false, idcode = null)
            }
            hypotheses += ChainHypothesis("hyp-bsdl", devs, emptyList(), rationale = "BSDL-declared lengths")
        }
        val status = if (hypotheses.size > 1) "AMBIGUOUS" else "UNIQUE"
        return ChainModel(status, hypotheses, findings)
    }

    /** Guard rail: reject any attempt to fix IR length from IDCODE alone. */
    fun irLenFromIdcodeOnly(idcode: Long): IrLenCandidate {
        throw IllegalStateException(
            "IRLEN_NOT_FROM_IDCODE: IDCODE 0x${idcode.toString(16)} identifies a device, not its IR length; " +
            "use BSDL or capture-pattern evidence and keep all candidates")
    }
}
