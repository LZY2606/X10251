package jtap.domain

/*
 * 采样文本格式（板级调试小组导出的 CSV-like 表示）：
 *   # unit: ns
 *   # edge: rising
 *   # tdo-polarity: normal
 *   index,tck_ns,tms,tdi,tdo[,trst]
 * 表头可缺失，此时按列序解析。任何无法转换的字段保留原串并写入 diagnostics。
 */
object SampleTraceParser {

    fun parse(
        id: String,
        name: String,
        version: Int,
        parentId: String?,
        raw: String,
        author: String = "fixture"
    ): SampleTrace {
        val lines = raw.replace("\r\n", "\n").split("\n")
        var unit = "ns"
        var declaredEdge = SampleEdge.RISING
        val diagnostics = mutableListOf<String>()
        val dataLines = mutableListOf<String>()
        var header: List<String>? = null

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("#")) {
                val body = trimmed.removePrefix("#").trim()
                when {
                    body.startsWith("unit:", ignoreCase = true) ->
                        unit = body.substringAfter(":").trim()
                    body.startsWith("edge:", ignoreCase = true) -> {
                        val v = body.substringAfter(":").trim()
                        declaredEdge = parseEdge(v) ?: run {
                            diagnostics += "无法识别的采样边沿 '$v'，按 rising 处理但保留原值"
                            SampleEdge.RISING
                        }
                    }
                    body.startsWith("tdo-polarity:", ignoreCase = true) -> Unit
                    else -> Unit
                }
            } else {
                if (header == null && !trimmed.first().isDigit()) header = trimmed.split(",").map { it.trim().lowercase() }
                else dataLines += line
            }
        }

        val edges = dataLines.mapIndexed { lineNo, line ->
            parseEdge(line, lineNo, unit, header, diagnostics)
        }
        return SampleTrace(
            id = id, name = name, version = version, parentId = parentId,
            tckUnit = unit, declaredEdge = declaredEdge, edges = edges,
            rawText = raw, diagnostics = diagnostics
        )
    }

    private fun parseEdge(v: String): SampleEdge? = when (v.lowercase()) {
        "rising", "rise", "posedge", "r" -> SampleEdge.RISING
        "falling", "fall", "negedge", "f" -> SampleEdge.FALLING
        else -> null
    }

    private fun col(header: List<String>?, tokens: List<String>, key: String, fallback: Int): String? {
        if (header != null) {
            val idx = header.indexOfFirst { it == key || it.startsWith(key) }
            if (idx >= 0 && idx < tokens.size) return tokens[idx]
            return null
        }
        return tokens.getOrNull(fallback)
    }

    private fun parseEdge(
        line: String,
        lineNo: Int,
        unit: String,
        header: List<String>?,
        diagnostics: MutableList<String>
    ): RawEdge {
        val tokens = line.split(",").map { it.trim() }
        val local = mutableListOf<String>()
        val idxToken = col(header, tokens, "index", 0)
        val tckToken = col(header, tokens, "tck", 1)
        val tmsToken = col(header, tokens, "tms", 2)
        val tdiToken = col(header, tokens, "tdi", 3)
        val tdoToken = col(header, tokens, "tdo", 4)
        val trstToken = col(header, tokens, "trst", 5)

        val idx = idxToken?.toIntOrNull()
        if (idx == null) local += "边沿序号无法解析: '$idxToken'"
        val tckNs = tckToken?.toDoubleOrNull()
        if (tckToken != null && tckNs == null) local += "TCK 时间 '$tckToken' 无法转换为数值，保留原始表示"
        val tms = parseBit(tmsToken ?: "")
        if (tms == null) local += "TMS '$tmsToken' 非 0/1/X/Z，属非法采样"
        val tdi = parseBit(tdiToken ?: "")
        if (tdi == null) local += "TDI '$tdiToken' 非 0/1/X/Z，属非法采样"
        val tdo = parseBit(tdoToken ?: "")
        if (tdo == null) local += "TDO '$tdoToken' 非 0/1/X/Z，属非法采样"
        val trst = parseBit(trstToken ?: "1")
        if (local.isNotEmpty()) diagnostics += "第 ${lineNo + 1} 数据行: ${local.joinToString("; ")}"
        return RawEdge(
            index = idx ?: lineNo,
            rawLine = line,
            tck = tckToken ?: "",
            tckUnit = unit,
            tms = tmsToken ?: "", tdi = tdiToken ?: "", tdo = tdoToken ?: "",
            trst = trstToken ?: "1",
            tckNs = tckNs,
            tmsBit = tms, tdiBit = tdi, tdoBit = tdo, trstBit = trst,
            diagnostics = local
        )
    }
}

