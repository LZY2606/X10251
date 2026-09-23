package gsb.store

import java.sql.Connection
import java.sql.DriverManager

const val SCHEMA_VERSION = 2

/**
 * SQLite storage. Derivations are written as BUILDING versions first; the
 * current pointer is switched only in the same transaction that marks the
 * version COMPLETE, so a crash mid-write leaves either the old version or a
 * complete new one.
 */
class Db(path: String) : AutoCloseable {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    init {
        conn.autoCommit = true
        conn.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
        conn.createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
        migrate()
    }

    private fun schemaVersion(): Int {
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='meta'")
            if (!rs.next()) return 0
        }
        conn.createStatement().use { st ->
            val rs = st.executeQuery("SELECT value FROM meta WHERE key='schema_version'")
            if (rs.next()) return rs.getString(1).toInt()
        }
        return 1 // meta exists but no version row -> legacy v1
    }

    private fun migrate() {
        when (schemaVersion()) {
            0 -> createV2()
            1 -> migrateV1toV2()
            SCHEMA_VERSION -> Unit
            else -> error("unsupported schema version")
        }
    }

    private fun createV2() {
        conn.createStatement().use { st ->
            st.execute("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            st.execute(
                """CREATE TABLE IF NOT EXISTS sources(
                     id TEXT NOT NULL, revision INTEGER NOT NULL, kind TEXT NOT NULL,
                     content_json TEXT NOT NULL, raw_text TEXT NOT NULL,
                     seq INTEGER PRIMARY KEY AUTOINCREMENT,
                     UNIQUE(id, revision))"""
            )
            st.execute(
                """CREATE TABLE IF NOT EXISTS branches(
                     id TEXT PRIMARY KEY, name TEXT NOT NULL,
                     base_version_id TEXT, head_version_id TEXT,
                     seq INTEGER NOT NULL)"""
            )
            st.execute(
                """CREATE TABLE IF NOT EXISTS versions(
                     id TEXT PRIMARY KEY, branch_id TEXT NOT NULL, parent_id TEXT,
                     label TEXT NOT NULL, status TEXT NOT NULL,
                     inputs_json TEXT NOT NULL DEFAULT '{}', nodes_json TEXT NOT NULL DEFAULT '{}',
                     seq INTEGER NOT NULL)"""
            )
            st.execute(
                """CREATE TABLE IF NOT EXISTS current_pointer(
                     branch_id TEXT PRIMARY KEY, version_id TEXT NOT NULL)"""
            )
            st.execute(
                """CREATE TABLE IF NOT EXISTS events(
                     id TEXT PRIMARY KEY, version_id TEXT, branch_id TEXT,
                     kind TEXT NOT NULL, payload_json TEXT NOT NULL,
                     seq INTEGER NOT NULL)"""
            )
            st.execute(
                """CREATE TABLE IF NOT EXISTS conflicts(
                     id TEXT PRIMARY KEY, branch_id TEXT NOT NULL, base_version_id TEXT NOT NULL,
                     ours_version_id TEXT NOT NULL, theirs_version_id TEXT NOT NULL,
                     status TEXT NOT NULL, resolution_event_id TEXT,
                     seq INTEGER NOT NULL)"""
            )
            st.execute(
                "INSERT OR REPLACE INTO meta(key, value) VALUES('schema_version', '$SCHEMA_VERSION')"
            )
        }
    }

    /** v1 -> v2: legacy sources table gains revision/raw_text; new tables appear. */
    private fun migrateV1toV2() {
        conn.createStatement().use { st ->
            st.execute("ALTER TABLE sources ADD COLUMN revision INTEGER NOT NULL DEFAULT 1")
            st.execute("ALTER TABLE sources ADD COLUMN raw_text TEXT NOT NULL DEFAULT ''")
            st.execute("ALTER TABLE sources ADD COLUMN seq INTEGER")
            // renumber seq deterministically by rowid
            val rs = st.executeQuery("SELECT rowid, id FROM sources ORDER BY rowid")
            val ids = mutableListOf<Pair<Long, String>>()
            while (rs.next()) ids += rs.getLong(1) to rs.getString(2)
            ids.forEachIndexed { i, (rowid, _) ->
                st.execute("UPDATE sources SET seq=${i + 1} WHERE rowid=$rowid")
            }
        }
        // rebuild sources table to enforce UNIQUE(id, revision)
        conn.createStatement().use { st ->
            st.execute(
                """CREATE TABLE sources_v2(
                     id TEXT NOT NULL, revision INTEGER NOT NULL, kind TEXT NOT NULL,
                     content_json TEXT NOT NULL, raw_text TEXT NOT NULL,
                     seq INTEGER PRIMARY KEY,
                     UNIQUE(id, revision))"""
            )
            st.execute(
                "INSERT INTO sources_v2(id, revision, kind, content_json, raw_text, seq) SELECT id, revision, kind, content_json, raw_text, seq FROM sources"
            )
            st.execute("DROP TABLE sources")
            st.execute("ALTER TABLE sources_v2 RENAME TO sources")
        }
        createV2()
    }

    /** Remove half-written versions left behind by an interrupted write. */
    fun recoverInterruptedWrites() {
        conn.createStatement().use { st ->
            st.execute("UPDATE versions SET status='FAILED' WHERE status='BUILDING'")
        }
    }

    override fun close() {
        conn.close()
    }
}
