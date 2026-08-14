package dev.warsha.remoteble.tools.integration

import java.io.File
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Small real-process helper for the packaged CLI tests.
 *
 * It deliberately knows nothing about RemoteBLE or its wire protocol.  `terminal = true` uses the
 * host's standard `script` utility to give the child a real Unix pseudo-terminal; ordinary pipes
 * remain available for EOF, slow-reader, and broken-pipe checks.  This keeps terminal coverage at
 * the boundary where users run the CLI instead of introducing a second agent implementation.
 */
internal object ProcessHarness {
    enum class StdoutMode { CAPTURE, PAUSED }

    private val isMac: Boolean = System.getProperty("os.name").lowercase().contains("mac")
    private val isUnix: Boolean = !System.getProperty("os.name").lowercase().contains("win")

    val ptyAvailable: Boolean by lazy {
        isUnix && System.getenv("PATH")
            ?.split(File.pathSeparator)
            ?.map { Path.of(it) }
            ?.any { Files.isExecutable(it.resolve("script")) }
            ?: false
    }

    fun start(
        command: List<String>,
        environment: Map<String, String> = emptyMap(),
        terminal: Boolean = false,
        stdoutMode: StdoutMode = StdoutMode.CAPTURE,
    ): ManagedProcess {
        require(command.isNotEmpty()) { "process command must not be empty" }
        require(!terminal || ptyAvailable) { "a Unix script(1) PTY is required for this test" }
        val actualCommand = if (terminal) ptyCommand(command) else command
        val process = ProcessBuilder(actualCommand).apply { environment().putAll(environment) }.start()
        return ManagedProcess(process, stdoutMode)
    }

    private fun ptyCommand(command: List<String>): List<String> = if (isMac) {
        // BSD script takes its transcript destination followed by the command and its arguments.
        listOf("script", "-q", "/dev/null") + command
    } else {
        // util-linux script accepts the command as one shell string and propagates its exit status.
        listOf("script", "-q", "-e", "-c", command.joinToString(" ", transform = ::shellQuote), "/dev/null")
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\\"'\\\"'") + "'"
}

internal class ManagedProcess internal constructor(
    private val process: Process,
    stdoutMode: ProcessHarness.StdoutMode,
) : AutoCloseable {
    private val stdoutRecords: BlockingQueue<String> = LinkedBlockingQueue()
    private val stdoutText = StringBuilder()
    private val stderrText = StringBuilder()
    private var stdoutPump: Thread? = null
    private val stderrPump: Thread = Thread {
        process.errorStream.bufferedReader().forEachLine { synchronized(stderrText) { stderrText.appendLine(it) } }
    }.apply { isDaemon = true; start() }
    private val stdin: Writer = process.outputStream.bufferedWriter()

    init {
        if (stdoutMode == ProcessHarness.StdoutMode.CAPTURE) startStdoutCapture()
    }

    fun send(text: String) {
        stdin.write(text)
        stdin.flush()
    }

    fun sendLine(line: String) = send("$line\n")

    /** Sends terminal Ctrl-C, not a process-directed signal, when the child owns a PTY. */
    fun sendInterrupt() = send("\u0003")

    fun closeInput() = stdin.close()

    /** Stops reading the pipe; use to model a consumer that has not yet drained stdout. */
    fun pauseStdout() {
        check(stdoutPump == null) { "stdout capture has already started" }
    }

    fun startStdoutCapture() {
        if (stdoutPump != null) return
        stdoutPump = Thread {
            process.inputStream.bufferedReader().forEachLine { line ->
                synchronized(stdoutText) { stdoutText.appendLine(line) }
                stdoutRecords.put(line)
            }
        }.apply { isDaemon = true; start() }
    }

    /** Closing the parent's read end creates the actual broken-pipe condition for the child. */
    fun closeStdout() = process.inputStream.close()

    fun awaitLine(timeoutSeconds: Long = 30, predicate: (String) -> Boolean): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (true) {
            val remaining = deadline - System.nanoTime()
            val line = if (remaining > 0) stdoutRecords.poll(remaining, TimeUnit.NANOSECONDS) else null
            checkNotNull(line) { "no matching stdout line within ${timeoutSeconds}s\n${diagnostics()}" }
            if (predicate(line)) return line
        }
    }

    fun awaitExit(timeoutSeconds: Long = 30): Int {
        check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { "process did not exit within ${timeoutSeconds}s\n${diagnostics()}" }
        stdoutPump?.join(5_000)
        stderrPump.join(5_000)
        return process.exitValue()
    }

    fun isAlive(): Boolean = process.isAlive

    fun stdout(): String = synchronized(stdoutText) { stdoutText.toString() }

    fun stderr(): String = synchronized(stderrText) { stderrText.toString() }

    fun diagnostics(): String = "exit=${if (process.isAlive) "running" else process.exitValue()}\nstdout=${stdout()}\nstderr=${stderr()}"

    override fun close() {
        runCatching { stdin.close() }
        if (process.isAlive) process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
    }
}
