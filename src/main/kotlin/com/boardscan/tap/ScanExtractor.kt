package com.boardscan.tap

import com.boardscan.model.*

/**
 * 从 TAP 轨迹抽取移位段。
 *
 * 位计数约定：CAPTURE_x->SHIFT_x 的那一拍是第 0 位（TDO 为捕获值 LSB），
 * SHIFT_x->EXIT1_x（TMS=1）那一拍是最后一位，共 N 拍。
 * 中途被 TLR 终止的段 completed=false, abortedByResetId 指向复位沿，位流仍保留。
 */
object ScanExtractor {
    fun extract(runId: String, branchLabel: String, steps: List<TapStep>, resetEdges: List<String>): List<ScanSegment> {
        val segments = mutableListOf<ScanSegment>()
        var position = 0
        var i = 0
        while (i < steps.size) {
            val step = steps[i]
            val reg = when {
                step.fromState == TapState.CAPTURE_IR && step.toState == TapState.SHIFT_IR -> ScanReg.IR
                step.fromState == TapState.CAPTURE_DR && step.toState == TapState.SHIFT_DR -> ScanReg.DR
                else -> null
            }
            if (reg == null) {
                i += 1
                continue
            }
            val wantShift = if (reg == ScanReg.IR) TapState.SHIFT_IR else TapState.SHIFT_DR
            val wantExit = if (reg == ScanReg.IR) TapState.EXIT1_IR else TapState.EXIT1_DR
            val wantUpdate = if (reg == ScanReg.IR) TapState.UPDATE_IR else TapState.UPDATE_DR

            val bitSteps = mutableListOf<TapStep>()
            bitSteps += step
            var j = i + 1
            while (j < steps.size && steps[j].fromState == wantShift &&
                (steps[j].toState == wantShift || steps[j].toState == wantExit)) {
                bitSteps += steps[j]
                if (steps[j].toState == wantExit) {
                    j += 1
                    break
                }
                j += 1
            }
            val exitStep = bitSteps.lastOrNull { it.toState == wantExit }

            var completed = false
            var abortedBy: String? = null
            var k = j
            while (k < steps.size) {
                val s = steps[k]
                if (s.toState == wantUpdate) { completed = true; break }
                if (s.toState == TapState.TLR) { abortedBy = s.edgeId; break }
                if (s.toState == TapState.SHIFT_DR || s.toState == TapState.SHIFT_IR ||
                    s.toState == TapState.CAPTURE_DR || s.toState == TapState.CAPTURE_IR) break
                k += 1
            }
            val unresolved = bitSteps.any { it.tdo == LogicLevel.X || it.tdi == LogicLevel.X }
            segments += ScanSegment(
                segmentId = "seg-$runId-$branchLabel-$position",
                runId = runId,
                branchLabel = branchLabel,
                reg = reg,
                position = position,
                startEdgeId = step.edgeId,
                exitEdgeId = exitStep?.edgeId,
                completed = completed,
                abortedByResetId = abortedBy,
                tdiBits = bitSteps.map { it.tdi },
                tdoBits = bitSteps.map { it.tdo },
                edgeIds = bitSteps.map { it.edgeId },
                note = if (abortedBy != null) "中途复位截断" else if (!completed) "开放扫描段" else if (unresolved) "含 X 位" else "",
            )
            position += 1
            i = j
        }
        return segments
    }
}
