package jtap.storage

import jtap.model.BoardRevisionSource
import jtap.model.BsdlCell
import jtap.model.BsdlSource
import jtap.model.CaptureEdge
import jtap.model.DeviceManifestEntry
import jtap.model.PinCoordinate
import jtap.model.PolarityConfig
import jtap.model.Quantity
import jtap.model.ResetEvent
import jtap.model.SampleEdge
import jtap.model.SampleSource
import jtap.model.UnitTag
import java.sql.Connection

class SourceRepository(private val conn: Connection) {
    fun ensureCase(caseId: String, name: String) {
        conn.prepareStatement(
            "INSERT OR IGNORE INTO cases(case_id,name,created_at) VALUES(?,?,?)"
        ).use {
            it.setString(1, caseId); it.setString(2, name); it.setString(3, Schema.now())
            it.executeUpdate()
        }
    }

    fun latestSampleRevision(caseId: String, label: String): Int =
        conn.prepareStatement(
            "SELECT COALESCE(MAX(revision),0) FROM sample_source WHERE case_id=? AND label=?"
        ).use {
            it.setString(1, caseId); it.setString(2, label)
            it.executeQuery().use { q -> if (q.next()) q.getInt(1) else 0 }
        }

    fun insertSample(caseId: String, label: String, s: SampleSource) {
        conn.prepareStatement(
            """INSERT INTO sample_source(source_id,case_id,revision,label,polarity_raw,
              |polarity_edge,raw_payload) VALUES(?,?,?,?,?,?,?)""".trimMargin()
        ).use {
            it.setString(1, s.sourceId); it.setString(2, caseId); it.setInt(3, s.revision)
            it.setString(4, label); it.setString(5, s.polarity.rawText)
            it.setString(6, s.polarity.captureEdge.name); it.setString(7, s.rawPayload)
            it.executeUpdate()
        }
        s.edges.forEach { e ->
            conn.prepareStatement(
                """INSERT INTO sample_edge(edge_id,source_id,case_id,revision,edge_index,
                  |at_value,at_unit,at_raw,at_diagnostic,tms,tck,tdi,tdo,declared_state,
                  |diagnostics_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""".trimMargin()
            ).use {
                val p = it
                p.setString(1, jtap.model.StableIds.edge(caseId, s.revision, e.edgeIndex))
                p.setString(2, s.sourceId); p.setString(3, caseId); p.setInt(4, s.revision)
                p.setLong(5, e.edgeIndex)
                p.setObject(6, e.at.value); p.setString(7, e.at.unit.name)
                p.setString(8, e.at.raw); p.setString(9, e.at.diagnostic)
                p.setString(10, e.tms); p.setString(11, e.tck); p.setString(12, e.tdi)
                p.setString(13, e.tdo); p.setString(14, e.declaredState)
                p.setString(15, JsonCodec.encode(e.diagnostics))
                p.executeUpdate()
            }
        }
    }

    fun insertReset(caseId: String, rev: Int, r: ResetEvent) {
        conn.prepareStatement(
            """INSERT INTO reset_event(event_id,case_id,source_id,revision,edge_index,
              |at_value,at_unit,at_raw,cause,mid_scan,abandoned_bits,detail)
              |VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""".trimMargin()
        ).use {
            it.setString(1, r.eventId); it.setString(2, caseId); it.setString(3, r.sourceId)
            it.setInt(4, rev); it.setLong(5, r.edgeIndex)
            it.setObject(6, r.at.value); it.setString(7, r.at.unit.name)
            it.setString(8, r.at.raw); it.setString(9, r.cause)
            it.setInt(10, if (r.midScan) 1 else 0); it.setInt(11, r.abandonedBits)
            it.setString(12, r.detail); it.executeUpdate()
        }
    }

