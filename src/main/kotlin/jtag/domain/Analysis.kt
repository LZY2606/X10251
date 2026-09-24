package jtag.domain

/** One contiguous Shift-IR / Shift-DR episode with the raw bit stream. */
data class ShiftSegment(
    val kind: String, // "IR" or "DR"
    val startEdge: Int,
    val endEdge: Int,
    val tdiBits: List<Int>,
    val tdoBits: List<Int>
) {
    val length: Int get() = tdoBits.size
    fun tdoHex(): String = bitsToHex(tdoBits)
    fun tdiHex(): String = bitsToHex(tdiBits)
}

fun bitsToHex(bits: List<Int>): String {
    if (bits.isEmpty()) return "0x0"
    val sb = StringBuilder("0x")
    var i = bits.size
    while (i > 0) {
        val from = maxOf(0, i - 4)
        var v = 0
        for (b in from until i) v = (v shl 1) or (bits[b] and 1)
        sb.append(v.toString(16))
        i = from
    }
    return sb.toString()
}

object ShiftExtractor {
    fun segments(trace: TapTrace): List<ShiftSegment> {
        val out = ArrayList<ShiftSegment>()
        var kind: String? = null
        var start = 0
        val tdi = ArrayList<Int>()
        val tdo = ArrayList<Int>()
        fun flush(endEdge: Int) {
            val k = kind ?: return
            if (tdo.isNotEmpty()) out += ShiftSegment(k, start, endEdge, tdi.toList(), tdo.toList())
            kind = null; tdi.clear(); tdo.clear()
        }
        for (step in trace.steps) {
            if (step.illegal || step.reset) { flush(step.edgeSeq); continue }
            val inShiftIr = step.to == TapState.SHIFT_IR
            val inShiftDr = step.to == TapState.SHIFT_DR
            if (inShiftIr || inShiftDr) {
                val k = if (inShiftIr) "IR" else "DR"
                if (kind != k) { flush(step.edgeSeq); kind = k; start = step.edgeSeq }
                tdi += step.tdi; tdo += step.tdo
            } else {
                flush(step.edgeSeq)
            }
        }
        flush(trace.steps.lastOrNull()?.edgeSeq ?: 0)
        return out
    }
}

/** A candidate for the per-device IR lengths of the chain. Multiple candidates
 *  are kept whenever the capture pattern does not decide uniquely. */
data class IrLengthCandidate(
    val id: String,
    val lengths: List<Int>, // per device, TDO-first order
    val status: String,     // "open" | "adopted" | "rejected"
    val source: String,     // "trace-analysis" | "vendor-doc" | "idcode-guess"
    val reason: String = ""
) {
    val total: Int get() = lengths.sum()
}

object IrAnalyzer {
    /**
     * Derive IR length candidates from a Shift-IR capture pattern.
     * Every device captures ...01 in its two LSBs, so a split is plausible at
     * every position p (1-based from TDO) where bits[p-2..p-1] == [0,1] counting
     * per-device from the chain end. All consistent decompositions are returned;
     * nothing is collapsed to a single answer, and IDCODE content is never used
     * to guess IR length.
     */
    fun candidatesFromCapture(tdoBits: List<Int>, maxDevices: Int = 8): List<List<Int>> {
        val n = tdoBits.size
        if (n < 2) return emptyList()
        val results = ArrayList<List<Int>>()
        // positions (0-based, from TDO) where a device boundary may sit:
        // boundary after index b means bits[b-1]==0? capture LSBs are ...01 with
        // bit0=1, bit1=0 shifted out first -> tdo[0]=1, tdo[1]=0 for each device.
        fun decompose(start: Int, acc: List<Int>) {
            if (start >= n) {
                if (acc.isNotEmpty()) results += acc
                return
            }
            if (acc.size >= maxDevices) return
            for (len in 2..(n - start)) {
                val end = start + len
                // capture bits of this device occupy [start, end); its two LSBs
                // are tdo[start]=1 and tdo[start+1]=0.
                if (tdoBits[start] == 1 && tdoBits[start + 1] == 0) {
                    decompose(end, acc + len)
                }
            }
        }
        decompose(0, emptyList())
        return results
    }
}

