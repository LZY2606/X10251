package gsb.domain

/** Machine-readable anomaly. Never silently dropped: every anomaly becomes an event. */
data class Anomaly(
    val id: String,
    val kind: String,
    val edgeId: String?,
    val shiftId: String?,
    val message: String,
    val rule: String?,
)

data class TraceStep(
    val edgeId: String,
    val index: Int,
    val tms: Boolean,
    val from: String?,
    val to: String?,
    val segment: String,
)

data class Shift(
    val id: String,
    val type: String, // IR | DR
    val tdiBits: List<Int>,
    val tdoBits: List<String>,
    val startEdgeId: String,
    val endEdgeId: String?,
    val aborted: Boolean,
    val abortReason: String?,
)

data class TraceResult(
    val steps: List<TraceStep>,
    val shifts: List<Shift>,
    val anomalies: List<Anomaly>,
    val diagnostics: List<String>,
)

/**
 * Rebuilds the TAP state trajectory from sampled edges.
 *
 * Semantics enforced here:
 *  - Illegal transitions (TMS unstable at the sampling edge) are NOT silently
 *    skipped: an anomaly is recorded, the trajectory becomes UNKNOWN and only
 *    re-anchors after a 5xTMS=1 reset or a TRST event.
 *  - Mid-scan resets (5 consecutive TMS=1 or a TRST event during a shift) are
 *    NOT silently skipped: the in-progress shift is aborted but preserved as
 *    evidence, and an anomaly is emitted.
 *  - Sampling on the wrong edge (polarity mismatch with dataChangesOn) makes
 *    TMS/TDI unstable and surfaces as POLARITY_SUSPECT.
 */
class Reconstructor(private val sampleEdge: String) {

