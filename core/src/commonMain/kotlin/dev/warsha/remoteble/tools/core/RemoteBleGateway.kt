package dev.warsha.remoteble.tools.core

import dev.warsha.remoteble.client.AgentSession
import dev.warsha.remoteble.client.DefaultAgentSession
import dev.warsha.remoteble.client.ReconnectPolicy
import dev.warsha.remoteble.client.RemoteGattClient
import dev.warsha.remoteble.client.RemoteScanSource
import dev.warsha.remoteble.client.RemoteTimeouts
import dev.warsha.remoteble.client.RetryPolicies
import dev.warsha.remoteble.client.SessionReadiness
import dev.warsha.remoteble.client.TransportState
import dev.warsha.remoteble.client.WebSocketAgentTransport
import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.AgentStatusDto
import dev.warsha.remoteble.protocol.Capabilities
import dev.warsha.remoteble.protocol.CharRef
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.IdentifierFormat
import dev.warsha.remoteble.protocol.Op
import dev.warsha.remoteble.protocol.AgentEvent
import dev.warsha.remoteble.protocol.AgentError
import dev.warsha.remoteble.protocol.AgentException
import dev.warsha.remoteble.protocol.BleConnState
import dev.warsha.remoteble.protocol.ErrorKind
import dev.warsha.remoteble.protocol.ScanFilter
import dev.warsha.remoteble.protocol.isIdempotent
import dev.warsha.remoteble.protocol.ResultPayload
import dev.warsha.remoteble.protocol.StatusSlotsDto
import dev.warsha.remoteble.protocol.orThrow
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets

