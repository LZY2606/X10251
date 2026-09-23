package jtag

import kotlinx.serialization.json.*

/**
 * Dependency-graph propagation. Derived nodes declare their source inputs;
 * a recompute only touches nodes whose inputs actually changed, and every
 * node can explain why it is still valid or why it must be recomputed.
 */
class Deriver(val store: Store, val now: () -> String = { java.time.Instant.now().toString() }) {

    companion object {
        /** derived node -> source/derived dependencies */
        val DEPS: Map<String, List<String>> = mapOf(
            "tap_replay" to listOf("samples", "resets"),
            "chain_assembly" to listOf("ir_candidates", "board_revisions", "tap_replay"),
            "cell_evidence" to listOf("bsdl_maps", "chain_assembly")
        )
        val ORDER = listOf("tap_replay", "chain_assembly", "cell_evidence")
    }

    data class Report(
        val recomputed: List<String>,
        val keptValid: List<Pair<String, String>>,   // node -> reason
        val events: List<JEvent>,
        val diagnostics: List<Diagnostic>,
        val derivations: Map<String, String>          // node -> derivation id
    )

    private fun currentInputs(node: String): Map<String, String?> =
        DEPS[node]!!.associateWith { dep ->
            if (dep in DEPS) latestDerivation(dep)?.get("id") else store.currentVersion(dep)
        }

    fun latestDerivation(kind: String): Map<String, String?>? =
        store.db.query(
            "SELECT id,inputs,outputs,valid_reason,stale_reason,status FROM derivations WHERE kind=? AND status IN ('complete','stale') ORDER BY rowid DESC LIMIT 1",
            kind) { rs ->
            mapOf("id" to rs.getString(1), "inputs" to rs.getString(2), "outputs" to rs.getString(3),
                "valid_reason" to rs.getString(4), "stale_reason" to rs.getString(5), "status" to rs.getString(6))
        }

    /** Why a node is still valid, or why it needs recompute. */
    fun validity(node: String): Pair<Boolean, String> {
        val der = latestDerivation(node) ?: return false to "no derivation exists yet"
        if (der["status"] == "stale") return false to "marked stale: ${der["stale_reason"]}"
        val recorded = Json.parseToJsonElement(der["inputs"] ?: "{}").jsonObject
        val now = currentInputs(node)
        val changed = now.filter { (k, v) -> recorded[k]?.jsonPrimitive?.contentOrNull != v }
        return if (changed.isEmpty()) true to "inputs unchanged since derivation ${der["id"]}"
        else false to "inputs changed: ${changed.keys.joinToString(",")}"
    }

    /** Recompute only what is affected; everything else keeps its version with a reason. */
    fun recompute(edge: SampleEdge = SampleEdge.RISING, force: Boolean = false): Report {
        val recomputed = mutableListOf<String>()
        val kept = mutableListOf<Pair<String, String>>()
        val allEvents = mutableListOf<JEvent>()
        val allDiags = mutableListOf<Diagnostic>()
        val derIds = mutableMapOf<String, String>()

        for (node in ORDER) {
            val (valid, reason) = validity(node)
            if (valid && !force) { kept += node to reason; latestDerivation(node)?.get("id")?.let { derIds[node] = it }; continue }
            when (node) {
                "tap_replay" -> {
                    val sv = store.currentVersion("samples")!!
                    val rv = store.currentVersion("resets")!!
                    val samples = store.loadSamples(sv)
                    val resets = store.loadResets(rv)
                    val derId = store.beginDerivation(node, JsonObject(mapOf("samples" to JsonPrimitive(sv), "resets" to JsonPrimitive(rv))).toString())
                    val res = TapReplay.replay(samples, resets, edge, derId, now())
                    store.writeTap(derId, res.steps, res.shifts)
                    res.events.forEach { store.addEvent(it) }
                    res.diagnostics.forEach { store.addDiagnostic(it) }
                    store.completeDerivation(derId,
                        """{"steps":${res.steps.size},"shifts":${res.shifts.size},"events":${res.events.size}}""",
                        "replayed ${samples.size} samples on ${edge.name.lowercase()} edge")
                    allEvents += res.events; allDiags += res.diagnostics
                    recomputed += node; derIds[node] = derId
                }
                "chain_assembly" -> {
                    val iv = store.currentVersion("ir_candidates")!!
                    val bv = store.currentVersion("board_revisions")!!
                    val tapDer = derIds["tap_replay"] ?: latestDerivation("tap_replay")!!["id"]!!
                    val boards = store.loadBoards(bv)
                    val spec = boards.firstOrNull()?.payload?.let { Json.parseToJsonElement(it).jsonObject }
                    val devices = spec?.get("devices")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                    val populated = spec?.get("populated")?.jsonObject?.mapValues { it.value.jsonPrimitive.boolean } ?: emptyMap()
                    val cands = store.loadIrCandidates(iv).groupBy { it.position }
                    val observedBypass = store.loadShifts(tapDer).filter { it.kind == "DR" }
                        .maxOfOrNull { it.bitsIn.length } ?: 0
                    val derId = store.beginDerivation(node, JsonObject(mapOf(
                        "ir_candidates" to JsonPrimitive(iv), "board_revisions" to JsonPrimitive(bv),
                        "tap_replay" to JsonPrimitive(tapDer))).toString())
                    val res = ChainAssembly.assemble(devices, populated, cands, observedBypass, derId, now())
                    store.writeChain(derId, res.devices, res.offsets)
                    res.events.forEach { store.addEvent(it) }
                    store.completeDerivation(derId,
                        """{"devices":${res.devices.size},"offsets":${res.offsets.size},"observedBypassBits":$observedBypass}""",
                        "assembled from ${devices.size} expected devices, observed $observedBypass bypass bits")
                    allEvents += res.events
                    recomputed += node; derIds[node] = derId
                }
                "cell_evidence" -> {
                    val mv = store.currentVersion("bsdl_maps")!!
                    val chainDer = derIds["chain_assembly"] ?: latestDerivation("chain_assembly")!!["id"]!!
                    val derId = store.beginDerivation(node, JsonObject(mapOf(
                        "bsdl_maps" to JsonPrimitive(mv), "chain_assembly" to JsonPrimitive(chainDer))).toString())
                    val cells = mutableListOf<CellPin>()
                    store.loadBsdlMaps(mv).forEach { row ->
                        val pkg = BsdlParser.parse(row.payload, row.bsdlVersion)
                        pkg.boundary.forEach { bc ->
                            cells += CellPin(newId("cell"), row.device, bc.cell, bc.port,
                                pkg.pinMap[bc.port] ?: "UNMAPPED", row.bsdlVersion)
                        }
                    }
                    store.writeCells(derId, cells)
                    store.completeDerivation(derId, """{"cells":${cells.size}}""",
                        "mapped ${cells.size} boundary cells from BSDL map version $mv")
                    recomputed += node; derIds[node] = derId
                }
            }
        }
        return Report(recomputed, kept, allEvents, allDiags, derIds)
    }
}
