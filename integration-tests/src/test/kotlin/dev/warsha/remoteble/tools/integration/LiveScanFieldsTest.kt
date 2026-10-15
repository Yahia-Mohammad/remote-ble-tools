package dev.warsha.remoteble.tools.integration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance

/**
 * The `scan.fields` capability (RemoteBLE 0.15): service data, Tx power, connectability and the
 * platform's name, which the agent sends only to a client that negotiates it. The simulated profile
 * declares them on [LiveAgent.FIELDS_HANDLE], so their absence here would mean the CLI stopped
 * offering the capability or stopped rendering what it receives.
 */
@Tag("live-agent")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveScanFieldsTest {

    @BeforeAll fun startAgent() {
        assumeTrue(LiveAgent.available, "set -Dremoteble.agent.jar=<path> to run the live-agent suite")
        LiveAgent.ensureStarted()
    }

    private fun cli() = CliRunner(clientId = "acceptance-scan-fields")

    @Test fun `a one-shot scan reports the advertised scan fields`() {
        val result = cli().run("--json", "scan", "--duration", "2s", "--name", LiveAgent.FIELDS_NAME)
        assertEquals(0, result.exitCode, "$result")
        SchemaAssertions.assertValid("result-envelope-v1.json", result.stdout.trim())
        val device = data(result.stdout).jsonArray.single().jsonObject
        assertEquals(LiveAgent.FIELDS_HANDLE, device.getValue("handle").jsonPrimitive.content)
        assertFields(device)
    }

    @Test fun `streamed scan results carry the scan fields`() {
        val result = cli().run("--jsonl", "scan", "--duration", "2s", "--max-events", "3", "--name", LiveAgent.FIELDS_NAME)
        assertEquals(0, result.exitCode, "$result")
        val lines = result.jsonLines().filter { "\"scan.result\"" in it }
        check(lines.isNotEmpty()) { "no scan.result lines: $result" }
        lines.forEach { SchemaAssertions.assertValid("result-envelope-v1.json", it) }
        assertFields(data(lines.first()).jsonObject)
    }

    @Test fun `session scan events carry the scan fields`() {
        val result = cli().session(listOf("--jsonl", "session")) { pipe ->
            pipe.send(
                """{"schemaVersion":1,"id":"s1","command":"scan","arguments":{"name":"${LiveAgent.FIELDS_NAME}","timeout":"2s","count":1}}""",
            )
            pipe.await { it.contains("\"type\":\"stream.closed\"") }
            pipe.send("""{"schemaVersion":1,"id":"close","command":"session.close","arguments":{}}""")
        }
        assertEquals(0, result.exitCode, "$result")
        result.jsonLines().forEach { SchemaAssertions.assertValid("session-output-v1.json", it) }
        val event = result.jsonLines().first { LiveAgent.FIELDS_HANDLE in it }
        assertFields(data(event).jsonObject)
    }

    @Test fun `a device without service data reports none`() {
        val result = cli().run("--json", "scan", "--duration", "2s", "--name", LiveAgent.HRM_NAME)
        assertEquals(0, result.exitCode, "$result")
        val device = data(result.stdout).jsonArray.single().jsonObject
        assertFalse("serviceData" in device, "$result")
        assertFalse("txPower" in device, "$result")
    }

    private fun data(line: String) = Json.parseToJsonElement(line.trim()).jsonObject.getValue("data")

    private fun assertFields(advertisement: JsonObject) {
        val serviceData = advertisement.getValue("serviceData").jsonObject
        val battery = serviceData.entries.single { (uuid, _) -> uuid.lowercase().startsWith("0000180f") || uuid.lowercase() == "180f" }
        assertEquals("5a", battery.value.jsonObject.getValue("hex").jsonPrimitive.content)
        assertEquals(1, battery.value.jsonObject.getValue("length").jsonPrimitive.int)
        assertEquals(-8, advertisement.getValue("txPower").jsonPrimitive.int)
        assertFalse(advertisement.getValue("connectable").jsonPrimitive.boolean)
    }
}
