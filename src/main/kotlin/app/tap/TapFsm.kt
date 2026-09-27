package app.tap

/**
 * IEEE 1149.1 TAP controller state machine.
 *
 * The transition table is the complete 16-state graph.  A TMS value that has no
 * edge from the current state is an *illegal transition*: it is recorded as an
 * error, never silently skipped.  Five consecutive TMS=1 TCK edges from any
 * non-TestLogicReset state reach TestLogicReset ("mid reset"); a reset while
 * Shift-IR/Shift-DR is active is a mid-shift reset and truncates the stream.
 */
object TapFsm {

    val states = listOf(
        "TestLogicReset", "RunTestIdle",
        "SelectDRScan", "CaptureDR", "ShiftDR", "Exit1DR", "PauseDR", "Exit2DR", "UpdateDR",
        "SelectIRScan", "CaptureIR", "ShiftIR", "Exit1IR", "PauseIR", "Exit2IR", "UpdateIR",
    )

    private val next: Map<Pair<String, Int>, String> = buildMap {
        put("TestLogicReset" to 0, "RunTestIdle");       put("TestLogicReset" to 1, "TestLogicReset")
        put("RunTestIdle" to 0, "RunTestIdle");         put("RunTestIdle" to 1, "SelectDRScan")
        put("SelectDRScan" to 0, "CaptureDR");          put("SelectDRScan" to 1, "SelectIRScan")
        put("CaptureDR" to 0, "ShiftDR");               put("CaptureDR" to 1, "Exit1DR")
        put("ShiftDR" to 0, "ShiftDR");                 put("ShiftDR" to 1, "Exit1DR")
        put("Exit1DR" to 0, "PauseDR");                 put("Exit1DR" to 1, "UpdateDR")
        put("PauseDR" to 0, "PauseDR");                 put("PauseDR" to 1, "Exit2DR")
        put("Exit2DR" to 0, "ShiftDR");                 put("Exit2DR" to 1, "UpdateDR")
        put("UpdateDR" to 0, "RunTestIdle");            put("UpdateDR" to 1, "SelectDRScan")
        put("SelectIRScan" to 0, "CaptureIR");          put("SelectIRScan" to 1, "TestLogicReset")
        put("CaptureIR" to 0, "ShiftIR");               put("CaptureIR" to 1, "Exit1IR")
        put("ShiftIR" to 0, "ShiftIR");                 put("ShiftIR" to 1, "Exit1IR")
        put("Exit1IR" to 0, "PauseIR");                 put("Exit1IR" to 1, "UpdateIR")
        put("PauseIR" to 0, "PauseIR");                 put("PauseIR" to 1, "Exit2IR")
        put("Exit2IR" to 0, "ShiftIR");                 put("Exit2IR" to 1, "UpdateIR")
        put("UpdateIR" to 0, "RunTestIdle");            put("UpdateIR" to 1, "SelectDRScan")
    }

    fun transition(from: String, tms: Int): String? = next[from to tms]
}
