package dev.warsha.remoteble.tools.cli

import dev.warsha.remoteble.tools.core.CliFailure
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SkillInstallerTest {
    @Test fun `project scope requires an explicit target`() {
        val root = Files.createTempDirectory("remoteble-skill")
        val installer = SkillInstaller(root.toString(), root.toString())
        assertFailsWith<CliFailure> { installer.resolve(SkillScope.PROJECT, emptyList(), null) }
    }

    @Test fun `shared user destination is installed idempotently and doctor detects modification`() {
        val home = Files.createTempDirectory("remoteble-skill-home")
        val project = Files.createTempDirectory("remoteble-skill-project")
        Files.createDirectories(home.resolve(".claude"))
        val installer = SkillInstaller(home.toString(), project.toString())
        val destinations = installer.resolve(SkillScope.USER, listOf("auto"), null)
        assertEquals(2, destinations.size)
        val shared = destinations.single { SkillTarget.CODEX in it.targets }
        assertTrue(SkillTarget.GEMINI in shared.targets, "auto records the shared Codex/Gemini destination")
        val first = installer.install(shared, force = false)
        assertTrue(first.changed)
        assertEquals(SkillStatus.CURRENT, installer.diagnose(shared).status)
        assertTrue(!installer.install(shared, force = false).changed)
        Files.writeString(java.nio.file.Path.of(shared.path, "SKILL.md"), "modified")
        assertEquals(SkillStatus.MODIFIED, installer.diagnose(shared).status)
        assertFailsWith<CliFailure> { installer.install(shared, force = false) }
        val forced = installer.install(shared, force = true)
        assertEquals(SkillStatus.CURRENT, forced.diagnosis.status)
        val backup = assertNotNull(forced.backup)
        assertTrue(Files.isDirectory(java.nio.file.Path.of(backup)))
        assertEquals("modified", Files.readString(java.nio.file.Path.of(backup, "SKILL.md")))
    }

    @Test fun `project scope discovers the nearest git root and android studio stays project scoped`() {
        val project = Files.createTempDirectory("remoteble-skill-project")
        Files.createDirectories(project.resolve(".git"))
        val nested = Files.createDirectories(project.resolve("nested/work"))
        val installer = SkillInstaller(project.toString(), nested.toString())
        val destination = installer.resolve(SkillScope.PROJECT, listOf("android-studio"), null).single()
        assertEquals(project.resolve(".agent/skills/remoteble").toString(), destination.path)
        assertFailsWith<CliFailure> { installer.resolve(SkillScope.USER, listOf("android-studio"), null) }
    }

    @Test fun `doctor rejects traversal in an install manifest`() {
        val home = Files.createTempDirectory("remoteble-skill-home")
        val installer = SkillInstaller(home.toString(), home.toString())
        val destination = installer.resolve(SkillScope.USER, listOf("codex"), null).single()
        installer.install(destination, force = false)
        Files.writeString(
            java.nio.file.Path.of(destination.path, SkillInstaller.INSTALL_MANIFEST),
            """{"schemaVersion":1,"skill":"remoteble","skillVersion":"0.1.0","cliVersion":"x","files":{"../escape":"deadbeef"}}""",
        )
        assertEquals(SkillStatus.INVALID, installer.diagnose(destination).status)
    }
}
