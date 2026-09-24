package jtag.tap

/**
 * Rebuilds the TAP state trajectory from sampled edges + reset events.
 *
 * Semantics enforced here:
 *  - Illegal TAP transitions (glitched/invalid TCK edges) are recorded as
 *    ILLEGAL_TRANSITION events and drive the state to UNKNOWN; they are never
 *    silently skipped.
 *  - Resets landing in the middle of a shift segment are recorded as
 *    MID_SHIFT_RESET and abort the segment; never silently skipped.
 */
object TraceReconstructor {

    fun reconstruct(sampling: SamplingSet, resets: ResetSet): TraceResult {
        val resetByEdge = resets.events.associateBy { it.edgeIndex }
        val steps = mutableListOf<TapStep>()
        val events = mutableListOf<TapEvent>()
        val segments = mutableListOf<ShiftSegment>()

        var state: TapState? = TapState.TEST_LOGIC_RESET
        var consecutiveTms1 = 0

        // active shift segment accumulation
        var segReg: String? = null
        var segStart = 0
        val segTdi = mutableListOf<Int>()
        val segTdo = mutableListOf<Int>()

        fun closeSegment(endEdge: Int, aborted: Boolean, reason: String?) {
            val reg = segReg ?: return
            segments += ShiftSegment(
                id = "seg-${segments.size}",
                register = reg,
                startEdge = segStart,
                endEdge = endEdge,
                tdiBits = segTdi.toList(),
                tdoBits = segTdo.toList(),
                aborted = aborted,
                abortedReason = reason
            )
            segReg = null
            segTdi.clear()
            segTdo.clear()
        }

        for (edge in sampling.edges) {
            val reset = resetByEdge[edge.edgeIndex]
            var illegal = false

            if (reset != null) {
                val inShift = state == TapState.SHIFT_DR || state == TapState.SHIFT_IR
                if (inShift || segReg != null) {
                    events += TapEvent(
                        type = "MID_SHIFT_RESET",
                        edgeIndex = edge.edgeIndex,
                        severity = "ERROR",
                        detail = "${reset.kind} reset landed during active ${segReg ?: "shift"} segment; segment aborted, not skipped",
                        attrs = mapOf("resetKind" to reset.kind.name, "state" to (state?.name ?: "UNKNOWN"))
                    )
                    closeSegment(edge.edgeIndex, aborted = true, reason = "MID_SHIFT_RESET:${reset.kind}")
                } else {
                    events += TapEvent(
                        type = "RESET",
                        edgeIndex = edge.edgeIndex,
                        severity = "INFO",
                        detail = "${reset.kind} reset at edge ${edge.edgeIndex}"
                    )
                }
                state = TapState.TEST_LOGIC_RESET
                consecutiveTms1 = 0
                steps += TapStep(edge.edgeIndex, edge.tms, edge.tdi, edge.tdo,
                    stateBefore = "RESET", stateAfter = state.name, resetHere = true)
                continue
            }

            if (!edge.tckValid) {
                illegal = true
                events += TapEvent(
                    type = "ILLEGAL_TRANSITION",
                    edgeIndex = edge.edgeIndex,
                    severity = "ERROR",
                    detail = "TCK edge ${edge.edgeIndex} is glitched/invalid; TAP transition cannot be derived, state forced to UNKNOWN",
                    attrs = mapOf("stateBefore" to (state?.name ?: "UNKNOWN"))
                )
                if (segReg != null) {
                    closeSegment(edge.edgeIndex, aborted = true, reason = "ILLEGAL_TRANSITION")
                }
                state = null
                consecutiveTms1 = 0
                steps += TapStep(edge.edgeIndex, edge.tms, edge.tdi, edge.tdo,
                    stateBefore = "UNKNOWN", stateAfter = "UNKNOWN", illegal = true)
                continue
            }

            // resync from UNKNOWN: 5 consecutive TMS=1 guarantee TEST_LOGIC_RESET
            if (state == null) {
                consecutiveTms1 = if (edge.tms == 1) consecutiveTms1 + 1 else 0
                if (consecutiveTms1 >= 5) {
                    state = TapState.TEST_LOGIC_RESET
                    events += TapEvent(
                        type = "RESYNC",
                        edgeIndex = edge.edgeIndex,
                        severity = "WARN",
                        detail = "Resynchronized to TEST_LOGIC_RESET after 5 consecutive TMS=1"
                    )
                }
                steps += TapStep(edge.edgeIndex, edge.tms, edge.tdi, edge.tdo,
                    stateBefore = "UNKNOWN", stateAfter = state?.name ?: "UNKNOWN")
                continue
            }

            val next = state!!.next(edge.tms)
            if (next == null) {
                illegal = true
                events += TapEvent(
                    type = "ILLEGAL_TRANSITION",
                    edgeIndex = edge.edgeIndex,
                    severity = "ERROR",
                    detail = "No legal FSM arc from ${state!!.name} with TMS=${edge.tms}",
                    attrs = mapOf("stateBefore" to state!!.name, "tms" to edge.tms.toString())
                )
                if (segReg != null) closeSegment(edge.edgeIndex, aborted = true, reason = "ILLEGAL_TRANSITION")
                state = null
                steps += TapStep(edge.edgeIndex, edge.tms, edge.tdi, edge.tdo,
                    stateBefore = "UNKNOWN", stateAfter = "UNKNOWN", illegal = true)
                continue
            }

            val before = state!!
            state = next

            // shift segment tracking
            val inShiftNow = next == TapState.SHIFT_DR || next == TapState.SHIFT_IR
            if (inShiftNow) {
                val reg = if (next == TapState.SHIFT_IR) "IR" else "DR"
                if (segReg == null) {
                    segReg = reg
                    segStart = edge.edgeIndex
                }
                segTdi += edge.tdi
                segTdo += edge.tdo
            } else if (segReg != null) {
                closeSegment(edge.edgeIndex, aborted = false, reason = null)
            }

            steps += TapStep(edge.edgeIndex, edge.tms, edge.tdi, edge.tdo,
                stateBefore = before.name, stateAfter = next.name)
        }

        if (segReg != null) {
            closeSegment(sampling.edges.lastOrNull()?.edgeIndex ?: 0, aborted = true, reason = "END_OF_TRACE")
        }

        return TraceResult(steps, segments, events)
    }
}
