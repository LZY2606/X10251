package com.boardscan.db

import com.boardscan.bsdl.BsdlParser
import com.boardscan.bsdl.BoardRevision
import com.boardscan.derive.DerivationEngine
import com.boardscan.derive.DerivationResult
import com.boardscan.derive.RunInputs
import com.boardscan.model.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

class ConflictException(message: String, val serverParent: String) : RuntimeException(message)

/**
 * 存储与版本裁定。
 *
 * 原子性：一次推导在单个事务内写齐 run/objects/findings/events，最后才 UPDATE current_pointer；
 * 事务提交前任何写入中断都回滚 -> 重启后只能看到旧版本或完整新版本。
 *
 * 冲突：编辑必须带 expectedParentRunId（当前指针），不符 -> ConflictException。
 */
class Repository(private val dbPath: String) {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    private val eventSeq = AtomicLong(0)

    fun connect(): Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")

    fun init() {
        connect().use { c ->
            c.createStatement().use { st ->
                Schema.CREATE.trim().split(";").map { it.trim() }.filter { it.isNotEmpty() }
                    .forEach { st.execute(it) }
            }
            val v = c.createStatement().executeQuery("SELECT value FROM meta WHERE key='schema_version'")
                .use { if (it.next()) it.getString(1) else null }
            if (v == null) {
                c.prepareStatement("INSERT INTO meta(key,value) VALUES('schema_version',?)")
                    .apply { setString(1, Schema.VERSION.toString()) }.executeUpdate()
            } else if (v.toInt() < Schema.VERSION) {
                migrate(c, v.toInt())
            }
        }
    }

    private fun migrate(c: Connection, from: Int) {
        // 旧版本迁移：从 v1 起逐个向前迁移。当前 v1->v2 补充 export_bundle。
        var v = from
        if (v == 1) {
            c.createStatement().execute(
                """CREATE TABLE IF NOT EXISTS export_bundle(
                bundle_id TEXT PRIMARY KEY, created_at TEXT NOT NULL, payload_json TEXT NOT NULL)"""
            )
            v = 2
        }
        c.prepareStatement("UPDATE meta SET value=? WHERE key='schema_version'")
            .apply { setString(1, v.toString()) }.executeUpdate()
    }

    // ---------- 直接来源（分表） ----------
    fun upsertSampleImport(sample: SampleImport) {
        connect().use { c ->
            c.autoCommit = false
            c.prepareStatement(
                """INSERT OR REPLACE INTO sample_import(sample_id,board_revision_id,polarity_id,imported_at,raw_json)
                   VALUES(?,?,?,?,?)"""
            ).apply {
                setString(1, sample.sampleId); setString(2, sample.boardRevisionId)
                setString(3, sample.polarityId); setString(4, sample.importedAt)
                setString(5, json.encodeToString(SampleImport.serializer(), sample))
            }.executeUpdate()
            c.prepareStatement("DELETE FROM sample_row WHERE sample_id=?").apply { setString(1, sample.sampleId) }.executeUpdate()
            val ins = c.prepareStatement(
                """INSERT INTO sample_row(sample_id,edge_id,ord,time_raw,tck_raw,tms_raw,tdi_raw,tdo_raw)
                   VALUES(?,?,?,?,?,?,?,?)"""
            )
            for (r in sample.rows) {
                ins.setString(1, sample.sampleId); ins.setString(2, r.edgeId); ins.setLong(3, r.order)
                ins.setString(4, r.timeRaw); ins.setString(5, r.tckRaw); ins.setString(6, r.tmsRaw)
                ins.setString(7, r.tdiRaw); ins.setString(8, r.tdoRaw); ins.addBatch()
            }
            ins.executeBatch()
            c.commit()
        }
    }

    fun upsertPolarity(p: PolarityConfig, createdAt: String = Instant.now().toString()) {
        connect().use { c ->
            c.prepareStatement(
                "INSERT OR REPLACE INTO polarity_config(polarity_id,version,raw_json,created_at) VALUES(?,?,?,?)"
            ).apply {
                setString(1, p.polarityId); setInt(2, p.version)
                setString(3, json.encodeToString(PolarityConfig.serializer(), p)); setString(4, createdAt)
            }.executeUpdate()
        }
    }

