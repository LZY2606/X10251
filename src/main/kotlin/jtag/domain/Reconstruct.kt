package jtag.domain

/** Reconstruction engine: turns separately-stored sources into derived views.
 *  Hard rules enforced here:
 *   - illegal TAP transitions and mid-scan resets are never silently skipped;
 *   - IR length is never fixed from IDCODE alone;
 *   - when a chain device is missing, offset candidates are preserved. */
object Reconstruct {

    data class TraceResult(val trace: TapTrace, val events: List<JudgmentEvent>)

    fun reconstructTapTrace(edges: List<SampleEdge>, resets: List<ResetEvent>, polarity: Polarity): TraceResult {
        val events = mutableListOf<JudgmentEvent>()
        val sorted = edges.sortedBy { it.edgeIndex }
        val resetsAt = resets.associateBy { it.atEdgeIndex }
        val times = sorted.mapNotNull { it.time.normalized }
        val medianPeriod = if (times.size >= 3) {
            val deltas = times.zipWithNext { a, b -> b - a }.sorted()
            deltas[deltas.size / 2]
        } else null

        val segments = mutableListOf<TapSegment>()
        var steps = mutableListOf<TapStep>()
        var segStart = 0
        var segCount = 0
        var state = TapState.TLR
        var tmsRun = 0

        fun closeSegment(endedBy: String) {
            if (steps.isNotEmpty()) {
                segments += TapSegment("seg-${++segCount}", segStart, steps.toList(), endedBy)
                steps = mutableListOf()
            }
        }

        for ((i, e) in sorted.withIndex()) {
            // explicit reset event stored in its own source table
            resetsAt[e.edgeIndex]?.let { r ->
                closeSegment("RESET:${r.kind}")
                events += JudgmentEvent("MID_RESET", "edge=${e.id}", "reset ${r.kind} at edge ${e.edgeIndex}; segment split, not skipped")
                state = TapState.TLR
                tmsRun = 0
                segStart = i
            }
            // clock gap => the next hop may skip states: an illegal-transition suspect
            var anomaly: String? = null
            var legal = true
            if (medianPeriod != null && e.time.normalized != null && steps.isNotEmpty()) {
                val prevT = sorted[i - 1].time.normalized
                if (prevT != null && e.time.normalized - prevT > 3 * medianPeriod) {
                    anomaly = "CLOCK_GAP"
                    legal = false
                    events += JudgmentEvent("ILLEGAL_TRANSITION", "edge=${e.id}",
                        "clock gap ${e.time.raw}${e.time.unit ?: ""} exceeds 3x median period; hop not assumed, segment split")
                    closeSegment("CLOCK_GAP")
                    state = TapState.TLR // conservative: unknown state, restart from TLR only after explicit resync
                    segStart = i
                }
            }
            if (e.tms !in 0..1) {
                anomaly = "UNSTABLE_TMS"
                legal = false
                events += JudgmentEvent("ILLEGAL_TRANSITION", "edge=${e.id}", "TMS unstable at sampling instant; step kept as suspect, not dropped")
            }
            tmsRun = if (e.tms == 1) tmsRun + 1 else 0
            val next = if (legal) TapFsm.next(state, e.tms) else state
            // mid-scan reset by TMS=1 x5 while shifting
            if (tmsRun >= 5 && state != TapState.TLR && next == TapState.TLR &&
                (steps.any { it.to == TapState.SIR || it.to == TapState.SDR })) {
                events += JudgmentEvent("MID_RESET", "edge=${e.id}", "TMS=1 for >=5 clocks during shift; forced Test-Logic-Reset, segment split")
                steps += TapStep(i, e.id, state, TapState.TLR, e.tms, true, "MID_RESET")
                closeSegment("MID_RESET")
                state = TapState.TLR
                tmsRun = 0
                segStart = i + 1
                continue
            }
            steps += TapStep(i, e.id, state, next, e.tms, legal, anomaly)
            state = next
        }
        closeSegment(null)
        return TraceResult(TapTrace(polarity, segments, events.map { "${it.kind}:${it.subject}" }), events)
    }

