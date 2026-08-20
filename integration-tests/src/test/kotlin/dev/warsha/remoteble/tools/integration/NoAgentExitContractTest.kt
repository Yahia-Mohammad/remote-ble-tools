package dev.warsha.remoteble.tools.integration

import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse

/**
 * Every command must reach the documented exit contract when no agent answers.
 *
 * This needs no agent, so unlike [PackagedCliIntegrationTest] it is not opt-in. It exists because
 * the capability-gated commands used to await the handshake on an unbounded `StateFlow`: with the
 * agent unreachable that value never arrives, and the process hung instead of exiting 9. A unit test
 * cannot show it — the failure is the absence of an exit — so the assertion is a real process with
 * a deadline.
 */
class NoAgentExitContractTest {
    @Test fun `capability gated commands exit retryable instead of hanging`() {
        val port = closedPort()
        val commands = listOf(
            listOf("agent", "capabilities"),
            listOf("agent", "status"),
            listOf("agent", "slots"),
            listOf("rssi", "unseen-device"),
            listOf("descriptor", "read", "unseen-device", "180f", "2a19", "2902"),
        )
        commands.forEach { command ->
            val result = runCli(port, command)
            assertEquals(9, result.exitCode, "${command.joinToString(" ")}: ${result.stderr}")
        }
    }

    @Test fun `radio commands keep the same exit contract`() {
        val port = closedPort()
        listOf(
            listOf("read", "unseen-device", "180f", "2a19"),
            listOf("inspect", "unseen-device"),
            listOf("connect", "unseen-device"),
        ).forEach { command ->
            val result = runCli(port, command)
            assertEquals(9, result.exitCode, "${command.joinToString(" ")}: ${result.stderr}")
        }
    }

    @Test fun `structured failures stay machine readable without an agent`() {
        val result = runCli(closedPort(), listOf("--json", "agent", "capabilities"))
        assertEquals(9, result.exitCode, result.stderr)
        assertTrue(result.stderr.contains("\"schemaVersion\":1"), result.stderr)
        assertTrue(result.stderr.contains("\"exitCode\":9"), result.stderr)
    }

    @Test fun `config show uses its published discriminator and effective overrides`() {
        val port = closedPort()
        val result = runCli(port, listOf("--client-id", "override-client", "--json", "config", "show"))
        assertEquals(0, result.exitCode, result.stderr)
        assertTrue(result.stdout.contains("\"type\":\"config.show\""), result.stdout)
        assertTrue(result.stdout.contains("\"endpoint\":\"ws://127.0.0.1:$port/agent\""), result.stdout)
        assertTrue(result.stdout.contains("\"clientId\":\"override-client\""), result.stdout)
    }

    @Test fun `ordinary commands retain the operating system SIGINT behavior`() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"), "POSIX-only signal assertion")
        val launcher = PackagedCli.launcher()
        val logs = Files.createTempDirectory("remoteble-sigint")
        val config = logs.resolve("config.yaml")
        Files.writeString(config, "schemaVersion: 1\n")
        val process = ProcessBuilder(
            "java", "-jar", launcher.toString(), "--token-stdin", "agent", "status",
        ).apply {
            environment()["REMOTE_BLE_LOG_DIR"] = logs.toString()
            environment()["REMOTE_BLE_CONFIG"] = config.toString()
            environment()["REMOTE_BLE_CLIENT_ID_FILE"] = logs.resolve("client-id").toString()
        }.start()
        try {
            // readNBytes waits for EOF after this partial token. At this point the platform class is
            // loaded, which is what exposed the former process-wide handler installation.
            process.outputStream.write("partial-token".encodeToByteArray())
            process.outputStream.flush()
            Thread.sleep(1_000)
            assertTrue(process.isAlive, "the CLI exited before SIGINT could be tested")

            val signal = ProcessBuilder("kill", "-INT", process.pid().toString()).start()
            assertTrue(signal.waitFor(5, TimeUnit.SECONDS) && signal.exitValue() == 0, "failed to deliver SIGINT")
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "ordinary command ignored SIGINT")
            assertTrue(process.exitValue() != 0, "SIGINT unexpectedly produced a successful exit")
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    @Test fun `persistent shell handles SIGINT through cleanup and exits promptly`() {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"), "POSIX-only signal assertion")
        ServerSocket(0).use { silentServer ->
            // Accept TCP but never complete the WebSocket handshake. This keeps awaitReady active
            // long enough to prove SIGINT cancels startup as well as an idle prompt.
            val holder = Thread {
                runCatching {
                    silentServer.accept().use {
                        while (!Thread.currentThread().isInterrupted) Thread.sleep(100)
                    }
                }
            }.apply { isDaemon = true; start() }
            val launcher = PackagedCli.launcher()
            val logs = Files.createTempDirectory("remoteble-shell-sigint")
            val config = logs.resolve("config.yaml")
            Files.writeString(config, "schemaVersion: 1\n")
            val process = ProcessBuilder(
                "java", "-jar", launcher.toString(), "--endpoint", "ws://127.0.0.1:${silentServer.localPort}/agent", "shell",
            ).apply {
                environment()["REMOTE_BLE_LOG_DIR"] = logs.toString()
                environment()["REMOTE_BLE_CONFIG"] = config.toString()
                environment()["REMOTE_BLE_CLIENT_ID_FILE"] = logs.resolve("client-id").toString()
                environment()["REMOTE_BLE_TOKEN"] = "integration-placeholder"
            }.start()
            try {
                Thread.sleep(1_000)
                assertTrue(process.isAlive, "the shell exited before SIGINT could be tested")

                val signal = ProcessBuilder("kill", "-INT", process.pid().toString()).start()
                assertTrue(signal.waitFor(5, TimeUnit.SECONDS) && signal.exitValue() == 0, "failed to deliver SIGINT")
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "persistent shell did not clean up after SIGINT")
                assertEquals(0, process.exitValue(), process.errorStream.readBytes().decodeToString())
            } finally {
                if (process.isAlive) process.destroyForcibly()
                holder.interrupt()
                holder.join(1_000)
            }
        }
    }

    /** A port that accepted a bind and was then released: nothing is listening on it. */
    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    private fun runCli(port: Int, arguments: List<String>): ProcessResult {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"), "POSIX-only launcher assertion")
        val launcher = PackagedCli.launcher()
        val logs = Files.createTempDirectory("remoteble-no-agent")
        val config = logs.resolve("config.yaml")
        Files.writeString(config, "schemaVersion: 1\n")
        val process = ProcessBuilder(
            listOf("java", "-jar", launcher.toString(), "--endpoint", "ws://127.0.0.1:$port/agent") + arguments,
        ).apply {
            // Keep the run off the developer's real configuration, identity, and log directory.
            environment()["REMOTE_BLE_LOG_DIR"] = logs.toString()
            environment()["REMOTE_BLE_CONFIG"] = config.toString()
            environment()["REMOTE_BLE_CLIENT_ID_FILE"] = logs.resolve("client-id").toString()
            environment()["REMOTE_BLE_TOKEN"] = "integration-placeholder"
        }.start()
        check(process.waitFor(DEADLINE_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "remoteble ${arguments.joinToString(" ")} did not exit within ${DEADLINE_SECONDS}s against an unreachable agent"
        }
        return ProcessResult(process.exitValue(), process.inputStream.readBytes().decodeToString(), process.errorStream.readBytes().decodeToString())
    }

    private data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

    private companion object {
        /** Comfortably above the default 20 s operation deadline, far below "hung". */
        const val DEADLINE_SECONDS = 40L
    }
}
