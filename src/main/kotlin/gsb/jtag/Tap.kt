package gsb.jtag

import kotlinx.serialization.Serializable

/** IEEE 1149.1 TAP controller states. */
@Serializable
enum class TapState(val label: String) {
    TLR("Test-Logic-Reset"), RTI("Run-Test/Idle"),
    SDRS("Select-DR-Scan"), CDR("Capture-DR"), SDR("Shift-DR"),
    E1DR("Exit1-DR"), PDR("Pause-DR"), E2DR("Exit2-DR"), UDR("Update-DR"),
    SIRS("Select-IR-Scan"), CIR("Capture-IR"), SIR("Shift-IR"),
    E1IR("Exit1-IR"), PIR("Pause-IR"), E2IR("Exit2-IR"), UIR("Update-IR");

    fun next(tms: Int): TapState = NEXT[this.ordinal][if (tms == 0) 0 else 1]

    val isShift get() = this == SDR || this == SIR

    companion object {
        // NEXT[state][tms] using standard 1149.1 transition table.
        private val N = TapState.values()
        private val NEXT: Array<Array<TapState>> = arrayOf(
            arrayOf(N[1], N[0]),   // TLR  -> RTI / TLR
            arrayOf(N[1], N[2]),   // RTI  -> RTI / SDRS
            arrayOf(N[3], N[8]),   // SDRS -> CDR / SIRS
            arrayOf(N[4], N[5]),   // CDR  -> SDR / E1DR
            arrayOf(N[4], N[5]),   // SDR  -> SDR / E1DR
            arrayOf(N[6], N[7]),   // E1DR -> PDR / UDR
            arrayOf(N[6], N[7]),   // PDR  -> PDR / E2DR
            arrayOf(N[4], N[8]),   // E2DR -> SDR / UDR  (E2DR tms=1 -> UDR)
            arrayOf(N[1], N[0]),   // UDR  -> RTI / TLR
            arrayOf(N[9], N[0]),   // SIRS -> CIR / TLR
            arrayOf(N[10], N[11]), // CIR  -> SIR / E1IR
            arrayOf(N[10], N[11]), // SIR  -> SIR / E1IR
            arrayOf(N[12], N[13]), // E1IR -> PIR / UIR
            arrayOf(N[12], N[13]), // PIR  -> PIR / E2IR
            arrayOf(N[10], N[14]), // E2IR -> SIR / UIR
            arrayOf(N[1], N[0]),   // UIR  -> RTI / TLR
        )
        init {
            // fix index references: build by ordinal explicitly to avoid mistakes
            fun a(s: TapState, t0: TapState, t1: TapState) { NEXT[s.ordinal][0] = t0; NEXT[s.ordinal][1] = t1 }
            a(TLR, RTI, TLR); a(RTI, RTI, SDRS)
            a(SDRS, CDR, SIRS); a(CDR, SDR, E1DR); a(SDR, SDR, E1DR)
            a(E1DR, PDR, UDR); a(PDR, PDR, E2DR); a(E2DR, SDR, UDR); a(UDR, RTI, TLR)
            a(SIRS, CIR, TLR); a(CIR, SIR, E1IR); a(SIR, SIR, E1IR)
            a(E1IR, PIR, UIR); a(PIR, PIR, E2IR); a(E2IR, SIR, UIR); a(UIR, RTI, TLR)
        }

        fun byLabel(label: String): TapState? = N.firstOrNull { it.name == label || it.label == label }
    }
}

/** Sampling polarity: which TCK edge carries valid TMS/TDI, and TDO active level. */
@Serializable
enum class TckEdge { RISING, FALLING }
@Serializable
data class Polarity(val tckEdge: TckEdge = TckEdge.RISING, val tdoActiveHigh: Boolean = true) {
    fun tdo(bit: Int): Int = if (tdoActiveHigh) bit else 1 - bit
    val id get() = "tck=${tckEdge.name.lowercase()},tdo=${if (tdoActiveHigh) "high" else "low"}"
}