    fun upsertBoard(b: BoardRevision) {
        connect().use { c ->
            c.prepareStatement(
                "INSERT OR REPLACE INTO board_revision(board_revision_id,name,revision,coordinate_basis,raw_json) VALUES(?,?,?,?,?)"
            ).apply {
                setString(1, b.boardRevisionId); setString(2, b.name); setString(3, b.revision)
                setString(4, b.coordinateBasis)
                setString(5, json.encodeToString(BoardRevision.serializer(), b))
            }.executeUpdate()
        }
    }

    fun upsertBsdl(bsdlId: String, rawText: String, importedAt: String = Instant.now().toString()): String {
        val parsed = BsdlParser.parse(bsdlId, rawText)
        connect().use { c ->
            c.prepareStatement(
                """INSERT OR REPLACE INTO bsdl_file(bsdl_id,version,parsed_json,raw_text,imported_at)
                   VALUES(?,?,?,?,?)"""
            ).apply {
                setString(1, bsdlId); setString(2, parsed.version)
                setString(3, json.encodeToString(com.boardscan.bsdl.ParsedBsdl.serializer(), parsed))
                setString(4, rawText); setString(5, importedAt)
            }.executeUpdate()
        }
        return parsed.version
    }

    // ---------- 读取 ----------
    fun currentRunId(): String? = connect().use { c ->
        c.createStatement().executeQuery("SELECT run_id FROM current_pointer WHERE scope='main'").use {
            if (it.next()) it.getString(1) else null
        }
    }

    fun allRunIds(): List<String> = connect().use { c ->
        c.createStatement().executeQuery("SELECT run_id FROM derivation_run ORDER BY created_at").use {
            val l = mutableListOf<String>(); while (it.next()) l += it.getString(1); l
        }
    }

    fun loadResult(runId: String): DerivationResult? = connect().use { c ->
        val row = c.prepareStatement("SELECT result_json FROM derivation_run WHERE run_id=? AND status='COMPLETE'")
            .apply { setString(1, runId) }.executeQuery()
        if (row.next()) json.decodeFromString(DerivationResult.serializer(), row.getString(1)) else null
    }

    fun runStatus(runId: String): String? = connect().use { c ->
        c.prepareStatement("SELECT status FROM derivation_run WHERE run_id=?").apply { setString(1, runId) }
            .executeQuery().use { if (it.next()) it.getString(1) else null }
    }

    fun loadInputs(runId: String): RunInputs? = loadResult(runId)?.inputs

    // ---------- 版本化提交 ----------
    /**
     * 原子提交一次推导。crashHook 在写完行、提交前执行（测试用于模拟写入中断：抛异常/杀进程）。
     * 不切换 current_pointer 直到完整写入成功。
     */
    fun commitRun(
        result: DerivationResult,
        expectedParentRunId: String?,
        crashHook: (Connection) -> Unit = {},
    ) {
        connect().use { c ->
            c.autoCommit = false
            try {
                val parent = currentRunIdLocked(c)
                if (parent != expectedParentRunId) {
                    c.rollback()
                    throw ConflictException(
                        "当前指针为 $parent，调用方基于 $expectedParentRunId 编辑，存在并发改动。", parent ?: "")
                }
                insertRunLocked(c, result, "PENDING")
                insertObjectsLocked(c, result)
                crashHook(c)
                insertDecisionMarkerLocked(c, result)
                c.prepareStatement("UPDATE derivation_run SET status='COMPLETE', result_json=? WHERE run_id=?")
                    .apply {
                        setString(1, json.encodeToString(DerivationResult.serializer(), result))
                        setString(2, result.runId)
                    }.executeUpdate()
                c.prepareStatement(
                    "INSERT INTO current_pointer(scope,run_id) VALUES('main',?) " +
                        "ON CONFLICT(scope) DO UPDATE SET run_id=excluded.run_id"
                ).apply { setString(1, result.runId) }.executeUpdate()
                c.commit()
            } catch (e: Throwable) {
                c.rollback()
                throw e
            }
        }
    }

    /** 仅在独立事务里写一个 PENDING 版本（模拟“写入中断后重启”的现场）。 */
    fun leavePendingForCrashTest(result: DerivationResult) {
        connect().use { c ->
            c.autoCommit = false
            insertRunLocked(c, result, "PENDING")
            insertObjectsLocked(c, result)
            c.commit()
        }
    }

