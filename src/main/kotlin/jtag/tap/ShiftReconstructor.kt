package jtag.tap

/** One reconstructed shift operation (IR or DR). Bits are LSB-first as shifted. */
data class ShiftCapture(
    val id: String,
    val kind: String,               // IR | DR
    val tdiBits: List<Int>,
    val tdoBits: List<Int>,
    val startEdgeOrd: Int,
    val endEdgeOrd: Int,
    val status: String              // COMPLETE | ABORTED_BY_RESET
) {
    fun tdiHex(): String = bitsToHex(tdiBits)
    fun tdoHex(): String = bitsToHex(tdoBits)
    fun tdiBitString(): String = tdiBits.joinToString("")
    fun tdoBitString(): String = tdoBits.joinToString("")

    companion object {
        fun bitsToHex(bits: List<Int>): String {
            if (bits.isEmpty()) return "0x0"
            val sb = StringBuilder("0x")
            var i = bits.size
            while (i > 0) {
                val lo = maxOf(0, i - 4)
                var v = 0
                for (b in lo until i) v = (v shl 1) or bits[b]
                sb.append(v.toString(16))
                i = lo
            }
            return sb.toString()
        }
    }
}

/** Reconstructs IR/DR shift bitstreams from a TAP trajectory. */
object ShiftReconstructor {
    fun reconstruct(trace: TraceResult): List<ShiftCapture> {
        val captures = mutableListOf<ShiftCapture>()
        var kind: String? = null
        val tdi = mutableListOf<Int>()
        val tdo = mutableListOf<Int>()
        var start = -1
        var n = 0

        fun flush(endOrd: Int, status: String) {
            if (kind != null && tdi.isNotEmpty()) {
                n++
                captures += ShiftCapture("shift-%03d".format(n), kind!!, tdi.toList(), tdo.toList(), start, endOrd, status)
            }
            kind = null; tdi.clear(); tdo.clear(); start = -1
        }

        for (step in trace.steps) {
            val inShift = step.to.isShift
            if (inShift) {
                if (kind == null) { kind = if (step.to == TapState.SHIFT_IR) "IR" else "DR"; start = step.ord }
                tdi += step.tdi; tdo += step.tdo
            } else if (kind != null && !step.to.isPause) {
                val aborted = trace.abortedShiftRanges.any { step.ord in it }
                flush(step.ord, if (aborted) "ABORTED_BY_RESET" else "COMPLETE")
            }
        }
        flush(trace.steps.lastOrNull()?.ord ?: -1, "COMPLETE")
        return captures
    }
}
