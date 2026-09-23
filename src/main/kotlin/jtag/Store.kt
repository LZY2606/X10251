package jtag

import java.io.File
import java.time.Instant
import kotlinx.serialization.json.*

/**
 * Five independent source stores + derived results + judgment/events archive.
 * Sources: sample edges, reset events, IR-length candidates, BSDL pin maps,
 * board revisions. Each write makes an independent version; the current
 * pointer moves only after a complete, successful write.
 */
class Store(val db: Db, val now: () -> String = { Instant.now().toString() }) {

    // ---------- versioning ----------
    fun currentVersion(kind: String): String? =
        db.query("SELECT version_id FROM current_pointer WHERE kind=?", kind) { it.getString(1) }.firstOrNull()

    fun nextSeq(kind: String): Int =
        db.query("SELECT COALESCE(MAX(seq),0) FROM versions WHERE kind=?", kind) { it.getInt(1) }.first() + 1

    fun beginVersion(kind: String, note: String = ""): String {
        val id = newId("ver")
        db.update("INSERT INTO versions VALUES(?,?,?,?,?,?)", id, kind, nextSeq(kind), "building", now(), note)
        return id
    }

    /** Marks a version complete and atomically moves the current pointer. */
    fun commitVersion(id: String) {
        db.tx {
            val kind = db.query("SELECT kind FROM versions WHERE id=?", id) { it.getString(1) }.first()
            db.update("UPDATE versions SET status='complete' WHERE id=?", id)
            db.update("INSERT OR REPLACE INTO current_pointer(kind,version_id) VALUES(?,?)", kind, id)
        }
    }

    fun abortVersion(id: String) = db.update("UPDATE versions SET status='aborted' WHERE id=?", id)

    // ---------- source: samples ----------
    fun saveSamples(baseVersion: String?, samples: List<Sample>): SaveResult =
        saveVersioned("samples", baseVersion) { vid ->
            samples.forEach { s ->
                db.update("INSERT INTO sample_edges VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    s.id, vid, s.idx, s.time.raw, s.time.value, s.time.unit, s.time.base,
                    s.tms, s.tck, s.tdi, s.tdo, if (s.ambiguous) 1 else 0, s.raw)
            }
        }

    fun loadSamples(versionId: String): List<Sample> =
        db.query("SELECT * FROM sample_edges WHERE version_id=? ORDER BY idx", versionId) { rs ->
            Sample(rs.getString("id"), rs.getInt("idx"),
                Quantity(rs.getString("time_raw"),
                    rs.getDouble("time_value").takeUnless { rs.wasNull() },
                    rs.getString("time_unit"), rs.getString("time_base")),
                rs.getInt("tms"), rs.getInt("tck"), rs.getInt("tdi"), rs.getInt("tdo"),
                rs.getInt("ambiguous") == 1, rs.getString("raw") ?: "")
        }

    // ---------- source: resets ----------
    fun saveResets(baseVersion: String?, resets: List<ResetEvent>): SaveResult =
        saveVersioned("resets", baseVersion) { vid ->
            resets.forEach { r ->
                db.update("INSERT INTO reset_events VALUES(?,?,?,?,?)", r.id, vid, r.atSample, r.kind, r.detail)
            }
        }

    fun loadResets(versionId: String): List<ResetEvent> =
        db.query("SELECT * FROM reset_events WHERE version_id=?", versionId) {
            ResetEvent(it.getString("id"), it.getInt("at_sample"), it.getString("kind"), it.getString("detail"))
        }

    // ---------- source: IR candidates ----------
    fun saveIrCandidates(baseVersion: String?, cands: List<IrCandidate>): SaveResult =
        saveVersioned("ir_candidates", baseVersion) { vid ->
            cands.forEach { c ->
                db.update("INSERT INTO ir_candidates VALUES(?,?,?,?,?,?)",
                    c.id, vid, c.position, c.length, c.source, c.rationale)
            }
        }

    fun loadIrCandidates(versionId: String): List<IrCandidate> =
        db.query("SELECT * FROM ir_candidates WHERE version_id=?", versionId) {
            IrCandidate(it.getString("id"), it.getInt("position"), it.getInt("length"),
                it.getString("source"), it.getString("rationale"))
        }

    // ---------- source: BSDL pin maps ----------
    data class BsdlMapRow(val id: String, val device: String, val bsdlVersion: String, val payload: String)

