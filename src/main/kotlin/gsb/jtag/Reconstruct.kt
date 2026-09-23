package gsb.jtag

/** Derivation engine. Every rule that fires emits a machine-readable DeriveEvent;
 *  illegal transitions and mid-shift resets are never silently skipped, and
 *  ambiguous results keep all legal candidates instead of collapsing to one. */
object Reconstruct {

    fun buildTrace(edges: List<SampleEdge>, resets: List<ResetEvent>, polarity: Polarity): TapTrace {
        val events = mutableListOf<DeriveEvent>()
        val segments = mutableListOf<TraceSegment>()
        val resetAt = resets.associateBy { it.edgeOrd }
        var state = TapState.TLR
        var segStart = 0
        var segStates = mutableListOf<TapState>()
        var segIndex = 0
        var mismatches = 0
        var decodedTotal = 0
        var evCounter = 0
        fun evId() = "EV-T%03d".format(++evCounter)

        fun closeSegment(endOrd: Int, endedBy: String?) {
            segments += TraceSegment(segIndex++, segStart, endOrd, segStates.toList(), endedBy)
            segStates = mutableListOf()
        }

        for (e in edges.sortedBy { it.ord }) {
            val reset = resetAt[e.ord]
            if (reset != null) {
                if (state.isShift) {
                    events += DeriveEvent(
                        evId(), EventKind.MID_SHIFT_RESET,
                        "reset ${reset.kind} at edge#${e.ord} interrupts ${state.label}; segment split, partial shift kept",
                        listOf(reset.id, e.id), Decision.ACCEPTED,
                        "mid-shift reset must surface, never be silently skipped"
                    )
                }
                closeSegment(e.ord - 1, reset.id)
                state = TapState.TLR
                segStart = e.ord
                // the reset edge itself still clocks the FSM from TLR
            }
            state = state.next(e.tms)
            segStates += state
            val claim = e.decodedState?.let { TapState.byLabel(it) }
            if (e.decodedState != null) {
                decodedTotal++
                if (claim == null || claim != state) {
                    mismatches++
                    events += DeriveEvent(
                        evId(), EventKind.ILLEGAL_TRANSITION,
                        "decoder claims '${e.decodedState}' after edge#${e.ord} but FSM transition yields ${state.label}; both continuations retained",
                        listOf(e.id), Decision.PENDING,
                        "illegal TAP transition must not be silently skipped"
                    )
                }
            }
        }
        closeSegment(edges.maxOfOrNull { it.ord } ?: -1, null)
        if (decodedTotal > 0 && mismatches * 2 > decodedTotal) {
            events += DeriveEvent(
                evId(), EventKind.POLARITY_SUSPECT,
                "$mismatches/$decodedTotal decoder claims contradict FSM; sampling polarity (${polarity.id}) likely wrong",
                edges.take(1).map { it.id }, Decision.PENDING,
                "systematic mismatch indicates polarity misconfiguration, not random illegal transitions"
            )
        }
        return TapTrace(segments, events)
    }

    fun extractShifts(trace: TapTrace, edges: List<SampleEdge>, polarity: Polarity): List<ShiftStream> {
        val byOrd = edges.associateBy { it.ord }
        val out = mutableListOf<ShiftStream>()
        var n = 0
        for (seg in trace.segments) {
            var i = 0
            while (i < seg.states.size) {
                val st = seg.states[i]
                if (st.isShift) {
                    var j = i
                    while (j < seg.states.size && seg.states[j] == st) j++
                    val ords = (seg.startOrd + i) until (seg.startOrd + j)
                    val tdi = ords.map { byOrd[it]?.tdi ?: 0 }
                    // TDO leaves the chain one half-cycle after TDI enters; align by shifting one edge.
                    val tdo = ords.mapNotNull { byOrd[it + 1]?.tdo?.let(polarity::tdo) }
                    out += ShiftStream(
                        "SH%03d".format(++n),
                        if (st == TapState.SIR) RegKind.IR else RegKind.DR,
                        seg.index, seg.startOrd + i, tdi, tdo
                    )
                    i = j
                } else i++
            }
        }
        return out
    }