    fun run(capture: SampleCapture, resets: List<ResetEvent>): TraceResult {
        val anomalies = mutableListOf<Anomaly>()
        val steps = mutableListOf<TraceStep>()
        val shifts = mutableListOf<Shift>()
        val diagnostics = capture.diagnostics.toMutableList()
        var anomalySeq = 0
        fun anomaly(kind: String, edgeId: String?, shiftId: String?, msg: String, rule: String? = null) {
            anomalies += Anomaly("an-${capture.sourceId}-${anomalySeq++}", kind, edgeId, shiftId, msg, rule)
        }

        val polarityMismatch = sampleEdge.equals(capture.dataChangesOn, ignoreCase = true)
        if (polarityMismatch) {
            anomaly(
                "POLARITY_SUSPECT", capture.edges.firstOrNull()?.id, null,
                "sampling edge $sampleEdge coincides with data-change edge ${capture.dataChangesOn}; TMS/TDI unstable",
                "sampling-polarity",
            )
        }

        // Align external reset events to edges by time.
        val resetEdgeIdx = mutableSetOf<Int>()
        for (r in resets.filter { it.type == "TRST_ASSERT" }) {
            if (r.atNs == null) {
                diagnostics.add("reset-event-unconvertible:${r.rawAt}")
                continue
            }
            val best = capture.edges.withIndex().filter { it.value.timeNs != null }
                .minByOrNull { kotlin.math.abs(it.value.timeNs!! - r.atNs) }
            if (best != null) resetEdgeIdx += best.index
        }

        var state: TapState? = TapState.TEST_LOGIC_RESET
        var tmsRun = 0
        var shiftType: String? = null
        var shiftStartEdge: String? = null
        val tdiBits = mutableListOf<Int>()
        val tdoBits = mutableListOf<String>()
        var unknown = false

        fun closeShift(edgeId: String?, aborted: Boolean, reason: String?) {
            val st = shiftType ?: return
            val id = "${capture.sourceId}#shift-${shifts.size}"
            shifts += Shift(id, st, tdiBits.toList(), tdoBits.toList(), shiftStartEdge!!, edgeId, aborted, reason)
            shiftType = null; shiftStartEdge = null; tdiBits.clear(); tdoBits.clear()
        }

        for (e in capture.edges) {
            val from = state
            // 1. signal integrity: TMS unstable at sampling edge -> illegal transition
            val unstable = !e.tmsStable || polarityMismatch
            if (unstable) {
                anomaly(
                    "ILLEGAL_TRANSITION", e.id, null,
                    "TMS not stable at $sampleEdge sampling edge (edge ${e.id}); transition cannot be trusted",
                    "illegal-transition-not-skippable",
                )
                closeShift(e.id, aborted = true, reason = "illegal-transition")
                if (!unknown) {
                    unknown = true
                }
                steps += TraceStep(e.id, e.index, e.tms, from?.name, null, e.segment)
                continue
            }

            // 2. external reset (TRST) aligned to this edge
            val trst = e.resetAsserted || e.index in resetEdgeIdx

            // 3. synchronous reset: 5 consecutive TMS=1
            tmsRun = if (e.tms) tmsRun + 1 else 0
            val syncReset = tmsRun >= 5

            if ((trst || syncReset) && shiftType != null) {
                val id = "${capture.sourceId}#shift-${shifts.size}"
                anomaly(
                    "MID_SCAN_RESET", e.id, id,
                    "reset (${if (trst) "TRST" else "TMS=1 x5"}) during $shiftType shift; partial bits preserved as evidence",
                    "mid-scan-reset-not-skippable",
                )
                closeShift(e.id, aborted = true, reason = if (trst) "trst" else "tms-reset")
            }
            if (trst) {
                anomaly("TRST_RESET", e.id, null, "TRST asserted at edge ${e.id}", null)
                if (unknown) {
                    anomaly("RESYNC", e.id, null, "trajectory re-anchored to TEST_LOGIC_RESET after TRST", null)
                    unknown = false
                }
                state = TapState.TEST_LOGIC_RESET
                steps += TraceStep(e.id, e.index, e.tms, from?.name, state!!.name, e.segment)
                continue
            }
            if (syncReset) {
                if (unknown) {
                    anomaly("RESYNC", e.id, null, "trajectory re-anchored to TEST_LOGIC_RESET after 5xTMS=1", null)
                    unknown = false
                }
                state = TapState.TEST_LOGIC_RESET
                steps += TraceStep(e.id, e.index, e.tms, from?.name, state!!.name, e.segment)
                continue
            }
            if (unknown) {
                steps += TraceStep(e.id, e.index, e.tms, from?.name, null, e.segment)
                continue
            }

            val cur = state!!
            // shift bookkeeping: bits are shifted on clocks spent in SHIFT_*
            if (cur.isShift) {
                tdiBits += if (e.tdi) 1 else 0
                tdoBits += e.tdo.toString()
            }
            val next = cur.next(e.tms)
            if (!cur.isShift && (next == TapState.SHIFT_IR || next == TapState.SHIFT_DR)) {
                shiftType = if (next == TapState.SHIFT_IR) "IR" else "DR"
                shiftStartEdge = e.id
            }
            if (cur.isShift && !next.isShift) {
                // leaving shift: this edge carried the last bit
                if (next == TapState.UPDATE_DR || next == TapState.UPDATE_IR ||
                    next == TapState.RUN_TEST_IDLE || next == TapState.SELECT_DR_SCAN
                ) {
                    // finalized below when fully out of the exit path; close now, shift is complete
                }
                closeShift(e.id, aborted = false, reason = null)
            }
            // TDO drive conflict outside shift states
            if (e.tdo != 'Z' && !cur.isShift && !(cur == TapState.CAPTURE_DR || cur == TapState.CAPTURE_IR)) {
                anomaly(
                    "TDO_DRIVE_CONFLICT", e.id, null,
                    "TDO driven ('${e.tdo}') while TAP in $cur (expected tri-state)", null,
                )
            }
            state = next
            steps += TraceStep(e.id, e.index, e.tms, from?.name, next.name, e.segment)
        }
        if (shiftType != null) closeShift(capture.edges.lastOrNull()?.id, aborted = true, reason = "capture-ended-mid-shift")
        return TraceResult(steps, shifts, anomalies, diagnostics)
    }
}
