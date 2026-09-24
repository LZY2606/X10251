package org.boarddebug.tap.domain

import java.util.UUID

/** 稳定身份：所有持久化对象使用 UUID；导入/导出保持不变。 */
object Ids {
    fun new(): String = UUID.randomUUID().toString()
}

/** 采样电平：0/1 为确定值，X 为未知，Z 为高阻；原始词法与诊断一并保留。 */
enum class SignalLevel { ZERO, ONE, X, Z;
    val isDeterministic: Boolean get() = this == ZERO || this == ONE
    companion object {
        fun parse(token: String): Pair<SignalLevel, String?> = when (token.uppercase()) {
            "0", "L" -> ZERO to null
            "1", "H" -> ONE to null
            "X", "?" -> X to null
            "Z" -> Z to null
            else -> X to "无法识别的电平词法 '$token'，按未知 X 保留在原位置"
        }
    }
}

data class LogicBit(val level: SignalLevel, val rawToken: String, val diag: String? = null) {
    fun render(): String = when (level) {
        SignalLevel.ZERO -> "0"; SignalLevel.ONE -> "1"; SignalLevel.X -> "x"; SignalLevel.Z -> "z"
    }
    companion object {
        fun of(raw: String): LogicBit {
            val (lvl, diag) = SignalLevel.parse(raw.trim())
            return LogicBit(lvl, raw.trim(), diag)
        }
    }
}

/**
 * 领域量携带单位或坐标基准。导入时保留原始表示；派生层才规范化。
 * 无法转换的值 canonical == null 并附诊断，绝不丢弃。
 */
data class PhysicalQuantity(
    val raw: String,
    val unit: String,
    val basis: String,
    val canonical: Double? = null,
    val diag: String? = null
) {
    companion object {
        private val UNIT_TO_NANOS = mapOf(
            "ns" to 1.0, "us" to 1_000.0, "ms" to 1_000_000.0, "s" to 1_000_000_000.0
        )
        fun time(raw: String, declaredUnit: String, basis: String): PhysicalQuantity {
            val t = raw.trim()
            val n = t.toDoubleOrNull()
            if (n == null) return PhysicalQuantity(t, declaredUnit, basis, null, "时间值 '$t' 无法转换为数值，保留原表示")
            val factor = UNIT_TO_NANOS[declaredUnit]
                ?: return PhysicalQuantity(t, declaredUnit, basis, null, "未知时间单位 '$declaredUnit'，无法规范化，保留原表示")
            return PhysicalQuantity(t, declaredUnit, basis, n * factor, null)
        }
        fun coord(raw: String, basis: String): PhysicalQuantity =
            PhysicalQuantity(raw.trim(), "pin-coordinate", basis, null, "物理引脚坐标以原始坐标基准保留")
    }
}

data class ImportDiagnostic(val seq: Int, val code: String, val message: String, val rawLine: String)

/** 导入采样的头部声明：采样边沿（采样极性）与时间基准。 */
data class SampleHeader(
    val name: String,
    val timeUnit: String,
    val captureEdge: String, // rising | falling —— 声明的采样边沿
    val powerOnReset: Boolean,
    val rawHeader: List<String>
)

data class SampleRun(
    val id: String,
    val name: String,
    val rawText: String,
    val timeUnit: String,
    val captureEdge: String,
    val powerOnReset: Boolean,
    val importedAt: String,
    val diagnostics: List<ImportDiagnostic>
)

/** 单条采样边沿；原始行、原始词法全部保留。 */
data class SampleEdge(
    val id: String,
    val runId: String,
    val seq: Int,
    val t: PhysicalQuantity,
    val edgeDirection: String, // rise | fall | idle（原始记录的时钟方向）
    val tms: LogicBit,
    val tck: LogicBit,
    val tdi: LogicBit,
    val tdo: LogicBit,
    val rawLine: String
)