    /** Parse a post-reset DR scan: bypass-only devices contribute a single 0,
     *  devices with IDCODE contribute 32 bits starting with a 1 (LSB first). */
    fun parseIdcodeScan(tdo: List<Int>): Pair<List<String?>, Int> {
        val ids = mutableListOf<String?>()
        var i = 0
        while (i < tdo.size) {
            if (tdo[i] == 0) { ids += null; i += 1 }
            else {
                if (i + 32 > tdo.size) break
                val v = tdo.subList(i, i + 32).reversed().joinToString("") { it.toString() }
                ids += v.toLong(2).toString(16).padStart(8, '0')
                i += 32
            }
        }
        return ids to i
    }

    fun reconstructChain(
        shifts: List<ShiftStream>,
        irCandidates: List<IrLengthCandidate>,
        bsdlMaps: List<BsdlMap>,
        board: BoardRevision
    ): ChainModel {
        val events = mutableListOf<DeriveEvent>()
        var evCounter = 0
        fun evId() = "EV-C%03d".format(++evCounter)

        val drShifts = shifts.filter { it.kind == RegKind.DR }
        val irShifts = shifts.filter { it.kind == RegKind.IR }
        val bypassCount = drShifts.firstOrNull()?.let { s -> s.tdo.count { it == 0 }.takeIf { _ -> s.tdo.all { it == 0 } } } ?: 0

        val idScan = drShifts.firstOrNull { s -> s.tdo.any { it == 1 } }
        val (idcodes, _) = idScan?.let { parseIdcodeScan(it.tdo) } ?: (emptyList<String?>() to 0)

        // devices closest to TDO appear first in the TDO stream; reverse to TDI-first order
        val presentOrdered: List<String?> = idcodes.reversed()
        val present = presentOrdered.size

        val byIdcode = bsdlMaps.associateBy { it.idcode?.lowercase() }
        val matchedNames = presentOrdered.map { id -> id?.let { byIdcode[it.lowercase()]?.device } }

        // IR length: measured total vs. candidates. Never guessed from IDCODE alone.
        val measuredIr = irShifts.firstOrNull()?.tdi?.size
        val lengths = IntArray(present) { -1 }
        if (measuredIr != null && present > 0) {
            val perPos = (0 until present).map { pos ->
                irCandidates.filter { it.devicePos == pos }.map { it.length }.distinct()
            }
            val partitions = mutableListOf<List<Int>>()
            fun walk(pos: Int, acc: List<Int>) {
                if (pos == present) { if (acc.sum() == measuredIr) partitions += acc; return }
                for (l in perPos[pos]) walk(pos + 1, acc + l)
            }
            if (perPos.all { it.isNotEmpty() }) walk(0, emptyList())
            when {
                partitions.size == 1 -> partitions[0].forEachIndexed { i, l -> lengths[i] = l }
                partitions.size > 1 -> events += DeriveEvent(
                    evId(), EventKind.IR_LENGTH_AMBIGUOUS,
                    "measured IR total $measuredIr admits ${partitions.size} partitions: " +
                        partitions.joinToString(" | ") { it.joinToString("+") },
                    irCandidates.map { it.id }, Decision.PENDING,
                    "IR length must not be guessed from IDCODE alone; all legal partitions retained"
                )
                else -> events += DeriveEvent(
                    evId(), EventKind.IR_LENGTH_AMBIGUOUS,
                    "no candidate partition sums to measured IR total $measuredIr; lengths left unresolved",
                    irCandidates.map { it.id }, Decision.PENDING,
                    "unresolved IR length stays unresolved rather than being invented"
                )
            }
        } else if (present > 0) {
            events += DeriveEvent(
                evId(), EventKind.IR_LENGTH_AMBIGUOUS,
                "no IR shift measured; IDCODE presence does not determine IR length",
                emptyList(), Decision.PENDING,
                "IR length must not be guessed from IDCODE alone"
            )
        }

        val missing = board.expectedDevices.filter { exp -> matchedNames.none { it == exp } }
        val baseDevices = (0 until present).map { pos ->
            DevicePlacement(
                pos, matchedNames[pos], presentOrdered[pos],
                lengths.getOrNull(pos)?.takeIf { it > 0 },
                bypassObserved = presentOrdered[pos] == null
            )
        }

        val candidates = mutableListOf<ChainCandidate>()
        var candCounter = 0
        fun candId() = "CC%02d".format(++candCounter)

        if (missing.isNotEmpty()) {
            // keep every legal insertion slot for each missing device -> offset candidates
            val slots = 0..present
            for (slot in slots) {
                val devs = baseDevices.toMutableList()
                missing.forEachIndexed { k, m ->
                    devs.add(minOf(slot + k, devs.size),
                        DevicePlacement(slot + k, m, null, null, bypassObserved = false))
                }
                val renum = devs.mapIndexed { i, d -> d.copy(pos = i) }
                candidates += ChainCandidate(
                    candId(), renum, cellOffsets(renum, bsdlMaps),
                    "missing ${missing.joinToString()} assumed at slot $slot"
                )
            }
            events += DeriveEvent(
                evId(), EventKind.DEVICE_MISSING,
                "board ${board.rev} expects ${board.expectedDevices} but $present device(s) answered; " +
                    "${slots.size} offset candidates retained",
                listOf(board.id), Decision.PENDING,
                "missing chain device: retain offset candidates, do not collapse"
            )
        } else {
            candidates += ChainCandidate(candId(), baseDevices, cellOffsets(baseDevices, bsdlMaps), "unique placement")
        }
        return ChainModel(candidates, events)
    }

