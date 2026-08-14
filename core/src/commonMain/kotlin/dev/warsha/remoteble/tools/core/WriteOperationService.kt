package dev.warsha.remoteble.tools.core

import dev.warsha.remoteble.protocol.AgentException
import dev.warsha.remoteble.protocol.Capabilities
import dev.warsha.remoteble.protocol.ErrorKind
import kotlin.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.TimeoutCancellationException

/** The minimum gateway surface required to preflight and dispatch one write. */
interface WriteGateway {
    val clientId: String
    suspend fun capabilities(): Set<String>
    suspend fun status(): dev.warsha.remoteble.protocol.AgentStatusDto
    suspend fun connect(handle: String)
    suspend fun write(
        handle: String,
        service: String,
        characteristic: String,
        value: ByteArray,
        withResponse: Boolean,
        onSubmitted: () -> Unit,
    )
}

/**
 * The single write dispatch path for every CLI frontend.  It intentionally has no retry path:
 * after the attempt audit record has been committed, a lost/timeout reply is indeterminate.
 */
class WriteOperationService(
    private val gateway: WriteGateway,
    private val policy: PolicyConfig,
    private val endpoint: String,
    private val audit: AuditLogger,
    private val rateLedger: WriteRateLedger = WriteRateLedger(),
) {
    data class Request(
        val handle: String,
        val serviceUuid: String,
        val characteristicUuid: String,
        val payload: ByteArray,
        val withResponse: Boolean,
        val operation: String = "write",
        val sessionId: String? = null,
        val commandId: String? = null,
        val streamId: String? = null,
    )

    suspend fun execute(request: Request) {
        @OptIn(ExperimentalUuidApi::class)
        val operationId = "op-" + Uuid.random().toString()
        val writeType = if (request.withResponse) "with-response" else "without-response"
        if (policy.readOnly) throw CliFailure(ExitCode.UNSUPPORTED, "write is disabled by local read-only policy")
        if (!policy.allowsWrite(endpoint, request.handle, request.serviceUuid, request.characteristicUuid, request.payload.size, request.withResponse)) {
            throw CliFailure(ExitCode.UNSUPPORTED, "write is denied by the local advisory policy")
        }
        val rateKey = listOf(endpoint, request.handle, request.serviceUuid, request.characteristicUuid, writeType).joinToString("|")
        val window = try {
            Duration.parse(policy.writeRateWindow)
        } catch (error: Throwable) {
            throw CliFailure(ExitCode.USAGE, "write rate window is invalid", error)
        }
        // Policy can reach here without passing through validation, and a non-positive window turns
        // the limiter off rather than failing loudly. Refuse the write instead.
        if (!window.isPositive() || !window.isFinite()) {
            throw CliFailure(ExitCode.USAGE, "write rate window must be a positive, finite duration")
        }
        val windowMillis = window.inWholeMilliseconds
        val capabilities = gateway.capabilities()
        if (Capabilities.WRITE_POLICY !in capabilities || Capabilities.AGENT_STATUS !in capabilities) {
            throw CliFailure(ExitCode.UNSUPPORTED, "agent does not advertise enforced write/status contracts")
        }
        if (!gateway.status().settings.writePolicyEnforced) {
            throw CliFailure(ExitCode.UNSUPPORTED, "agent-side write policy is not enforced; writes are refused")
        }
        // A connection/setup failure happens before the write attempt and must retain its
        // ordinary definitive/retryable exit mapping. Once the audit attempt is committed,
        // the gateway dispatches exactly one Write frame and never retries it.
        gateway.connect(request.handle)
        if (!rateLedger.tryConsume(rateKey, policy.maximumWritesPerWindow, windowMillis)) {
            throw CliFailure(ExitCode.UNSUPPORTED, "write rate limit exceeded; try again later")
        }
        fun audit(result: String, errorKind: String? = null, mutation: Boolean = false) = audit.audit(
            operation = request.operation, operationId = operationId, sessionId = request.sessionId, commandId = request.commandId,
            streamId = request.streamId, endpoint = endpoint, clientId = gateway.clientId,
            device = request.handle, serviceUuid = request.serviceUuid, characteristicUuid = request.characteristicUuid,
            writeType = writeType, payloadLength = request.payload.size, result = result, errorKind = errorKind,
            mutation = mutation,
        )
        // The attempt record is the boundary. Everything after it is uncertain on cancellation:
        // the frame may already have reached the transport, and neither the send nor the socket
        // reports how far it got, so there is no point at which "cancelled" can be read as
        // "prevented". Cancellation *before* this line — during policy, capability, connection, or
        // rate checks — propagates unchanged and stays definitive, because no frame exists yet.
        audit("attempt", mutation = true)
        try {
            gateway.write(request.handle, request.serviceUuid, request.characteristicUuid, request.payload, request.withResponse) {
                // The frame is already on the wire. Logging must therefore be best-effort here:
                // failure cannot truthfully claim that this mutation was prevented.
                audit("submitted")
            }
            audit("ok")
        } catch (error: AgentException) {
            audit("error", error.error.kind.name)
            if (error.error.kind == ErrorKind.TIMEOUT || error.error.kind == ErrorKind.TRANSPORT_LOST) {
                throw CliFailure(ExitCode.INDETERMINATE, "write outcome is uncertain; the write was not retried", error, error.error)
            }
            throw error
        } catch (error: TimeoutCancellationException) {
            audit("error", "TIMEOUT")
            throw CliFailure(ExitCode.INDETERMINATE, "write outcome is uncertain; the write was not retried", error)
        } catch (error: CancellationException) {
            audit("indeterminate", "CANCELLED")
            throw CliFailure(ExitCode.INDETERMINATE, "write outcome is uncertain; the write was not retried", error)
        } catch (error: Throwable) {
            audit("error", error::class.simpleName)
            throw error
        }
    }
}
