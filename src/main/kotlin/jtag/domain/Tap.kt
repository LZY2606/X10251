package jtag.domain

/** IEEE 1149.1 TAP controller states. */
enum class TapState(val label: String) {
    TLR("Test-Logic-Reset"), RTI("Run-Test/Idle"),
    SDS("Select-DR-Scan"), SDC("Capture-DR"), SDR("Shift-DR"),
    E1D("Exit1-DR"), PDR("Pause-DR"), E2D("Exit2-DR"), UDR("Update-DR"),
    SIS("Select-IR-Scan"), SIC("Capture-IR"), SIR("Shift-IR"),
    E1I("Exit1-IR"), PIR("Pause-IR"), E2I("Exit2-IR"), UIR("Update-IR")
}

/** Deterministic next-state table: transitions[TapState][tms] = next. */
object TapFsm {
    val transitions: Map<TapState, Array<TapState>> = mapOf(
        TapState.TLR to arrayOf(TapState.RTI, TapState.TLR),
        TapState.RTI to arrayOf(TapState.RTI, TapState.SDS),
        TapState.SDS to arrayOf(TapState.SDC, TapState.SIS),
        TapState.SDC to arrayOf(TapState.SDR, TapState.E1D),
        TapState.SDR to arrayOf(TapState.SDR, TapState.E1D),
        TapState.E1D to arrayOf(TapState.PDR, TapState.UDR),
        TapState.PDR to arrayOf(TapState.PDR, TapState.E2D),
        TapState.E2D to arrayOf(TapState.SDR, TapState.UDR),
        TapState.UDR to arrayOf(TapState.RTI, TapState.SDS),
        TapState.SIS to arrayOf(TapState.SIC, TapState.TLR),
        TapState.SIC to arrayOf(TapState.SIR, TapState.E1I),
        TapState.SIR to arrayOf(TapState.SIR, TapState.E1I),
        TapState.E1I to arrayOf(TapState.PIR, TapState.UIR),
        TapState.PIR to arrayOf(TapState.PIR, TapState.E2I),
        TapState.E2I to arrayOf(TapState.SIR, TapState.UIR),
        TapState.UIR to arrayOf(TapState.RTI, TapState.SDS),
    )

    fun next(state: TapState, tms: Int): TapState = transitions.getValue(state)[if (tms == 0) 0 else 1]

    /** A transition is illegal only when the sampled step cannot be a single FSM hop,
     *  e.g. a clock gap implies a jump. Pure TMS values are always decodable, so
     *  illegality is detected structurally (gap / unstable sample), never silently skipped. */
    fun isReachableInOneStep(from: TapState, to: TapState): Boolean =
        transitions.getValue(from).contains(to)
}
