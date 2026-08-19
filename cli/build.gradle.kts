import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi
import org.jetbrains.kotlin.gradle.plugin.mpp.DisableCacheInKotlinVersion
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.model.Component
import org.gradle.jvm.tasks.Jar
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipFile
import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.cyclonedx.bom)
}

kotlin {
    jvm {
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
            // DocumentedCommandTest reads the published markdown; `user.dir` is this module.
            systemProperty("remoteble.repo.root", rootProject.projectDir.absolutePath)
            // Without these the task is up to date when only documentation changed, which is
            // exactly the change DocumentedCommandTest exists to catch.
            inputs.files(rootProject.fileTree("skills") { include("**/*.md") }).withPathSensitivity(PathSensitivity.RELATIVE)
            inputs.files(rootProject.fileTree("docs") { include("**/*.md") }).withPathSensitivity(PathSensitivity.RELATIVE)
            inputs.file(rootProject.file("README.md")).withPathSensitivity(PathSensitivity.RELATIVE)
        }
    }
    macosArm64()
    linuxX64()
    linuxArm64()

    targets.withType<KotlinNativeTarget>().configureEach {
        // clikt 5.x ships `Context.selfAndAncestors` in both the `clikt` and `clikt-mordant`
        // artifacts. With the Kotlin/Native compiler cache on, each is cached into its own static
        // archive and ld.lld sees the symbol twice when linking the Linux test binary, which fails
        // `linkDebugTestLinuxX64`. macOS links cleanly, so only Linux gives up its cache here.
        // (`kotlin.native.cacheKind.*` was the old switch; it was removed in Kotlin 2.3.20.)
        if (konanTarget.family == Family.LINUX) {
            @OptIn(KotlinNativeCacheApi::class)
            binaries.configureEach {
                disableNativeCache(
                    DisableCacheInKotlinVersion.`2_4_10`,
                    "clikt 5.x defines Context.selfAndAncestors in both clikt and clikt-mordant; " +
                        "cached, ld.lld sees it twice and linkDebugTestLinux*64 fails",
                )
            }
        }
        binaries {
            executable("remoteble") {
                entryPoint = "dev.warsha.remoteble.tools.cli.main"
                baseName = "remoteble"
            }
            // This is intentionally not staged into release archives. It exists solely so the JVM
            // integration suite can prove that the actual Native lock interoperates on one host.
            executable("nativeLockHolder") {
                entryPoint = "dev.warsha.remoteble.tools.cli.nativeLockHolder"
                baseName = "native-lock-holder"
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(libs.clikt)
            implementation(libs.serialization.json)
            implementation(libs.coroutines.core)
            implementation(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

val jvmCompilation = kotlin.targets.getByName("jvm").compilations.getByName("main")

/**
 * Main.kt needs the release version at compile time. Gradle cannot reach a Kotlin `const` before
 * compilation, so a task writes the value into a generated source file that all targets compile.
 */
val generatedVersionDir = layout.buildDirectory.dir("generated/version")
val generateCliVersion = tasks.register("generateCliVersion") {
    group = "build"
    val versionValue = project.version.toString()
    inputs.property("remoteble.version", versionValue)
    outputs.dir(generatedVersionDir)
    doLast {
        val file = generatedVersionDir.get().file("dev/warsha/remoteble/tools/cli/CliVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package dev.warsha.remoteble.tools.cli\n\ninternal const val CLI_VERSION: String = \"$versionValue\"\n")
    }
}

kotlin.sourceSets.getByName("commonMain").kotlin.srcDir(generatedVersionDir)
tasks.withType<KotlinCompile>().configureEach { dependsOn(generateCliVersion) }
tasks.withType<KotlinNativeCompile>().configureEach { dependsOn(generateCliVersion) }

/** Compiles the canonical, text-readable skill into every executable; no adjacent checkout is read at runtime. */
val generatedSkillDir = layout.buildDirectory.dir("generated/skill")
val skillSource = rootProject.file("skills/remoteble")
val generateEmbeddedSkill = tasks.register("generateEmbeddedSkill") {
    group = "build"
    inputs.files(rootProject.fileTree(skillSource) { exclude("evals/**") }).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(generatedSkillDir)
    doLast {
        val files = skillSource.walkTopDown().filter { it.isFile && !it.relativeTo(skillSource).invariantSeparatorsPath.startsWith("evals/") }.sortedBy { it.relativeTo(skillSource).path }.toList()
        val version = Regex("(?m)^  version: [\\\"]?([^\\\"\\s]+)").find(skillSource.resolve("SKILL.md").readText())?.groupValues?.get(1)
            ?: error("skills/remoteble/SKILL.md is missing metadata.version")
        val source = generatedSkillDir.get().file("dev/warsha/remoteble/tools/cli/EmbeddedSkill.kt").asFile
        source.parentFile.mkdirs()
        source.writeText(buildString {
            appendLine("package dev.warsha.remoteble.tools.cli")
            appendLine()
            appendLine("internal data class EmbeddedSkillFile(val path: String, val base64: String)")
            appendLine("internal const val EMBEDDED_SKILL_VERSION: String = \"$version\"")
            appendLine("internal fun embeddedSkillFiles(): List<EmbeddedSkillFile> = listOf(")
            files.forEach { file ->
                val path = file.relativeTo(skillSource).invariantSeparatorsPath
                appendLine("    EmbeddedSkillFile(\"$path\", \"${Base64.getEncoder().encodeToString(file.readBytes())}\"),")
            }
            appendLine(")")
        })
    }
}
kotlin.sourceSets.getByName("commonMain").kotlin.srcDir(generatedSkillDir)
tasks.withType<KotlinCompile>().configureEach { dependsOn(generateEmbeddedSkill) }
tasks.withType<KotlinNativeCompile>().configureEach { dependsOn(generateEmbeddedSkill) }

val validateSkill = tasks.register("validateSkill") {
    group = "verification"
    description = "Validates the portable RemoteBLE Agent Skill without invoking a model."
    inputs.files(rootProject.fileTree(skillSource)).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("remoteble.version", project.version.toString())
    doLast {
        val skill = skillSource.resolve("SKILL.md")
        val text = skill.readText()
        val frontmatter = Regex("\\A---\\n([\\s\\S]*?)\\n---").find(text)?.groupValues?.get(1) ?: error("SKILL.md needs YAML frontmatter")
        check(Regex("(?m)^name: remoteble$").containsMatchIn(frontmatter)) { "skill name must be remoteble" }
        val description = Regex("(?m)^description: >-\\n((?:  .*\\n)+)").find(frontmatter)?.groupValues?.get(1)?.trim()?.replace(Regex("\\s+"), " ")
            ?: error("skill description must use a folded scalar")
        check(description.length in 80..1024) { "skill description must be 80-1024 characters" }
        check(!frontmatter.contains("compatibility:")) { "compatibility belongs in the body prerequisites section" }
        val skillVersion = Regex("(?m)^  version: [\\\"]?([^\\\"\\s]+)").find(frontmatter)?.groupValues?.get(1) ?: error("metadata.version is required")
        val projectVersion = project.version.toString()
        val prereleaseBaseVersion = Regex("^(\\d+\\.\\d+\\.\\d+)-").find(projectVersion)?.groupValues?.get(1)
        check(
            skillVersion == projectVersion ||
                (projectVersion == "0.1.0-SNAPSHOT" && skillVersion == "0.1.0") ||
                skillVersion == prereleaseBaseVersion,
        ) { "skill metadata version does not match release version" }
        check(text.lineSequence().count() <= 500) { "SKILL.md must remain under 500 lines" }
        Regex("\\[[^]]+]\\(([^)#]+)(?:#[^)]+)?\\)").findAll(text).forEach { match -> check(skillSource.resolve(match.groupValues[1]).isFile) { "missing skill reference ${match.groupValues[1]}" } }
        val forbidden = skillSource.walkTopDown().filter { it.isFile && !it.relativeTo(skillSource).invariantSeparatorsPath.startsWith("evals/") }.filter { file ->
            val path = file.relativeTo(skillSource).invariantSeparatorsPath
            path.startsWith("config/") || path.startsWith("policy/") || path.startsWith("credentials/") || path == "config.yaml" || path == "policy.yaml"
        }.toList()
        check(forbidden.isEmpty()) { "install-forbidden skill files: $forbidden" }
        listOf("evals.json", "trigger-evals.json").forEach { name -> JsonSlurper().parse(skillSource.resolve("evals/$name")) }
        val cases = JsonSlurper().parse(skillSource.resolve("evals/trigger-evals.json")) as? List<*>
            ?: error("trigger-evals.json must be an array")
        val positives = cases.count { (it as Map<*, *>)["should_trigger"] == true }
        check(positives == 10 && cases.size - positives == 10) { "trigger cases must contain 10 positive and 10 negative prompts" }
    }
}
tasks.matching { it.name.endsWith("Test") }.configureEach { dependsOn(validateSkill) }

tasks.register<Jar>("fatJar") {
    group = "distribution"
    archiveBaseName.set("remoteble")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest.attributes["Main-Class"] = "dev.warsha.remoteble.tools.cli.MainKt"
    from(jvmCompilation.output.allOutputs)
    val runtimeClasspath = configurations.getByName("jvmRuntimeClasspath")
    dependsOn(runtimeClasspath)
    from({ runtimeClasspath.map { if (it.isDirectory) it else zipTree(it) } })
}

/**
 * Gradle 9 normalizes archive entry permissions, so a launcher that is executable in the staging
 * directory still lands in the ZIP as 0644 and cannot be run from a clean extraction. Every copy
 * spec that carries a launcher has to state the mode itself.
 */
fun CopySpec.executableLaunchers() = filesMatching("**/dist/bin/*") {
    permissions { unix("0755") }
}

val releaseStage = layout.buildDirectory.dir("release-stage")

/**
 * The JVM fat JAR is what the release SBOM must describe: its resolved runtime classpath *is* this
 * module's `jvmRuntimeClasspath`. The CycloneDX plugin scans every configuration by default, which
 * would drag test and native-only dependencies into a document read as authoritative for the
 * artifact we ship, so the scan is limited to the one configuration that ends up in the JAR.
 */
val bomJsonOutput = layout.buildDirectory.file("reports/cyclonedx-direct/sbom.json")
tasks.withType<CyclonedxDirectTask>().configureEach {
    projectType.set(Component.Type.APPLICATION)
    componentName.set("remoteble")
    // `actions/attest` treats a CycloneDX document without a serial number as an unsupported
    // format outright, so the release cannot attest an SBOM that omits it. Nothing is lost by
    // emitting one: `metadata.timestamp` already makes each document unique per build.
    includeBomSerialNumber.set(true)
}
tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom") {
    includeConfigs.set(listOf("jvmRuntimeClasspath"))
    jsonOutput.set(bomJsonOutput)
    xmlOutput.set(layout.buildDirectory.file("reports/cyclonedx-direct/sbom.xml"))
}

val releaseArtifacts = tasks.register<Sync>("releaseArtifacts") {
    group = "distribution"
    dependsOn(validateSkill)
    dependsOn("fatJar")
    dependsOn("cyclonedxDirectBom")
    into(releaseStage)
    from(tasks.named("fatJar")) { into("lib") }
    from(rootProject.file("LICENSE"))
    from(rootProject.file("NOTICE"))
    from("src/main/dist") { into("dist"); exclude("bin/**") }
    from("src/main/dist/bin") { into("dist/bin"); filePermissions { unix("0755") } }
    from(skillSource) { into("dist/skills/remoteble"); exclude("evals/**") }
    from(bomJsonOutput) { rename { "sbom.json" } }
    doLast {
        val stage = releaseStage.get().asFile
        val checksums = stage.walkTopDown().filter { it.isFile && it.name != "SHA256SUMS" }.toList()
            .joinToString("\n") { file ->
                val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { b -> "%02x".format(b) }
                "$digest  ${file.relativeTo(stage).invariantSeparatorsPath}"
            } + "\n"
        stage.resolve("SHA256SUMS").writeText(checksums)
    }
}

val skillArchive = tasks.register<Zip>("skillArchive") {
    group = "distribution"
    dependsOn(validateSkill)
    archiveBaseName.set("remoteble-skill")
    archiveVersion.set(project.version.toString())
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(skillSource) { into("remoteble"); exclude("evals/**") }
}
val skillChecksum = tasks.register("skillChecksum") {
    group = "distribution"
    dependsOn(skillArchive)
    val checksum = layout.buildDirectory.file("distributions/remoteble-skill-${project.version}.zip.sha256")
    outputs.file(checksum)
    doLast {
        val archive = skillArchive.get().archiveFile.get().asFile
        val digest = MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { b -> "%02x".format(b) }
        checksum.get().asFile.writeText("$digest  ${archive.name}\n")
    }
}
val verifySkillArchive = tasks.register("verifySkillArchive") {
    group = "verification"
    description = "Asserts the standalone readable skill exactly matches the generated embedded bundle input."
    dependsOn(skillArchive, generateEmbeddedSkill)
    inputs.files(rootProject.fileTree(skillSource) { exclude("evals/**") }).withPathSensitivity(PathSensitivity.RELATIVE)
    doLast {
        val expected = skillSource.walkTopDown().filter { it.isFile && !it.relativeTo(skillSource).invariantSeparatorsPath.startsWith("evals/") }
            .associate { it.relativeTo(skillSource).invariantSeparatorsPath to it.readBytes() }
        val archive = skillArchive.get().archiveFile.get().asFile
        val archived = ZipFile(archive).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory }.associate { entry ->
                entry.name.removePrefix("remoteble/") to zip.getInputStream(entry).readBytes()
            }
        }
        check(archived.keys == expected.keys) { "standalone skill ZIP has different files from the embedded bundle source" }
        expected.forEach { (path, bytes) -> check(archived[path]?.contentEquals(bytes) == true) { "standalone skill ZIP differs at $path" } }
        val generated = generatedSkillDir.get().file("dev/warsha/remoteble/tools/cli/EmbeddedSkill.kt").asFile.readText()
        expected.forEach { (path, bytes) ->
            check(generated.contains("EmbeddedSkillFile(\"$path\", \"${Base64.getEncoder().encodeToString(bytes)}\")")) { "embedded bundle differs at $path" }
        }
    }
}
releaseArtifacts.configure { dependsOn(verifySkillArchive) }

tasks.register<Zip>("releaseArchive") {
    group = "distribution"
    dependsOn(releaseArtifacts)
    archiveBaseName.set("remoteble")
    archiveVersion.set(project.version.toString())
    from(releaseStage)
    executableLaunchers()
}

private data class NativeArchiveSpec(
    val target: String,
    val archiveName: String,
    val linkTask: String,
)

listOf(
    NativeArchiveSpec("macosArm64", "macos-arm64", "linkRemotebleReleaseExecutableMacosArm64"),
    NativeArchiveSpec("linuxX64", "linux-x64", "linkRemotebleReleaseExecutableLinuxX64"),
    NativeArchiveSpec("linuxArm64", "linux-arm64", "linkRemotebleReleaseExecutableLinuxArm64"),
).forEach { spec ->
    /**
     * Each native distribution gets its own SBOM describing that target's resolved compile
     * klibs. The plugin's `cyclonedxDirectBom` scans the JVM runtime classpath for the release
     * archive, so per-target BOM tasks are registered here instead.
     */
    val nativeBomJson = layout.buildDirectory.file("reports/cyclonedx-direct/${spec.target}/sbom.json")
    val nativeBomTask = tasks.register<CyclonedxDirectTask>("${spec.target}Bom") {
        group = "distribution"
        // Component identity and the serial number come from the shared `configureEach` above.
        // Restating them here is what let the native documents keep omitting a serial number
        // after the shared block started emitting one.
        includeConfigs.set(listOf("${spec.target}CompileKlibraries"))
        jsonOutput.set(nativeBomJson)
        xmlOutput.set(layout.buildDirectory.file("reports/cyclonedx-direct/${spec.target}/sbom.xml"))
    }

    val nativeStage = layout.buildDirectory.dir("native-release-stage/${spec.target}")
    val nativeReleaseArtifacts = tasks.register<Sync>("${spec.target}ReleaseArtifacts") {
        group = "distribution"
        dependsOn(validateSkill, verifySkillArchive)
        dependsOn(":cli:${spec.linkTask}")
        dependsOn(nativeBomTask)
        into(nativeStage)
        from(layout.buildDirectory.dir("bin/${spec.target}/remotebleReleaseExecutable")) {
            include("remoteble.kexe")
            rename("remoteble.kexe", "remoteble")
            into("dist/bin")
            filePermissions { unix("0755") }
        }
        from("src/main/dist/bin/rble") { into("dist/bin"); filePermissions { unix("0755") } }
        from("src/main/dist/completions") { into("dist/completions") }
        from("src/main/dist/man") { into("dist/man") }
        from(skillSource) { into("dist/skills/remoteble"); exclude("evals/**") }
        from(rootProject.file("LICENSE"))
        from(rootProject.file("NOTICE"))
        from(nativeBomJson) { rename { "sbom.json" } }
        doLast {
            val stage = nativeStage.get().asFile
            val checksums = stage.walkTopDown().filter { it.isFile && it.name != "SHA256SUMS" }.toList()
                .joinToString("\n") { file ->
                    val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { b -> "%02x".format(b) }
                    "$digest  ${file.relativeTo(stage).invariantSeparatorsPath}"
                } + "\n"
            stage.resolve("SHA256SUMS").writeText(checksums)
        }
    }

    tasks.register<Zip>("${spec.target}NativeArchive") {
        group = "distribution"
        dependsOn(nativeReleaseArtifacts)
        archiveBaseName.set("remoteble-${spec.archiveName}")
        archiveVersion.set(project.version.toString())
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
        from(nativeStage)
        executableLaunchers()
    }
}

private data class LinuxPackageSpec(
    val target: String,
    val debArchitecture: String,
    val rpmArchitecture: String,
)

val nfpmExecutable = providers.gradleProperty("nfpm.executable").orElse("nfpm")
val nfpmConfig = rootProject.file("packaging/nfpm.yaml")

private fun registerLinuxPackageTasks(spec: LinuxPackageSpec) {
    val stage = layout.buildDirectory.dir("native-release-stage/${spec.target}")
    val distributions = layout.buildDirectory.dir("distributions")

    fun registerPackage(format: String, architecture: String, filename: String) = tasks.register<Exec>("${spec.target}${format.replaceFirstChar(Char::uppercase)}Package") {
        group = "distribution"
        description = "Builds the $format package for ${spec.target} using nFPM."
        dependsOn("${spec.target}ReleaseArtifacts")
        inputs.file(nfpmConfig)
        inputs.dir(stage)
        inputs.property("remoteble.version", project.version.toString())
        inputs.property("package.architecture", architecture)
        val output = distributions.map { it.file(filename) }
        outputs.file(output)
        // nFPM expands environment variables in only a fixed set of fields -- name, version, arch,
        // the dependency lists -- and `contents[].src` is not one of them, so `${'$'}{STAGE_DIR}` reached
        // the globber verbatim and matched nothing. PACKAGE_ARCH/PACKAGE_VERSION below are in the
        // expanded set and still arrive through the environment; only the staging path is rendered.
        val renderedConfig = layout.buildDirectory.file("tmp/nfpm/${spec.target}-${'$'}format.yaml")
        doFirst {
            check(nfpmConfig.isFile) { "Missing nFPM configuration: ${nfpmConfig.absolutePath}" }
            val stageDir = stage.get().asFile
            val staged = stageDir.resolve("dist/bin/remoteble")
            check(staged.isFile) { "Staged executable is missing: ${'$'}{staged.absolutePath}" }
            val rendered = renderedConfig.get().asFile
            rendered.parentFile.mkdirs()
            rendered.writeText(nfpmConfig.readText().replace("${'$'}{STAGE_DIR}", stageDir.absolutePath))
        }
        executable(nfpmExecutable.get())
        args("package", "--config", renderedConfig.get().asFile.absolutePath, "--packager", format, "--target", output.get().asFile.absolutePath)
        environment("PACKAGE_ARCH", architecture)
        environment("PACKAGE_VERSION", project.version.toString())
        environment("STAGE_DIR", stage.get().asFile.absolutePath)
    }

    val deb = registerPackage("deb", spec.debArchitecture, "remoteble_${project.version}_${spec.debArchitecture}.deb")
    val rpm = registerPackage("rpm", spec.rpmArchitecture, "remoteble-${project.version}-1.${spec.rpmArchitecture}.rpm")
    tasks.register("${spec.target}Packages") {
        group = "distribution"
        description = "Builds DEB and RPM packages for ${spec.target}."
        dependsOn(deb, rpm)
    }
}

listOf(
    LinuxPackageSpec("linuxX64", "amd64", "x86_64"),
    LinuxPackageSpec("linuxArm64", "arm64", "aarch64"),
).forEach(::registerLinuxPackageTasks)

tasks.register("linuxPackages") {
    group = "distribution"
    description = "Builds all Linux DEB and RPM distribution packages."
    dependsOn("linuxX64Packages", "linuxArm64Packages")
}
