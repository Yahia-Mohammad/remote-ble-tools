package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.AgentError
import dev.warsha.remoteble.protocol.AgentEvent
import dev.warsha.remoteble.protocol.AgentException
import dev.warsha.remoteble.protocol.CharRef
import dev.warsha.remoteble.protocol.DescRef
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.BleConnState
import dev.warsha.remoteble.protocol.ErrorKind
import dev.warsha.remoteble.protocol.Op
import dev.warsha.remoteble.protocol.OpResult
import dev.warsha.remoteble.protocol.ResultPayload
import dev.warsha.remoteble.protocol.ServiceNode
import dev.warsha.remoteble.protocol.orThrow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.channelFlow

/**
 * Per-op-class request deadlines. Tuned for the *relayed* worst case, not localhost:
 * establishing a BLE link (scan→connect→bond) and discovering the full GATT table are
 * far slower and more variable than a single read/write, so they get more headroom than
 * the ordinary [op] timeout. Tighten these when you control the network path.
 */
data class RemoteTimeouts(
    val connect: Duration = 30.seconds,
    val discover: Duration = 20.seconds,
    val op: Duration = AgentSession.DEFAULT_TIMEOUT,
)

/**
 * The CLI's remote GATT operation layer, expressed only in protocol-level types.
 */
class RemoteGattClient(
    val handle: DeviceHandle,
    private val session: AgentSession,
    private val timeouts: RemoteTimeouts = RemoteTimeouts(),
) {
    suspend fun connect() {
        session.request(Op.Connect(handle), timeouts.connect).orThrow()
    }

    suspend fun disconnect() {
        session.request(Op.Disconnect(handle), timeouts.op).orThrow()
    }

    suspend fun discover(): List<ServiceNode> =
        session.request(Op.Discover(handle), timeouts.discover).payloadAs<ResultPayload.Services>().services

    suspend fun read(char: CharRef): ByteArray =
        session.request(Op.Read(handle, char), timeouts.op).payloadAs<ResultPayload.Bytes>().value

    /**
     * Sends exactly one Write command. [onSubmitted] runs only after [AgentSession.dispatch]
     * has put that command on the transport, before its reply is awaited.
     */
    suspend fun write(char: CharRef, value: ByteArray, withResponse: Boolean, onSubmitted: () -> Unit = {}) {
        val reply = session.dispatch(Op.Write(handle, char, value, withResponse), timeouts.op)
        onSubmitted()
        reply.await().orThrow()
    }

    /** Reads the connected link's RSSI in dBm (requires the agent's `rssi` capability). */
    suspend fun readRssi(): Int =
        session.request(Op.ReadRssi(handle), timeouts.op).payloadAs<ResultPayload.Rssi>().rssi

    /** Reads a descriptor (requires the agent's `descriptors` capability). */
    suspend fun readDescriptor(desc: DescRef): ByteArray =
        session.request(Op.ReadDescriptor(handle, desc), timeouts.op).payloadAs<ResultPayload.Bytes>().value

    /**
     * Opens a subscription on collect and tears it down on cancel, bridging the
     * request side (observe.start/stop) and the event side (notifications by subId).
     * [onSubscription] runs once the subscription is established.
     */
    fun observe(char: CharRef, onSubscription: suspend () -> Unit = {}): Flow<ByteArray> = channelFlow {
        val subId = session.nextStreamId()
        val pump = session.events()
            // Issue observe.start from onSubscription — only once this collector is registered on
            // the shared event stream — so the first notification can't be emitted before we're
            // listening and get dropped. The caller's onSubscription hook then runs.
            .onSubscription {
                session.request(Op.ObserveStart(subId, handle, char), timeouts.op).orThrow()
                onSubscription()
            }
            .onEach { event ->
                when (event) {
                    is AgentEvent.Notification -> if (event.subId == subId) send(event.value)
                    is AgentEvent.ConnectionState -> if (event.device == handle && event.state == BleConnState.DISCONNECTED) {
                        val reason = event.reason
                        throw AgentException(
                            AgentError(
                                kind = reason?.kind ?: ErrorKind.DISCONNECTED,
                                gattStatus = reason?.gattStatus,
                                message = "peripheral '${handle.value}' disconnected while notifications were active" +
                                    (reason?.message?.let { ": $it" } ?: ""),
                                holder = reason?.holder,
                            ),
                        )
                    }
                    else -> Unit
                }
            }
            .launchIn(this)
        awaitClose {
            pump.cancel()
            session.fireAndForget(Op.ObserveStop(subId))
        }
    }
}

/** Extracts the expected success payload, or throws with a clear [ErrorKind.UNSUPPORTED]. */
internal inline fun <reified T : ResultPayload> OpResult.payloadAs(): T {
    val payload = orThrow()
    return payload as? T
        ?: throw AgentException(
            AgentError(ErrorKind.UNSUPPORTED, message = "unexpected payload: $payload"),
        )
}
