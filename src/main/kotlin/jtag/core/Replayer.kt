package jtag.core

import java.util.UUID

fun newId(prefix: String) = "$prefix:${UUID.randomUUID()}"

data class ReplayResult(
    val steps: List<TapStep>,
    val segments: List<ShiftSegment>,
    val events: List<JudgeEvent>
)

/**
 * Replays sampled TMS/TCK/TDI/TDO edges through the TAP FSM.
 * Rules enforced (never silently skipped):
 *  - mid-scan reset (TRST or 5x TMS=1 while mid-operation) invalidates the open
 *    shift segment and is recorded as MID_SCAN_RESET;
 *  - TDO evidence contradicting the mandatory IR capture pattern (..01) is
 *    recorded as ILLEGAL_TRANSITION and the segment marked SUSPECT;
 *  - transitions out of UNKNOWN are illegal by construction.
 */
class Replayer(
    private val edges: List<EdgeSample>,
    private val resets: List<ResetEvent>,
    private val polarity: SamplingEdge
) {
    fun run(): ReplayResult {
        val steps = mutableListOf<TapStep>()
        val segments = mutableListOf<ShiftSegment>()
        val events = mutableListOf<JudgeEvent>()
        var seq = 0
        var eventSeq = 0

        // Effective sample stream under the configured sampling polarity.
        // FALLING means the probe sampled half a cycle late: values shift by one clock.
        val count = if (polarity == SamplingEdge.FALLING) edges.size - 1 else edges.size
        fun tmsAt(i: Int) = if (polarity == SamplingEdge.FALLING) edges[i + 1].tms else edges[i].tms
        fun tdiAt(i: Int) = if (polarity == SamplingEdge.FALLING) edges[i + 1].tdi else edges[i].tdi
        fun tdoAt(i: Int) = if (polarity == SamplingEdge.FALLING) edges[i + 1].tdo else edges[i].tdo

        val trstByClock = resets.groupBy { it.clockIndex }
        var state = TapState.TEST_LOGIC_RESET
        var segRegister: String? = null
        var segStart = -1
        val segTdo = StringBuilder()
        val segTdi = StringBuilder()
        var tmsRun = 0

        fun closeSegment(exitClock: Int, status: String) {
            val reg = segRegister ?: return
            segments += ShiftSegment(
                id = newId("shift"), seq = segments.size, register = reg,
                startClock = segStart, bitsTdo = segTdo.toString(), bitsTdi = segTdi.toString(),
                status = status
            )
            segRegister = null; segStart = -1; segTdo.clear(); segTdi.clear()
        }

        var i = 0
        while (i < count) {
            val tms = tmsAt(i)
            tmsRun = if (tms == 1) tmsRun + 1 else 0
            val trst = trstByClock[i]?.firstOrNull()
            val implicitReset = tmsRun >= 5
            if (trst != null || implicitReset) {
                val midOp = state.isMidOperation
                val kind = if (trst != null) trst.kind else "TMS_FIVE_ONES"
                if (midOp) {
                    closeSegment(i, "INVALIDATED")
                    steps += TapStep(
                        newId("tap"), seq++, i, state, TapState.TEST_LOGIC_RESET, tms,
                        "MID_SCAN_RESET", "reset ($kind) at clock $i interrupted an in-progress scan"
                    )
                    events += JudgeEvent(
                        newId("evt"), eventSeq++, "MID_SCAN_RESET", "clock:$i", null,
                        "Reset ($kind) asserted mid-scan at clock $i; open shift segment invalidated " +
                            "and retained as evidence, not silently skipped.", "{}"
                    )
                } else {
                    steps += TapStep(newId("tap"), seq++, i, state, TapState.TEST_LOGIC_RESET, tms, "RESET", null)
                }
                state = TapState.TEST_LOGIC_RESET
                tmsRun = 0
                i++
                continue
            }

            val next = TapFsm.next(state, tms)
            if (next == null) {
                steps += TapStep(
                    newId("tap"), seq++, i, state, TapState.UNKNOWN, tms,
                    "ILLEGAL_TRANSITION", "no legal TAP transition from $state (tms=$tms)"
                )
                events += JudgeEvent(
                    newId("evt"), eventSeq++, "ILLEGAL_TRANSITION", "clock:$i", null,
                    "Illegal TAP transition from $state at clock $i; recorded, not skipped.", "{}"
                )
                i++
                continue
            }

            // Evidence check on entering SHIFT_IR: the IR capture pattern ends in
            // ..01, so the first two bits shifted out must be 1 then 0.
            var status = "OK"
            var anomaly: String? = null
            if (state == TapState.CAPTURE_IR && next == TapState.SHIFT_IR) {
                val b0 = tdoAt(i)
                val b1 = if (i + 1 < count) tdoAt(i + 1) else -1
                if (!(b0 == 1 && b1 == 0)) {
                    status = "ILLEGAL_TRANSITION"
                    anomaly = "TDO capture pattern $b0$b1 contradicts mandatory IR capture ..01"
                    events += JudgeEvent(
                        newId("evt"), eventSeq++, "ILLEGAL_TRANSITION", "clock:$i", null,
                        "TDO evidence ($b0$b1) contradicts the mandatory IR capture pattern (10) at " +
                            "clock $i; transition into SHIFT_IR flagged illegal and kept, not skipped.", "{}"
                    )
                }
            }
            steps += TapStep(newId("tap"), seq++, i, state, next, tms, status, anomaly)

            if (next == TapState.SHIFT_DR || next == TapState.SHIFT_IR) {
                if (segRegister == null) {
                    segRegister = if (next == TapState.SHIFT_DR) "DR" else "IR"
                    segStart = i
                }
                segTdo.append(tdoAt(i)); segTdi.append(tdiAt(i))
            } else if (segRegister != null) {
                val suspect = steps.lastOrNull { it.clockIndex == segStart }?.status == "ILLEGAL_TRANSITION"
                closeSegment(i, if (suspect) "SUSPECT" else "VALID")
            }
            state = next
            i++
        }
        closeSegment(if (count > 0) count - 1 else 0, "VALID")
        return ReplayResult(steps, segments, events)
    }
}
