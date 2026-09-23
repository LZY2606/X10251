package jtag.tap

import jtag.model.*
import kotlinx.serialization.Serializable

@Serializable
enum class ShiftKind { IR, DR }

@Serializable
data class ShiftBitstream(
    val segmentIndex: Int,
    val kind: ShiftKind,
    val clockCount: Int,
    val tdiBits: List<Boolean>,   // in shift order (first clocked first)
    val tdoBits: List<Boolean>,
    val abortedByReset: Boolean = false
) {
    fun tdoHex(): String = bitsToHex(tdoBits)
    fun tdiHex(): String = bitsToHex(tdiBits)
    companion object {
        fun bitsToHex(bits: List<Boolean>): String {
            if (bits.isEmpty()) return "0x0"
            val padded = bits + List((4 - bits.size % 4) % 4) { false }
            return "0x" + padded.chunked(4).joinToString("") { nibble ->
                nibble.foldIndexed(0) { i, acc, b -> if (b) acc or (1 shl i) else acc }.toString(16)
            }
        }
    }
}

object ShiftExtractor {
    fun extract(traj: TapTrajectory): List<ShiftBitstream> {
        val out = mutableListOf<ShiftBitstream>()
        for (seg in traj.segments) {
            var kind: ShiftKind? = null
            var tdi = mutableListOf<Boolean>()
            var tdo = mutableListOf<Boolean>()
            for (st in seg.steps) {
                when (st.state) {
                    TapState.SHIFT_IR -> { kind = ShiftKind.IR; tdi += st.tdi; tdo += st.tdo }
                    TapState.SHIFT_DR -> { kind = ShiftKind.DR; tdi += st.tdi; tdo += st.tdo }
                    else -> {
                        if (kind != null) {
                            out += ShiftBitstream(seg.index, kind, tdi.size, tdi.toList(), tdo.toList(), seg.abortedByReset)
                            kind = null; tdi = mutableListOf(); tdo = mutableListOf()
                        }
                    }
                }
            }
            if (kind != null) out += ShiftBitstream(seg.index, kind, tdi.size, tdi.toList(), tdo.toList(), seg.abortedByReset)
        }
        return out
    }
}

@Serializable
data class IrCandidateVerdict(
    val candidate: IrLengthCandidate,
    val consistent: Boolean,
    val reason: String
)

@Serializable
data class IrAnalysis(
    val observedIrClocks: Int?,
    val verdicts: List<IrCandidateVerdict>,
    val surviving: List<IrLengthCandidate>,
    val judgments: List<String>
)

object IrLengthAnalyzer {
    /**
     * Rule: IR length may NOT be inferred from IDCODE alone. IDCODE only tells us the
     * post-reset DR length of a device; it says nothing about its IR length.
     * A candidate survives iff its total IR length matches the observed Shift-IR clock count.
     */
    fun analyze(shifts: List<ShiftBitstream>, candidates: List<IrLengthCandidate>, idcodeObserved: Boolean): IrAnalysis {
        val irShift = shifts.filter { it.kind == ShiftKind.IR && !it.abortedByReset }.maxByOrNull { it.clockCount }
        val observed = irShift?.clockCount
        val judgments = mutableListOf<String>()
        if (idcodeObserved)
            judgments += "JUDGMENT ir-length-not-from-idcode: IDCODE was observed on DR; per rule it does not reduce IR length candidates"
        val verdicts = candidates.map { c ->
            val total = c.perDeviceBits.sum()
            when {
                observed == null -> IrCandidateVerdict(c, true, "no completed Shift-IR observed; candidate kept")
                total == observed -> IrCandidateVerdict(c, true, "sum ${c.perDeviceBits}=$total matches observed $observed Shift-IR clocks")
                else -> IrCandidateVerdict(c, false, "sum ${c.perDeviceBits}=$total contradicts observed $observed Shift-IR clocks")
            }
        }
        val surviving = verdicts.filter { it.consistent }.map { it.candidate }
        if (surviving.size > 1)
            judgments += "JUDGMENT ir-length-ambiguous: ${surviving.size} candidates survive; all legal candidates retained, no unique answer forced"
        return IrAnalysis(observed, verdicts, surviving, judgments)
    }
}

@Serializable
data class ChainDevice(
    val position: Int,
    val name: String,
    val idcode: String?,          // observed 32-bit idcode, null when bypassed/missing
    val bypass: Boolean,
    val missing: Boolean = false,
    val bsdlRef: String? = null
)

@Serializable
data class OffsetCandidate(
    val id: String = newId(),
    val missingDevice: String,
    val insertPosition: Int,      // chain position where the missing device may sit
    val rationale: String,
    val status: CandidateStatus = CandidateStatus.PENDING
)

@Serializable
data class ChainAnalysis(
    val devices: List<ChainDevice>,
    val idcodes: List<String>,
    val bypassCount: Int,
    val offsetCandidates: List<OffsetCandidate>,
    val judgments: List<String>
)

object ChainAnalyzer {
    /**
     * Parse the first post-reset DR shift into 32-bit IDCODE / 1-bit BYPASS fields,
     * then reconcile against the board revision. Missing devices keep offset candidates.
     */
    fun analyze(shifts: List<ShiftBitstream>, board: BoardRevision): ChainAnalysis {
        val judgments = mutableListOf<String>()
        val dr = shifts.filter { it.kind == ShiftKind.DR && !it.abortedByReset }.maxByOrNull { it.clockCount }
        val idcodes = mutableListOf<String>()
        var bypassCount = 0
        val observed = mutableListOf<Pair<String?, Boolean>>() // idcode? to bypass
        if (dr != null) {
            var i = 0
            val bits = dr.tdoBits
            while (i < bits.size) {
                // BYPASS registers read as a single 0 bit right after capture.
                if (!bits[i]) { bypassCount++; observed += (null to true); i += 1 }
                else if (i + 32 <= bits.size) {
                    val code = bits.subList(i, i + 32).foldIndexed(0L) { idx, acc, b -> if (b) acc or (1L shl idx) else acc }
                    idcodes += "%08X".format(code)
                    observed += ("%08X".format(code) to false)
                    i += 32
                } else { i += 1 }
            }
        }
        val devices = mutableListOf<ChainDevice>()
        val offsets = mutableListOf<OffsetCandidate>()
        val expected = board.slots.sortedBy { it.position }
        val observedCount = observed.size
        var pos = 0
        for ((idx, slot) in expected.withIndex()) {
            if (idx < observedCount) {
                val (code, bypass) = observed[idx]
                devices += ChainDevice(pos++, slot.name, code, bypass, bsdlRef = slot.bsdlRef)
                if (bypass) judgments += "JUDGMENT bypass-device: ${slot.name} shows 1-bit DR; modeled as BYPASS, not dropped from chain"
            } else {
                // chain shorter than expected: missing device, keep all offset candidates
                devices += ChainDevice(pos++, slot.name, null, bypass = false, missing = true, bsdlRef = slot.bsdlRef)
                for (insertAt in 0..observedCount) {
                    offsets += OffsetCandidate(
                        missingDevice = slot.name,
                        insertPosition = insertAt,
                        rationale = "chain observed $observedCount device(s), expected ${expected.size}; '${slot.name}' may sit at offset $insertAt"
                    )
                }
                judgments += "JUDGMENT missing-device-offsets-kept: '${slot.name}' not observed; ${observedCount + 1} offset candidates retained"
            }
        }
        return ChainAnalysis(devices, idcodes, bypassCount, offsets, judgments)
    }
}
