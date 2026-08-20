package dev.warsha.remoteble.tools.integration

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse

/**
 * Naming a global option without a command has to read as the usage failure it is.
 *
 * It printed the usage text to standard output and said nothing about what was wrong, so the reader
 * had to infer the problem from a wall of help, and a caller piping the command received that help
 * as data. A command group additionally exited 0, reporting success for a run in which nothing ran.
 *
 * This runs the packaged CLI as a process because the contract is about the exit code and the
 * stream each byte lands on, and Clikt's in-process test harness does not apply the entry point's
 * own mapping — the layer where this behaviour lives.
 */
class UsageContractTest {
    @Test fun `a missing environment-selected config file is refused`() {
        val result = runCli(listOf("config", "validate"))

        assertEquals(2, result.exitCode, result.toString())
        assertTrue(result.stderr.contains("Configuration file not found"), result.toString())
        assertEquals("", result.stdout.trim(), result.toString())
    }

    @Test fun `a missing command is refused on stderr with a reason`() {
        listOf(
            listOf("--config", "/nonexistent/config.yaml"),
            emptyList(),
            listOf("config"),
            listOf("agent"),
            listOf("descriptor"),
            listOf("skills"),
        ).forEach { arguments ->
            val result = runCli(arguments)
            val described = arguments.joinToString(" ").ifEmpty { "(no arguments)" }

            assertEquals(2, result.exitCode, "$described: $result")
            assertTrue(result.stderr.contains("Error: no command given"), "$described: $result")
            assertTrue(result.stderr.contains("Usage:"), "$described: $result")
            // The usage text is a diagnostic. A caller piping the command must not receive it as data.
            assertEquals("", result.stdout.trim(), "$described put diagnostics on stdout: $result")
        }
    }

    @Test fun `help that was asked for stays successful output`() {
        listOf(listOf("--help"), listOf("config", "--help")).forEach { arguments ->
            val result = runCli(arguments)
            val described = arguments.joinToString(" ")

            assertEquals(0, result.exitCode, "$described: $result")
            assertTrue(result.stdout.contains("Usage:"), "$described: $result")
            assertTrue(!result.stdout.contains("Error:"), "$described reported an error: $result")
            assertEquals("", result.stderr.trim(), "$described wrote to stderr: $result")
        }
    }

    private fun runCli(arguments: List<String>): ProcessResult {
        assumeFalse(System.getProperty("os.name").lowercase().contains("win"), "POSIX-only launcher assertion")
        val logs = Files.createTempDirectory("remoteble-usage")
        val process = ProcessBuilder(listOf("java", "-jar", PackagedCli.launcher().toString()) + arguments).apply {
            environment()["REMOTE_BLE_LOG_DIR"] = logs.toString()
            environment()["REMOTE_BLE_CONFIG"] = logs.resolve("absent.yaml").toString()
            environment()["REMOTE_BLE_CLIENT_ID_FILE"] = logs.resolve("client-id").toString()
        }.start()
        check(process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "remoteble ${arguments.joinToString(" ")} did not exit"
        }
        return ProcessResult(
            process.exitValue(),
            process.inputStream.readBytes().decodeToString(),
            process.errorStream.readBytes().decodeToString(),
        )
    }

    private data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String) {
        override fun toString(): String = "exit=$exitCode\n--- stdout ---\n$stdout\n--- stderr ---\n$stderr"
    }
}
