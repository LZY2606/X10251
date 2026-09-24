package jtag

import java.security.MessageDigest

/** Deterministic staged deriver. Stages form a dependency graph:
 *  NORMALIZE -> TRACE -> SEGMENTS -> CHAIN -> EVIDENCE.
 *  Recompute only stages whose inputs changed; earlier valid stages are reused
 *  and an explanatory event records why they remain valid. */
class Engine(
    val samples: SampleEdgeSet,
    val resets: ResetEventSet,
    val irCandidates: IrLengthCandidateSet,
    val mappings: List<BsdlMapping>,
    val board: BoardRevision,
    val params: DeriveParams
) {
    val events = mutableListOf<JudgeEvent>()
    private val stageHashes = mutableMapOf<String, String>()

    fun stageHash(stage: String) = stageHashes[stage]

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /** stage -> hash of its direct inputs, used for dependency propagation. */
    fun inputHashes(): Map<String, String> = mapOf(
        "NORMALIZE" to hash("${samples.id}:${samples.edges.size}:${samples.clockUnit}:${params.polarityOverride}:${samples.activeEdge}:${samples.minPeriodRaw}"),
        "TRACE" to hash("${resets.id}:${resets.events}:${params.polarityOverride}"),
        "SEGMENTS" to hash("trace"),
        "CHAIN" to hash("${irCandidates.id}:${irCandidates.perDevice.keys}:${board.id}:${board.revision}:${params.chosenIrLengths}"),
        "EVIDENCE" to hash("${mappings.map { it.id + it.mappingVersion }}")
    )

    // ---- stage 1: normalize units (raw kept, derived layer normalizes) ----
    data class Norm(val tPs: List<Long?>, val minPeriodPs: Long?, val polarity: String)

    fun normalize(): Norm {
        val unitPs = unitToPs(samples.clockUnit)
        val tPs = samples.edges.map { e ->
            val v = e.tRaw.toDoubleOrNull()
            when {
                v == null -> { events += JudgeEvent(EventKinds.UNIT_UNCONVERTIBLE, "WARN",
                    "无法解析时间戳 '${e.tRaw}'，原始值保留在源层", listOf(samples.id), "NORMALIZE"); null }
                unitPs == null -> { events += JudgeEvent(EventKinds.UNIT_UNCONVERTIBLE, "WARN",
                    "未知单位 '${samples.clockUnit}'，时间值不做规范化，原始表示保留", listOf(samples.id), "NORMALIZE"); null }
                else -> (v * unitPs).toLong()
            }
        }
        val minP = samples.minPeriodRaw.toDoubleOrNull()?.let { if (unitPs != null) (it * unitPs).toLong() else null }
        val polarity = params.polarityOverride ?: samples.activeEdge
        if (polarity != samples.activeEdge)
            events += JudgeEvent(EventKinds.POLARITY_MISMATCH, "WARN",
                "采样极性被覆盖为 $polarity（源记录为 ${samples.activeEdge}）", listOf(samples.id), "NORMALIZE")
        return Norm(tPs, minP, polarity)
    }

    private fun unitToPs(u: String): Long? = when (u) {
        "ps" -> 1L; "ns" -> 1_000L; "us" -> 1_000_000L; else -> null
    }

    // ---- stage 2: TAP trace; illegal transitions & mid-stream resets are events, never skipped ----
    fun trace(norm: Norm): List<TracePoint> {
        val pts = mutableListOf<TracePoint>()
        var state = TapState.TEST_LOGIC_RESET
        var tmsHighRun = 0
        var started = false
        val resetIdx = resets.events.map { it.atEdgeIndex }.toSet()
        samples.edges.forEachIndexed { i, e ->
            // glitch: TCK period below minimum => illegal transition evidence
            if (i > 0 && norm.minPeriodPs != null && norm.tPs[i] != null && norm.tPs[i - 1] != null) {
                val dt = norm.tPs[i]!! - norm.tPs[i - 1]!!
                if (dt < norm.minPeriodPs)
                    events += JudgeEvent(EventKinds.ILLEGAL_TRANSITION, "ERROR",
                        "边沿 $i 的 TCK 周期 ${dt}ps 小于最小周期 ${norm.minPeriodPs}ps，判定为非法转移（毛刺），不静默跳过",
                        listOf(samples.id, "edge:$i"), "TRACE")
            }
            tmsHighRun = if (e.tms == 1) tmsHighRun + 1 else 0
            val prev = state
            state = state.next(e.tms)
            if (tmsHighRun >= 5 && started && prev != TapState.TEST_LOGIC_RESET)
                events += JudgeEvent(EventKinds.MID_STREAM_RESET, "ERROR",
                    "边沿 $i 处检测到中途复位（连续 TMS=1），扫描上下文作废但事件保留",
                    listOf(samples.id, "edge:$i"), "TRACE")
            if (i in resetIdx && started)
                events += JudgeEvent(EventKinds.MID_STREAM_RESET, "ERROR",
                    "边沿 $i 处记录了复位事件（${resets.events.first { it.atEdgeIndex == i }.kind}）",
                    listOf(resets.id, "edge:$i"), "TRACE")
            if (state != TapState.TEST_LOGIC_RESET) started = true
            pts += TracePoint(i, norm.tPs[i], e.tRaw, state.name, e.tms)
        }
        return pts
    }

    // ---- stage 3: shift segments (IR/DR bitstreams) ----
    fun segments(pts: List<TracePoint>): List<ShiftSegment> {
        val segs = mutableListOf<ShiftSegment>()
        var i = 0
        while (i < pts.size) {
            val st = pts[i].state
            if (st == "SHIFT_IR" || st == "SHIFT_DR") {
                val kind = if (st == "SHIFT_IR") "IR" else "DR"
                val start = i
                val tdi = StringBuilder(); val tdo = StringBuilder()
                while (i < pts.size && pts[i].state == st) {
                    tdi.append(samples.edges[i].tdi); tdo.append(samples.edges[i].tdo); i++
                }
                segs += ShiftSegment(kind, start, tdi.length, tdi.toString(), tdo.toString())
                if (kind == "IR" && tdo.length >= 2) {
                    // Capture-IR loads '01' into the two LSBs; they appear first on TDO.
                    val head = tdo.substring(0, 2)
                    if (head != "01" && head != "10")
                        events += JudgeEvent(EventKinds.CAPTURE_VIOLATION, "ERROR",
                            "Shift-IR 捕获到的 TDO 头两位为 '$head'，不符合 Capture-IR '01' 模式（可能极性/同步错误）",
                            listOf(samples.id, "edge:$start"), "SEGMENTS")
                }
            } else i++
        }
        return segs
    }

    // ---- stage 4: chain reconstruction; keep all legal candidates ----
    fun chain(segs: List<ShiftSegment>): List<ChainHypothesis> {
        val irSeg = segs.firstOrNull { it.kind == "IR" } ?: return emptyList()
        val totalIr = irSeg.bitCount
        val drSeg = segs.firstOrNull { it.kind == "DR" }
        val expected = board.expectedDevices
        // enumerate compositions of candidate IR lengths summing to totalIr
        val perDev = expected.map { d ->
            val cands = irCandidates.perDevice[d.name]?.map { it.length }
                ?: run {
                    events += JudgeEvent(EventKinds.IR_LENGTH_UNRESOLVED, "WARN",
                        "器件 ${d.name} 没有 IR 长度候选来源；IDCODE 不能用来猜定 IR 长度，保留未决",
                        listOf(irCandidates.id, d.name), "CHAIN")
                    emptyList()
                }
            d to (params.chosenIrLengths[d.name]?.let { listOf(it) } ?: cands)
        }
        val combos = mutableListOf<List<Int>>()
        fun bt(idx: Int, acc: List<Int>, sum: Int) {
            if (idx == perDev.size) { if (sum == totalIr) combos += acc; return }
            for (l in perDev[idx].second) bt(idx + 1, acc + l, sum + l)
        }
        bt(0, emptyList(), 0)
        if (combos.isEmpty()) {
            // device missing on chain: observed IR shorter than any full composition.
            // keep offset candidates = every position where a device could be absent.
            val missingAt = expected.indices.toList()
            events += JudgeEvent(EventKinds.MISSING_DEVICE, "ERROR",
                "观测 IR 总长 $totalIr 与期望链不匹配；保留缺件偏移候选 $missingAt",
                listOf(board.id, irCandidates.id), "CHAIN")
            val hyps = missingAt.map { pos ->
                val devs = expected.filterIndexed { idx, _ -> idx != pos }.mapIndexed { n, d ->
                    ChainDevice(n, d.name, perDev[expected.indexOf(d)].second.firstOrNull() ?: 0,
                        d.idcode, d.bypassOnly, "OK")
                }
                ChainHypothesis("hyp-missing-$pos", devs, listOf(pos), "MISSING_DEVICE")
            }
            return hyps
        }
        if (combos.size > 1)
            events += JudgeEvent(EventKinds.IR_LENGTH_AMBIGUOUS, "WARN",
                "IR 总长按候选长度有 ${combos.size} 种合法分解，全部保留，不靠 IDCODE 猜定",
                listOf(irCandidates.id), "CHAIN")
        // DR analysis: BYPASS devices contribute exactly 1 bit
        val hyps = combos.mapIndexed { ci, lens ->
            val devs = expected.mapIndexed { idx, d ->
                val bypass = d.bypassOnly
                if (bypass)
                    events += JudgeEvent(EventKinds.BYPASS_DEVICE, "INFO",
                        "器件 ${d.name} 处于 BYPASS，DR 贡献 1 位", listOf(board.id, d.name), "CHAIN")
                ChainDevice(idx, d.name, lens[idx], d.idcode, bypass, if (bypass) "BYPASS_DEVICE" else "OK")
            }
            val drBits = devs.sumOf { if (it.bypass) 1 else 32 }
            if (drSeg != null && drSeg.bitCount != drBits)
                events += JudgeEvent(EventKinds.CAPTURE_VIOLATION, "WARN",
                    "假设 ${ci + 1} 预测 DR 长 $drBits，实测 ${drSeg.bitCount}",
                    listOf(board.id), "CHAIN")
            ChainHypothesis("hyp-$ci", devs, emptyList(), if (combos.size > 1) "AMBIGUOUS" else "UNIQUE")
        }
        return hyps
    }

    // ---- stage 5: cell -> pin evidence from chosen BSDL mapping versions ----
    fun evidence(): List<CellPinEvidence> =
        mappings.flatMap { m -> m.cells.map { CellPinEvidence(m.device, it.cellIndex, it.port, it.pin, m.mappingVersion) } }

    fun run(): DerivationResult {
        val n = normalize(); stageHashes["NORMALIZE"] = inputHashes()["NORMALIZE"]!!
        val t = trace(n);      stageHashes["TRACE"] = inputHashes()["TRACE"]!!
        val s = segments(t);   stageHashes["SEGMENTS"] = inputHashes()["SEGMENTS"]!!
        val c = chain(s);      stageHashes["CHAIN"] = inputHashes()["CHAIN"]!!
        val e = evidence();    stageHashes["EVIDENCE"] = inputHashes()["EVIDENCE"]!!
        return DerivationResult(t, s, c, e)
    }
}

/** Compare two BSDL mappings; emit MAPPING_VERSION_CHANGED events for differing cells. */
fun compareMappings(a: BsdlMapping, b: BsdlMapping): List<JudgeEvent> {
    val byCellA = a.cells.associateBy { it.cellIndex }
    val byCellB = b.cells.associateBy { it.cellIndex }
    val ev = mutableListOf<JudgeEvent>()
    for ((idx, ca) in byCellA) {
        val cb = byCellB[idx]
        if (cb == null) ev += JudgeEvent(EventKinds.MAPPING_VERSION_CHANGED, "WARN",
            "单元 $idx 在 ${b.mappingVersion} 中缺失", listOf(a.id, b.id), "EVIDENCE")
        else if (ca.pin != cb.pin || ca.port != cb.port)
            ev += JudgeEvent(EventKinds.MAPPING_VERSION_CHANGED, "WARN",
                "单元 $idx 引脚映射变化：${a.mappingVersion}=${ca.port}/${ca.pin} -> ${b.mappingVersion}=${cb.port}/${cb.pin}",
                listOf(a.id, b.id, "cell:$idx"), "EVIDENCE")
    }
    return ev
}
