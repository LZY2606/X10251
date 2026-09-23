package jtag.casepack

import jtag.bsdl.BsdlParser
import jtag.derive.Deriver
import jtag.derive.Nodes
import jtag.model.*
import jtag.store.*
import jtag.tap.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Parses the in-repo capture script into raw half-period edge samples (both phases kept). */
object CaptureScript {
    data class Generated(val bundle: EdgeBundle, val illegalHintSeqs: List<Int>)

    private fun expandBits(spec: String): List<Boolean> {
        val out = mutableListOf<Boolean>()
        for (tok in spec.split(",")) {
            when {
                tok.startsWith("bits:") -> tok.removePrefix("bits:").forEach { out += (it == '1') }
                tok.startsWith("idcode:") -> {
                    val v = tok.removePrefix("idcode:").toLong(16)
                    for (i in 0 until 32) out += ((v shr i) and 1L) == 1L
                }
                tok == "bypass" -> out += false
                tok.startsWith("zeros:") -> repeat(tok.removePrefix("zeros:").toInt()) { out += false }
                tok.startsWith("ones:") -> repeat(tok.removePrefix("ones:").toInt()) { out += true }
                else -> error("unknown bit spec '$tok'")
            }
        }
        return out
    }

    fun generate(script: String): Generated {
        val samples = mutableListOf<EdgeSample>()
        var seq = 0
        var clock = 0
        var state = TapState.TEST_LOGIC_RESET
        var shiftDrEntries = 0
        val illegalHintSeqs = mutableListOf<Int>()

        fun timeRaw(): String = when {
            clock == 3 -> "n/a"                       // unconvertible: stays raw with diagnostic
            clock % 2 == 0 -> "${10 * (clock + 1)} ns"
            else -> "0.0${(clock % 9) + 1} us"
        }

        fun clock(tms: Boolean, tdi: Boolean, tdo: Boolean, tdoFall: Boolean = tdo, trst: Boolean = false) {
            val t = timeRaw()
            samples += EdgeSample(seq++, ClockPhase.RISING, tms, tdi, tdo, tck = true, parseTime(t), trst)
            samples += EdgeSample(seq++, ClockPhase.FALLING, tms, tdi, tdoFall, tck = false, parseTime(t), trst)
            val next = nextState(state, tms)
            if (next == TapState.SHIFT_DR && state != TapState.SHIFT_DR) {
                shiftDrEntries++
                if (shiftDrEntries == 2) illegalHintSeqs += seq - 2 // rising edge seq entering 2nd SHIFT_DR
            }
            state = next
            clock++
        }

        for (rawLine in script.lines()) {
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val tok = line.split(Regex("\\s+"))
            when (tok[0]) {
                "reset" -> repeat(5) { clock(tms = true, tdi = false, tdo = false) }
                "idle" -> repeat(tok.getOrNull(1)?.toInt() ?: 1) { clock(tms = false, tdi = false, tdo = false) }
                "to_shift_ir" -> { clock(true, false, false); clock(true, false, false); clock(false, false, false); clock(false, false, false) }
                "to_shift_dr" -> { clock(true, false, false); clock(false, false, false); clock(false, false, false) }
                "shift", "shiftopen" -> {
                    val args = tok.drop(1).associate { it.substringBefore('=') to it.substringAfter('=') }
                    val tdi = expandBits(args["tdi"] ?: "zeros:1")
                    val tdo = expandBits(args["tdo"] ?: "zeros:1")
                    val tdoFall = args["tdofall"]?.let { expandBits(it) } ?: tdo
                    val n = maxOf(tdi.size, tdo.size)
                    for (i in 0 until n) {
                        // 'shift' exits on the last clock (TMS=1); 'shiftopen' stays in shift
                        val last = tok[0] == "shift" && i == n - 1
                        clock(tms = last, tdi = tdi.getOrElse(i) { false },
                            tdo = tdo.getOrElse(i) { false }, tdoFall = tdoFall.getOrElse(i) { false })
                    }
                }
                "update" -> { clock(true, false, false); clock(false, false, false) }
                "midreset" -> repeat(5) { clock(tms = true, tdi = false, tdo = false) }
                "trst" -> clock(tms = false, tdi = false, tdo = false, trst = true)
                else -> error("unknown op '${tok[0]}'")
            }
        }
        val hints = illegalHintSeqs.map { StateHint(it, "SHIFT_IR", "decoder-overlay-v3") }
        return Generated(EdgeBundle(samples, hints, "probe-main", Quantity("1.8 V", "V", 1.8)), illegalHintSeqs)
    }
}

