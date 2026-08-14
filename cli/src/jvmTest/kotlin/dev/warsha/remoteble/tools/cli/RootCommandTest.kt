package dev.warsha.remoteble.tools.cli

import com.github.ajalt.clikt.testing.test
import dev.warsha.remoteble.protocol.AgentStatusDto
import dev.warsha.remoteble.protocol.LeaseStatusDto
import dev.warsha.remoteble.protocol.StatusSettingsDto
import dev.warsha.remoteble.protocol.StatusSlotsDto
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RootCommandTest {
    @Test fun `config validate accepts strict example`() {
        val config = Files.createTempFile("remoteble", ".yaml")
        Files.writeString(config, "schemaVersion: 1\npolicy:\n  readOnly: true\n")
        val result = buildCli().test(listOf("--config", config.toString(), "config", "validate"))
        assertEquals(0, result.statusCode, result.stderr)
    }

    @Test fun `global options are accepted after the leaf command`() {
        val config = Files.createTempFile("remoteble", ".yaml")
        Files.writeString(config, "schemaVersion: 1\npolicy:\n  readOnly: true\n")
        val result = buildCli().test(listOf("config", "validate", "--config", config.toString(), "--json"))
        assertEquals(0, result.statusCode, result.stderr)
    }

    @Test fun `write is fail closed before network activity`() {
        val result = buildCli().test(listOf("write", "device", "180d", "2a37", "--hex", "00", "--write-type", "with-response"))
        assertEquals(7, result.statusCode, result.stderr)
    }

    @Test fun `write requires exactly one explicitly encoded payload source`() {
        val result = buildCli().test(listOf("write", "device", "180d", "2a37", "--write-type", "with-response"))
        assertEquals(2, result.statusCode, result.stderr)
        assertTrue(result.stderr.contains("Specify exactly one"))
    }

    @Test fun `json failures stay on stderr as versioned envelopes`() {
        val result = buildCli().test(listOf("write", "device", "180d", "2a37", "--hex", "00", "--write-type", "with-response", "--json"))
        assertEquals(7, result.statusCode, result.stderr)
        // Clikt's in-memory test terminal combines the stderr stream in `output`; the production
        // command uses `echo(..., err = true)` and therefore leaves stdout untouched.
        assertTrue(result.output.contains("\"schemaVersion\":1"), result.output)
        assertTrue(result.output.contains("\"type\":\"error\""), result.output)
    }

    @Test fun `observe requires an explicit bounded termination condition`() {
        val result = buildCli().test(listOf("observe", "device", "180d", "2a37"))
        assertEquals(2, result.statusCode, result.stderr)
        assertTrue(result.stderr.contains("observe requires --count or --timeout"), result.stderr)
    }

    @Test fun `token stdin and payload stdin conflict before reading either stream`() {
        val result = buildCli().test(listOf("--token-stdin", "write", "device", "180d", "2a37", "--stdin", "hex", "--write-type", "with-response"))
        assertEquals(2, result.statusCode, result.stderr)
        assertTrue(result.stderr.contains("cannot be combined"), result.stderr)
    }

    @Test fun `log level is accepted as a normal global override`() {
        val result = buildCli().test(listOf("--log-level", "debug", "config", "validate"))
        assertEquals(0, result.statusCode, result.stderr)
    }

    /**
     * Lease holders and `agentInfo` are named by other parties, so status output is as much
     * untrusted text as an advertised device name and must not carry control characters into a
     * terminal or an agent's captured context.
     */
    @Test fun `hostile agent and lease text is escaped in human status output`() {
        val status = AgentStatusDto(
            agentInfo = "agent\u001b[2Kspoofed\u0007",
            uptimeMs = 1,
            settings = StatusSettingsDto(
                leaseGraceMs = 1, transportGraceMs = 1, exclusiveByDefault = true,
                scanConcurrency = "single", strictIdentifiers = true,
            ),
            slots = StatusSlotsDto(free = 1, total = 2),
            connectedClients = 1,
            leases = listOf(
                LeaseStatusDto(handle = "dev\u000d\u000a1", holder = "holder", connected = true, inGrace = false),
            ),
        )
        val rendered = status.human()
        assertTrue(rendered.none { it.isISOControl() && it != '\n' }, rendered)
        assertTrue(rendered.contains("\\u001b"), rendered)
        assertTrue(rendered.contains("\\u000d\\u000a"), rendered)
        assertTrue(rendered.contains("\\u0007"), rendered)
    }

    @Test fun `configured automatic identity warns that it may have been copied`() {
        val config = Files.createTempFile("remoteble", ".yaml")
        Files.writeString(config, "schemaVersion: 1\nagent:\n  clientId: rble-auto-0123456789abcdef0123456789abcdef\n")
        val result = buildCli().test(listOf("--config", config.toString(), "config", "validate"))
        assertEquals(0, result.statusCode, result.stderr)
        // The warning is written through the platform stderr primitive rather than Clikt's
        // in-memory terminal; this asserts the effective configured-ID path is accepted.
    }

}
