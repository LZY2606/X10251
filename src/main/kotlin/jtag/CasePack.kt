package jtag

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ArchiveCase(
    val name: String, val mutatedSource: String, val fixture: String,
    val resetFixture: String? = null, val paramsOverride: Map<String, String>? = null,
    val compareTo: String? = null,
    val expectEvents: List<String>,
    val judgment: Map<String, String>,
    val accepted: Map<String, String>,
    val rejected: List<Map<String, String>>
)

@Serializable
data class Archive(
    val id: String, val title: String,
    val baseline: Map<String, kotlinx.serialization.json.JsonElement>,
    val cases: List<ArchiveCase>
)

object CasePack {
    private val json = Json { ignoreUnknownKeys = true }

    fun fixture(name: String): String =
        requireNotNull(CasePack::class.java.getResource("/casepack/$name")) { "fixture $name missing" }.readText()

    fun archive(): Archive = json.decodeFromString(fixture("counterexamples.json"))

    /** Seed the five separately-stored baseline source kinds. */
    fun seedBaselineSources(store: Store) {
        store.putSource("edges-base", "sample_edges", "基线采样边沿", fixture("sample_edges.json"))
        store.putSource("resets-base", "reset_events", "基线复位事件", fixture("reset_events.json"))
        store.putSource("ircand-base", "ir_length_candidates", "基线 IR 长度候选", fixture("ir_length_candidates.json"))
        store.putSource("bsdl-u1-v1", "bsdl_mapping", "u1 BSDL 映射 v1.0", fixture("bsdl_mapping_u1_v1.json"))
        store.putSource("bsdl-u1-v2", "bsdl_mapping", "u1 BSDL 映射 v1.1", fixture("bsdl_mapping_u1_v2.json"))
        store.putSource("bsdl-u3-v2", "bsdl_mapping", "u3 BSDL 映射 v2.0", fixture("bsdl_mapping_u3_v2.json"))
        store.putSource("board-revA", "board_revision", "板卡修订 A", fixture("board_revision.json"))
    }

    fun baselineParams() = DeriveParams(
        sampleSetId = "edges-base", resetSetId = "resets-base", irCandidateSetId = "ircand-base",
        bsdlMappingIds = listOf("bsdl-u1-v1", "bsdl-u3-v2"), boardRevisionId = "board-revA",
        label = "基线推导")

    fun loadSources(store: Store, params: DeriveParams):
            Pair<Engine, Unit> {
        val samples = json.decodeFromString(SampleEdgeSet.serializer(), store.source(params.sampleSetId)!!.content)
        val resets = json.decodeFromString(ResetEventSet.serializer(), store.source(params.resetSetId)!!.content)
        val irc = json.decodeFromString(IrLengthCandidateSet.serializer(), store.source(params.irCandidateSetId)!!.content)
        val maps = params.bsdlMappingIds.map { json.decodeFromString(BsdlMapping.serializer(), store.source(it)!!.content) }
        val board = json.decodeFromString(BoardRevision.serializer(), store.source(params.boardRevisionId)!!.content)
        return Pair(Engine(samples, resets, irc, maps, board, params), Unit)
    }

    /** Run a derivation; compare stage input hashes with parent to explain
     *  which results remain valid and which must be recomputed. */
    fun derive(store: Store, params: DeriveParams, parentId: String?, label: String,
               makeCurrent: Boolean = true, id: String? = null): String {
        val (engine, _) = loadSources(store, params)
        val result = engine.run()
        val parentHashes = parentId?.let { store.stageHashesOf(it) } ?: emptyMap()
        val propEvents = mutableListOf<JudgeEvent>()
        if (parentHashes.isNotEmpty()) {
            val order = listOf("NORMALIZE", "TRACE", "SEGMENTS", "CHAIN", "EVIDENCE")
            var dirty = false
            for (st in order) {
                val same = parentHashes[st] != null && parentHashes[st] == engine.stageHash(st) && !dirty
                if (same) propEvents += JudgeEvent(EventKinds.STAGE_REUSED, "INFO",
                    "阶段 $st 输入未变，结果仍有效，跳过重算", stage = st)
                else {
                    dirty = true
                    propEvents += JudgeEvent(EventKinds.STAGE_RECOMPUTED, "INFO",
                        "阶段 $st 输入变化（或上游失效），需要重算", stage = st)
                }
            }
        }
        val did = id ?: store.newId("der")
        store.saveDerivation(did, parentId, label, params, result, engine.events + propEvents,
            engine.inputHashes(), makeCurrent)
        return did
    }

