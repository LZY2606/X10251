package com.boardscan.tap

import com.boardscan.model.*

/**
 * 导入层：原始字符串原样进入 RawSample；本类在派生层做规范化。
 * 无法转换的值得到 LogicLevel.X + 诊断，绝不静默丢弃。
 */
object SignalDecoder {

    private val voltageRegex = Regex("""^\s*([+-]?\d+(?:\.\d+)?)\s*(v|V|mV|mv)\s*$""")
    private val timeRegex = Regex("""^\s*([+-]?\d+(?:\.\d+)?)\s*(ns|us|µs|ms|s)\s*$""")

    fun decodeLevel(raw: String, thresholdV: Double, signalName: String): LevelDecode {
        val t = raw.trim()
        if (t.isEmpty()) return LevelDecode(LogicLevel.X, raw, "$signalName 采样为空，无法判定逻辑电平")
        when (t.lowercase()) {
            "0", "l", "low" -> return LevelDecode(LogicLevel.ZERO, raw)
            "1", "h", "high" -> return LevelDecode(LogicLevel.ONE, raw)
            "x", "z", "u", "?" -> return LevelDecode(LogicLevel.X, raw, "$signalName 原始表示 '$t' 表示高阻/未定义")
        }
        voltageRegex.matchEntire(t)?.let { m ->
            val v = m.groupValues[1].toDouble()
            val unit = m.groupValues[2].lowercase()
            val volts = if (unit == "mv") v / 1000.0 else v
            return when {
                volts < 0.0 -> LevelDecode(LogicLevel.X, raw, "$signalName 电压 ${v}${unit} 为负，无法映射到 0/1")
                volts < thresholdV -> LevelDecode(LogicLevel.ZERO, raw)
                else -> LevelDecode(LogicLevel.ONE, raw)
            }
        }
        return LevelDecode(LogicLevel.X, raw, "$signalName 值 '$t' 无法按符号或电压门限 ${thresholdV}V 转换")
    }

    fun normalizeTime(raw: String, basis: String): ValueWithUnit {
        val t = raw.trim()
        timeRegex.matchEntire(t)?.let { m ->
            val v = m.groupValues[1].toDouble()
            val unit = m.groupValues[3]
            val ns = when (unit) {
                "ns" -> v
                "us", "µs" -> v * 1000.0
                "ms" -> v * 1_000_000.0
                "s" -> v * 1_000_000_000.0
                else -> v
            }
            return ValueWithUnit(raw = raw, normalized = ns, unit = unit, basis = basis)
        }
        return ValueWithUnit(raw = raw, diagnostic = "时间 '$t' 无法按 ns/us/ms/s 转换，保留原值")
    }

    /**
     * 把原始采样行转成“有效沿”序列。只有 TCK 在有效边沿方向跨过门限的行才产生 DecodedEdge。
     * 非有效沿的行不参与状态推进；门限无法转换的行产生 UNCONVERTIBLE_VALUE 诊断。
     */
    fun decodeEdges(import: SampleImport, polarity: PolarityConfig): Pair<List<DecodedEdge>, List<Finding>> {
        val diagnostics = mutableListOf<Finding>()
        val decoded = import.rows.map { row ->
            val tck = decodeLevel(row.tckRaw, polarity.tckThresholdV, "TCK")
            val tms = decodeLevel(row.tmsRaw, polarity.signalThresholdV, "TMS")
            val tdi = decodeLevel(row.tdiRaw, polarity.signalThresholdV, "TDI")
            val tdo = decodeLevel(row.tdoRaw, polarity.signalThresholdV, "TDO")
            val time = normalizeTime(row.timeRaw, polarity.timeUnitBasis)
            listOf(tck, tms, tdi, tdo).forEach { d ->
                if (d.diagnostic != null && d.level == LogicLevel.X &&
                    !d.diagnostic.contains("高阻") && !d.diagnostic.contains("未定义")) {
                    diagnostics += Finding(
                        findingId = "f-diag-${import.sampleId}-${row.edgeId}-${d.hashCode()}",
                        code = FindingCode.UNCONVERTIBLE_VALUE,
                        severity = FindingSeverity.WARNING,
                        message = "采样 ${row.edgeId}：${d.diagnostic}",
                        runId = "",
                        branchLabel = polarity.polarityId,
                        edgeId = row.edgeId,
                        refs = mapOf("raw" to d.raw, "sampleId" to import.sampleId),
                    )
                }
            }
            if (time.diagnostic != null) {
                diagnostics += Finding(
                    findingId = "f-diag-${import.sampleId}-${row.edgeId}-time",
                    code = FindingCode.UNCONVERTIBLE_VALUE,
                    severity = FindingSeverity.WARNING,
                    message = "采样 ${row.edgeId}：${time.diagnostic}",
                    runId = "",
                    branchLabel = polarity.polarityId,
                    edgeId = row.edgeId,
                    refs = mapOf("raw" to row.timeRaw),
                )
            }
            DecodedEdgeRow(row, tck, tms, tdi, tdo, time)
        }

        val edges = mutableListOf<DecodedEdge>()
        var prevTck: LogicLevel? = null
        for (r in decoded) {
            val active = prevTck != null && isActiveTransition(prevTck!!, r.tck.level, polarity.activeEdge)
            if (active) {
                edges += DecodedEdge(
                    edgeId = r.row.edgeId, order = r.row.order, time = r.time,
                    tck = r.tck, tms = r.tms, tdi = r.tdi, tdo = r.tdo,
                    activeEdge = polarity.activeEdge,
                )
            }
            if (r.tck.level != LogicLevel.X) prevTck = r.tck.level
        }

        // 沿的顺序应单调；按归一化时间核对（无法转换的行跳过，但诊断已保留）。
        var lastTime: Double? = null
        for (e in edges) {
            val t = e.time.normalized
            if (t != null) {
                if (lastTime != null && t < lastTime) {
                    diagnostics += Finding(
                        findingId = "f-order-${import.sampleId}-${e.edgeId}",
                        code = FindingCode.INCONSISTENT_EDGE_ORDER,
                        severity = FindingSeverity.ERROR,
                        message = "有效沿 ${e.edgeId} 时间 ${e.time.raw} 早于前一沿，采样顺序与时间不一致。",
                        runId = "",
                        branchLabel = polarity.polarityId,
                        edgeId = e.edgeId,
                        refs = mapOf("previousTimeNs" to lastTime.toString(), "timeNs" to t.toString()),
                    )
                }
                lastTime = t
            }
        }
        return edges to diagnostics
    }

    private fun isActiveTransition(prev: LogicLevel, now: LogicLevel, active: Edge): Boolean {
        if (prev == LogicLevel.X || now == LogicLevel.X) return false
        return when (active) {
            Edge.RISING -> prev == LogicLevel.ZERO && now == LogicLevel.ONE
            Edge.FALLING -> prev == LogicLevel.ONE && now == LogicLevel.ZERO
        }
    }

    private data class DecodedEdgeRow(
        val row: RawSample,
        val tck: LevelDecode,
        val tms: LevelDecode,
        val tdi: LevelDecode,
        val tdo: LevelDecode,
        val time: ValueWithUnit,
    )
}
