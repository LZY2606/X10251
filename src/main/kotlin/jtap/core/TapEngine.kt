package jtap.core

object TapEngine {
    fun replay(inputs: CaseInputs): TapReplay {
        val activeEdges = inputs.edges.filter { it.edge == inputs.polarity.normalized?.substringBefore(' ') }
        val transitions = mutableListOf<TapTransition>()
        val streams = mutableListOf<ShiftStream>()
        val diagnostics = inputs.diagnostics.toMutableList()
        var state: TapState? = null
        var register = ""
        var segmentIndex = 0
        var bits = mutableListOf<ShiftBit>()
        var segmentStart: String? = null
        var segmentReset = false

        fun closeStream(endEdgeId: String) {
            if (bits.isNotEmpty()) {
                streams += ShiftStream(
                    streamId = "STREAM:$register:$segmentIndex",
                    register = register,
                    segmentIndex = segmentIndex,
                    startEdgeId = segmentStart ?: bits.first().edgeId,
                    endEdgeId = endEdgeId,
                    bits = bits.toList(),
                    rawTdi = bits.joinToString("") { it.tdi.toString() },
                    rawTdo = bits.joinToString("") { it.tdo.toString() },
                    interruptedByReset = segmentReset
                )
            }
            bits = mutableListOf()
            segmentStart = null
            segmentReset = false
        }

        fun nextState(current: TapState?, tms: Int, resetEvent: ResetEvent?): Pair<TapState, Boolean> {
            if (resetEvent != null) return TapState.TEST_LOGIC_RESET to true
            return when (current) {
                null -> if (tms == 1) TapState.TEST_LOGIC_RESET else TapState.INVALID to false
                TapState.TEST_LOGIC_RESET -> if (tms == 0) TapState.RUN_TEST_IDLE else TapState.TEST_LOGIC_RESET
                TapState.RUN_TEST_IDLE -> if (tms == 1) TapState.SELECT_DR_SCAN else TapState.RUN_TEST_IDLE
                TapState.SELECT_DR_SCAN -> if (tms == 1) TapState.SELECT_IR_SCAN else TapState.CAPTURE_DR
                TapState.CAPTURE_DR -> if (tms == 1) TapState.EXIT1_DR else TapState.SHIFT_DR
                TapState.SHIFT_DR -> if (tms == 1) TapState.EXIT1_DR else TapState.SHIFT_DR
                TapState.EXIT1_DR -> if (tms == 1) TapState.UPDATE_DR else TapState.PAUSE_DR
                TapState.PAUSE_DR -> if (tms == 1) TapState.EXIT2_DR else TapState.PAUSE_DR
                TapState.EXIT2_DR -> if (tms == 1) TapState.UPDATE_DR else TapState.SHIFT_DR
                TapState.UPDATE_DR -> if (tms == 1) TapState.SELECT_DR_SCAN else TapState.RUN_TEST_IDLE
                TapState.SELECT_IR_SCAN -> if (tms == 1) TapState.TEST_LOGIC_RESET else TapState.CAPTURE_IR
                TapState.CAPTURE_IR -> if (tms == 1) TapState.EXIT1_IR else TapState.SHIFT_IR
                TapState.SHIFT_IR -> if (tms == 1) TapState.EXIT1_IR else TapState.SHIFT_IR
                TapState.EXIT1_IR -> if (tms == 1) TapState.UPDATE_IR else TapState.PAUSE_IR
                TapState.PAUSE_IR -> if (tms == 1) TapState.EXIT2_IR else TapState.PAUSE_IR
                TapState.EXIT2_IR -> if (tms == 1) TapState.UPDATE_IR else TapState.SHIFT_IR
                TapState.UPDATE_IR -> if (tms == 1) TapState.SELECT_DR_SCAN else TapState.RUN_TEST_IDLE
                TapState.INVALID -> TapState.INVALID
            }
        }

        activeEdges.forEach { edge ->
            val reset = inputs.importedResets.firstOrNull { it.edgeId == edge.edgeId }
            val shifting = state == TapState.SHIFT_IR || state == TapState.SHIFT_DR
            if (shifting && reset == null) {
                val currentRegister = if (state == TapState.SHIFT_IR) "IR" else "DR"
                if (register.isNotBlank() && register != currentRegister) {
                    closeStream(edge.edgeId)
                    segmentIndex++
                }
                register = currentRegister
                if (segmentStart == null) segmentStart = edge.edgeId
                bits += ShiftBit(
                    bitId = "BIT:${register}:${bits.size}",
                    edgeId = edge.edgeId,
                    position = bits.size,
                    register = register,
                    tdi = edge.tdi ?: 0,
                    tdo = edge.tdo ?: 0
                )
            }
            val tms = edge.tms
            val illegal = tms == null || (state == null && tms == 0) || state == TapState.INVALID
            val target = if (!illegal) nextState(state, tms!!, reset) else TapState.INVALID to (reset != null)
            val reason = when {
                reset != null && (state in listOf(TapState.SHIFT_IR, TapState.SHIFT_DR, TapState.CAPTURE_IR, TapState.CAPTURE_DR, TapState.PAUSE_IR, TapState.PAUSE_DR) || reset.midScan) ->
                    "MID_SCAN_RESET_RECORDED"
                reset != null -> "RESET_RECORDED"
                tms == null -> "ILLEGAL_TAP_TRANSITION_NONBINARY_TMS"
                state == null && tms == 0 -> "ILLEGAL_TAP_TRANSITION_WITHOUT_RESET"
                state == TapState.INVALID -> "INVALID_STATE_PERSISTS_UNTIL_RESET"
                else -> "LEGAL_TAP_TRANSITION"
            }
            transitions += TapTransition(
                edgeId = edge.edgeId,
                index = transitions.size,
                from = state,
                to = target.first,
                tms = tms,
                legal = !illegal && reason.startsWith("LEGAL") || reason == "RESET_RECORDED" || reason == "MID_SCAN_RESET_RECORDED",
                resetEventId = reset?.eventId,
                reason = reason
            )
            when (reason) {
                "MID_SCAN_RESET_RECORDED" -> {
                    if (bits.isNotEmpty()) segmentReset = true
                    closeStream(edge.edgeId)
                    segmentIndex++
                    diagnostics += diag("MID_SCAN_RESET", "error", reason, edge.edgeId, reset?.eventId)
                }
                "RESET_RECORDED" -> diagnostics += diag("RESET", "info", reason, edge.edgeId, reset?.eventId)
                "ILLEGAL_TAP_TRANSITION_NONBINARY_TMS", "ILLEGAL_TAP_TRANSITION_WITHOUT_RESET" -> {
                    closeStream(edge.edgeId)
                    segmentIndex++
                    diagnostics += diag("ILLEGAL_TAP_TRANSITION", "error", reason, edge.edgeId, null)
                }
                "INVALID_STATE_PERSISTS_UNTIL_RESET" -> diagnostics += diag("INVALID_TAP_STATE", "error", reason, edge.edgeId, null)
            }
            state = target.first
        }
        activeEdges.lastOrNull()?.let { closeStream(it.edgeId) }
        return TapReplay(inputs.polarity, inputs.edges, inputs.importedResets, transitions, streams, diagnostics)
    }

    private fun diag(code: String, severity: String, message: String, edgeId: String, sourceId: String?) =
        Diagnostic("DIAG:${code}:$edgeId", severity, code, message, sourceId, edgeId)
}
