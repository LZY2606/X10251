package jtap.storage

import java.sql.Connection

/**
 * Versioned, provenance-aware schema.
 *
 * Storage families are physically separated:
 *   sample_*   edges/reset events
 *   bsdl_*     pin/cell mappings
 *   board_*    board revisions and device manifests
 *   ir_*       IR-length candidates
 *   derive_*   derivation versions + current pointer
 *   decide_*   human decisions
 *   dep_*      dependency graph / replay ledger
 *
 * Every derivation version is written in ONE transaction. The current pointer
 * is flipped last inside that transaction. A crash mid-write therefore leaves
 * either the old complete version or the new complete version, never both.
 */
object Schema {
    const val VERSION = 2

    val V1 = listOf(
        """
        CREATE TABLE IF NOT EXISTS meta (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS cases (
            case_id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            created_at TEXT NOT NULL
        )
        """,
        // --- family 1: sample edges ------------------------------------------------
        """
        CREATE TABLE IF NOT EXISTS sample_source (
            source_id TEXT PRIMARY KEY,
            case_id TEXT NOT NULL,
            revision INTEGER NOT NULL,
            label TEXT NOT NULL,
            polarity_raw TEXT NOT NULL,
            polarity_edge TEXT NOT NULL,
            raw_payload TEXT NOT NULL,
            UNIQUE(case_id, label, revision)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sample_edge (
            edge_id TEXT PRIMARY KEY,
            source_id TEXT NOT NULL,
            case_id TEXT NOT NULL,
            revision INTEGER NOT NULL,
            edge_index INTEGER NOT NULL,
            at_value REAL,
            at_unit TEXT NOT NULL,
            at_raw TEXT NOT NULL,
            at_diagnostic TEXT,
            tms TEXT NOT NULL,
            tck TEXT NOT NULL,
            tdi TEXT NOT NULL,
            tdo TEXT NOT NULL,
            declared_state TEXT,
            diagnostics_json TEXT NOT NULL
        )
        """,
        // --- family 2: reset events (separate table) --------------------------------
        """
        CREATE TABLE IF NOT EXISTS reset_event (
            event_id TEXT PRIMARY KEY,
            case_id TEXT NOT NULL,
            source_id TEXT NOT NULL,
            revision INTEGER NOT NULL,
            edge_index INTEGER NOT NULL,
            at_value REAL,
            at_unit TEXT NOT NULL,
            at_raw TEXT NOT NULL,
            cause TEXT NOT NULL,
            mid_scan INTEGER NOT NULL,
            abandoned_bits INTEGER NOT NULL,
            detail TEXT NOT NULL
        )
        """,
        // --- family 3: BSDL pin maps -----------------------------------------------
        """
        CREATE TABLE IF NOT EXISTS bsdl_source (
            source_id TEXT PRIMARY KEY,
            case_id TEXT NOT NULL,
            revision INTEGER NOT NULL,
            device_name TEXT NOT NULL,
            bsdl_version TEXT NOT NULL,
            idcode_mask TEXT,
            ir_length INTEGER,
            ir_length_raw TEXT,
            raw_payload TEXT NOT NULL,
            diagnostics_json TEXT NOT NULL,
            UNIQUE(case_id, device_name, revision)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS bsdl_cell (
            id TEXT PRIMARY KEY,
            source_id TEXT NOT NULL,
            case_id TEXT NOT NULL,
            revision INTEGER NOT NULL,
            cell_number INTEGER NOT NULL,
            port_name TEXT NOT NULL,
            cell_type TEXT NOT NULL,
            function TEXT NOT NULL,
            safe_bit TEXT,
            board_revision TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS bsdl_opcode (
            id TEXT PRIMARY KEY,
            source_id TEXT NOT NULL,
            name TEXT NOT NULL,
            relevant_bits INTEGER NOT NULL
        )
        """,
        // --- family 4: board revisions ---------------------------------------------
        """
        CREATE TABLE IF NOT EXISTS board_revision (
            source_id TEXT PRIMARY KEY,
            case_id TEXT NOT NULL,
            revision INTEGER NOT NULL,
            board_revision TEXT NOT NULL,
            raw_payload TEXT NOT NULL,
            UNIQUE(case_id, board_revision)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS board_device (
            id TEXT PRIMARY KEY,
            source_id TEXT NOT NULL,
            position INTEGER NOT NULL,
            expected_name TEXT NOT NULL,
            bsdl_source_id TEXT,
            expected_idcode TEXT
        )
        """,
        // --- family 5: IR length candidates ----------------------------------------
        """
        CREATE TABLE IF NOT EXISTS ir_candidate (
            candidate_id TEXT PRIMARY KEY,
            case_id TEXT NOT NULL,
            derivation_id TEXT,
            lengths_json TEXT NOT NULL,
            total INTEGER NOT NULL,
            evidence_json TEXT NOT NULL,
            adopted INTEGER NOT NULL,
            reason TEXT NOT NULL
        )
        """,
        // --- derivations (versioned results) ---------------------------------------
        """
        CREATE TABLE IF NOT EXISTS derivation (
            derivation_id TEXT PRIMARY KEY,
            case_id TEXT NOT NULL,
            ordinal INTEGER NOT NULL,
            parent_derivation_id TEXT,
            status TEXT NOT NULL,
            basis_json TEXT NOT NULL,
            fingerprint TEXT NOT NULL,
            created_at TEXT NOT NULL,
            UNIQUE(case_id, ordinal)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS derivation_current (
            case_id TEXT PRIMARY KEY,
            derivation_id TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS tap_transition (
            id TEXT PRIMARY KEY,
            derivation_id TEXT NOT NULL,
            edge_index INTEGER NOT NULL,
            ordinal INTEGER NOT NULL,
            from_state TEXT NOT NULL,
            to_state TEXT,
            kind TEXT NOT NULL,
            tms_raw TEXT NOT NULL,
            tdi_raw TEXT NOT NULL,
            tdo_raw TEXT NOT NULL,
            ones_run INTEGER NOT NULL,
            abandoned INTEGER NOT NULL,
            diagnostic TEXT,
            edge_id TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS scan_segment (
            segment_id TEXT PRIMARY KEY,
            derivation_id TEXT NOT NULL,
            kind TEXT NOT NULL,
            enter_edge INTEGER NOT NULL,
            tdi_bits TEXT NOT NULL,
            tdo_bits TEXT NOT NULL,
            aborted INTEGER NOT NULL,
            abandoned_bits INTEGER NOT NULL,
            reset_edge INTEGER
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS chain_device (
            device_id TEXT PRIMARY KEY,
            derivation_id TEXT NOT NULL,
            position INTEGER NOT NULL,
            expected_name TEXT NOT NULL,
            bsdl_version TEXT,
            idcode_observed TEXT,
            present INTEGER NOT NULL,
            bypass INTEGER NOT NULL,
            ir_length INTEGER,
            dr_offset INTEGER NOT NULL,
            dr_length INTEGER NOT NULL,
            offset_candidates_json TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS display_value (
            value_id TEXT PRIMARY KEY,
            derivation_id TEXT NOT NULL,
            kind TEXT NOT NULL,
            stable_key TEXT NOT NULL,
            payload_json TEXT NOT NULL
        )
        """,
        // --- machine-readable judgement events -------------------------------------
        """
        CREATE TABLE IF NOT EXISTS judgement_event (
            event_id TEXT PRIMARY KEY,
            case_id TEXT NOT NULL,
            derivation_id TEXT,
            rule_code TEXT NOT NULL,
            machine_rule TEXT NOT NULL,
            severity TEXT NOT NULL,
            message TEXT NOT NULL,
            edge_index INTEGER,
            subjects_json TEXT NOT NULL,
            candidates_json TEXT NOT NULL,
            adopted INTEGER,
            basis_json TEXT NOT NULL
        )
        """,
        // --- decisions --------------------------------------------------------------
        """
        CREATE TABLE IF NOT EXISTS decision (
            decision_id TEXT PRIMARY KEY,
            case_id TEXT NOT NULL,
            ordinal INTEGER NOT NULL,
            at_event_id TEXT,
            action TEXT NOT NULL,
            chosen_candidate_id TEXT,
            rejected_json TEXT NOT NULL,
            parent_derivation_id TEXT,
            note TEXT NOT NULL,
            actor TEXT NOT NULL,
            UNIQUE(case_id, ordinal)
        )
        """,
        // --- dependency graph / replay ledger --------------------------------------
        """
        CREATE TABLE IF NOT EXISTS dep_node (
            node_id TEXT PRIMARY KEY,
            case_id TEXT NOT NULL,
            derivation_id TEXT NOT NULL,
            kind TEXT NOT NULL,
            source_fingerprint TEXT NOT NULL,
            status TEXT NOT NULL,
            rationale TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS dep_edge (
            from_node TEXT NOT NULL,
            to_node TEXT NOT NULL,
            PRIMARY KEY(from_node, to_node)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS replay_ledger (
            case_id TEXT NOT NULL,
            seq INTEGER NOT NULL,
            derivation_id TEXT NOT NULL,
            scenario TEXT NOT NULL,
            outcome TEXT NOT NULL,
            detail TEXT NOT NULL,
            PRIMARY KEY(case_id, seq)
        )
        """,
    )

