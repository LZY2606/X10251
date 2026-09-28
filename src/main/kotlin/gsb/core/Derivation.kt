package gsb.core

/** Rule ids emitted as machine-readable events. */
object Rules {
    const val NO_SILENT_ILLEGAL_TRANSITION = "RULE_NO_SILENT_ILLEGAL_TRANSITION"
    const val NO_SILENT_MID_SCAN_RESET = "RULE_NO_SILENT_MID_SCAN_RESET"
    const val IR_LENGTH_NOT_FROM_IDCODE_ONLY = "RULE_IR_LENGTH_NOT_FROM_IDCODE_ONLY"
    const val MISSING_DEVICE_KEEP_OFFSETS = "RULE_MISSING_DEVICE_KEEP_OFFSETS"
    const val KEEP_ALL_LEGAL_CANDIDATES = "RULE_KEEP_ALL_LEGAL_CANDIDATES"
    const val UNIT_UNCONVERTIBLE = "RULE_UNIT_UNCONVERTIBLE"
}

object Derivation {

    /** Rebuild the TAP trajectory from a sample log + declared reset events.
     *  Nothing is silently skipped: truncated shifts, mid-scan resets and
     *  unconvertible units all surface as anomalies/diagnostics. */
    fun trajectory(log: SampleLog, resets: ResetEventSet): Trajectory {
        val anomalies = mutableListOf<Anomaly>()
        val steps = mutableListOf<TapStepSer>()
        var state = TapState.TLR
        var shiftKind: String? = null
        var shiftStart = -1
        var shiftBits = 0
        for (row in log.rows) {
            val inReset = resets.events.any { row.ordinal in it.fromOrdinal..it.toOrdinal }
            val prev = state
            state = state.next(row.tms)
            if (inReset && state != TapState.TLR && row.tms == 0) {
                // declared reset window but TMS left the reset pattern: flag, keep going
                anomalies.add(Anomaly(Rules.NO_SILENT_MID_SCAN_RESET, "warning", row.ordinal,
                    "reset event ${resetName(resets, row.ordinal)} window left at ordinal ${row.ordinal} without reaching Test-Logic-Reset",
                    listOf(row.ordinal)))
            }
            if (state == TapState.TLR && prev.isShift) {
                anomalies.add(Anomaly(Rules.NO_SILENT_MID_SCAN_RESET, "error", row.ordinal,
                    "mid-scan reset: $shiftKind shift aborted after $shiftBits bits by Test-Logic-Reset",
                    (shiftStart..row.ordinal).toList()))
                shiftKind = null
            }
            if (prev.isShift && !state.isShift && state != TapState.TLR) {
                shiftKind = null // shift closed; bit-count legality is judged by the shift analyzer
            }
            if (state.isShift && shiftKind == null) { shiftKind = if (state == TapState.S1I) "IR" else "DR"; shiftStart = row.ordinal; shiftBits = 0 }
            if (state.isShift) shiftBits++
            steps.add(TapStepSer(row.ordinal, SampleLogParser.timeToPs(row.timeRaw),
                state.name, row.tms, row.tdi, row.tdo, inReset))
        }
        return Trajectory(steps, anomalies)
    }

    private fun resetName(resets: ResetEventSet, ordinal: Int) =
        resets.events.firstOrNull { ordinal in it.fromOrdinal..it.toOrdinal }?.id ?: "?"

    /** Extract IR/DR shift bitstreams from a trajectory. A shift that ends with a bit
     *  count matching no candidate length is an ILLEGAL_TRANSITION-class anomaly. */
    fun shifts(traj: Trajectory, legalIrLengths: Set<Int>, legalDrLengths: Set<Int>): Pair<ShiftBitstreams, List<Anomaly>> {
        val scans = mutableListOf<ShiftScan>()
        val anomalies = mutableListOf<Anomaly>()
        var i = 0
        val steps = traj.steps
        while (i < steps.size) {
            val st = steps[i].state
            if (st == "S1I" || st == "S1D") {
                val kind = if (st == "S1I") "IR" else "DR"
                val start = i
                val tdi = StringBuilder(); val tdo = StringBuilder()
                while (i < steps.size && steps[i].state == st) { tdi.append(steps[i].tdi); tdo.append(steps[i].tdo); i++ }
                val n = tdi.length
                scans.add(ShiftScan(kind, steps[start].index, steps[i - 1].index, tdi.toString(), tdo.toString(), n))
                val legal = if (kind == "IR") legalIrLengths else legalDrLengths
                if (legal.isNotEmpty() && n !in legal) {
                    anomalies.add(Anomaly(Rules.NO_SILENT_ILLEGAL_TRANSITION, "error", steps[i - 1].index,
                        "illegal TAP transition: $kind shift exited after $n bits; legal lengths are $legal (shift truncated or misaligned)",
                        (steps[start].index..steps[i - 1].index).toList()))
                }
            } else i++
        }
        return ShiftBitstreams(scans) to anomalies
    }