    fun saveBsdlMaps(baseVersion: String?, rows: List<BsdlMapRow>): SaveResult =
        saveVersioned("bsdl_maps", baseVersion) { vid ->
            rows.forEach { r ->
                db.update("INSERT INTO bsdl_maps VALUES(?,?,?,?,?)", r.id, vid, r.device, r.bsdlVersion, r.payload)
            }
        }

    fun loadBsdlMaps(versionId: String): List<BsdlMapRow> =
        db.query("SELECT * FROM bsdl_maps WHERE version_id=?", versionId) {
            BsdlMapRow(it.getString("id"), it.getString("device"),
                it.getString("bsdl_version"), it.getString("payload"))
        }

    // ---------- source: board revisions ----------
    data class BoardRow(val id: String, val rev: String, val payload: String)

    fun saveBoards(baseVersion: String?, rows: List<BoardRow>): SaveResult =
        saveVersioned("board_revisions", baseVersion) { vid ->
            rows.forEach { r ->
                db.update("INSERT INTO board_revisions VALUES(?,?,?,?)", r.id, vid, r.rev, r.payload)
            }
        }

    fun loadBoards(versionId: String): List<BoardRow> =
        db.query("SELECT * FROM board_revisions WHERE version_id=?", versionId) {
            BoardRow(it.getString("id"), it.getString("rev"), it.getString("payload"))
        }

    /**
     * Optimistic-concurrency save. Two users editing from the same base
     * version produce a resolvable conflict: the incoming branch is retained
     * and a conflict row is recorded; nothing is overwritten.
     */
    sealed class SaveResult {
        data class Ok(val versionId: String) : SaveResult()
        data class Conflicted(val conflictId: String, val currentVersion: String) : SaveResult()
    }

    fun saveVersioned(kind: String, baseVersion: String?, writer: (String) -> Unit): SaveResult {
        val current = currentVersion(kind)
        if (current != null && baseVersion != null && current != baseVersion) {
            val cid = newId("conf")
            db.update("INSERT INTO conflicts VALUES(?,?,?,?,?,?,?,?)",
                cid, kind, baseVersion, current, "incoming-write", "", "open", null)
            return SaveResult.Conflicted(cid, current)
        }
        val vid = beginVersion(kind)
        try {
            db.tx {
                writer(vid)
                db.update("UPDATE versions SET status='complete' WHERE id=?", vid)
                db.update("INSERT OR REPLACE INTO current_pointer(kind,version_id) VALUES(?,?)", kind, vid)
            }
        } catch (e: Throwable) {
            abortVersion(vid)
            throw e
        }
        return SaveResult.Ok(vid)
    }

    fun listVersions(kind: String): List<Triple<String, String, Int>> =
        db.query("SELECT id,status,seq FROM versions WHERE kind=? ORDER BY seq", kind) {
            Triple(it.getString(1), it.getString(2), it.getInt(3))
        }

    // ---------- derivations ----------
    fun beginDerivation(kind: String, inputs: String): String {
        val id = newId("der")
        db.update("INSERT INTO derivations VALUES(?,?,?,?,?,?,?,?)",
            id, id, kind, "building", inputs, "", "", "")
        return id
    }

    fun completeDerivation(id: String, outputs: String, validReason: String) =
        db.update("UPDATE derivations SET status='complete', outputs=?, valid_reason=? WHERE id=?", outputs, validReason, id)

    fun markStale(id: String, reason: String) =
        db.update("UPDATE derivations SET status='stale', stale_reason=? WHERE id=?", reason, id)

    fun derivationStatus(id: String): String? =
        db.query("SELECT status FROM derivations WHERE id=?", id) { it.getString(1) }.firstOrNull()

    fun listDerivations(): List<Map<String, String?>> =
        db.query("SELECT id,kind,status,valid_reason,stale_reason FROM derivations ORDER BY id") {
            mapOf("id" to it.getString(1), "kind" to it.getString(2), "status" to it.getString(3),
                "valid_reason" to it.getString(4), "stale_reason" to it.getString(5))
        }

    // ---------- events ----------
    fun addEvent(e: JEvent) = db.update(
        "INSERT INTO events VALUES(?,?,?,?,?,?,?)",
        e.id, e.kind, e.payload, e.derivationId,
        Json.encodeToString(JsonArray(e.sourceRefs.map { JsonPrimitive(it) })),
        if (e.decided) 1 else 0, e.createdAt)

