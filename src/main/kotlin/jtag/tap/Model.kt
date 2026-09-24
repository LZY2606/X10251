package jtag.tap

import kotlinx.serialization.Serializable

/** Raw sampled TCK edge. Raw representation is preserved as imported; units carried explicitly. */
@Serializable
data class SamplingEdge(
    val edgeIndex: Int,
    val tms: Int,
    val tdi: Int,
    val tdo: Int,
    val tckValid: Boolean = true,   // false = glitch / missing clock edge, cannot drive FSM
    val timePs: Long? = null        // raw timestamp, picoseconds; null if unknown
)

@Serializable
enum class EdgePolarity { RISING, FALLING }

@Serializable
data class SamplingSet(
    val polarity: EdgePolarity,
    val edges: List<SamplingEdge>
)

@Serializable
enum class ResetKind { TRST, POWER_ON }

@Serializable
data class ResetEvent(val edgeIndex: Int, val kind: ResetKind)

@Serializable
data class ResetSet(val events: List<ResetEvent>)

/** One reconstructed TAP step. */
@Serializable
data class TapStep(
    val edgeIndex: Int,
    val tms: Int,
    val tdi: Int,
    val tdo: Int,
    val stateBefore: String,   // TapState name or "UNKNOWN"
    val stateAfter: String,
    val illegal: Boolean = false,
    val resetHere: Boolean = false
)

/** A contiguous Shift-IR / Shift-DR bit segment. */
@Serializable
data class ShiftSegment(
    val id: String,
    val register: String,          // "IR" or "DR"
    val startEdge: Int,
    val endEdge: Int,              // inclusive edge of last shift bit
    val tdiBits: List<Int>,        // in shift order (first shifted-in first)
    val tdoBits: List<Int>,
    val aborted: Boolean = false,  // true if ended by reset / illegal transition, not by Exit1
    val abortedReason: String? = null
)

/** Machine-readable judgment events. These are never silently dropped. */
@Serializable
data class TapEvent(
    val type: String,              // ILLEGAL_TRANSITION, MID_SHIFT_RESET, IR_LENGTH_AMBIGUOUS, ...
    val edgeIndex: Int,
    val severity: String,          // ERROR | WARN | INFO
    val detail: String,
    val attrs: Map<String, String> = emptyMap()
)

@Serializable
data class TraceResult(
    val steps: List<TapStep>,
    val segments: List<ShiftSegment>,
    val events: List<TapEvent>
)
