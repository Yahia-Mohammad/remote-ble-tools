package dev.warsha.remoteble.tools.core

import dev.warsha.remoteble.client.AgentSession
import dev.warsha.remoteble.client.ReconciliationReport
import dev.warsha.remoteble.client.RetryPolicy
import dev.warsha.remoteble.client.SessionReadiness
import dev.warsha.remoteble.client.TransportState
import dev.warsha.remoteble.protocol.AdvertisementDto
import dev.warsha.remoteble.protocol.AgentError
import dev.warsha.remoteble.protocol.AgentEvent
import dev.warsha.remoteble.protocol.AgentException
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.ErrorKind
import dev.warsha.remoteble.protocol.Op
import dev.warsha.remoteble.protocol.OpResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

class ManagedStreamAuditTest {
    @Test fun `stream auditing begins only after scan start acknowledgement and finalizes once`() = runBlocking {
        val directory = Files.createTempDirectory("remoteble-stream-audit")
        val session = StreamSession(OpResult.Ok())
        val gateway = gateway(session)
        try {
            val stream = RemoteOperationService(gateway, "ws://agent.test", AuditLogger(directory.toString()))
                .managedScan(emptyList(), streamId = 7)
            assertTrue(auditRecords(directory).isEmpty())

            val collection = async { stream.events.first() }
            session.startRequested.await()
            assertTrue(auditRecords(directory).isEmpty(), "started must wait for the protocol reply")

            session.allowStartReply.complete(Unit)
            awaitCondition { auditRecords(directory).count { it.contains("\"result\":\"started\"") } == 1 }
            session.events.emit(AgentEvent.ScanResult(7, AdvertisementDto(DeviceHandle("device"), rssi = -40)))
            collection.await()

            stream.stopAndAwait("timeout")
            stream.stopAndAwait("timeout")

            val records = auditRecords(directory)
            val started = records.filter { it.contains("\"result\":\"started\"") }
            val terminal = records.filter { it.contains("\"result\":\"timeout\"") }
            assertEquals(1, started.size)
            assertEquals(1, terminal.size)
            assertEquals(operationId(started.single()), operationId(terminal.single()))
            assertEquals(1, session.stopRequests)
        } finally {
            gateway.close()
        }
    }

    @Test fun `failed scan start records one correlated error without started`() = runBlocking {
        val directory = Files.createTempDirectory("remoteble-stream-start-failure")
        val session = StreamSession(OpResult.Err(AgentError(ErrorKind.POLICY_DENIED, message = "denied")))
        val gateway = gateway(session)
        try {
            val stream = RemoteOperationService(gateway, "ws://agent.test", AuditLogger(directory.toString()))
                .managedScan(emptyList(), streamId = 8)
            val collection = async { runCatching { stream.events.first() }.exceptionOrNull() }

            session.startRequested.await()
            assertTrue(auditRecords(directory).isEmpty())
            session.allowStartReply.complete(Unit)

            assertIs<AgentException>(collection.await())
            val records = auditRecords(directory)
            assertFalse(records.any { it.contains("\"result\":\"started\"") })
            val errors = records.filter { it.contains("\"result\":\"error\"") }
            assertEquals(1, errors.size)
            assertTrue(errors.single().contains("\"errorKind\":\"AgentException\""))
        } finally {
            gateway.close()
        }
    }

    private suspend fun awaitCondition(predicate: () -> Boolean) {
        withTimeout(2.seconds) {
            while (!predicate()) yield()
        }
    }

    private fun operationId(record: String): String =
        Regex("\\\"operationId\\\":\\\"([^\\\"]+)\\\"").find(record)?.groupValues?.get(1) ?: error("missing operation id")

    private fun auditRecords(directory: java.nio.file.Path): List<String> =
        Files.list(directory).use { files ->
            files.filter { it.fileName.toString().startsWith("audit-") }
                .flatMap { Files.lines(it) }
                .toList()
        }

    private fun gateway(session: StreamSession): RemoteBleGateway = RemoteBleGateway(
        session,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        HttpClient(CIO),
        dev.warsha.remoteble.client.RemoteTimeouts(op = 2.seconds),
        "test-client",
    )

    private class StreamSession(private val startReply: OpResult) : AgentSession {
        override val transportState = MutableStateFlow(TransportState.CONNECTED)
        override val readiness = MutableStateFlow(SessionReadiness.READY)
        override val reconciliationReport = MutableStateFlow<ReconciliationReport?>(null)
        override val slotState = MutableStateFlow<AgentEvent.SlotState?>(null)
        override val capabilities = MutableStateFlow<Set<String>?>(emptySet())
        val events = MutableSharedFlow<AgentEvent>()
        val startRequested = CompletableDeferred<Unit>()
        val allowStartReply = CompletableDeferred<Unit>()
        var stopRequests = 0

        override suspend fun request(op: Op, timeout: kotlin.time.Duration, retry: RetryPolicy?): OpResult = when (op) {
            is Op.ScanStart -> {
                startRequested.complete(Unit)
                allowStartReply.await()
                startReply
            }
            is Op.ScanStop -> {
                stopRequests += 1
                OpResult.Ok()
            }
            else -> error("unexpected operation: $op")
        }

        override suspend fun dispatch(op: Op, timeout: kotlin.time.Duration) = error("not used")
        override fun events(): SharedFlow<AgentEvent> = events
        override fun nextStreamId(): Long = 1
        override fun fireAndForget(op: Op) = Unit
        override suspend fun close() = Unit
    }
}
