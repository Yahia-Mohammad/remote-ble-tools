package dev.warsha.remoteble.tools.integration

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance

/**
 * The packaged CLI against an agent serving `wss://` behind a self-signed identity, which RemoteBLE
 * 0.14 introduced and made the default wherever the network can reach an agent. Trust comes only
 * from the pinned fingerprint: no certificate authority vouches for the agent.
 */
@Tag("live-agent")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveTlsAgentTest {

    @BeforeAll fun startAgent() {
        assumeTrue(LiveAgent.available, "set -Dremoteble.agent.jar=<path> to run the live-agent suite")
        LiveAgent.ensureTlsStarted()
    }

    private fun cli(clientId: String) = CliRunner(clientId = clientId, endpoint = LiveAgent.tlsEndpoint)

    @Test fun `a pinned agent is reached over wss and answers status and scan`() {
        val runner = cli("acceptance-tls-pinned")
        val status = runner.run("--fingerprint", LiveAgent.tlsFingerprint, "--json", "agent", "status")
        assertEquals(0, status.exitCode, "$status")
        assertContains(status.stdout, "protocolVersion")

        val scan = runner.run("--fingerprint", LiveAgent.tlsFingerprint, "--json", "scan", "--duration", "2s")
        assertEquals(0, scan.exitCode, "$scan")
        assertContains(scan.stdout, LiveAgent.HRM_NAME)
    }

    @Test fun `the pin can come from configuration in upper case`() {
        val runner = cli("acceptance-tls-config").withConfig(
            """
            schemaVersion: 1
            agent:
              endpoint: "${LiveAgent.tlsEndpoint}"
              fingerprint: "${LiveAgent.tlsFingerprint.uppercase()}"
            """.trimIndent(),
        )
        val status = runner.run("--json", "agent", "status")
        assertEquals(0, status.exitCode, "$status")
    }

    @Test fun `a different pin is refused as an authentication failure without retrying`() {
        val wrong = "sha256:" + "0".repeat(64)
        val started = System.nanoTime()
        val result = cli("acceptance-tls-wrong").run("--fingerprint", wrong, "--json", "agent", "status")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(4, result.exitCode, "$result")
        assertContains(result.stderr, "Agent identity mismatch")
        assertContains(result.stderr, LiveAgent.tlsFingerprint)
        // Retrying a mismatch would spend the reconnect backoff and the operation deadline.
        assert(elapsedMs < 15_000) { "a pin mismatch took $elapsedMs ms; it must not be retried" }
    }

    @Test fun `an unpinned wss endpoint fails and names the fingerprint as the fix`() {
        val result = cli("acceptance-tls-unpinned").run("--json", "agent", "status")
        assertEquals(9, result.exitCode, "$result")
        assertContains(result.stderr, "--fingerprint")
    }

    @Test fun `a fingerprint on a cleartext endpoint is a usage error`() {
        val result = CliRunner(clientId = "acceptance-tls-cleartext")
            .run("--fingerprint", LiveAgent.tlsFingerprint, "--json", "agent", "status")
        assertEquals(2, result.exitCode, "$result")
        assertFalse(result.stdout.contains("protocolVersion"), "$result")
    }
}
