package jtag

import java.sql.Connection
import java.sql.DriverManager

const val SCHEMA_VERSION = 2

/**
 * SQLite store with versioned tables. Every derivation writes into a new
 * version row (status=building) and only flips the current pointer inside the
 * same transaction that marks the version complete. A process killed mid-write
 * therefore leaves either the old version or a complete new one; on startup
 * any leftover building versions are aborted.
 */
class Db(path: String) {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path")

    init {
        conn.autoCommit = true
        exec("PRAGMA journal_mode=WAL")
        exec("PRAGMA foreign_keys=ON")
        migrate()
        recover()
    }

    fun exec(sql: String) = conn.createStatement().use { it.execute(sql) }

    private fun migrate() {
        exec("CREATE TABLE IF NOT EXISTS schema_meta(version INTEGER NOT NULL)")
        val v = query("SELECT version FROM schema_meta") { it.getInt(1) }.firstOrNull()
        if (v == null) {
            exec("INSERT INTO schema_meta VALUES ($SCHEMA_VERSION)")
            createSchemaV2()
        } else if (v < 2) {
            // v1 -> v2: quantities gained an explicit coordinate base column
            exec("ALTER TABLE sample_edges ADD COLUMN time_base TEXT")
            exec("ALTER TABLE tap_steps ADD COLUMN time_base TEXT")
            exec("UPDATE schema_meta SET version=$SCHEMA_VERSION")
        }
    }

    private fun createSchemaV2() {
        val ddl = """
        CREATE TABLE IF NOT EXISTS versions(id TEXT PRIMARY KEY, kind TEXT NOT NULL, seq INTEGER NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL, note TEXT);
        CREATE TABLE IF NOT EXISTS current_pointer(kind TEXT PRIMARY KEY, version_id TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS sample_edges(id TEXT PRIMARY KEY, version_id TEXT NOT NULL, idx INTEGER NOT NULL, time_raw TEXT, time_value REAL, time_unit TEXT, time_base TEXT, tms INT, tck INT, tdi INT, tdo INT, ambiguous INT, raw TEXT);
        CREATE TABLE IF NOT EXISTS reset_events(id TEXT PRIMARY KEY, version_id TEXT NOT NULL, at_sample INT, kind TEXT, detail TEXT);
        CREATE TABLE IF NOT EXISTS ir_candidates(id TEXT PRIMARY KEY, version_id TEXT NOT NULL, position INT, length INT, source TEXT, rationale TEXT);
        CREATE TABLE IF NOT EXISTS bsdl_maps(id TEXT PRIMARY KEY, version_id TEXT NOT NULL, device TEXT, bsdl_version TEXT, payload TEXT);
        CREATE TABLE IF NOT EXISTS board_revisions(id TEXT PRIMARY KEY, version_id TEXT NOT NULL, rev TEXT, payload TEXT);
        CREATE TABLE IF NOT EXISTS derivations(id TEXT PRIMARY KEY, version_id TEXT NOT NULL, kind TEXT, status TEXT, inputs TEXT, outputs TEXT, valid_reason TEXT, stale_reason TEXT);
        CREATE TABLE IF NOT EXISTS tap_steps(id TEXT PRIMARY KEY, derivation_id TEXT, idx INT, segment INT, from_state TEXT, to_state TEXT, tms INT, time_raw TEXT, time_base TEXT, flags TEXT);
        CREATE TABLE IF NOT EXISTS shift_streams(id TEXT PRIMARY KEY, derivation_id TEXT, segment INT, kind TEXT, bits_in TEXT, bits_out TEXT, start_step INT, end_step INT);
        CREATE TABLE IF NOT EXISTS chain_devices(id TEXT PRIMARY KEY, derivation_id TEXT, position INT, device TEXT, ir_length INT, status TEXT, candidates TEXT);
        CREATE TABLE IF NOT EXISTS offset_candidates(id TEXT PRIMARY KEY, derivation_id TEXT, device TEXT, offsets TEXT);
        CREATE TABLE IF NOT EXISTS cell_pin_evidence(id TEXT PRIMARY KEY, derivation_id TEXT, device TEXT, cell INT, port TEXT, pin TEXT, bsdl_version TEXT);
        CREATE TABLE IF NOT EXISTS events(id TEXT PRIMARY KEY, kind TEXT, payload TEXT, derivation_id TEXT, source_refs TEXT, decided INT, created_at TEXT);
        CREATE TABLE IF NOT EXISTS conflicts(id TEXT PRIMARY KEY, store TEXT, base_version TEXT, current_version TEXT, incoming TEXT, existing TEXT, status TEXT, resolution TEXT);
        CREATE TABLE IF NOT EXISTS counterexamples(id TEXT PRIMARY KEY, name TEXT, baseline TEXT, mutation TEXT, expected_scope TEXT, observed_scope TEXT, adopted INT, retained TEXT);
        CREATE TABLE IF NOT EXISTS diagnostics(id TEXT PRIMARY KEY, where TEXT, message TEXT, raw TEXT);
        """.trimIndent()
        ddl.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { exec(it) }
    }

    /** Crash recovery: abort any version left in 'building' state. */
    private fun recover() {
        exec("UPDATE versions SET status='aborted' WHERE status='building'")
    }

    fun <T> query(sql: String, vararg args: Any?, map: (java.sql.ResultSet) -> T): List<T> {
        conn.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            val rs = ps.executeQuery()
            val out = mutableListOf<T>()
            while (rs.next()) out += map(rs)
            return out
        }
    }

    fun update(sql: String, vararg args: Any?): Int {
        conn.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            return ps.executeUpdate()
        }
    }

    fun <T> tx(block: () -> T): T {
        conn.autoCommit = false
        try {
            val r = block()
            conn.commit()
            return r
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    fun close() = conn.close()
}
