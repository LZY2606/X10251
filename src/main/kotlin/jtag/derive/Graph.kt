package jtag.derive

import jtag.bsdl.CellPinEvidence
import jtag.bsdl.EvidenceBuilder
import jtag.model.*
import jtag.store.*
import jtag.tap.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest

object Nodes {
    const val TRAJECTORY = "tapTrajectory"
    const val SHIFTS = "shiftBitstreams"
    const val IR_ANALYSIS = "irAnalysis"
    const val CHAIN = "deviceChain"
    const val EVIDENCE = "cellPinEvidence"
    val ALL = listOf(TRAJECTORY, SHIFTS, IR_ANALYSIS, CHAIN, EVIDENCE)
}

private fun sha(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

/** Dependency-graph deriver: recomputes only nodes whose inputs changed, explains every decision. */
class Deriver(private val store: Store) {
    val json = Json { encodeDefaults = true }

    inline fun <reified T> decode(payload: String): T = json.decodeFromString<T>(payload)

    fun sourceFingerprint(rows: List<SourceRow>): String =
        sha(rows.sortedBy { it.id }.joinToString("|") { "${it.kind}:${it.id}:${it.payload}" })

    /**
     * Populate derived nodes on [builder]. Sources already added to the builder override
     * parent sources of the same kind-set; everything else is inherited from the parent.
     */
    fun deriveInto(builder: VersionBuilder) {
        val parentId = builder.parent
        val parentSources = parentId?.let { store.sourcesOf(it) } ?: emptyList()
        val newKinds = builder.sources.map { it.kind }.toSet()
        val merged = parentSources.filter { it.kind !in newKinds } + builder.sources
        // also inherit builder events? events are version-local; nothing to inherit.

        val edges = merged.filter { it.kind == SourceKind.EDGE_BUNDLE }.map { decode<EdgeBundle>(it.payload) }.firstOrNull()
        val resets = merged.filter { it.kind == SourceKind.RESET_EVENT }.map { decode<ResetEvent>(it.payload) }
        val irCandidates = merged.filter { it.kind == SourceKind.IR_CANDIDATE }.map { decode<IrLengthCandidate>(it.payload) }
        val bsdl = merged.filter { it.kind == SourceKind.BSDL_MAPPING }.map { decode<BsdlMapping>(it.payload) }
        val board = merged.filter { it.kind == SourceKind.BOARD_REVISION }.map { decode<BoardRevision>(it.payload) }.firstOrNull()
        val polarity = merged.filter { it.kind == SourceKind.PARAM }
            .map { decode<Map<String, String>>(it.payload) }
            .firstOrNull { it["key"] == "polarity" }?.get("value")?.let { ClockPhase.valueOf(it) } ?: ClockPhase.RISING

        fun fp(kind: String) = sourceFingerprint(merged.filter { it.kind == kind })
        fun inherited(node: String, inputs: String): Boolean {
            if (parentId == null) return false
            val p = store.derivedOf(parentId, node) ?: return false
            if (p.inputs != inputs) return false
            builder.addDerived(node, "VALID", p.inputs, p.payload,
                "inputs unchanged (${inputs}); inherited from ${parentId.take(8)} — still valid, no recompute")
            return true
        }

        // node 1: tapTrajectory <- edges + resets + polarity param
        run {
            val inputs = sha("${fp(SourceKind.EDGE_BUNDLE)}|${fp(SourceKind.RESET_EVENT)}|${fp(SourceKind.PARAM)}")
            if (!inherited(Nodes.TRAJECTORY, inputs)) {
                if (edges == null) builder.addDerived(Nodes.TRAJECTORY, "MISSING", inputs, "{}", "no edge bundle present")
                else {
                    val traj = TrajectoryBuilder.build(edges, polarity, resets)
                    val why = buildList {
                        if (SourceKind.EDGE_BUNDLE in newKinds) add("edges changed")
                        if (SourceKind.RESET_EVENT in newKinds) add("reset events changed")
                        if (SourceKind.PARAM in newKinds) add("polarity parameter changed")
                    }.joinToString(", ").ifEmpty { "initial computation" }
                    builder.addDerived(Nodes.TRAJECTORY, "VALID", inputs,
                        json.encodeToString(TapTrajectory.serializer(), traj), "recomputed: $why")
                }
            }
        }
        val traj = builder.derived.find { it.node == Nodes.TRAJECTORY }?.let {
            if (it.status == "VALID") decode<TapTrajectory>(it.payload) else null
        }

        // node 2: shiftBitstreams <- trajectory
        run {
            val inputs = sha("traj:${builder.derived.find { it.node == Nodes.TRAJECTORY }?.let { sha(it.payload) }}")
            if (!inherited(Nodes.SHIFTS, inputs)) {
                val shifts = traj?.let { ShiftExtractor.extract(it) } ?: emptyList()
                builder.addDerived(Nodes.SHIFTS, if (traj != null) "VALID" else "MISSING", inputs,
                    json.encodeToString(ListSerializer(ShiftBitstream.serializer()), shifts),
                    if (traj != null) "recomputed: trajectory changed" else "no trajectory available")
            }
        }
        val shifts = decode<List<ShiftBitstream>>(builder.derived.first { it.node == Nodes.SHIFTS }.payload)

        // node 3: irAnalysis <- shifts + ir candidates
        run {
            val inputs = sha("shifts:${sha(json.encodeToString(ListSerializer(ShiftBitstream.serializer()), shifts))}|ir:${fp(SourceKind.IR_CANDIDATE)}")
            if (!inherited(Nodes.IR_ANALYSIS, inputs)) {
                val idcodeObserved = shifts.any { s -> s.kind == ShiftKind.DR && s.tdoBits.take(33).count { it } > 0 }
                val analysis = IrLengthAnalyzer.analyze(shifts, irCandidates, idcodeObserved)
                val why = if (SourceKind.IR_CANDIDATE in newKinds) "IR candidate set changed" else "shift bitstreams changed"
                builder.addDerived(Nodes.IR_ANALYSIS, "VALID", inputs,
                    json.encodeToString(IrAnalysis.serializer(), analysis), "recomputed: $why")
            }
        }

        // node 4: deviceChain <- shifts + board revision
        run {
            val inputs = sha("shifts:${sha(json.encodeToString(ListSerializer(ShiftBitstream.serializer()), shifts))}|board:${fp(SourceKind.BOARD_REVISION)}")
            if (!inherited(Nodes.CHAIN, inputs)) {
                val chain = if (board != null) ChainAnalyzer.analyze(shifts, board)
                else ChainAnalysis(emptyList(), emptyList(), 0, emptyList(), listOf("no board revision present"))
                val why = if (SourceKind.BOARD_REVISION in newKinds) "board revision changed" else "shift bitstreams changed"
                builder.addDerived(Nodes.CHAIN, "VALID", inputs,
                    json.encodeToString(ChainAnalysis.serializer(), chain), "recomputed: $why")
            }
        }
        val chain = decode<ChainAnalysis>(builder.derived.first { it.node == Nodes.CHAIN }.payload)

        // node 5: cellPinEvidence <- chain + bsdl mappings + shifts
        run {
            val inputs = sha("chain:${sha(json.encodeToString(ChainAnalysis.serializer(), chain))}|bsdl:${fp(SourceKind.BSDL_MAPPING)}")
            if (!inherited(Nodes.EVIDENCE, inputs)) {
                val mappings = bsdl.associateBy { it.deviceRef }
                val evidence = EvidenceBuilder.build(chain, shifts, mappings)
                val why = if (SourceKind.BSDL_MAPPING in newKinds) "BSDL mapping version changed" else "device chain changed"
                builder.addDerived(Nodes.EVIDENCE, "VALID", inputs,
                    json.encodeToString(ListSerializer(CellPinEvidence.serializer()), evidence), "recomputed: $why")
            }
        }
    }
}
