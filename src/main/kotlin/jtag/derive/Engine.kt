package jtag.derive

import jtag.core.*
import jtag.store.DerivedData
import jtag.store.Store
import jtag.store.VersionRow

/**
 * Dependency-graph derivation. Source nodes (edges / resets / ir_candidates /
 * bsdl / board) and the params node feed derived nodes; a change propagates
 * only to the downstream closure, everything else is reused from the parent
 * version with a REUSED log entry explaining why it is still valid.
 */
class Engine(private val store: Store) {

    data class Params(val polarity: SamplingEdge = SamplingEdge.RISING) {
        fun encode() = "polarity=$polarity"
        companion object {
            fun decode(s: String?) =
                Params(if (s?.contains("FALLING") == true) SamplingEdge.FALLING else SamplingEdge.RISING)
        }
    }

    companion object {
        val SOURCE_NODES = listOf("edges", "resets", "ir_candidates", "bsdl", "board")
        val DERIVED_NODES = listOf("tap_trace", "shift_bits", "chain", "ir_length", "cell_pin")
        val DOWNSTREAM: Map<String, List<String>> = mapOf(
            "edges" to listOf("tap_trace"),
            "resets" to listOf("tap_trace"),
            "params" to listOf("tap_trace"),
            "ir_candidates" to listOf("ir_length"),
            "bsdl" to listOf("chain", "ir_length", "cell_pin"),
            "board" to listOf("chain", "cell_pin"),
            "tap_trace" to listOf("shift_bits"),
            "shift_bits" to listOf("chain", "ir_length", "cell_pin"),
            "chain" to listOf("ir_length", "cell_pin"),
            "ir_length" to listOf("cell_pin"),
        )

        fun nodeOfEvent(kind: String) = when {
            kind == "MID_SCAN_RESET" || kind == "ILLEGAL_TRANSITION" -> "tap_trace"
            kind.startsWith("CHAIN_") -> "chain"
            kind.startsWith("IR_") -> "ir_length"
            else -> "cell_pin"
        }
    }

    fun derive(label: String, author: String, inputs: Map<String, String>, params: Params): String {
        val parent = store.currentVersionId()
        val parentInputs = parent?.let { store.versionInputs(it) } ?: emptyMap()
        val parentParams = parent?.let { store.version(it)?.params }

        val changedSources = SOURCE_NODES.filter { inputs[it] != parentInputs[it] }.toMutableSet()
        if (parent == null) changedSources += SOURCE_NODES
        if (parentParams != null && parentParams != params.encode()) changedSources += "params"

        val dirty = mutableSetOf<String>()
        fun mark(n: String) { if (dirty.add(n)) DOWNSTREAM[n]?.forEach(::mark) }
        changedSources.forEach(::mark)
        if (parent == null) dirty += DERIVED_NODES

        val log = mutableListOf<RecomputeEntry>()
        val events = mutableListOf<JudgeEvent>()

        // --- tap_trace / shift_bits ---
        val steps: List<TapStep>
        val segments: List<ShiftSegment>
        if ("tap_trace" in dirty) {
            val why = changedSources.filter { it in listOf("edges", "resets", "params") }
            val replay = Replayer(
                store.loadEdges(inputs.getValue("edges")),
                inputs["resets"]?.let { store.loadResets(it) } ?: emptyList(),
                params.polarity
            ).run()
            steps = replay.steps; segments = replay.segments
            events += replay.events
            log += RecomputeEntry("tap_trace", "RECOMPUTED",
                "inputs changed: ${why.joinToString(", ").ifEmpty { "initial derivation" }}")
            log += RecomputeEntry("shift_bits", "RECOMPUTED", "tap_trace recomputed")
        } else {
            steps = store.tapSteps(parent!!)
            segments = store.shifts(parent)
            log += RecomputeEntry("tap_trace", "REUSED",
                "edges/resets/params unchanged since $parent; trajectory still valid")
            log += RecomputeEntry("shift_bits", "REUSED", "tap_trace unchanged; segments still valid")
        }

        // --- chain / ir_length / cell_pin ---
        val devices: List<ChainDevice>
        val offsets: List<OffsetCandidate>
        val cells: List<CellPin>
        if (listOf("chain", "ir_length", "cell_pin").any { it in dirty }) {
            val bsdlImports = inputs.getValue("bsdl").split("+")
            val (parts, _) = store.loadBsdl(bsdlImports.first())
            val bsdl = mutableMapOf<String, BsdlPart>()
            for (imp in bsdlImports) {
                val (ps, _) = store.loadBsdl(imp)
                ps.forEach { bsdl[it.device] = it }
            }
            val analysis = ChainAnalyzer(
                segments,
                store.loadBoard(inputs.getValue("board")),
                bsdl,
                inputs["ir_candidates"]?.let { store.loadIrCandidates(it) } ?: emptyList()
            ).analyze()
            val byNode = analysis.events.groupBy { nodeOfEvent(it.kind) }
            if ("chain" in dirty) {
                devices = analysis.devices
                events += byNode["chain"].orEmpty()
                log += RecomputeEntry("chain", "RECOMPUTED",
                    "inputs changed: ${changedSources.filter { it in listOf("shift_bits", "board", "bsdl", "edges", "resets", "params") }.joinToString(", ").ifEmpty { "upstream recompute" }}")
            } else {
                devices = store.devices(parent!!)
                log += RecomputeEntry("chain", "REUSED", "shift_bits/board/bsdl unchanged; chain still valid")
            }
            if ("ir_length" in dirty) {
                events += byNode["ir_length"].orEmpty()
                log += RecomputeEntry("ir_length", "RECOMPUTED", "ir_candidates/chain inputs changed")
            } else {
                log += RecomputeEntry("ir_length", "REUSED", "ir_candidates and chain unchanged; still valid")
            }
            if ("cell_pin" in dirty) {
                offsets = analysis.offsets; cells = analysis.cells
                events += byNode["cell_pin"].orEmpty()
                log += RecomputeEntry("cell_pin", "RECOMPUTED", "chain/bsdl/board inputs changed")
            } else {
                offsets = store.offsets(parent!!); cells = store.cellPins(parent)
                log += RecomputeEntry("cell_pin", "REUSED", "chain/bsdl/board unchanged; mapping still valid")
            }
        } else {
            devices = store.devices(parent!!)
            offsets = store.offsets(parent)
            cells = store.cellPins(parent)
            log += RecomputeEntry("chain", "REUSED", "no chain input changed")
            log += RecomputeEntry("ir_length", "REUSED", "no ir_length input changed")
            log += RecomputeEntry("cell_pin", "REUSED", "no cell_pin input changed")
        }

        val vid = newId("ver")
        store.beginVersion(VersionRow(vid, parent, label, author, params.encode(), "BUILDING"), inputs)
        store.writeDerived(vid, DerivedData(steps, segments, devices, offsets, cells, events, log))
        store.publishVersion(vid)
        return vid
    }
}
