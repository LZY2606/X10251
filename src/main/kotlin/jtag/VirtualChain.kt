package jtag

/** A virtual JTAG device used to synthesize realistic TDO for fixtures. */
data class VirtualDevice(
    val name: String,
    val irLength: Int,
    val idcode: String?,               // 32-char binary, MSB first; null => bypass-only
    val idcodeOpcode: String? = null,  // instruction bits selecting IDCODE
    val irCaptureOnes: Boolean = false // true => Capture-IR loads all-ones (hides boundaries)
) {
    var state: TapState = TapState.RESET
    var instruction: String = if (idcode != null) "IDCODE" else "BYPASS"
    var irShift: List<Int> = List(irLength) { 0 }
    var drShift: List<Int> = listOf(0)

    fun captureIr(): List<Int> =
        if (irCaptureOnes) List(irLength) { 1 }
        else List(irLength) { i -> if (i == 0) 1 else 0 } // bit0=1, bit1=0, rest 0

    fun captureDr(): List<Int> =
        if (instruction == "IDCODE" && idcode != null)
            idcode.reversed().map { it - '0' }   // LSB first
        else listOf(0)                            // bypass

    fun updateIr(bits: List<Int>) {
        val s = bits.joinToString("") { it.toString() }
        instruction = when {
            s.all { it == '1' } -> "BYPASS"
            idcodeOpcode != null && s == idcodeOpcode.reversed() -> "IDCODE"
            idcodeOpcode == null && idcode != null && s.startsWith("00") -> "IDCODE"
            else -> "BYPASS"
        }
    }
}

/** Chain ordered TDI -> TDO: devices[0] is nearest TDI. */
class VirtualChain(val devices: List<VirtualDevice>) {
    /** One TCK cycle. Returns the TDO bit presented during this cycle. */
    fun clock(tms: Int, tdi: Int): Int {
        // compute TDO bits from pre-edge state, TDI->TDO
        val tdoBits = IntArray(devices.size)
        for ((i, d) in devices.withIndex()) {
            tdoBits[i] = when (d.state) {
                TapState.SHIFT_IR -> d.irShift[0]
                TapState.SHIFT_DR -> d.drShift[0]
                else -> 0
            }
        }
        // apply edges
        for ((i, d) in devices.withIndex()) {
            val inBit = if (i == 0) tdi else tdoBits[i - 1]
            when (d.state) {
                TapState.CAPTURE_IR -> d.irShift = d.captureIr()
                TapState.CAPTURE_DR -> d.drShift = d.captureDr()
                TapState.SHIFT_IR -> d.irShift = d.irShift.drop(1) + inBit
                TapState.SHIFT_DR -> d.drShift = d.drShift.drop(1) + inBit
                TapState.UPDATE_IR -> d.updateIr(d.irShift)
                TapState.RESET -> d.instruction = if (d.idcode != null) "IDCODE" else "BYPASS"
                else -> {}
            }
            d.state = TapFsm.next(d.state, tms)
        }
        return tdoBits.last()
    }
}

/** High-level script helpers, expressed as (tms, tdi) cycles. */
object JtagScript {
    fun reset(): List<Pair<Int, Int>> = List(6) { 1 to 0 }
    fun idle(n: Int = 2): List<Pair<Int, Int>> = List(n) { 0 to 0 }

    /** IDLE/RESET -> SHIFT_DR, shift [bits] (LSB-first into TDI), back to IDLE. */
    fun drScan(bits: List<Int>): List<Pair<Int, Int>> =
        listOf(0 to 0, 1 to 0, 0 to 0, 0 to 0) + // IDLE, SELECT_DR, CAPTURE_DR, SHIFT_DR
        bits.mapIndexed { i, b -> (if (i == bits.size - 1) 1 else 0) to b } +
        listOf(1 to 0, 0 to 0)           // UPDATE_DR, IDLE

    fun irScan(bits: List<Int>): List<Pair<Int, Int>> =
        listOf(0 to 0, 1 to 0, 1 to 0, 0 to 0, 0 to 0) + // IDLE, SELECT_DR, SELECT_IR, CAPTURE_IR, SHIFT_IR
        bits.mapIndexed { i, b -> (if (i == bits.size - 1) 1 else 0) to b } +
        listOf(1 to 0, 0 to 0)
}
