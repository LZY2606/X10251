package jtap.bsdl

import jtap.model.BsdlCell
import jtap.model.BsdlSource
import jtap.model.PinCoordinate

/**
 * Small, deterministic BSDL reader covering exactly the boundary-scan subset
 * the replay tool needs. It never silently repairs a malformed file:
 * unparseable bits become diagnostics while parseable content is retained.
 *
 * Supported attributes:
 *   attribute INSTRUCTION_LENGTH of X : entity is N;
 *   attribute IDCODE_REGISTER of X : entity is "....";
 *   attribute INSTRUCTION_OPCODE ... (
 *       BYPASS (1111), EXTEST (0000), SAMPLE (0010), IDCODE (0110), ...);
 *   attribute BOUNDARY_REGISTER ... (
 *       num ( port, cell, function, safe [, ccell, disval, disres] ));
 */
object BsdlParser {
    private fun attrScalar(text: String, name: String): Pair<String?, String?> {
        val re = Regex("""attribute\s+$name\s+of\s+[\w"]+\s*:\s*entity\s+is\s+("([^"]*)"|([0-9a-fA-FxX]+)|(\w+))\s*;""",
            RegexOption.IGNORE_CASE)
        val m = re.find(text) ?: return null to "attribute $name not found"
        val raw = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
        return raw to null
    }

    private fun attrBlock(text: String, name: String): String? {
        val start = Regex("""attribute\s+$name\s+of\s+[\w"]+\s*:\s*entity\s+is""",
            RegexOption.IGNORE_CASE).find(text) ?: return null
        val begin = text.indexOf('(', start.range.last)
        val semi = text.indexOf(';', begin)
        if (begin < 0 || semi < 0) return null
        return text.substring(begin + 1, semi)
    }

    @Suppress("UNUSED_PARAMETER")
    fun parse(
        sourceId: String,
        revision: Int,
        deviceName: String,
        bsdlVersion: String,
        text: String,
        boardRevision: String,
    ): BsdlSource {
        val diagnostics = mutableListOf<String>()

        val (irLenRaw, irDiag) = attrScalar(text, "INSTRUCTION_LENGTH")
        irDiag?.let { diagnostics += it }
        val irLength = irLenRaw?.toIntOrNull()
        if (irLenRaw != null && irLength == null) {
            diagnostics += "INSTRUCTION_LENGTH '$irLenRaw' is not an integer; left at original position"
        }

        val (idcode, idDiag) = attrScalar(text, "IDCODE_REGISTER")
        idDiag?.let { diagnostics += it }

        val opcodes = linkedMapOf<String, Int>()
        attrBlock(text, "INSTRUCTION_OPCODE")?.let { block ->
            Regex("""(\w+)\s*\(\s*"?([01xX]+)"?\s*\)""", RegexOption.IGNORE_CASE)
                .findAll(block).forEach { m ->
                    val name = m.groupValues[1].uppercase()
                    val pattern = m.groupValues[2]
                    if (pattern.contains('x', true)) {
                        diagnostics += "opcode $name pattern '$pattern' has don't-care bits; canonical form retained"
                    }
                    opcodes[name] = pattern.count { it != 'x' && it != 'X' }
                }
        } ?: diagnostics += "INSTRUCTION_OPCODE block not found"

        val cells = mutableListOf<BsdlCell>()
        attrBlock(text, "BOUNDARY_REGISTER")?.let { block ->
            val rowRe = Regex(
                """(\d+)\s*\(\s*([\w.&]+)\s*,\s*(\w+)\s*,\s*(\w+)(?:\s*,\s*([01Xx]))?(?:\s*,[^)]*)?\)""")
            rowRe.findAll(block).forEach { m ->
                cells += BsdlCell(
                    number = m.groupValues[1].toInt(),
                    portName = m.groupValues[2],
                    cellType = m.groupValues[3].uppercase(),
                    function = m.groupValues[4].uppercase(),
                    safeBit = m.groupValues[5].ifBlank { null },
                    coordinate = PinCoordinate(m.groupValues[2], null, boardRevision),
                )
            }
            if (cells.isEmpty()) diagnostics += "BOUNDARY_REGISTER contained no parseable cells"
        } ?: diagnostics += "BOUNDARY_REGISTER block not found"

        return BsdlSource(
            sourceId = sourceId,
            revision = revision,
            deviceName = deviceName,
            bsdlVersion = bsdlVersion,
            packageBody = text,
            idcodeMask = idcode?.trim('"'),
            instructionLength = irLength,
            instructionLengthRaw = irLenRaw,
            opcodes = opcodes,
            cells = cells.sortedBy { it.number },
            rawPayload = text,
            diagnostics = diagnostics,
        )
    }

}
