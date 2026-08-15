package dev.warsha.remoteble.tools.integration

import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Starts one radio-less RemoteBLE JVM agent for the whole live-agent suite and stops it on exit.
 *
 * The agent is the released JVM fat JAR run with `--simulate`, which is upstream's supported
 * radio-less mode; the Rust agent has no equivalent and cannot be used here. The JAR path arrives as
 * the `remoteble.agent.jar` system property so the build — not this code — owns how it is obtained.
 *
 * Two principals are configured because contention tests need a second identity: the
 * agent-side denied-write proof, and lease contention. The write policy allowlists exactly one
 * characteristic for `primary` and nothing for `secondary`, so a denial is the agent's decision and
 * not this CLI's local advisory policy.
 */
internal object LiveAgent {
    const val PRIMARY_PRINCIPAL = "primary"
    const val PRIMARY_TOKEN = "primary-secret-token"
    const val SECONDARY_TOKEN = "secondary-secret-token"
    const val OPERATOR_TOKEN = "operator-secret-token"

    /** Handles and UUIDs declared by `sim-acceptance.json`. */
    const val HRM_HANDLE = "sim-hrm-1"
    const val HRM_NAME = "Warsha HRM (sim)"
    const val HOSTILE_HANDLE = "sim-hostile-1"

    /**
     * A one-shot command deliberately keeps its lease, and leases are exclusive per client, so a
     * scenario that takes one needs a peripheral no other scenario touches.
     */
    const val LEASE_HANDLE = "sim-lease-1"
    const val POLICY_HANDLE = "sim-policy-1"
    const val CONTENDED_HANDLE = "sim-contend-1"
    const val SESSION_WRITE_HANDLE = "sim-twin-a"
    const val SHELL_WRITE_HANDLE = "sim-twin-b"
    const val DROP_HANDLE = "sim-drop-1"
    const val PTY_JOBS_HANDLE = "sim-pty-jobs-1"
    const val PTY_INTERRUPT_HANDLE = "sim-pty-interrupt-1"
    const val BROKEN_PIPE_HANDLE = "sim-broken-pipe-1"
    const val TWIN_NAME = "Warsha Twin (sim)"
    const val HEART_RATE_SERVICE = "180d"
    const val MEASUREMENT_CHARACTERISTIC = "2a37"
    const val CONTROL_POINT_CHARACTERISTIC = "2a39"
    const val BATTERY_SERVICE = "180f"
    const val BATTERY_CHARACTERISTIC = "2a19"

    /** `read.static` for the battery level in the profile: 0x64 == 100 %. */
    const val BATTERY_HEX = "64"

    val available: Boolean by lazy { agentJar != null }

    val endpoint: String by lazy { "ws://127.0.0.1:$port/agent" }

    private val agentJar: File? by lazy {
        System.getProperty("remoteble.agent.jar")?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }
    }

    private val port: Int by lazy { freePort() }

    private val workspace: Path by lazy { Files.createTempDirectory("remoteble-live-agent") }

    private val process: Process by lazy { start() }

    /** Starts the agent on first use and blocks until it accepts connections. */
    fun ensureStarted() {
        check(available) { "remoteble.agent.jar is not set or does not exist" }
        check(process.isAlive || awaitPort(1)) { "agent exited during startup: ${diagnostics()}" }
    }

    fun logs(): String = runCatching { workspace.resolve("agent.log").toFile().readText() }.getOrDefault("")

    private fun start(): Process {
        val profile = copyResource("sim-acceptance.json")
        val policy = copyResource("acceptance-policy.json")
        val log = workspace.resolve("agent.log").toFile()
        val process = ProcessBuilder(
            // The JVM agent takes --port/--bind as flags; PORT is the Rust agent's variable and is
            // ignored here, which silently leaves it on the default port.
            javaExecutable(), "-jar", agentJar!!.absolutePath,
            "--bind", "127.0.0.1", "--port", port.toString(), "--simulate", profile.toString(),
        ).apply {
            // Named credentials give each acceptance identity its own principal, which is what the
            // agent's per-principal write policy and lease ownership are keyed on.
            environment()["REMOTE_BLE_TOKENS"] = "$PRIMARY_PRINCIPAL=$PRIMARY_TOKEN,secondary=$SECONDARY_TOKEN"
            environment()["REMOTE_BLE_OPERATOR_TOKEN"] = OPERATOR_TOKEN
            environment()["REMOTE_BLE_POLICY_FILE"] = policy.toString()
            redirectErrorStream(true)
            redirectOutput(log)
        }.start()
        Runtime.getRuntime().addShutdownHook(Thread { process.destroyForcibly() })
        check(awaitPort(AGENT_START_SECONDS)) {
            process.destroyForcibly()
            "agent did not accept connections on $port within ${AGENT_START_SECONDS}s: ${diagnostics()}"
        }
        return process
    }

    private fun diagnostics(): String = logs().lineSequence().take(40).joinToString("\n").ifBlank { "(no agent output)" }

    private fun awaitPort(seconds: Int): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds.toLong())
        while (System.nanoTime() < deadline) {
            runCatching { Socket("127.0.0.1", port).close() }.onSuccess { return true }
            Thread.sleep(100)
        }
        return false
    }

    private fun copyResource(name: String): Path {
        val target = workspace.resolve(name)
        val bytes = checkNotNull(javaClass.classLoader.getResourceAsStream(name)) { "missing test resource $name" }
            .use { it.readBytes() }
        Files.write(target, bytes)
        return target
    }

    private fun javaExecutable(): String =
        Path.of(System.getProperty("java.home"), "bin", "java").toString()

    /** A port that was bound and released: free at this instant, which is all a test fixture needs. */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private const val AGENT_START_SECONDS = 60
}
