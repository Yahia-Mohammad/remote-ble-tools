package dev.warsha.remoteble.tools.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.testing.test
import dev.warsha.remoteble.tools.core.ExitCode
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every command and configuration file printed in the public documentation has to be runnable.
 *
 * The Skill is the path by which a coding agent reaches real hardware, so a stale example there is
 * not a typo — it is an instruction an agent will follow. These files were verified by hand once;
 * this is what keeps them verified. It is deliberately the same shape as the archive-permission
 * check in CI: assert the property that matters, not that the text still exists.
 *
 * Commands run in-process against a closed port with a one-second deadline. Anything that reaches
 * the network is expected to fail — what this asserts is only that the parser accepted the line,
 * because [ExitCode.USAGE] is raised before any connection is attempted.
 */
class DocumentedCommandTest {

    @Test fun `every documented command parses`() {
        val config = fastFailConfig()
        val documented = documentedCommands()
        assertTrue(documented.size >= 15, "found only ${documented.size} documented commands; the extractor is probably broken")

        val broken = documented.mapNotNull { (source, command) ->
            val (exitCode, message) = runForExitCode(listOf("--config", config) + command.arguments)
            // 2 is USAGE: an unknown flag, a missing required option, or a bad argument count.
            // Every other code means the parser was satisfied and the failure came later.
            if (exitCode == ExitCode.USAGE.value) "$source: ${command.line}\n      $message" else null
        }
        if (broken.isNotEmpty()) {
            fail("documented commands rejected by the CLI:\n  - " + broken.joinToString("\n  - "))
        }
    }

    @Test fun `every documented configuration validates`() {
        val broken = documentedConfigurations().mapNotNull { (source, yaml) ->
            val file = Files.createTempFile("remoteble-doc", ".yaml")
            // A `policy:` fragment is legitimate documentation; supply the envelope it omits.
            val body = if (yaml.contains("schemaVersion")) yaml else "schemaVersion: 1\n$yaml"
            Files.writeString(file, body)
            val (exitCode, message) = runForExitCode(listOf("--config", file.toString(), "config", "validate"))
            if (exitCode != 0) "$source:\n      $message" else null
        }
        if (broken.isNotEmpty()) {
            fail("documented configurations rejected by config validate:\n  - " + broken.joinToString("\n  - "))
        }
    }

    private data class DocumentedCommand(val line: String, val arguments: List<String>)

    /**
     * Runs an argument list the way `main` does, and reports the exit code a shell would see.
     *
     * Clikt's own `test` helper does not apply the entry point's mapping — a parse failure comes
     * back as Clikt's status rather than [ExitCode.USAGE], which would let a documented command with
     * a nonexistent flag pass this test. Mirroring `main` is what makes the assertion mean what it
     * says.
     */
    private fun runForExitCode(arguments: List<String>): Pair<Int, String> {
        val cli = buildCli()
        return try {
            cli.parse(arguments)
            0 to ""
        } catch (error: CliktError) {
            val code = if (error is UsageError) ExitCode.USAGE.value else error.statusCode
            // Clikt builds a usage message from the context it was thrown in, so `message` is often
            // empty here. Re-run through the test harness only when reporting a failure, where the
            // rendered stderr is what makes the report actionable.
            val rendered = error.message?.takeIf { it.isNotBlank() }
                ?: buildCli().test(arguments).let { it.stderr + it.output }.lines().firstOrNull { it.isNotBlank() }
            code to (rendered?.trim().orEmpty())
        }
    }

    /** Points every command at a closed port with a short deadline so nothing waits on the network. */
    private fun fastFailConfig(): String {
        val port = ServerSocket(0).use { it.localPort }
        val file = Files.createTempFile("remoteble-doc-config", ".yaml")
        Files.writeString(
            file,
            """
            schemaVersion: 1
            agent:
              endpoint: "ws://127.0.0.1:$port/agent"
            defaults:
              operationTimeout: "1s"
              scanDuration: "1s"
            """.trimIndent() + "\n",
        )
        return file.toString()
    }

