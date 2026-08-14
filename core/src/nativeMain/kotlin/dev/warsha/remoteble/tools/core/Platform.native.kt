@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package dev.warsha.remoteble.tools.core

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import dev.warsha.remoteble.tools.core.recordlock.rble_lock_is_contended
import dev.warsha.remoteble.tools.core.recordlock.rble_install_sigint_handler
import dev.warsha.remoteble.tools.core.recordlock.rble_process_lock_acquire
import dev.warsha.remoteble.tools.core.recordlock.rble_process_lock_release
import dev.warsha.remoteble.tools.core.recordlock.rble_restore_sigint_handler
import dev.warsha.remoteble.tools.core.recordlock.rble_take_interrupt
import dev.warsha.remoteble.tools.core.recordlock.rble_try_write_lock
import dev.warsha.remoteble.tools.core.recordlock.rble_unlock
import platform.posix.F_OK
import platform.posix.EINTR
import platform.posix.FILE
import platform.posix.EEXIST
import platform.posix.O_CREAT
import platform.posix.O_RDWR
import platform.posix.SEEK_END
import platform.posix.SIGPIPE
import platform.posix.SIG_IGN
import platform.posix.access
import platform.posix.close
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.feof
import platform.posix.fileno
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.mkdir
import platform.posix.chmod
import platform.posix.fread
import platform.posix.fgets
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.mkdir
import platform.posix.open
import platform.posix.opendir
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.POLLIN
import platform.posix.readdir
import platform.posix.rewind
import platform.posix.signal
import platform.posix.rename
import platform.posix.stdin
import platform.posix.stdout
import platform.posix.stderr
import platform.posix.usleep
import platform.posix.errno
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A downstream pipe closing must be observed as a write failure, not terminate the CLI. */
@OptIn(ExperimentalForeignApi::class)
private val ignoreSigpipe: Boolean = run {
    signal(SIGPIPE, SIG_IGN)
    true
}

@OptIn(ExperimentalForeignApi::class)
actual fun environmentVariable(name: String): String? = getenv(name)?.toKString()

actual fun homeDirectory(): String = environmentVariable("HOME") ?: "."
actual fun isApplePlatform(): Boolean = Platform.osFamily == OsFamily.MACOSX

@OptIn(ExperimentalForeignApi::class)
actual fun fileExists(path: String): Boolean = access(path, F_OK) == 0

