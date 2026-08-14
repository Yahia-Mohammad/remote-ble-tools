import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.model.Component
import org.gradle.jvm.tasks.Jar
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip
import java.security.MessageDigest

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
    includeBomSerialNumber.set(false)
}
tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom") {
    includeConfigs.set(listOf("jvmRuntimeClasspath"))
    jsonOutput.set(bomJsonOutput)
    xmlOutput.set(layout.buildDirectory.file("reports/cyclonedx-direct/sbom.xml"))
}

val releaseArtifacts = tasks.register<Sync>("releaseArtifacts") {
    group = "distribution"
    dependsOn("fatJar")
    dependsOn("cyclonedxDirectBom")
    into(releaseStage)
    from(tasks.named("fatJar")) { into("lib") }
    from(rootProject.file("LICENSE"))
    from(rootProject.file("NOTICE"))
    from("src/main/dist") { into("dist"); exclude("bin/**") }
    from("src/main/dist/bin") { into("dist/bin"); filePermissions { unix("0755") } }
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
        projectType.set(Component.Type.APPLICATION)
        componentName.set("remoteble")
        includeBomSerialNumber.set(false)
        includeConfigs.set(listOf("${spec.target}CompileKlibraries"))
        jsonOutput.set(nativeBomJson)
        xmlOutput.set(layout.buildDirectory.file("reports/cyclonedx-direct/${spec.target}/sbom.xml"))
    }

    val nativeStage = layout.buildDirectory.dir("native-release-stage/${spec.target}")
    val nativeReleaseArtifacts = tasks.register<Sync>("${spec.target}ReleaseArtifacts") {
        group = "distribution"
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