/**
 * Builds the offline case pack "scan chain graph: JTAG TAP transitions, boundary-scan
 * cells and device mapping" as a versioned narrative. Fully offline, repo fixtures only.
 */
object CasePack {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun resource(name: String): String =
        requireNotNull(CasePack::class.java.getResource("/casepack/$name")) { "missing fixture $name" }.readText()

    inline fun <reified T> loadFixture(name: String): T = json.decodeFromString(resource(name))

    /** Returns version labels in build order. Idempotent: does nothing when versions exist. */
    fun load(store: Store): List<String> {
        if (store.versions().isNotEmpty()) return store.versions().map { it.label }
        val deriver = Deriver(store)
        val labels = mutableListOf<String>()

        val generated = CaptureScript.generate(resource("edges_script.txt"))
        val bundle = generated.bundle
        val resets = TrajectoryBuilder.detectResets(TrajectoryBuilder.activeEdges(bundle.samples, ClockPhase.RISING))
        val irCandidates = loadFixture<List<IrLengthCandidate>>("ir_candidates.json")
        val board = loadFixture<BoardRevision>("board_rev_a.json")
        val acmeV1 = BsdlParser.parse(resource("acme7420_v1.bsdl"), "ACME7420", "1.0")
        val acmeV2 = BsdlParser.parse(resource("acme7420_v2.bsdl"), "ACME7420", "2.0")
        val orion = BsdlParser.parse(resource("orion90.bsdl"), "ORION90", "1.0")

        fun recordJudgments(store: Store, b: VersionBuilder) {
            deriver.deriveInto(b)
            for (d in b.derived) {
                when (d.node) {
                    Nodes.IR_ANALYSIS -> deriver.decode<IrAnalysis>(d.payload).judgments
                    Nodes.CHAIN -> deriver.decode<ChainAnalysis>(d.payload).judgments
                    else -> emptyList()
                }.forEach { j ->
                    b.addEvent("JUDGMENT", """{"node":"${d.node}","text":${Json.encodeToString(j)}}""")
                }
            }
        }

        // v1: baseline import — all five source kinds stored separately
        val v1 = store.saveFrom(null, "baseline-import") { b ->
            b.addSource(SourceKind.EDGE_BUNDLE, json.encodeToString(EdgeBundle.serializer(), bundle))
            resets.forEach { b.addSource(SourceKind.RESET_EVENT, json.encodeToString(ResetEvent.serializer(), it), it.id) }
            irCandidates.forEach { b.addSource(SourceKind.IR_CANDIDATE, json.encodeToString(IrLengthCandidate.serializer(), it), it.id) }
            b.addSource(SourceKind.BSDL_MAPPING, json.encodeToString(BsdlMapping.serializer(), acmeV1), acmeV1.id)
            b.addSource(SourceKind.BSDL_MAPPING, json.encodeToString(BsdlMapping.serializer(), orion), orion.id)
            b.addSource(SourceKind.BOARD_REVISION, json.encodeToString(BoardRevision.serializer(), board), board.id)
            b.addSource(SourceKind.PARAM, """{"key":"polarity","value":"RISING"}""")
            b.addEvent("IMPORT", """{"story":"board debug team imports TMS/TCK/TDI/TDO samples and BSDL versions to rebuild TAP state, IR/DR registers and scan chain cells","edges":${bundle.samples.size},"resets":${resets.size},"irCandidates":${irCandidates.size}}""")
            recordJudgments(store, b)
        }
        labels += "baseline-import"

        // v2: parameter revision — sampling polarity RISING -> FALLING (local dependency fan-out only)
        val v2 = store.saveFrom(v1, "param-revision polarity=FALLING") { b ->
            b.addSource(SourceKind.PARAM, """{"key":"polarity","value":"FALLING"}""")
            b.addEvent("PARAM_REVISION", """{"key":"polarity","from":"RISING","to":"FALLING","scope":"re-derives tapTrajectory and downstream only"}""")
            recordJudgments(store, b)
        }
        labels += "param-revision"

        // v3: source data change with local impact — BSDL mapping version 1.0 -> 2.0 for ACME7420
        val v3 = store.saveFrom(v2, "bsdl-mapping ACME7420 1.0->2.0") { b ->
            b.addSource(SourceKind.BSDL_MAPPING, json.encodeToString(BsdlMapping.serializer(), acmeV2), acmeV2.id)
            b.addSource(SourceKind.BSDL_MAPPING, json.encodeToString(BsdlMapping.serializer(), orion), orion.id)
            b.addEvent("SOURCE_CHANGE", """{"kind":"BSDL_MAPPING","device":"ACME7420","from":"1.0","to":"2.0","scope":"cellPinEvidence only"}""")
            recordJudgments(store, b)
        }
        labels += "bsdl-mapping-v2"

        // v4a / v4b: two users adjudicate the IR length ambiguity from the same base -> conflict
        fun adjudicated(cands: List<IrLengthCandidate>, acceptIdx: Int): List<IrLengthCandidate> =
            cands.mapIndexed { i, c -> c.copy(status = if (i == acceptIdx) CandidateStatus.ACCEPTED else CandidateStatus.REJECTED) }

        val v4a = store.saveFrom(v3, "adjudicate IR=[4,5,4]") { b ->
            adjudicated(irCandidates, 0).forEach { b.addSource(SourceKind.IR_CANDIDATE, json.encodeToString(IrLengthCandidate.serializer(), it), it.id) }
            b.addEvent("ADJUDICATION", """{"rule":"ir-length-adjudication","accepted":"${irCandidates[0].id}","rejected":["${irCandidates[1].id}","${irCandidates[2].id}"],"rationale":"three populated sites confirmed by chain evidence; rejected candidates retained"}""")
            recordJudgments(store, b)
        }
        labels += "adjudicate-4-5-4"

        val v4b = try {
            store.saveFrom(v3, "adjudicate IR=[8,5]") { b ->
                adjudicated(irCandidates, 1).forEach { b.addSource(SourceKind.IR_CANDIDATE, json.encodeToString(IrLengthCandidate.serializer(), it), it.id) }
                b.addEvent("ADJUDICATION", """{"rule":"ir-length-adjudication","accepted":"${irCandidates[1].id}","rejected":["${irCandidates[0].id}","${irCandidates[2].id}"],"rationale":"second-source 8-bit IR part hypothesis; kept as unselected branch"}""")
                recordJudgments(store, b)
            }
            error("unreachable")
        } catch (c: Store.ConflictWithBranch) {
            c.branchVersion
        }
        labels += "adjudicate-8-5 (conflict branch)"
        val conflict = store.conflicts().first { it.b == v4b }
        store.resolveConflict(conflict.id, v4a, "chain evidence shows three devices; [4,5,4] kept, [8,5] branch retained")

        // v5: counterexample archive branch from baseline — exercises the judgment rules explicitly
        val v5b = VersionBuilder(v1, "counterexample-archive")
        v5b.addEvent("JUDGMENT", """{"rule":"illegal-transition-not-skipped","detail":"decoder hint SHIFT_IR at a SHIFT_DR edge is recorded as IllegalTransition event; FSM continuation and hint both retained"}""")
        v5b.addEvent("JUDGMENT", """{"rule":"mid-reset-not-skipped","detail":"5xTMS=1 during SHIFT_DR aborts the segment with MID_STREAM_RESET; never silently dropped"}""")
        v5b.addEvent("JUDGMENT", """{"rule":"ir-length-not-from-idcode","detail":"IDCODE observation constrains DR only; IR candidates [4,5,4],[8,5],[13] all survive on clock-count evidence"}""")
        v5b.addEvent("JUDGMENT", """{"rule":"missing-device-offsets-kept","detail":"U4 expected but absent; offset candidates for all insertion positions retained"}""")
        irCandidates.mapIndexed { i, c -> if (i == 2) c.copy(status = CandidateStatus.REJECTED) else c }
            .forEach { v5b.addSource(SourceKind.IR_CANDIDATE, json.encodeToString(IrLengthCandidate.serializer(), it), it.id) }
        v5b.addEvent("ADJUDICATION", """{"rule":"ir-length-adjudication","accepted":null,"rejected":["${irCandidates[2].id}"],"rationale":"single-device [13] contradicted by 3 observed chain fields; other candidates left PENDING — no unique answer forced"}""")
        recordJudgments(store, v5b)
        store.commit(v5b, checkBase = false, becomeCurrent = false)
        labels += "counterexample-archive"

        return labels
    }
}
