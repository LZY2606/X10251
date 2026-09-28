package gsb.core

/** IEEE 1149.1 TAP controller state machine. */
enum class TapState(val label: String) {
    TLR("Test-Logic-Reset"), RTI("Run-Test/Idle"),
    SDR("Select-DR-Scan"), CDR("Capture-DR"), S1D("Shift-DR"), E1D("Exit1-DR"),
    PDR("Pause-DR"), E2D("Exit2-DR"), UDR("Update-DR"),
    SIR("Select-IR-Scan"), CIR("Capture-IR"), S1I("Shift-IR"), E1I("Exit1-IR"),
    PIR("Pause-IR"), E2I("Exit2-IR"), UIR("Update-IR");

    fun next(tms: Int): TapState = when (this) {
        TLR -> if (tms == 1) TLR else RTI
        RTI -> if (tms == 1) TLR else SDR
        SDR -> if (tms == 1) SIR else CDR
        CDR -> if (tms == 1) E1D else S1D
        S1D -> if (tms == 1) E1D else S1D
        E1D -> if (tms == 1) UDR else PDR
        PDR -> if (tms == 1) E2D else PDR
        E2D -> if (tms == 1) UDR else SDR
        UDR -> if (tms == 1) SDR else RTI
        SIR -> if (tms == 1) TLR else CIR
        CIR -> if (tms == 1) E1I else S1I
        S1I -> if (tms == 1) E1I else S1I
        E1I -> if (tms == 1) UIR else PIR
        PIR -> if (tms == 1) E2I else PIR
        E2I -> if (tms == 1) UIR else SIR
        UIR -> if (tms == 1) SDR else RTI
    }

    val isShift get() = this == S1D || this == S1I
    val isIr get() = this in setOf(SIR, CIR, S1I, E1I, PIR, E2I, UIR)
}

data class TapStep(
    val index: Int,          // index into the sample log (coordinate base: sample ordinal)
    val timePs: Long?,       // normalized time in ps, null when unconvertible
    val state: TapState,
    val tms: Int, val tdi: Int, val tdo: Int,
    val reset: Boolean       // true when this step sits inside a declared reset event window
)
