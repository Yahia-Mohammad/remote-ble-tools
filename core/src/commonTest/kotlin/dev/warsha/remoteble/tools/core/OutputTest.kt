package dev.warsha.remoteble.tools.core

import dev.warsha.remoteble.protocol.AgentError
import dev.warsha.remoteble.protocol.AgentException
import dev.warsha.remoteble.protocol.ErrorKind
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.ZERO
import kotlin.test.Test
import kotlin.test.assertEquals

class OutputTest {
    @Test fun `UUIDs normalize and assigned names are stable`() {
        assertEquals("0000180d-0000-1000-8000-00805f9b34fb", normalizeUuid("180D"))
        assertEquals("Heart Rate", sigName("180d"))
        assertEquals(listOf("read", "notify"), gattPropertyNames(0x12))
    }
    @Test fun `bytes use stable codecs`() {
        val value = byteArrayOf(0, 15, -1)
        assertEquals("000fff", value.hex())
        assertEquals("AA//", value.base64())
    }

    @Test fun `hostile device text is escaped`() {
        assertEquals("A\\u000aB", escapeDeviceText("A\nB"))
    }

    @Test fun `operation failures retain stable protocol exit codes`() {
        val unknown = operationFailure(AgentException(AgentError(ErrorKind.UNKNOWN_DEVICE, message = "missing")))
        val busy = operationFailure(AgentException(AgentError(ErrorKind.PERIPHERAL_BUSY)))
        val denied = operationFailure(AgentException(AgentError(ErrorKind.POLICY_DENIED)))

        assertEquals(ExitCode.NOT_FOUND, unknown.exitCode)
        assertEquals("missing", unknown.message)
        assertEquals(ExitCode.BUSY, busy.exitCode)
        assertEquals(ExitCode.UNSUPPORTED, denied.exitCode)
    }

    @Test fun `timeouts and existing failures retain their contract`() {
        val timeout = operationFailure(runBlocking {
            runCatching { withTimeout(ZERO) { awaitCancellation() } }.exceptionOrNull()!!
        })
        val existing = CliFailure(ExitCode.USAGE, "already classified")

        assertEquals(ExitCode.RETRYABLE, timeout.exitCode)
        assertEquals(existing, operationFailure(existing))
    }
}
