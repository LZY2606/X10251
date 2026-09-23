package tapreplay.core

/**
 * BSDL 子集解析器（IEEE 1149.1 BSDL 风格）。
 * 解析：INSTRUCTION_LENGTH / INSTRUCTION_OPCODE(BYPASS) / IDCODE_REGISTER /
 * TAP_SCAN_* / BOUNDARY_LENGTH / BOUNDARY_REGISTER，外加两条工程化属性：
 *   PORT_PIN      —— 端口到封装引脚
 *   PIN_COORDINATE —— 引脚坐标（带 datum，板级基准，保留原始表示）
 * 无法识别的内容不丢弃：写入 diagnostics。
 */
object BsdlParser {

    fun parse(
        id: StableId,
        revisionId: StableId,
        deviceId: StableId,
        version: String,
        body: String,
    ): BsdlMapping {
        val diags = mutableListOf<String>()
        val noComment = body.lines().joinToString("\n") { line ->
            line.substringBefore("--").trim()
        }

        fun attr(name: String): String? {
            val m = Regex("(?is)attribute\\s+$name\\b[^;]*?is\\b(.*?);").find(noComment)
                ?: return null
            return m.groupValues[1].trim().trim('"')
        }

        val irLength = attr("INSTRUCTION_LENGTH")?.toIntOrNull()
            .also { if (attr("INSTRUCTION_LENGTH") != null && it == null) diags += "UNPARSEABLE_INSTRUCTION_LENGTH" }

        val opcodeRaw = attr("INSTRUCTION_OPCODE")
        val bypass = opcodeRaw?.let { Regex("(?i)BYPASS\\s*\\(\\s*\"([01xX]+)\"\\s*\\)").find(it) }
        val bypassHex = bypass?.groupValues?.get(1)

        val idcodeBits = attr("IDCODE_REGISTER")
        val idHex = idcodeBits?.filter { it == '0' || it == '1' }
            ?.takeIf { it.length in 1..32 }
            ?.let { bits ->
                val padded = bits.padStart(32, '0')
                padded.chunked(4).joinToString("") { q ->
                    Integer.toHexString(Integer.parseInt(q, 2))
                }.uppercase()
            }

        val irCapture = attr("CAPTURE_DATA") ?: attr("IR_CAPTURE")

        val tapPorts = mutableMapOf<String, String>()
        listOf("TCK", "TMS", "TDI", "TDO", "TRST").forEach { sig ->
            Regex("(?i)attribute\\s+TAP_SCAN_[A-Z]+\\s+of\\s+([A-Za-z0-9_]+)\\s*:\\s*signal\\s+is\\s+([A-Za-z0-9_]+)")
                .findAll(noComment).forEach { }
        }
        Regex("(?is)attribute\\s+(TAP_SCAN_[A-Z]+)\\s+of\\s+([A-Za-z0-9_]+)\\b[^;]*?;").findAll(noComment)
            .forEach { tapPorts[it.groupValues[1].substringAfter("TAP_SCAN_")] = it.groupValues[2] }
        Regex("(?is)port\\s*\\((.*?)\\)\\s*;").find(noComment)?.let { portBlock ->
            portBlock.groupValues[1].split(";").forEach { decl ->
                val n = decl.trim().substringBefore(":").trim()
                if (n in listOf("TCK", "TMS", "TDI", "TDO", "TRST") && !tapPorts.containsValue(n)) {
                    tapPorts.putIfAbsent(n, n)
                }
            }
        }

        val boundaryLength = attr("BOUNDARY_LENGTH")?.toIntOrNull()

        // 端口 -> 封装引脚
        val pinByPort = mutableMapOf<String, String>()
        attr("PORT_PIN")?.split(",")?.forEach { kv ->
            val p = kv.split(":")
            if (p.size == 2) pinByPort[p[0].trim()] = p[1].trim()
            else diags += "UNPARSEABLE_PORT_PIN:$kv"
        }

        // 端口 -> (x, y, 网表/连接器, datum)
        data class Coord(val x: String, val y: String, val net: String, val datum: String)
        val coordByPort = mutableMapOf<String, Coord>()
        attr("PIN_COORDINATE")?.let { block ->
            Regex("([A-Za-z0-9_]+)\\s*:\\s*\\(([^)]*)\\)").findAll(block).forEach { m ->
                val parts = m.groupValues[2].split(",").map { it.trim() }
                if (parts.size >= 4) {
                    coordByPort[m.groupValues[1]] = Coord(parts[0], parts[1], parts[2], parts[3])
                } else diags += "UNPARSEABLE_PIN_COORDINATE:${m.groupValues[1]}"
            }
        }

        val cells = mutableListOf<CellBinding>()
        attr("BOUNDARY_REGISTER")?.let { block ->
            Regex("(?m)(\\d+)\\s*\\(\\s*([A-Za-z0-9_]+)\\s*,\\s*([A-Za-z0-9_]+|\\*)\\s*,\\s*([A-Za-z0-9_]+)(?:\\s*,\\s*([01Xx]))?").findAll(block)
                .forEach { m ->
                    val port = m.groupValues[3]
                    val coord = coordByPort[port]
                    cells += CellBinding(
                        cellIndex = m.groupValues[1].toInt(),
                        port = port,
                        pin = pinByPort[port] ?: "?",
                        function = m.groupValues[4].lowercase(),
                        safeBit = m.groupValues[5].takeIf { it.isNotBlank() }?.let { if (it == "0") 0 else if (it == "1") 1 else null },
                        coordinateDatum = coord?.datum,
                        coordinateX = coord?.x,
                        coordinateY = coord?.y,
                    )
                }
        }
        if (boundaryLength != null && cells.size != boundaryLength) {
            diags += "BOUNDARY_LENGTH_MISMATCH:declared=$boundaryLength parsed=${cells.size}"
        }

        return BsdlMapping(
            id = id,
            revisionId = revisionId,
            deviceId = deviceId,
            bsdlVersion = version,
            rawBody = body,
            idcodeHex = idHex,
            irLengthBits = irLength,
            irCapture = irCapture,
            bypassOpcodeHex = bypassHex,
            tapPorts = tapPorts.toMap(),
            cells = cells.sortedBy { it.cellIndex },
            diagnostics = diags,
        )
    }
}
