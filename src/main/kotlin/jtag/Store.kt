package jtag

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.DriverManager

const val SCHEMA_VERSION = 2

/**
 * Versioned store. Sources of different kinds are stored separately.
 * Every derivation is written inside one transaction; the `current` pointer
 * flips only after the derivation is fully committed. After an interrupted
 * write, restart sees either the old version or a complete new one.
 */
class Store(val path: String) {
    val json = Json { prettyPrint = false; ignoreUnknownKeys = true }
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path").apply {
        createStatement().execute("PRAGMA journal_mode=WAL")
        createStatement().execute("PRAGMA foreign_keys=ON")
    }

    init { migrate(); recover() }

    private fun migrate() {
        val st = conn.createStatement()
        st.execute("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT)")
        val v = st.executeQuery("SELECT value FROM meta WHERE key='schema_version'").let { if (it.next()) it.getString(1).toInt() else 0 }
        if (v < 1) {
            st.execute("""CREATE TABLE IF NOT EXISTS sources(
                id TEXT PRIMARY KEY, kind TEXT, label TEXT, content TEXT, seq INTEGER)""")
            st.execute("""CREATE TABLE IF NOT EXISTS derivations(
                id TEXT PRIMARY KEY, parent_id TEXT, label TEXT, status TEXT,
                params TEXT, result TEXT, seq INTEGER)""")
            st.execute("""CREATE TABLE IF NOT EXISTS events(
                id TEXT PRIMARY KEY, derivation_id TEXT, kind TEXT, payload TEXT, seq INTEGER)""")
            st.execute("""CREATE TABLE IF NOT EXISTS decisions(
                id TEXT PRIMARY KEY, derivation_id TEXT, kind TEXT, accepted INTEGER, payload TEXT, seq INTEGER)""")
            st.execute("""CREATE TABLE IF NOT EXISTS conflicts(
                id TEXT PRIMARY KEY, base_version TEXT, branch_a TEXT, branch_b TEXT,
                status TEXT, resolution TEXT, seq INTEGER)""")
            st.execute("INSERT OR REPLACE INTO meta(key,value) VALUES('schema_version','1')")
        }
        if (v < 2) {
            // v2: events gain severity column; derivations gain stage hash table
            val cols = st.executeQuery("PRAGMA table_info(events)").let { rs -> generateSequence { if (rs.next()) rs.getString(2) else null }.toList() }
            if ("severity" !in cols) st.execute("ALTER TABLE events ADD COLUMN severity TEXT DEFAULT 'INFO'")
            st.execute("""CREATE TABLE IF NOT EXISTS stage_hashes(
                derivation_id TEXT, stage TEXT, input_hash TEXT,
                PRIMARY KEY(derivation_id, stage))""")
            st.execute("INSERT OR REPLACE INTO meta(key,value) VALUES('schema_version','2')")
        }
    }

    /** Abandon derivations left in 'building' state by an interrupted write. */
    fun recover() {
        conn.createStatement().execute("UPDATE derivations SET status='aborted' WHERE status='building'")
    }

    private var seqCounter = 0
    @Synchronized private fun nextSeq(): Int {
        if (seqCounter == 0) {
            val rs = conn.createStatement().executeQuery(
                "SELECT COALESCE(MAX(s),0) FROM (SELECT seq s FROM sources UNION ALL SELECT seq FROM derivations UNION ALL SELECT seq FROM events UNION ALL SELECT seq FROM decisions UNION ALL SELECT seq FROM conflicts)")
            rs.next(); seqCounter = rs.getInt(1)
        }
        return ++seqCounter
    }

    fun newId(prefix: String) = "$prefix-${System.currentTimeMillis().toString(36)}-${nextSeq()}"

    // ---------- sources ----------
    fun putSource(id: String, kind: String, label: String, content: String) {
        val ps = conn.prepareStatement("INSERT OR REPLACE INTO sources(id,kind,label,content,seq) VALUES(?,?,?,?,?)")
        ps.setString(1, id); ps.setString(2, kind); ps.setString(3, label); ps.setString(4, content); ps.setInt(5, nextSeq())
        ps.executeUpdate()
    }

    data class SourceRow(val id: String, val kind: String, val label: String, val content: String, val seq: Int)

    fun sources(kind: String? = null): List<SourceRow> {
        val sql = if (kind == null) "SELECT * FROM sources ORDER BY seq" else "SELECT * FROM sources WHERE kind='$kind' ORDER BY seq"
        val rs = conn.createStatement().executeQuery(sql)
        val out = mutableListOf<SourceRow>()
        while (rs.next()) out += SourceRow(rs.getString("id"), rs.getString("kind"), rs.getString("label"), rs.getString("content"), rs.getInt("seq"))
        return out
    }

    fun source(id: String): SourceRow? = sources().firstOrNull { it.id == id }

    // ---------- derivations ----------
    data class DerivationRow(val id: String, val parentId: String?, val label: String, val status: String,
                             val params: String, val result: String?, val seq: Int)

    /** Save a completed derivation atomically and optionally flip current pointer. */
    @Synchronized
    fun saveDerivation(id: String, parentId: String?, label: String, params: DeriveParams,
                       result: DerivationResult, events: List<JudgeEvent>,
                       stageHashes: Map<String, String>, makeCurrent: Boolean = true) {
        conn.autoCommit = false
        try {
            val ps = conn.prepareStatement("INSERT INTO derivations(id,parent_id,label,status,params,result,seq) VALUES(?,?,?,'building',?,?,?)")
            ps.setString(1, id); ps.setString(2, parentId); ps.setString(3, label)
            ps.setString(4, json.encodeToString(DeriveParams.serializer(), params))
            ps.setString(5, json.encodeToString(DerivationResult.serializer(), result))
            ps.setInt(6, nextSeq()); ps.executeUpdate()
            for (e in events) {
                val pe = conn.prepareStatement("INSERT INTO events(id,derivation_id,kind,severity,payload,seq) VALUES(?,?,?,?,?,?)")
                pe.setString(1, newId("ev")); pe.setString(2, id); pe.setString(3, e.kind); pe.setString(4, e.severity)
                pe.setString(5, json.encodeToString(JudgeEvent.serializer(), e)); pe.setInt(6, nextSeq()); pe.executeUpdate()
            }
            for ((st, h) in stageHashes) {
                val ph = conn.prepareStatement("INSERT INTO stage_hashes(derivation_id,stage,input_hash) VALUES(?,?,?)")
                ph.setString(1, id); ph.setString(2, st); ph.setString(3, h); ph.executeUpdate()
            }
            conn.createStatement().execute("UPDATE derivations SET status='complete' WHERE id='$id'")
            if (makeCurrent)
                conn.createStatement().execute("INSERT OR REPLACE INTO meta(key,value) VALUES('current','$id')")
            conn.commit()
        } catch (ex: Exception) {
            conn.rollback(); throw ex
        } finally { conn.autoCommit = true }
    }

    fun current(): String? {
        val rs = conn.createStatement().executeQuery("SELECT value FROM meta WHERE key='current'")
        return if (rs.next()) rs.getString(1) else null
    }

    fun setCurrent(id: String) {
        conn.createStatement().execute("INSERT OR REPLACE INTO meta(key,value) VALUES('current','$id')")
    }

    fun derivations(): List<DerivationRow> {
        val rs = conn.createStatement().executeQuery("SELECT * FROM derivations ORDER BY seq")
        val out = mutableListOf<DerivationRow>()
        while (rs.next()) out += DerivationRow(rs.getString("id"), rs.getString("parent_id"), rs.getString("label"),
            rs.getString("status"), rs.getString("params"), rs.getString("result"), rs.getInt("seq"))
        return out
    }

    fun derivation(id: String): DerivationRow? = derivations().firstOrNull { it.id == id }

    fun eventsOf(derivationId: String): List<Pair<String, JudgeEvent>> {
        val rs = conn.createStatement().executeQuery("SELECT * FROM events WHERE derivation_id='$derivationId' ORDER BY seq")
        val out = mutableListOf<Pair<String, JudgeEvent>>()
        while (rs.next()) out += rs.getString("id") to json.decodeFromString(JudgeEvent.serializer(), rs.getString("payload"))
        return out
    }

    fun stageHashesOf(derivationId: String): Map<String, String> {
        val rs = conn.createStatement().executeQuery("SELECT * FROM stage_hashes WHERE derivation_id='$derivationId'")
        val out = mutableMapOf<String, String>()
        while (rs.next()) out[rs.getString("stage")] = rs.getString("input_hash")
        return out
    }

    // ---------- decisions (accepted and rejected both kept) ----------
    fun saveDecision(derivationId: String, kind: String, accepted: Boolean, payload: String): String {
        val id = newId("dec")
        val ps = conn.prepareStatement("INSERT INTO decisions(id,derivation_id,kind,accepted,payload,seq) VALUES(?,?,?,?,?,?)")
        ps.setString(1, id); ps.setString(2, derivationId); ps.setString(3, kind)
        ps.setInt(4, if (accepted) 1 else 0); ps.setString(5, payload); ps.setInt(6, nextSeq())
        ps.executeUpdate(); return id
    }

    data class DecisionRow(val id: String, val derivationId: String, val kind: String, val accepted: Boolean, val payload: String, val seq: Int)

    fun decisions(): List<DecisionRow> {
        val rs = conn.createStatement().executeQuery("SELECT * FROM decisions ORDER BY seq")
        val out = mutableListOf<DecisionRow>()
        while (rs.next()) out += DecisionRow(rs.getString("id"), rs.getString("derivation_id"), rs.getString("kind"),
            rs.getInt("accepted") == 1, rs.getString("payload"), rs.getInt("seq"))
        return out
    }

    // ---------- optimistic concurrency ----------
    /** Two editors from the same base version: second save raises a resolvable conflict. */
    @Synchronized
    fun saveWithConflictCheck(baseVersion: String, newDerivationId: String): ConflictRow? {
        val cur = current()
        if (cur == null || cur == baseVersion) return null
        val id = newId("conf")
        val ps = conn.prepareStatement("INSERT INTO conflicts(id,base_version,branch_a,branch_b,status,resolution,seq) VALUES(?,?,?,?, 'open', NULL, ?)")
        ps.setString(1, id); ps.setString(2, baseVersion); ps.setString(3, cur); ps.setString(4, newDerivationId); ps.setInt(5, nextSeq())
        ps.executeUpdate()
        return ConflictRow(id, baseVersion, cur, newDerivationId, "open", null)
    }

    data class ConflictRow(val id: String, val base: String, val branchA: String, val branchB: String, val status: String, val resolution: String?)

    fun conflicts(): List<ConflictRow> {
        val rs = conn.createStatement().executeQuery("SELECT * FROM conflicts ORDER BY seq")
        val out = mutableListOf<ConflictRow>()
        while (rs.next()) out += ConflictRow(rs.getString("id"), rs.getString("base_version"), rs.getString("branch_a"),
            rs.getString("branch_b"), rs.getString("status"), rs.getString("resolution"))
        return out
    }

    /** Resolve by choosing a branch; the unchosen branch is retained, never overwritten. */
    fun resolveConflict(id: String, chosenDerivation: String) {
        conn.createStatement().execute("UPDATE conflicts SET status='resolved', resolution='$chosenDerivation' WHERE id='$id'")
        setCurrent(chosenDerivation)
    }

    // ---------- export / import (identity, order, provenance preserved) ----------
    fun exportJson(): String {
        fun table(name: String): List<JsonObject> {
            val rs = conn.createStatement().executeQuery("SELECT * FROM $name ORDER BY rowid")
            val md = rs.metaData
            val out = mutableListOf<JsonObject>()
            while (rs.next()) {
                val m = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
                for (c in 1..md.columnCount) {
                    val v = rs.getString(c)
                    m[md.getColumnName(c)] = JsonPrimitive(v ?: "")
                }
                out += JsonObject(m)
            }
            return out
        }
        val dump = mapOf(
            "sources" to table("sources"), "derivations" to table("derivations"),
            "events" to table("events"), "decisions" to table("decisions"),
            "conflicts" to table("conflicts"), "stage_hashes" to table("stage_hashes"),
            "meta" to table("meta")
        )
        val root = JsonObject(dump.mapValues { JsonArray(it.value) })
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root)
    }

    fun importJson(dump: String) {
        val parsed = Json.parseToJsonElement(dump).jsonObject
        conn.autoCommit = false
        try {
            for (t in listOf("sources", "derivations", "events", "decisions", "conflicts", "stage_hashes", "meta")) {
                val rows = parsed[t]?.jsonArray ?: continue
                for (row in rows) {
                    val obj = row.jsonObject
                    val cols = obj.keys.toList()
                    val sql = "INSERT OR REPLACE INTO $t(${cols.joinToString(",")}) VALUES(${cols.joinToString(",") { "?" }})"
                    val ps = conn.prepareStatement(sql)
                    cols.forEachIndexed { i, c -> ps.setString(i + 1, obj[c]!!.jsonPrimitive.content) }
                    ps.executeUpdate()
                }
            }
            conn.commit()
        } catch (ex: Exception) { conn.rollback(); throw ex } finally { conn.autoCommit = true }
        recover()
    }

    fun close() = conn.close()
}