/*
 * 极小 BSDL 子集（面向本工具的离线案例包，非通用 BSDL 解析器）：
 *   attribute INSTRUCTION_LENGTH of ... : entity is 8;
 *   attribute IDCODE_REGISTER of ... : entity is "00001000000001111000000010010011";
 *   "cell  BC_1  port(P,  output,  X),   1;"
 * 映射来源行号随单元保留，便于“单元 -> 引脚”证据回链。
 */
object BsdlParser {

    fun parse(id: String, deviceRef: String, version: String, raw: String): BsdlDoc {
        val diagnostics = mutableListOf<String>()
        var irLen: Int? = null
        var idcode: String? = null
        val cells = mutableListOf<BoundaryCell>()

        raw.replace("\r\n", "\n").split("\n").forEachIndexed { zeroBased, rawLine ->
            val lineNo = zeroBased + 1
            val line = rawLine.trim()
            if (line.startsWith("--")) return@forEachIndexed
            if (line.contains("INSTRUCTION_LENGTH", ignoreCase = true)) {
                val m = Regex("""is\s+(\d+)\s*;""").find(line)
                if (m != null) irLen = m.groupValues[1].toInt()
                else diagnostics += "第 $lineNo 行 INSTRUCTION_LENGTH 无法解析"
            }
            if (line.contains("IDCODE_REGISTER", ignoreCase = true)) {
                val m = Regex("""is\s+"([01Xx]+)"""").find(line)
                if (m != null) idcode = m.groupValues[1].uppercase()
            }
            val cellMatch = Regex("""^"?(\d+)\s+\S+\s+port\s*\(\s*([^,]+),\s*([^,]+),\s*([^)]+)\)""").find(line)
            if (cellMatch != null) {
                val n = cellMatch.groupValues[1].toInt()
                val port = cellMatch.groupValues[2].trim()
                val func = cellMatch.groupValues[3].trim()
                val safe = cellMatch.groupValues[4].trim()
                val ctl = Regex("""control\s*\(\s*(\d+)\s*\)""", RegexOption.IGNORE_CASE).find(line)
                    ?.groupValues?.get(1)?.toInt()
                cells += BoundaryCell(
                    stableId = "cell:${deviceRef}:v${version}:$n",
                    deviceRef = deviceRef, bsdlVersionId = id,
                    cellNumber = n, portId = port, function = func, safe = safe,
                    controlCell = ctl,
                    coordinateBasis = "BSDL boundary_scan_chain 单元序号（TDI→TDO）",
                    sourceLine = lineNo
                )
            }
        }
        return BsdlDoc(id, deviceRef, version, irLen, idcode, cells, raw, diagnostics)
    }
}

object BoardRevisionParser {
    fun parse(id: String, raw: String): BoardRevision {
        var boardRef = "UNKNOWN"
        var revision = "UNKNOWN"
        val expected = mutableListOf<ExpectedDevice>()
        raw.replace("\r\n", "\n").split("\n").forEach { line0 ->
            val line = line0.trim()
            if (line.startsWith("#") || line.isEmpty()) return@forEach
            if (line.startsWith("board:", ignoreCase = true)) boardRef = line.substringAfter(":").trim()
            else if (line.startsWith("revision:", ignoreCase = true)) revision = line.substringAfter(":").trim()
            else {
                val p = line.split(",").map { it.trim() }
                if (p.size >= 4) {
                    val idx = p[0].toIntOrNull()
                    if (idx != null) expected += ExpectedDevice(
                        chainIndexFromTdi = idx,
                        deviceRef = p[1],
                        boardNetPrefix = p[2],
                        populated = p[3].equals("populated", ignoreCase = true) || p[3] == "1"
                    )
                }
            }
        }
        return BoardRevision(id, boardRef, revision,
            coordinateBasis = "板卡修订 $boardRef/$revision 的器件位号清单",
            expectedDevices = expected)
    }
}
