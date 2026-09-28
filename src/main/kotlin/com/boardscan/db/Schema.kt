package com.boardscan.db

object Schema {
    const val VERSION = 2

    val CREATE = """
    PRAGMA journal_mode=WAL;
    PRAGMA foreign_keys=ON;

    CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);

    -- 五类直接来源严格分表
    CREATE TABLE IF NOT EXISTS sample_import(
        sample_id TEXT PRIMARY KEY,
        board_revision_id TEXT NOT NULL,
        polarity_id TEXT NOT NULL,
        imported_at TEXT NOT NULL,
        raw_json TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS sample_row(
        sample_id TEXT NOT NULL,
        edge_id TEXT NOT NULL,
        ord BIGINT NOT NULL,
        time_raw TEXT NOT NULL,
        tck_raw TEXT NOT NULL,
        tms_raw TEXT NOT NULL,
        tdi_raw TEXT NOT NULL,
        tdo_raw TEXT NOT NULL,
        PRIMARY KEY(sample_id, edge_id)
    );
    CREATE TABLE IF NOT EXISTS reset_event(
        reset_id TEXT PRIMARY KEY,
        sample_id TEXT NOT NULL,
        kind TEXT NOT NULL,
        enter_edge_id TEXT NOT NULL,
        mid_scan INTEGER NOT NULL,
        open_segment_id TEXT,
        seq BIGINT NOT NULL,
        run_id TEXT NOT NULL,
        raw_json TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS ir_candidate(
        candidate_id TEXT PRIMARY KEY,
        run_id TEXT NOT NULL,
        device_ref TEXT NOT NULL,
        length_bits INTEGER NOT NULL,
        source TEXT NOT NULL,
        source_ref TEXT NOT NULL,
        consistent INTEGER NOT NULL,
        note TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS bsdl_file(
        bsdl_id TEXT NOT NULL,
        version TEXT NOT NULL,
        parsed_json TEXT NOT NULL,
        raw_text TEXT NOT NULL,
        imported_at TEXT NOT NULL,
        PRIMARY KEY(bsdl_id, version)
    );
    CREATE TABLE IF NOT EXISTS board_revision(
        board_revision_id TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        revision TEXT NOT NULL,
        coordinate_basis TEXT NOT NULL,
        raw_json TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS polarity_config(
        polarity_id TEXT PRIMARY KEY,
        version INTEGER NOT NULL,
        raw_json TEXT NOT NULL,
        created_at TEXT NOT NULL
    );

    -- 派生版本
    CREATE TABLE IF NOT EXISTS derivation_run(
        run_id TEXT PRIMARY KEY,
        parent_run_id TEXT,
        branch_label TEXT NOT NULL,
        status TEXT NOT NULL,
        created_at TEXT NOT NULL,
        author TEXT NOT NULL,
        fingerprint TEXT NOT NULL,
        expected_parent_run_id TEXT,
        result_json TEXT
    );
    CREATE TABLE IF NOT EXISTS derived_object(
        object_id TEXT NOT NULL,
        run_id TEXT NOT NULL,
        kind TEXT NOT NULL,
        stable_id TEXT NOT NULL,
        seq INTEGER NOT NULL,
        payload_json TEXT NOT NULL,
        PRIMARY KEY(run_id, kind, object_id)
    );
    CREATE TABLE IF NOT EXISTS finding(
        finding_id TEXT PRIMARY KEY,
        run_id TEXT NOT NULL,
        branch_label TEXT NOT NULL,
        code TEXT NOT NULL,
        severity TEXT NOT NULL,
        message TEXT NOT NULL,
        edge_id TEXT,
        refs_json TEXT NOT NULL,
        disposition TEXT NOT NULL,
        decision_id TEXT,
        seq BIGINT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS event_log(
        event_id TEXT PRIMARY KEY,
        run_id TEXT NOT NULL,
        parent_run_id TEXT NOT NULL,
        action TEXT NOT NULL,
        target_finding_id TEXT,
        target_object_id TEXT,
        payload_json TEXT NOT NULL,
        author TEXT NOT NULL,
        created_at TEXT NOT NULL,
        seq BIGINT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS current_pointer(
        scope TEXT PRIMARY KEY,
        run_id TEXT NOT NULL
    );

    -- 导出包清单（重新导入用）
    CREATE TABLE IF NOT EXISTS export_bundle(
        bundle_id TEXT PRIMARY KEY,
        created_at TEXT NOT NULL,
        payload_json TEXT NOT NULL
    );
    """
}