    private fun currentRunIdLocked(c: Connection): String? =
        c.createStatement().executeQuery("SELECT run_id FROM current_pointer WHERE scope='main'").use {
            if (it.next()) it.getString(1) else null
        }

    private fun insertRunLocked(c: Connection, result: DerivationResult, status: String) {
        c.prepareStatement(
            """INSERT INTO derivation_run(run_id,parent_run_id,branch_label,status,created_at,author,
               fingerprint,expected_parent_run_id,result_json) VALUES(?,?,?,?,?,?,?,?,?)"""
        ).apply {
            setString(1, result.runId); setString(2, result.parentRunId)
            setString(3, result.branchLabel); setString(4, status)
            setString(5, result.createdAt); setString(6, "engine")
            setString(7, result.fingerprint); setString(8, result.parentRunId)
            setString(9, if (status == "COMPLETE") json.encodeToString(DerivationResult.serializer(), result) else null)
        }.executeUpdate()
    }

    private fun insertObjectsLocked(c: Connection, result: DerivationResult) {
        val ins = c.prepareStatement(
            """INSERT OR REPLACE INTO derived_object(object_id,run_id,kind,stable_id,seq,payload_json)
               VALUES(?,?,?,?,?,?)"""
        )
        fun add(kind: String, objectId: String, stableId: String, seq: Int, payload: String) {
            ins.setString(1, objectId); ins.setString(2, result.runId); ins.setString(3, kind)
            ins.setString(4, stableId); ins.setInt(5, seq); ins.setString(6, payload); ins.addBatch()
        }
        result.steps.forEachIndexed { i, s -> add("tap", s.edgeId, s.edgeId, i, json.encodeToString(com.boardscan.tap.TapStep.serializer(), s)) }
        result.scans.forEachIndexed { i, s -> add("scan", s.segmentId, s.segmentId, i, json.encodeToString(ScanSegment.serializer(), s)) }
        result.chain.devices.forEachIndexed { i, d -> add("device", d.deviceId, stableDeviceId(d.bsdlId, d.position), i, json.encodeToString(ChainDevice.serializer(), d)) }
        result.chain.irCandidates.forEachIndexed { i, cand -> add("ir", cand.candidateId, cand.candidateId, i, json.encodeToString(IrLengthCandidate.serializer(), cand)) }
        result.chain.cellEvidence.forEachIndexed { i, ev ->
            add("cell", "cell-${result.runId}-${ev.bsdlVersion}-${ev.cellNumber}",
                "cell-${ev.bsdlVersion}-${ev.cellNumber}", i,
                json.encodeToString(com.boardscan.bsdl.CellPinEvidence.serializer(), ev))
        }
        result.resets.forEachIndexed { i, r -> add("reset", r.resetId, r.resetId, i, json.encodeToString(ResetEvent.serializer(), r)) }
        ins.executeBatch()

        val fins = c.prepareStatement(
            """INSERT OR REPLACE INTO finding(finding_id,run_id,branch_label,code,severity,message,
               edge_id,refs_json,disposition,decision_id,seq) VALUES(?,?,?,?,?,?,?,?,?,?,?)"""
        )
        for (f in result.findings) {
            fins.setString(1, f.findingId); fins.setString(2, result.runId); fins.setString(3, f.branchLabel)
            fins.setString(4, f.code.name); fins.setString(5, f.severity.name); fins.setString(6, f.message)
            fins.setString(7, f.edgeId); fins.setString(8, json.encodeToString(MapSerializer(String.serializer(), String.serializer()), f.refs))
            fins.setString(9, f.disposition.name); fins.setString(10, f.decisionId); fins.setLong(11, f.seq)
            fins.addBatch()
        }
        fins.executeBatch()
    }

    private fun insertDecisionMarkerLocked(c: Connection, result: DerivationResult) {
        val id = "evt-${result.runId}"
        c.prepareStatement(
            """INSERT OR IGNORE INTO event_log(event_id,run_id,parent_run_id,action,target_finding_id,
               target_object_id,payload_json,author,created_at,seq) VALUES(?,?,?,?,?,?,?,?,?,?)"""
        ).apply {
            setString(1, id); setString(2, result.runId); setString(3, result.parentRunId)
            setString(4, "DERIVE"); setString(5, null); setString(6, null)
            setString(7, "{}"); setString(8, "engine"); setString(9, result.createdAt)
            setLong(10, eventSeq.incrementAndGet())
        }.executeUpdate()
    }

