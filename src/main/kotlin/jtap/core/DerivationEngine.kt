package jtap.core

data class RevisionSelection(
    val boardRevisionId: String,
    val bsdlVersionByDevice: Map<String, String>,
    val chosenCandidateId: String?
)

object DerivationEngine {
    fun derive(inputs: CaseInputs, selection: RevisionSelection): DerivedResult {
        val replay = TapEngine.replay(inputs)
        val board = inputs.boardRevisions.firstOrNull { it.revisionId == selection.boardRevisionId }
            ?: error("board revision ${selection.boardRevisionId} not found")
        val observedIrLength = replay.streams.firstOrNull { it.register == "IR" }?.bits?.size ?: 0
        val bypassStreamLengths = replay.streams.filter { it.register == "DR" }.map { it.bits.size }.toSet()
        val candidates = inputs.importedIrCandidates.map { candidate ->
            val exact = candidate.totalLength == observedIrLength
            val matchesBoard = candidate.deviceOffsets.keys.all { id ->
                board.devices.any { it.deviceId == id && it.presentObserved }
            } && candidate.deviceOffsets.size == board.devices.count { it.presentObserved }
            val status = when {
                exact && matchesBoard -> "LEGAL_CANDIDATE_RETAINED"
                !exact -> "REJECTED_BY_IR_SCAN_LENGTH"
                else -> "LEGAL_CANDIDATE_RETAINED"
            }
            val rationale = listOf(
                candidate.rationale,
                "observed IR shift length=${observedIrLength}bit",
                "IDCODE is corroboration only and cannot determine IR length",
                if (status == "REJECTED_BY_IR_SCAN_LENGTH") "length mismatch" else "length matches"
            ).filter { it.isNotBlank() }.joinToString("; ")
            candidate.copy(status = status, rationale = rationale)
        }

        val chosen = candidates.firstOrNull { it.candidateId == selection.chosenCandidateId && it.status != "REJECTED_BY_IR_SCAN_LENGTH" }
        val activeOffsets = chosen?.deviceOffsets ?: emptyMap()
        val activeLengths = chosen?.lengthByDevice ?: emptyMap()
        val chain = board.devices.sortedBy { it.position }.map { device ->
            val offset = activeOffsets[device.deviceId]
            val possibleOffsets = candidates
                .filter { it.status != "REJECTED_BY_IR_SCAN_LENGTH" && it.deviceOffsets.containsKey(device.deviceId) }
                .associate { it.candidateId to (it.deviceOffsets[device.deviceId] ?: 0) }
            val status = when {
                !device.expected && device.presentObserved -> "UNEXPECTED_DEVICE"
                device.expected && !device.presentObserved -> "MISSING_DEVICE_OFFSET_CANDIDATES_RETAINED"
                device.bypass -> "BYPASS_DEVICE"
                else -> "PRESENT"
            }
            DeviceChainNode(
                nodeId = "NODE:${device.position}:${device.deviceId}",
                deviceId = device.deviceId,
                position = device.position,
                irLength = activeLengths[device.deviceId] ?: inputs.bsdlFiles.firstOrNull { it.deviceId == device.deviceId }?.irLength?.raw?.filter(Char::isDigit)?.toIntOrNull(),
                present = device.presentObserved,
                bypass = device.bypass,
                status = status,
                offsets = possibleOffsets.ifEmpty { mapOf("missing" to (offset ?: 0)) },
                evidenceIds = listOf(device.sourceText.hashCode().toUInt().toString(16), "IR_LENGTH=$observedIrLength", "DR_LENGTHS=${bypassStreamLengths.sorted()}"),
                reason = "Missing board device is not collapsed; all legal offsets retained"
            )
        }

        val cellEvidence = board.devices.filter { it.presentObserved }.flatMap { device ->
            val version = selection.bsdlVersionByDevice[device.deviceId]
            val bsdl = inputs.bsdlFiles.filter { it.deviceId == device.deviceId }
                .sortedBy { it.version != version }
                .firstOrNull()
            if (bsdl == null) emptyList() else bsdl.cells.map { cell ->
                CellPinEvidence(
                    evidenceId = "EVIDENCE:${device.deviceId}:${cell.cellNumber}:${bsdl.version}",
                    deviceId = device.deviceId,
                    cellNumber = cell.cellNumber,
                    bsdlId = bsdl.bsdlId,
                    bsdlVersion = bsdl.version,
                    boardRevisionId = board.revisionId,
                    port = cell.port,
                    pin = cell.pin,
                    coordinate = cell.coordinate,
                    status = if (cell.pin.normalized == "UNKNOWN" || cell.coordinate.normalized == null) "SOURCE_VALUE_UNCONVERTIBLE" else "MAPPED",
                    reason = "Cell and pin trace to BSDL ${bsdl.version} and board ${board.revisionId}"
                )
            }
        }

        val derivedDiagnostics = mutableListOf<Diagnostic>()
        replay.diagnostics.filter { it.code in setOf("MID_SCAN_RESET", "ILLEGAL_TAP_TRANSITION") }.forEach {
            derivedDiagnostics += it
        }
        if (bypassStreamLengths.any { it != board.devices.count { device -> device.presentObserved && device.bypass } && it == board.devices.count { device -> device.presentObserved } }) {
            derivedDiagnostics += Diagnostic(
                "DIAG:BYPASS:${bypassStreamLengths.sorted()}",
                "info",
                "BYPASS_DEVICE_OBSERVED",
                "BYPASS DR scan exposes one-bit per bypass-capable device in observed chain",
                null,
                replay.streams.firstOrNull { it.register == "DR" }?.startEdgeId
            )
        }
        if (candidates.count { it.status == "LEGAL_CANDIDATE_RETAINED" } > 1) {
            derivedDiagnostics += Diagnostic(
                "DIAG:IR:MULTIPLE",
                "warning",
                "IR_LENGTH_MULTIPLE_SOLUTIONS",
                "Multiple IR length partitions remain legal; do not collapse from IDCODE alone",
                null,
                replay.streams.firstOrNull { it.register == "IR" }?.startEdgeId
            )
        }
        if (replay.diagnostics.any { it.code == "POLARITY_NO_ACTIVE_EDGE" }) {
            derivedDiagnostics += replay.diagnostics.first { it.code == "POLARITY_NO_ACTIVE_EDGE" }
        }
        return DerivedResult(replay, candidates, chain, cellEvidence, replay.diagnostics + derivedDiagnostics)
    }

