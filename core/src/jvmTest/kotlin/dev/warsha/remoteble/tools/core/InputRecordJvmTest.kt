package dev.warsha.remoteble.tools.core

import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** JVM half of the shared record-boundary contract; see the Native test of the same name. */
class InputRecordJvmTest {
    @Test fun `records within the limit are returned without their terminator`() {
        val reader = StringReader("first\nsecond\n")
        assertEquals("first", readLineFrom(reader, 16))
        assertEquals("second", readLineFrom(reader, 16))
        assertNull(readLineFrom(reader, 16))
    }

    @Test fun `a record of exactly the limit is accepted`() {
        val reader = StringReader("${"a".repeat(8)}\n")
        assertEquals("a".repeat(8), readLineFrom(reader, 8))
    }

    @Test fun `an over-long record is rejected and the next record still parses`() {
        val reader = StringReader("${"a".repeat(40)}\n{\"ok\":true}\n")
        val failure = assertFailsWith<CliFailure> { readLineFrom(reader, 8) }
        assertEquals(ExitCode.USAGE, failure.exitCode)
        assertEquals("{\"ok\":true}", readLineFrom(reader, 16))
    }

    @Test fun `a final record without a trailing newline is complete`() {
        assertEquals("tail", readLineFrom(StringReader("tail"), 16))
    }

    @Test fun `carriage returns are trimmed`() {
        assertEquals("value", readLineFrom(StringReader("value\r\n"), 16))
    }
}
