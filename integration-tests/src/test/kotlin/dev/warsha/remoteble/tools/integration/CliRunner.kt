package dev.warsha.remoteble.tools.integration

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal data class CliResult(val exitCode: Int, val stdout: String, val stderr: String) {
    fun jsonLines(): List<String> = stdout.lineSequence().filter { it.startsWith("{") }.toList()
    override fun toString(): String = "exit=$exitCode\n--- stdout ---\n$stdout\n--- stderr ---\n$stderr"
}

/**
 * Runs the packaged CLI as a real process against the live agent.
 *
 * Each invocation is a separate process on purpose: process-per-command is the shape this CLI is
 * designed for, and lease resumption across invocations is one of the things under test. The client
 * identity and log directory therefore persist across invocations within one scenario, exactly as
 * they would for a user, while staying isolated from the developer's real ones.
 */
internal class CliRunner(
    private val token: String = LiveAgent.PRIMARY_TOKEN,
    private val clientId: String = "acceptance-client-a",
    private val extraEnvironment: Map<String, String> = emptyMap(),
) {
    val logDirectory: Path = Files.createTempDirectory("remoteble-acceptance-logs")

    private val configPath: Path = logDirectory.resolve("config.yaml")

    fun run(vararg arguments: String): CliResult = run(arguments.toList(), stdin = null)

    private fun command(arguments: List<String>): List<String> =
        listOf("java", "-jar", PackagedCli.launcher().toString(), "--endpoint", LiveAgent.endpoint) + arguments

    private fun environment(): Map<String, String> = mapOf(
        "REMOTE_BLE_TOKEN" to token,
        "REMOTE_BLE_CLIENT_ID" to clientId,
        "REMOTE_BLE_LOG_DIR" to logDirectory.toString(),
        "REMOTE_BLE_CLIENT_ID_FILE" to logDirectory.resolve("client-id").toString(),
        "REMOTE_BLE_CONFIG" to configPath.toString(),
    ) + extraEnvironment

    private fun start(arguments: List<String>): Process = ProcessBuilder(command(arguments)).apply {
        environment().putAll(this@CliRunner.environment())
    }.start()

    /** Starts the same isolated packaged CLI through the reusable pipe/PTY harness. */
    fun interactive(
        arguments: List<String>,
        terminal: Boolean = false,
        stdoutMode: ProcessHarness.StdoutMode = ProcessHarness.StdoutMode.CAPTURE,
    ): ManagedProcess = ProcessHarness.start(command(arguments), environment(), terminal, stdoutMode)

    fun run(arguments: List<String>, stdin: String? = null, timeoutSeconds: Long = 90): CliResult {
        val process = start(arguments)
        stdin?.let { process.outputStream.use { stream -> stream.write(it.encodeToByteArray()) } }
        if (stdin == null) process.outputStream.close()
        val stdout = process.inputStream.bufferedReader()
        val stderr = process.errorStream.bufferedReader()
        val out = StringBuilder()
        val err = StringBuilder()
        val pumpOut = Thread { stdout.forEachLine { out.appendLine(it) } }.apply { isDaemon = true; start() }
        val pumpErr = Thread { stderr.forEachLine { err.appendLine(it) } }.apply { isDaemon = true; start() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("remoteble ${arguments.joinToString(" ")} did not exit within ${timeoutSeconds}s")
        }
        pumpOut.join(5_000)
        pumpErr.join(5_000)
        return CliResult(process.exitValue(), out.toString(), err.toString())
    }

    /**
     * Runs a session interactively: the script writes records and reads replies as they arrive,
     * then stdin is closed and the process is awaited. `stream.stop` needs the streamId the session
     * assigned, which is only knowable from the output, so a fixed stdin script cannot reach it.
     */
    fun session(arguments: List<String>, timeoutSeconds: Long = 90, script: (SessionPipe) -> Unit): CliResult {
        val process = start(arguments)
        val records = LinkedBlockingQueue<String>()
        val out = StringBuilder()
        val err = StringBuilder()
        val pumpOut = Thread {
            process.inputStream.bufferedReader().forEachLine { out.appendLine(it); records.put(it) }
        }.apply { isDaemon = true; start() }
        val pumpErr = Thread {
            process.errorStream.bufferedReader().forEachLine { err.appendLine(it) }
        }.apply { isDaemon = true; start() }
        val writer = process.outputStream.bufferedWriter()
        try {
            script(SessionPipe(writer, records))
        } finally {
            runCatching { writer.close() }
        }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("remoteble ${arguments.joinToString(" ")} did not exit within ${timeoutSeconds}s\n$out")
        }
        pumpOut.join(5_000)
        pumpErr.join(5_000)
        return CliResult(process.exitValue(), out.toString(), err.toString())
    }

    internal class SessionPipe(private val writer: java.io.Writer, private val records: BlockingQueue<String>) {
        fun send(record: String) {
            writer.write(record + "\n")
            writer.flush()
        }

        /** Consumes records until one matches, so the caller can wait for a specific reply. */
        fun await(timeoutSeconds: Long = 30, predicate: (String) -> Boolean): String {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            while (true) {
                val remaining = deadline - System.nanoTime()
                val record = if (remaining > 0) records.poll(remaining, TimeUnit.NANOSECONDS) else null
                checkNotNull(record) { "no matching session record within ${timeoutSeconds}s" }
                if (predicate(record)) return record
            }
        }
    }

    /** Writes a config file for this identity; returns the same runner for chaining. */
    fun withConfig(yaml: String): CliRunner {
        Files.writeString(configPath, yaml)
        return this
    }

    fun auditRecords(): List<String> = runCatching {
        Files.list(logDirectory).use { paths ->
            paths.filter { it.fileName.toString().startsWith("audit-") }.toList()
        }.flatMap { Files.readAllLines(it) }
    }.getOrDefault(emptyList())
}