    private fun documentedCommands(): List<Pair<String, DocumentedCommand>> =
        documentationFiles().flatMap { file ->
            fencedBlocks(file.readText(), setOf("bash", "sh")).flatMap { block ->
                block.lines()
                    .map { it.trim().removePrefix("$ ").trim() }
                    .filter { it.startsWith("remoteble ") }
                    .map { line -> relativePath(file) to DocumentedCommand(line, tokenize(normalize(line)).map(::substitutePlaceholder)) }
            }
        }

    private fun documentedConfigurations(): List<Pair<String, String>> =
        documentationFiles().flatMap { file ->
            fencedBlocks(file.readText(), setOf("yaml", "yml"))
                // `steps:` marks the deferred recipe format, which is a different schema entirely.
                .filter { (it.contains("policy:") || it.contains("agent:")) && !it.contains("steps:") }
                .map { relativePath(file) to it }
        }

    private fun documentationFiles(): List<File> {
        val root = File(System.getProperty("remoteble.repo.root") ?: error("remoteble.repo.root is not set"))
        val roots = listOf(File(root, "skills"), File(root, "docs"))
        return (roots.flatMap { it.walkTopDown().filter { file -> file.isFile && file.extension == "md" } } +
            File(root, "README.md")).filter { it.isFile }.sorted()
    }

    private fun relativePath(file: File): String {
        val root = File(System.getProperty("remoteble.repo.root")!!).absoluteFile
        return file.absoluteFile.relativeTo(root).path
    }

    /** Returns the body of every fenced block whose info string is in [languages]. */
    private fun fencedBlocks(markdown: String, languages: Set<String>): List<String> {
        val blocks = mutableListOf<String>()
        var current: StringBuilder? = null
        markdown.lines().forEach { line ->
            val fence = line.trimStart().removePrefix("```")
            when {
                current != null && line.trimStart().startsWith("```") -> {
                    blocks += current.toString(); current = null
                }
                current != null -> current.appendLine(line)
                line.trimStart().startsWith("```") && fence.trim() in languages -> current = StringBuilder()
            }
        }
        // Documentation wraps long invocations; rejoin them before anything tries to parse one.
        return blocks.map { it.replace("\\\n", " ") }
    }

    /**
     * Reduces a documented invocation to the argument list a shell would pass. Documentation marks
     * optional arguments with brackets and shows redirection and backgrounding; none of that is
     * part of what the CLI parses.
     */
    private fun normalize(line: String): String = line
        .substringBefore(" #")
        .substringBefore(" | ")
        .replace(Regex(">>?\\s*\\S+"), "")
        .replace("[", "")
        .replace("]", "")
        .removePrefix("remoteble")
        .trim()
        .removeSuffix("&")
        .trim()

    /**
     * Replaces an upper-case placeholder with a real value of the right kind.
     *
     * `remoteble read HANDLE SERVICE CHARACTERISTIC` is how the command surface is documented, and
     * the CLI rightly rejects `SERVICE` as a UUID. Substituting keeps the assertion pointed at what
     * is actually worth guarding — the argument shape and the flag names — rather than at the
     * placeholder convention.
     */
    private fun substitutePlaceholder(token: String): String {
        if (!token.matches(Regex("[A-Z][A-Z_]*"))) return token
        return when {
            token.contains("DESCRIPTOR") -> "2902"
            token.contains("CHARACTERISTIC") -> "2a37"
            token.contains("SERVICE") -> "180d"
            token.contains("HANDLE") || token.contains("DEVICE") -> "dev_42"
            token.contains("STREAM") -> "1"
            token.contains("TYPE") -> "with-response"
            token.contains("VALUE") -> "01"
            token.contains("SECONDS") -> "5s"
            token == "N" -> "10"
            else -> token
        }
    }

    /** Splits on whitespace, honouring the single and double quotes the examples use. */
    private fun tokenize(value: String): List<String> {
        val tokens = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        value.forEach { character ->
            when {
                quote != null && character == quote -> quote = null
                quote != null -> token.append(character)
                character == '"' || character == '\'' -> quote = character
                character.isWhitespace() -> if (token.isNotEmpty()) { tokens += token.toString(); token.clear() }
                else -> token.append(character)
            }
        }
        if (token.isNotEmpty()) tokens += token.toString()
        return tokens
    }
}