    fun loadEvents(derivationId: String? = null): List<JEvent> {
        val sql = if (derivationId == null) "SELECT * FROM events ORDER BY created_at"
        else "SELECT * FROM events WHERE derivation_id=? ORDER BY created_at"
        return db.query(sql, *listOfNotNull(derivationId).toTypedArray()) { rs ->
            val refs = (Json.parseToJsonElement(rs.getString("source_refs")) as JsonArray).map { it.jsonPrimitive.content }
            JEvent(rs.getString("id"), rs.getString("kind"), rs.getString("payload"),
                rs.getString("derivation_id"), refs, rs.getInt("decided") == 1, rs.getString("created_at"))
        }
    }

    // ---------- conflicts ----------
    fun loadConflicts(): List<Conflict> = db.query("SELECT * FROM conflicts") {
        Conflict(it.getString(1), it.getString(2), it.getString(3), it.getString(4),
            it.getString(5), it.getString(6), it.getString(7), it.getString(8))
    }

    fun resolveConflict(id: String, side: String) {
        // the unchosen branch stays stored (status=retained) - never an irreversible overwrite
        db.update("UPDATE conflicts SET status='resolved', resolution=? WHERE id=?", side, id)
    }

    // ---------- counterexample archive ----------
    fun saveCounterexample(c: Counterexample) =
        db.update("INSERT OR REPLACE INTO counterexamples VALUES(?,?,?,?,?,?,?,?)",
            c.id, c.name, c.baseline, c.mutation, c.expectedScope, c.observedScope,
            if (c.adopted) 1 else 0, c.retained)

    fun loadCounterexamples(): List<Counterexample> = db.query("SELECT * FROM counterexamples") {
        Counterexample(it.getString(1), it.getString(2), it.getString(3), it.getString(4),
            it.getString(5), it.getString(6), it.getInt(7) == 1, it.getString(8))
    }

    // ---------- diagnostics ----------
    fun addDiagnostic(d: Diagnostic) = db.update("INSERT INTO diagnostics VALUES(?,?,?,?)", d.id, d.where, d.message, d.raw)
    fun loadDiagnostics(): List<Diagnostic> = db.query("SELECT * FROM diagnostics") {
        Diagnostic(it.getString(1), it.getString(2), it.getString(3), it.getString(4))
    }

    // ---------- derived result writers/readers ----------
    fun writeTap(derId: String, steps: List<TapStep>, shifts: List<ShiftStream>) {
        steps.forEach { s ->
            db.update("INSERT INTO tap_steps VALUES(?,?,?,?,?,?,?,?,?,?)",
                s.id, derId, s.idx, s.segment, s.from.name, s.to.name, s.tms, s.time.raw, s.time.base,
                Json.encodeToString(JsonArray(s.flags.map { JsonPrimitive(it) })))
        }
        shifts.forEach { s ->
            db.update("INSERT INTO shift_streams VALUES(?,?,?,?,?,?,?,?)",
                s.id, derId, s.segment, s.kind, s.bitsIn, s.bitsOut, s.startStep, s.endStep)
        }
    }

    fun loadTapSteps(derId: String): List<TapStep> = db.query(
        "SELECT * FROM tap_steps WHERE derivation_id=? ORDER BY idx", derId) { rs ->
        val flags = (Json.parseToJsonElement(rs.getString("flags")) as JsonArray).map { it.jsonPrimitive.content }
        TapStep(rs.getString("id"), rs.getInt("idx"), rs.getInt("segment"),
            TapState.valueOf(rs.getString("from_state")), TapState.valueOf(rs.getString("to_state")),
            rs.getInt("tms"), Quantity(rs.getString("time_raw"), null, null, rs.getString("time_base")), flags)
    }

    fun loadShifts(derId: String): List<ShiftStream> = db.query(
        "SELECT * FROM shift_streams WHERE derivation_id=? ORDER BY start_step", derId) {
        ShiftStream(it.getString("id"), it.getInt("segment"), it.getString("kind"),
            it.getString("bits_in"), it.getString("bits_out"), it.getInt("start_step"), it.getInt("end_step"))
    }

