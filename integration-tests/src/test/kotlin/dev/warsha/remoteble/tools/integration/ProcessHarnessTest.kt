package dev.warsha.remoteble.tools.integration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class ProcessHarnessTest {
    @Test fun `plain process accepts interactive input and EOF`() {
        ProcessHarness.start(listOf("sh", "-c", "IFS= read -r line; printf 'seen:%s\\n' \"\$line\""))
            .use { process ->
                process.sendLine("pipe")
                assertTrue(process.awaitLine { it == "seen:pipe" }.isNotBlank())
                process.closeInput()
                assertEquals(0, process.awaitExit())
            }
    }

    @Test fun `PTY process owns a terminal and relays input`() {
        assumeTrue(ProcessHarness.ptyAvailable, "script(1) is required for the Unix PTY test")
        ProcessHarness.start(
            listOf("sh", "-c", "test -t 0 && IFS= read -r line && printf 'terminal:%s\\n' \"\$line\""),
            terminal = true,
        ).use { process ->
            process.sendLine("pty")
            process.awaitLine { it.contains("terminal:pty") }
            process.closeInput()
            assertEquals(0, process.awaitExit(), process.diagnostics())
        }
    }
}
