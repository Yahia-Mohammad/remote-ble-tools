package dev.warsha.remoteble.tools.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigTest {
    @Test fun `client ids accept copied safe values and reject malformed values`() {
        assertEquals("rble-01234567", validateClientIdentity("rble-01234567"))
        assertEquals(ExitCode.USAGE, assertFailsWith<CliFailure> { validateClientIdentity("short") }.exitCode)
        assertEquals(ExitCode.USAGE, assertFailsWith<CliFailure> { validateClientIdentity("bad id 123") }.exitCode)
    }

    @Test fun `profile overrides only declared values`() {
        val config = FileConfig(
            agent = AgentConfig(endpoint = "ws://base", tokenEnvironmentVariable = "BASE_TOKEN"),
            profiles = mapOf("lab" to ProfileConfig(agent = AgentProfileConfig(endpoint = "ws://lab"))),
        )
        val resolved = config.withProfile("lab")
        assertEquals("ws://lab", resolved.agent.endpoint)
        assertEquals("BASE_TOKEN", resolved.agent.tokenEnvironmentVariable)
    }

    @Test fun `unknown profile is a usage failure`() {
        assertEquals(ExitCode.USAGE, assertFailsWith<CliFailure> { FileConfig().withProfile("missing") }.exitCode)
    }

    @Test fun `local write rules require an exact endpoint target type and payload match`() {
        val policy = PolicyConfig(
            maximumWriteBytes = 64,
            writeRules = listOf(
                WriteRuleConfig("wss://agent.example/agent", "device-1", "180f", "2a19", 2, setOf(true)),
            ),
        )
        assertEquals(true, policy.allowsWrite("wss://agent.example/agent", "device-1", "180F", "2A19", 2, true))
        assertEquals(false, policy.allowsWrite("ws://other", "device-1", "180f", "2a19", 1, true))
        assertEquals(false, policy.allowsWrite("wss://agent.example/agent", "device-1", "180f", "2a19", 3, true))
        assertEquals(false, policy.allowsWrite("wss://agent.example/agent", "device-1", "180f", "2a19", 1, false))
    }

    /** A window of zero or less prunes every ledger entry, which would allow unlimited writes. */
    @Test fun `a non-positive write rate window is rejected`() {
        listOf("0s", "-5s", "PT0S").forEach { window ->
            val config = FileConfig(
                agent = AgentConfig(endpoint = "ws://agent"),
                policy = PolicyConfig(writeRateWindow = window),
            )
            assertEquals(
                ExitCode.USAGE,
                assertFailsWith<CliFailure>("'$window' was accepted") { ConfigLoader.validate(config) }.exitCode,
            )
        }
        ConfigLoader.validate(FileConfig(agent = AgentConfig(endpoint = "ws://agent"), policy = PolicyConfig(writeRateWindow = "30s")))
    }

    @Test fun `writable mode still denies an empty local allowlist`() {
        val policy = PolicyConfig(readOnly = false, writeRules = emptyList())
        assertEquals(false, policy.allowsWrite("wss://agent.example/agent", "device-1", "180f", "2a19", 1, true))
    }

    @Test fun `profile policy resolves before write policy is evaluated`() {
        val baseRule = WriteRuleConfig("wss://base", "base", "180f", "2a19", 1, setOf(true))
        val profileRule = WriteRuleConfig("wss://lab", "lab", "180d", "2a37", 2, setOf(false))
        val resolved = FileConfig(
            policy = PolicyConfig(readOnly = true, writeRules = listOf(baseRule)),
            profiles = mapOf("lab" to ProfileConfig(policy = PolicyProfileConfig(readOnly = false, writeRules = listOf(profileRule)))),
        ).withProfile("lab").policy

        assertEquals(false, resolved.readOnly)
        assertEquals(true, resolved.allowsWrite("wss://lab", "lab", "180d", "2a37", 2, false))
        assertEquals(false, resolved.allowsWrite("wss://base", "base", "180f", "2a19", 1, true))
    }

    @Test fun `a fingerprint is normalized to the form agents print`() {
        val pin = "SHA256:" + "AB".repeat(32)
        val resolved = FileConfig(agent = AgentConfig(endpoint = "wss://agent.test/agent", fingerprint = pin)).resolve()
        assertEquals("sha256:" + "ab".repeat(32), resolved.agent.fingerprint)
    }

    @Test fun `a malformed fingerprint is a usage failure`() {
        val config = FileConfig(agent = AgentConfig(endpoint = "wss://agent.test/agent", fingerprint = "sha256:abc"))
        assertEquals(ExitCode.USAGE, assertFailsWith<CliFailure> { config.resolve() }.exitCode)
        assertEquals(ExitCode.USAGE, assertFailsWith<CliFailure> { ConfigLoader.validate(config) }.exitCode)
    }

    @Test fun `a fingerprint on a cleartext endpoint is refused`() {
        val pin = "sha256:" + "0".repeat(64)
        val config = FileConfig(agent = AgentConfig(endpoint = "wss://agent.test/agent", fingerprint = pin))
        assertEquals(ExitCode.USAGE, assertFailsWith<CliFailure> { config.resolve(endpointOverride = "ws://agent.test/agent") }.exitCode)
        assertEquals(ExitCode.USAGE, assertFailsWith<CliFailure> {
            ConfigLoader.validate(FileConfig(agent = AgentConfig(endpoint = "ws://agent.test/agent", fingerprint = pin)))
        }.exitCode)
    }

    @Test fun `a fingerprint override and a profile fingerprint both apply`() {
        val base = "sha256:" + "1".repeat(64)
        val lab = "sha256:" + "2".repeat(64)
        val config = FileConfig(
            agent = AgentConfig(endpoint = "wss://base/agent", fingerprint = base),
            profiles = mapOf("lab" to ProfileConfig(agent = AgentProfileConfig(endpoint = "wss://lab/agent", fingerprint = lab))),
        )
        assertEquals(lab, config.withProfile("lab").resolve().agent.fingerprint)
        assertEquals(base, config.withProfile("lab").copy(agent = config.agent).resolve().agent.fingerprint)
        val override = "sha256:" + "3".repeat(64)
        assertEquals(override, config.resolve(fingerprintOverride = override).agent.fingerprint)
    }

    @Test fun `new automatic identities are opaque random identifiers`() {
        assertEquals(true, isAutoGeneratedClientIdentity(defaultClientIdentity()))
    }
}