/** Device on the scan chain, TDO-first order. */
data class ChainDevice(
    val index: Int,
    val name: String,
    val idcode: String?,   // hex string if IDCODE instruction present
    val bypass: Boolean,
    val missing: Boolean,  // expected by board revision but not observed
    val irLength: Int?,
    val bsdlId: String? = null
)

/** Boundary-scan cell offset assignment. When a device is missing, several
 *  offset layouts stay possible; every layout is kept as its own candidate. */
data class ChainLayout(
    val id: String,
    val devices: List<ChainDevice>,
    val cellOffsets: Map<String, Int>, // device name -> first cell offset from TDO
    val status: String,                // "open" | "adopted" | "rejected"
    val note: String = ""
)

object ChainAnalyzer {
    /** Parse a DR shift segment into devices: 32-bit words with LSB=1 are
     *  IDCODEs, a single 0 bit is a BYPASS capture. */
    fun parseIdcodeSegment(tdoBits: List<Int>): List<Pair<String?, Boolean>> {
        val out = ArrayList<Pair<String?, Boolean>>()
        var i = 0
        while (i < tdoBits.size) {
            if (tdoBits[i] == 0) { out += (null to true); i += 1 }
            else {
                val remain = tdoBits.size - i
                if (remain < 32) { out += (null to true); i = tdoBits.size; break }
                val word = tdoBits.subList(i, i + 32)
                var v = 0L
                for (b in 31 downTo 0) v = (v shl 1) or word[b].toLong()
                out += ("0x" + v.toString(16).padStart(8, '0') to false)
                i += 32
            }
        }
        return out
    }

    /**
     * Build chain layouts. If the board revision expects more devices than were
     * observed, the missing ones are inserted at every plausible position and
     * each resulting layout is kept as an offset candidate.
     */
    fun layouts(
        observed: List<Pair<String?, Boolean>>,
        expectedNames: List<String>,
        irCandidate: IrLengthCandidate?,
        bsdlCells: Map<String, Int> // device name -> boundary length
    ): List<ChainLayout> {
        val obsCount = observed.size
        val missing = (expectedNames.size - obsCount).coerceAtLeast(0)
        val layouts = ArrayList<ChainLayout>()
        val positions = if (missing == 0) listOf(emptyList<Int>())
        else (0..obsCount).map { listOf(it) } // single missing device insertion points

        for ((variantIdx, inserts) in positions.withIndex()) {
            val devices = ArrayList<ChainDevice>()
            val obsIt = observed.iterator()
            val missingNames = expectedNames.takeLast(missing)
            var obsIdx = 0
            val total = obsCount + missing
            for (pos in 0 until total) {
                if (inserts.contains(pos)) {
                    val nm = missingNames.getOrElse(0) { "MISSING$pos" }
                    devices += ChainDevice(pos, nm, null, bypass = false, missing = true,
                        irLength = null, bsdlId = null)
                } else {
                    val (idcode, bypass) = obsIt.next()
                    val nm = expectedNames.getOrElse(obsIdx) { "DEV$obsIdx" }
                    devices += ChainDevice(pos, nm, idcode, bypass, missing = false,
                        irLength = irCandidate?.lengths?.getOrNull(obsIdx),
                        bsdlId = null)
                    obsIdx++
                }
            }
            val offsets = LinkedHashMap<String, Int>()
            var off = 0
            for (d in devices) {
                offsets[d.name] = off
                off += bsdlCells[d.name] ?: 0
            }
            val status = if (missing == 0 && variantIdx == 0) "adopted" else "open"
            val note = if (missing > 0) "missing device offset candidate #${variantIdx + 1}" else "complete chain"
            layouts += ChainLayout("layout-${variantIdx + 1}", devices, offsets, status, note)
        }
        return layouts
    }
}
