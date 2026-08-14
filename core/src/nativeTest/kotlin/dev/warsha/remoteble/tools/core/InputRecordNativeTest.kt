@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.warsha.remoteble.tools.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import platform.posix.fclose
import platform.posix.fopen

/**
 * Native half of the shared record-boundary contract, asserting the same cases as the JVM test.
 *
 * `fgets` stops at the buffer bound as readily as at a newline and reports neither, so an over-long
 * record used to be returned as a silently truncated line whose remainder was then parsed as the
 * next record — where the JVM raised a usage error. These read from a real file because that is the
 * only way to exercise the actual `fgets` boundary behaviour.
 */
class InputRecordNativeTest {
    private val directory = (environmentVariable("TMPDIR")?.trimEnd('/') ?: "/tmp") + "/remoteble-native-records"

    private fun open(name: String, contents: String) = run {
        ensureDirectory(directory)
        val path = "$directory/$name"
        writeFileText(path, contents)
        fopen(path, "rb") ?: error("cannot open $path")
    }

    @AfterTest fun cleanUp() {
        runCatching { listDirectory(directory).forEach(::deleteFile) }
    }

    @Test fun `records within the limit are returned without their terminator`() {
        val file = open("plain", "first\nsecond\n")
        try {
            assertEquals("first", readLineFrom(file, 16))
            assertEquals("second", readLineFrom(file, 16))
            assertNull(readLineFrom(file, 16))
        } finally { fclose(file) }
    }

    @Test fun `a record of exactly the limit is accepted`() {
        val file = open("exact", "${"a".repeat(8)}\n")
        try {
            assertEquals("a".repeat(8), readLineFrom(file, 8))
        } finally { fclose(file) }
    }

    @Test fun `an over-long record is rejected and the next record still parses`() {
        val file = open("oversize", "${"a".repeat(40)}\n{\"ok\":true}\n")
        try {
            val failure = assertFailsWith<CliFailure> { readLineFrom(file, 8) }
            assertEquals(ExitCode.USAGE, failure.exitCode)
            assertEquals("{\"ok\":true}", readLineFrom(file, 16))
        } finally { fclose(file) }
    }

    @Test fun `a final record without a trailing newline is complete`() {
        val file = open("tail", "tail")
        try {
            assertEquals("tail", readLineFrom(file, 16))
        } finally { fclose(file) }
    }

    @Test fun `carriage returns are trimmed`() {
        val file = open("crlf", "value\r\n")
        try {
            assertEquals("value", readLineFrom(file, 16))
        } finally { fclose(file) }
    }
}
