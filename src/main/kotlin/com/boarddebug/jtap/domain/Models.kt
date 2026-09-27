package com.boarddebug.jtap.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
enum class SourceKind {
    SAMPLE_EDGES,
    RESET_EVENT,
    IR_LENGTH_CANDIDATE,
    BSDL_PIN_MAP,
    BOARD_REVISION,
    DECISION
}

data class SourceRef(
    val id: String,
    val kind: SourceKind,
    val version: String,
    val path: String,
    val rawRepresentation: String
)

data class SampleEdge(
    val id: String,
    val sampleSetId: String,
    val ordinal: Int,
    val edgeLabel: String,
    val activeEdge: String,
    val tckBasis: String,
    val timeBasis: String,
    val tmsRaw: String,
    val tdiRaw: String,
    val tdoRaw: String,
    val label: String
)

data class ResetEventRecord(
    val id: String,
    val sampleSetId: String,
    val startOrdinal: Int,
    val endOrdinal: Int,
    val kind: String,
    val scope: String,
    val register: String?,
    val expectedRemainingBits: Int?,
    val rawRepresentation: String
)

data class IrLengthCandidateRecord(
    val id: String,
    val sampleSetId: String,
    val observedLengthBits: Int,
    val evidenceEdges: String,
    val rawRepresentation: String
)

data class BoundaryCellRecord(
    val cellNumber: Int,
 val cellType: String,
    val port: String,
    val function: String,
    val safeBit: String,
    val pin: String,
    val xMm: String?,
    val yMm: String?,
    val coordinateUnit: String,
    val rawLine: String
)

data class BsdlDocument(
    val id: String,
    val componentId: String,
    val bsdlVersion: String,
    val path: String,
    val instructionLengthBits: Int?,
    val idcodeRegister: String?,
    val boundaryLength: Int?,
    val opcodes: Map<String, String>,
    val cells: List<BoundaryCellRecord>,
    val rawRepresentation: String
)

data class BoardDevice(
    val componentId: String,
    val expectedIrLengthBits: Int?,
    val bsdlRef: String?,
    val sourceState: String
)

data class BoardRevisionRecord(
    val id: String,
    val boardId: String,
    val revision: String,
    val coordinateOrigin: String,
    val coordinateUnit: String,
    val tckActiveEdge: String,
    val chainOrder: List<String>,
    val devices: List<BoardDevice>,
    val rawRepresentation: String
)

data class CaseInput(
    val caseId: String,
    val sampleSetId: String,
    val samples: List<SampleEdge>,
    val resetEvents: List<ResetEventRecord>,
    val irSources: List<IrLengthCandidateRecord>,
    val bsdl: List<BsdlDocument>,
    val board: BoardRevisionRecord,
    val sources: List<SourceRef>,
    val decisions: List<DecisionInput> = emptyList()
)

data class DecisionInput(
    val id: String,
    val kind: String,
    val candidateId: String?,
    val parameter: String?,
    val value: String?,
    val basis: String,
    val parentEventId: String?
)

data class DependencyRef(
    val kind: String,
    val id: String,
    val version: String,
    val role: String
)

data class DerivedObject(
    val stableId: String,
    val type: String,
    val ordinal: Int,
    val state: String,
    val adoption: String,
    val payload: JsonObject,
    val dependencies: List<DependencyRef>,
    val ruleVersion: String,
    val sourceRefs: List<String>
)

data class LogicEvent(
    val stableId: String,
    val ruleId: String,
    val type: String,
    val severity: String,
    val state: String,
    val edgeId: String?,
    val objectId: String?,
    val message: String,
    val basis: JsonObject,
    val candidateIds: List<String>,
    val sourceRefs: List<String>,
    val decisionId: String? = null
)

data class RecomputeItem(
    val objectId: String,
    val state: String,
    val reason: String,
    val changedDependencies: List<String>
)

data class DerivationResult(
    val objects: List<DerivedObject>,
    val events: List<LogicEvent>,
    val recompute: List<RecomputeItem>,
    val adoptedCandidateIds: List<String>,
    val retainedCandidateIds: List<String>
)
