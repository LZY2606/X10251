package tapreplay.core

/** 从轨迹中抽取移位段（IR/DR），并从 DR 扫描重建器件链与缺件偏移候选。 */
object ScanReconstructor {

    /**
     * 扫描段语义：
     *  - 进入 SHIFT_* 后，每个“保持在 SHIFT 的 TMS=0”边沿贡献一位；
     *  - 离开移位的 TMS=1 边沿同时完成最后一位（标准捕获约定）；
     *  - 若随后进入 TEST_LOGIC_RESET 或 UNKNOWN，标记 aborted，保留已收位。
     */
    fun extract(trace: List<TraceStep>, branchId: String, events: MutableList<EngineEvent>): List<ScanSegment> {
        val scans = mutableListOf<ScanSegment>()
        var i = 0
        while (i < trace.size) {
            val st = trace[i]
            val shifting = st.to == TapState.SHIFT_DR || st.to == TapState.SHIFT_IR ||
                st.from == TapState.SHIFT_DR || st.from == TapState.SHIFT_IR
            if (shifting) {
                val kind = if (st.to == TapState.SHIFT_IR || st.from == TapState.SHIFT_IR) "IR" else "DR"
                val tdi = mutableListOf<Int>()
                val tdo = mutableListOf<Int>()
                val startEdge = st.edgeIndex ?: i
                var aborted = false
                var abortReason: String? = null
                var leftTo: TapState? = null
                var j = i
                // 从进入 SHIFT 之后的边沿开始收位
                while (j < trace.size) {
                    val s = trace[j]
                    val inShift = s.from == TapState.SHIFT_DR || s.from == TapState.SHIFT_IR
                    val enteringShift = (s.to == TapState.SHIFT_DR || s.to == TapState.SHIFT_IR) && !inShift
                    if (inShift) {
                        if (s.tms == 0) {
                            s.tdi?.let { tdi += it }
                            s.tdo?.let { tdo += it }
                        } else {
                            // 离开移位：最后一位在此边沿完成
                            s.tdi?.let { tdi += it }
                            s.tdo?.let { tdo += it }
                            leftTo = s.to
                            if (s.to == TapState.TEST_LOGIC_RESET) {
                                aborted = true
                                abortReason = "MID_SCAN_RESET"
                            }
                            j++
                            break
                        }
                    } else if (s.from == TapState.UNKNOWN || s.to == TapState.UNKNOWN) {
                        aborted = true
                        abortReason = "NON_BINARY_SIGNAL_DURING_SHIFT"
                        j++
                        break
                    } else if (!enteringShift && tdi.isNotEmpty()) {
                        leftTo = s.to
                        break
                    }
                    j++
                }
                val seg = ScanSegment(
                    id = "scan-$branchId-${kind}-$startEdge",
                    kind = kind,
                    startEdgeIndex = startEdge,
                    endEdgeIndex = trace.getOrNull(j - 1)?.edgeIndex,
                    shiftTdiBits = tdi,
                    shiftTdoBits = tdo,
                    enteredFrom = st.from,
                    leftTo = leftTo,
                    aborted = aborted,
                    abortReason = abortReason,
                )
                scans += seg
                if (aborted) {
                    events += EngineEvent(
                        "evt-$branchId-abort-$startEdge", "ABORTED_SCAN_SEGMENT", "ERROR",
                        st.edgeId, st.edgeIndex, seg.id,
                        "$kind 移位段 @$startEdge 中止（$abortReason），已保留 ${tdi.size} 个 TDI 位 / ${tdo.size} 个 TDO 位",
                        mapOf("rule" to "ABORTED_BITS_ARE_RETAINED", "tdiBits" to tdi.size.toString()),
                    )
                }
                i = j
            } else i++
        }
        return scans
    }

    private fun bitsToHexLsbFirst(bits: List<Int>): String {
        val padded = bits + List((32 - bits.size % 32) % 32) { 0 }
        return padded.chunked(32).joinToString("") { word ->
            // TDO 先出 LSB
            val v = word.foldIndexed(0) { idx, acc, b -> acc or (b shl idx) }
            "%08X".format(v)
        }
    }

    data class Slice(val width: Int, val bits: List<Int>, val hex: String?)

    /** 把一次 DR 扫描的 TDO 按“槽宽”从 TDO 侧切开（先出的在最靠近 TDO 的槽）。 */
    fun sliceTdo(tdo: List<Int>, widths: List<Int>): List<Slice> {
        val out = mutableListOf<Slice>()
        var pos = 0
        widths.forEach { w ->
            val seg = tdo.subList(pos, (pos + w).coerceAtMost(tdo.size)).toList()
            pos += w
            out += Slice(w, seg, if (w == 32) bitsToHexLsbFirst(seg) else null)
        }
        return out
    }
}
