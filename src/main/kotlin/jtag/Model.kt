package jtag

import kotlinx.serialization.Serializable

/** Kinds of source materials. Each kind is stored separately per the storage contract. */
enum class SourceKind {
    SAMPLE_EDGES, RESET_EVENTS, IR_LENGTH_CANDIDATES, BSDL_PIN_MAP, BOARD_REVISION
}

/**
 * A domain quantity always carries its raw representation plus unit / coordinate basis.
 * The raw text is preserved at import; normalization happens only in the derived layer.
 */
@Serializable
data class Quantity(
    val raw: String,
    val unit: String? = null,
    val basis: String? = null,
) {
    /** Normalized value in the base unit of its dimension, or null when unconvertible. */
    fun normalized(): Double? = UnitTable.toBase(raw, unit)
}

object UnitTable {
    private val time = mapOf("ns" to 1.0, "us" to 1e3, "ms" to 1e6, "s" to 1e9)
    private val volt = mapOf("V" to 1.0, "mV" to 1e-3)
    fun toBase(raw: String, unit: String?): Double? {
        val v = raw.trim().toDoubleOrNull() ?: return null
        if (unit == null) return v
        time[unit]?.let { return v * it }
        volt[unit]?.let { return v * it }
        return null // unknown unit: unconvertible, stays raw with a diagnostic
    }
}

/** The 16 JTAG TAP controller states (IEEE 1149.1). */
enum class TapState {
    TEST_LOGIC_RESET, RUN_TEST_IDLE,
    SELECT_DR_SCAN, CAPTURE_DR, SHIFT_DR, EXIT1_DR, PAUSE_DR, EXIT2_DR, UPDATE_DR,
    SELECT_IR_SCAN, CAPTURE_IR, SHIFT_IR, EXIT1_IR, PAUSE_IR, EXIT2_IR, UPDATE_IR;

    /** Next state for TMS sampled on the active TCK edge. */
    fun next(tms: Int): TapState = when (this) {
        TEST_LOGIC_RESET -> if (tms == 1) TEST_LOGIC_RESET else RUN_TEST_IDLE
        RUN_TEST_IDLE -> if (tms == 1) SELECT_DR_SCAN else RUN_TEST_IDLE
        SELECT_DR_SCAN -> if (tms == 1) SELECT_IR_SCAN else CAPTURE_DR
        CAPTURE_DR -> if (tms == 1) EXIT1_DR else SHIFT_DR
        SHIFT_DR -> if (tms == 1) EXIT1_DR else SHIFT_DR
        EXIT1_DR -> if (tms == 1) UPDATE_DR else PAUSE_DR
        PAUSE_DR -> if (tms == 1) EXIT2_DR else PAUSE_DR
        EXIT2_DR -> if (tms == 1) UPDATE_DR else SHIFT_DR
        UPDATE_DR -> if (tms == 1) SELECT_DR_SCAN else RUN_TEST_IDLE
        SELECT_IR_SCAN -> if (tms == 1) TEST_LOGIC_RESET else CAPTURE_IR
        CAPTURE_IR -> if (tms == 1) EXIT1_IR else SHIFT_IR
        SHIFT_IR -> if (tms == 1) EXIT1_IR else SHIFT_IR
        EXIT1_IR -> if (tms == 1) UPDATE_IR else PAUSE_IR
        PAUSE_IR -> if (tms == 1) EXIT2_IR else PAUSE_IR
        EXIT2_IR -> if (tms == 1) UPDATE_IR else SHIFT_IR
        UPDATE_IR -> if (tms == 1) SELECT_DR_SCAN else RUN_TEST_IDLE
    }
}

/** One sampled row of the four JTAG signals. Times/voltages keep raw text + unit. */
@Serializable
data class EdgeSample(
    val index: Int,
    val time: Quantity,
    val tck: Int, val tms: Int, val tdi: Int, val tdo: Int,
)

@Serializable
data class ResetEvent(
    val atTime: Quantity,
    val kind: String, // "TRST_ASSERT" | "TMS5" (five clocks with TMS=1)
    val note: String = "",
)

/** A parsed BSDL device description (subset of IEEE 1149.1 BSDL). */
@Serializable
data class BsdlDevice(
    val name: String,
    val mapVersion: String,
    val instructionLength: Int,
    val idcode: String?,            // 32-bit binary string, may contain X
    val opcodes: Map<String, String>, // mnemonic -> binary pattern
    val boundaryLength: Int,
    /** cell index -> (cellType, portName, function, safeValue) */
    val boundaryCells: List<BoundaryCell>,
    /** logical port -> package pin (coordinate basis: package drawing of mapVersion) */
    val pinMap: Map<String, String>,
)

@Serializable
data class BoundaryCell(
    val index: Int,
    val cellType: String,
    val port: String,   // "*" for control/internal cells
    val function: String, // input | output2 | output3 | control | bidir | internal
    val safe: String,
)

@Serializable
data class BoardRevision(
    val revision: String,
    /** Expected device order on the scan chain, nearest TDI first. */
    val chainOrder: List<String>,
    val note: String = "",
)

@Serializable
data class IrLengthCandidate(
    val device: String,
    val lengths: List<Int>,
    val origin: String, // e.g. "BSDL", "MEASURE", "DATASHEET" — never "IDCODE" alone
)

// ---------------- Derived artifacts ----------------

@Serializable
data class TapTransition(
    val clockIndex: Int,
    val time: Quantity,
    val from: TapState,
    val to: TapState,
    val tms: Int, val tdi: Int, val tdo: Int,
    val anomaly: String? = null, // non-null => diagnostic, never silently skipped
)

@Serializable
data class ShiftSegment(
    val kind: String, // "IR" | "DR"
    val startClock: Int,
    val bitCount: Int,
    val tdiBits: String, // first-shifted bit first
    val tdoBits: String,
    val truncatedByReset: Boolean = false,
)

@Serializable
data class TapTrace(
    val transitions: List<TapTransition>,
    val shifts: List<ShiftSegment>,
    val resets: List<ResetEvent>,
    val anomalies: List<String>,
)

/** One legal composition of the chain's total IR length. All legal candidates are kept. */
@Serializable
data class ChainComposition(
    val id: String,
    val deviceIrLengths: Map<String, Int>,
    val totalIr: Int,
    val basis: String, // which sources constrain this composition
    val adopted: Boolean = false,
)

/** Offset candidate for cell->pin mapping when a chain device may be missing. */
@Serializable
data class OffsetCandidate(
    val id: String,
    val missingDevice: String?,      // null = chain complete
    val missingAtPosition: Int?,     // position in expected order (TDI-first)
    val drOffsetShift: Int,          // bits the downstream cells shift by
    val rationale: String,
    val adopted: Boolean = false,
)

@Serializable
data class CellPinEvidence(
    val device: String,
    val cellIndex: Int,
    val port: String,
    val function: String,
    val pin: String?,              // null when unmapped in this BSDL version
    val chainBitPosition: Int,     // position in the concatenated boundary register
    val mapVersion: String,
)

@Serializable
data class ChainMap(
    val devices: List<String>,     // TDI-first order actually used
    val cells: List<CellPinEvidence>,
    val offsetCandidates: List<OffsetCandidate>,
    val diagnostics: List<String>,
)

@Serializable
data class PinMappingDiff(
    val device: String,
    val fromVersion: String,
    val toVersion: String,
    val changed: List<Triple<Int, String, String>>, // cellIndex, pin@from, pin@to
    val onlyInFrom: List<Int>,
    val onlyInTo: List<Int>,
)
