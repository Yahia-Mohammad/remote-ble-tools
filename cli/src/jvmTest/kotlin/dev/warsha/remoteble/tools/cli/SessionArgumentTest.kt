package dev.warsha.remoteble.tools.cli

import dev.warsha.remoteble.tools.core.CliFailure
import dev.warsha.remoteble.tools.core.ExitCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Session arguments are typed by `session-input-v1.json`, and the accessors have to enforce that.
 * The case that matters most is JSON `null`: `JsonNull.content` is the string `"null"`, so a lenient
 * read silently turned `{"handle": null}` into a device handle named `null` and sent it to the agent
 * instead of rejecting the record.
 */
class SessionArgumentTest {
    private fun arguments(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    @Test fun `json null is rejected rather than read as the text null`() {
        val arguments = arguments("""{"handle": null}""")
        val failure = assertFailsWith<CliFailure> { arguments.stringArgument("read", "handle") }
        assertEquals(ExitCode.USAGE, failure.exitCode)
        assertEquals("read argument 'handle' must be a string", failure.message)
    }

    @Test fun `an absent key stays absent`() {
        val arguments = arguments("""{"serviceUuid": "180f"}""")
        assertNull(arguments.stringArgument("read", "handle"))
        assertNull(arguments.intArgument("scan", "count"))
        assertNull(arguments.booleanArgument("observe", "unbounded"))
    }

    @Test fun `well typed values are returned unchanged`() {
        val arguments = arguments("""{"handle": "dev-1", "count": 7, "streamId": 12, "unbounded": true}""")
        assertEquals("dev-1", arguments.stringArgument("observe", "handle"))
        assertEquals(7, arguments.intArgument("observe", "count"))
        assertEquals(12L, arguments.integerArgument("stream.stop", "streamId"))
        assertEquals(true, arguments.booleanArgument("observe", "unbounded"))
    }

    @Test fun `numbers and booleans are not accepted where a string is typed`() {
        assertFailsWith<CliFailure> { arguments("""{"handle": 5}""").stringArgument("read", "handle") }
        assertFailsWith<CliFailure> { arguments("""{"handle": true}""").stringArgument("read", "handle") }
    }

    @Test fun `quoted numbers are not accepted where an integer is typed`() {
        val failure = assertFailsWith<CliFailure> { arguments("""{"count": "7"}""").intArgument("scan", "count") }
        assertEquals(ExitCode.USAGE, failure.exitCode)
    }

    @Test fun `nested structures are a usage error rather than an internal failure`() {
        val failure = assertFailsWith<CliFailure> { arguments("""{"handle": {"nested": 1}}""").stringArgument("read", "handle") }
        assertEquals(ExitCode.USAGE, failure.exitCode)
    }

    @Test fun `out of range integers are refused before narrowing`() {
        val failure = assertFailsWith<CliFailure> { arguments("""{"count": 4294967296}""").intArgument("scan", "count") }
        assertEquals(ExitCode.USAGE, failure.exitCode)
    }
}