    /** boundary-scan cell offset of each device counted from the TDO end. */
    private fun cellOffsets(devices: List<DevicePlacement>, bsdlMaps: List<BsdlMap>): Map<String, Int> {
        val cellsByDevice = bsdlMaps.associate { it.device to it.cells.size }
        val out = mutableMapOf<String, Int>()
        var offset = 0
        for (d in devices.sortedByDescending { it.pos }) { // TDO end first
            val name = d.name ?: continue
            out[name] = offset
            offset += cellsByDevice[name] ?: 0
        }
        return out
    }

    fun cellPinEvidence(chain: ChainModel, bsdlMaps: List<BsdlMap>): Pair<List<CellPinEvidence>, List<DeriveEvent>> {
        val events = mutableListOf<DeriveEvent>()
        val out = mutableListOf<CellPinEvidence>()
        var n = 0
        val cand = chain.primary
        for (d in cand.devices) {
            val name = d.name ?: continue
            val maps = bsdlMaps.filter { it.device == name }
            val map = maps.maxByOrNull { it.bsdlVersion } ?: continue
            val base = cand.cellOffset[name] ?: 0
            for (c in map.cells) {
                out += CellPinEvidence(
                    "CP%04d".format(++n), name, c.index, c.port,
                    map.pins[c.port], c.function,
                    base + (map.cells.size - 1 - c.index),
                    map.bsdlVersion, map.id
                )
            }
            // version comparison: same device mapped by more than one BSDL version
            if (maps.size > 1) {
                val sorted = maps.sortedBy { it.bsdlVersion }
                for (k in 1 until sorted.size) {
                    val a = sorted[k - 1]; val b = sorted[k]
                    val diff = (a.pins.keys + b.pins.keys).filter { a.pins[it] != b.pins[it] }
                    val cellDiff = (a.cells.map { it.port } + b.cells.map { it.port }).toSet()
                        .count { p -> a.cells.find { it.port == p }?.function != b.cells.find { it.port == p }?.function }
                    if (diff.isNotEmpty() || cellDiff > 0) {
                        events += DeriveEvent(
                            "EV-M%03d".format(events.size + 1), EventKind.MAPPING_VERSION_CHANGED,
                            "BSDL ${a.bsdlVersion} -> ${b.bsdlVersion} for $name changes pins ${diff.joinToString()} and $cellDiff cell function(s)",
                            listOf(a.id, b.id), Decision.PENDING,
                            "mapping version change: evidence re-derived, both versions addressable"
                        )
                    }
                }
            }
        }
        return out to events
    }
}