    fun loadSample(caseId: String, sourceId: String): SampleSource? {
        val meta = conn.prepareStatement(
            "SELECT revision,label,polarity_raw,polarity_edge,raw_payload " +
                "FROM sample_source WHERE case_id=? AND source_id=?"
        ).use {
            it.setString(1, caseId); it.setString(2, sourceId)
            it.executeQuery().use { q ->
                if (!q.next()) return null
                object {
                    val rev = q.getInt(1); val label = q.getString(2)
                    val polRaw = q.getString(3); val polEdge = q.getString(4)
                    val raw = q.getString(5)
                }
            }
        }
        val edges = mutableListOf<SampleEdge>()
        conn.prepareStatement(
            """SELECT edge_index,at_value,at_unit,at_raw,at_diagnostic,tms,tck,tdi,tdo,
              |declared_state,diagnostics_json FROM sample_edge
              |WHERE case_id=? AND source_id=? ORDER BY edge_index""".trimMargin()
        ).use {
            it.setString(1, caseId); it.setString(2, sourceId)
            it.executeQuery().use { q ->
                while (q.next()) {
                    edges += SampleEdge(
                        edgeIndex = q.getLong(1),
                        at = Quantity(q.getObject(2) as? Double,
                            runCatching { UnitTag.valueOf(q.getString(3)) }.getOrDefault(UnitTag.UNCONVERTED),
                            q.getString(4), q.getString(5)),
                        tms = q.getString(6), tck = q.getString(7),
                        tdi = q.getString(8), tdo = q.getString(9),
                        declaredState = q.getString(10),
                        diagnostics = (JsonCodec.decode(q.getString(11)) as? List<*>)
                            ?.map { it.toString() } ?: emptyList(),
                    )
                }
            }
        }
        val pol = jtap.core.SampleImporter.parsePolarity(meta.polRaw)
            .let { if (meta.polEdge == CaptureEdge.UNCONVERTED.name) it.copy(captureEdge = CaptureEdge.UNCONVERTED) else it }
        return SampleSource(sourceId, meta.rev, meta.label, pol, edges, meta.raw)
    }

    fun currentSampleSourceId(caseId: String, label: String): String? =
        conn.prepareStatement(
            """SELECT source_id FROM sample_source WHERE case_id=? AND label=?
              |ORDER BY revision DESC LIMIT 1""".trimMargin()
        ).use {
            it.setString(1, caseId); it.setString(2, label)
            it.executeQuery().use { q -> if (q.next()) q.getString(1) else null }
        }

    fun insertBsdl(caseId: String, b: BsdlSource) {
        conn.prepareStatement(
            """INSERT INTO bsdl_source(source_id,case_id,revision,device_name,bsdl_version,
              |idcode_mask,ir_length,ir_length_raw,raw_payload,diagnostics_json)
              |VALUES(?,?,?,?,?,?,?,?,?,?)""".trimMargin()
        ).use {
            it.setString(1, b.sourceId); it.setString(2, caseId); it.setInt(3, b.revision)
            it.setString(4, b.deviceName); it.setString(5, b.bsdlVersion)
            it.setString(6, b.idcodeMask); it.setObject(7, b.instructionLength)
            it.setString(8, b.instructionLengthRaw); it.setString(9, b.rawPayload)
            it.setString(10, JsonCodec.encode(b.diagnostics)); it.executeUpdate()
        }
        b.cells.forEach { c ->
            conn.prepareStatement(
                """INSERT INTO bsdl_cell(id,source_id,case_id,revision,cell_number,port_name,
                  |cell_type,function,safe_bit,board_revision)
                  |VALUES(?,?,?,?,?,?,?,?,?,?)""".trimMargin()
            ).use {
                val id = "${b.sourceId}:cell:${c.number}"
                it.setString(1, id); it.setString(2, b.sourceId); it.setString(3, caseId)
                it.setInt(4, b.revision); it.setInt(5, c.number); it.setString(6, c.portName)
                it.setString(7, c.cellType); it.setString(8, c.function); it.setString(9, c.safeBit)
                it.setString(10, c.coordinate.boardRevision); it.executeUpdate()
            }
        }
        b.opcodes.forEach { (name, bits) ->
            conn.prepareStatement(
                "INSERT INTO bsdl_opcode(id,source_id,name,relevant_bits) VALUES(?,?,?,?)"
            ).use {
                it.setString(1, "${b.sourceId}:op:$name"); it.setString(2, b.sourceId)
                it.setString(3, name); it.setInt(4, bits); it.executeUpdate()
            }
        }
    }

