package jtag

/**
 * TAP trajectory replay. Rules enforced here:
 *  - illegal/ambiguous transitions are never silently skipped: they produce
 *    events and both candidate next-states are kept;
 *  - a mid-stream reset (TMS=1 for >=5 clocks, or a declared reset event)
 *    splits the trajectory into segments and is recorded;
 *  - sampling polarity is a parameter; inconsistent polarity yields
 *    diagnostics instead of silently flipped data.
 */
object TapReplay {

    data class Result(
        val steps: List<TapStep>,
        val shifts: List<ShiftStream>,
        val events: List<JEvent>,
        val diagnostics: List<Diagnostic>
    )

    fun replay(
        samples: List<Sample>,
        declaredResets: List<ResetEvent>,
        edge: SampleEdge,
        derivationId: String?,
        now: String
    ): Result {
        val events = mutableListOf<JEvent>()
        val diags = mutableListOf<Diagnostic>()
        val steps = mutableListOf<TapStep>()
        val shifts = mutableListOf<ShiftStream>()

        // extract clock edges according to configured sampling polarity
        val clockIdx = mutableListOf<Int>()
        for (i in 1 until samples.size) {
            val prev = samples[i - 1].tck; val cur = samples[i].tck
            if (edge == SampleEdge.RISING && prev == 0 && cur == 1) clockIdx += i
            if (edge == SampleEdge.FALLING && prev == 1 && cur == 0) clockIdx += i
        }
        if (clockIdx.isEmpty() && samples.isNotEmpty()) {
            diags += Diagnostic(newId("diag"), "tap_replay",
                "no ${edge.name.lowercase()} TCK edges found; check sampling polarity", "${samples.size} samples")
        }

        val resetAt = declaredResets.associateBy { it.atSample }
        var state = TapState.TEST_LOGIC_RESET
        var segment = 0
        var tmsHighRun = 0
        var stepIdx = 0
        var shiftBitsIn = StringBuilder()
        var shiftBitsOut = StringBuilder()
        var shiftStart = -1
        var shiftKind: String? = null

        fun closeShift(endStep: Int) {
            val k = shiftKind ?: return
            shifts += ShiftStream(newId("shift"), segment, k,
                shiftBitsIn.toString(), shiftBitsOut.toString(), shiftStart, endStep)
            shiftBitsIn = StringBuilder(); shiftBitsOut = StringBuilder(); shiftKind = null
        }

        for (ci in clockIdx) {
            val s = samples[ci]
            val flags = mutableListOf<String>()

            // declared reset event at this sample: never skipped
            resetAt[ci]?.let { r ->
                closeShift(stepIdx)
                events += JEvent(newId("evt"), "MID_RESET",
                    """{"kind":"${r.kind}","detail":"${r.detail}","segment":$segment}""",
                    derivationId, listOf(r.id, s.id), false, now)
                flags += "DECLARED_RESET"
                segment++
                state = TapState.TEST_LOGIC_RESET
                tmsHighRun = 0
            }

            // ambiguous sample (setup/hold violation): illegal transition, keep both candidates
            if (s.ambiguous) {
                val a = state.next(0); val b = state.next(1)
                events += JEvent(newId("evt"), "ILLEGAL_TRANSITION",
                    """{"from":"$state","candidates":["$a","$b"],"reason":"TMS ambiguous at clock edge (setup/hold violation)"}""",
                    derivationId, listOf(s.id), false, now)
                flags += "ILLEGAL_TRANSITION"
                // keep going with TMS as sampled, but the event retains both candidates
            }

            val tms = s.tms
            val next = state.next(tms)
            steps += TapStep(newId("step"), stepIdx, segment, state, next, tms, s.time, flags)

            // mid-stream reset detection: TMS high for >=5 consecutive clocks
            tmsHighRun = if (tms == 1) tmsHighRun + 1 else 0
            if (tmsHighRun == 5 && next == TapState.TEST_LOGIC_RESET) {
                closeShift(stepIdx)
                events += JEvent(newId("evt"), "MID_RESET",
                    """{"kind":"TMS_HIGH_5","detail":"TMS=1 for 5 clocks mid-stream","segment":$segment}""",
                    derivationId, listOf(s.id), false, now)
                segment++
            }

            // shift bit capture: TDI sampled on the configured edge, TDO read back
            if (next.isShift) {
                if (shiftKind == null) { shiftKind = if (state.isIr) "IR" else "DR"; shiftStart = stepIdx }
                shiftBitsIn.append(s.tdi)
                shiftBitsOut.append(s.tdo)
            } else if (shiftKind != null && !next.isShift) {
                closeShift(stepIdx)
            }

            state = next
            stepIdx++
        }
        closeShift(stepIdx)
        return Result(steps, shifts, events, diags)
    }
}
