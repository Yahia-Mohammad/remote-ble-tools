package dev.warsha.remoteble.tools.integration

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Opt-in black-box checks for a real released agent. They intentionally consume no simulator or
 * sibling checkout; set REMOTE_BLE_INTEGRATION_ENDPOINT (and its configured token env var) in CI.
 */
@EnabledIfEnvironmentVariable(named = "REMOTE_BLE_INTEGRATION_ENDPOINT", matches = ".+")
class PackagedCliIntegrationTest {
    @Test fun `packaged cli negotiates capabilities`() {
        val result = runCli("--endpoint", endpoint(), "--json", "agent", "capabilities")
        assertEquals(0, result.exitCode, result.stderr)
        assertTrue(result.stdout.contains("\"schemaVersion\":1"), result.stdout)
        assertTrue(result.stdout.contains("agent.capabilities"), result.stdout)
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "REMOTE_BLE_INTEGRATION_EXPECT_V010", matches = "true")
    fun `v010 feature gates fail closed without a BLE operation`() {
        val status = runCli("--endpoint", endpoint(), "agent", "status")
        assertEquals(7, status.exitCode, status.stderr)
        assertTrue(status.stderr.contains("STATUS"), status.stderr)

        val slots = runCli("--endpoint", endpoint(), "agent", "slots")
        assertEquals(7, slots.exitCode, slots.stderr)
        assertTrue(slots.stderr.contains("GLOBAL_SLOTS"), slots.stderr)

        val config = Files.createTempFile("remoteble-write", ".yaml")
        Files.writeString(config, "schemaVersion: 1\npolicy:\n  readOnly: false\n")
        val write = runCli("--config", config.toString(), "--endpoint", endpoint(), "write", "unseen-device", "180d", "2a37", "--hex", "00")
        assertEquals(7, write.exitCode, write.stderr)
        assertTrue(write.stderr.contains("WRITE_POLICY"), write.stderr)
    }

    private fun endpoint(): String = checkNotNull(System.getenv("REMOTE_BLE_INTEGRATION_ENDPOINT"))

    private fun runCli(vararg arguments: String): ProcessResult {
        val windows = System.getProperty("os.name").lowercase().contains("win")
        assumeFalse(windows, "The current packaged-process launcher assertion is POSIX-only")
        val launcher = PackagedCli.launcher()
        val process = ProcessBuilder(listOf("java", "-jar", launcher.toString()) + arguments).start()
        check(process.waitFor(35, TimeUnit.SECONDS)) { "CLI did not exit before test deadline" }
        return ProcessResult(process.exitValue(), process.inputStream.readBytes().decodeToString(), process.errorStream.readBytes().decodeToString())
    }

    private data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)
}
