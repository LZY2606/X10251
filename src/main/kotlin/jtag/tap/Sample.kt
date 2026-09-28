package jtag.tap

import jtag.units.Quantity

enum class Polarity { NORMAL, INVERTED }
enum class EdgeKind { RISING, FALLING }

/**
 * One sampled edge of the JTAG bus. Time carries unit and basis; the raw
 * string is the original import representation.
 */
data class SampleEdge(
    val id: String,
    val ord: Int,
    val time: Quantity,
    val tck: Int, val tms: Int, val tdi: Int, val tdo: Int,
    val edge: EdgeKind,
    val polarity: Polarity,
    /** Optional externally-decoded TAP state label (e.g. from a protocol probe). */
    val observedState: String? = null
)

data class ResetEvent(
    val id: String,
    val ord: Int,
    val atEdgeIndex: Int,
    val kind: String,      // TRST_ASSERTED | TMS_HOLD_5 | POWER_CYCLE
    val note: String = ""
)
