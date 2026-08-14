package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.AgentEvent
import dev.warsha.remoteble.protocol.CharRef
import dev.warsha.remoteble.protocol.BleConnState
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.ErrorKind
import dev.warsha.remoteble.protocol.Op
import dev.warsha.remoteble.protocol.OpResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlin.test.assertFailsWith

class RemoteGattClientTest {
    @Test fun `write dispatches one frame before awaiting its reply`() = runBlocking {
        val session = RecordingSession()
        var submittedAfterDispatches = -1

        RemoteGattClient(DeviceHandle("device"), session).write(CharRef("180d", "2a37"), byteArrayOf(1, 2), true) {
            submittedAfterDispatches = session.dispatched.size
        }

        assertEquals(0, session.requests)
        assertEquals(1, session.dispatched.size)
        assertEquals(1, submittedAfterDispatches)
        val write = session.dispatched.single() as Op.Write
        assertEquals(DeviceHandle("device"), write.device)
        assertEquals(CharRef("180d", "2a37"), write.char)
        assertEquals(byteArrayOf(1, 2).toList(), write.value.toList())
        assertEquals(true, write.withResponse)
    }

    @Test fun `observe reports an unsolicited physical disconnect`() = runBlocking {
        val session = RecordingSession()
        val client = RemoteGattClient(DeviceHandle("device"), session)
        val failure = async {
            assertFailsWith<dev.warsha.remoteble.protocol.AgentException> {
                client.observe(CharRef("180d", "2a37")).first()
            }
        }

        while (session.events.subscriptionCount.value == 0) kotlinx.coroutines.yield()
        session.events.emit(AgentEvent.ConnectionState(DeviceHandle("device"), BleConnState.DISCONNECTED))

        assertEquals(ErrorKind.DISCONNECTED, failure.await().error.kind)
    }

    private class RecordingSession : AgentSession {
        override val transportState = MutableStateFlow(TransportState.CONNECTED)
        override val readiness = MutableStateFlow(SessionReadiness.READY)
        override val reconciliationReport = MutableStateFlow<ReconciliationReport?>(null)
        override val slotState = MutableStateFlow<AgentEvent.SlotState?>(null)
        override val capabilities = MutableStateFlow<Set<String>?>(emptySet())
        val events = MutableSharedFlow<AgentEvent>()
        val dispatched = mutableListOf<Op>()
        var requests = 0

        override suspend fun request(op: Op, timeout: kotlin.time.Duration, retry: RetryPolicy?): OpResult {
            requests += 1
            return when (op) {
                is Op.ObserveStart, is Op.ObserveStop -> OpResult.Ok()
                else -> error("writes must use dispatch")
            }
        }

        override suspend fun dispatch(op: Op, timeout: kotlin.time.Duration): Deferred<OpResult> {
            dispatched += op
            return CompletableDeferred(OpResult.Ok())
        }

        override fun events(): SharedFlow<AgentEvent> = events
        override fun nextStreamId(): Long = 1
        override fun fireAndForget(op: Op) = Unit
        override suspend fun close() = Unit
    }
}
