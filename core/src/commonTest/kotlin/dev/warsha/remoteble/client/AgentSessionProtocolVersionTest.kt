package dev.warsha.remoteble.client

import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.AgentException
import dev.warsha.remoteble.protocol.ErrorKind
import dev.warsha.remoteble.protocol.Op
import dev.warsha.remoteble.protocol.OpResult
import dev.warsha.remoteble.protocol.ServerHello
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

class AgentSessionProtocolVersionTest {
    private val codec = CborProtocolCodec()

    @Test fun `matching server hello makes the session ready`() = runBlocking {
        val transport = TestTransport()
        val session = DefaultAgentSession(transport, codec, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        try {
            transport.awaitHello()
            transport.incomingFrames.emit(codec.encode(ServerHello(version = 1)))
            await { session.readiness.value == SessionReadiness.READY }
            assertEquals(SessionReadiness.READY, session.readiness.value)
        } finally {
            session.close()
        }
    }

    @Test fun `incompatible server hello fails closed before any command is sent`() = runBlocking {
        val transport = TestTransport()
        val session = DefaultAgentSession(transport, codec, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        try {
            transport.awaitHello()
            transport.incomingFrames.emit(codec.encode(ServerHello(version = 2)))
            await { session.readiness.value == SessionReadiness.INCOMPATIBLE_PROTOCOL }

            val result = assertIs<OpResult.Err>(session.request(Op.AgentStatus))
            assertEquals(ErrorKind.INCOMPATIBLE_PROTOCOL, result.error.kind)
            // The only frame sent is ClientHello. A bad ServerHello must not be followed by a command.
            assertEquals(1, transport.sent.size)
        } finally {
            session.close()
        }
    }

    @Test fun `capability wait ends when server hello is incompatible`() = runBlocking {
        val transport = TestTransport()
        val session = DefaultAgentSession(transport, codec, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        try {
            transport.awaitHello()
            transport.incomingFrames.emit(codec.encode(ServerHello(version = 2)))

            val failure = assertFailsWith<AgentException> { session.awaitCapabilities() }
            assertEquals(ErrorKind.INCOMPATIBLE_PROTOCOL, failure.error.kind)
        } finally {
            session.close()
        }
    }

    private suspend fun await(predicate: () -> Boolean) {
        withTimeout(2.seconds) {
            while (!predicate()) delay(10)
        }
    }

    private class TestTransport : AgentTransport {
        override val state: StateFlow<TransportState> = MutableStateFlow(TransportState.CONNECTED)
        val incomingFrames = MutableSharedFlow<ByteArray>()
        override val incoming: Flow<ByteArray> = incomingFrames
        val sent = mutableListOf<ByteArray>()

        override suspend fun connect() = Unit
        override suspend fun send(frame: ByteArray) { sent += frame }
        override suspend fun close() = Unit

        suspend fun awaitHello() = withTimeout(2.seconds) {
            while (sent.isEmpty()) delay(10)
        }
    }
}
