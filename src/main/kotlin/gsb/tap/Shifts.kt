package gsb.tap

enum class ShiftKind { IR, DR }

/** One Shift-IR / Shift-DR episode extracted from the TAP trace. */
data class ShiftEpisode(
    val id: String,
    val kind: ShiftKind,
    val startClock: Int,
    val endClock: Int,
    val tdiBits: List<Level>,
    val tdoBits: List<Level>,
    val aborted: Boolean,
) {
    val length get() = tdiBits.size
    fun tdoBitString(): String = tdoBits.joinToString("") {
        when (it) { Level.HIGH -> "1"; Level.LOW -> "0"; Level.X -> "X"; Level.Z -> "Z" } }
    fun tdiBitString(): String = tdiBits.joinToString("") {
        when (it) { Level.HIGH -> "1"; Level.LOW -> "0"; Level.X -> "X"; Level.Z -> "Z" } }
}

object ShiftExtractor {
    fun extract(trace: TraceResult): List<ShiftEpisode> {
        val episodes = mutableListOf<ShiftEpisode>()
        var kind: ShiftKind? = null
        var start = -1
        val tdi = mutableListOf<Level>()
        val tdo = mutableListOf<Level>()
        fun close(endClock: Int) {
            val k = kind ?: return
            val aborted = trace.abortedShiftClocks.any { it in start..endClock }
            episodes += ShiftEpisode("shift@${start}", k, start, endClock, tdi.toList(), tdo.toList(), aborted)
            kind = null; tdi.clear(); tdo.clear()
        }
        for (step in trace.steps) {
            val s = step.state
            val active = s == TapState.SHIFT_IR || s == TapState.SHIFT_DR
            if (active) {
                val k = if (s == TapState.SHIFT_IR) ShiftKind.IR else ShiftKind.DR
                if (kind != k) { close(step.clock - 1); kind = k; start = step.clock }
                tdi += step.tdi; tdo += step.tdo
            } else if (kind != null) close(step.clock)
        }
        if (kind != null) close(trace.steps.lastOrNull()?.clock ?: -1)
        return episodes
    }
}
