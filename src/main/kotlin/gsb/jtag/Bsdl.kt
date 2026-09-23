package gsb.jtag

/** Minimal BSDL subset parser: enough of IEEE 1149.1b to recover
 *  INSTRUCTION_LENGTH, IDCODE_REGISTER, PIN_MAP and BOUNDARY_REGISTER. */
object Bsdl {

    fun parse(id: String, device: String, version: String, text: String): BsdlMap {
        val irLen = Regex("""INSTRUCTION_LENGTH\s+is\s+(\d+)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.toInt()
            ?: throw IllegalArgumentException("BSDL $device@$version: no INSTRUCTION_LENGTH")
        val idcode = Regex("""IDCODE_REGISTER\s+is\s+"(\d{32})"""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)
            ?.let { it.toLong(2).toString(16).padStart(8, '0') }
        val pinMapName = Regex("""PIN_MAP\s+is\s+(\w+)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)
        val pins = mutableMapOf<String, String>()
        if (pinMapName != null) {
            val body = Regex("""$pinMapName\s*:\s*PIN_MAP_STRING\s*:=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1) ?: ""
            Regex("""(\w+)\s*:\s*([A-Za-z0-9_]+)""").findAll(body).forEach {
                pins[it.groupValues[1]] = it.groupValues[2]
            }
        }
        val cells = mutableListOf<BsdlCell>()
        val bsr = Regex("""BOUNDARY_REGISTER\s+is\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1) ?: ""
        Regex("""(\d+)\s*\(\s*(\w+)\s*\(\s*(\w+)\s*,\s*(\w+)\s*,\s*(\w+)""").findAll(bsr).forEach {
            // "3 (BC_1 (IO1, input, X))," style: num (cell (port, function, safe))
            cells += BsdlCell(it.groupValues[1].toInt(), it.groupValues[3], it.groupValues[4], it.groupValues[5])
        }
        return BsdlMap(id, device, version, irLen, idcode, cells.sortedBy { it.index }, pins)
    }
}
