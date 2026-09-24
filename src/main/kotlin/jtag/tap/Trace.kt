package jtag.tap

import jtag.model.*
import kotlinx.serialization.json.*

data class EdgeSample(
    val id: String, val index: Int, val tms: Int, val tdi: Int, val tdo: Int,
    val timeRaw: String, val timeUnit: String?
)

data class ResetEvent(val id: String, val edgeIndex: Int, val kind: String)

data class Evt(val type: String, val payload: JsonObject)

data class TraceEntry(
    val seq: Int, val state: TapState, val edgeIndex: Int, val edgeId: String,
    val tms: Int, val tdi: Int, val tdo: Int, val flags: List<String>
)

/**
 * 由采样边沿 + 复位事件 + 采样极性重建 TAP 轨迹。
 * 中途复位与非法转移都产生事件，绝不静默跳过。
 */
class TraceReconstructor {
    fun reconstruct(
        edges: List<EdgeSample>,
        resets: List<ResetEvent>,
        polarity: String
    ): Pair<List<TraceEntry>, List<Evt>> {
        val events = mutableListOf<Evt>()
        val entries = mutableListOf<TraceEntry>()
        val resetAt = resets.associateBy { it.edgeIndex }
        var state = TapState.TEST_LOGIC_RESET
        var prevState = state
        var prevRawTdo: Int? = null
        var prevTdoEff: Int? = null
        events += Evt("PolarityApplied", jsonOf("polarity" to polarity))
        edges.forEachIndexed { i, e ->
            val flags = mutableListOf<String>()
            val reset = resetAt[e.index]
            if (reset != null) {
                if (state.isShift || state.isPause) {
                    events += Evt("MidScanReset", jsonOf(
                        "edgeIndex" to e.index, "edgeId" to e.id,
                        "state" to state.name, "resetId" to reset.id,
                        "judgment" to "mid-scan-reset-not-skipped"
                    ))
                    flags += "mid-scan-reset"
                }
                state = TapState.TEST_LOGIC_RESET
                flags += "reset:${reset.kind}"
            } else {
                state = state.next(e.tms)
            }
            val tdoEff = if (polarity == "falling") (prevRawTdo ?: e.tdo) else e.tdo
            if (prevTdoEff != null && tdoEff != prevTdoEff && !state.isShiftFamily && !prevState.isShiftFamily) {
                events += Evt("IllegalTransition", jsonOf(
                    "edgeIndex" to e.index, "edgeId" to e.id,
                    "fromState" to prevState.name, "toState" to state.name,
                    "detail" to "tdo-changed-outside-shift",
                    "judgment" to "illegal-transition-not-skipped"
                ))
                flags += "tainted"
            }
            entries += TraceEntry(i, state, e.index, e.id, e.tms, e.tdi, tdoEff, flags)
            prevState = state
            prevRawTdo = e.tdo
            prevTdoEff = tdoEff
        }
        return entries to events
    }
}
