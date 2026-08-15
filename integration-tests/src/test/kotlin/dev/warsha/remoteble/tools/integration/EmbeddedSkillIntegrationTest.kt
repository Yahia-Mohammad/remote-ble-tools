package dev.warsha.remoteble.tools.integration

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmbeddedSkillIntegrationTest {
    @Test fun `packaged JVM CLI installs verifies refuses modifications and backs up on force`() {
        val home = Files.createTempDirectory("remoteble-skill-home")
        val project = Files.createTempDirectory("remoteble-skill-project")
        val install = run(home, "--json", "skills", "install", "--target", "codex", "--scope", "user")
        assertEquals(0, install.exitCode, install.stderr)
        assertTrue(install.stdout.contains("skills.install"), install.stdout)
        assertEquals(0, run(home, "skills", "doctor", "--target", "codex", "--scope", "user").exitCode)
        val skill = home.resolve(".agents/skills/remoteble")
        Files.writeString(skill.resolve("SKILL.md"), "modified")
        assertEquals(1, run(home, "skills", "doctor", "--target", "codex", "--scope", "user").exitCode)
        assertEquals(1, run(home, "skills", "install", "--target", "codex", "--scope", "user").exitCode)
        val forced = run(home, "skills", "install", "--target", "codex", "--scope", "user", "--force")
        assertEquals(0, forced.exitCode, forced.stderr)
        assertTrue(Files.list(skill.parent).use { paths -> paths.anyMatch { it.fileName.toString().startsWith("remoteble.backup-") } })
        assertEquals(0, run(home, "skills", "install", "--target", "android-studio", "--scope", "project", "--project-dir", project.toString()).exitCode)
        assertTrue(Files.isRegularFile(project.resolve(".agent/skills/remoteble/SKILL.md")))
    }

    private fun run(home: java.nio.file.Path, vararg args: String): CliResult {
        val process = ProcessBuilder(listOf("java", "-jar", PackagedCli.launcher().toString()) + args).apply {
            environment()["HOME"] = home.toString()
            environment()["USERPROFILE"] = home.toString()
        }.start()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "skills command did not exit" }
        return CliResult(process.exitValue(), process.inputStream.readBytes().decodeToString(), process.errorStream.readBytes().decodeToString())
    }
}
