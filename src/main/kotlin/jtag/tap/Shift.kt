package jtag.tap

import jtag.model.*
import kotlinx.serialization.json.*

data class ShiftSegment(
    val kind: String, val startEdge: Int, val endEdge: Int,
    val tdiBits: String, val tdoBits: String, val edgeIds: List<String>
) { val length: Int get() = tdiBits.length }

/** 从 TAP 轨迹抽取 IR/DR 移位段。时钟前沿处于 SHIFT 状态时计为一个移位位。 */
fun extractSegments(entries: List<TraceEntry>): List<ShiftSegment> {
    val segs = mutableListOf<ShiftSegment>()
    var kind: String? = null
    var start = 0
    val tdi = StringBuilder(); val tdo = StringBuilder(); val ids = mutableListOf<String>()
    var prev = TapState.TEST_LOGIC_RESET
    fun close(endEdge: Int) {
        val k = kind ?: return
        segs += ShiftSegment(k, start, endEdge, tdi.toString(), tdo.toString(), ids.toList())
        kind = null; tdi.clear(); tdo.clear(); ids.clear()
    }
    for (e in entries) {
        val shiftNow = prev == TapState.SHIFT_IR || prev == TapState.SHIFT_DR
        if (shiftNow) {
            if (kind == null) { kind = if (prev == TapState.SHIFT_IR) "IR" else "DR"; start = e.edgeIndex }
            tdi.append(e.tdi); tdo.append(e.tdo); ids += e.edgeId
        } else close(e.edgeIndex - 1)
        prev = e.state
    }
    close(entries.lastOrNull()?.edgeIndex ?: 0)
    return segs
}

data class IrCandidate(val devicePosition: Int, val lengthBits: Int, val source: String, val adopted: Boolean)
data class BoardDevice(val position: Int, val name: String, val idcode: String?, val irLength: Int?)

data class ChainDevice(
    val position: Int, val name: String, val idcode: String?,
    val irLength: Int?, val irLengthCandidates: List<Int>,
    val drOffsetCandidates: List<Int>, val bypass: Boolean, val missing: Boolean
)

data class ChainResult(val devices: List<ChainDevice>, val status: String, val events: List<Evt>)

/**
 * 扫描链解析：IR 长度多解全部保留；IDCODE 只作提示不作定论；
 * 缺件时为下游器件保留全部偏移候选。
 */
