package jtag.tap

import jtag.derive.JudgmentEvent
import jtag.derive.Rules
import java.util.UUID

data class TraceStep(
    val ord: Int,
    val edgeId: String,
    val from: TapState,
    val to: TapState,
    val tms: Int, val tdi: Int, val tdo: Int,
    val observed: TapState?,
    val diverged: Boolean
)

data class TraceResult(
    val steps: List<TraceStep>,
    val events: List<JudgmentEvent>,
    /** Ids of edges where a shift capture was aborted by reset; partial bits preserved. */
    val abortedShiftRanges: List<IntRange>
)

/**
 * Rebuilds the TAP state trajectory from sampled TCK rising edges.
 * Illegal transitions and mid-scan resets are surfaced as events and kept
 * in the trajectory; nothing is silently skipped.
 */
class TapTracer(
    private val edges: List<SampleEdge>,
    private val resets: List<ResetEvent>,
    private val initial: TapState = TapState.TEST_LOGIC_RESET
) {
    fun run(): TraceResult {
        val steps = mutableListOf<TraceStep>()
        val events = mutableListOf<JudgmentEvent>()
        val aborted = mutableListOf<IntRange>()
        var state = initial
        var shiftStart: Int? = null
        var divergenceCount = 0

        val resetByIndex = resets.associateBy { it.atEdgeIndex }

        edges.forEachIndexed { i, e ->
            resetByIndex[e.ord]?.let { r ->
                if (shiftStart != null && state.isShift) {
                    aborted += shiftStart!!..i
                    events += JudgmentEvent(
                        id = "ev-" + UUID.randomUUID(),
                        kind = "MID_SCAN_RESET",
                        severity = "ERROR",
                        message = "reset '${r.kind}' asserted at edge ${e.ord} while ${state} shift in progress; partial capture preserved",
                        subjects = listOf(e.id, r.id),
                        evidence = mapOf("resetKind" to r.kind, "shiftStartEdge" to shiftStart.toString(), "abortEdge" to e.ord.toString()),
                        basis = Rules.MID_SCAN_RESET
                    )
                }
                state = TapState.TEST_LOGIC_RESET
                shiftStart = null
            }

            val from = state
            val to = from.next(e.tms)
            if (!TapFsm.isLegal(from, to)) {
                events += JudgmentEvent(
                    id = "ev-" + UUID.randomUUID(),
                    kind = "ILLEGAL_TRANSITION",
                    severity = "ERROR",
                    message = "illegal TAP transition $from -> $to at edge ${e.ord}",
                    subjects = listOf(e.id),
                    evidence = mapOf("from" to from.name, "to" to to.name, "tms" to e.tms.toString()),
                    basis = Rules.ILLEGAL_TRANSITION
                )
            }

            val observed = e.observedState?.let { label ->
                TapState.entries.firstOrNull { it.name == label }
            }
            var diverged = false
            if (observed != null && observed != to) {
                diverged = true
                divergenceCount++
                val illegalJump = !TapFsm.isLegal(from, observed)
                events += JudgmentEvent(
                    id = "ev-" + UUID.randomUUID(),
                    kind = "ILLEGAL_TRANSITION",
                    severity = "ERROR",
                    message = "sampled trajectory diverges from observed decode at edge ${e.ord}: computed $to, observed $observed" +
                        (if (illegalJump) " (observed jump $from -> $observed is not a legal TAP edge)" else ""),
                    subjects = listOf(e.id),
                    evidence = mapOf(
                        "computed" to to.name, "observed" to observed.name,
                        "observedJumpLegal" to (!illegalJump).toString(), "polarity" to e.polarity.name
                    ),
                    basis = Rules.ILLEGAL_TRANSITION
                )
            }

            if (from.isShift && to == TapState.TEST_LOGIC_RESET && shiftStart != null) {
                aborted += shiftStart!!..i
                events += JudgmentEvent(
                    id = "ev-" + UUID.randomUUID(),
                    kind = "MID_SCAN_RESET",
                    severity = "ERROR",
                    message = "TMS-driven reset reached at edge ${e.ord} during ${from}; partial capture preserved",
                    subjects = listOf(e.id),
                    evidence = mapOf("shiftStartEdge" to shiftStart.toString(), "abortEdge" to e.ord.toString()),
                    basis = Rules.MID_SCAN_RESET
                )
                shiftStart = null
            }
            if (to.isShift && shiftStart == null) shiftStart = i
            if (!to.isShift && !to.isPause && from.isShift) shiftStart = null

            steps += TraceStep(i, e.id, from, to, e.tms, e.tdi, e.tdo, observed, diverged)
            state = to
        }

        if (edges.any { it.polarity == Polarity.INVERTED } && divergenceCount > 0) {
            events += JudgmentEvent(
                id = "ev-" + UUID.randomUUID(),
                kind = "POLARITY_MISMATCH",
                severity = "WARNING",
                message = "inverted sampling polarity produced $divergenceCount divergences from observed decode; decode not silently flipped",
                subjects = edges.filter { it.polarity == Polarity.INVERTED }.map { it.id },
                evidence = mapOf("divergences" to divergenceCount.toString()),
                basis = Rules.POLARITY_CHECK
            )
        }
        return TraceResult(steps, events, aborted)
    }
}
