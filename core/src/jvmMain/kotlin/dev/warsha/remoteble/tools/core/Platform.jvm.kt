package dev.warsha.remoteble.tools.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.StandardCopyOption
import java.nio.channels.FileChannel
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

private val stdinReader by lazy { System.`in`.bufferedReader() }
private val interruptRequested = AtomicBoolean(false)
private val interruptHandlerLock = Any()
private var previousInterruptHandler: sun.misc.SignalHandler? = null
private var interruptHandlerInstalled = false
private data class StdinLineRequest(val maximumBytes: Int, val result: CompletableDeferred<String?>)
private val stdinLineRequests = LinkedBlockingQueue<StdinLineRequest>()
private val stdinLineReaderStarted = AtomicBoolean(false)
private val stdinLineReader = Thread({
    while (true) {
        val request = stdinLineRequests.take()
        val result = runCatching { readLineFrom(stdinReader, request.maximumBytes) }
        if (!request.result.isCancelled) {
            result.fold(request.result::complete, request.result::completeExceptionally)
        }
    }
}, "remoteble-stdin-reader").apply { isDaemon = true }

actual fun environmentVariable(name: String): String? = System.getenv(name)

// Respect HOME when a caller intentionally isolates local state (CI, sandboxes, skill installs).
// Java's user.home otherwise remains the parent process's home even after ProcessBuilder overrides HOME.
actual fun homeDirectory(): String = System.getenv("HOME")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home")
actual fun workingDirectory(): String = System.getProperty("user.dir")
actual fun isApplePlatform(): Boolean = System.getProperty("os.name").contains("mac", ignoreCase = true)

actual fun fileExists(path: String): Boolean = Files.exists(Path.of(path))
actual fun isDirectory(path: String): Boolean = Files.isDirectory(Path.of(path))

actual fun readFileText(path: String): String = Files.readString(Path.of(path))

actual fun forEachFileLine(path: String, maximumLineBytes: Int, consume: (String) -> Unit) {
    Files.newBufferedReader(Path.of(path)).use { reader ->
        while (true) {
            val line = readBoundedLineFrom(reader, maximumLineBytes, "audit record") ?: break
            consume(line)
        }
    }
}

actual fun readStandardInput(maximumBytes: Int): ByteArray = System.`in`.readNBytes(maximumBytes)

actual suspend fun readStandardInputLine(maximumBytes: Int): String? {
    if (stdinLineReaderStarted.compareAndSet(false, true)) stdinLineReader.start()
    val result = CompletableDeferred<String?>()
    stdinLineRequests.put(StdinLineRequest(maximumBytes, result))
    try {
        while (!result.isCompleted) {
            if (consumeInterrupt()) {
                result.cancel()
                return null
            }
            delay(50)
        }
        return result.await()
    } finally {
        // The worker is the only code that reads stdin. A cancelled session returns immediately;
        // when input eventually arrives, it discards this abandoned request before serving the
        // next session rather than racing a second reader against the same terminal.
        if (!result.isCompleted) result.cancel()
    }
}

actual fun installInterruptHandler(): Boolean = synchronized(interruptHandlerLock) {
    if (interruptHandlerInstalled) return@synchronized true
    runCatching {
        interruptRequested.set(false)
        val signal = sun.misc.Signal("INT")
        previousInterruptHandler = sun.misc.Signal.handle(signal) { interruptRequested.set(true) }
        interruptHandlerInstalled = true
    }.isSuccess
}

actual fun restoreInterruptHandler() = synchronized(interruptHandlerLock) {
    if (!interruptHandlerInstalled) return@synchronized
    val previous = previousInterruptHandler
    runCatching {
        if (previous != null) sun.misc.Signal.handle(sun.misc.Signal("INT"), previous)
    }
    previousInterruptHandler = null
    interruptHandlerInstalled = false
    interruptRequested.set(false)
}

actual fun consumeInterrupt(): Boolean = interruptRequested.getAndSet(false)

/**
 * Reads one bounded record from [reader].
 *
 * Reads incrementally rather than through readLine(): the limit has to be enforced while the record
 * is arriving, not after a caller-controlled line has already been materialized in full. Takes the
 * reader as a parameter so the boundary behaviour is testable, and to keep it in step with the
 * Native implementation, which had silently split an over-long record into two.
 */
