package jtag

/**
 * Scan-chain assembly. Rules:
 *  - IR length is never fixed from IDCODE alone; all legal candidates are kept;
 *  - bypass devices contribute exactly 1 DR bit;
 *  - when a device is missing from the chain, offset candidates for the
 *    remaining devices are preserved instead of collapsing to one answer.
 */
object ChainAssembly {

    data class Result(
        val devices: List<ChainDevice>,
        val offsets: List<OffsetCandidate>,
        val events: List<JEvent>
    )

    fun assemble(
        expectedDevices: List<String>,          // from board revision, in chain order
        populated: Map<String, Boolean>,        // device -> populated?
        irCandidates: Map<Int, List<IrCandidate>>, // position -> candidates
        observedBypassBits: Int,                // measured BYPASS-chain DR length
        derivationId: String?,
        now: String
    ): Result {
        val events = mutableListOf<JEvent>()
        val devices = mutableListOf<ChainDevice>()
        val offsets = mutableListOf<OffsetCandidate>()

        expectedDevices.forEachIndexed { pos, dev ->
            val cands = (irCandidates[pos] ?: emptyList()).sortedBy { it.source }
            val lengths = cands.map { it.length }.distinct()
            val idcodeOnly = cands.isNotEmpty() && cands.all { it.source == "IDCODE_HEURISTIC" }
            when {
                cands.isEmpty() -> {
                    devices += ChainDevice(newId("dev"), pos, dev, null, "UNKNOWN", emptyList())
                }
                lengths.size > 1 || idcodeOnly -> {
                    devices += ChainDevice(newId("dev"), pos, dev, null, "AMBIGUOUS", lengths)
                    events += JEvent(newId("evt"), "IR_LENGTH_AMBIGUOUS",
                        """{"device":"$dev","position":$pos,"candidates":${lengths},"basis":"IR length cannot be fixed from IDCODE alone; candidates kept"}""",
                        derivationId, cands.map { it.id }, false, now)
                }
                else -> devices += ChainDevice(newId("dev"), pos, dev, lengths.first(), "OK", lengths)
            }
        }

        // missing-device analysis: expected populated count vs observed bypass bits
        val populatedDevices = expectedDevices.filter { populated[it] != false }
        val missing = expectedDevices.filter { populated[it] == false }
        if (populatedDevices.size != observedBypassBits || missing.isNotEmpty()) {
            // keep every legal offset hypothesis: each missing device may explain the shortfall
            val hypotheses = mutableListOf<List<String>>()
            if (missing.isNotEmpty()) hypotheses += missing
            if (populatedDevices.size - observedBypassBits > 0 && missing.isEmpty()) {
                // unannounced shortfall: any single device could be absent
                expectedDevices.forEach { d -> hypotheses += listOf(d) }
            }
            for (h in hypotheses.distinct()) {
                for (dev in expectedDevices) {
                    if (dev in h) {
                        devices += ChainDevice(newId("dev"), expectedDevices.indexOf(dev), dev, null,
                            "MISSING_CANDIDATE", emptyList())
                    } else {
                        val base = expectedDevices.indexOf(dev)
                        val candOffsets = (0..h.size).map { base - it }.distinct()
                        offsets += OffsetCandidate(newId("off"), dev, candOffsets)
                    }
                }
                events += JEvent(newId("evt"), "OFFSET_CANDIDATES_KEPT",
                    """{"missingHypothesis":${h.map { "\"$it\"" }},"observedBypassBits":$observedBypassBits,"expected":${populatedDevices.size}}""",
                    derivationId, emptyList(), false, now)
            }
        }
        return Result(devices, offsets, events)
    }
}