    fun stableDeviceId(bsdlId: String, position: Int) = "dev-$bsdlId-pos$position"

    // ---------- 决定事件（不覆盖历史） ----------
    /**
     * 记录一次人工接受/否定/参数调整。该动作不修改旧 finding，而是创建新版本的 run：
     * 旧 run 及其结论永久保留，可回到旧事件恢复当时的可见结论。
     */
    fun recordDecision(
        decision: Decision,
        buildNewResult: (String) -> DerivationResult,
    ): DerivationResult {
        val current = currentRunId()
        if (decision.expectedParentRunId != null && decision.expectedParentRunId != current) {
            throw ConflictException(
                "两位使用者基于同一版本编辑：当前=$current，期望=${decision.expectedParentRunId}",
                current ?: "")
        }
        connect().use { c ->
            c.autoCommit = false
            c.prepareStatement(
                """INSERT INTO event_log(event_id,run_id,parent_run_id,action,target_finding_id,
                   target_object_id,payload_json,author,created_at,seq) VALUES(?,?,?,?,?,?,?,?,?,?)"""
            ).apply {
                setString(1, decision.decisionId); setString(2, decision.runId)
                setString(3, decision.parentRunId); setString(4, decision.action.name)
                setString(5, decision.targetFindingId); setString(6, decision.targetObjectId)
                setString(7, json.encodeToString(MapSerializer(String.serializer(), String.serializer()), decision.payload))
                setString(8, decision.author); setString(9, decision.createdAt)
                setLong(10, eventSeq.incrementAndGet())
            }.executeUpdate()
            c.commit()
        }
        val result = buildNewResult(decision.runId)
        val withDisposition = result.copy(findings = result.findings.map { f ->
            if (decision.targetFindingId != null && relatedFinding(f, decision)) {
                f.copy(disposition = when (decision.action) {
                    DecisionAction.ACCEPT_FINDING, DecisionAction.CHOOSE_CANDIDATE,
                    DecisionAction.SET_PARAMETER -> FindingDisposition.ACCEPTED
                    DecisionAction.REJECT_FINDING -> FindingDisposition.REJECTED
                    DecisionAction.RESOLVE_CONFLICT -> f.disposition
                }, decisionId = decision.decisionId)
            } else f
        })
        commitRun(withDisposition, current)
        return withDisposition
    }

    private fun relatedFinding(f: Finding, d: Decision): Boolean {
        if (d.targetFindingId != null) return f.findingId == d.targetFindingId
        if (d.action == DecisionAction.CHOOSE_CANDIDATE && d.targetObjectId != null) {
            return f.refs["candidateId"] == d.targetObjectId ||
                f.code == FindingCode.IR_LENGTH_AMBIGUOUS
        }
        return false
    }

    fun events(): List<Map<String, String?>> = connect().use { c ->
        c.createStatement().executeQuery(
            "SELECT event_id,run_id,parent_run_id,action,target_finding_id,target_object_id,author,created_at,seq " +
                "FROM event_log ORDER BY seq"
        ).use {
            val out = mutableListOf<Map<String, String?>>()
            while (it.next()) {
                out += mapOf(
                    "eventId" to it.getString(1), "runId" to it.getString(2),
                    "parentRunId" to it.getString(3), "action" to it.getString(4),
                    "targetFindingId" to it.getString(5), "targetObjectId" to it.getString(6),
                    "author" to it.getString(7), "createdAt" to it.getString(8),
                    "seq" to it.getString(9))
            }
            out
        }
    }

