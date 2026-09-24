package jtap.core

enum class TapState(val stableId: String) {
    TEST_LOGIC_RESET("TLR"),
    RUN_TEST_IDLE("RTI"),
    SELECT_DR_SCAN("SDR"),
    CAPTURE_DR("CDR"),
    SHIFT_DR("SHDR"),
    EXIT1_DR("E1DR"),
    PAUSE_DR("PDR"),
    EXIT2_DR("E2DR"),
    UPDATE_DR("UDR"),
    SELECT_IR_SCAN("SIR"),
    CAPTURE_IR("CIR"),
    SHIFT_IR("SHIR"),
    EXIT1_IR("E1IR"),
    PAUSE_IR("PIR"),
    EXIT2_IR("E2IR"),
    UPDATE_IR("UIR"),
    INVALID("INVALID")
}

data class UnitBasis(
    val unit: String,
    val origin: String,
    val precision: String
)

data class SourceValue(
    val raw: String,
    val normalized: String?,
    val basis: String?,
    val diagnostic: String? = null
)

data class SignalEdge(
    val edgeId: String,
    val sampleIndex: Int,
    val tckRaw: String,
    val tmsRaw: String,
    val tdiRaw: String,
    val tdoRaw: String,
    val tck: Int?,
    val tms: Int?,
    val tdi: Int?,
    val tdo: Int?,
    val edge: String,
    val timeNs: SourceValue,
    val diagnostics: List<String> = emptyList()
)

data class ResetEvent(
    val eventId: String,
    val edgeId: String?,
    val kind: String,
    val atTimeNs: SourceValue,
    val sourceText: String,
    val midScan: Boolean,
    val previousState: TapState?
)

data class TapTransition(
    val edgeId: String,
    val index: Int,
    val from: TapState?,
    val to: TapState?,
    val tms: Int?,
    val legal: Boolean,
    val resetEventId: String?,
    val reason: String
)

data class ShiftBit(
    val bitId: String,
    val edgeId: String,
    val position: Int,
    val register: String,
    val tdi: Int,
    val tdo: Int
)

data class ShiftStream(
    val streamId: String,
    val register: String,
    val segmentIndex: Int,
    val startEdgeId: String,
    val endEdgeId: String,
    val bits: List<ShiftBit>,
    val rawTdi: String,
    val rawTdo: String,
    val interruptedByReset: Boolean
)

data class IrLengthCandidate(
    val candidateId: String,
    val sourceId: String,
    val deviceOffsets: Map<String, Int>,
    val lengthByDevice: Map<String, Int>,
    val totalLength: Int,
    val status: String,
    val rationale: String,
    val evidenceIds: List<String>,
    val source: String
)

data class BsdlInstruction(
    val name: String,
    val opcode: String,
    val register: String
)

data class BsdlCell(
    val cellNumber: Int,
    val port: String,
    val function: String,
    val safe: String,
    val coordinate: SourceValue,
    val pin: SourceValue,
    val sourceText: String
)

data class BsdlFile(
    val bsdlId: String,
    val deviceId: String,
    val version: String,
    val irLength: SourceValue,
    val idcode: String?,
    val instructions: List<BsdlInstruction>,
    val cells: List<BsdlCell>,
    val sourceText: String
)

data class BoardDevice(
    val deviceId: String,
    val position: Int,
    val expected: Boolean,
    val presentObserved: Boolean,
    val bypass: Boolean,
    val coordinate: SourceValue,
    val sourceText: String
)

data class BoardRevision(
    val revisionId: String,
    val name: String,
    val devices: List<BoardDevice>,
    val sourceText: String
)

data class DeviceChainNode(
    val nodeId: String,
    val deviceId: String?,
    val position: Int,
    val irLength: Int?,
    val present: Boolean,
    val bypass: Boolean,
    val status: String,
    val offsets: Map<String, Int>,
    val evidenceIds: List<String>,
    val reason: String
)

data class CellPinEvidence(
    val evidenceId: String,
    val deviceId: String,
    val cellNumber: Int,
    val bsdlId: String,
    val bsdlVersion: String,
    val boardRevisionId: String,
    val port: String,
    val pin: SourceValue,
    val coordinate: SourceValue,
    val status: String,
    val reason: String
)

data class Diagnostic(
    val diagnosticId: String,
    val severity: String,
    val code: String,
    val message: String,
    val sourceId: String?,
    val edgeId: String?
)

data class TapReplay(
    val polarity: SourceValue,
    val edges: List<SignalEdge>,
    val resetEvents: List<ResetEvent>,
    val transitions: List<TapTransition>,
    val streams: List<ShiftStream>,
    val diagnostics: List<Diagnostic>
)

data class CaseInputs(
    val sampleSourceId: String,
    val resetSourceId: String,
    val irSourceId: String,
    val bsdlSourceIds: List<String>,
    val boardSourceId: String,
    val polarity: SourceValue,
    val rawSampleText: String,
    val rawResetText: String,
    val rawIrText: String,
    val rawBsdlText: Map<String, String>,
    val rawBoardText: String,
    val edges: List<SignalEdge>,
    val importedResets: List<ResetEvent>,
    val importedIrCandidates: List<IrLengthCandidate>,
    val bsdlFiles: List<BsdlFile>,
    val boardRevisions: List<BoardRevision>,
    val diagnostics: List<Diagnostic>
)

data class DerivedResult(
    val replay: TapReplay,
    val irCandidates: List<IrLengthCandidate>,
    val deviceChain: List<DeviceChainNode>,
    val cellEvidence: List<CellPinEvidence>,
    val diagnostics: List<Diagnostic>
)

data class DependencyNode(
    val nodeId: String,
    val kind: String,
    val label: String,
    val inputs: Set<String>,
    val status: String,
    val reason: String
)

data class DependencyGraph(
    val nodes: List<DependencyNode>,
    val edges: List<Pair<String, String>>
) {
    fun affected(changedInputs: Set<String>): Set<String> {
        val reverse = edges.groupBy({ it.second }, { it.first })
        val result = LinkedHashSet(changedInputs)
        val queue = ArrayDeque(changedInputs)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            reverse[current].orEmpty().forEach { dependent ->
                if (result.add(dependent)) queue.add(dependent)
            }
        }
        return result
    }
}

data class EvidenceEvent(
    val eventId: String,
    val kind: String,
    val status: String,
    val message: String,
    val sourceIds: List<String>,
    val evidenceIds: List<String>,
    val objectIds: List<String>,
    val machineRule: String,
    val createdAtEventOrdinal: Int
)