    /** Extract IR/DR shift bitstreams from a trace. Capture-IR "01" pattern is
     *  used as a polarity/legality cross-check, never to fix IR length. */
    fun extractShiftBlocks(trace: TapTrace, edges: List<SampleEdge>): Pair<ShiftBitstreams, List<JudgmentEvent>> {
        val byId = edges.associateBy { it.id }
        val events = mutableListOf<JudgmentEvent>()
        val blocks = mutableListOf<ShiftBlock>()
        var n = 0
        for (seg in trace.segments) {
            var cur: MutableList<ShiftBit>? = null
            var kind: String? = null
            fun flush() {
                val bits = cur; val k = kind
                if (bits != null && bits.isNotEmpty() && k != null) {
                    val id = "blk-${++n}"
                    if (k == "IR" && bits.size >= 2) {
                        val b0 = bits[0].tdo; val b1 = bits[1].tdo
                        if (!(b0 == 1 && b1 == 0)) {
                            events += JudgmentEvent("POLARITY_SUSPECT", "block=$id",
                                "capture-IR pattern is $b1$b0, expected 01; check sampling polarity ${trace.polarity}")
                        }
                    }
                    blocks += ShiftBlock(id, k, seg.id, bits.toList())
                }
                cur = null; kind = null
            }
            for (st in seg.steps) {
                val inShift = st.to == TapState.SIR || st.to == TapState.SDR
                if (inShift) {
                    val k = if (st.to == TapState.SIR) "IR" else "DR"
                    if (kind != k) flush()
                    kind = k
                    val e = byId.getValue(st.edgeId)
                    val list = cur ?: mutableListOf<ShiftBit>().also { cur = it }
                    list += ShiftBit(e.id, e.tdi, e.tdo, e.time.normalized)
                } else flush()
            }
            flush()
        }
        return ShiftBitstreams(blocks) to events
    }

