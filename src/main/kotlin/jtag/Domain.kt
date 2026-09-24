package jtag

import kotlinx.serialization.Serializable

/** A domain quantity: raw representation is preserved at import;
 *  normalization happens only in the derived layer. */
@Serializable
data class Quantity(val raw: String, val unit: String, val basis: String? = null) {
    /** Normalized value in the canonical unit for [unit], or null when unconvertible. */
    fun normalized(): Pair<Double, String>? {
        val v = raw.trim().toDoubleOrNull() ?: return null
        return when (unit) {
            "ns" -> v * 1000.0 to "ps"
            "ps" -> v to "ps"
            "us" -> v * 1_000_000.0 to "ps"
            "V" -> v * 1000.0 to "mV"
            "mV" -> v to "mV"
            "MHz" -> v to "MHz"
            "grid" -> v to "grid" // coordinate basis values stay symbolic; see basis
            else -> null
        }
    }
}

enum class TapState {
    TestLogicReset, RunTestIdle,
    SelectDRScan, CaptureDR, ShiftDR, Exit1DR, PauseDR, Exit2DR, UpdateDR,
    SelectIRScan, CaptureIR, ShiftIR, Exit1IR, PauseIR, Exit2IR, UpdateIR;

    fun next(tms: Int): TapState = when (this) {
        TestLogicReset -> if (tms == 1) TestLogicReset else RunTestIdle
        RunTestIdle -> if (tms == 1) SelectDRScan else RunTestIdle
        SelectDRScan -> if (tms == 1) SelectIRScan else CaptureDR
        CaptureDR -> if (tms == 1) Exit1DR else ShiftDR
        ShiftDR -> if (tms == 1) Exit1DR else ShiftDR
        Exit1DR -> if (tms == 1) UpdateDR else PauseDR
        PauseDR -> if (tms == 1) Exit2DR else PauseDR
        Exit2DR -> if (tms == 1) UpdateDR else ShiftDR
        UpdateDR -> if (tms == 1) SelectDRScan else RunTestIdle
        SelectIRScan -> if (tms == 1) TestLogicReset else CaptureIR
        CaptureIR -> if (tms == 1) Exit1IR else ShiftIR
        ShiftIR -> if (tms == 1) Exit1IR else ShiftIR
        Exit1IR -> if (tms == 1) UpdateIR else PauseIR
        PauseIR -> if (tms == 1) Exit2IR else PauseIR
        Exit2IR -> if (tms == 1) UpdateIR else ShiftIR
        UpdateIR -> if (tms == 1) SelectDRScan else RunTestIdle
    }

    val isShift: Boolean get() = this == ShiftIR || this == ShiftDR
}

/** Source kinds, each stored separately. */
enum class SourceKind(val key: String) {
    SampleEdges("sample-edges"),
    ResetEvents("reset-events"),
    IrLengthCandidates("ir-length-candidates"),
    BsdlPinMap("bsdl-pin-map"),
    BoardRevision("board-revision");

    companion object {
        fun of(key: String): SourceKind = entries.first { it.key == key }
    }
}

@Serializable
data class SampleCycle(val tms: Int, val tdi: Int, val tdo: Int, val tmsGlitch: Boolean = false)

@Serializable
data class SampleEdgesPayload(
    val label: String,
    val activeEdge: String, // "rising" | "falling" — sampling polarity
    val tckPeriod: Quantity,
    val threshold: Quantity,
    val cycles: List<SampleCycle>
)

@Serializable
data class ResetEvent(val atCycle: Int, val kind: String) // "trst" | "por"

@Serializable
data class ResetEventsPayload(val resets: List<ResetEvent>)

@Serializable
data class DeviceIrCandidate(val name: String, val irLengthCandidates: List<Int>, val idcode: String? = null)

@Serializable
data class IrLengthCandidatesPayload(val devices: List<DeviceIrCandidate>)

@Serializable
data class BsdlCell(val cell: Int, val port: String, val pin: String, val function: String, val safe: String)

@Serializable
data class BsdlPinMapPayload(
    val bsdlVersion: String,
    val packageName: String,
    val pinBasis: String, // coordinate basis, e.g. "JEDEC-grid"
    val cells: List<BsdlCell>
)

@Serializable
data class BoardDevice(val position: Int, val name: String, val refDes: String)

@Serializable
data class BoardRevisionPayload(val revision: String, val devices: List<BoardDevice>)

/** Machine-readable judgement event. */
@Serializable
data class EventPayload(
    val type: String,
    val severity: String, // "info" | "warning" | "error"
    val summary: String,
    val detail: Map<String, String> = emptyMap(),
    val candidates: List<String> = emptyList()
)

object EventTypes {
    const val MidShiftReset = "MidShiftReset"
    const val IllegalTapTransition = "IllegalTapTransition"
    const val IrLengthAmbiguous = "IrLengthAmbiguous"
    const val IrLengthInsufficientEvidence = "IrLengthInsufficientEvidence"
    const val BypassDevice = "BypassDevice"
    const val ChainDeviceMissing = "ChainDeviceMissing"
    const val PolarityApplied = "PolarityApplied"
    const val MappingVersionChanged = "MappingVersionChanged"
    const val UnconvertibleUnit = "UnconvertibleUnit"
    const val DecisionConflict = "DecisionConflict"
    const val DecisionAccepted = "DecisionAccepted"
    const val DecisionRejected = "DecisionRejected"
}
