package dev.warsha.remoteble.tools.core

import dev.warsha.remoteble.protocol.AgentStatusDto
import dev.warsha.remoteble.protocol.Capabilities
import dev.warsha.remoteble.protocol.StatusSettingsDto
import dev.warsha.remoteble.protocol.StatusSlotsDto
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking

class WriteOperationServiceTest {
    @Test fun `failed agent preflight does not consume a local rate allowance`() = runBlocking {
        val state = Files.createTempDirectory("remoteble-write-preflight")
        val gateway = FakeWriteGateway(capabilities = emptySet())
        val service = service(gateway, state, maximumWrites = 1)

        assertEquals(ExitCode.UNSUPPORTED, assertFailsWith<CliFailure> { service.execute(request()) }.exitCode)
        gateway.capabilities = enforcedCapabilities

        service.execute(request())
        assertEquals(1, gateway.writes)
    }

    @Test fun `audit failure prevents frame dispatch`() = runBlocking {
        val state = Files.createTempDirectory("remoteble-write-audit")
        val auditPath = Files.createTempFile("remoteble-write-audit-file", ".tmp")
        val gateway = FakeWriteGateway(enforcedCapabilities)
        val service = WriteOperationService(
            gateway,
            writablePolicy(),
            endpoint,
            AuditLogger(auditPath.toString()),
            WriteRateLedger(state.resolve("rate.jsonl").toString()),
        )

        assertEquals(ExitCode.FAILURE, assertFailsWith<CliFailure> { service.execute(request()) }.exitCode)
        assertEquals(1, gateway.connects)
        assertEquals(0, gateway.writes)
    }

    @Test fun `one successful write has attempt submitted and outcome audit records`() = runBlocking {
        val state = Files.createTempDirectory("remoteble-write-audit-records")
        val gateway = FakeWriteGateway(enforcedCapabilities)
        service(gateway, state).execute(request())

        assertEquals(1, gateway.connects)
        assertEquals(1, gateway.writes)
        val audit = Files.list(state).use { paths ->
            Files.readString(paths.filter { it.fileName.toString().startsWith("audit-") }.findFirst().get())
        }
        assertTrue(audit.contains("\"result\":\"attempt\""), audit)
        assertTrue(audit.contains("\"result\":\"submitted\""), audit)
        assertTrue(audit.contains("\"result\":\"ok\""), audit)
    }

    @Test fun `cancellation after submission is indeterminate and never retried`() = runBlocking {
        val state = Files.createTempDirectory("remoteble-write-cancelled")
        val gateway = FakeWriteGateway(enforcedCapabilities, cancelAfterSubmission = true)

        val failure = assertFailsWith<CliFailure> { service(gateway, state).execute(request()) }

        assertEquals(ExitCode.INDETERMINATE, failure.exitCode)
        assertEquals(1, gateway.submissions)
        assertEquals(0, gateway.writes)
        val audit = Files.list(state).use { paths ->
            Files.readString(paths.filter { it.fileName.toString().startsWith("audit-") }.findFirst().get())
        }
        assertTrue(audit.contains("\"result\":\"indeterminate\""), audit)
        assertTrue(audit.contains("\"errorKind\":\"CANCELLED\""), audit)
    }

    private fun service(gateway: FakeWriteGateway, state: java.nio.file.Path, maximumWrites: Int = 2) =
        WriteOperationService(
            gateway,
            writablePolicy(maximumWrites),
            endpoint,
            AuditLogger(state.toString()),
            WriteRateLedger(state.resolve("rate.jsonl").toString()),
        )

    private fun writablePolicy(maximumWrites: Int = 2) = PolicyConfig(
        readOnly = false,
        maximumWritesPerWindow = maximumWrites,
        writeRules = listOf(WriteRuleConfig(endpoint, "device", "180d", "2a37", 16, setOf(true))),
    )

    private fun request() = WriteOperationService.Request("device", "180d", "2a37", byteArrayOf(1), true)

    private class FakeWriteGateway(
        var capabilities: Set<String>,
        private val cancelAfterSubmission: Boolean = false,
    ) : WriteGateway {
        override val clientId = "test-client"
        var connects = 0
        var submissions = 0
        var writes = 0

        override suspend fun capabilities(): Set<String> = capabilities
        override suspend fun status(): AgentStatusDto = AgentStatusDto(
            uptimeMs = 1,
            settings = StatusSettingsDto(0, 0, true, "multiplexed", true, writePolicyEnforced = true),
            slots = StatusSlotsDto(1, 1),
            connectedClients = 1,
        )
        override suspend fun connect(handle: String) {
            connects += 1
        }
        override suspend fun write(
            handle: String,
            service: String,
            characteristic: String,
            value: ByteArray,
            withResponse: Boolean,
            onSubmitted: () -> Unit,
        ) {
            submissions += 1
            onSubmitted()
            if (cancelAfterSubmission) throw CancellationException("cancelled after write submission")
            writes += 1
        }
    }

    private companion object {
        const val endpoint = "wss://agent.example/agent"
        val enforcedCapabilities = setOf(Capabilities.WRITE_POLICY, Capabilities.AGENT_STATUS)
    }
}
