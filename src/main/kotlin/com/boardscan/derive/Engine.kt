package com.boardscan.derive

import com.boardscan.chain.ChainInference
import com.boardscan.model.*
import com.boardscan.tap.ScanExtractor
import com.boardscan.tap.SignalDecoder
import com.boardscan.tap.TapReplayer
import com.boardscan.bsdl.BoardRevision
import kotlinx.serialization.json.Json
import java.security.MessageDigest

private val json = Json { ignoreUnknownKeys = true }

object DerivationEngine {

    /** 输入指纹：只对直接来源的原始表示做哈希。改动一个直接来源只会脏化对应子图。 */
    fun fingerprint(inputs: RunInputs): String {
        val md = MessageDigest.getInstance("SHA-256")
        fun put(s: String) { md.update(s.toByteArray()); md.update(byteArrayOf(0)) }
        put(inputs.sample.sampleId)
        inputs.sample.rows.forEach { put("${it.edgeId}|${it.order}|${it.timeRaw}|${it.tckRaw}|${it.tmsRaw}|${it.tdiRaw}|${it.tdoRaw}") }
        put("pol:${inputs.polarity.polarityId}:${inputs.polarity.version}:" +
            "${inputs.polarity.activeEdge}:${inputs.polarity.tckThresholdV}:${inputs.polarity.signalThresholdV}")
        inputs.bsdls.forEach { put("bsdl:${it.bsdlId}:${it.version}:${it.rawText.hashCode()}") }
        inputs.boards.forEach { put("brd:${it.boardRevisionId}:${it.revision}:${json.encodeToString(BoardRevision.serializer(), it).hashCode()}") }
        inputs.declarations.forEach { put("decl:${it.position}:${it.name}:${it.bsdlId}:${it.bsdlVersion}:${it.instruction}") }
        if (inputs.measuredIdcodeCount != null) put("idcode-count:${inputs.measuredIdcodeCount}")
        return md.digest().joinToString("") { "%02x".format(it) }.take(20)
    }

    /**
     * @param changedInputs 本次相对父版本改动的直接来源（用于依赖图说明）
     */
    fun run(
        runId: String,
        parentRunId: String,
        branchLabel: String,
        author: String,
        createdAt: String,
        inputs: RunInputs,
        changedInputs: Set<String>,
        previousFingerprints: Map<String, String> = emptyMap(),
    ): DerivationResult {
        val (edges, decodeFindings) = SignalDecoder.decodeEdges(inputs.sample, inputs.polarity)
        val replayer = TapReplayer(runId, branchLabel)
        val replay = replayer.replay(edges)
        val scans = ScanExtractor.extract(runId, branchLabel, replay.steps, replay.resetEdges)

        val resets = replay.resetEdges.mapIndexed { index, edgeId ->
            val before = replay.steps.firstOrNull { it.edgeId == edgeId }
            val mid = before?.fromState?.name?.let {
                it == "SHIFT_DR" || it == "SHIFT_IR"
            } ?: false
            val aborted = scans.firstOrNull { it.abortedByResetId == edgeId }
            ResetEvent(
                resetId = "rst-$runId-$branchLabel-$index",
                sampleId = inputs.sample.sampleId,
                kind = if (index == 0 && !aborted?.note.isNullOrEmpty()) ResetKind.MID_SCAN_TLR
                else if (index == 0) ResetKind.POWERUP_ESTABLISHED
                else if (aborted != null) ResetKind.MID_SCAN_TLR else ResetKind.TLR_RE_ENTRY,
                enterEdgeId = edgeId,
                tmsRunEdges = replay.steps.takeWhile { it.edgeId != edgeId }.takeLast(5)
                    .map { it.edgeId },
                midScan = aborted != null,
                openScanSegmentId = aborted?.segmentId,
                seq = index.toLong(),
            )
        }

        val chain = ChainInference(runId, branchLabel)
            .infer(scans, declarationsFromInputs(inputs), inputs.measuredIdcodeCount)

        val allFindings = (decodeFindings.map { it.copy(runId = runId, branchLabel = branchLabel) } +
            replay.findings + chain.findings)
            .sortedWith(compareBy({ it.seq }, { it.findingId }))
            .mapIndexed { index, f -> f.copy(seq = index.toLong()) }

        val fingerprints = mapOf(
            "in:samples" to inputs.fpSamples(),
            "in:polarity" to inputs.fpPolarity(),
            "in:bsdl" to inputs.fpBsdl(),
            "in:board" to inputs.fpBoard(),
        )
        val graph = DependencyGraphBuilder.build(fingerprints, changedInputs)

        return DerivationResult(
            runId = runId,
            parentRunId = parentRunId,
            branchLabel = branchLabel,
            createdAt = createdAt,
            fingerprint = fingerprint(inputs),
            inputs = inputs,
            decodedEdges = edges,
            steps = replay.steps,
            scans = scans,
            resets = resets,
            chain = chain,
            graph = graph,
            findings = allFindings,
        )
    }

    private fun RunInputs.fpSamples() = "sha:" + fingerprint(this).take(12) + ":samples"
    private fun RunInputs.fpPolarity() =
        "sha:${polarity.polarityId}-${polarity.version}-${polarity.activeEdge}-${polarity.tckThresholdV}-${polarity.signalThresholdV}"
    private fun RunInputs.fpBsdl() = "sha:" + bsdls.joinToString("|") { "${it.bsdlId}@${it.version}:${it.rawText.hashCode()}" }
    private fun RunInputs.fpBoard() = "sha:" + boards.joinToString("|") {
        "${it.boardRevisionId}@${it.revision}:${json.encodeToString(BoardRevision.serializer(), it).hashCode()}"
    }
}
