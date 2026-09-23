package jtag.core

/** Parsers keep the raw representation of every imported row; normalization
 *  (units, hex, bit order) happens only in the derived layer. Values that
 *  cannot be converted stay in place and produce a ValueDiagnostic. */
object Parsers {

    data class ParsedEdges(val edges: List<EdgeSample>, val diagnostics: List<ValueDiagnostic>)

    /** CSV: clock,tck,tms,tdi,tdo,time_raw  (time_raw e.g. "50ns", "0.05us", "n/a") */
    fun parseEdges(csv: String, idOf: (Int) -> String): ParsedEdges {
        val edges = mutableListOf<EdgeSample>()
        val diags = mutableListOf<ValueDiagnostic>()
        val lines = csv.lines().filter { it.isNotBlank() }
        for ((n, line) in lines.withIndex()) {
            if (n == 0 && line.startsWith("clock")) continue
            val c = line.split(",").map { it.trim() }
            val id = idOf(c[0].toInt())
            val (tv, tu) = parseTime(c.getOrElse(5) { "" })?.let { it.first to it.second }
                ?: run {
                    diags += ValueDiagnostic(id, "time_raw", c.getOrElse(5) { "" },
                        "time value not convertible to a known unit; kept in place with raw text")
                    null to null
                }
            edges += EdgeSample(id, c[0].toInt(), c[1].toInt(), c[2].toInt(), c[3].toInt(), c[4].toInt(),
                tv, tu, line)
        }
        return ParsedEdges(edges.sortedBy { it.clockIndex }, diags)
    }

    /** Returns value in ns plus canonical unit, or null when unconvertible. */
    private fun parseTime(raw: String): Pair<Double, String>? {
        val m = Regex("^([0-9.]+)\\s*(ns|us|ms|s)$").find(raw.trim()) ?: return null
        val v = m.groupValues[1].toDoubleOrNull() ?: return null
        val ns = when (m.groupValues[2]) {
            "ns" -> v; "us" -> v * 1e3; "ms" -> v * 1e6; "s" -> v * 1e9; else -> return null
        }
        return ns to "ns"
    }

    /** CSV: clock,kind  (kind = TRST_PULSE) */
    fun parseResets(csv: String, idOf: (Int) -> String): List<ResetEvent> =
        csv.lines().filter { it.isNotBlank() }
            .filterIndexed { n, l -> !(n == 0 && l.startsWith("clock")) }
            .map { line ->
                val c = line.split(",").map { it.trim() }
                ResetEvent(idOf(c[0].toInt()), c[0].toInt(), c[1], line)
            }

    /** CSV: device_pos,length_bits,basis,status */
    fun parseIrCandidates(csv: String, idOf: (Int) -> String): List<IrLengthCandidate> =
        csv.lines().filter { it.isNotBlank() }
            .filterIndexed { n, l -> !(n == 0 && l.startsWith("device_pos")) }
            .mapIndexed { i, line ->
                val c = line.split(",").map { it.trim() }
                IrLengthCandidate(idOf(i), c[0].toInt(), c[1].toInt(), c[2], c[3], line)
            }

    /** Minimal BSDL subset, one directive per line:
     *  device NAME / bsdl_version V / idcode 0x.. / ir_length N /
     *  instruction NAME OPCODE / cell N PIN FUNCTION safe S */
    fun parseBsdl(text: String, idOf: (Int) -> String): Pair<BsdlPart, List<BsdlCell>> {
        var device = "?"; var version = "?"; var idcode: String? = null; var irLen: Int? = null
        val cells = mutableListOf<BsdlCell>()
        for (line in text.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val w = t.split(Regex("\\s+"))
            when (w[0]) {
                "device" -> device = w[1]
                "bsdl_version" -> version = w[1]
                "idcode" -> idcode = w[1].removePrefix("0x").lowercase().padStart(8, '0')
                "ir_length" -> irLen = w[1].toInt()
                "cell" -> cells += BsdlCell(idOf(cells.size), device, version, w[1].toInt(), w[2], w[3],
                    w.getOrElse(5) { "" }, t)
            }
        }
        return BsdlPart(device, version, idcode, irLen, cells) to cells
    }

    /** JSON-ish board revision: {"revision":"R2","devices":[{"pos":0,"device":"DEV_A","expected":true},...]} */
    fun parseBoard(json: String, idOf: (Int) -> String): BoardRevision {
        val rev = Regex("\"revision\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: "?"
        val devices = Regex("\\{[^{}]*\"pos\"[^{}]*\\}").findAll(json).map { m ->
            val obj = m.value
            val pos = Regex("\"pos\"\\s*:\\s*(\\d+)").find(obj)!!.groupValues[1].toInt()
            val dev = Regex("\"device\"\\s*:\\s*\"([^\"]+)\"").find(obj)!!.groupValues[1]
            val exp = Regex("\"expected\"\\s*:\\s*(true|false)").find(obj)!!.groupValues[1] == "true"
            BoardDevice(idOf(pos), rev, pos, dev, exp, obj)
        }.toList()
        return BoardRevision(rev, devices)
    }
}
