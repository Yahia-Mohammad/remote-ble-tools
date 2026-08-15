package dev.warsha.remoteble.tools.cli

import dev.warsha.remoteble.tools.core.CliFailure
import dev.warsha.remoteble.tools.core.ExitCode
import dev.warsha.remoteble.tools.core.deleteDirectoryRecursively
import dev.warsha.remoteble.tools.core.deleteFile
import dev.warsha.remoteble.tools.core.ensureDirectory
import dev.warsha.remoteble.tools.core.fileExists
import dev.warsha.remoteble.tools.core.homeDirectory
import dev.warsha.remoteble.tools.core.isDirectory
import dev.warsha.remoteble.tools.core.listDirectory
import dev.warsha.remoteble.tools.core.movePath
import dev.warsha.remoteble.tools.core.readFileText
import dev.warsha.remoteble.tools.core.sha256Hex
import dev.warsha.remoteble.tools.core.workingDirectory
import dev.warsha.remoteble.tools.core.writeFileText
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal enum class SkillTarget(val cliName: String) {
    CODEX("codex"), GEMINI("gemini"), CLAUDE("claude"), ANDROID_STUDIO("android-studio");

    companion object {
        fun parse(value: String): SkillTarget = entries.firstOrNull { it.cliName == value }
            ?: throw CliFailure(ExitCode.USAGE, "Unknown skill target '$value'")
    }
}

internal enum class SkillScope { USER, PROJECT }
internal enum class SkillStatus { CURRENT, MISSING, OUTDATED, MODIFIED, INVALID }

@Serializable
internal data class InstalledSkillManifest(
    val schemaVersion: Int,
    val skill: String,
    val skillVersion: String,
    val cliVersion: String,
    val files: Map<String, String>,
)

internal data class SkillDestination(val targets: List<SkillTarget>, val path: String)
internal data class SkillDiagnosis(val destination: SkillDestination, val status: SkillStatus, val detail: String? = null)
internal data class SkillInstallResult(val diagnosis: SkillDiagnosis, val changed: Boolean, val backup: String? = null)

/**
 * Installs only the bundle compiled into the executable. It deliberately does not read a nearby
 * checkout, environment configuration, credentials, policy, or eval material.
 */