    fun graph(selection: RevisionSelection): DependencyGraph {
        val nodes = listOf(
            DependencyNode("src:sample", "source", "TMS/TCK/TDI/TDO sample edges", setOf("sample"), "input", "Original capture retained"),
            DependencyNode("src:reset", "source", "Reset events", setOf("reset"), "input", "Reset source stored separately"),
            DependencyNode("src:ir", "source", "IR length candidates", setOf("ir"), "input", "Imported candidates stored separately"),
            DependencyNode("src:bsdl", "source", "BSDL pin maps", setOf("bsdl"), "input", "BSDL versions stored separately"),
            DependencyNode("src:board", "source", "Board revision", setOf("board"), "input", "Board revision stored separately"),
            DependencyNode("derived:tap", "derived", "TAP trajectory and bit streams", setOf("sample", "reset", "polarity"), "derived", "Replay depends on sample, reset and polarity"),
            DependencyNode("derived:ir", "derived", "IR candidate validation", setOf("ir", "sample", "board"), "derived", "Uses observed IR length but not IDCODE alone"),
            DependencyNode("derived:chain", "derived", "Device chain and offset candidates", setOf("derived:ir", "board", "sample"), "derived", "Missing devices retain offsets"),
            DependencyNode("derived:cell", "derived", "Cell to pin evidence", setOf("board", "bsdl"), "derived", "BSDL-only change is locally scoped")
        )
        val edges = listOf(
            "src:sample" to "derived:tap",
            "src:reset" to "derived:tap",
            "src:sample" to "derived:ir",
            "src:ir" to "derived:ir",
            "src:board" to "derived:ir",
            "derived:tap" to "derived:chain",
            "derived:ir" to "derived:chain",
            "src:board" to "derived:chain",
            "src:board" to "derived:cell",
            "src:bsdl" to "derived:cell"
        )
        return DependencyGraph(nodes, edges)
    }

    fun propagationReport(selection: RevisionSelection, changedInputs: Set<String>): List<DependencyNode> {
        val graph = graph(selection)
        val affected = graph.affected(changedInputs)
        return graph.nodes.map { node ->
            when {
                node.id in changedInputs -> node.copy(status = "CHANGED_SOURCE", reason = "Direct source changed")
                node.id in affected -> node.copy(status = "RECOMPUTED", reason = "Reachable through dependency edge")
                else -> node.copy(status = "STILL_VALID", reason = "No changed input reaches this result")
            }
        }
    }
}