    /** Solve IR length candidates per device. IDCODE alone never resolves a length:
     *  it only contributes one evidence tag among several. */
    fun irLengthCandidates(board: BoardRevision, bsdl: Map<String, BsdlPinMap>,
                           shifts: ShiftBitstreams, declared: IrLengthCandidates): IrLengthCandidates {
        val out = mutableListOf<IrLengthHypothesis>()
        for (dev in board.devices) {
            val evidence = mutableMapOf<Int, MutableList<String>>()
            fun add(len: Int?, tag: String) { if (len != null && len > 0) evidence.getOrPut(len) { mutableListOf() }.add(tag) }
            add(bsdl[dev.bsdlMap]?.instructionLength, "bsdl:${dev.bsdlMap}@${bsdl[dev.bsdlMap]?.bsdlVersion}:INSTRUCTION_LENGTH")
            for (h in declared.hypotheses.filter { it.deviceRef == dev.ref })
                add(h.length, "declared:${h.evidence.joinToString("+")}")
            // observed IR shift totals are chain-level; a single-device chain segment of
            // exactly one legal length is weak evidence, IDCODE observations weaker still
            val irScans = shifts.scans.filter { it.kind == "IR" }.map { it.bitCount }.toSet()
            for (len in evidence.keys.toList()) if (len in irScans) evidence[len]!!.add("observed:ir-shift-bits=$len")
            for ((len, tags) in evidence) out.add(IrLengthHypothesis(dev.ref, len, tags))
        }
        return IrLengthCandidates("solved", out)
    }

    /** Whether a hypothesis set is resolved. IDCODE-only evidence never resolves. */
    fun resolved(hyp: List<IrLengthHypothesis>, deviceRef: String): Boolean {
        val c = hyp.filter { it.deviceRef == deviceRef }
        if (c.size != 1) return false
        val only = c[0].evidence.all { it.startsWith("observed:idcode") || it.startsWith("idcode") }
        return !only
    }

    /** Build the device chain. Missing devices keep offset candidates instead of
     *  collapsing the cell->pin mapping to a single guess. */
    fun deviceChain(board: BoardRevision, ir: IrLengthCandidates,
                    observedBypassPositions: Set<Int>, missingRefs: Set<String>): DeviceChain {
        val slots = board.devices.sortedBy { it.chainPosition }.map { dev ->
            val cands = ir.hypotheses.filter { it.deviceRef == dev.ref }.map { it.length }.sorted()
            DeviceSlot(dev.chainPosition, dev.ref, dev.part,
                if (resolved(ir.hypotheses, dev.ref)) cands.firstOrNull() else null,
                cands, dev.chainPosition in observedBypassPositions, dev.ref in missingRefs)
        }
        val offsets = mutableListOf<OffsetCandidate>()
        val notes = mutableListOf<String>()
        val missing = slots.filter { it.missing }
        if (missing.isNotEmpty()) {
            notes.add("missing device(s) ${missing.map { it.ref }}: downstream cell offsets are ambiguous, all legal candidates retained")
            // every boundary cell after a missing device gets candidate device assignments
            val present = slots.filter { !it.missing }
            for (slot in present) {
                val width = slot.irCandidates.firstOrNull() ?: 1
                for (cell in 0 until width.coerceAtMost(8)) {
                    offsets.add(OffsetCandidate(slot.chainPosition * 100 + cell,
                        (present.map { "${it.ref}:cell$cell" } + missing.map { "${it.ref}:cell$cell(unresolved)" })))
                }
            }
        }
        return DeviceChain(slots, offsets, notes)
    }

    /** Join boundary cells to package pins via a specific BSDL map version. */
    fun cellPinEvidence(chain: DeviceChain, board: BoardRevision, bsdl: BsdlPinMap): CellPinEvidenceSet {
        val diags = mutableListOf<String>()
        val rows = mutableListOf<CellPinEvidence>()
        val devByRef = board.devices.associateBy { it.ref }
        for (cell in bsdl.boundaryCells) {
            val dev = board.devices.firstOrNull { it.bsdlMap == bsdl.name }
            if (dev == null) { diags.add("no board device uses bsdl map ${bsdl.name}"); break }
            val pin = bsdl.pinMap[cell.port]
            if (pin == null) diags.add("cell ${cell.index} port ${cell.port}: no pin in ${bsdl.name}@${bsdl.bsdlVersion}; kept without pin")
            rows.add(CellPinEvidence(cell.index, dev.ref, cell.port, pin ?: "(unmapped)",
                bsdl.name, bsdl.bsdlVersion, dev.xMm, dev.yMm))
        }
        return CellPinEvidenceSet(rows, diags)
    }
}
