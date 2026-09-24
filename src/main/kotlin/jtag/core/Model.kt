package jtag.core

import kotlinx.serialization.Serializable

/**
 * A domain quantity that always carries its unit and coordinate basis.
 * Raw representation is preserved at import; normalization happens only
 * in the derived layer. Unconvertible values stay in place with diagnostics.
 */
@Serializable
data class Quantity(
    val raw: String,
    val unit: String? = null,   // e.g. "ns", "us", "samples"
    val basis: String? = null,  // e.g. "tck-rising-edge", "tck-falling-edge", "wall-clock"
)

@Serializable
data class Diagnostic(val code: String, val message: String, val subject: String)

sealed interface NormResult {
    @Serializable data class Ok(val ns: Double) : NormResult
    @Serializable data class Unconvertible(val original: Quantity, val diagnostic: Diagnostic) : NormResult
}

/** Normalize a time quantity to nanoseconds. Unknown units are NOT dropped. */
fun normalizeTime(q: Quantity, subject: String): NormResult {
    val v = q.raw.toDoubleOrNull()
        ?: return NormResult.Unconvertible(q, Diagnostic("UNPARSEABLE_VALUE", "cannot parse '${q.raw}' as number", subject))
    return when (q.unit) {
        "ns" -> NormResult.Ok(v)
        "us" -> NormResult.Ok(v * 1000.0)
        "ms" -> NormResult.Ok(v * 1_000_000.0)
        "samples" -> NormResult.Unconvertible(q, Diagnostic("NO_CLOCK_RATE", "sample-index basis without clock rate cannot become ns", subject))
        else -> NormResult.Unconvertible(q, Diagnostic("UNKNOWN_UNIT", "unit '${q.unit}' has no conversion to ns", subject))
    }
}

/** Raw imported sample edge, exactly as delivered by the logic analyzer. */
@Serializable
data class SampleEdge(
    val edgeIndex: Int,                 // coordinate basis: TCK edge ordinal
    val time: Quantity,                 // raw timestamp with unit/basis
    val tms: Int, val tdi: Int, val tdo: Int,
    val edgeBasis: String = "rising",   // rising | falling
    val polarity: String = "normal",    // normal | inverted
    val expectedState: String? = null,  // optional protocol-analyzer annotation
)

/** Normalized sample: canonical rising-edge, non-inverted, time in ns when convertible. */
@Serializable
data class NormSample(
    val edgeIndex: Int,
    val timeNs: Double?,
    val tms: Int, val tdi: Int, val tdo: Int,
    val expectedState: String? = null,
    val notes: List<String> = emptyList(),
)

@Serializable
data class NormalizeOutput(val samples: List<NormSample>, val diagnostics: List<Diagnostic>)

/** Derived-layer normalization. Raw edges are never mutated. */
fun normalizeSamples(edges: List<SampleEdge>): NormalizeOutput {
    val diags = mutableListOf<Diagnostic>()
    val out = edges.sortedBy { it.edgeIndex }.map { e ->
        val notes = mutableListOf<String>()
        var tms = e.tms; var tdi = e.tdi; var tdo = e.tdo
        if (e.polarity == "inverted") {
            tms = 1 - tms; tdi = 1 - tdi; tdo = 1 - tdo
            notes.add("polarity:inverted->normal")
        } else if (e.polarity != "normal") {
            diags.add(Diagnostic("UNKNOWN_POLARITY", "polarity '${e.polarity}' not normalizable; bits kept as-is", "edge#${e.edgeIndex}"))
        }
        if (e.edgeBasis == "falling") notes.add("edge:falling->rising(+half-cycle phase shift)")
        else if (e.edgeBasis != "rising")
            diags.add(Diagnostic("UNKNOWN_EDGE_BASIS", "edge basis '${e.edgeBasis}' unknown", "edge#${e.edgeIndex}"))
        val t = normalizeTime(e.time, "edge#${e.edgeIndex}")
        if (t is NormResult.Unconvertible) diags.add(t.diagnostic)
        NormSample(e.edgeIndex, (t as? NormResult.Ok)?.ns, tms, tdi, tdo, e.expectedState, notes)
    }
    return NormalizeOutput(out, diags)
}
