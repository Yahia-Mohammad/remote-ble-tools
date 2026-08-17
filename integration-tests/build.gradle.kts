import java.security.MessageDigest
import java.time.Duration

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(project(":cli"))
    testImplementation(project(":core"))
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.json.schema.validator) {
        // The checked-in contracts are JSON; do not carry YAML parsing into the test graph.
        exclude(group = "tools.jackson.dataformat", module = "jackson-dataformat-yaml")
    }
    testImplementation(kotlin("test"))
}

/**
 * The released RemoteBLE JVM agent fat JAR that backs [liveAgentTest].
 *
 * Supply it with `-Premoteble.agent.jar=<path>` or `REMOTE_BLE_AGENT_JAR`; a relative path resolves
 * against the repository root, not this module. When nothing is configured the task falls back to
 * whatever `fetchAgent` downloaded, so the usual local flow is two commands and no path handling.
 */
val configuredAgent: File? = ((project.findProperty("remoteble.agent.jar") as String?)
    ?: System.getenv("REMOTE_BLE_AGENT_JAR"))
    ?.takeIf { it.isNotBlank() }
    ?.let { rootProject.file(it) }

val agentVersion = "0.11.0"
val agentDirectory = layout.buildDirectory.dir("live-agent")
val fetchedAgent = agentDirectory.map { it.file("remoteble-agent-$agentVersion-all.jar") }

fun Test.sharedConfiguration() {
    useJUnitPlatform()
    dependsOn(":cli:fatJar")
    // `user.dir` is this module, not the repository root, so the packaged launcher has to be
    // located explicitly. Pin its expected name too: developers may retain archives built with
    // other versions, which must not make locating the test subject ambiguous.
    systemProperty("remoteble.cli.libs", rootProject.file("cli/build/libs").absolutePath)
    systemProperty("remoteble.cli.jar", "remoteble-${rootProject.version}-all.jar")
    systemProperty("remoteble.repo.root", rootProject.projectDir.absolutePath)
    // Keeps the simulated agent's own log inside the build tree so CI can upload it; a temp dir
    // leaves a TRANSPORT_LOST failure with no record of what the agent did.
    systemProperty("remoteble.agent.workspace", layout.buildDirectory.dir("live-agent-run").get().asFile.absolutePath)
    testLogging { showStandardStreams = false; events("failed") }
}

tasks.test {
    sharedConfiguration()
    // The default check must not need an agent or a host-matching Native executable. Those suites
    // run only from their dedicated tasks, where their required process fixture is configured.
    useJUnitPlatform { excludeTags("live-agent", "native-lock-handoff") }
}

/** Native lock behavior is meaningful only on the host that can execute the matching binary. */
val hostNativeTarget = when {
    System.getProperty("os.name").contains("mac", ignoreCase = true) -> "macosArm64"
    System.getProperty("os.arch").contains("aarch64", ignoreCase = true) ||
        System.getProperty("os.arch").contains("arm64", ignoreCase = true) -> "linuxArm64"
    else -> "linuxX64"
}
val hostNativeTargetTitle = hostNativeTarget.replaceFirstChar(Char::uppercase)
val nativeLockHolder = rootProject.project(":cli").layout.buildDirectory.file(
    "bin/$hostNativeTarget/nativeLockHolderDebugExecutable/native-lock-holder.kexe",
)

tasks.register<Test>("nativeLockHandoffTest") {
    group = "verification"
    description = "Proves the JVM and matching Native executable share the same local state lock."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    sharedConfiguration()
    dependsOn(":cli:linkNativeLockHolderDebugExecutable$hostNativeTargetTitle")
    useJUnitPlatform { includeTags("native-lock-handoff") }
    doFirst { systemProperty("remoteble.native.lock.holder", nativeLockHolder.get().asFile.absolutePath) }
}

/**
 * Runs the live-agent integration suite against a radio-less agent.
 *
 * It starts one released JVM agent with `--simulate` for the whole suite, so this is the only task
 * that exercises the wire protocol end to end. Kept separate from `test` because it is slow (one
 * scenario deliberately pauses 30 s to prove lease resumption) and needs an external artifact.
 */
tasks.register<Test>("liveAgentTest") {
    group = "verification"
    description = "Runs the live-agent integration suite against a simulated RemoteBLE agent."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    sharedConfiguration()
    useJUnitPlatform { includeTags("live-agent") }
    // One scenario waits out a 30 s pause on purpose; the default 10 s idle timeout would kill it.
    timeout.set(Duration.ofMinutes(15))
    outputs.upToDateWhen { false }
    doFirst {
        val agent = configuredAgent ?: fetchedAgent.get().asFile.takeIf { it.isFile }
        // Skipping has to be impossible to mistake for passing: a configured-but-missing JAR fails,
        // and `-Premoteble.agent.required=true` (used by CI) refuses to let the suite no-op at all.
        if (configuredAgent != null && !configuredAgent.isFile) {
            error("remoteble.agent.jar does not exist: ${configuredAgent.absolutePath}")
        }
        if (agent == null) {
            val message = "liveAgentTest: no agent JAR found; every scenario will be skipped.\n" +
                "  Run './gradlew :integration-tests:fetchAgent' first, or pass " +
                "-Premoteble.agent.jar=<path>."
            if (agentRequired) error(message) else logger.lifecycle(message)
        } else {
            logger.lifecycle("liveAgentTest: using agent ${agent.absolutePath}")
            (this as Test).systemProperty("remoteble.agent.jar", agent.absolutePath)
        }
    }
}

/** Set by CI so a misconfigured path can never present itself as a passing run. */
val agentRequired: Boolean = (project.findProperty("remoteble.agent.required") as String?)?.toBoolean() ?: false

/**
 * Downloads and verifies the released JVM agent used by [liveAgentTest]. Pinned deliberately: a
 * floating tag would turn an upstream release into a surprise failure on an unrelated change.
 */
tasks.register("fetchAgent") {
    group = "verification"
    description = "Downloads the pinned RemoteBLE JVM agent fat JAR and verifies its checksum."
    val target = fetchedAgent
    outputs.file(target)
    doLast {
        val base = "https://github.com/Yahia-Mohammad/remote-ble/releases/download/v$agentVersion"
        val jar = target.get().asFile
        jar.parentFile.mkdirs()
        val checksumFile = File(jar.parentFile, "${jar.name}.sha256")
        listOf(jar to "$base/${jar.name}", checksumFile to "$base/${jar.name}.sha256").forEach { (file, url) ->
            if (!file.exists()) uri(url).toURL().openStream().use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val expected = checksumFile.readText().trim().split(Regex("\\s+")).first().lowercase()
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(jar.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(expected == actual) {
            jar.delete()
            "agent JAR checksum mismatch: expected $expected, got $actual"
        }
        logger.lifecycle("Agent ready: ${jar.absolutePath}")
    }
}
