package dev.warsha.remoteble.tools.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Strict key handling is the YAML library's behavior, not this project's, so nothing here would
 * notice it changing. A permissive parser would accept a typo'd key and silently apply the default
 * instead — the configuration would look honored and would not be, which is the failure a user is
 * least able to diagnose. Pin it so a dependency bump has to argue with a test.
 */
class ConfigLoaderStrictnessTest {
    @Test fun `a missing explicitly selected file is a usage failure`() {
        val path = Files.createTempDirectory("remoteble-config-missing").resolve("config.yaml").toString()

        val failure = assertFailsWith<CliFailure> { ConfigLoader.load(path) }

        assertEquals(ExitCode.USAGE, failure.exitCode)
        assertTrue(failure.message.contains(path), "the failure must name the missing file: ${failure.message}")
    }

    @Test fun `an unknown key is a usage failure, not a silent default`() {
        val path = write(
            """
            schemaVersion: 1
            agent:
              endpoint: "ws://agent.test"
            typoedKey: 3
            """.trimIndent(),
        )

        val failure = assertFailsWith<CliFailure> { ConfigLoader.load(path) }

        assertEquals(ExitCode.USAGE, failure.exitCode)
        assertTrue(failure.message.contains(path), "the failure must name the file it read: ${failure.message}")
    }

    @Test fun `a misspelled key inside a nested section is refused too`() {
        val path = write(
            """
            schemaVersion: 1
            agent:
              endpoint: "ws://agent.test"
              endpointTypo: "ws://elsewhere.test"
            """.trimIndent(),
        )

        assertEquals(ExitCode.USAGE, assertFailsWith<CliFailure> { ConfigLoader.load(path) }.exitCode)
    }

    @Test fun `the same configuration without the stray key loads`() {
        val path = write(
            """
            schemaVersion: 1
            agent:
              endpoint: "ws://agent.test"
            """.trimIndent(),
        )

        val (config, source) = ConfigLoader.load(path)

        assertEquals("ws://agent.test", config.agent.endpoint)
        assertEquals(path, source)
    }

    private fun write(yaml: String): String {
        val file = Files.createTempDirectory("remoteble-config-strictness").resolve("config.yaml")
        Files.writeString(file, yaml + "\n")
        return file.toString()
    }
}