    fun loadBsdl(caseId: String, sourceId: String): BsdlSource? {
        val rows = conn.prepareStatement(
            """SELECT revision,device_name,bsdl_version,idcode_mask,ir_length,ir_length_raw,
              |raw_payload,diagnostics_json FROM bsdl_source
              |WHERE case_id=? AND source_id=?""".trimMargin()
        ).use {
            it.setString(1, caseId); it.setString(2, sourceId)
            it.executeQuery().use { q -> if (!q.next()) return null else q }
        }
        val cells = mutableListOf<BsdlCell>()
        conn.prepareStatement(
            """SELECT cell_number,port_name,cell_type,function,safe_bit,board_revision
              |FROM bsdl_cell WHERE source_id=? ORDER BY cell_number""".trimMargin()
        ).use {
            it.setString(1, sourceId)
            it.executeQuery().use { q ->
                while (q.next()) {
                    cells += BsdlCell(q.getInt(1), q.getString(2), q.getString(3), q.getString(4),
                        q.getString(5), PinCoordinate(q.getString(2), null, q.getString(6)))
                }
            }
        }
        val opcodes = linkedMapOf<String, Int>()
        conn.prepareStatement("SELECT name,relevant_bits FROM bsdl_opcode WHERE source_id=?")
            .use {
                it.setString(1, sourceId)
                it.executeQuery().use { q ->
                    while (q.next()) opcodes[q.getString(1)] = q.getInt(2)
                }
            }
        return BsdlSource(
            sourceId = sourceId, revision = rows.getInt(1), deviceName = rows.getString(2),
            bsdlVersion = rows.getString(3), packageBody = "", idcodeMask = rows.getString(4),
            instructionLength = rows.getObject(5) as? Int,
            instructionLengthRaw = rows.getString(6),
            opcodes = opcodes, cells = cells, rawPayload = rows.getString(7),
            diagnostics = (JsonCodec.decode(rows.getString(8)) as? List<*>)?.map { it.toString() }
                ?: emptyList(),
        )
    }

    fun insertBoard(caseId: String, b: BoardRevisionSource) {
        conn.prepareStatement(
            """INSERT INTO board_revision(source_id,case_id,revision,board_revision,raw_payload)
              |VALUES(?,?,?,?,?)""".trimMargin()
        ).use {
            it.setString(1, b.sourceId); it.setString(2, caseId); it.setInt(3, b.revision)
            it.setString(4, b.boardRevision); it.setString(5, b.rawPayload); it.executeUpdate()
        }
        b.entries.forEach { e ->
            conn.prepareStatement(
                """INSERT INTO board_device(id,source_id,position,expected_name,bsdl_source_id,
                  |expected_idcode) VALUES(?,?,?,?,?,?)""".trimMargin()
            ).use {
                it.setString(1, "${b.sourceId}:dev:${e.position}")
                it.setString(2, b.sourceId); it.setInt(3, e.position)
                it.setString(4, e.expectedName); it.setString(5, e.bsdlSourceId)
                it.setString(6, e.expectedIdcode); it.executeUpdate()
            }
        }
    }

    fun loadBoard(caseId: String, sourceId: String): BoardRevisionSource? {
        val root = conn.prepareStatement(
            "SELECT revision,board_revision,raw_payload FROM board_revision " +
                "WHERE case_id=? AND source_id=?"
        ).use {
            it.setString(1, caseId); it.setString(2, sourceId)
            it.executeQuery().use { q -> if (!q.next()) return null else q }
        }
        val entries = mutableListOf<DeviceManifestEntry>()
        conn.prepareStatement(
            "SELECT position,expected_name,bsdl_source_id,expected_idcode " +
                "FROM board_device WHERE source_id=? ORDER BY position"
        ).use {
            it.setString(1, sourceId)
            it.executeQuery().use { q ->
                while (q.next()) {
                    entries += DeviceManifestEntry(q.getInt(1), q.getString(2),
                        q.getString(3), q.getString(4))
                }
            }
        }
        return BoardRevisionSource(sourceId, root.getInt(1), root.getString(2), entries,
            root.getString(3))
    }

    fun listSourceIds(caseId: String, table: String, idColumn: String = "source_id"): List<String> =
        conn.createStatement().executeQuery(
            "SELECT $idColumn FROM $table WHERE case_id='$caseId'"
        ).use { q ->
            val out = mutableListOf<String>()
            while (q.next()) out += q.getString(1)
            out
        }

    fun currentBoardSourceId(caseId: String): String? =
        conn.createStatement().executeQuery(
            "SELECT source_id FROM board_revision WHERE case_id='$caseId' ORDER BY revision DESC LIMIT 1"
        ).use { if (it.next()) it.getString(1) else null }
}
