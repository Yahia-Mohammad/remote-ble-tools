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

    /**
     * A second agent from the same JAR serving `wss://` behind its own self-signed identity, as every
     * RemoteBLE 0.14+ agent can and phone agents do by default. Started only by the TLS scenarios.
     */
    val tlsEndpoint: String by lazy { "wss://127.0.0.1:$tlsPort/agent" }

    /** The identity the TLS agent logged at startup: what a user copies from `--print-pairing`. */
    val tlsFingerprint: String by lazy {
        ensureTlsStarted()
        // The agent binds before it logs its identity, so an accepting port does not yet mean the
        // line is in the log.
        val identity = Regex("Agent identity: (sha256:[0-9a-f]{64})")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AGENT_START_SECONDS.toLong())
        var fingerprint = identity.find(logs(TLS_LOG))?.groupValues?.get(1)
        while (fingerprint == null && System.nanoTime() < deadline) {
            Thread.sleep(100)
            fingerprint = identity.find(logs(TLS_LOG))?.groupValues?.get(1)
        }
        checkNotNull(fingerprint) { "the TLS agent logged no identity: ${diagnostics(TLS_LOG)}" }
    }

    private val agentJar: File? by lazy {
        System.getProperty("remoteble.agent.jar")?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }
    }

    private val port: Int by lazy { freePort() }
    private val tlsPort: Int by lazy { freePort() }

    /**
     * The build points this at the module's build directory so CI can upload `agent.log` as failure
     * evidence; it falls back to a temp dir when unset. A TRANSPORT_LOST failure says only that the
     * CLI lost the agent -- the agent's own log is the sole record of why, and in a temp dir it died
     * with the runner.
     */
    private val workspace: Path by lazy {
        val configured = System.getProperty("remoteble.agent.workspace")?.takeIf { it.isNotBlank() }
        if (configured == null) Files.createTempDirectory("remoteble-live-agent")
        else Path.of(configured).also { Files.createDirectories(it) }
    }

    private val process: Process by lazy { start(tls = false) }
    private val tlsProcess: Process by lazy { start(tls = true) }

    /** Starts the agent on first use and blocks until it accepts connections. */
    fun ensureStarted() {
        check(available) { "remoteble.agent.jar is not set or does not exist" }
        check(process.isAlive || awaitPort(port, 1)) { "agent exited during startup: ${diagnostics(LOG)}" }
    }

    fun ensureTlsStarted() {
        check(available) { "remoteble.agent.jar is not set or does not exist" }
        check(tlsProcess.isAlive || awaitPort(tlsPort, 1)) { "TLS agent exited during startup: ${diagnostics(TLS_LOG)}" }
    }

    fun logs(): String = logs(LOG)

    private fun logs(name: String): String = runCatching { workspace.resolve(name).toFile().readText() }.getOrDefault("")

    private fun start(tls: Boolean): Process {
        val port = if (tls) tlsPort else port
        val logName = if (tls) TLS_LOG else LOG
        val profile = copyResource("sim-acceptance.json")
        val policy = copyResource("acceptance-policy.json")
        val log = workspace.resolve(logName).toFile()
        val process = ProcessBuilder(
            // The JVM agent takes --port/--bind as flags; PORT is the Rust agent's variable and is
            // ignored here, which silently leaves it on the default port.
            javaExecutable(), "-jar", agentJar!!.absolutePath,
            "--bind", "127.0.0.1", "--port", port.toString(), "--simulate", profile.toString(),
            *(if (tls) arrayOf("--tls") else emptyArray()),
        ).apply {
            // Named credentials give each acceptance identity its own principal, which is what the
            // agent's per-principal write policy and lease ownership are keyed on.
            environment()["REMOTE_BLE_TOKENS"] = "$PRIMARY_PRINCIPAL=$PRIMARY_TOKEN,secondary=$SECONDARY_TOKEN"
            environment()["REMOTE_BLE_OPERATOR_TOKEN"] = OPERATOR_TOKEN
            environment()["REMOTE_BLE_POLICY_FILE"] = policy.toString()
            if (tls) environment()["REMOTE_BLE_IDENTITY_FILE"] = workspace.resolve("tls-identity.pem").toString()
            redirectErrorStream(true)
            redirectOutput(log)
        }.start()
        Runtime.getRuntime().addShutdownHook(Thread { process.destroyForcibly() })
        check(awaitPort(port, AGENT_START_SECONDS)) {
            process.destroyForcibly()
            "agent did not accept connections on $port within ${AGENT_START_SECONDS}s: ${diagnostics(logName)}"
        }
        return process
    }

    private fun diagnostics(logName: String): String =
        logs(logName).lineSequence().take(40).joinToString("\n").ifBlank { "(no agent output)" }

    private fun awaitPort(port: Int, seconds: Int): Boolean {
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
    private const val LOG = "agent.log"
    private const val TLS_LOG = "agent-tls.log"
}