    // ---------- 导出 / 导入（身份、顺序、来源关系保持） ----------
    fun exportBundle(): ExportBundle {
        val runs = allRunIds().mapNotNull { loadResult(it) }
        val samples = mutableListOf<SampleImport>()
        val polarities = mutableListOf<PolarityConfig>()
        val boards = mutableListOf<BoardRevision>()
        val bsdls = mutableListOf<com.boardscan.bsdl.ParsedBsdl>()
        connect().use { c ->
            c.createStatement().executeQuery("SELECT raw_json FROM sample_import").use {
                while (it.next()) samples += json.decodeFromString(SampleImport.serializer(), it.getString(1))
            }
            c.createStatement().executeQuery("SELECT raw_json FROM polarity_config").use {
                while (it.next()) polarities += json.decodeFromString(PolarityConfig.serializer(), it.getString(1))
            }
            c.createStatement().executeQuery("SELECT raw_json FROM board_revision").use {
                while (it.next()) boards += json.decodeFromString(BoardRevision.serializer(), it.getString(1))
            }
            c.createStatement().executeQuery("SELECT parsed_json FROM bsdl_file").use {
                while (it.next()) bsdls += json.decodeFromString(com.boardscan.bsdl.ParsedBsdl.serializer(), it.getString(1))
            }
        }
        val bundle = ExportBundle(
            bundleId = "bundle-${Instant.now().toEpochMilli()}",
            createdAt = Instant.now().toString(),
            currentRunId = currentRunId(),
            samples = samples, polarities = polarities, boards = boards, bsdls = bsdls,
            runs = runs, events = events(),
        )
        connect().use { c ->
            c.prepareStatement("INSERT OR REPLACE INTO export_bundle(bundle_id,created_at,payload_json) VALUES(?,?,?)")
                .apply {
                    setString(1, bundle.bundleId); setString(2, bundle.createdAt)
                    setString(3, json.encodeToString(ExportBundle.serializer(), bundle))
                }.executeUpdate()
        }
        return bundle
    }

    fun writeBundleFile(path: String) {
        val payload = json.encodeToString(ExportBundle.serializer(), exportBundle())
        val target = java.io.File(path)
        val tmp = java.io.File(path + ".tmp")
        tmp.writeText(payload)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyAtomic())
    }

    private fun StandardCopyAtomic(): StandardCopyOption = StandardCopyOption.ATOMIC_MOVE

    /** 导入到一个全新的数据库文件；run/finding/stable_id 全部沿用导出值。 */
    fun importBundle(bundle: ExportBundle, intoPath: String): Repository {
        val repo = Repository(intoPath)
        repo.init()
        bundle.samples.forEach(repo::upsertSampleImport)
        bundle.polarities.forEach(repo::upsertPolarity)
        bundle.boards.forEach(repo::upsertBoard)
        bundle.bsdls.forEach { parsed ->
            // 原始 BSDL 文本已在 parsed.rawText 保留
            val v = repo.upsertBsdl(parsed.bsdlId, parsed.rawText)
            require(v == parsed.version) { "BSDL ${parsed.bsdlId} 重新解析版本漂移: $v != ${parsed.version}" }
        }
        // 按父->子顺序恢复 run，保证顺序与指针一致
        val ordered = orderByParent(bundle.runs)
        for (r in ordered) repo.restoreRun(r)
        bundle.currentRunId?.let {
            repo.connect().use { c ->
                c.prepareStatement("INSERT OR REPLACE INTO current_pointer(scope,run_id) VALUES('main',?)")
                    .apply { setString(1, it) }.executeUpdate()
            }
        }
        return repo
    }


    /** 导入恢复：直接写 COMPLETE 版本，不做乐观锁，保留身份与父子关系。 */
    fun restoreRun(result: DerivationResult) {
        connect().use { c ->
            c.autoCommit = false
            insertRunLocked(c, result, "COMPLETE")
            insertObjectsLocked(c, result)
            insertDecisionMarkerLocked(c, result)
            c.commit()
        }
    }

    private fun orderByParent(runs: List<DerivationResult>): List<DerivationResult> {
        val byId = runs.associateBy { it.runId }
        val visited = linkedSetOf<String>()
        fun visit(r: DerivationResult) {
            if (!visited.add(r.runId)) return
            byId[r.parentRunId]?.let { visit(it) }
            visited.add(r.runId)
        }
        runs.forEach(::visit)
        return visited.mapNotNull { byId[it] }
    }
}

@kotlinx.serialization.Serializable
data class ExportBundle(
    val bundleId: String,
    val createdAt: String,
    val currentRunId: String?,
    val samples: List<SampleImport>,
    val polarities: List<PolarityConfig>,
    val boards: List<BoardRevision>,
    val bsdls: List<com.boardscan.bsdl.ParsedBsdl>,
    val runs: List<DerivationResult>,
    val events: List<Map<String, String?>>,
)
