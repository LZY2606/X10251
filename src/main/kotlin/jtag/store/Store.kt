package jtag.store

import jtag.core.*
import kotlinx.serialization.json.*
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

data class VersionRow(val id: String, val parentId: String?, val label: String,
                      val author: String, val params: String, val status: String)

data class DerivedData(
    val tap: List<TapStep>,
    val shifts: List<ShiftSegment>,
    val devices: List<ChainDevice>,
    val offsets: List<OffsetCandidate>,
    val cells: List<CellPin>,
    val events: List<JudgeEvent>,
    val recompute: List<RecomputeEntry>
)

sealed interface DecisionResult {
    data class Saved(val versionId: String) : DecisionResult
    data class Conflicted(val versionId: String, val conflictId: String,
                          val currentVersionId: String) : DecisionResult
}

class Store private constructor(val conn: Connection) {
    private val seq = AtomicInteger(1)
    private fun nxt() = seq.getAndIncrement()

    companion object {
        fun open(path: String): Store {
            Class.forName("org.sqlite.JDBC")
            val c = DriverManager.getConnection("jdbc:sqlite:$path")
            c.autoCommit = true
            val s = Store(c)
            s.initOrMigrate()
            s.recoverInterruptedWrites()
            return s
        }
    }

    private fun initOrMigrate() {
        val hasVersions = conn.metaData.getTables(null, null, "versions", null).use { it.next() }
        if (!hasVersions) {
            conn.createStatement().use { st ->
                SCHEMA_V2.trimIndent().split(";").filter { it.isNotBlank() }.forEach { st.execute(it) }
                st.execute("INSERT OR IGNORE INTO meta(key,value) VALUES('schema_version','$SCHEMA_VERSION')")
            }
            return
        }
        val v = conn.createStatement().executeQuery("SELECT value FROM meta WHERE key='schema_version'")
            .use { if (it.next()) it.getInt(1) else 1 }
        if (v == 1) migrate1to2()
    }

    private fun migrate1to2() {
        conn.createStatement().use { st ->
            st.execute("ALTER TABLE versions ADD COLUMN author TEXT")
            st.execute("""CREATE TABLE IF NOT EXISTS conflicts(id TEXT PRIMARY KEY, base_version TEXT,
                branch_a TEXT, branch_b TEXT, status TEXT, resolution TEXT, created_seq INTEGER)""")
            st.execute("UPDATE meta SET value='$SCHEMA_VERSION' WHERE key='schema_version'")
        }
    }

    /** Crash recovery: a BUILDING version is a never-completed two-phase write.
     *  Readers see either the previous complete version or nothing new. */
    fun recoverInterruptedWrites() {
        tx {
            listOf("der_tap", "der_shift", "der_device", "der_offset", "der_cellpin",
                "recompute_log", "version_inputs", "events").forEach { t ->
                prepare("DELETE FROM $t WHERE version_id IN (SELECT id FROM versions WHERE status='BUILDING')")
                    .use { it.executeUpdate() }
            }
            prepare("DELETE FROM versions WHERE status='BUILDING'").use { it.executeUpdate() }
        }
    }

    fun close() = conn.close()

    private inline fun <T> tx(body: () -> T): T {
        conn.autoCommit = false
        try { val r = body(); conn.commit(); return r } catch (e: Throwable) { conn.rollback(); throw e }
        finally { conn.autoCommit = true }
    }
    private fun prepare(sql: String) = conn.prepareStatement(sql)

    // -------------------------------------------------------------- imports
    fun newImport(kind: String, label: String): String {
        val id = newId("imp")
        prepare("INSERT INTO imports(id,kind,label,created_seq) VALUES(?,?,?,?)").use {
            it.setString(1, id); it.setString(2, kind); it.setString(3, label); it.setInt(4, nxt())
            it.executeUpdate()
        }
        return id
    }