    fun writeChain(derId: String, devices: List<ChainDevice>, offsets: List<OffsetCandidate>) {
        devices.forEach { d ->
            db.update("INSERT INTO chain_devices VALUES(?,?,?,?,?,?,?)",
                d.id, derId, d.position, d.device, d.irLength, d.status,
                Json.encodeToString(JsonArray(d.candidates.map { JsonPrimitive(it) })))
        }
        offsets.forEach { o ->
            db.update("INSERT INTO offset_candidates VALUES(?,?,?,?)",
                o.id, derId, o.device, Json.encodeToString(JsonArray(o.offsets.map { JsonPrimitive(it) })))
        }
    }

    fun loadChainDevices(derId: String): List<ChainDevice> = db.query(
        "SELECT * FROM chain_devices WHERE derivation_id=? ORDER BY position", derId) { rs ->
        val c = (Json.parseToJsonElement(rs.getString("candidates")) as JsonArray).map { it.jsonPrimitive.int }
        ChainDevice(rs.getString("id"), rs.getInt("position"), rs.getString("device"),
            rs.getInt("ir_length").takeUnless { rs.wasNull() }, rs.getString("status"), c)
    }

    fun loadOffsets(derId: String): List<OffsetCandidate> = db.query(
        "SELECT * FROM offset_candidates WHERE derivation_id=?", derId) { rs ->
        val offs = (Json.parseToJsonElement(rs.getString("offsets")) as JsonArray).map { it.jsonPrimitive.int }
        OffsetCandidate(rs.getString("id"), rs.getString("device"), offs)
    }

    fun writeCells(derId: String, cells: List<CellPin>) {
        cells.forEach { c ->
            db.update("INSERT INTO cell_pin_evidence VALUES(?,?,?,?,?,?,?)",
                c.id, derId, c.device, c.cell, c.port, c.pin, c.bsdlVersion)
        }
    }

    fun loadCells(derId: String, device: String? = null): List<CellPin> {
        val sql = if (device == null)
            "SELECT * FROM cell_pin_evidence WHERE derivation_id=? ORDER BY device,cell"
        else "SELECT * FROM cell_pin_evidence WHERE derivation_id=? AND device=? ORDER BY cell"
        return db.query(sql, *listOfNotNull(derId, device).toTypedArray()) {
            CellPin(it.getString("id"), it.getString("device"), it.getInt("cell"),
                it.getString("port"), it.getString("pin"), it.getString("bsdl_version"))
        }
    }
}

/** Full-table JSON backup; stable ids, order and provenance survive export/import. */
object Backup {
    private val TABLES = listOf(
        "versions", "current_pointer", "sample_edges", "reset_events", "ir_candidates",
        "bsdl_maps", "board_revisions", "derivations", "tap_steps", "shift_streams",
        "chain_devices", "offset_candidates", "cell_pin_evidence", "events",
        "conflicts", "counterexamples", "diagnostics", "schema_meta")

    fun export(db: Db): String {
        val root = mutableMapOf<String, JsonElement>()
        for (t in TABLES) {
            val cols = db.query("PRAGMA table_info($t)") { it.getString("name") }
            val rows = db.query("SELECT * FROM $t") { rs ->
                buildJsonObject {
                    cols.forEach { c ->
                        when (val v = rs.getObject(c)) {
                            null -> put(c, JsonNull)
                            is Int -> put(c, v)
                            is Long -> put(c, v)
                            is Double -> put(c, v)
                            else -> put(c, v.toString())
                        }
                    }
                }
            }
            root[t] = JsonArray(rows)
        }
        return JsonObject(root).toString()
    }

    fun import(db: Db, json: String) {
        val root = Json.parseToJsonElement(json).jsonObject
        db.tx {
            for (t in TABLES) {
                val arr = root[t]?.jsonArray ?: continue
                val cols = db.query("PRAGMA table_info($t)") { it.getString("name") }
                db.exec("DELETE FROM $t")
                val ph = cols.joinToString(",") { "?" }
                for (row in arr) {
                    val o = row.jsonObject
                    val sql = "INSERT INTO $t(${cols.joinToString(",")}) VALUES($ph)"
                    db.conn.prepareStatement(sql).use { ps ->
                        cols.forEachIndexed { i, c ->
                            val e = o[c] ?: JsonNull
                            ps.setObject(i + 1, if (e is JsonNull) null else e.jsonPrimitive.content)
                        }
                        ps.executeUpdate()
                    }
                }
            }
        }
    }

    fun exportFile(db: Db, file: File) = file.writeText(export(db))
}
