package dev.warsha.remoteble.tools.core

import dev.warsha.remoteble.client.AgentSession
import dev.warsha.remoteble.client.ReconciliationReport
import dev.warsha.remoteble.client.RetryPolicy
import dev.warsha.remoteble.client.SessionReadiness
import dev.warsha.remoteble.client.TransportState
import dev.warsha.remoteble.protocol.AgentEvent
import dev.warsha.remoteble.protocol.Op
import dev.warsha.remoteble.protocol.OpResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * A session establishes its transport asynchronously, so a one-shot command reaches the gateway
 * while the link is still coming up. Dispatching then fails `TRANSPORT_LOST` against a connection
 * that was milliseconds away — intermittently on a loaded machine, reliably never on a fast one.
 * The gateway must wait for a usable session instead, while still refusing promptly once the
 * transport reports that no usable session is coming.
 */
class GatewayReadinessTest {
    @Test fun `an operation waits for a session that becomes usable late`() = runBlocking {
        val session = LateSession()
        val gateway = gateway(session)
        try {
            val operation = async { gateway.connect("device") }
            // Give an ungated dispatch every chance to happen before readiness arrives.
            repeat(50) { yield() }
            assertFalse(operation.isCompleted, "the operation completed before the session was usable")
            assertEquals(0, session.requests, "a command reached the wire before the transport was up")

            session.transportState.value = TransportState.CONNECTED
            session.readiness.value = SessionReadiness.READY
            withTimeout(2.seconds) { operation.await() }

            assertEquals(1, session.requests)
            assertEquals(SessionReadiness.READY, session.readinessAtDispatch)
        } finally {
            gateway.close()
        }
    }

    @Test fun `a degraded session is usable and is not waited out`() = runBlocking {
        val session = LateSession()
        val gateway = gateway(session)
        try {
            val operation = async { gateway.connect("device") }
            session.readiness.value = SessionReadiness.DEGRADED
            withTimeout(2.seconds) { operation.await() }

            assertEquals(1, session.requests)
        } finally {
            gateway.close()
        }
    }

    @Test fun `a transport that gives up fails without waiting out the deadline`() = runBlocking {
        val session = LateSession()
        val gateway = gateway(session)
        try {
            val operation = async { runCatching { gateway.connect("device") }.exceptionOrNull() }
            session.transportState.value = TransportState.GAVE_UP
            // The operation deadline is 30s; a prompt refusal cannot be sitting it out.
            val failure = assertIs<CliFailure>(withTimeout(2.seconds) { operation.await() })

            assertEquals(ExitCode.RETRYABLE, failure.exitCode)
            assertEquals(0, session.requests, "a hopeless transport must not be dispatched into")
        } finally {
            gateway.close()
        }
    }

    @Test fun `an unsupported protocol keeps its own exit contract`() = runBlocking {
        val session = LateSession()
        val gateway = gateway(session)
        try {
            val operation = async { runCatching { gateway.connect("device") }.exceptionOrNull() }
            session.transportState.value = TransportState.INCOMPATIBLE_PROTOCOL
            val failure = assertIs<CliFailure>(withTimeout(2.seconds) { operation.await() })

            assertEquals(ExitCode.UNSUPPORTED, failure.exitCode)
            assertEquals(0, session.requests)
        } finally {
            gateway.close()
        }
    }

    private fun gateway(session: AgentSession): RemoteBleGateway = RemoteBleGateway(
        session,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        HttpClient(CIO),
        // Long enough that any test finishing quickly proves it did not wait out the deadline.
        dev.warsha.remoteble.client.RemoteTimeouts(op = 30.seconds),
        "test-client",
    )

    /** A session that stays unusable until a test makes it usable, recording what it dispatched. */
    private class LateSession : AgentSession {
        override val transportState = MutableStateFlow(TransportState.CONNECTING)
        override val readiness = MutableStateFlow(SessionReadiness.CONNECTING)
        override val reconciliationReport = MutableStateFlow<ReconciliationReport?>(null)
        override val slotState = MutableStateFlow<AgentEvent.SlotState?>(null)
        override val capabilities = MutableStateFlow<Set<String>?>(emptySet())
        private val eventFlow = MutableSharedFlow<AgentEvent>()
        var requests = 0
            private set
        var readinessAtDispatch: SessionReadiness? = null
            private set

        override suspend fun request(op: Op, timeout: kotlin.time.Duration, retry: RetryPolicy?): OpResult {
            requests += 1
            readinessAtDispatch = readiness.value
            return OpResult.Ok()
        }

        override suspend fun dispatch(op: Op, timeout: kotlin.time.Duration) = error("not used")
        override fun events(): SharedFlow<AgentEvent> = eventFlow
        override fun nextStreamId(): Long = 1
        override fun fireAndForget(op: Op) = Unit
        override suspend fun close() = Unit
    }
}