    fun insertEdges(importId: String, parsed: Parsers.ParsedEdges) {
        prepare("INSERT INTO src_edges(id,import_id,clock_idx,tck,tms,tdi,tdo,time_value,time_unit,raw) " +
            "VALUES(?,?,?,?,?,?,?,?,?,?)").use { p ->
            for (e in parsed.edges) {
                p.setString(1, e.id); p.setString(2, importId); p.setInt(3, e.clockIndex)
                p.setInt(4, e.tck); p.setInt(5, e.tms); p.setInt(6, e.tdi); p.setInt(7, e.tdo)
                if (e.timeValue == null) p.setNull(8, java.sql.Types.REAL) else p.setDouble(8, e.timeValue)
                p.setString(9, e.timeUnit); p.setString(10, e.raw); p.addBatch()
            }
            p.executeBatch()
        }
        prepare("INSERT INTO src_diagnostics(id,import_id,source_id,field,raw_value,message) " +
            "VALUES(?,?,?,?,?,?)").use { p ->
            for (d in parsed.diagnostics) {
                p.setString(1, newId("diag")); p.setString(2, importId); p.setString(3, d.sourceId)
                p.setString(4, d.field); p.setString(5, d.rawValue); p.setString(6, d.message); p.addBatch()
            }
            p.executeBatch()
        }
    }

    fun insertResets(importId: String, rows: List<ResetEvent>) {
        prepare("INSERT INTO src_resets(id,import_id,clock_idx,kind,raw) VALUES(?,?,?,?,?)").use { p ->
            rows.forEach { r ->
                p.setString(1, r.id); p.setString(2, importId); p.setInt(3, r.clockIndex)
                p.setString(4, r.kind); p.setString(5, r.raw); p.addBatch()
            }; p.executeBatch()
        }
    }

    fun insertIrCandidates(importId: String, rows: List<IrLengthCandidate>) {
        prepare("INSERT INTO src_ir_candidates(id,import_id,device_pos,length_bits,basis,status,raw) " +
            "VALUES(?,?,?,?,?,?,?)").use { p ->
            rows.forEach { r ->
                p.setString(1, r.id); p.setString(2, importId); p.setInt(3, r.devicePos)
                p.setInt(4, r.lengthBits); p.setString(5, r.basis); p.setString(6, r.status)
                p.setString(7, r.raw); p.addBatch()
            }; p.executeBatch()
        }
    }

    fun insertBsdl(importId: String, part: BsdlPart, cells: List<BsdlCell>) {
        prepare("INSERT INTO src_bsdl_devices(id,import_id,device,bsdl_version,idcode,ir_length,raw) " +
            "VALUES(?,?,?,?,?,?,?)").use { p ->
            p.setString(1, newId("bpart")); p.setString(2, importId); p.setString(3, part.device)
            p.setString(4, part.version); p.setString(5, part.idcode)
            if (part.irLength == null) p.setNull(6, java.sql.Types.INTEGER) else p.setInt(6, part.irLength)
            p.setString(7, "part:${part.device}:${part.version}"); p.executeUpdate()
        }
        prepare("INSERT INTO src_bsdl_cells(id,import_id,device,bsdl_version,cell,pin,function,safe,raw) " +
            "VALUES(?,?,?,?,?,?,?,?,?)").use { p ->
            cells.forEach { c ->
                p.setString(1, c.id); p.setString(2, importId); p.setString(3, c.device)
                p.setString(4, c.bsdlVersion); p.setInt(5, c.cell); p.setString(6, c.pin)
                p.setString(7, c.function); p.setString(8, c.safe); p.setString(9, c.raw); p.addBatch()
            }; p.executeBatch()
        }
    }

    fun insertBoard(importId: String, board: BoardRevision) {
        prepare("INSERT INTO src_board(id,import_id,revision,device_pos,device,expected_present,raw) " +
            "VALUES(?,?,?,?,?,?,?)").use { p ->
            board.devices.forEach { d ->
                p.setString(1, d.id); p.setString(2, importId); p.setString(3, board.revision)
                p.setInt(4, d.position); p.setString(5, d.device)
                p.setInt(6, if (d.expectedPresent) 1 else 0); p.setString(7, d.raw); p.addBatch()
            }; p.executeBatch()
        }
    }

