package jtag

import kotlinx.serialization.Serializable

@Serializable
data class ShiftRecord(
    val index: Int,
    val kind: String,                 // IR | DR
    val tdiBits: List<Int?>,
    val tdoBits: List<Int?>,
    val startTNs: Double,
    val endTNs: Double,
    val invalidated: Boolean = false,
    val invalidReason: String? = null,
    val tainted: Boolean = false,
    /** coordinate basis for bit positions */
    val basis: String = "bit0=first bit shifted at TDI while in SHIFT state",
) {
    fun tdiHex(): String = bitsHex(tdiBits)
    fun tdoHex(): String = bitsHex(tdoBits)
    val length: Int get() = tdiBits.size
    companion object {
        fun bitsHex(bits: List<Int?>): String {
            if (bits.isEmpty()) return ""
            val nibbles = mutableListOf<Int>()
            var acc = 0; var n = 0
            for (b in bits) {           // bit0 is LSB of the shifted value
                acc = acc or ((b ?: 0) shl n); n++
                if (n == 4) { nibbles += acc; acc = 0; n = 0 }
            }
            if (n > 0) nibbles += acc
            return nibbles.asReversed().joinToString("") { it.toString(16) }
        }
    }
}

object ShiftExtractor {
    /**
     * Bit alignment model: one bit is shifted on every TCK edge whose *source*
     * state is SHIFT_IR/SHIFT_DR (this includes the edge that exits SHIFT).
     */
    fun extract(trace: TapTrace): Pair<List<ShiftRecord>, List<JudgmentEvent>> {
        val records = mutableListOf<ShiftRecord>()
        val events = mutableListOf<JudgmentEvent>()
        var kind: String? = null
        val tdi = mutableListOf<Int?>()
        val tdo = mutableListOf<Int?>()
        var startT = 0.0
        var tainted = false
        var invalidated = false
        var reason: String? = null

        fun close(endT: Double) {
            val k = kind ?: return
            if (tdi.isNotEmpty() || invalidated) {
                records += ShiftRecord(records.size, k, tdi.toList(), tdo.toList(),
                    startT, endT, invalidated, reason, tainted)
            }
            kind = null; tdi.clear(); tdo.clear()
            invalidated = false; reason = null; tainted = false
        }

        for (step in trace.steps) {
            val fromShift = step.from == TapState.SHIFT_IR || step.from == TapState.SHIFT_DR
            val toShift = step.to == TapState.SHIFT_IR || step.to == TapState.SHIFT_DR
            if (toShift && !fromShift && kind == null) {
                kind = if (step.to == TapState.SHIFT_IR) "IR" else "DR"
                startT = step.tNs
            }
            if (kind != null && step.eventIds.isNotEmpty()) {
                invalidated = true
                val causes = trace.events.filter { it.id in step.eventIds }
                reason = causes.joinToString(";") { "${it.type}@${it.payload["at_ns"] ?: "?"}" }
                for (e in causes) {
                    events += JudgmentEvent("evt-shiftinv-${records.size}-${e.id}",
                        EventTypes.SHIFT_INVALIDATED, e.rule,
                        mapOf("shift_index" to records.size.toString(), "cause" to e.type),
                        listOf(e.id))
                }
            }
            if (kind != null && step.tainted) tainted = true
            if (kind != null && fromShift) { tdi += step.tdi; tdo += step.tdo }
            if (kind != null && fromShift && !toShift) close(step.tNs)
        }
        close(trace.steps.lastOrNull()?.tNs ?: 0.0)
        return records to events
    }
}