    /** v1 -> v2: keep an explicit migration rather than dropping history. */
    private val V2 = listOf(
        """
        CREATE TABLE IF NOT EXISTS schema_migration_log (
            version INTEGER PRIMARY KEY,
            applied_at TEXT NOT NULL,
            note TEXT NOT NULL
        )
        """,
        """
        ALTER TABLE sample_source ADD COLUMN superseded_by TEXT
        """,
    )

    fun migrate(conn: Connection) {
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA foreign_keys=ON")
        }
        V1.forEach { conn.createStatement().use { it.execute(it) } }
        val current = conn.createStatement().executeQuery("PRAGMA user_version").use {
            if (it.next()) it.getInt(1) else 0
        }
        if (current < 2) {
            // ALTER may already exist on partially migrated files.
            conn.createStatement().use { runCatching { it.execute(V2[0]) } }
            val altered = runCatching {
                conn.createStatement().use { it.execute(V2[1]) }
            }.isSuccess
            conn.createStatement().use {
                it.execute(
                    "INSERT OR REPLACE INTO schema_migration_log(version, applied_at, note) " +
                        "VALUES (2, ${quote(now())}, ${quote(if (altered) "added sample_source.superseded_by" else "column already present")})"
                )
            }
            conn.createStatement().use { it.execute("PRAGMA user_version=2") }
        }
        conn.createStatement().use {
            it.execute("INSERT OR REPLACE INTO meta(key,value) VALUES('schema_version','$VERSION')")
        }
    }

    private fun quote(s: String) = "'" + s.replace("'", "''") + "'"
    internal fun now(): String = java.time.Instant.now().toString()
}