internal class SkillInstaller(
    private val home: String = homeDirectory(),
    private val currentDirectory: String = workingDirectory(),
) {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val bundledFiles: Map<String, String> = embeddedSkillFiles().associate { file ->
        validateRelativePath(file.path)
        file.path to Base64.decode(file.base64).decodeToString()
    }
    private val bundleDigests: Map<String, String> = bundledFiles.mapValues { (_, body) -> sha256Hex(body) }

    init {
        if (bundledFiles.isEmpty() || "SKILL.md" !in bundledFiles) {
            throw IllegalStateException("embedded RemoteBLE skill bundle is incomplete")
        }
        bundledFiles.keys.forEach(::validateRelativePath)
        if (bundledFiles.keys.any { it == "evals" || it.startsWith("evals/") || forbiddenSkillPath(it) }) {
            throw IllegalStateException("embedded RemoteBLE skill bundle contains install-forbidden files")
        }
    }

    fun resolve(scope: SkillScope, requested: List<String>, projectDirectory: String?): List<SkillDestination> {
        val explicit = requested.filter { it != "auto" }
        if (scope == SkillScope.PROJECT && explicit.isEmpty()) {
            throw CliFailure(ExitCode.USAGE, "--scope project requires an explicit --target")
        }
        val targets = when {
            requested.isEmpty() || requested.all { it == "auto" } -> autoTargets(scope)
            requested.any { it == "auto" } -> throw CliFailure(ExitCode.USAGE, "--target auto cannot be combined with explicit targets")
            else -> explicit.map(SkillTarget::parse)
        }
        if (scope == SkillScope.USER && SkillTarget.ANDROID_STUDIO in targets) {
            throw CliFailure(ExitCode.USAGE, "android-studio is supported only with --scope project")
        }
        val root = when (scope) {
            SkillScope.USER -> home
            SkillScope.PROJECT -> projectDirectory ?: findGitRoot(currentDirectory) ?: currentDirectory
        }
        return targets.groupBy { destinationFor(it, scope, root) }
            .map { (path, grouped) -> SkillDestination(grouped.distinct(), path) }
            .sortedBy { it.path }
    }

    fun diagnose(destination: SkillDestination): SkillDiagnosis {
        val target = destination.path
        if (!fileExists(target)) return SkillDiagnosis(destination, SkillStatus.MISSING)
        if (!isDirectory(target)) return SkillDiagnosis(destination, SkillStatus.INVALID, "destination is not a directory")
        val manifestPath = "$target/$INSTALL_MANIFEST"
        if (!fileExists(manifestPath)) return SkillDiagnosis(destination, SkillStatus.MODIFIED, "not installer-managed")
        val manifest = runCatching { json.decodeFromString<InstalledSkillManifest>(readFileText(manifestPath)) }.getOrElse {
            return SkillDiagnosis(destination, SkillStatus.INVALID, "invalid install manifest")
        }
        if (manifest.schemaVersion != MANIFEST_SCHEMA || manifest.skill != SKILL_NAME || manifest.files.keys.any { path ->
                runCatching { validateRelativePath(path); forbiddenSkillPath(path) }.getOrDefault(true)
            }
        ) {
            return SkillDiagnosis(destination, SkillStatus.INVALID, "manifest does not describe a safe RemoteBLE skill")
        }
        val modified = manifest.files.any { (relative, digest) ->
            val path = "$target/$relative"
            !fileExists(path) || !runCatching { sha256Hex(readFileText(path)) == digest }.getOrDefault(false)
        }
        if (modified) return SkillDiagnosis(destination, SkillStatus.MODIFIED, "managed file digest differs")
        val unexpected = installedFilePaths(target).filterNot { it == INSTALL_MANIFEST || it in manifest.files }
        if (unexpected.isNotEmpty()) return SkillDiagnosis(destination, SkillStatus.MODIFIED, "contains unmanaged files")
        return if (manifest.skillVersion == skillVersion() && manifest.cliVersion == CLI_VERSION && manifest.files == bundleDigests) {
            SkillDiagnosis(destination, SkillStatus.CURRENT)
        } else {
            SkillDiagnosis(destination, SkillStatus.OUTDATED, "skill or CLI version differs")
        }
    }

    fun install(destination: SkillDestination, force: Boolean): SkillInstallResult {
        val diagnosis = diagnose(destination)
        if (diagnosis.status == SkillStatus.CURRENT) return SkillInstallResult(diagnosis, changed = false)
        if (diagnosis.status in setOf(SkillStatus.MODIFIED, SkillStatus.INVALID) && !force) {
            throw CliFailure(ExitCode.FAILURE, "refusing to replace ${destination.path}: ${diagnosis.detail}; use --force to back it up first")
        }
        val parent = parentOf(destination.path)
        ensureDirectory(parent)
        val stage = "$parent/.remoteble-stage-${uniqueSuffix()}"
        val holding = "$parent/.remoteble-replaced-${uniqueSuffix()}"
        var backup: String? = null
        try {
            ensureDirectory(stage)
            bundledFiles.forEach { (relative, body) ->
                val path = "$stage/$relative"
                ensureDirectory(parentOf(path))
                writeFileText(path, body)
            }
            val manifest = InstalledSkillManifest(MANIFEST_SCHEMA, SKILL_NAME, skillVersion(), CLI_VERSION, bundleDigests)
            writeFileText("$stage/$INSTALL_MANIFEST", json.encodeToString(InstalledSkillManifest.serializer(), manifest))
            if (fileExists(destination.path)) {
                movePath(destination.path, holding)
                if (force) {
                    backup = backupPath(destination.path)
                    movePath(holding, backup)
                }
            }
            movePath(stage, destination.path)
            if (fileExists(holding)) deleteDirectoryRecursively(holding)
            return SkillInstallResult(diagnose(destination), changed = true, backup = backup)
        } catch (error: Throwable) {
            if (!fileExists(destination.path) && fileExists(holding)) runCatching { movePath(holding, destination.path) }
            throw error
        } finally {
            if (fileExists(stage)) runCatching { deleteDirectoryRecursively(stage) }
        }
    }

    private fun autoTargets(scope: SkillScope): List<SkillTarget> = when (scope) {
        SkillScope.PROJECT -> emptyList()
        SkillScope.USER -> buildList {
            add(SkillTarget.CODEX)
            add(SkillTarget.GEMINI)
            if (isDirectory("$home/.claude")) add(SkillTarget.CLAUDE)
        }
    }

    private fun destinationFor(target: SkillTarget, scope: SkillScope, root: String): String = when (scope) {
        SkillScope.USER -> when (target) {
            SkillTarget.CODEX, SkillTarget.GEMINI -> "$root/.agents/skills/$SKILL_NAME"
            SkillTarget.CLAUDE -> "$root/.claude/skills/$SKILL_NAME"
            SkillTarget.ANDROID_STUDIO -> error("checked above")
        }
        SkillScope.PROJECT -> when (target) {
            SkillTarget.CODEX, SkillTarget.GEMINI -> "$root/.agents/skills/$SKILL_NAME"
            SkillTarget.CLAUDE -> "$root/.claude/skills/$SKILL_NAME"
            SkillTarget.ANDROID_STUDIO -> "$root/.agent/skills/$SKILL_NAME"
        }
    }

    private fun findGitRoot(start: String): String? {
        var candidate = normalizePath(start)
        while (true) {
            if (fileExists("$candidate/.git")) return candidate
            val parent = parentOf(candidate)
            if (parent == candidate) return null
            candidate = parent
        }
    }

    private fun installedFilePaths(root: String, prefix: String = ""): List<String> = listDirectory(root).flatMap { path ->
        val name = path.substringAfterLast('/')
        val relative = if (prefix.isEmpty()) name else "$prefix/$name"
        if (isDirectory(path)) installedFilePaths(path, relative) else listOf(relative)
    }

    private fun backupPath(destination: String): String = "${destination}.backup-${uniqueSuffix()}"
    private fun uniqueSuffix(): String = Clock.System.now().toString().replace(Regex("[^A-Za-z0-9]"), "")

    private fun validateRelativePath(path: String) {
        if (path.isBlank() || path.startsWith('/') || path.contains('\\') || path.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw IllegalStateException("unsafe embedded skill path '$path'")
        }
    }

    private fun forbiddenSkillPath(path: String): Boolean = path in setOf("config.yaml", "policy.yaml") ||
        path.startsWith("config/") || path.startsWith("policy/") || path.startsWith("credentials/") || path.startsWith(".claude/")

    private fun normalizePath(path: String): String = path.trimEnd('/').ifEmpty { "/" }
    private fun parentOf(path: String): String = normalizePath(path).substringBeforeLast('/', "/").ifEmpty { "/" }

    companion object {
        const val SKILL_NAME = "remoteble"
        const val INSTALL_MANIFEST = ".remoteble-install.json"
        const val MANIFEST_SCHEMA = 1
    }
}

internal fun skillVersion(): String = EMBEDDED_SKILL_VERSION
