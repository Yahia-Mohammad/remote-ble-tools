package dev.warsha.remoteble.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The reply to a [Command]: success (optionally carrying a payload) or an error. */
@Serializable
sealed interface OpResult {
    @Serializable @SerialName("ok")
    data class Ok(val payload: ResultPayload? = null) : OpResult

    @Serializable @SerialName("err")
    data class Err(val error: AgentError) : OpResult
}

@Serializable
sealed interface ResultPayload {
    /** Compatibility-only decoder for pre-readiness clients; no agent advertises a slots query. */
    @Deprecated("Slots are delivered as the immediate slots event")
    @Serializable @SerialName("agent.slots")
    data class AgentSlots(val slots: StatusSlotsDto) : ResultPayload
    /** Read result. */
    @Serializable @SerialName("bytes")
    class Bytes(val value: ByteArray) : ResultPayload {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Bytes) return false
            return value.contentEquals(other.value)
        }

        override fun hashCode(): Int = value.contentHashCode()

        override fun toString(): String = "Bytes(${value.size} bytes)"
    }

    /** Discover result. */
    @Serializable @SerialName("services")
    data class Services(val services: List<ServiceNode>) : ResultPayload

    /** Connect / RequestMtu result. */
    @Serializable @SerialName("mtu")
    data class Mtu(val mtu: Int) : ResultPayload

    /** ReadRssi result — the connected link's RSSI in dBm (negative; capability `rssi`). */
    @Serializable @SerialName("rssi")
    data class Rssi(val rssi: Int) : ResultPayload

    /** Pair result — the resulting bond state. */
    @Serializable @SerialName("bond")
    data class Bond(val state: BleBondState) : ResultPayload

    /** AgentStatus result — caller-scoped snapshot (capability `agent.status`). */
    @Serializable @SerialName("status")
    data class Status(val status: AgentStatusDto) : ResultPayload
}

@Serializable
data class AgentStatusDto(
    val agentInfo: String? = null,
    val protocolVersion: Int = PROTOCOL_VERSION,
    val uptimeMs: Long,
    val settings: StatusSettingsDto,
    val slots: StatusSlotsDto,
    val connectedClients: Int,
    val leases: List<LeaseStatusDto> = emptyList(),
    val otherLeases: Int = 0,
    val operatorScope: Boolean = false,
)

@Serializable
data class StatusSettingsDto(
    val leaseGraceMs: Long,
    val transportGraceMs: Long,
    val exclusiveByDefault: Boolean,
    val scanConcurrency: String,
    val strictIdentifiers: Boolean,
    val writePolicyEnforced: Boolean = false,
)

@Serializable
data class StatusSlotsDto(val free: Int, val total: Int)

@Serializable
data class LeaseStatusDto(
    val handle: String,
    val name: String? = null,
    val holder: String? = null,
    val mine: Boolean = false,
    val connected: Boolean,
    val inGrace: Boolean,
    val remainingGraceMs: Long? = null,
)

@Serializable
data class ServiceNode(val uuid: String, val characteristics: List<CharNode>)

@Serializable
data class CharNode(
    val uuid: String,
    val properties: Int,
    val descriptors: List<String> = emptyList(),
)