@OptIn(ExperimentalForeignApi::class)
actual fun readFileText(path: String): String {
    val file = fopen(path, "rb") ?: error("Cannot open $path")
    try {
        check(fseek(file, 0, SEEK_END) == 0) { "Cannot seek $path" }
        val length = ftell(file)
        check(length >= 0) { "Cannot determine size of $path" }
        rewind(file)
        val bytes = ByteArray(length.toInt())
        if (bytes.isNotEmpty()) {
            bytes.usePinned { pinned ->
                val count = fread(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file)
                check(count.toLong() == bytes.size.toLong()) { "Cannot read $path" }
            }
        }
        return bytes.decodeToString()
    } finally {
        fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun forEachFileLine(path: String, maximumLineBytes: Int, consume: (String) -> Unit) {
    val file = fopen(path, "rb") ?: error("Cannot open $path")
    try {
        while (true) {
            val line = readBoundedLineFrom(file, maximumLineBytes, "audit record") ?: break
            consume(line)
        }
    } finally {
        fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun readStandardInput(maximumBytes: Int): ByteArray {
    val buffer = ByteArray(maximumBytes)
    val count = buffer.usePinned { pinned ->
        fread(pinned.addressOf(0), 1.convert(), maximumBytes.convert(), stdin).toInt()
    }
    return buffer.copyOf(count)
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun readStandardInputLine(maximumBytes: Int): String? {
    // stdin is a blocking C FILE. Poll in short intervals before calling fgets so coroutine
    // cancellation can finish a session/shell promptly while an interactive terminal is idle.
    while (true) {
        if (consumeInterrupt()) return null
        currentCoroutineContext().ensureActive()
        val ready = memScoped {
            val descriptors = allocArray<pollfd>(1)
            descriptors.pointed.fd = fileno(stdin)
            descriptors.pointed.events = POLLIN.convert()
            descriptors.pointed.revents = 0
            poll(descriptors, 1u, 100)
        }
        if (ready > 0) return readLineFrom(stdin!!, maximumBytes)
        if (ready < 0 && consumeInterrupt()) return null
        if (ready < 0 && errno == EINTR) continue
        if (ready < 0) throw IllegalStateException("unable to poll standard input")
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun installInterruptHandler(): Boolean = rble_install_sigint_handler() != 0

@OptIn(ExperimentalForeignApi::class)
actual fun restoreInterruptHandler() = rble_restore_sigint_handler()

@OptIn(ExperimentalForeignApi::class)
actual fun consumeInterrupt(): Boolean = rble_take_interrupt() != 0

/**
 * Reads one bounded record from [file].
 *
 * fgets stops at the buffer bound as readily as at a newline and reports neither, so an over-long
 * record used to come back as a silently truncated "line" whose remainder was then parsed as the
 * next record. Sizing the buffer one byte past the limit makes the two cases separable: a record
 * within the limit always arrives with its newline, so a chunk without one is either the final
 * unterminated record (EOF) or a record that is too long.
 *
 * Takes the stream as a parameter so the boundary behaviour is testable against a real file.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun readLineFrom(file: CPointer<FILE>, maximumBytes: Int): String? {
    return try {
        readBoundedLineFrom(file, maximumBytes, "session input record")
    } catch (error: IllegalArgumentException) {
        throw CliFailure(ExitCode.USAGE, error.message ?: "session input record is invalid", error)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readBoundedLineFrom(file: CPointer<FILE>, maximumBytes: Int, recordName: String): String? {
    val buffer = ByteArray(maximumBytes + 2)
    val chunk = buffer.usePinned { pinned -> fgets(pinned.addressOf(0), buffer.size, file)?.toKString() }
        ?: return null
    if (!chunk.endsWith('\n') && feof(file) == 0) {
        discardRestOfLine(file)
        throw IllegalArgumentException("$recordName exceeds $maximumBytes bytes")
    }
    val line = chunk.trimEnd('\n', '\r')
    if (line.encodeToByteArray().size > maximumBytes) {
        throw IllegalArgumentException("$recordName exceeds $maximumBytes bytes")
    }
    return line
}

/** Leaves [file] on the next record boundary so one rejected line cannot corrupt the one after it. */
@OptIn(ExperimentalForeignApi::class)
private fun discardRestOfLine(file: CPointer<FILE>) {
    val buffer = ByteArray(4096)
    while (true) {
        val chunk = buffer.usePinned { pinned -> fgets(pinned.addressOf(0), buffer.size, file)?.toKString() }
            ?: return
        if (chunk.endsWith('\n')) return
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun writeStandardOutput(bytes: ByteArray) {
    ignoreSigpipe
    if (bytes.isNotEmpty()) {
        bytes.usePinned { pinned ->
            val written = fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), stdout)
            check(written.toLong() == bytes.size.toLong()) { "standard output is no longer writable" }
        }
    }
    check(fflush(stdout) == 0) { "standard output is no longer writable" }
}

@OptIn(ExperimentalForeignApi::class)
actual fun writeStandardError(bytes: ByteArray) {
    ignoreSigpipe
    if (bytes.isNotEmpty()) {
        bytes.usePinned { pinned ->
            val written = fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), stderr)
            check(written.toLong() == bytes.size.toLong()) { "standard error is no longer writable" }
        }
    }
    check(fflush(stderr) == 0) { "standard error is no longer writable" }
}

@OptIn(ExperimentalForeignApi::class)
actual fun appendFileText(path: String, value: String) = writeAllBytes(path, "ab", value)

@OptIn(ExperimentalForeignApi::class)
actual fun writeFileText(path: String, value: String) = writeAllBytes(path, "wb", value)

/**
 * A short write or a failed flush must surface. The audit layer refuses to dispatch a mutation it
 * could not record, and the rate ledger's atomic replacement assumes its temporary file is whole —
 * both guarantees are only as good as this check, so a full or read-only filesystem has to raise
 * here rather than report a write that never landed.
 */
@OptIn(ExperimentalForeignApi::class)
private fun writeAllBytes(path: String, mode: String, value: String) {
    val file = fopen(path, mode) ?: error("Cannot open $path")
    try {
        val bytes = value.encodeToByteArray()
        if (bytes.isNotEmpty()) {
            bytes.usePinned { pinned ->
                val written = fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file)
                check(written.toLong() == bytes.size.toLong()) { "Cannot write $path" }
            }
        }
        check(fflush(file) == 0) { "Cannot flush $path" }
    } finally { fclose(file) }
}

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
actual fun writeFileTextAtomically(path: String, value: String) {
    ensureDirectory(path.substringBeforeLast('/'))
    val temporary = "${path}.tmp-${kotlin.uuid.Uuid.random()}"
    try {
        writeFileText(temporary, value)
        setOwnerOnly(temporary, directory = false)
        check(rename(temporary, path) == 0) { "Cannot replace $path" }
        setOwnerOnly(path, directory = false)
    } finally {
        if (fileExists(temporary)) platform.posix.unlink(temporary)
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun writeFileTextIfAbsent(path: String, value: String): Boolean {
    val file = fopen(path, "wx") ?: return false
    try {
        val bytes = value.encodeToByteArray()
        if (bytes.isNotEmpty()) bytes.usePinned { pinned -> fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file) }
        check(fflush(file) == 0) { "Cannot flush $path" }
        return true
    } finally { fclose(file) }
}

@OptIn(ExperimentalForeignApi::class)
actual fun ensureDirectory(path: String) {
    val normalized = path.trimEnd('/')
    if (normalized.isEmpty() || normalized == ".") return
    var current = if (normalized.startsWith('/')) "/" else ""
    normalized.split('/').filter { it.isNotEmpty() }.forEach { part ->
        current = if (current.isEmpty() || current == "/") "$current$part" else "$current/$part"
        if (mkdir(current, 0x1c0u) != 0 && errno != EEXIST) error("Cannot create directory $current")
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun listDirectory(path: String): List<String> {
    val directory = opendir(path) ?: error("Cannot list $path")
    try {
        val result = mutableListOf<String>()
        while (true) {
            val entry = readdir(directory) ?: break
            val name = entry.pointed.d_name.toKString()
            if (name != "." && name != "..") result += "$path/$name"
        }
        return result
    } finally { closedir(directory) }
}
@OptIn(ExperimentalForeignApi::class)
actual fun deleteFile(path: String) { platform.posix.unlink(path) }
@OptIn(ExperimentalForeignApi::class)
actual fun setOwnerOnly(path: String, directory: Boolean) {
    chmod(path, if (directory) 0x1c0u else 0x180u)
}

@OptIn(ExperimentalForeignApi::class)
actual fun <T> withFileLock(path: String, block: () -> T): T {
    ensureDirectory(path.substringBeforeLast('/'))
    // The fcntl record lock below only excludes *other processes*. Threads inside this one share
    // its lock ownership, so they must be serialized here first — otherwise concurrent session jobs
    // would all enter the critical section, and the first to finish would release the lock for
    // everyone by closing its descriptor. See the note in recordlock.h.
    check(rble_process_lock_acquire() == 0) { "Cannot acquire in-process state lock" }
    try {
        val descriptor = open(path, O_CREAT or O_RDWR, 0x180u)
        check(descriptor >= 0) { "Cannot open lock $path" }
        var locked = false
        try {
            repeat(500) {
                if (rble_try_write_lock(descriptor) == 0) {
                    locked = true
                    return block()
                }
                if (rble_lock_is_contended(errno) == 0) {
                    throw CliFailure(ExitCode.FAILURE, "Cannot acquire local state lock")
                }
                usleep(10_000u)
            }
            throw CliFailure(ExitCode.FAILURE, "timed out acquiring local state lock")
        } finally {
            if (locked) rble_unlock(descriptor)
            close(descriptor)
        }
    } finally {
        rble_process_lock_release()
    }
}
