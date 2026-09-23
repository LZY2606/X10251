package jtag.store

import jtag.model.newId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.DriverManager

const val SCHEMA_VERSION = 2

object SourceKind {
    const val EDGE_BUNDLE = "EDGE_BUNDLE"
    const val RESET_EVENT = "RESET_EVENT"
    const val IR_CANDIDATE = "IR_CANDIDATE"
    const val BSDL_MAPPING = "BSDL_MAPPING"
    const val BOARD_REVISION = "BOARD_REVISION"
    const val PARAM = "PARAM"
}

@Serializable
data class VersionInfo(val id: String, val seq: Int, val parent: String?, val label: String, val state: String, val created: String)

@Serializable
data class SourceRow(val id: String, val version: String, val kind: String, val seq: Int, val payload: String)

@Serializable
data class EventRow(val id: String, val version: String, val seq: Int, val kind: String, val payload: String, val created: String)

@Serializable
data class DerivedRow(val version: String, val node: String, val status: String, val inputs: String, val payload: String, val explanation: String)

@Serializable
data class ConflictRow(val id: String, val base: String, val a: String, val b: String, val status: String, val resolution: String?, val created: String)

@Serializable
data class ExportBundle(
    val schemaVersion: Int,
    val versions: List<VersionInfo>,
    val current: String?,
    val sources: List<SourceRow>,
    val events: List<EventRow>,
    val derived: List<DerivedRow>,
    val conflicts: List<ConflictRow>
)

class ConflictDetected(val base: String, val current: String) : Exception("current pointer moved from $base to $current")

class VersionBuilder(val parent: String?, val label: String) {
    val id: String = newId()
    val sources = mutableListOf<SourceRow>()
    val events = mutableListOf<EventRow>()
    val derived = mutableListOf<DerivedRow>()
    private var seq = 0
    fun addSource(kind: String, payload: String, id: String = newId()) {
        sources += SourceRow(id, id.let { "" }, kind, seq++, payload) // version filled at commit
    }
    fun addEvent(kind: String, payload: String) {
        events += EventRow(newId(), "", seq++, kind, payload, now())
    }
    fun addDerived(node: String, status: String, inputs: String, payload: String, explanation: String) {
        derived += DerivedRow("", node, status, inputs, payload, explanation)
    }
}

fun now(): String = java.time.Instant.now().toString()

class Store(path: String) : AutoCloseable {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path")
    val json = Json { prettyPrint = false; encodeDefaults = true }

    init {
        conn.autoCommit = true
        exec("PRAGMA journal_mode=WAL")
        exec("PRAGMA foreign_keys=ON")
        migrate()
    }

    private fun exec(sql: String) { conn.createStatement().use { it.execute(sql) } }

    private fun migrate() {
        val v = schemaVersion()
        if (v == 0) {
            conn.createStatement().use { st ->
                st.executeUpdate("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT)")
                st.executeUpdate("CREATE TABLE IF NOT EXISTS versions(id TEXT PRIMARY KEY, seq INTEGER, parent TEXT, label TEXT, state TEXT, created TEXT)")
                st.executeUpdate("CREATE TABLE IF NOT EXISTS current_pointer(version TEXT)")
                st.executeUpdate("CREATE TABLE IF NOT EXISTS sources(id TEXT PRIMARY KEY, version TEXT, kind TEXT, seq INTEGER, payload TEXT)")
                st.executeUpdate("CREATE TABLE IF NOT EXISTS events(id TEXT PRIMARY KEY, version TEXT, seq INTEGER, kind TEXT, payload TEXT, created TEXT)")
                st.executeUpdate("CREATE TABLE IF NOT EXISTS derived(version TEXT, node TEXT, status TEXT, inputs TEXT, payload TEXT, explanation TEXT NOT NULL DEFAULT '', PRIMARY KEY(version,node))")
                st.executeUpdate("CREATE TABLE IF NOT EXISTS conflicts(id TEXT PRIMARY KEY, base TEXT, a TEXT, b TEXT, status TEXT, resolution TEXT, created TEXT)")
                st.executeUpdate("INSERT OR REPLACE INTO meta(key,value) VALUES('schema_version','$SCHEMA_VERSION')")
            }
        } else if (v < SCHEMA_VERSION) {
            // v1 -> v2: derived gains explanation column, conflicts table added
            if (!columnExists("derived", "explanation"))
                exec("ALTER TABLE derived ADD COLUMN explanation TEXT NOT NULL DEFAULT ''")
            exec("CREATE TABLE IF NOT EXISTS conflicts(id TEXT PRIMARY KEY, base TEXT, a TEXT, b TEXT, status TEXT, resolution TEXT, created TEXT)")
            exec("INSERT OR REPLACE INTO meta(key,value) VALUES('schema_version','$SCHEMA_VERSION')")
        }
    }

