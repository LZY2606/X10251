package jtag.chain

import kotlinx.serialization.Serializable

@Serializable
data class BoundaryCell(
    val index: Int,
    val cellType: String,     // BC_1, BC_2, BC_4 ...
    val function: String,     // input | output2 | output3 | bidir | control | observe_only
    val port: String? = null,
    val safe: String = "X"
)

@Serializable
data class PinMapping(
    val port: String,
    val pin: String,          // raw pin/ball label, e.g. "B4"
    val basis: String = "bga-ball"
)

@Serializable
data class BsdlPackage(
    val id: String,
    val entity: String,
    val version: String,
    val irLength: Int,
    val instructions: Map<String, String>,   // name -> opcode bits
    val idcode: String? = null,
    val boundaryCells: List<BoundaryCell> = emptyList(),
    val pinMap: List<PinMapping> = emptyList()
)

@Serializable
data class ChainDevice(
    val id: String,
    val name: String,
    val position: Int,
    val bsdlId: String? = null,
    val bypass: Boolean = false,
    val present: Boolean = true
)

@Serializable
data class BoardRevision(
    val id: String,
    val label: String,
    val devices: List<ChainDevice>
)