internal fun readLineFrom(reader: java.io.Reader, maximumBytes: Int): String? {
    return try {
        readBoundedLineFrom(reader, maximumBytes, "session input record")
    } catch (error: IllegalArgumentException) {
        throw CliFailure(ExitCode.USAGE, error.message ?: "session input record is invalid", error)
    }
}

private fun readBoundedLineFrom(reader: java.io.Reader, maximumBytes: Int, recordName: String): String? {
    val builder = StringBuilder()
    var sawInput = false
    while (true) {
        val value = reader.read()
        if (value < 0) break
        sawInput = true
        val character = value.toChar()
        if (character == '\n') break
        builder.append(character)
        // One char is at least one UTF-8 byte, so this bounds memory ahead of the exact check.
        if (builder.length > maximumBytes) {
            discardRestOfLine(reader)
            throw IllegalArgumentException("$recordName exceeds $maximumBytes bytes")
        }
    }
    if (!sawInput) return null
    val line = builder.toString().trimEnd('\r')
    if (line.encodeToByteArray().size > maximumBytes) {
        throw IllegalArgumentException("$recordName exceeds $maximumBytes bytes")
    }
    return line
}

/** Leaves [reader] on the next record boundary so one rejected line cannot corrupt the one after it. */
private fun discardRestOfLine(reader: java.io.Reader) {
    while (true) {
        val value = reader.read()
        if (value < 0 || value.toChar() == '\n') return
    }
}

actual fun writeStandardOutput(bytes: ByteArray) {
    System.out.write(bytes)
    System.out.flush()
    if (System.out.checkError()) throw java.io.IOException("standard output is no longer writable")
}

actual fun writeStandardError(bytes: ByteArray) {
    System.err.write(bytes)
    System.err.flush()
    if (System.err.checkError()) throw java.io.IOException("standard error is no longer writable")
}

actual fun appendFileText(path: String, value: String) {
    Files.writeString(Path.of(path), value, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
}
actual fun writeFileText(path: String, value: String) { Files.writeString(Path.of(path), value) }
actual fun writeFileTextAtomically(path: String, value: String) {
    val target = Path.of(path)
    ensureDirectory(target.parent.toString())
    val temporary = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
    try {
        Files.writeString(temporary, value, StandardOpenOption.TRUNCATE_EXISTING)
        setOwnerOnly(temporary.toString(), directory = false)
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
        setOwnerOnly(target.toString(), directory = false)
    } finally {
        Files.deleteIfExists(temporary)
    }
}
actual fun writeFileTextIfAbsent(path: String, value: String): Boolean = try {
    Files.writeString(Path.of(path), value, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    true
} catch (_: java.nio.file.FileAlreadyExistsException) {
    false
}

actual fun ensureDirectory(path: String) { Files.createDirectories(Path.of(path)) }
actual fun listDirectory(path: String): List<String> = Files.list(Path.of(path)).use { stream -> stream.map { it.toString() }.toList() }
actual fun deleteFile(path: String) { Files.deleteIfExists(Path.of(path)) }
actual fun movePath(source: String, destination: String) {
    try {
        Files.move(Path.of(source), Path.of(destination), StandardCopyOption.ATOMIC_MOVE)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(Path.of(source), Path.of(destination))
    }
}
actual fun deleteDirectoryRecursively(path: String) {
    val root = Path.of(path)
    if (!Files.exists(root)) return
    Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
}
actual fun setOwnerOnly(path: String, directory: Boolean) {
    runCatching {
        Files.setPosixFilePermissions(Path.of(path), if (directory) {
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
        } else {
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        })
    }
}
actual fun <T> withFileLock(path: String, block: () -> T): T {
    ensureDirectory(Path.of(path).parent.toString())
    return FileChannel.open(Path.of(path), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
        repeat(500) {
            try {
                channel.tryLock()?.use { return block() }
            } catch (_: java.nio.channels.OverlappingFileLockException) {
                // Another CLI invocation in this process owns the lock. Retry like an external process.
            }
            Thread.sleep(10)
        }
        throw CliFailure(ExitCode.FAILURE, "timed out acquiring local state lock")
    }
}