    fun schemaVersion(): Int {
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='meta'")
            if (!rs.next()) return 0
            val rs2 = st.executeQuery("SELECT value FROM meta WHERE key='schema_version'")
            return if (rs2.next()) rs2.getInt(1) else 0
        }
    }

    private fun columnExists(table: String, col: String): Boolean {
        conn.createStatement().use { st ->
            val rs = st.executeQuery("PRAGMA table_info($table)")
            while (rs.next()) if (rs.getString("name") == col) return true
        }
        return false
    }

    fun currentVersion(): String? {
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT version FROM current_pointer LIMIT 1")
            return if (rs.next()) rs.getString(1) else null
        }
    }

    fun versions(): List<VersionInfo> {
        val out = mutableListOf<VersionInfo>()
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT id,seq,parent,label,state,created FROM versions ORDER BY seq")
            while (rs.next()) out += VersionInfo(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6))
        }
        return out
    }

    fun sourcesOf(version: String, kind: String? = null): List<SourceRow> {
        val sql = if (kind == null) "SELECT id,version,kind,seq,payload FROM sources WHERE version=? ORDER BY seq"
                  else "SELECT id,version,kind,seq,payload FROM sources WHERE version=? AND kind=? ORDER BY seq"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, version); if (kind != null) ps.setString(2, kind)
            val rs = ps.executeQuery(); val out = mutableListOf<SourceRow>()
            while (rs.next()) out += SourceRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5))
            return out
        }
    }

    fun eventsOf(version: String): List<EventRow> {
        conn.prepareStatement("SELECT id,version,seq,kind,payload,created FROM events WHERE version=? ORDER BY seq").use { ps ->
            ps.setString(1, version); val rs = ps.executeQuery(); val out = mutableListOf<EventRow>()
            while (rs.next()) out += EventRow(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5), rs.getString(6))
            return out
        }
    }

    fun derivedOf(version: String, node: String): DerivedRow? {
        conn.prepareStatement("SELECT version,node,status,inputs,payload,explanation FROM derived WHERE version=? AND node=?").use { ps ->
            ps.setString(1, version); ps.setString(2, node); val rs = ps.executeQuery()
            return if (rs.next()) DerivedRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)) else null
        }
    }

    fun derivedAll(version: String): List<DerivedRow> {
        conn.prepareStatement("SELECT version,node,status,inputs,payload,explanation FROM derived WHERE version=? ORDER BY node").use { ps ->
            ps.setString(1, version); val rs = ps.executeQuery(); val out = mutableListOf<DerivedRow>()
            while (rs.next()) out += DerivedRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6))
            return out
        }
    }

    fun conflicts(): List<ConflictRow> {
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT id,base,a,b,status,resolution,created FROM conflicts ORDER BY created")
            val out = mutableListOf<ConflictRow>()
            while (rs.next()) out += ConflictRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7))
            return out
        }
    }

    /**
     * Commit a version atomically: version row + sources + events + derived + pointer switch
     * happen in ONE transaction. Any failure rolls everything back; readers see either the
     * old current version or the complete new one, never a partial write.
     */
    @Synchronized
    fun commit(builder: VersionBuilder, expectedBase: String? = null, becomeCurrent: Boolean = true, checkBase: Boolean = true): String {
        val base = expectedBase
        conn.autoCommit = false
        try {
            val cur = currentVersion()
            if (becomeCurrent && checkBase && cur != base) throw ConflictDetected(base.toString(), cur.toString())
            val seq = (versions().maxOfOrNull { it.seq } ?: 0) + 1
            conn.prepareStatement("INSERT INTO versions(id,seq,parent,label,state,created) VALUES(?,?,?,?,?,?)").use { ps ->
                ps.setString(1, builder.id); ps.setInt(2, seq); ps.setString(3, builder.parent)
                ps.setString(4, builder.label); ps.setString(5, "COMPLETE"); ps.setString(6, now())
                ps.executeUpdate()
            }
            for (s in builder.sources) conn.prepareStatement("INSERT INTO sources(id,version,kind,seq,payload) VALUES(?,?,?,?,?)").use { ps ->
                ps.setString(1, s.id); ps.setString(2, builder.id); ps.setString(3, s.kind); ps.setInt(4, s.seq); ps.setString(5, s.payload); ps.executeUpdate()
            }
            for (e in builder.events) conn.prepareStatement("INSERT INTO events(id,version,seq,kind,payload,created) VALUES(?,?,?,?,?,?)").use { ps ->
                ps.setString(1, e.id); ps.setString(2, builder.id); ps.setInt(3, e.seq); ps.setString(4, e.kind); ps.setString(5, e.payload); ps.setString(6, e.created); ps.executeUpdate()
            }
            for (d in builder.derived) conn.prepareStatement("INSERT INTO derived(version,node,status,inputs,payload,explanation) VALUES(?,?,?,?,?,?)").use { ps ->
                ps.setString(1, builder.id); ps.setString(2, d.node); ps.setString(3, d.status); ps.setString(4, d.inputs); ps.setString(5, d.payload); ps.setString(6, d.explanation); ps.executeUpdate()
            }
            if (becomeCurrent) {
                conn.prepareStatement("DELETE FROM current_pointer").use { it.executeUpdate() }
                conn.prepareStatement("INSERT INTO current_pointer(version) VALUES(?)").use { ps -> ps.setString(1, builder.id); ps.executeUpdate() }
            }
            conn.commit()
            return builder.id
        } catch (t: Throwable) {
            conn.rollback()
            throw t
        } finally {
            conn.autoCommit = true
        }
    }

    /** Save with optimistic concurrency. On conflict, both branches are kept and a conflict row is recorded. */
    fun saveFrom(base: String?, label: String, block: (VersionBuilder) -> Unit): String {
        val b = VersionBuilder(base, label)
        block(b)
        return try {
            commit(b, expectedBase = base, becomeCurrent = true)
        } catch (c: ConflictDetected) {
            // keep this branch as a non-current version, record a resolvable conflict
            commit(b, becomeCurrent = false, checkBase = false)
            val conflictId = newId()
            conn.prepareStatement("INSERT INTO conflicts(id,base,a,b,status,resolution,created) VALUES(?,?,?,?,?,?,?)").use { ps ->
                ps.setString(1, conflictId); ps.setString(2, base); ps.setString(3, c.current); ps.setString(4, b.id)
                ps.setString(5, "OPEN"); ps.setString(6, null); ps.setString(7, now()); ps.executeUpdate()
            }
            throw ConflictWithBranch(c.current, b.id, conflictId)
        }
    }

    class ConflictWithBranch(val currentVersion: String, val branchVersion: String, val conflictId: String) :
        Exception("conflict $conflictId: current=$currentVersion branch=$branchVersion")

    /** Resolve a conflict by choosing one side. The unchosen side is retained, never overwritten. */
    fun resolveConflict(conflictId: String, chooseVersion: String, rationale: String) {
        val conflict = conflicts().firstOrNull { it.id == conflictId } ?: error("no conflict $conflictId")
        require(chooseVersion == conflict.a || chooseVersion == conflict.b) { "chosen version must be one side of the conflict" }
        conn.autoCommit = false
        try {
            conn.prepareStatement("UPDATE conflicts SET status='RESOLVED', resolution=? WHERE id=?").use { ps ->
                ps.setString(1, "chose=$chooseVersion rationale=$rationale"); ps.setString(2, conflictId); ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM current_pointer").use { it.executeUpdate() }
            conn.prepareStatement("INSERT INTO current_pointer(version) VALUES(?)").use { ps -> ps.setString(1, chooseVersion); ps.executeUpdate() }
            conn.commit()
        } catch (t: Throwable) { conn.rollback(); throw t } finally { conn.autoCommit = true }
    }

    fun export(): ExportBundle = ExportBundle(
        SCHEMA_VERSION, versions(), currentVersion(),
        versions().flatMap { sourcesOf(it.id) },
        versions().flatMap { eventsOf(it.id) },
        versions().flatMap { derivedAll(it.id) },
        conflicts()
    )

    /** Import into an (empty) database. Stable identities, order and provenance are preserved verbatim. */
    fun import(bundle: ExportBundle) {
        conn.autoCommit = false
        try {
            for (v in bundle.versions) conn.prepareStatement("INSERT OR IGNORE INTO versions(id,seq,parent,label,state,created) VALUES(?,?,?,?,?,?)").use { ps ->
                ps.setString(1, v.id); ps.setInt(2, v.seq); ps.setString(3, v.parent); ps.setString(4, v.label); ps.setString(5, v.state); ps.setString(6, v.created); ps.executeUpdate()
            }
            for (s in bundle.sources) conn.prepareStatement("INSERT OR IGNORE INTO sources(id,version,kind,seq,payload) VALUES(?,?,?,?,?)").use { ps ->
                ps.setString(1, s.id); ps.setString(2, s.version); ps.setString(3, s.kind); ps.setInt(4, s.seq); ps.setString(5, s.payload); ps.executeUpdate()
            }
            for (e in bundle.events) conn.prepareStatement("INSERT OR IGNORE INTO events(id,version,seq,kind,payload,created) VALUES(?,?,?,?,?,?)").use { ps ->
                ps.setString(1, e.id); ps.setString(2, e.version); ps.setInt(3, e.seq); ps.setString(4, e.kind); ps.setString(5, e.payload); ps.setString(6, e.created); ps.executeUpdate()
            }
            for (d in bundle.derived) conn.prepareStatement("INSERT OR IGNORE INTO derived(version,node,status,inputs,payload,explanation) VALUES(?,?,?,?,?,?)").use { ps ->
                ps.setString(1, d.version); ps.setString(2, d.node); ps.setString(3, d.status); ps.setString(4, d.inputs); ps.setString(5, d.payload); ps.setString(6, d.explanation); ps.executeUpdate()
            }
            for (c in bundle.conflicts) conn.prepareStatement("INSERT OR IGNORE INTO conflicts(id,base,a,b,status,resolution,created) VALUES(?,?,?,?,?,?,?)").use { ps ->
                ps.setString(1, c.id); ps.setString(2, c.base); ps.setString(3, c.a); ps.setString(4, c.b); ps.setString(5, c.status); ps.setString(6, c.resolution); ps.setString(7, c.created); ps.executeUpdate()
            }
            if (bundle.current != null) {
                conn.prepareStatement("DELETE FROM current_pointer").use { it.executeUpdate() }
                conn.prepareStatement("INSERT INTO current_pointer(version) VALUES(?)").use { ps -> ps.setString(1, bundle.current); ps.executeUpdate() }
            }
            conn.commit()
        } catch (t: Throwable) { conn.rollback(); throw t } finally { conn.autoCommit = true }
    }

    override fun close() { conn.close() }
}
