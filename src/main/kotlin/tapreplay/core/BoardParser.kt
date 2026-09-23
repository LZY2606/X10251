package tapreplay.core

/** 板卡修订解析（板名、坐标基准、期望器件清单）。坐标保留原始毫米表示与 datum。 */
object BoardParser {

    fun parse(
        id: StableId,
        revisionId: StableId,
        name: String,
        datum: String,
        body: String,
    ): BoardRevision {
        val diags = mutableListOf<String>()
        val (_, rows) = Csv.parse(body)
        val devices = rows.mapIndexed { i, r ->
            val devId = r["device_id"].ifBlank { "dev-${r["designator"].lowercase()}" }
            val x = r["x_mm"]
            val y = r["y_mm"]
            if (x.toDoubleOrNull() == null || y.toDoubleOrNull() == null) {
                diags += "UNPARSEABLE_COORDINATE:${r["designator"]} ($x,$y)"
            }
            BoardDevice(
                deviceId = devId,
                designator = r["designator"],
                expectedIdcodeHex = r["expected_idcode"].ifBlank { null }?.uppercase(),
                coordinateDatum = r["datum"].ifBlank { datum },
                xMm = x,
                yMm = y,
            )
        }
        return BoardRevision(id, revisionId, name, datum, devices, diags)
    }
}

/** IR 候选 CSV 解析。ir_length 非数字时保留原始表示并标 GUESS/诊断。 */
object IrCandidateCsv {
    fun parse(revisionId: StableId, branchId: StableId, body: String): List<IrLengthCandidate> {
        val (_, rows) = Csv.parse(body)
        return rows.map { r ->
            val raw = r["ir_length_bits"]
            val v = raw.trim().toIntOrNull()
            val diag = if (v == null && raw.isNotBlank()) "UNPARSEABLE_IR_LENGTH:$raw" else null
            IrLengthCandidate(
                id = "irc-${revisionId}-${branchId}-${r["device_id"]}-${r["source"]}".lowercase(),
                revisionId = revisionId,
                branchId = branchId,
                deviceId = r["device_id"],
                irLengthBits = v,
                rawIrLength = raw,
                source = r["source"].ifBlank { "HUMAN_GUESS" },
                confidence = r["confidence"].ifBlank { if (v == null) "GUESS" else "SOFT" },
                diagnostic = diag,
            )
        }
    }
}

/** 复位事件 CSV 解析。edge_index 无法转换时保留原值 + 诊断（不静默丢弃）。 */
object ResetCsv {
    fun parse(revisionId: StableId, branchId: StableId, body: String): List<ResetEvent> {
        val (_, rows) = Csv.parse(body)
        return rows.mapIndexed { i, r ->
            val rawIdx = r["at_edge_index"]
            val idx = rawIdx.trim().toIntOrNull()
            val diag = if (idx == null && rawIdx.isNotBlank()) "UNPARSEABLE_EDGE_INDEX:$rawIdx" else null
            ResetEvent(
                id = "rst-${revisionId}-${branchId}-$i",
                revisionId = revisionId,
                branchId = branchId,
                kind = r["kind"].ifBlank { "UNKNOWN" },
                rawKind = r["kind"],
                rawAtEdgeIndex = rawIdx,
                atEdgeIndex = idx,
                diagnostic = diag,
            )
        }
    }
}

/** 采样边沿 CSV 解析：原始表示原样保留。 */
object SampleCsv {
    fun parse(
        sourceId: StableId,
        revisionId: StableId,
        boardRevisionId: StableId,
        branchId: StableId,
        polarityRaw: String,
        periodRaw: String,
        body: String,
    ): SampleSource {
        val diags = mutableListOf<String>()
        val (_, rows) = Csv.parse(body)
        val edges = rows.mapIndexed { i, r ->
            val rawTms = r["tms"]
            val tms = Parsers.parseLogic(rawTms)
            if (tms == null) diags += "EDGE_$i:NON_BINARY_TMS=$rawTms"
            val rawTck = r["tck"]
            if (Parsers.parseLogic(rawTck) == null) diags += "EDGE_$i:NON_BINARY_TCK=$rawTck"
            val time = Parsers.normalizeTime(r["time_ns"])
            if (r["time_ns"].isNotBlank() && time?.normalizedNanos == null && time != null) {
                diags += "EDGE_$i:${time.conversionDiagnostic}"
            }
            SampleEdge(
                id = "edge-${revisionId}-${branchId}-$i",
                index = i,
                rawTime = r["time_ns"],
                time = time,
                rawTms = rawTms,
                rawTck = rawTck,
                rawTdi = r["tdi"],
                rawTdo = r["tdo"],
                tms = tms,
                tck = Parsers.parseLogic(rawTck),
                tdi = Parsers.parseLogic(r["tdi"]),
                tdo = Parsers.parseLogic(r["tdo"]),
                decodeDiagnostics = emptyList(),
            )
        }
        val polarity = Parsers.parsePolarity(polarityRaw)
        if (polarity == SamplePolarity.UNKNOWN) diags += "UNKNOWN_SAMPLE_POLARITY:$polarityRaw"
        val period = periodRaw.trim().toLongOrNull()
        if (periodRaw.isNotBlank() && period == null) diags += "UNPARSEABLE_TCK_PERIOD:$periodRaw"
        return SampleSource(
            id = sourceId,
            revisionId = revisionId,
            boardRevisionId = boardRevisionId,
            branchId = branchId,
            polarity = polarity,
            polarityRaw = polarityRaw,
            tckPeriodNanos = period,
            tckPeriodRaw = periodRaw,
            edges = edges,
            diagnostics = diags,
        )
    }
}