    /** Seed the counterexample archive: baseline plus one-source mutations,
     *  keeping both accepted and rejected outcomes as machine-readable events. */
    fun seedArchive(store: Store): Map<String, String> {
        seedBaselineSources(store)
        val baseId = derive(store, baselineParams(), null, "基线推导")
        val arch = archive()
        val out = mutableMapOf("baseline" to baseId)
        for (case in arch.cases) {
            val p = baselineParams()
            val mutated = when (case.mutatedSource) {
                "sample_edges" -> {
                    val content = fixture(case.fixture)
                    val sid = json.decodeFromString(SampleEdgeSet.serializer(), content).id
                    store.putSource(sid, "sample_edges", "反例:${case.name}", content)
                    case.resetFixture?.let {
                        val rc = fixture(it)
                        val rid = json.decodeFromString(ResetEventSet.serializer(), rc).id
                        store.putSource(rid, "reset_events", "反例:${case.name} 复位", rc)
                        p.copy(sampleSetId = sid, resetSetId = rid,
                            polarityOverride = case.paramsOverride?.get("polarityOverride"))
                    } ?: p.copy(sampleSetId = sid,
                        polarityOverride = case.paramsOverride?.get("polarityOverride"))
                }
                "ir_length_candidates" -> {
                    val content = fixture(case.fixture)
                    val sid = json.decodeFromString(IrLengthCandidateSet.serializer(), content).id
                    store.putSource(sid, "ir_length_candidates", "反例:${case.name}", content)
                    p.copy(irCandidateSetId = sid)
                }
                "board_revision" -> {
                    val content = fixture(case.fixture)
                    val sid = json.decodeFromString(BoardRevision.serializer(), content).id
                    store.putSource(sid, "board_revision", "反例:${case.name}", content)
                    p.copy(boardRevisionId = sid)
                }
                "bsdl_mapping" -> p.copy(bsdlMappingIds = listOf(
                    json.decodeFromString(BsdlMapping.serializer(), fixture(case.fixture)).id, "bsdl-u3-v2"))
                else -> p
            }
            if (case.mutatedSource == "bsdl_mapping") {
                val content = fixture(case.fixture)
                val m = json.decodeFromString(BsdlMapping.serializer(), content)
                store.putSource(m.id, "bsdl_mapping", "反例:${case.name}", content)
            }
            val did = derive(store, mutated.copy(label = "反例:${case.name}"), baseId,
                "反例:${case.name}", makeCurrent = false)
            // mapping-version case additionally records the version comparison events
            if (case.compareTo != null) {
                val a = json.decodeFromString(BsdlMapping.serializer(), store.source(case.compareTo)!!.content)
                val b = json.decodeFromString(BsdlMapping.serializer(), fixture(case.fixture))
                val evs = compareMappings(a, b)
                val row = store.derivation(did)!!
                val pp = json.decodeFromString(DeriveParams.serializer(), row.params)
                val rr = json.decodeFromString(DerivationResult.serializer(), row.result!!)
                store.saveDerivation(did + "-cmp", baseId, "反例:${case.name}(映射比较)", pp, rr,
                    store.eventsOf(did).map { it.second } + evs, emptyMap(), makeCurrent = false)
            }
            // accepted + rejected outcomes both preserved
            store.saveDecision(did, "ACCEPT", true, json.encodeToString(
                kotlinx.serialization.builtins.MapSerializer(
                    kotlinx.serialization.builtins.serializer<String>(),
                    kotlinx.serialization.builtins.serializer<String>()), case.accepted))
            for (rej in case.rejected)
                store.saveDecision(did, "REJECT", false, json.encodeToString(
                    kotlinx.serialization.builtins.MapSerializer(
                        kotlinx.serialization.builtins.serializer<String>(),
                        kotlinx.serialization.builtins.serializer<String>()), rej))
            // machine-readable judgment event
            store.saveDecision(did, "JUDGMENT", true,
                """{"rule":"${case.judgment["rule"]}","machineCheck":"${case.judgment["machineCheck"]}","expectEvents":"${case.expectEvents.joinToString(",")}"}""")
            out[case.name] = did
        }
        return out
    }
}
