package dev.warsha.remoteble.tools.integration

import dev.warsha.remoteble.protocol.CLIENT_ID_HEADER
import dev.warsha.remoteble.protocol.CborProtocolCodec
import dev.warsha.remoteble.protocol.Capabilities
import dev.warsha.remoteble.protocol.CharRef
import dev.warsha.remoteble.protocol.ClientHello
import dev.warsha.remoteble.protocol.Command
import dev.warsha.remoteble.protocol.DeviceHandle
import dev.warsha.remoteble.protocol.ErrorKind
import dev.warsha.remoteble.protocol.Op
import dev.warsha.remoteble.protocol.OpResult
import dev.warsha.remoteble.protocol.Reply
import dev.warsha.remoteble.protocol.ServerHello
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance

/**
 * The MVP acceptance scenarios, run by the packaged CLI against a live radio-less agent.
 *
 * This is the only coverage in the repository that exercises the wire. Everything else asserts what
 * this code does with its own types; these assert what a released agent actually answers, which is
 * where a protocol mistake would surface.
 *
 * Scenario numbering follows `docs/mvp-scope.md`. Scenarios needing a real radio are noted where
 * they are skipped rather than silently omitted.
 */
@Tag("live-agent")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveAgentScenarioTest {

    @BeforeAll fun startAgent() {
        assumeTrue(LiveAgent.available, "set -Dremoteble.agent.jar=<path> to run the live-agent suite")
        LiveAgent.ensureStarted()
    }

    private fun cli(
        token: String = LiveAgent.PRIMARY_TOKEN,
        clientId: String = "acceptance-client-a",
    ) = CliRunner(token = token, clientId = clientId)

    // --- 1. Is an agent ready, and what does it support? ------------------------------------

    @Test fun `scenario 1 - capabilities and status describe a ready agent`() {
        val capabilities = cli().run("--json", "agent", "capabilities")
        assertEquals(0, capabilities.exitCode, "$capabilities")
        assertContains(capabilities.stdout, "agent.status")
        assertContains(capabilities.stdout, "write.policy")

        val status = cli().run("--json", "agent", "status")
        assertEquals(0, status.exitCode, "$status")
        // The decode is the point: these fields only appear if AgentStatusDto matches the agent's.
        listOf("protocolVersion", "uptimeMs", "transportGraceMs", "writePolicyEnforced", "slots")
            .forEach { assertContains(status.stdout, it) }

        val slots = cli().run("--json", "agent", "slots")
        assertEquals(0, slots.exitCode, "$slots")
        assertContains(slots.stdout, "\"total\"")
    }

    @Test fun `scenario 1b - operator scope is granted only with the operator credential`() {
        val withoutOperator = cli().run("agent", "status", "--operator")
        assertEquals(2, withoutOperator.exitCode, "$withoutOperator")

        val withOperator = CliRunner(
            extraEnvironment = mapOf("REMOTE_BLE_OPERATOR_TOKEN" to LiveAgent.OPERATOR_TOKEN),
        ).run("--json", "agent", "status", "--operator")
        assertEquals(0, withOperator.exitCode, "$withOperator")
        assertContains(withOperator.stdout, "\"operatorScope\":true")
    }

    // --- 2, 3, 11. Scan, selection, and hostile names ---------------------------------------

    @Test fun `scenario 2 - scan finds the simulated peripheral`() {
        val result = cli().run("--json", "scan", "--duration", "3s")
        assertEquals(0, result.exitCode, "$result")
        assertContains(result.stdout, LiveAgent.HRM_HANDLE)
    }

    @Test fun `scenario 3 - a unique selector resolves and an ambiguous one is refused`() {
        val unique = cli().run("--quiet", "connect", "--name", LiveAgent.HRM_NAME)
        assertEquals(0, unique.exitCode, "$unique")
        assertEquals(LiveAgent.HRM_HANDLE, unique.stdout.trim())

        // Two peripherals advertise the twin name, so this must be a clear refusal, not a pick.
        val ambiguous = cli().run("connect", "--name", LiveAgent.TWIN_NAME)
        assertEquals(5, ambiguous.exitCode, "$ambiguous")
        assertContains(ambiguous.stderr, "ambiguous")
    }

    @Test fun `scenario 11 - a hostile advertised name is delimited data and changes nothing`() {
        val result = cli().run("--json", "scan", "--duration", "3s")
        assertEquals(0, result.exitCode, "$result")
        assertContains(result.stdout, LiveAgent.HOSTILE_HANDLE)
        // The raw escape must not survive into output; it is escaped or dropped, never executed.
        assertTrue('' !in result.stdout, "an ANSI escape reached stdout verbatim")
    }

    // --- 4, 5. Discovery and reads ----------------------------------------------------------

    @Test fun `scenario 4 - inspect prints the GATT tree`() {
        val result = cli().run("--json", "inspect", LiveAgent.HRM_HANDLE)
        assertEquals(0, result.exitCode, "$result")
        listOf("180d", "2a37", "2a39", "180f", "2a19").forEach {
            assertContains(result.stdout.lowercase(), it)
        }
    }

    @Test fun `scenario 5 - the battery level reads with raw bytes preserved`() {
        val result = cli().run(
            "--json", "read", LiveAgent.HRM_HANDLE, LiveAgent.BATTERY_SERVICE, LiveAgent.BATTERY_CHARACTERISTIC,
        )
        assertEquals(0, result.exitCode, "$result")
        assertContains(result.stdout, "\"hex\":\"${LiveAgent.BATTERY_HEX}\"")
        assertContains(result.stdout, "\"length\":1")
    }

    // --- 6. Bounded notification streaming --------------------------------------------------

    @Test fun `scenario 6 - ten notifications arrive as jsonl and the stream closes`() {
        val result = cli().run(
            "--jsonl", "observe", LiveAgent.HRM_HANDLE, LiveAgent.HEART_RATE_SERVICE,
            LiveAgent.MEASUREMENT_CHARACTERISTIC, "--count", "10",
        )
        assertEquals(0, result.exitCode, "$result")
        val notifications = result.jsonLines().filter { it.contains("observe.notification") }
        assertEquals(10, notifications.size, "$result")
        assertTrue(notifications.all { it.contains("\"schemaVersion\":1") }, "$result")
    }

    @Test fun `scenario 8 - an unsolicited simulated disconnect ends observe with a typed explanation`() {
        val runner = cli(clientId = "acceptance-drop-client")
        val result = runner.run(
            "--jsonl", "observe", LiveAgent.DROP_HANDLE, LiveAgent.HEART_RATE_SERVICE,
            LiveAgent.MEASUREMENT_CHARACTERISTIC, "--count", "100", "--timeout", "5s",
        )
        assertEquals(9, result.exitCode, "$result")
        assertTrue(result.jsonLines().any { it.contains("observe.notification") }, "$result")
        assertContains(result.stderr, "DISCONNECTED")
        assertContains(result.stderr, LiveAgent.DROP_HANDLE)

        // A stream's agent-side disconnect must not poison the next independent invocation.
        val recovery = runner.run("--json", "agent", "status")
        assertEquals(0, recovery.exitCode, "the CLI did not recover for a fresh invocation: $recovery")
    }

    // --- 7. Writes, allowed and refused -----------------------------------------------------

    @Test fun `scenario 7 - an allowlisted write succeeds`() {
        val result = writeCapableCli().run(
            "--json", "write", LiveAgent.HRM_HANDLE, LiveAgent.HEART_RATE_SERVICE,
            LiveAgent.CONTROL_POINT_CHARACTERISTIC, "--hex", "01", "--write-type", "with-response",
        )
        assertEquals(0, result.exitCode, "$result")
        assertContains(result.stdout, "\"length\":1")
    }

    @Test fun `scenario 7b - local read-only policy refuses before the radio`() {
        val result = cli().run(
            "write", LiveAgent.HRM_HANDLE, LiveAgent.HEART_RATE_SERVICE,
            LiveAgent.CONTROL_POINT_CHARACTERISTIC, "--hex", "01", "--write-type", "with-response",
        )
        assertEquals(7, result.exitCode, "$result")
    }

    @Test fun `scenario 7c - a characteristic outside the local allowlist is refused`() {
        val result = writeCapableCli().run(
            "write", LiveAgent.HRM_HANDLE, LiveAgent.BATTERY_SERVICE, LiveAgent.BATTERY_CHARACTERISTIC,
            "--hex", "01", "--write-type", "with-response",
        )
        assertEquals(7, result.exitCode, "$result")
    }

    @Test fun `scenario 7d - the agent refuses a principal its policy does not allow`() {
        // The secondary principal has an empty agent-side allowlist, while this client's *local*
        // policy permits the write. A refusal here is therefore the agent's, which is the whole
        // point: local policy is advisory and must never be mistaken for the control.
        val result = writeCapableCli(
            token = LiveAgent.SECONDARY_TOKEN,
            clientId = "acceptance-client-b",
            device = LiveAgent.POLICY_HANDLE,
        ).run(
            "--json", "write", LiveAgent.POLICY_HANDLE, LiveAgent.HEART_RATE_SERVICE,
            LiveAgent.CONTROL_POINT_CHARACTERISTIC, "--hex", "01", "--write-type", "with-response",
        )
        assertEquals(7, result.exitCode, "$result")
        assertTrue(
            result.stderr.contains("POLICY_DENIED") || result.stderr.contains("denied"),
            "expected an agent-side denial, got: $result",
        )
    }

    @Test fun `agent write policy refuses a raw protocol client`() = runBlocking {
        // This deliberately bypasses every CLI service and its advisory local policy. The same
        // agent-side refusal must therefore be visible to a minimal CBOR/WebSocket protocol peer.
        val result = rawPolicyDeniedWrite()
        val failure = assertIs<OpResult.Err>(result)
        assertEquals(ErrorKind.POLICY_DENIED, failure.error.kind)
    }

    // --- 12. Lease contention ---------------------------------------------------------------

    @Test fun `scenario 12 - a second identity is refused by name, not by timeout`() {
        val holder = cli(clientId = "acceptance-holder")
        assertEquals(0, holder.run("connect", LiveAgent.CONTENDED_HANDLE).exitCode)
        try {
            val contender = CliRunner(token = LiveAgent.SECONDARY_TOKEN, clientId = "acceptance-contender").run(
                "--json", "read", LiveAgent.CONTENDED_HANDLE, LiveAgent.BATTERY_SERVICE,
                LiveAgent.BATTERY_CHARACTERISTIC,
            )
            assertEquals(6, contender.exitCode, "$contender")
            assertContains(contender.stderr, "PERIPHERAL_BUSY")
            // The point of the holder disclosure: contention is diagnosable, not a mystery timeout.
            assertContains(contender.stderr, "\"principal\"")
        } finally {
            holder.run("disconnect", LiveAgent.CONTENDED_HANDLE)
        }
    }

    // --- 9. Diagnostics ---------------------------------------------------------------------

    @Test fun `scenario 9 - diagnostic report contains redacted commands and results`() {
        val runner = cli()
        runner.run("--json", "read", LiveAgent.HRM_HANDLE, LiveAgent.BATTERY_SERVICE, LiveAgent.BATTERY_CHARACTERISTIC)
        val report = runner.run("--json", "report")
        assertEquals(0, report.exitCode, "$report")
        assertContains(report.stdout, "\"type\":\"report\"")
        assertContains(report.stdout, "\"operation\":\"read\"")
        assertTrue(!report.stdout.contains(LiveAgent.PRIMARY_TOKEN), "a bearer token reached the report: $report")
    }

    // --- 10. The state model ----------------------------------------------------------------

    @Test fun `scenario 10 - the complete cross invocation workflow retains its lease`() {
        // This is deliberately one process per command. It proves the state model rather than a
        // convenient in-memory session: after the pause every operation must resume the same
        // agent-side lease using only the persisted client identity.
        val runner = writeCapableCli(clientId = "acceptance-lease-client", device = LiveAgent.LEASE_HANDLE)
        assertEquals(0, runner.run("connect", LiveAgent.LEASE_HANDLE).exitCode)
        try {
            Thread.sleep(LEASE_PAUSE_MILLIS)

            val inspect = runner.run("--json", "inspect", LiveAgent.LEASE_HANDLE)
            assertEquals(0, inspect.exitCode, "the lease did not survive the pause: $inspect")
            assertContains(inspect.stdout.lowercase(), LiveAgent.BATTERY_CHARACTERISTIC)

            val read = runner.run(
                "--json", "read", LiveAgent.LEASE_HANDLE, LiveAgent.BATTERY_SERVICE, LiveAgent.BATTERY_CHARACTERISTIC,
            )
            assertEquals(0, read.exitCode, "the lease did not survive the pause: $read")

            val observe = runner.run(
                "--jsonl", "observe", LiveAgent.LEASE_HANDLE, LiveAgent.HEART_RATE_SERVICE,
                LiveAgent.MEASUREMENT_CHARACTERISTIC, "--count", "3",
            )
            assertEquals(0, observe.exitCode, "$observe")
            assertEquals(3, observe.jsonLines().count { it.contains("observe.notification") }, "$observe")

            val write = runner.run(
                "--json", "write", LiveAgent.LEASE_HANDLE, LiveAgent.HEART_RATE_SERVICE,
                LiveAgent.CONTROL_POINT_CHARACTERISTIC, "--hex", "01", "--write-type", "with-response",
            )
            assertEquals(0, write.exitCode, "$write")
        } finally {
            runner.run("disconnect", LiveAgent.LEASE_HANDLE)
        }
    }

    // --- Session protocol -------------------------------------------------------------------

    @Test fun `the persistent session answers commands and closes cleanly`() {
        val input = buildString {
            appendLine("""{"schemaVersion":1,"id":"c1","command":"agent.status","arguments":{}}""")
            appendLine(
                """{"schemaVersion":1,"id":"c2","command":"read","arguments":""" +
                    """{"handle":"${LiveAgent.HRM_HANDLE}","serviceUuid":"${LiveAgent.BATTERY_SERVICE}",""" +
                    """"characteristicUuid":"${LiveAgent.BATTERY_CHARACTERISTIC}"}}""",
            )
            appendLine("""{"schemaVersion":1,"id":"c3","command":"session.close","arguments":{}}""")
        }
        val result = cli().run(listOf("--jsonl", "session"), stdin = input)
        assertEquals(0, result.exitCode, "$result")
        val types = result.jsonLines().mapNotNull { TYPE.find(it)?.groupValues?.get(1) }
        assertContains(types, "session.ready")
        assertContains(types, "session.closed")
        assertEquals(3, types.count { it == "command.result" }, "$result")
        assertTrue(types.none { it == "command.error" }, "$result")
    }

    @Test fun `packaged one-shot, stream, and session records conform to their published schemas`() {
        val runner = cli(clientId = "acceptance-schema-goldens")
        val oneShot = runner.run("--json", "agent", "status")
        assertEquals(0, oneShot.exitCode, "$oneShot")
        oneShot.jsonLines().forEach { SchemaAssertions.assertValid("result-envelope-v1.json", it) }

        val stream = runner.run("--jsonl", "scan", "--duration", "1s")
        assertEquals(0, stream.exitCode, "$stream")
        stream.jsonLines().forEach { SchemaAssertions.assertValid("result-envelope-v1.json", it) }

        val session = runner.run(
            listOf("--jsonl", "session"),
            stdin = """
                {"schemaVersion":1,"id":"status","command":"agent.status","arguments":{}}
                {"schemaVersion":1,"id":"close","command":"session.close","arguments":{}}
            """.trimIndent() + "\n",
        )
        assertEquals(0, session.exitCode, "$session")
        session.jsonLines().forEach { SchemaAssertions.assertValid("session-output-v1.json", it) }
    }

    @Test fun `a rejected session record does not end the session`() {
        val input = buildString {
            appendLine("""{"schemaVersion":1,"id":"bad","command":"read","arguments":{"handle":null}}""")
            appendLine("""{"schemaVersion":1,"id":"good","command":"agent.slots","arguments":{}}""")
            appendLine("""{"schemaVersion":1,"id":"done","command":"session.close","arguments":{}}""")
        }
        val result = cli().run(listOf("--jsonl", "session"), stdin = input)
        assertEquals(0, result.exitCode, "$result")
        val types = result.jsonLines().mapNotNull { TYPE.find(it)?.groupValues?.get(1) }
        assertEquals(1, types.count { it == "command.error" }, "$result")
        assertTrue(types.count { it == "command.result" } >= 2, "the session stopped after a bad record: $result")
    }

    @Test fun `allowed writes work through the machine session and human shell`() {
        val session = writeCapableCli(clientId = "acceptance-session-write", device = LiveAgent.SESSION_WRITE_HANDLE).run(
            listOf("--jsonl", "session"),
            stdin = buildString {
                appendLine(
                    """{"schemaVersion":1,"id":"write","command":"write","arguments":{""" +
                        """"handle":"${LiveAgent.SESSION_WRITE_HANDLE}","serviceUuid":"${LiveAgent.HEART_RATE_SERVICE}",""" +
                        """"characteristicUuid":"${LiveAgent.CONTROL_POINT_CHARACTERISTIC}","hex":"01","writeType":"with-response"}}""",
                )
                appendLine("""{"schemaVersion":1,"id":"close","command":"session.close","arguments":{}}""")
            },
        )
        assertEquals(0, session.exitCode, "$session")
        assertContains(session.stdout, "\"withResponse\":true")
        assertTrue(!session.stdout.contains("\"type\":\"command.error\""), "$session")

        val shell = writeCapableCli(clientId = "acceptance-shell-write", device = LiveAgent.SHELL_WRITE_HANDLE).run(
            listOf("shell"),
            stdin = "write ${LiveAgent.SHELL_WRITE_HANDLE} ${LiveAgent.HEART_RATE_SERVICE} ${LiveAgent.CONTROL_POINT_CHARACTERISTIC} --hex 01 --write-type with-response\nexit\n",
        )
        assertEquals(0, shell.exitCode, "$shell")
        assertContains(shell.stdout, "wrote 1 bytes")
    }

    @Test fun `local policy write denials reach the machine session and human shell`() {
        val session = cli(clientId = "acceptance-session-denied").run(
            listOf("--jsonl", "session"),
            stdin = buildString {
                appendLine(
                    """{"schemaVersion":1,"id":"write","command":"write","arguments":{""" +
                        """"handle":"${LiveAgent.HRM_HANDLE}","serviceUuid":"${LiveAgent.HEART_RATE_SERVICE}",""" +
                        """"characteristicUuid":"${LiveAgent.CONTROL_POINT_CHARACTERISTIC}","hex":"01","writeType":"with-response"}}""",
                )
                appendLine("""{"schemaVersion":1,"id":"close","command":"session.close","arguments":{}}""")
            },
        )
        assertEquals(0, session.exitCode, "$session")
        assertContains(session.stdout, "\"type\":\"command.error\"")
        assertContains(session.stdout, "\"exitCode\":7")
        assertContains(session.stdout, "local read-only policy")

        val shell = cli(clientId = "acceptance-shell-denied").run(
            listOf("shell"),
            stdin = "write ${LiveAgent.HRM_HANDLE} ${LiveAgent.HEART_RATE_SERVICE} ${LiveAgent.CONTROL_POINT_CHARACTERISTIC} --hex 01 --write-type with-response\nexit\n",
        )
        assertEquals(0, shell.exitCode, "$shell")
        assertContains(shell.stdout, "write failed: write is disabled by local read-only policy")
    }

    @Test fun `write rate limits reach the machine session and human shell`() {
        val session = writeCapableCli(
            clientId = "acceptance-session-write",
            device = LiveAgent.SESSION_WRITE_HANDLE,
            maximumWritesPerWindow = 1,
        ).run(
            listOf("--jsonl", "session"),
            stdin = buildString {
                repeat(2) { index ->
                    appendLine(
                        """{"schemaVersion":1,"id":"write-$index","command":"write","arguments":{""" +
                            """"handle":"${LiveAgent.SESSION_WRITE_HANDLE}","serviceUuid":"${LiveAgent.HEART_RATE_SERVICE}",""" +
                            """"characteristicUuid":"${LiveAgent.CONTROL_POINT_CHARACTERISTIC}","hex":"01","writeType":"with-response"}}""",
                    )
                }
                appendLine("""{"schemaVersion":1,"id":"close","command":"session.close","arguments":{}}""")
            },
        )
        assertEquals(0, session.exitCode, "$session")
        assertContains(session.stdout, "write rate limit exceeded")
        assertContains(session.stdout, "\"exitCode\":7")

        val shell = writeCapableCli(
            clientId = "acceptance-shell-write",
            device = LiveAgent.SHELL_WRITE_HANDLE,
            maximumWritesPerWindow = 1,
        ).run(
            listOf("shell"),
            stdin = buildString {
                repeat(2) {
                    appendLine(
                        "write ${LiveAgent.SHELL_WRITE_HANDLE} ${LiveAgent.HEART_RATE_SERVICE} " +
                            "${LiveAgent.CONTROL_POINT_CHARACTERISTIC} --hex 01 --write-type with-response",
                    )
                }
                appendLine("exit")
            },
        )
        assertEquals(0, shell.exitCode, "$shell")
        assertContains(shell.stdout, "write failed: write rate limit exceeded; try again later")
    }

    /**
     * `stream.stop` cancels the stream job, and the terminal records are emitted from that cancelled
     * coroutine. Suspending sends there are exactly what a cancelled coroutine refuses to run, so
     * this asserts the documented `stream.closed` still arrives rather than being dropped.
     */
    @Test fun `a stopped stream still reports stream closed`() {
        val result = cli().session(listOf("--jsonl", "session")) { pipe ->
            pipe.send("""{"schemaVersion":1,"id":"s1","command":"scan","arguments":{"timeout":"60s","count":100}}""")
            val started = pipe.await { it.contains("\"type\":\"stream.started\"") }
            val streamId = STREAM_ID.find(started)?.groupValues?.get(1)
            checkNotNull(streamId) { "stream.started carried no streamId: $started" }
            pipe.send("""{"schemaVersion":1,"id":"s2","command":"stream.stop","arguments":{"streamId":$streamId}}""")
            pipe.await { it.contains("\"type\":\"stream.closed\"") }
            pipe.send("""{"schemaVersion":1,"id":"s3","command":"session.close","arguments":{}}""")
        }
        assertEquals(0, result.exitCode, "$result")
        val closed = result.jsonLines().filter { it.contains("\"type\":\"stream.closed\"") }
        assertEquals(1, closed.size, "the stopped stream did not report stream.closed exactly once: $result")
        assertContains(closed.single(), "\"reason\":\"stopped\"")
    }

    // --- Packaged terminal and pipe lifecycle -----------------------------------------------

    @Test fun `PTY shell lists and stops a background observe job`() {
        assumeTrue(ProcessHarness.ptyAvailable, "script(1) is required for the Unix PTY test")
        val process = cli(clientId = "acceptance-pty-jobs").interactive(listOf("shell"), terminal = true)
        process.use {
            process.sendLine(
                "observe ${LiveAgent.PTY_JOBS_HANDLE} ${LiveAgent.HEART_RATE_SERVICE} " +
                    "${LiveAgent.MEASUREMENT_CHARACTERISTIC} --timeout 30s &",
            )
            process.sendLine("jobs")
            val job = process.awaitLine { Regex("(?:^|\\s)(\\d+) running$").containsMatchIn(it) }
            val streamId = Regex("(?:^|\\s)(\\d+) running$").find(job)!!.groupValues[1]
            process.sendLine("stop $streamId")
            process.sendLine("exit")
            assertEquals(0, process.awaitExit(), process.diagnostics())
        }
    }

    @Test fun `PTY shell Ctrl-C stops a foreground stream and cleans up`() {
        assumeTrue(ProcessHarness.ptyAvailable, "script(1) is required for the Unix PTY test")
        val process = cli(clientId = "acceptance-pty-interrupt").interactive(listOf("shell"), terminal = true)
        process.use {
            process.sendLine(
                "observe ${LiveAgent.PTY_INTERRUPT_HANDLE} ${LiveAgent.HEART_RATE_SERVICE} " +
                    "${LiveAgent.MEASUREMENT_CHARACTERISTIC} --timeout 30s",
            )
            process.awaitLine { Regex("\\[\\d+] [0-9a-f]+").containsMatchIn(it) }
            process.sendInterrupt()
            assertEquals(0, process.awaitExit(), process.diagnostics())
        }
    }

    @Test fun `shell exits cleanly when its input reaches EOF`() {
        val process = cli(clientId = "acceptance-shell-eof").interactive(listOf("shell"))
        process.use {
            process.closeInput()
            assertEquals(0, process.awaitExit(), process.diagnostics())
        }
    }

    @Test fun `closed stdout does not leave a packaged stream process hanging`() {
        val process = cli(clientId = "acceptance-broken-pipe").interactive(
            listOf(
                "--jsonl", "observe", LiveAgent.BROKEN_PIPE_HANDLE, LiveAgent.HEART_RATE_SERVICE,
                LiveAgent.MEASUREMENT_CHARACTERISTIC, "--count", "100",
            ),
            stdoutMode = ProcessHarness.StdoutMode.PAUSED,
        )
        process.use {
            // Let the CLI establish the simulated observation before closing the downstream read
            // end. The parent deliberately never drains stdout, so this is a real broken pipe.
            Thread.sleep(1_000)
            assertTrue(process.isAlive(), "stream ended before the broken-pipe assertion")
            process.closeStdout()
            assertTrue(process.awaitExit(timeoutSeconds = 20) >= 0, process.diagnostics())
        }
    }

    private fun writeCapableCli(
        token: String = LiveAgent.PRIMARY_TOKEN,
        clientId: String = "acceptance-client-a",
        device: String = LiveAgent.HRM_HANDLE,
        maximumWritesPerWindow: Int = 60,
    ) = CliRunner(token = token, clientId = clientId).withConfig(
        """
        schemaVersion: 1
        policy:
          readOnly: false
          maximumWritesPerWindow: $maximumWritesPerWindow
          writeRateWindow: "60s"
          writeRules:
            - endpoint: "${LiveAgent.endpoint}"
              device: "$device"
              serviceUuid: "${LiveAgent.HEART_RATE_SERVICE}"
              characteristicUuid: "${LiveAgent.CONTROL_POINT_CHARACTERISTIC}"
              maximumBytes: 2
              withResponse: [true]
        """.trimIndent() + "\n",
    )

    private suspend fun rawPolicyDeniedWrite(): OpResult {
        val codec = CborProtocolCodec()
        val http = HttpClient(CIO) { install(WebSockets) }
        try {
            val socket = http.webSocketSession(urlString = LiveAgent.endpoint) {
                header(HttpHeaders.Authorization, "Bearer ${LiveAgent.SECONDARY_TOKEN}")
                header(CLIENT_ID_HEADER, "acceptance-raw-policy-client")
            }
            socket.send(
                Frame.Binary(
                    fin = true,
                    data = codec.encode(ClientHello(capabilities = setOf(Capabilities.WRITE_POLICY))),
                ),
            )
            awaitRawFrame<ServerHello>(socket, codec)

            socket.send(Frame.Binary(fin = true, data = codec.encode(Command(1, Op.Connect(DeviceHandle(LiveAgent.POLICY_HANDLE))))))
            assertIs<OpResult.Ok>(awaitRawReply(socket, codec, 1).result)
            try {
                val write = Op.Write(
                    device = DeviceHandle(LiveAgent.POLICY_HANDLE),
                    char = CharRef(LiveAgent.HEART_RATE_SERVICE, LiveAgent.CONTROL_POINT_CHARACTERISTIC),
                    value = byteArrayOf(1),
                    withResponse = true,
                )
                socket.send(Frame.Binary(fin = true, data = codec.encode(Command(2, write))))
                return awaitRawReply(socket, codec, 2).result
            } finally {
                // Closing a raw socket does not release an agent lease. Explicitly disconnect so
                // this protocol-level test cannot affect the independent contention scenarios.
                socket.send(
                    Frame.Binary(
                        fin = true,
                        data = codec.encode(Command(3, Op.Disconnect(DeviceHandle(LiveAgent.POLICY_HANDLE)))),
                    ),
                )
                assertIs<OpResult.Ok>(awaitRawReply(socket, codec, 3).result)
            }
        } finally {
            http.close()
        }
    }

    private suspend inline fun <reified T> awaitRawFrame(
        socket: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
        codec: CborProtocolCodec,
    ): T =
        withTimeout(5_000) {
            while (true) {
                val frame = socket.incoming.receive()
                if (frame is Frame.Binary) {
                    val decoded = codec.decode(frame.data)
                    if (decoded is T) return@withTimeout decoded
                }
            }
            error("unreachable")
        }

    private suspend fun awaitRawReply(
        socket: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
        codec: CborProtocolCodec,
        cid: Long,
    ): Reply = withTimeout(5_000) {
        while (true) {
            val frame = socket.incoming.receive()
            if (frame is Frame.Binary) {
                val decoded = codec.decode(frame.data)
                if (decoded is Reply && decoded.cid == cid) return@withTimeout decoded
            }
        }
        error("unreachable")
    }

    private companion object {
        val TYPE = Regex("\"type\":\"([^\"]+)\"")
        val STREAM_ID = Regex("\"streamId\":(\\d+)")

        /**
         * Longer than the agent's BLE-disconnect grace and long enough to be a realistic gap between
         * agent tool calls, while staying well inside the 120 s transport grace the lease depends on.
         */
        const val LEASE_PAUSE_MILLIS = 30_000L
    }
}
