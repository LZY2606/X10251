package jtag.store

const val SCHEMA_VERSION = 2

val SCHEMA_V2 = """
CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE IF NOT EXISTS imports(id TEXT PRIMARY KEY, kind TEXT, label TEXT, created_seq INTEGER);
CREATE TABLE IF NOT EXISTS src_edges(id TEXT PRIMARY KEY, import_id TEXT, clock_idx INT, tck INT,
    tms INT, tdi INT, tdo INT, time_value REAL, time_unit TEXT, raw TEXT);
CREATE TABLE IF NOT EXISTS src_resets(id TEXT PRIMARY KEY, import_id TEXT, clock_idx INT, kind TEXT, raw TEXT);
CREATE TABLE IF NOT EXISTS src_ir_candidates(id TEXT PRIMARY KEY, import_id TEXT, device_pos INT,
    length_bits INT, basis TEXT, status TEXT, raw TEXT);
CREATE TABLE IF NOT EXISTS src_bsdl_devices(id TEXT PRIMARY KEY, import_id TEXT, device TEXT,
    bsdl_version TEXT, idcode TEXT, ir_length INT, raw TEXT);
CREATE TABLE IF NOT EXISTS src_bsdl_cells(id TEXT PRIMARY KEY, import_id TEXT, device TEXT,
    bsdl_version TEXT, cell INT, pin TEXT, function TEXT, safe TEXT, raw TEXT);
CREATE TABLE IF NOT EXISTS src_board(id TEXT PRIMARY KEY, import_id TEXT, revision TEXT,
    device_pos INT, device TEXT, expected_present INT, raw TEXT);
CREATE TABLE IF NOT EXISTS src_diagnostics(id TEXT PRIMARY KEY, import_id TEXT, source_id TEXT,
    field TEXT, raw_value TEXT, message TEXT);
CREATE TABLE IF NOT EXISTS versions(id TEXT PRIMARY KEY, parent_id TEXT, label TEXT, author TEXT,
    status TEXT, created_seq INTEGER, params TEXT);
CREATE TABLE IF NOT EXISTS version_inputs(version_id TEXT, node TEXT, import_id TEXT,
    PRIMARY KEY(version_id, node));
CREATE TABLE IF NOT EXISTS der_tap(version_id TEXT, identity TEXT, seq INT, clock_idx INT,
    from_state TEXT, to_state TEXT, tms INT, status TEXT, anomaly TEXT,
    PRIMARY KEY(version_id, identity));
CREATE TABLE IF NOT EXISTS der_shift(version_id TEXT, identity TEXT, seq INT, register TEXT,
    start_clock INT, bits_tdo TEXT, bits_tdi TEXT, status TEXT,
    PRIMARY KEY(version_id, identity));
CREATE TABLE IF NOT EXISTS der_device(version_id TEXT, identity TEXT, seq INT, position INT,
    name TEXT, idcode TEXT, bypass INT, ir_length INT, status TEXT,
    PRIMARY KEY(version_id, identity));
CREATE TABLE IF NOT EXISTS der_offset(version_id TEXT, identity TEXT, device_pos INT,
    cell_offset INT, basis TEXT, PRIMARY KEY(version_id, identity));
CREATE TABLE IF NOT EXISTS der_cellpin(version_id TEXT, identity TEXT, device TEXT, cell INT,
    pin TEXT, function TEXT, bsdl_version TEXT, observed_bit INT,
    PRIMARY KEY(version_id, identity));
CREATE TABLE IF NOT EXISTS events(id TEXT PRIMARY KEY, version_id TEXT, seq INT, kind TEXT,
    subject TEXT, accepted INT, rationale TEXT, payload TEXT);
CREATE TABLE IF NOT EXISTS recompute_log(version_id TEXT, node TEXT, action TEXT, reason TEXT,
    PRIMARY KEY(version_id, node));
CREATE TABLE IF NOT EXISTS pointer(id INTEGER PRIMARY KEY CHECK(id=1), version_id TEXT);
CREATE TABLE IF NOT EXISTS conflicts(id TEXT PRIMARY KEY, base_version TEXT, branch_a TEXT,
    branch_b TEXT, status TEXT, resolution TEXT, created_seq INTEGER);
CREATE INDEX IF NOT EXISTS ix_edges_import ON src_edges(import_id);
CREATE INDEX IF NOT EXISTS ix_resets_import ON src_resets(import_id);
CREATE INDEX IF NOT EXISTS ix_cand_import ON src_ir_candidates(import_id);
CREATE INDEX IF NOT EXISTS ix_cell_import ON src_bsdl_cells(import_id);
CREATE INDEX IF NOT EXISTS ix_board_import ON src_board(import_id);
CREATE INDEX IF NOT EXISTS ix_events_version ON events(version_id);
"""

