package gsb.domain

data class Segmentation(
    val assignment: Map<String, Int>, // deviceId -> ir length
    val solelyIdcodeGuess: Boolean,
)

data class DrParse(
    val idcodes: List<String>,
    val bypassBits: Int,
    val observedDevices: Int,
    val remainderBits: Int,
)

data class ChainResult(
    val totalIrWidth: Int,
    val segmentations: List<Segmentation>,
    val status: String, // RESOLVED | AMBIGUOUS | UNRESOLVED_IDCODE_ONLY | INCONSISTENT | ADOPTED_BY_DECISION
    val adopted: Map<String, Int>?,
    val adoptedByEvent: String?,
    val dr: DrParse?,
    val expectedDevices: Int,
    val missingDevices: List<String>,
    val anomalies: List<Anomaly>,
)

object ChainAnalysis {

    /** Parse a DR shift TDO stream: bit 0 -> bypass device; bit 1 -> 32-bit IDCODE follows. */
    fun parseDrChain(tdoBits: List<String>): DrParse {
        val idcodes = mutableListOf<String>()
        var bypass = 0
        var i = 0
        var devices = 0
        while (i < tdoBits.size) {
            val b = tdoBits[i]
            if (b == "0") { bypass++; devices++; i += 1; continue }
            if (b == "1" && i + 32 <= tdoBits.size) {
                val chunk = tdoBits.subList(i, i + 32)
                if (chunk.any { it == "Z" }) break
                // LSB-first on the wire
                val v = chunk.reversed().joinToString("").toLong(2)
                idcodes += "0x" + v.toString(16).uppercase().padStart(8, '0')
                devices++; i += 32; continue
            }
            break
        }
        return DrParse(idcodes, bypass, devices, tdoBits.size - i)
    }

    /** Enumerate every candidate assignment whose lengths sum to the measured IR width. */
    fun segmentations(devices: List<DeviceEntry>, totalIrWidth: Int): List<Segmentation> {
        val out = mutableListOf<Segmentation>()
        fun rec(i: Int, acc: MutableMap<String, Int>, sum: Int, idcodeOnly: Boolean) {
            if (sum > totalIrWidth) return
            if (i == devices.size) {
                if (sum == totalIrWidth) out += Segmentation(acc.toMap(), idcodeOnly)
                return
            }
            val d = devices[i]
            for (c in d.candidates) {
                acc[d.id] = c.length
                // A candidate backed only by an IDCODE guess (no independent
                // BSDL/measured candidate of the same length) cannot anchor a decision.
                val onlyGuess = c.provenance == "IDCODE_GUESS" &&
                    d.candidates.none { it.length == c.length && it.provenance != "IDCODE_GUESS" }
                rec(i + 1, acc, sum + c.length, idcodeOnly || onlyGuess)
            }
            acc.remove(d.id)
        }
        rec(0, mutableMapOf(), 0, false)
        return out
    }

    fun analyze(
        devices: List<DeviceEntry>,
        irShiftWidths: List<Int>,
        drShift: Shift?,
        board: BoardRevision?,
        decision: JsonDecisionOverlay?,
        captureId: String,
    ): ChainResult {
        val anomalies = mutableListOf<Anomaly>()
        var seq = 0
        fun anomaly(kind: String, msg: String, rule: String? = null) {
            anomalies += Anomaly("an-chain-$captureId-${seq++}", kind, null, null, msg, rule)
        }

        val totalIrWidth = irShiftWidths.firstOrNull() ?: 0
        val segs = segmentations(devices, totalIrWidth)
        var status: String
        var adopted: Map<String, Int>? = null
        var adoptedBy: String? = null
        when {
            segs.isEmpty() -> {
                status = "INCONSISTENT"
                anomaly("CHAIN_IR_MISMATCH", "no candidate assignment sums to measured IR width $totalIrWidth")
            }
            segs.size > 1 -> {
                status = "AMBIGUOUS"
                anomaly(
                    "IR_LENGTH_AMBIGUOUS",
                    "${segs.size} legal IR-length segmentations for width $totalIrWidth; all retained",
                    "ir-length-not-idcode-only",
                )
            }
            segs.single().solelyIdcodeGuess -> {
                status = "UNRESOLVED_IDCODE_ONLY"
                anomaly(
                    "IR_LENGTH_IDCODE_ONLY",
                    "sole consistent segmentation rests on IDCODE guesses only; not adopted automatically",
                    "ir-length-not-idcode-only",
                )
            }
            else -> {
                status = "RESOLVED"
                adopted = segs.single().assignment
            }
        }
        if (decision != null && segs.any { it.assignment == decision.assignment }) {
            status = "ADOPTED_BY_DECISION"
            adopted = decision.assignment
            adoptedBy = decision.eventId
        }

        val dr = drShift?.let { parseDrChain(it.tdoBits) }
        val expected = board?.expectedDevices?.size ?: devices.size
        val observed = dr?.observedDevices ?: 0
        val missing = mutableListOf<String>()
        if (dr != null && observed < expected) {
            val depop = board?.depopulated ?: emptyList()
            val unexplained = expected - observed - depop.size
            val candidates = if (unexplained > 0) {
                board?.expectedDevices?.filter { it !in depop }?.takeLast(unexplained) ?: emptyList()
            } else emptyList()
            missing += candidates
            anomaly(
                "MISSING_DEVICE",
                "expected $expected devices, observed $observed on DR chain; missing=$candidates depopulated=$depop",
                "missing-device-offsets-retained",
            )
        }
        return ChainResult(totalIrWidth, segs, status, adopted, adoptedBy, dr, expected, missing, anomalies)
    }
}

/** A human decision overlay (stored as a DECISION source, linked to its event). */
data class JsonDecisionOverlay(val assignment: Map<String, Int>, val eventId: String)
