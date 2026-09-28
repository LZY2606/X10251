package com.boardscan.bsdl

import com.boardscan.model.*
import kotlinx.serialization.Serializable

/** 边界扫描单元（BCR）：单元号 -> 逻辑端口，cell_type/function 全部保留 BSDL 原文。 */
@Serializable
data class BoundaryCell(
    val cellNumber: Int,
    val portName: String?,
    val cellType: String,
    val function: String,
    val safeBit: String?,
    val rawTuple: String,
)

@Serializable
data class InstructionDef(
    val name: String,
    val opcode: String,
    val regLength: Int?,
    val raw: String,
)

@Serializable
data class ParsedBsdl(
    val bsdlId: String,
    val entityName: String,
    val version: String,
    val irLengthBits: Int,
    val idcode: String?,
    val cells: List<BoundaryCell>,
    val instructions: List<InstructionDef>,
    val diagnostics: List<String>,
    val rawText: String,
)

/** 板卡修订：逻辑端口到封装引脚再到板上网络的映射，独立于 BSDL 版本存储。 */
@Serializable
data class BoardRevision(
    val boardRevisionId: String,
    val name: String,
    val revision: String,
    val coordinateBasis: String,
    val portToPin: Map<String, PinMapping>,
)

@Serializable
data class PinMapping(
    val portName: String,
    val packagePin: String?,
    val netName: String?,
    val xMm: Double?,
    val yMm: Double?,
    val coordinateBasis: String,
    val raw: String,
    val diagnostic: String? = null,
)

/** 单元 -> 端口 -> 封装引脚 -> 网络 的证据链。缺任何一环都保留诊断而不臆造。 */
@Serializable
data class CellPinEvidence(
    val cellNumber: Int,
    val portName: String?,
    val packagePin: String?,
    val netName: String?,
    val coordinateBasis: String?,
    val xMm: Double?,
    val yMm: Double?,
    val bsdlVersion: String,
    val boardRevisionId: String,
    val diagnostic: String?,
)

object BsdlParser {
    private fun stripComments(text: String): String =
        text.lineSequence().map { line ->
            val idx = line.indexOf("--")
            if (idx >= 0) line.substring(0, idx) else line
        }.joinToString("\n")

    private fun attr(text: String, name: String): String? {
        val regex = Regex("""attribute\s+$name\s+of\s+\w+\s*:\s*entity\s+is\s*(.*?)\s*;""",
            RegexOption.IGNORE_CASE or RegexOption.DOT_MATCHES_ALL)
        return regex.find(text)?.groupValues?.get(1)?.trim()
    }

    fun parse(bsdlId: String, rawText: String): ParsedBsdl {
        val diagnostics = mutableListOf<String>()
        val text = stripComments(rawText)

        val entityMatch = Regex("""entity\s+(\w+)\s+is""", RegexOption.IGNORE_CASE).find(text)
        val entity = entityMatch?.groupValues?.get(1)
        if (entity == null) diagnostics += "未找到 entity 名"

        val verRaw = attr(text, "BOUNDARY_SCAN_VERSION")?.trim('"') ?: "0000"
        val version = verRaw.ifEmpty { "0000" }

        val irLength = attr(text, "INSTRUCTION_LENGTH")?.filter { it.isDigit() }?.toIntOrNull()
            ?: run { diagnostics += "缺少 INSTRUCTION_LENGTH，IR 长度不可由 BSDL 裁定"; 0 }

        val idcode = attr(text, "IDCODE_REGISTER")?.let { extractQuoted(it) }

        val instrRaw = attr(text, "INSTRUCTION_OPCODE") ?: "".also {
            diagnostics += "缺少 INSTRUCTION_OPCODE"
        }
        val instructions = parseOpcodes(instrRaw)

        val cellsRaw = attr(text, "BOUNDARY_REGISTER") ?: "".also {
            diagnostics += "缺少 BOUNDARY_REGISTER"
        }
        val cells = parseBoundaryCells(cellsRaw) { diagnostics += it }

        return ParsedBsdl(
            bsdlId = bsdlId,
            entityName = entity ?: "UNKNOWN",
            version = version,
            irLengthBits = irLength,
            idcode = idcode,
            cells = cells.sortedBy { it.cellNumber },
            instructions = instructions,
            diagnostics = diagnostics,
            rawText = rawText,
        )
    }

    private fun extractQuoted(s: String): String? =
        Regex("""["']([^"']+)["']""").find(s)?.groupValues?.get(1)

    private fun parseOpcodes(raw: String): List<InstructionDef> {
        val body = raw.trim().trimStart('(').trimEnd(')')
        return body.split(Regex("""\)\s*,\s*\("""))
            .mapNotNull { chunk ->
                val cleaned = chunk.trim().trim('(', ')', '"', ' ')
                val firstComma = cleaned.indexOf(',')
                if (firstComma < 0) return@mapNotNull null
                val name = cleaned.substring(0, firstComma).trim().trim('"')
                val rest = cleaned.substring(firstComma + 1)
                val opcodeMatch = Regex("""["']([01xX]+)["']""").find(rest)
                val opcode = opcodeMatch?.groupValues?.get(1) ?: return@mapNotNull null
                val captureMatch = Regex("""capture\s*\(\s*([A-Za-z0-9_]+)\s*,\s*(\d+)\s*\)""",
                    RegexOption.IGNORE_CASE).find(rest)
                InstructionDef(
                    name = name.uppercase(),
                    opcode = opcode,
                    regLength = captureMatch?.groupValues?.get(2)?.toIntOrNull(),
                    raw = "($cleaned)",
                )
            }
    }

    private fun parseBoundaryCells(raw: String, diag: (String) -> Unit): List<BoundaryCell> {
        val body = raw.trim().trimStart('(').trimEnd(')')
        return body.split(Regex("""\)\s*,\s*\("""))
            .mapNotNull { chunk ->
                val parts = chunk.trim().trim('(', ')', ' ').split(",").map { it.trim().trim('"') }
                if (parts.size < 4) {
                    if (parts.isNotEmpty() && parts[0].isNotEmpty()) diag("边界单元元组无法解析: ($chunk)")
                    return@mapNotNull null
                }
                val num = parts[0].toIntOrNull()
                if (num == null) {
                    diag("边界单元编号非整数: '${parts[0]}'")
                    return@mapNotNull null
                }
                BoundaryCell(
                    cellNumber = num,
                    portName = parts.getOrNull(1)?.takeUnless { it.equals("*", true) || it.isEmpty() },
                    cellType = parts.getOrNull(2) ?: "",
                    function = parts.getOrNull(3) ?: "",
                    safeBit = parts.getOrNull(4)?.takeIf { it.isNotEmpty() },
                    rawTuple = "($chunk)",
                )
            }
    }
}
