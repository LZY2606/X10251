package gsb.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

val Json = Json { ignoreUnknownKeys = true; prettyPrint = false }

fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
fun JsonObject.reqStr(k: String): String = str(k) ?: error("missing string field '$k'")
fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
fun JsonObject.arr(k: String): JsonArray? = this[k] as? JsonArray
fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject

/** Raw source kinds, each stored separately with its own revision history. */
enum class SourceKind {
    SAMPLE_EDGES, RESET_EVENTS, IR_LENGTH_CANDIDATES, BSDL_MAPPING, BOARD_REVISION, PARAM, DECISION;

    companion object {
        fun of(name: String): SourceKind = valueOf(name)
    }
}

data class Source(
    val id: String,
    val revision: Int,
    val kind: SourceKind,
    val content: JsonObject,
    val rawText: String,
    val seq: Long = 0,
) {
    val ref: String get() = "${kind.name}:$id@$revision"
}

// ---------------------------------------------------------------- edges

data class Edge(
    val id: String,
    val index: Int,
    val timeNs: Double?,
    val tms: Boolean,
    val tdi: Boolean,
    val tdo: Char, // '0', '1' or 'Z' (tri-state)
    val tmsStable: Boolean = true,
    val resetAsserted: Boolean = false,
    val segment: String = "",
)

data class ResetEvent(val rawAt: String, val atNs: Double?, val type: String, val diagnostic: String?)

data class SampleCapture(
    val sourceId: String,
    val clock: Quantity,
    val startTime: Quantity,
    val sampleEdgeHint: String,
    val dataChangesOn: String,
    val edges: List<Edge>,
    val diagnostics: List<String>,
)

object SampleParser {
    fun parse(src: Source): SampleCapture {
        val c = src.content
        val clock = Units.parse(c.str("clock") ?: "unknown")
        val start = Units.parse(c.str("startTime") ?: "0 ns")
        val period = Units.periodNanos(clock)
        val startNs = Units.toNanos(start)
        val diagnostics = mutableListOf<String>()
        if (period == null) diagnostics.add("clock-unconvertible:${clock.raw}")
        if (startNs == null) diagnostics.add("start-time-unconvertible:${start.raw}")
        val edges = mutableListOf<Edge>()
        var index = 0
        for ((si, segEl) in (c.arr("segments") ?: JsonArray(emptyList())).withIndex()) {
            val seg = segEl as JsonObject
            val name = seg.str("name") ?: "seg-$si"
            val tms = seg.str("tms") ?: ""
            val n = tms.length
            fun padded(key: String, fill: Char): String {
                val s = seg.str(key)
                return when {
                    s == null -> CharArray(n) { fill }.concatToString()
                    s.length == n -> s
                    else -> error("segment '$name' field '$key' length ${s.length} != tms length $n")
                }
            }
            val tdi = padded("tdi", '0')
            val tdo = padded("tdo", 'Z')
            val violationBits = (seg.arr("violations") ?: JsonArray(emptyList()))
                .mapNotNull { (it as? JsonObject)?.int("bit") }.toSet()
            val resetBits = (seg.arr("resets") ?: JsonArray(emptyList()))
                .mapNotNull { (it as? JsonObject)?.int("bit") }.toSet()
            for (i in 0 until n) {
                val tNs = if (period != null && startNs != null) startNs + index * period else null
                edges += Edge(
                    id = "${src.id}#e$index",
                    index = index,
                    timeNs = tNs,
                    tms = tms[i] == '1',
                    tdi = tdi[i] == '1',
                    tdo = tdo[i],
                    tmsStable = i !in violationBits,
                    resetAsserted = i in resetBits,
                    segment = name,
                )
                index++
            }
        }
        return SampleCapture(
            sourceId = src.id,
            clock = clock,
            startTime = start,
            sampleEdgeHint = c.str("sampleEdgeHint") ?: "RISING",
            dataChangesOn = c.str("dataChangesOn") ?: "FALLING",
            edges = edges,
            diagnostics = diagnostics,
        )
    }
}

object ResetParser {
    fun parse(src: Source): List<ResetEvent> =
        (src.content.arr("events") ?: JsonArray(emptyList())).map { el ->
            val o = el as JsonObject
            val rawAt = o.str("at") ?: "unknown"
            val q = Units.parse(rawAt)
            ResetEvent(
                rawAt = rawAt,
                atNs = Units.toNanos(q),
                type = o.str("type") ?: "TRST_ASSERT",
                diagnostic = q.diagnostic,
            )
        }
}

// ------------------------------------------------- ir length candidates

data class IrCandidate(val length: Int, val provenance: String, val sourceRef: String?)

data class DeviceEntry(
    val id: String,
    val position: Int,
    val bsdlRef: String?,
    val candidates: List<IrCandidate>,
)

object IrLengthParser {
    fun parse(src: Source): List<DeviceEntry> =
        (src.content.arr("devices") ?: JsonArray(emptyList())).map { el ->
            val o = el as JsonObject
            DeviceEntry(
                id = o.reqStr("id"),
                position = o.int("position") ?: 0,
                bsdlRef = o.str("bsdl"),
                candidates = (o.arr("candidates") ?: JsonArray(emptyList())).map { ce ->
                    val co = ce as JsonObject
                    IrCandidate(
                        length = co.int("length") ?: error("candidate missing length"),
                        provenance = co.str("provenance") ?: "ASSUMED",
                        sourceRef = co.str("source"),
                    )
                },
            )
        }.sortedBy { it.position }
}

// ------------------------------------------------------- board revision

data class BoardRevision(
    val board: String,
    val revision: String,
    val expectedDevices: List<String>,
    val depopulated: List<String>,
)

object BoardParser {
    fun parse(src: Source): BoardRevision = BoardRevision(
        board = src.content.str("board") ?: "unknown",
        revision = src.content.str("revision") ?: "?",
        expectedDevices = (src.content.arr("expectedDevices") ?: JsonArray(emptyList()))
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        depopulated = (src.content.arr("depopulated") ?: JsonArray(emptyList()))
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
    )
}