class RemoteBleGateway internal constructor(
    val session: AgentSession,
    private val scope: CoroutineScope,
    private val http: io.ktor.client.HttpClient,
    private val timeouts: RemoteTimeouts,
    /** The concrete stable id sent on every reconnect and recorded in audit metadata. */
    override val clientId: String,
) : AutoCloseable, WriteGateway {
    /** A protocol stream whose stop operation has a stable id and can be acknowledged. */
    class ManagedStream<T> internal constructor(
        val streamId: Long,
        val events: Flow<T>,
        private val stopOperation: suspend () -> Unit,
    ) {
        suspend fun stopAndAwait() = stopOperation()
    }
    override suspend fun capabilities(): Set<String> =
        awaitNegotiated("the agent handshake", session.capabilities)

    /**
     * Awaits a value the agent only supplies over a live session, without ever waiting forever.
     *
     * Both [capabilities] and [slots] are populated from agent traffic and reset to `null` on every
     * reconnect, so a bare `filterNotNull().first()` on an unreachable — or simply silent — agent
     * suspends for the life of the process. A one-shot command must instead fail with the ordinary
     * exit contract, so this bounds the wait by the operation deadline and short-circuits the two
     * transport outcomes that mean the value is never coming.
     */
    private suspend fun <T : Any> awaitNegotiated(what: String, source: StateFlow<T?>): T {
        val (value, readiness, state) = try {
            withTimeout(timeouts.op) {
                combine(source, session.readiness, session.transportState) { value, readiness, state -> Triple(value, readiness, state) }
                    .first { (value, readiness, state) ->
                        value != null || readiness == SessionReadiness.INCOMPATIBLE_PROTOCOL || state.isTerminal()
                    }
            }
        } catch (error: TimeoutCancellationException) {
            throw CliFailure(ExitCode.RETRYABLE, "timed out after ${timeouts.op} waiting for $what", error)
        }
        value?.let { return it }
        throw when {
            readiness == SessionReadiness.INCOMPATIBLE_PROTOCOL || state == TransportState.INCOMPATIBLE_PROTOCOL ->
                CliFailure(ExitCode.UNSUPPORTED, "the agent and this client share no supported protocol version")
            else -> CliFailure(ExitCode.RETRYABLE, "the agent connection failed before $what completed")
        }
    }

    private fun TransportState.isTerminal(): Boolean =
        this == TransportState.GAVE_UP || this == TransportState.INCOMPATIBLE_PROTOCOL

    /**
     * Waits for a usable session before the first op is dispatched. Every entry point needs this:
     * the transport connects asynchronously, so an op issued immediately after [open] is sent while
     * the state is still CONNECTING and fails TRANSPORT_LOST without the link ever having been
     * given a chance. Short-circuits the transport outcomes that mean a usable session is never
     * coming, so an unreachable agent is still reported promptly rather than at the deadline.
     */
    suspend fun awaitReady() {
        val usable = setOf(SessionReadiness.READY, SessionReadiness.DEGRADED)
        val (readiness, state) = try {
            withTimeout(timeouts.op) {
                combine(session.readiness, session.transportState) { readiness, state -> readiness to state }
                    .first { (readiness, state) ->
                        readiness in usable || readiness == SessionReadiness.INCOMPATIBLE_PROTOCOL || state.isTerminal()
                    }
            }
        } catch (error: TimeoutCancellationException) {
            throw CliFailure(ExitCode.RETRYABLE, "timed out after ${timeouts.op} waiting for the agent session", error)
        }
        if (readiness in usable) return
        throw when {
            readiness == SessionReadiness.INCOMPATIBLE_PROTOCOL || state == TransportState.INCOMPATIBLE_PROTOCOL ->
                CliFailure(ExitCode.UNSUPPORTED, "the agent and this client share no supported protocol version")
            else -> CliFailure(ExitCode.RETRYABLE, "the agent connection failed before the session was ready")
        }
    }

    override suspend fun status(): AgentStatusDto {
        awaitReady()
        return (session.request(Op.AgentStatus, timeouts.op).orThrow() as? ResultPayload.Status)
            ?.status ?: error("agent.status returned an unexpected payload")
    }

    suspend fun slots(): StatusSlotsDto {
        val event = awaitNegotiated("an agent slot snapshot", session.slotState)
        return StatusSlotsDto(free = event.free, total = event.total)
    }

    /** A scan deadline is normal completion: retain observations already received. */
    suspend fun scan(filters: List<ScanFilter>, duration: Duration, maximumEvents: Int): List<dev.warsha.remoteble.protocol.AdvertisementDto> {
        val observations = mutableListOf<dev.warsha.remoteble.protocol.AdvertisementDto>()
        awaitReady()
        withTimeoutOrNull(duration) {
            RemoteScanSource(session).advertisements(filters).take(maximumEvents).collect { observations += it }
        }
        return observations
    }

    fun managedScan(
        filters: List<ScanFilter>,
        streamId: Long = session.nextStreamId(),
        onStarted: () -> Unit = {},
        onStartFailure: (Throwable) -> Unit = {},
    ): ManagedStream<dev.warsha.remoteble.protocol.AdvertisementDto> {
        val scanId = streamId
        val events = channelFlow {
            val pump = session.events()
                .onSubscription {
                    try {
                        awaitReady()
                        session.request(Op.ScanStart(scanId, filters), timeouts.op).orThrow()
                        onStarted()
                    } catch (error: Throwable) {
                        onStartFailure(error)
                        throw error
                    }
                }
                .onEach { event ->
                    when (event) {
                        is AgentEvent.ScanResult -> if (event.scanId == scanId) send(event.advertisement)
                        is AgentEvent.ScanResultBatch -> if (event.scanId == scanId) event.advertisements.forEach { send(it) }
                        else -> Unit
                    }
                }
                .launchIn(this)
            awaitClose { pump.cancel() }
        }
        return ManagedStream(scanId, events) { session.request(Op.ScanStop(scanId), timeouts.op).orThrow() }
    }

    private fun gatt(handle: String): RemoteGattClient {
        return RemoteGattClient(DeviceHandle(handle), session, timeouts)
    }

    override suspend fun connect(handle: String) {
        awaitReady()
        gatt(handle).connect()
    }
    suspend fun disconnect(handle: String) {
        awaitReady()
        gatt(handle).disconnect()
    }
    suspend fun inspect(handle: String) = connectedGatt(handle).discover()
    suspend fun read(handle: String, service: String, characteristic: String): ByteArray =
        connectedGatt(handle).read(CharRef(service, characteristic))
    override suspend fun write(
        handle: String,
        service: String,
        characteristic: String,
        value: ByteArray,
        withResponse: Boolean,
        onSubmitted: () -> Unit,
    ) {
        awaitReady()
        gatt(handle).write(CharRef(service, characteristic), value, withResponse, onSubmitted)
    }
    suspend fun readRssi(handle: String): Int = connectedGatt(handle).readRssi()
    suspend fun readDescriptor(handle: String, service: String, characteristic: String, descriptor: String): ByteArray =
        connectedGatt(handle).readDescriptor(dev.warsha.remoteble.protocol.DescRef(service, characteristic, descriptor))
    suspend fun managedObserve(
        handle: String,
        service: String,
        characteristic: String,
        streamId: Long = session.nextStreamId(),
        onStarted: () -> Unit = {},
        onStartFailure: (Throwable) -> Unit = {},
    ): ManagedStream<ByteArray> {
        connectedGatt(handle)
        val subId = streamId
        val char = CharRef(service, characteristic)
        val events = channelFlow {
            val pump = session.events()
                .onSubscription {
                    try {
                        awaitReady()
                        session.request(Op.ObserveStart(subId, DeviceHandle(handle), char), timeouts.op).orThrow()
                        onStarted()
                    } catch (error: Throwable) {
                        onStartFailure(error)
                        throw error
                    }
                }
                .onEach { event ->
                    when (event) {
                        is AgentEvent.Notification -> if (event.subId == subId) send(event.value)
                        is AgentEvent.ConnectionState -> if (event.device.value == handle && event.state == BleConnState.DISCONNECTED) {
                            val reason = event.reason
                            throw AgentException(
                                AgentError(
                                    kind = reason?.kind ?: ErrorKind.DISCONNECTED,
                                    gattStatus = reason?.gattStatus,
                                    message = "peripheral '$handle' disconnected while notifications were active" +
                                        (reason?.message?.let { ": $it" } ?: ""),
                                    holder = reason?.holder,
                                ),
                            )
                        }
                        else -> Unit
                    }
                }
                .launchIn(this)
            awaitClose { pump.cancel() }
        }
        return ManagedStream(subId, events) { session.request(Op.ObserveStop(subId), timeouts.op).orThrow() }
    }

    private suspend fun connectedGatt(handle: String): RemoteGattClient {
        awaitReady()
        return gatt(handle).also { it.connect() }
    }

    override fun close() {
        kotlinx.coroutines.runBlocking { session.close() }
        http.close()
        scope.cancel()
    }

    companion object {
        private val CLI_CAPABILITIES: Set<String> = setOf(
            Capabilities.AGENT_STATUS,
            Capabilities.CONNECTION_SLOTS,
            Capabilities.WRITE_POLICY,
            Capabilities.LEASE_HOLDER,
            Capabilities.DESCRIPTORS,
            Capabilities.RSSI,
            Capabilities.SCAN_BATCH,
            Capabilities.RADIO_STATE,
            Capabilities.IDENTIFIER_TRANSLATION,
        )

        /**
         * The ordinary bearer always authenticates the client principal. [operatorToken] is only
         * an optional second credential on the WebSocket upgrade and never replaces that bearer.
         */
        suspend fun open(
            config: ResolvedConfig,
            ordinaryTokenOverride: String? = null,
            operatorToken: String? = null,
        ): RemoteBleGateway {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val http = HttpClient(CIO) { install(WebSockets) }
            val token = { ordinaryTokenOverride ?: environmentVariable(config.agent.tokenEnvironmentVariable) }
            val resolvedClientId = config.agent.clientId ?: persistedClientIdentity()
            val transport = WebSocketAgentTransport(
                url = config.agent.endpoint,
                scope = scope,
                httpClient = http,
                authToken = token,
                operatorToken = { operatorToken },
                reconnect = ReconnectPolicy(maxAttempts = 2),
                clientId = resolvedClientId,
            )
            val session = DefaultAgentSession(
                transport = transport,
                codec = CborProtocolCodec(),
                scope = scope,
                clientCapabilities = CLI_CAPABILITIES,
                retryPolicyFor = { op -> if (op.isIdempotent) RetryPolicies.maxAttempts(2) else RetryPolicies.None },
                identifierFormat = IdentifierFormat.STRING,
            )
            return RemoteBleGateway(session, scope, http, RemoteTimeouts(op = config.operationTimeout), resolvedClientId)
        }
    }
}