    /** Resolve the device chain. All legal segmentations are kept; IR length is
     *  never fixed from IDCODE alone; missing devices keep offset candidates. */
    fun resolveChain(
        bitstreams: ShiftBitstreams,
        irCandidates: List<IrLengthCandidate>,
        board: BoardRevision,
        bsdl: List<BsdlMapping>,
    ): Pair<List<DeviceChain>, List<JudgmentEvent>> {
        val events = mutableListOf<JudgmentEvent>()
        val irBlock = bitstreams.blocks.firstOrNull { it.kind == "IR" }
        val totalIr = irBlock?.bits?.size ?: 0

        // candidate lengths per device: union of BSDL + stored candidates; IDCODE_GUESS never auto-adopted
        val perDevice: Map<String, List<Pair<Int, String>>> = board.chainOrder.associateWith { dev ->
            val fromBsdl = bsdl.filter { it.device == dev }.mapNotNull { it.irLength }.map { it to "BSDL" }
            val stored = irCandidates.filter { it.device == dev }.map { it.length to it.basis }
            (fromBsdl + stored).distinctBy { it.first }.ifEmpty { listOf(0 to "UNKNOWN") }
        }
        for ((dev, cands) in perDevice) {
            if (cands.size > 1)
                events += JudgmentEvent("IRLEN_AMBIGUITY_RETAINED", "device=$dev",
                    "IR length candidates ${cands.map { it.first }} kept; IDCODE alone is not decisive")
            if (cands.any { it.second == "IDCODE_GUESS" } && cands.size == 1)
                events += JudgmentEvent("IRLEN_AMBIGUITY_RETAINED", "device=$dev",
                    "only IDCODE-based guess available; kept as CANDIDATE, not adopted")
        }

        // enumerate segmentations of totalIr across chain order (bounded)
        val hyps = mutableListOf<DeviceChain>()
        val order = board.chainOrder
        fun enum_(idx: Int, remaining: Int, acc: List<ChainDevice>) {
            if (idx == order.size) {
                if (remaining == 0) {
                    val id = "hyp-${hyps.size + 1}"
                    hyps += DeviceChain(id, acc)
                }
                return
            }
            val dev = order[idx]
            for ((len, basis) in perDevice.getValue(dev)) {
                if (len < 0 || len > remaining) continue
                val status = when {
                    basis == "BSDL" || basis == "DATASHEET" -> "ADOPTED"
                    basis == "UNKNOWN" -> "UNKNOWN"
                    else -> "CANDIDATE"
                }
                enum_(idx + 1, remaining - len, acc + ChainDevice(dev, len, status))
            }
            // also allow the device to be missing from the chain (offset candidate)
            if (dev in board.expectedDevices && idx < order.size) {
                enum_(idx + 1, remaining, acc + ChainDevice(dev, 0, "UNKNOWN", missing = true))
            }
        }
        enum_(0, totalIr, emptyList())
        if (hyps.isEmpty()) {
            // no exact segmentation: keep offset candidates for missing devices
            val offsets = order.indices.map { pos ->
                OffsetCandidate(pos, order[pos], 1)
            }
            events += JudgmentEvent("MISSING_DEVICE_OFFSET_CANDIDATES", "board=${board.board}@${board.revision}",
                "no exact IR segmentation of length $totalIr; ${offsets.size} offset candidates retained")
            val devs = order.map { d ->
                ChainDevice(d, perDevice.getValue(d).firstOrNull()?.first ?: 0, "UNKNOWN",
                    missing = true)
            }
            hyps += DeviceChain("hyp-offsets", devs, offsetCandidates = offsets)
        } else if (hyps.size > 1) {
            val ids = hyps.map { it.hypothesisId }
            hyps.replaceAll { it.copy(alternatives = ids - it.hypothesisId) }
            events += JudgmentEvent("IRLEN_AMBIGUITY_RETAINED", "chain=${board.board}",
                "${hyps.size} legal chain hypotheses retained: $ids")
        }
        // bypass devices: DR length 1 per bypassed device
        val drBlock = bitstreams.blocks.firstOrNull { it.kind == "DR" }
        if (drBlock != null && hyps.isNotEmpty()) {
            val h0 = hyps.first()
            if (drBlock.bits.size == h0.devices.count { !it.missing }) {
                hyps.replaceAll { h ->
                    h.copy(devices = h.devices.map { d -> if (!d.missing) d.copy(bypass = true) else d })
                }
                events += JudgmentEvent("BYPASS_OFFSET", "block=${drBlock.id}",
                    "DR width equals device count; all present devices treated as BYPASS (1-bit DR)")
            }
        }
        return hyps to events
    }

    /** Cell→pin evidence for one BSDL mapping version against observed DR shifts. */
    fun cellPinEvidence(chain: DeviceChain, mapping: BsdlMapping, bitstreams: ShiftBitstreams): CellPinEvidence {
        val dr = bitstreams.blocks.firstOrNull { it.kind == "DR" }
        val rows = mapping.cells.map { c ->
            val hit = dr?.bits?.getOrNull(c.cellIndex)
            CellPinRow(
                cellIndex = c.cellIndex,
                pin = c.pin,
                func = c.func,
                observedEdgeIds = if (hit != null) listOf(hit.edgeId) else emptyList(),
                unmapped = c.pin == null,
            )
        }
        return CellPinEvidence(chain.devices.firstOrNull { it.ref == mapping.device }?.ref ?: mapping.device,
            mapping.version, rows)
    }
}

/** Machine-readable judgment event emitted by rules and by human decisions. */
@Serializable
data class JudgmentEvent(
    val kind: String,      // MID_RESET | ILLEGAL_TRANSITION | IRLEN_AMBIGUITY_RETAINED | ...
    val subject: String,   // stable subject reference, e.g. edge=e-017 / device=U2
    val detail: String,
)
