package dev.warsha.remoteble.tools.integration

import dev.warsha.remoteble.tools.core.withFileLock
import java.io.BufferedReader
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

@Tag("native-lock-handoff")
class NativeLockHandoffTest {
    private val holder: String = checkNotNull(System.getProperty("remoteble.native.lock.holder")) {
        "remoteble.native.lock.holder is not set; run :integration-tests:nativeLockHandoffTest"
    }

    @Test fun `Native holder blocks JVM then JVM holder blocks Native`() {
        val lock = Files.createTempDirectory("remoteble-jvm-native-lock").resolve(".state.lock")
        val lines = Executors.newCachedThreadPool()
        val processes = mutableListOf<NativeProcess>()
        try {
            val nativeFirst = startNative(lock, holdSeconds = 2).also(processes::add)
            assertEquals("ready", awaitLine(nativeFirst.output, lines))
            nativeFirst.signalGo()
            assertEquals("attempting", awaitLine(nativeFirst.output, lines))
            assertEquals("locked", awaitLine(nativeFirst.output, lines))
            val start = System.nanoTime()
            withFileLock(lock.toString()) { }
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertTrue(elapsedMillis >= 1_000, "JVM acquired a Native-held lock too early: ${elapsedMillis}ms")
            assertTrue(nativeFirst.process.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, nativeFirst.process.exitValue())

            lateinit var acquired: Future<String>
            lateinit var nativeSecond: NativeProcess
            withFileLock(lock.toString()) {
                nativeSecond = startNative(lock, holdSeconds = 1).also(processes::add)
                assertEquals("ready", awaitLine(nativeSecond.output, lines))
                nativeSecond.signalGo()
                assertEquals("attempting", awaitLine(nativeSecond.output, lines))
                acquired = lines.submit<String> { nativeSecond.output.readLine() }
                Thread.sleep(300)
                assertFalse(acquired.isDone, "Native acquired a JVM-held lock")
            }
            // Leaving the block releases the JVM lock, so the matching Native executable can now
            // acquire it and announce success on the same process-owned lock file.
            assertEquals("locked", acquired.get(5, TimeUnit.SECONDS))
            assertTrue(nativeSecond.process.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, nativeSecond.process.exitValue())
        } finally {
            processes.filter { it.process.isAlive }.forEach { it.process.destroyForcibly() }
            lines.shutdownNow()
        }
    }

    private fun startNative(lock: Path, holdSeconds: Int): NativeProcess {
        val process = ProcessBuilder(holder, lock.toString(), holdSeconds.toString()).start()
        return NativeProcess(process, process.inputStream.bufferedReader(), process.outputStream.bufferedWriter())
    }

    private fun awaitLine(output: BufferedReader, executor: ExecutorService): String =
        executor.submit<String> { output.readLine() }.get(15, TimeUnit.SECONDS)

    private data class NativeProcess(
        val process: Process,
        val output: BufferedReader,
        val input: BufferedWriter,
    ) {
        fun signalGo() {
            input.write("go\n")
            input.flush()
        }
    }
}
