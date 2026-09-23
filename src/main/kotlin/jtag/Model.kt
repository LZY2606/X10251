package jtag

import java.util.UUID

/** Stable identity: assigned once at creation, preserved across export/import. */
fun newId(prefix: String): String = prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)

/**
 * A domain quantity. The raw representation is preserved exactly as imported;
 * normalization happens only in the derived layer. Values that cannot be
 * converted stay in place and produce a diagnostic instead of being dropped.
 */
data class Quantity(
    val raw: String,
    val value: Double?,
    val unit: String?,
    /** coordinate base, e.g. "since_power_on", "sample_index" */
    val base: String?
) {
    companion object {
        private val toNs = mapOf("ns" to 1.0, "us" to 1_000.0, "ms" to 1_000_000.0, "s" to 1_000_000_000.0)
        fun parse(raw: String, base: String?): Pair<Quantity, String?> {
            val m = Regex("""^(-?\d+(?:\.\d+)?)\s*([a-zA-Z]*)$""").matchEntire(raw.trim())
                ?: return Quantity(raw, null, null, base) to "unparseable quantity: '$raw'"
            val v = m.groupValues[1].toDouble()
            val u = m.groupValues[2].ifEmpty { null }
            return Quantity(raw, v, u, base) to null
        }
    }
    /** Normalize to nanoseconds; null (with diagnostic recorded by caller) when impossible. */
    fun toNs(): Double? = when {
        value == null -> null
        unit == null -> value // unitless: assumed already in base unit, kept as-is
        toNs.containsKey(unit) -> value * toNs[unit]!!
        else -> null
    }
    fun isConvertible(): Boolean = value != null && (unit == null || toNs.containsKey(unit))
}

enum class SampleEdge { RISING, FALLING }

enum class TapState {
    TEST_LOGIC_RESET, RUN_TEST_IDLE,
    SELECT_DR_SCAN, CAPTURE_DR, SHIFT_DR, EXIT1_DR, PAUSE_DR, EXIT2_DR, UPDATE_DR,
    SELECT_IR_SCAN, CAPTURE_IR, SHIFT_IR, EXIT1_IR, PAUSE_IR, EXIT2_IR, UPDATE_IR;

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
    val isShift get() = this == SHIFT_IR || this == SHIFT_DR
    val isIr get() = ordinal >= SELECT_IR_SCAN.ordinal
}

data class Sample(
    val id: String,
    val idx: Int,
    val time: Quantity,
    val tms: Int, val tck: Int, val tdi: Int, val tdo: Int,
    /** true when the sample violates setup/hold around the clock edge (glitch) */
    val ambiguous: Boolean = false,
    val raw: String = ""
)

data class ResetEvent(val id: String, val atSample: Int, val kind: String, val detail: String)

data class IrCandidate(val id: String, val position: Int, val length: Int, val source: String, val rationale: String)

data class TapStep(
    val id: String, val idx: Int, val segment: Int,
    val from: TapState, val to: TapState, val tms: Int,
    val time: Quantity, val flags: List<String>
)

data class ShiftStream(
    val id: String, val segment: Int, val kind: String, // IR or DR
    val bitsIn: String, val bitsOut: String,
    val startStep: Int, val endStep: Int
)

data class ChainDevice(
    val id: String, val position: Int, val device: String,
    val irLength: Int?, val status: String, // OK, AMBIGUOUS, MISSING_CANDIDATE, BYPASS
    val candidates: List<Int>
)

data class OffsetCandidate(val id: String, val device: String, val offsets: List<Int>)

data class CellPin(val id: String, val device: String, val cell: Int, val port: String, val pin: String, val bsdlVersion: String)

data class JEvent(
    val id: String, val kind: String, val payload: String,
    val derivationId: String?, val sourceRefs: List<String>,
    val decided: Boolean, val createdAt: String
)

data class Conflict(
    val id: String, val store: String, val baseVersion: String, val currentVersion: String,
    val incoming: String, val existing: String, var status: String, var resolution: String?
)

data class Counterexample(
    val id: String, val name: String, val baseline: String, val mutation: String,
    val expectedScope: String, val observedScope: String, val adopted: Boolean, val retained: String
)

data class Diagnostic(val id: String, val where: String, val message: String, val raw: String)