class ChainResolver {
    fun resolve(
        segments: List<ShiftSegment>,
        candidates: List<IrCandidate>,
        board: List<BoardDevice>
    ): ChainResult {
        val events = mutableListOf<Evt>()
        val drSegs = segments.filter { it.kind == "DR" }
        val irSegs = segments.filter { it.kind == "IR" }

        // --- IDCODE 扫描（复位后首个 DR 段）---
        val idcodes = mutableListOf<String>()
        var idcodeSeg: ShiftSegment? = null
        val firstDr = drSegs.firstOrNull()
        if (firstDr != null && firstDr.length >= 32 && firstDr.length % 32 == 0) {
            val bits = firstDr.tdoBits
            var ok = true
            for (off in 0 until bits.length step 32) {
                val chunk = bits.substring(off, off + 32)
                if (chunk.firstOrNull() != '1') { ok = false; break }
            }
            if (ok) {
                for (off in 0 until bits.length step 32) {
                    val chunk = bits.substring(off, off + 32)
                    var v = 0L
                    chunk.forEachIndexed { i, c -> if (c == '1') v = v or (1L shl i) }
                    idcodes += "0x%08X".format(v)
                }
                idcodeSeg = firstDr
            }
        }

        // --- 缺件检测：板卡修订列出的器件多于 IDCODE 读回数 ---
        val missingCount = (board.size - idcodes.size).coerceAtLeast(0)
        val missingPositions = mutableSetOf<Int>()
        if (idcodes.isNotEmpty() && missingCount > 0) {
            val unmatched = board.filter { it.idcode != null && it.idcode !in idcodes }.map { it.position }
            missingPositions += unmatched.take(missingCount)
            events += Evt("ChainDeviceMissing", jsonOf(
                "expected" to board.size, "observed" to idcodes.size,
                "missingPositions" to missingPositions.toList(),
                "judgment" to "keep-offset-candidates"
            ))
        }

        // --- BYPASS 推断：非首个 DR 段长度 < 32*N 时枚举 bypass 数 ---
        val bypassPositions = mutableSetOf<Int>()
        for (seg in drSegs.filter { it !== idcodeSeg }) {
            val n = board.size - missingPositions.size
            val rem = 32 * n - seg.length
            if (rem > 0 && rem % 31 == 0) {
                val b = rem / 31
                if (b in 1..n) {
                    // 哪些器件处于 bypass 无法唯一确定时，全部列为候选
                    val candidatesPos = board.filter { it.position !in missingPositions }.map { it.position }
                    bypassPositions += candidatesPos.take(b.coerceAtMost(candidatesPos.size))
                    events += Evt("BypassDeviceInferred", jsonOf(
                        "drLength" to seg.length, "deviceCount" to n, "bypassCount" to b,
                        "positionCandidates" to candidatesPos
                    ))
                }
            }
        }

        // --- IR 长度求解：枚举所有满足观测长度的组合 ---
        val observedIr = irSegs.firstOrNull()?.length
        val perPos = board.map { dev ->
            val cands = candidates.filter { it.devicePosition == dev.position }.map { it.lengthBits }.distinct().sorted()
            val fixed = dev.irLength
            dev.position to when {
                fixed != null -> listOf(fixed)
                cands.isNotEmpty() -> cands
                else -> listOf(1, 2, 3, 4, 5, 6, 7, 8)
            }
        }
        val solutions = mutableListOf<List<Int>>()
        if (observedIr != null) {
            fun rec(i: Int, acc: List<Int>, sum: Int) {
                if (i == perPos.size) { if (sum == observedIr) solutions += acc; return }
                for (l in perPos[i].second) rec(i + 1, acc + l, sum + l)
            }
            rec(0, emptyList(), 0)
        }
        val adopted = candidates.filter { it.adopted }
        val chosen: List<Int>? = when {
            adopted.isNotEmpty() && adopted.size == board.size ->
                board.map { d -> adopted.first { it.devicePosition == d.position }.lengthBits }
            solutions.size == 1 -> solutions[0]
            else -> null
        }
        if (observedIr != null && solutions.size > 1 && chosen == null) {
            events += Evt("IrLengthAmbiguous", jsonOf(
                "observedIrLength" to observedIr,
                "solutions" to solutions.map { JsonArray(it.map { JsonPrimitive(it) }) },
                "judgment" to "all-candidates-retained"
            ))
        }
        // IDCODE 只提供器件数量提示，不能单独猜定 IR 长度
        for (c in candidates.filter { it.source == "IDCODE_HINT" }) {
            val corroborated = candidates.any {
                it.devicePosition == c.devicePosition && it.lengthBits == c.lengthBits && it.source != "IDCODE_HINT"
            } || board.firstOrNull { it.position == c.devicePosition }?.irLength == c.lengthBits
            if (!corroborated) {
                events += Evt("IrLengthIdcodeRejected", jsonOf(
                    "devicePosition" to c.devicePosition, "lengthBits" to c.lengthBits,
                    "judgment" to "ir-length-not-from-idcode-alone"
                ))
            }
        }

        // --- 偏移候选：缺件时为下游保留多个合法偏移 ---
        val devices = board.mapIndexed { idx, dev ->
            val missing = dev.position in missingPositions
            val offsets = mutableSetOf<Int>()
            if (idcodes.isNotEmpty()) {
                // 每个 IDCODE 器件 32 位；缺件可能占据其前任意位置 -> 偏移为区间候选
                val presentBefore = board.take(idx).count { it.position !in missingPositions }
                val missingBeforeMax = board.take(idx).count { it.position in missingPositions }
                for (m in 0..missingBeforeMax) offsets += (presentBefore + m) * 32
            } else {
                offsets += 0
            }
            ChainDevice(
                position = dev.position, name = dev.name,
                idcode = dev.idcode?.takeIf { it in idcodes },
                irLength = chosen?.get(idx),
                irLengthCandidates = chosen?.get(idx)?.let { listOf(it) } ?: perPos[idx].second,
                drOffsetCandidates = offsets.sorted(),
                bypass = dev.position in bypassPositions,
                missing = missing
            )
        }
        val status = when {
            missingPositions.isNotEmpty() -> "MISSING_DEVICE"
            chosen == null && observedIr != null -> "AMBIGUOUS"
            else -> "RESOLVED"
        }
        return ChainResult(devices, status, events)
    }
}
