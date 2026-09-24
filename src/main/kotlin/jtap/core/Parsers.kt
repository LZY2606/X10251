package jtap.core

object JtapParsers {
    private val nsBasis = UnitBasis("ns", "capture-trigger", "0.1ns")
    private val milBasis = UnitBasis("mil", "board-origin", "1mil")

    fun parseSample(sourceId: String, activeEdge: String, text: String): Pair<List<SignalEdge>, List<Diagnostic>> {
        val edges = mutableListOf<SignalEdge>()
        val diagnostics = mutableListOf<Diagnostic>()
        var nextIndex = 0
        var time = 0.0
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("SAMPLE") }
            .forEach { line ->
                when {
                    line.startsWith("EDGE") -> {
                        val id = requireValue(line, "id") ?: "E$nextIndex"
                        val edge = value(line, "edge") ?: "rise"
                        val rawTck = value(line, "tck_raw") ?: if (edge == "rise") "0->1" else "1->0"
                        time += 10.0
                        edges += SignalEdge(
                            edgeId = id,
                            sampleIndex = nextIndex++,
                            tckRaw = rawTck,
                            tmsRaw = value(line, "tms") ?: "X",
                            tdiRaw = value(line, "tdi") ?: "X",
                            tdoRaw = value(line, "tdo") ?: "X",
                            tck = 1,
                            tms = parseBit(value(line, "tms")),
                            tdi = parseBit(value(line, "tdi")),
                            tdo = parseBit(value(line, "tdo")),
                            edge = edge,
                            timeNs = sourceValue("${"%.1f".format(time)} ns", nsBasis.unit),
                            diagnostics = bitDiagnostics(id, line)
                        )
                    }
                    line.startsWith("SHIFT") -> {
                        val macroId = requireValue(line, "id") ?: "SHIFT${edges.size}"
                        val register = requireValue(line, "register") ?: error("SHIFT requires register: $line")
                        val count = requireValue(line, "count")?.toInt() ?: error("SHIFT requires count: $line")
                        val tdiBits = requireValue(line, "tdi")?.let(::bitsOf) ?: List(count) { 0 }
                        val tdoBits = requireValue(line, "tdo")?.let(::bitsOf) ?: List(count) { 0 }
                        require(tdiBits.size == count && tdoBits.size == count) {
                            "SHIFT $macroId bit stream length must equal count=$count"
                        }
                        repeat(count) { bitPosition ->
                            val isExit = bitPosition == count - 1
                            val tms = if (isExit) 1 else 0
                            time += 10.0
                            val id = "E:$macroId:${bitPosition + 1}"
                            edges += SignalEdge(
                                edgeId = id,
                                sampleIndex = nextIndex++,
                                tckRaw = if (activeEdge == "fall") "1->0" else "0->1",
                                tmsRaw = tms.toString(),
                                tdiRaw = tdiBits[bitPosition].toString(),
                                tdoRaw = tdoBits[bitPosition].toString(),
                                tck = 1,
                                tms = tms,
                                tdi = tdiBits[bitPosition],
                                tdo = tdoBits[bitPosition],
                                edge = activeEdge,
                                timeNs = sourceValue("${"%.1f".format(time)} ns", nsBasis.unit),
                                diagnostics = listOf("expanded from source macro $macroId")
                            )
                        }
                    }
                    else -> diagnostics += Diagnostic(
                        "DIAG:$sourceId:${diagnostics.size}",
                        "warning",
                        "UNPARSED_SAMPLE_LINE",
                        "No parser for source representation",
                        sourceId,
                        null
                    )
                }
            }
        if (edges.none { it.edge == activeEdge }) {
            diagnostics += Diagnostic(
                "DIAG:$sourceId:polarity",
                "error",
                "POLARITY_NO_ACTIVE_EDGE",
                "No sampling edge matches active edge $activeEdge",
                sourceId,
                null
            )
        }
        return edges to diagnostics
    }

    fun parseResets(sourceId: String, text: String, edges: List<SignalEdge>): Pair<List<ResetEvent>, List<Diagnostic>> {
        val events = mutableListOf<ResetEvent>()
        val diagnostics = mutableListOf<Diagnostic>()
        text.lineSequence().map { it.trim() }.filter { it.startsWith("RESET") }.forEach { line ->
            val id = requireValue(line, "id") ?: "R${events.size}"
            val edgeRef = requireValue(line, "edge").orEmpty()
            val edgeId = when {
                edgeRef.startsWith("auto:") -> {
                    val parts = edgeRef.removePrefix("auto:").split(":")
                    val wanted = "E:${parts[0]}:${parts[1]}"
                    edges.firstOrNull { it.edgeId == wanted }?.edgeId
                }
                else -> edges.firstOrNull { it.edgeId == edgeRef }?.edgeId ?: edgeRef.ifBlank { null }
            }
            if (edgeId == null) {
                diagnostics += Diagnostic("DIAG:$id:edge", "error", "RESET_EDGE_NOT_FOUND", line, sourceId, null)
            }
            val at = edges.firstOrNull { it.edgeId == edgeId }?.timeNs
                ?: sourceValue(requireValue(line, "at") ?: "unknown ns", "ns")
            events += ResetEvent(
                eventId = id,
                edgeId = edgeId,
                kind = requireValue(line, "kind") ?: "hard",
                atTimeNs = at,
                sourceText = line,
                midScan = requireValue(line, "mid_scan")?.toBooleanStrict() ?: false,
                previousState = null
            )
        }
        return events to diagnostics
    }

    fun parseIrCandidates(sourceId: String, text: String): List<IrLengthCandidate> =
        text.lineSequence().map { it.trim() }.filter { it.startsWith("CANDIDATE") }.mapIndexed { index, line ->
            val lengths = mapValue(requireValue(line, "lengths").orEmpty())
            val offsets = mapValue(requireValue(line, "offsets").orEmpty())
            IrLengthCandidate(
                candidateId = requireValue(line, "id") ?: "IR$index",
                sourceId = sourceId,
                deviceOffsets = offsets,
                lengthByDevice = lengths,
                totalLength = lengths.values.sum(),
                status = requireValue(line, "status") ?: "imported",
                rationale = requireValue(line, "rationale").orEmpty(),
                evidenceIds = csv(requireValue(line, "evidence")),
                source = line
            )
        }.toList()

    fun parseBsdl(sourceId: String, text: String): Pair<BsdlFile, List<Diagnostic>> {
        val deviceId = Regex("""of\s+(\w+)\s*:""").find(text)?.groupValues?.get(1)
            ?: Regex("""entity\s+(\w+)""").find(text)?.groupValues?.get(1)
            ?: error("BSDL device id missing")
        val version = Regex("""JTAP_BSDL_VERSION\s+["']?([^"';]+)["']?""").find(text)?.groupValues?.get(1)?.trim()
            ?: "unversioned"
        val irLengthText = Regex("""INSTRUCTION_LENGTH[^i]*?is\s+([^;]+);""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val idcode = Regex("""IDCODE_REGISTER[^i]*?is\s+["']?([0-9xX]+)["']?""").find(text)?.groupValues?.get(1)
        val instructions = Regex("""(?i)([A-Z0-9_]+)\s*\(([0-9xX]+)\)""")
            .findAll(text.substringAfter("INSTRUCTION_OPCODE").substringBefore(';'))
            .map { BsdlInstruction(it.groupValues[1], it.groupValues[2], registerFor(it.groupValues[1])) }
            .toList()
        val diagnostics = mutableListOf<Diagnostic>()
        val cells = text.lineSequence().map { it.trim() }.filter { it.startsWith("CELL") }.mapIndexed { index, line ->
            val cell = requireValue(line, "cell")?.toInt() ?: index
            val coordinateText = Regex("""coordinate\s*=\s*\(([^)]+)\)""").find(line)?.groupValues?.get(1).orEmpty()
            val coordinate = coordinateValue(sourceId, "CELL:$deviceId:$cell", coordinateText, diagnostics)
            BsdlCell(
                cellNumber = cell,
                port = requireValue(line, "port").orEmpty(),
                function = requireValue(line, "function") ?: "observe_only",
                safe = requireValue(line, "safe") ?: "X",
                coordinate = coordinate,
                pin = sourceValue(requireValue(line, "pin") ?: "UNKNOWN", null),
                sourceText = line
            )
        }.toList()
        return BsdlFile(
            bsdlId = sourceId,
            deviceId = deviceId,
            version = version,
            irLength = sourceValue(irLengthText, "bit"),
            idcode = idcode,
            instructions = instructions,
            cells = cells,
            sourceText = text
        ) to diagnostics
    }

    fun parseBoard(sourceId: String, text: String): Pair<BoardRevision, List<Diagnostic>> {
        val revisionId = Regex("""REVISION\s+id\s*=\s*(\S+)""").find(text)?.groupValues?.get(1) ?: "BR"
        val name = Regex("""name\s*=\s*["']?([^"'\n]+?)["']?(?:\s+#|$)""").find(text)?.groupValues?.get(1)?.trim() ?: revisionId
        val diagnostics = mutableListOf<Diagnostic>()
        val devices = text.lineSequence().map { it.trim() }.filter { it.startsWith("DEVICE") }.map { line ->
            val id = requireValue(line, "id")!!
            val coordinateText = Regex("""coordinate\s*=\s*\(([^)]+)\)""").find(line)?.groupValues?.get(1).orEmpty()
            BoardDevice(
                deviceId = id,
                position = requireValue(line, "position")!!.toInt(),
                expected = requireValue(line, "expected")?.toBooleanStrict() ?: true,
                presentObserved = requireValue(line, "present_observed")?.toBooleanStrict() ?: true,
                bypass = requireValue(line, "bypass")?.toBooleanStrict() ?: false,
                coordinate = coordinateValue(sourceId, "DEVICE:$id", coordinateText, diagnostics),
                sourceText = line
            )
        }.toList()
        return BoardRevision(revisionId, name, devices, text) to diagnostics
    }

    private fun registerFor(name: String): String = when (name.uppercase()) {
        "BYPASS" -> "BYPASS1"
        "IDCODE" -> "DEVICE_ID"
        "SAMPLE", "EXTEST", "INTEST" -> "BOUNDARY"
        else -> "PRIVATE"
    }

    private fun parseBit(value: String?): Int? = when (value?.uppercase()) {
        "0", "1" -> value.toInt()
        else -> null
    }

    private fun bitDiagnostics(id: String, line: String): List<String> =
        listOf("tms", "tdi", "tdo").mapNotNull { signal ->
            val raw = value(line, signal)
            if (raw != null && raw != "0" && raw != "1") "$id $signal=$raw is not a binary sample" else null
        }

    private fun value(line: String, key: String): String? =
        Regex("""(?:^|\s)$key\s*=\s*("([^"]*)"|'([^']*)'|[^\s]+)""").find(line)?.groupValues?.let {
            (it.getOrNull(2) ?: it.getOrNull(3) ?: it.getOrNull(1))?.trim()
        }

    private fun requireValue(line: String, key: String): String? = value(line, key)

    private fun bitsOf(text: String): List<Int> = text.map { it.digitToInt() }

    private fun csv(value: String?): List<String> = value?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    private fun mapValue(text: String): Map<String, Int> =
        text.split(',').mapNotNull { pair ->
            val parts = pair.split(':')
            if (parts.size == 2) parts[0].trim() to parts[1].trim().toInt() else null
        }.toMap()

    private fun sourceValue(raw: String, unit: String?): SourceValue {
        val normalized = when {
            unit == "ns" && Regex("""^-?\d+(\.\d+)?\s*ns$""").matches(raw) -> raw
            unit == "bit" && Regex("""^\d+\s*bits?$""", RegexOption.IGNORE_CASE).matches(raw) -> raw
            unit == null -> raw
            else -> null
        }
        return SourceValue(raw, normalized, unit)
    }

    private fun coordinateValue(sourceId: String, objectId: String, text: String, diagnostics: MutableList<Diagnostic>): SourceValue {
        val parts = text.split(',').map { it.trim() }
        val convertible = parts.size == 3 && parts[0].toDoubleOrNull() != null && parts[1].toDoubleOrNull() != null
        if (!convertible) {
            diagnostics += Diagnostic(
                "DIAG:$objectId:coordinate",
                "warning",
                "UNCONVERTIBLE_COORDINATE",
                "Coordinate retained in original location; expected (x,y,unit)",
                sourceId,
                null
            )
        }
        return SourceValue("($text)", parts.takeIf { convertible }?.joinToString(",")?.let { "($it)" }, parts.getOrNull(2))
    }
}
