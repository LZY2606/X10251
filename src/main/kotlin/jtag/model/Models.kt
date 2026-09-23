package jtag.model

import kotlinx.serialization.Serializable
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

/** A quantity that keeps its raw representation. Normalization happens only in the derived layer. */
@Serializable
data class Quantity(
    val raw: String,
    val unit: String? = null,
    val normalized: Double? = null, // normalized value in canonical unit, null when unconvertible
    val diagnostic: String? = null  // present when the value could not be converted
)

fun parseQuantity(raw: String, canonicalUnit: String, factors: Map<String, Double>): Quantity {
    val m = Regex("""^\s*(-?[0-9]+(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)\s*([a-zA-Zμ]+)\s*$""").find(raw)
        ?: return Quantity(raw = raw, diagnostic = "unparseable quantity: '$raw' kept raw at source position")
    val value = m.groupValues[1].toDouble()
    val unit = m.groupValues[2]
    val factor = factors[unit]
        ?: return Quantity(raw = raw, unit = unit, diagnostic = "unknown unit '$unit': cannot normalize to $canonicalUnit, kept raw")
    return Quantity(raw = raw, unit = unit, normalized = value * factor)
}

val TIME_FACTORS = mapOf("s" to 1e9, "ms" to 1e6, "us" to 1e3, "ns" to 1.0, "ps" to 1e-3)
fun parseTime(raw: String): Quantity = parseQuantity(raw, "ns", TIME_FACTORS)

/** Raw capture row: one row per TCK half period, so both phases are preserved. */
@Serializable
data class EdgeSample(
    val seq: Int,
    val phase: ClockPhase,
    val tms: Boolean,
    val tdi: Boolean,
    val tdo: Boolean,
    val tck: Boolean,
    val time: Quantity,
    val trst: Boolean = false
)

@Serializable
enum class ClockPhase { RISING, FALLING }

@Serializable
enum class ResetKind { TMS_FIVE_HIGH, TRST_ASSERTED }

@Serializable
data class ResetEvent(
    val id: String = newId(),
    val kind: ResetKind,
    val atSeq: Int,          // edge seq where reset became effective
    val span: Int,           // number of consecutive reset-inducing edges
    val midStream: Boolean   // true when it interrupted an in-progress shift/transaction
)

@Serializable
enum class CandidateStatus { PENDING, ACCEPTED, REJECTED }

@Serializable
data class IrLengthCandidate(
    val id: String = newId(),
    val perDeviceBits: List<Int>,   // hypothesized IR length per chain position
    val rationale: String,
    val status: CandidateStatus = CandidateStatus.PENDING
)

@Serializable
data class BoundaryCell(
    val index: Int,
    val cellType: String,   // e.g. BC_1, BC_2, BC_4
    val port: String,       // port name or "-" for control cells
    val function: String,   // input, output2, output3, control, observe_only, internal
    val safe: String,       // safe value
    val pin: String? = null // package coordinate, basis recorded on the mapping
)

@Serializable
data class BsdlMapping(
    val id: String = newId(),
    val deviceRef: String,
    val bsdlVersion: String,
    val entity: String,
    val packageBasis: String,          // coordinate basis for pins, e.g. "TQFP48 pad number" / "BGA ball grid"
    val idcode: String,                // 32-bit binary string
    val irLength: Int,
    val cells: List<BoundaryCell>,
    val rawText: String                // original BSDL preserved verbatim
)

@Serializable
data class DeviceSlot(
    val position: Int,
    val name: String,
    val bsdlRef: String,      // deviceRef of a BsdlMapping
    val expectedPresent: Boolean = true
)

@Serializable
data class BoardRevision(
    val id: String = newId(),
    val label: String,
    val slots: List<DeviceSlot>
)

/** Optional external decoder hint: "after edge N the decoder believes we are in state S". */
@Serializable
data class StateHint(val atSeq: Int, val state: String, val source: String)

@Serializable
data class EdgeBundle(
    val samples: List<EdgeSample>,
    val hints: List<StateHint> = emptyList(),
    val probeId: String = "probe-0",
    val voltage: Quantity = Quantity("1.8 V", "V", 1.8)
)