    // -------------------------------------------------------------- loaders
    fun loadEdges(importId: String): List<EdgeSample> =
        prepare("SELECT id,clock_idx,tck,tms,tdi,tdo,time_value,time_unit,raw FROM src_edges " +
            "WHERE import_id=? ORDER BY clock_idx").use { p ->
            p.setString(1, importId); p.executeQuery().use { rs ->
                val out = mutableListOf<EdgeSample>()
                while (rs.next()) out += EdgeSample(rs.getString(1), rs.getInt(2), rs.getInt(3),
                    rs.getInt(4), rs.getInt(5), rs.getInt(6),
                    if (rs.getObject(7) == null) null else rs.getDouble(7),
                    rs.getString(8), rs.getString(9))
                out
            }
        }

    fun loadResets(importId: String): List<ResetEvent> =
        prepare("SELECT id,clock_idx,kind,raw FROM src_resets WHERE import_id=? ORDER BY clock_idx").use { p ->
            p.setString(1, importId); p.executeQuery().use { rs ->
                val out = mutableListOf<ResetEvent>()
                while (rs.next()) out += ResetEvent(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4))
                out
            }
        }

    fun loadIrCandidates(importId: String): List<IrLengthCandidate> =
        prepare("SELECT id,device_pos,length_bits,basis,status,raw FROM src_ir_candidates WHERE import_id=?")
            .use { p -> p.setString(1, importId); p.executeQuery().use { rs ->
                val out = mutableListOf<IrLengthCandidate>()
                while (rs.next()) out += IrLengthCandidate(rs.getString(1), rs.getInt(2), rs.getInt(3),
                    rs.getString(4), rs.getString(5), rs.getString(6))
                out
            } }

    fun loadBsdl(importId: String): Pair<List<BsdlPart>, List<BsdlCell>> {
        val cells = prepare("SELECT id,device,bsdl_version,cell,pin,function,safe,raw FROM src_bsdl_cells " +
            "WHERE import_id=? ORDER BY device, cell").use { p ->
            p.setString(1, importId); p.executeQuery().use { rs ->
                val out = mutableListOf<BsdlCell>()
                while (rs.next()) out += BsdlCell(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getInt(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8))
                out
            }
        }
        val parts = prepare("SELECT device,bsdl_version,idcode,ir_length FROM src_bsdl_devices WHERE import_id=?")
            .use { p -> p.setString(1, importId); p.executeQuery().use { rs ->
                val out = mutableListOf<BsdlPart>()
                while (rs.next()) out += BsdlPart(rs.getString(1), rs.getString(2), rs.getString(3),
                    if (rs.getObject(4) == null) null else rs.getInt(4),
                    cells.filter { it.device == rs.getString(1) })
                out
            } }
        return parts to cells
    }

    fun loadBoard(importId: String): BoardRevision =
        prepare("SELECT id,revision,device_pos,device,expected_present,raw FROM src_board WHERE import_id=? " +
            "ORDER BY device_pos").use { p -> p.setString(1, importId); p.executeQuery().use { rs ->
            var rev = "?"; val out = mutableListOf<BoardDevice>()
            while (rs.next()) {
                rev = rs.getString(2)
                out += BoardDevice(rs.getString(1), rev, rs.getInt(3), rs.getString(4),
                    rs.getInt(5) == 1, rs.getString(6))
            }
            BoardRevision(rev, out)
        } }

    fun diagnostics(): List<ValueDiagnostic> =
        prepare("SELECT source_id,field,raw_value,message FROM src_diagnostics").use { p ->
            p.executeQuery().use { rs ->
                val out = mutableListOf<ValueDiagnostic>()
                while (rs.next()) out += ValueDiagnostic(rs.getString(1), rs.getString(2),
                    rs.getString(3), rs.getString(4)); out
            } }

    // ----------------------------------------------------------- versions
    fun currentVersionId(): String? =
        prepare("SELECT version_id FROM pointer WHERE id=1").use { p ->
            p.executeQuery().use { if (it.next()) it.getString(1) else null } }

    fun version(vid: String): VersionRow? =
        prepare("SELECT id,parent_id,label,author,status,params FROM versions WHERE id=?").use { p ->
            p.setString(1, vid); p.executeQuery().use { rs ->
                if (rs.next()) VersionRow(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getString(6), rs.getString(5)) else null
            } }

    fun versionInputs(vid: String): Map<String, String> =
        prepare("SELECT node,import_id FROM version_inputs WHERE version_id=?").use { p ->
            p.setString(1, vid); p.executeQuery().use { rs ->
                val out = mutableMapOf<String, String>()
                while (rs.next()) out[rs.getString(1)] = rs.getString(2); out
            } }

    fun allVersions(): List<VersionRow> =
        prepare("SELECT id,parent_id,label,author,status,params FROM versions ORDER BY created_seq").use { p ->
            p.executeQuery().use { rs ->
                val out = mutableListOf<VersionRow>()
                while (rs.next()) out += VersionRow(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getString(6), rs.getString(5)); out
            } }

    /** Phase 1 of the two-phase write: rows become visible under BUILDING only. */
    fun beginVersion(row: VersionRow, inputs: Map<String, String>) = tx {
        prepare("INSERT INTO versions(id,parent_id,label,author,status,created_seq,params) " +
            "VALUES(?,?,?,?,?,?,?)").use { p ->
                p.setString(1, row.id); p.setString(2, row.parentId); p.setString(3, row.label)
                p.setString(4, row.author); p.setString(5, "BUILDING"); p.setInt(6, nxt())
                p.setString(7, row.params); p.executeUpdate()
            }
        prepare("INSERT INTO version_inputs(version_id,node,import_id) VALUES(?,?,?)").use { p ->
            inputs.forEach { (k, v) -> p.setString(1, row.id); p.setString(2, k); p.setString(3, v); p.addBatch() }
            p.executeBatch()
        }
    }

    fun writeDerived(vid: String, d: DerivedData) {
        prepare("INSERT INTO der_tap(version_id,identity,seq,clock_idx,from_state,to_state,tms,status,anomaly) " +
            "VALUES(?,?,?,?,?,?,?,?,?)").use { p ->
            d.tap.forEach { r ->
                p.setString(1, vid); p.setString(2, r.id); p.setInt(3, r.seq); p.setInt(4, r.clockIndex)
                p.setString(5, r.fromState.name); p.setString(6, r.toState.name); p.setInt(7, r.tms)
                p.setString(8, r.status); p.setString(9, r.anomaly); p.addBatch()
            }; p.executeBatch()
        }
        prepare("""INSERT INTO der_shift(version_id,identity,seq,register,start_clock,bits_tdo,bits_tdi,status)
            VALUES(?,?,?,?,?,?,?,?)""").use { p ->
            d.shifts.forEach { r ->
                p.setString(1, vid); p.setString(2, r.id); p.setInt(3, r.seq); p.setString(4, r.register)
                p.setInt(5, r.startClock); p.setString(6, r.bitsTdo); p.setString(7, r.bitsTdi)
                p.setString(8, r.status); p.addBatch()
            }; p.executeBatch()
        }
        prepare("""INSERT INTO der_device(version_id,identity,seq,position,name,idcode,bypass,ir_length,status)
            VALUES(?,?,?,?,?,?,?,?,?)""").use { p ->
            d.devices.forEach { r ->
                p.setString(1, vid); p.setString(2, r.id); p.setInt(3, r.position); p.setInt(4, r.position)
                p.setString(5, r.name); p.setString(6, r.idcode); p.setInt(7, if (r.bypass) 1 else 0)
                if (r.irLength == null) p.setNull(8, java.sql.Types.INTEGER) else p.setInt(8, r.irLength)
                p.setString(9, r.status); p.addBatch()
            }; p.executeBatch()
        }
        prepare("INSERT INTO der_offset(version_id,identity,device_pos,cell_offset,basis) VALUES(?,?,?,?,?)").use { p ->
            d.offsets.forEach { r ->
                p.setString(1, vid); p.setString(2, r.id); p.setInt(3, r.devicePos)
                p.setInt(4, r.cellOffset); p.setString(5, r.basis); p.addBatch()
            }; p.executeBatch()
        }
        prepare("""INSERT INTO der_cellpin(version_id,identity,device,cell,pin,function,bsdl_version,observed_bit)
            VALUES(?,?,?,?,?,?,?,?)""").use { p ->
            d.cells.forEach { r ->
                p.setString(1, vid); p.setString(2, r.id); p.setString(3, r.device); p.setInt(4, r.cell)
                p.setString(5, r.pin); p.setString(6, r.function); p.setString(7, r.bsdlVersion)
                if (r.observedBit == null) p.setNull(8, java.sql.Types.INTEGER) else p.setInt(8, r.observedBit)
                p.addBatch()
            }; p.executeBatch()
        }
        writeEvents(vid, d.events)
        prepare("INSERT INTO recompute_log(version_id,node,action,reason) VALUES(?,?,?,?)").use { p ->
            d.recompute.forEach { r ->
                p.setString(1, vid); p.setString(2, r.node); p.setString(3, r.action)
                p.setString(4, r.reason); p.addBatch()
            }; p.executeBatch()
        }
    }

    fun writeEvents(vid: String, events: List<JudgeEvent>) {
        prepare("INSERT INTO events(id,version_id,seq,kind,subject,accepted,rationale,payload) " +
            "VALUES(?,?,?,?,?,?,?,?)").use { p ->
            events.forEach { e ->
                p.setString(1, e.id); p.setString(2, vid); p.setInt(3, e.seq); p.setString(4, e.kind)
                p.setString(5, e.subject)
                if (e.accepted == null) p.setNull(6, java.sql.Types.INTEGER) else p.setInt(6, if (e.accepted) 1 else 0)
                p.setString(7, e.rationale); p.setString(8, e.payload); p.addBatch()
            }; p.executeBatch()
        }
    }

    /** Phase 2: only now does the current pointer move to the complete version. */
    fun publishVersion(vid: String) = tx {
        prepare("UPDATE versions SET status='COMPLETE' WHERE id=?").use { it.setString(1, vid); it.executeUpdate() }
        prepare("DELETE FROM pointer WHERE id=1").use { it.executeUpdate() }
        prepare("INSERT INTO pointer(id,version_id) VALUES(1,?)").use { it.setString(1, vid); it.executeUpdate() }
    }

    /** Human accept/reject/adjust: a new version, never an irreversible overwrite.
     *  Editing from a non-current base records a resolvable conflict. */
    fun saveDecision(baseVersionId: String, author: String, label: String,
                     events: List<JudgeEvent>): DecisionResult = tx {
        val vid = newId("ver")
        prepare("INSERT INTO versions(id,parent_id,label,author,status,created_seq,params) " +
            "VALUES(?,?,?,?,?,?,'')").use { p ->
                p.setString(1, vid); p.setString(2, baseVersionId); p.setString(3, label)
                p.setString(4, author); p.setString(5, "COMPLETE"); p.setInt(6, nxt()); p.executeUpdate()
            }
        copyDerived(baseVersionId, vid)
        prepare("INSERT INTO version_inputs(version_id,node,import_id) SELECT ?,node,import_id FROM version_inputs WHERE version_id=?")
            .use { p -> p.setString(1, vid); p.setString(2, baseVersionId); p.executeUpdate() }
        writeEvents(vid, events)
        val current = currentVersionId()
        if (current == null || current == baseVersionId) {
            prepare("DELETE FROM pointer WHERE id=1").use { it.executeUpdate() }
            prepare("INSERT INTO pointer(id,version_id) VALUES(1,?)").use { it.setString(1, vid); it.executeUpdate() }
            DecisionResult.Saved(vid)
        } else {
            val cid = newId("conf")
            prepare("INSERT INTO conflicts(id,base_version,branch_a,branch_b,status,resolution,created_seq) " +
                "VALUES(?,?,?,?,?,?,?)").use { p ->
                p.setString(1, cid); p.setString(2, baseVersionId); p.setString(3, current)
                p.setString(4, vid); p.setString(5, "OPEN"); p.setString(6, null); p.setInt(7, nxt())
                p.executeUpdate()
            }
            DecisionResult.Conflicted(vid, cid, current)
        }
    }

    private fun copyDerived(from: String, to: String) {
        listOf(
            "INSERT INTO der_tap(version_id,identity,seq,clock_idx,from_state,to_state,tms,status,anomaly) " +
                "SELECT ?,identity,seq,clock_idx,from_state,to_state,tms,status,anomaly FROM der_tap WHERE version_id=?",
            "INSERT INTO der_shift(version_id,identity,seq,register,start_clock,bits_tdo,bits_tdi,status) " +
                "SELECT ?,identity,seq,register,start_clock,bits_tdo,bits_tdi,status FROM der_shift WHERE version_id=?",
            "INSERT INTO der_device(version_id,identity,seq,position,name,idcode,bypass,ir_length,status) " +
                "SELECT ?,identity,seq,position,name,idcode,bypass,ir_length,status FROM der_device WHERE version_id=?",
            "INSERT INTO der_offset(version_id,identity,device_pos,cell_offset,basis) " +
                "SELECT ?,identity,device_pos,cell_offset,basis FROM der_offset WHERE version_id=?",
            "INSERT INTO der_cellpin(version_id,identity,device,cell,pin,function,bsdl_version,observed_bit) " +
                "SELECT ?,identity,device,cell,pin,function,bsdl_version,observed_bit FROM der_cellpin WHERE version_id=?",
            "INSERT INTO recompute_log(version_id,node,action,reason) SELECT ?,node,action,reason " +
                "FROM recompute_log WHERE version_id=?"
        ).forEach { sql -> prepare(sql).use { p -> p.setString(1, to); p.setString(2, from); p.executeUpdate() } }
    }

    fun conflicts(): List<Triple<String, String, String>> =
        prepare("SELECT id,branch_a,branch_b FROM conflicts WHERE status='OPEN'").use { p ->
            p.executeQuery().use { rs -> val out = mutableListOf<Triple<String,String,String>>()
                while (rs.next()) out += Triple(rs.getString(1), rs.getString(2), rs.getString(3)); out } }

    fun resolveConflict(conflictId: String, chosenVersionId: String) = tx {
        prepare("UPDATE conflicts SET status='RESOLVED', resolution=? WHERE id=?").use {
            it.setString(1, chosenVersionId); it.setString(2, conflictId); it.executeUpdate() }
        prepare("DELETE FROM pointer WHERE id=1").use { it.executeUpdate() }
        prepare("INSERT INTO pointer(id,version_id) VALUES(1,?)").use {
            it.setString(1, chosenVersionId); it.executeUpdate() }
    }

    // ---------------------------------------------------- derived readers
    fun tapSteps(vid: String): List<TapStep> =
        prepare("SELECT identity,seq,clock_idx,from_state,to_state,tms,status,anomaly FROM der_tap " +
            "WHERE version_id=? ORDER BY seq").use { p -> p.setString(1, vid); p.executeQuery().use { rs ->
            val out = mutableListOf<TapStep>()
            while (rs.next()) out += TapStep(rs.getString(1), rs.getInt(2), rs.getInt(3),
                TapState.valueOf(rs.getString(4)), TapState.valueOf(rs.getString(5)), rs.getInt(6),
                rs.getString(7), rs.getString(8)); out } }

    fun shifts(vid: String): List<ShiftSegment> =
        prepare("SELECT identity,seq,register,start_clock,bits_tdo,bits_tdi,status FROM der_shift " +
            "WHERE version_id=? ORDER BY seq").use { p -> p.setString(1, vid); p.executeQuery().use { rs ->
            val out = mutableListOf<ShiftSegment>()
            while (rs.next()) out += ShiftSegment(rs.getString(1), rs.getInt(2), rs.getString(3),
                rs.getInt(4), rs.getString(5), rs.getString(6), rs.getString(7)); out } }

    fun devices(vid: String): List<ChainDevice> =
        prepare("SELECT identity,position,name,idcode,bypass,ir_length,status FROM der_device " +
            "WHERE version_id=? ORDER BY position").use { p -> p.setString(1, vid); p.executeQuery().use { rs ->
            val out = mutableListOf<ChainDevice>()
            while (rs.next()) out += ChainDevice(rs.getString(1), rs.getInt(2), rs.getString(3),
                rs.getString(4), rs.getInt(5) == 1,
                if (rs.getObject(6) == null) null else rs.getInt(6), rs.getString(7)); out } }

    fun offsets(vid: String): List<OffsetCandidate> =
        prepare("SELECT identity,device_pos,cell_offset,basis FROM der_offset WHERE version_id=?").use { p ->
            p.setString(1, vid); p.executeQuery().use { rs ->
                val out = mutableListOf<OffsetCandidate>()
                while (rs.next()) out += OffsetCandidate(rs.getString(1), rs.getInt(2), rs.getInt(3),
                    rs.getString(4)); out } }

    fun cellPins(vid: String): List<CellPin> =
        prepare("SELECT identity,device,cell,pin,function,bsdl_version,observed_bit FROM der_cellpin " +
            "WHERE version_id=? ORDER BY device, cell").use { p -> p.setString(1, vid); p.executeQuery().use { rs ->
            val out = mutableListOf<CellPin>()
            while (rs.next()) out += CellPin(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                rs.getString(5), rs.getString(6),
                if (rs.getObject(7) == null) null else rs.getInt(7)); out } }

    fun events(vid: String): List<JudgeEvent> =
        prepare("SELECT id,seq,kind,subject,accepted,rationale,payload FROM events WHERE version_id=? ORDER BY seq")
            .use { p -> p.setString(1, vid); p.executeQuery().use { rs ->
                val out = mutableListOf<JudgeEvent>()
                while (rs.next()) out += JudgeEvent(rs.getString(1), rs.getInt(2), rs.getString(3),
                    rs.getString(4), if (rs.getObject(5) == null) null else rs.getInt(5) == 1,
                    rs.getString(6), rs.getString(7)); out } }

    fun recomputeLog(vid: String): List<RecomputeEntry> =
        prepare("SELECT node,action,reason FROM recompute_log WHERE version_id=?").use { p ->
            p.setString(1, vid); p.executeQuery().use { rs ->
                val out = mutableListOf<RecomputeEntry>()
                while (rs.next()) out += RecomputeEntry(rs.getString(1), rs.getString(2), rs.getString(3)); out } }

    // ------------------------------------------------------ export/import
    private val tables = listOf("meta", "imports", "src_edges", "src_resets", "src_ir_candidates",
        "src_bsdl_devices", "src_bsdl_cells", "src_board", "src_diagnostics", "versions",
        "version_inputs", "der_tap", "der_shift", "der_device", "der_offset", "der_cellpin",
        "events", "recompute_log", "pointer", "conflicts")

    fun exportJson(): String {
        val root = buildJsonObject {
            put("schema_version", SCHEMA_VERSION)
            putJsonObject("tables") {
                for (t in tables) {
                    putJsonArray(t) {
                        prepare("SELECT * FROM $t").use { p ->
                            p.executeQuery().use { rs ->
                                val md = rs.metaData
                                while (rs.next()) {
                                    add(buildJsonObject {
                                        for (i in 1..md.columnCount) {
                                            val v = rs.getObject(i)
                                            when (v) {
                                                null -> put(md.getColumnName(i), JsonNull)
                                                is Number -> put(md.getColumnName(i), v.toString())
                                                else -> put(md.getColumnName(i), v.toString())
                                            }
                                        }
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
        return root.toString()
    }

    fun wipe() = tx {
        tables.filter { it != "meta" }.forEach { t ->
            prepare("DELETE FROM $t").use { it.executeUpdate() }
        }
    }

    fun importJson(json: String) {
        val root = Json.parseToJsonElement(json).jsonObject
        val tbls = root["tables"]!!.jsonObject
        tx {
            for (t in tables) {
                val rows = tbls[t]?.jsonArray ?: continue
                if (rows.isEmpty()) continue
                val cols = rows[0].jsonObject.keys.toList()
                val sql = "INSERT INTO $t(${cols.joinToString(",")}) VALUES(${cols.joinToString(",") { "?" }})"
                prepare(sql).use { p ->
                    rows.forEach { row ->
                        cols.forEachIndexed { i, c ->
                            val el = row.jsonObject[c]
                            if (el == null || el is JsonNull) p.setString(i + 1, null)
                            else p.setString(i + 1, el.jsonPrimitive.content)
                        }
                        p.addBatch()
                    }
                    p.executeBatch()
                }
            }
        }
    }
}