/** The oldest schema this build can migrate FROM (used by the migration test). */
val SCHEMA_V1_PARTIAL = """
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE imports(id TEXT PRIMARY KEY, kind TEXT, label TEXT, created_seq INTEGER);
CREATE TABLE src_edges(id TEXT PRIMARY KEY, import_id TEXT, clock_idx INT, tck INT,
    tms INT, tdi INT, tdo INT, time_value REAL, time_unit TEXT, raw TEXT);
CREATE TABLE src_resets(id TEXT PRIMARY KEY, import_id TEXT, clock_idx INT, kind TEXT, raw TEXT);
CREATE TABLE src_ir_candidates(id TEXT PRIMARY KEY, import_id TEXT, device_pos INT,
    length_bits INT, basis TEXT, status TEXT, raw TEXT);
CREATE TABLE src_bsdl_devices(id TEXT PRIMARY KEY, import_id TEXT, device TEXT,
    bsdl_version TEXT, idcode TEXT, ir_length INT, raw TEXT);
CREATE TABLE src_bsdl_cells(id TEXT PRIMARY KEY, import_id TEXT, device TEXT,
    bsdl_version TEXT, cell INT, pin TEXT, function TEXT, safe TEXT, raw TEXT);
CREATE TABLE src_board(id TEXT PRIMARY KEY, import_id TEXT, revision TEXT,
    device_pos INT, device TEXT, expected_present INT, raw TEXT);
CREATE TABLE src_diagnostics(id TEXT PRIMARY KEY, import_id TEXT, source_id TEXT,
    field TEXT, raw_value TEXT, message TEXT);
CREATE TABLE versions(id TEXT PRIMARY KEY, parent_id TEXT, label TEXT,
    status TEXT, created_seq INTEGER, params TEXT);
CREATE TABLE version_inputs(version_id TEXT, node TEXT, import_id TEXT,
    PRIMARY KEY(version_id, node));
CREATE TABLE der_tap(version_id TEXT, identity TEXT, seq INT, clock_idx INT,
    from_state TEXT, to_state TEXT, tms INT, status TEXT, anomaly TEXT,
    PRIMARY KEY(version_id, identity));
CREATE TABLE der_shift(version_id TEXT, identity TEXT, seq INT, register TEXT,
    start_clock INT, bits_tdo TEXT, bits_tdi TEXT, status TEXT,
    PRIMARY KEY(version_id, identity));
CREATE TABLE der_device(version_id TEXT, identity TEXT, seq INT, position INT,
    name TEXT, idcode TEXT, bypass INT, ir_length INT, status TEXT,
    PRIMARY KEY(version_id, identity));
CREATE TABLE der_offset(version_id TEXT, identity TEXT, device_pos INT,
    cell_offset INT, basis TEXT, PRIMARY KEY(version_id, identity));
CREATE TABLE der_cellpin(version_id TEXT, identity TEXT, device TEXT, cell INT,
    pin TEXT, function TEXT, bsdl_version TEXT, observed_bit INT,
    PRIMARY KEY(version_id, identity));
CREATE TABLE events(id TEXT PRIMARY KEY, version_id TEXT, seq INT, kind TEXT,
    subject TEXT, accepted INT, rationale TEXT, payload TEXT);
CREATE TABLE recompute_log(version_id TEXT, node TEXT, action TEXT, reason TEXT,
    PRIMARY KEY(version_id, node));
CREATE TABLE pointer(id INTEGER PRIMARY KEY CHECK(id=1), version_id TEXT);
INSERT INTO meta(key, value) VALUES ('schema_version', '1');
"""
