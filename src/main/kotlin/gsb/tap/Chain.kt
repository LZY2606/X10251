package gsb.tap

/** Expected device on the chain, from the board revision source. */
data class DeviceExpectation(
    val ref: String,
    val position: Int,
    /** Candidate IR lengths from the ir-candidates source / BSDL. Empty = unknown. */
    val irLengthCandidates: Set<Int>,
    val bsdlRef: String? = null,
)

data class DevicePlacement(
    val ref: String,
    val irOffset: Int,
    val irLength: Int,
    val present: Boolean = true,
)

/** One complete, self-consistent explanation of the observed chain. */
data class ChainCandidate(
    val id: String,
    val placements: List<DevicePlacement>,
    val note: String,
)

data class ChainResult(
    val irLengthObserved: Int?,
    val drBypassBitsObserved: Int?,
    val candidates: List<ChainCandidate>,
    val events: List<TapEvent>,
)

object ChainReconstructor {
    /**
     * Reconstruct the scan chain from shift episodes and board expectations.
     * Rules enforced:
     *  - All IR-length assignments consistent with the observed Shift-IR length are kept
     *    (IR_LENGTH_AMBIGUOUS when >1). IR length is never guessed from IDCODE alone.
     *  - When the bypass DR count is smaller than the expected device count, the device is
     *    reported missing and ALL plausible offset candidates are retained (MISSING_DEVICE).
     */
    fun reconstruct(episodes: List<ShiftEpisode>, expected: List<DeviceExpectation>): ChainResult {
        val events = mutableListOf<TapEvent>()
        val irShift = episodes.firstOrNull { it.kind == ShiftKind.IR && !it.aborted }
        val drShift = episodes.firstOrNull { it.kind == ShiftKind.DR && !it.aborted }
        val irLen = irShift?.length
        val drBits = drShift?.length

        val candidates = mutableListOf<ChainCandidate>()

        if (irLen != null) {
            val assignments = enumerateIrAssignments(expected, irLen)
            when {
                assignments.isEmpty() -> {
                    events += TapEvent("IR_LENGTH_UNRESOLVED", irShift!!.startClock,
                        "no IR-length assignment of the expected devices sums to observed $irLen bits; " +
                            "IDCODE presence alone is not accepted as IR-length evidence",
                        expected.map { it.ref })
                    // keep offset candidates: try dropping each single device (chain may be missing a part)
                    expected.forEach { drop ->
                        val rest = expected.filter { it != drop }
                        enumerateIrAssignments(rest, irLen).forEach { placements ->
                            candidates += ChainCandidate(
                                "cand-missing-${drop.ref}-${candidates.size}",
                                placements + DevicePlacement(drop.ref, -1, 0, present = false),
                                "consistent only if ${drop.ref} is absent from the chain")
                        }
                    }
                    if (candidates.isNotEmpty())
                        events += TapEvent("MISSING_DEVICE", irShift!!.startClock,
                            "observed IR length only consistent when a device is missing; offset candidates retained",
                            candidates.map { it.id })
                }
                assignments.size > 1 -> {
                    events += TapEvent("IR_LENGTH_AMBIGUOUS", irShift!!.startClock,
                        "${assignments.size} IR-length assignments consistent with observed $irLen bits; all retained",
                        assignments.indices.map { "cand-$it" })
                    assignments.forEachIndexed { i, p ->
                        candidates += ChainCandidate("cand-$i", p, "IR assignment #${i + 1} of ${assignments.size}")
                    }
                }
                else -> candidates += ChainCandidate("cand-0", assignments.single(), "unique IR assignment")
            }
        }

        if (drBits != null && expected.isNotEmpty()) {
            val presentCount = drBits // each bypassed device contributes exactly 1 DR bit
            if (presentCount < expected.size) {
                val missing = expected.size - presentCount
                events += TapEvent("MISSING_DEVICE", drShift!!.startClock,
                    "bypass DR scan shows $presentCount bit(s) but board revision expects ${expected.size} device(s); " +
                        "$missing device(s) missing, offset candidates retained",
                    expected.map { it.ref })
                // retain every offset candidate set for the missing devices
                val combos = combinations(expected, expected.size - presentCount)
                combos.forEachIndexed { i, combo ->
                    if (candidates.none { c -> c.placements.map { it.ref }.toSet() == combo.map { it.ref }.toSet() }) {
                        var off = 0
                        val placements = combo.map { dev ->
                            val len = dev.irLengthCandidates.firstOrNull() ?: 0
                            DevicePlacement(dev.ref, off, len).also { off += len }
                        }
                        candidates += ChainCandidate("cand-dr-$i", placements,
                            "bypass-count consistent subset (${combo.size} of ${expected.size} devices)")
                    }
                }
            }
        }
        return ChainResult(irLen, drBits, candidates, events)
    }

    /** All assignments of candidate IR lengths to devices whose sum equals [observed]. */
    fun enumerateIrAssignments(devices: List<DeviceExpectation>, observed: Int): List<List<DevicePlacement>> {
        val out = mutableListOf<List<DevicePlacement>>()
        fun rec(i: Int, offset: Int, acc: MutableList<DevicePlacement>) {
            if (i == devices.size) {
                if (offset == observed) out += acc.toList()
                return
            }
            val dev = devices[i]
            val lens = if (dev.irLengthCandidates.isEmpty()) setOf(0) else dev.irLengthCandidates
            for (len in lens) {
                if (offset + len > observed) continue
                acc += DevicePlacement(dev.ref, offset, len)
                rec(i + 1, offset + len, acc)
                acc.removeAt(acc.size - 1)
            }
        }
        rec(0, 0, mutableListOf())
        return out
    }

    fun combinations(devices: List<DeviceExpectation>, pick: Int): List<List<DeviceExpectation>> {
        val out = mutableListOf<List<DeviceExpectation>>()
        fun rec(i: Int, acc: MutableList<DeviceExpectation>) {
            if (acc.size == pick) { out += acc.toList(); return }
            if (i >= devices.size) return
            acc += devices[i]; rec(i + 1, acc); acc.removeAt(acc.size - 1)
            rec(i + 1, acc)
        }
        rec(0, mutableListOf())
        return out
    }
}
