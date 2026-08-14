package dev.warsha.remoteble.tools.core

import dev.warsha.remoteble.protocol.AgentStatusDto
import dev.warsha.remoteble.protocol.AdvertisementDto
import dev.warsha.remoteble.protocol.ScanFilter
import dev.warsha.remoteble.protocol.ServiceNode
import dev.warsha.remoteble.protocol.StatusSlotsDto
import kotlin.time.Duration
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow

/** Exactly one opaque handle or one or more advertisement selectors. */
data class DeviceSelection(
    val handle: String?,
    val name: String?,
    val serviceUuid: String?,
)

/** Common non-mutating operation path used by the one-shot and persistent frontends. */
class RemoteOperationService(
    private val gateway: RemoteBleGateway,
    private val endpoint: String,
    private val audit: AuditLogger,
    private val sessionId: String? = null,
    private val commandId: String? = null,
) {
    /** A managed radio stream with one audit correlation id for its full lifetime. */
    class ManagedOperation<T> internal constructor(
        val streamId: Long,
        val events: Flow<T>,
        private val stop: suspend (String) -> Unit,
    ) {
        suspend fun stopAndAwait(result: String) = stop(result)
    }

    suspend fun status(): AgentStatusDto = audited("agent.status") { gateway.status() }

    suspend fun slots(): StatusSlotsDto = audited("agent.slots") { gateway.slots() }

    suspend fun inspect(handle: String): List<ServiceNode> =
        audited("inspect", device = handle) { gateway.inspect(handle) }

    suspend fun read(handle: String, serviceUuid: String, characteristicUuid: String): ByteArray =
        audited("read", handle, serviceUuid, characteristicUuid) { gateway.read(handle, serviceUuid, characteristicUuid) }

    suspend fun readDescriptor(handle: String, serviceUuid: String, characteristicUuid: String, descriptorUuid: String): ByteArray =
        audited("descriptor.read", handle, serviceUuid, characteristicUuid) {
            gateway.readDescriptor(handle, serviceUuid, characteristicUuid, descriptorUuid)
        }

    suspend fun readRssi(handle: String): Int = audited("rssi", device = handle) { gateway.readRssi(handle) }

    suspend fun scan(filters: List<ScanFilter>, duration: Duration, maximumEvents: Int): List<AdvertisementDto> =
        audited("scan") { gateway.scan(filters, duration, maximumEvents) }

    suspend fun resolve(selection: DeviceSelection, scanDuration: Duration): String =
        audited("selector.resolve") {
            if (selection.handle != null) {
                if (selection.name != null || selection.serviceUuid != null) {
                    throw CliFailure(ExitCode.USAGE, "Use either HANDLE or selector flags, not both")
                }
                return@audited selection.handle
            }
            if (selection.name == null && selection.serviceUuid == null) {
                throw CliFailure(ExitCode.USAGE, "Provide a HANDLE or --name/--service selector")
            }
            val matches = gateway.scan(
                listOf(ScanFilter(selection.serviceUuid, selection.name)),
                scanDuration,
                maximumEvents = 100,
            ).distinctBy { it.device.value }
            when (matches.size) {
                0 -> throw CliFailure(ExitCode.NOT_FOUND, "No device matched the requested selector")
                1 -> matches.single().device.value
                else -> throw CliFailure(ExitCode.NOT_FOUND, "Selector is ambiguous; ${matches.size} devices matched. Run scan and use a handle.")
            }
        }

    suspend fun connect(selection: DeviceSelection, scanDuration: Duration): String {
        val handle = resolve(selection, scanDuration)
        audited("connect", device = handle) { gateway.connect(handle) }
        return handle
    }

    suspend fun disconnect(selection: DeviceSelection, scanDuration: Duration): String {
        val handle = resolve(selection, scanDuration)
        audited("disconnect", device = handle) { gateway.disconnect(handle) }
        return handle
    }

    suspend fun managedScan(filters: List<ScanFilter>, streamId: Long = gateway.session.nextStreamId()): ManagedOperation<AdvertisementDto> =
        managed("scan", streamId = streamId) { onStarted, onStartFailure ->
            gateway.managedScan(filters, streamId, onStarted, onStartFailure)
        }

    suspend fun managedObserve(
        handle: String,
        serviceUuid: String,
        characteristicUuid: String,
        streamId: Long = gateway.session.nextStreamId(),
    ): ManagedOperation<ByteArray> = managed("observe", handle, serviceUuid, characteristicUuid, streamId) { onStarted, onStartFailure ->
        gateway.managedObserve(handle, serviceUuid, characteristicUuid, streamId, onStarted, onStartFailure)
    }

    private suspend fun <T> managed(
        operation: String,
        device: String? = null,
        serviceUuid: String? = null,
        characteristicUuid: String? = null,
        streamId: Long,
        open: suspend (onStarted: () -> Unit, onStartFailure: (Throwable) -> Unit) -> RemoteBleGateway.ManagedStream<T>,
    ): ManagedOperation<T> {
        val operationId = operationId()
        val started = kotlin.time.Clock.System.now().toEpochMilliseconds()
        @OptIn(ExperimentalAtomicApi::class)
        val streamStarted = AtomicBoolean(false)
        @OptIn(ExperimentalAtomicApi::class)
        val terminal = AtomicBoolean(false)
        @OptIn(ExperimentalAtomicApi::class)
        val stopRequested = AtomicBoolean(false)
        fun auditTerminal(result: String, errorKind: String? = null) {
            @OptIn(ExperimentalAtomicApi::class)
            if (!terminal.compareAndSet(false, true)) return
            audit.audit(operation, operationId, sessionId, commandId, streamId.toString(), endpoint, gateway.clientId,
                device, serviceUuid, characteristicUuid,
                durationMs = kotlin.time.Clock.System.now().toEpochMilliseconds() - started, result = result, errorKind = errorKind)
        }
        fun auditStarted() {
            @OptIn(ExperimentalAtomicApi::class)
            if (!streamStarted.compareAndSet(false, true)) return
            audit.audit(operation, operationId, sessionId, commandId, streamId.toString(), endpoint, gateway.clientId,
                device, serviceUuid, characteristicUuid, durationMs = kotlin.time.Clock.System.now().toEpochMilliseconds() - started, result = "started")
        }
        val stream = try {
            open(::auditStarted) { error -> auditTerminal("error", error::class.simpleName) }
        } catch (error: Throwable) {
            auditTerminal("error", error::class.simpleName)
            throw error
        }
        return ManagedOperation(stream.streamId, stream.events) { result ->
            @OptIn(ExperimentalAtomicApi::class)
            if (!stopRequested.compareAndSet(false, true)) return@ManagedOperation
            try {
                stream.stopAndAwait()
                auditTerminal(result)
            } catch (error: Throwable) {
                auditTerminal("error", error::class.simpleName)
                throw error
            }
        }
    }

    private suspend fun <T> audited(
        operation: String,
        device: String? = null,
        serviceUuid: String? = null,
        characteristicUuid: String? = null,
        action: suspend () -> T,
    ): T {
        val operationId = operationId()
        val started = kotlin.time.Clock.System.now().toEpochMilliseconds()
        try {
            return action().also {
                audit.audit(operation, operationId, sessionId, commandId, endpoint = endpoint, clientId = gateway.clientId,
                    device = device, serviceUuid = serviceUuid, characteristicUuid = characteristicUuid,
                    durationMs = kotlin.time.Clock.System.now().toEpochMilliseconds() - started, result = "ok")
            }
        } catch (error: Throwable) {
            audit.audit(operation, operationId, sessionId, commandId, endpoint = endpoint, clientId = gateway.clientId,
                device = device, serviceUuid = serviceUuid, characteristicUuid = characteristicUuid,
                durationMs = kotlin.time.Clock.System.now().toEpochMilliseconds() - started, result = "error",
                errorKind = error::class.simpleName)
            throw error
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun operationId(): String = "op-" + Uuid.random().toString()
}
