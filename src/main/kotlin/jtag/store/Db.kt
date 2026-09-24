package jtag.store

import java.sql.Connection
import java.sql.DriverManager

const val SCHEMA_VERSION = 2

/** v1 schema: no events.branch column, no conflicts table. */
val SCHEMA_V1 = """
CREATE TABLE schema_meta(version INTEGER NOT NULL);
INSERT INTO schema_meta(version) VALUES (1);
CREATE TABLE sample_sets(id TEXT PRIMARY KEY, name TEXT, polarity TEXT, time_unit TEXT,
  sample_rate_hz REAL, created_seq INTEGER);
CREATE TABLE sample_edges(id TEXT PRIMARY KEY, sample_set_id TEXT, seq INTEGER,
  tck INTEGER, tms INTEGER, tdi INTEGER, tdo INTEGER,
  time_value REAL, time_unit TEXT, raw TEXT);
CREATE TABLE reset_events(id TEXT PRIMARY KEY, sample_set_id TEXT, edge_seq INTEGER,
  kind TEXT, note TEXT, raw TEXT);
CREATE TABLE ir_candidates(id TEXT PRIMARY KEY, sample_set_id TEXT, lengths TEXT,
  status TEXT, source TEXT, reason TEXT, created_seq INTEGER);
CREATE TABLE bsdl_packages(id TEXT PRIMARY KEY, entity TEXT, version TEXT,
  raw_text TEXT, created_seq INTEGER);
CREATE TABLE bsdl_pin_maps(id TEXT PRIMARY KEY, bsdl_id TEXT, port TEXT, pin TEXT,
  basis TEXT, raw TEXT);
CREATE TABLE board_revisions(id TEXT PRIMARY KEY, board TEXT, revision TEXT,
  expected_devices TEXT, note TEXT, raw TEXT);
CREATE TABLE derivations(id TEXT PRIMARY KEY, kind TEXT, version INTEGER, branch TEXT,
  inputs TEXT, fingerprint TEXT, status TEXT, result TEXT, created_seq INTEGER);
CREATE TABLE current_pointer(kind TEXT PRIMARY KEY, derivation_id TEXT);
CREATE TABLE events(id TEXT PRIMARY KEY, seq INTEGER, kind TEXT,
  subject TEXT, payload TEXT, derivation_id TEXT, adopted INTEGER);
CREATE TABLE docs(id TEXT PRIMARY KEY, version INTEGER, content TEXT, author TEXT);
""".trimIndent()

val MIGRATION_1_TO_2 = """
ALTER TABLE events ADD COLUMN branch TEXT NOT NULL DEFAULT 'main';
CREATE TABLE conflicts(id TEXT PRIMARY KEY, doc_id TEXT, base_version INTEGER,
  branch_a TEXT, branch_b TEXT, author_a TEXT, author_b TEXT,
  status TEXT, chosen TEXT, created_seq INTEGER);
UPDATE schema_meta SET version = 2;
""".trimIndent()

val SCHEMA_V2_EXTRA = """
CREATE TABLE IF NOT EXISTS conflicts(id TEXT PRIMARY KEY, doc_id TEXT, base_version INTEGER,
  branch_a TEXT, branch_b TEXT, author_a TEXT, author_b TEXT,
  status TEXT, chosen TEXT, created_seq INTEGER);
""".trimIndent()

class Db(val path: String) : AutoCloseable {
    val conn: Connection = DriverManager.getConnection("jdbc:sqlite:$path").apply {
        createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
    }

    init { migrate() }

    private fun migrate() {
        conn.autoCommit = false
        try {
            val hasMeta = tableExists("schema_meta")
            if (!hasMeta) {
                conn.createStatement().use { st ->
                    SCHEMA_V1.split(";").map { it.trim() }.filter { it.isNotEmpty() }
                        .forEach { st.execute(it) }
                    // fresh DB goes straight to current version
                    MIGRATION_1_TO_2.split(";").map { it.trim() }.filter { it.isNotEmpty() }
                        .forEach { st.execute(it) }
                }
            } else {
                val v = conn.createStatement().use { st ->
                    st.executeQuery("SELECT version FROM schema_meta").let { rs ->
                        if (rs.next()) rs.getInt(1) else 1
                    }
                }
                if (v < 2) {
                    conn.createStatement().use { st ->
                        MIGRATION_1_TO_2.split(";").map { it.trim() }.filter { it.isNotEmpty() }
                            .forEach { st.execute(it) }
                    }
                }
            }
            conn.createStatement().use { it.execute(SCHEMA_V2_EXTRA) }
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = true
        }
    }

    private fun tableExists(name: String): Boolean =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='$name'")
                .let { it.next() }
        }

    fun nextSeq(table: String, column: String): Int = conn.createStatement().use { st ->
        st.executeQuery("SELECT COALESCE(MAX($column),0)+1 FROM $table").let { rs ->
            rs.next(); rs.getInt(1)
        }
    }

    override fun close() = conn.close()
}
