package dev.warsha.remoteble.tools.integration

import kotlin.test.Test

class SchemaValidationTest {
    @Test fun `result envelope enforces every published discriminator shape`() {
        RESULT_RECORDS.forEach { (type, data) ->
            SchemaAssertions.assertValid("result-envelope-v1.json", resultEnvelope(type, data))
            SchemaAssertions.assertInvalid("result-envelope-v1.json", resultEnvelope(type, "{}"))
        }
    }

    @Test fun `session output accepts every declared record shape`() {
        SESSION_RECORDS.forEach { SchemaAssertions.assertValid("session-output-v1.json", it) }
    }

    @Test fun `schemas reject unknown variants and malformed terminal records`() {
        SchemaAssertions.assertValid(
            "result-envelope-v1.json",
            """{"schemaVersion":1,"type":"connect","data":{"handle":"device-1"}}""",
        )
        SchemaAssertions.assertInvalid("result-envelope-v1.json", """{"schemaVersion":1,"type":"future.command","data":{}}""")
        SchemaAssertions.assertInvalid(
            "result-envelope-v1.json",
            """{"schemaVersion":1,"type":"scan.result","data":{"handle":"device-1","rssi":-50,"serviceUuids":[],"manufacturerData":{}}}""",
        )
        SchemaAssertions.assertInvalid(
            "session-output-v1.json",
            """{"schemaVersion":1,"type":"stream.closed","timestamp":"2026-08-14T00:00:00Z","sequence":1,"id":"s","streamId":1,"data":{"count":0,"reason":"future"}}""",
        )
    }

    private companion object {
        fun resultEnvelope(type: String, data: String) =
            """{"schemaVersion":1,"type":"$type","timestamp":"2026-08-14T00:00:00Z","sequence":1,"data":$data}"""

        val RESULT_RECORDS = listOf(
            "agent.capabilities" to """{"capabilities":["agent.status"],"scanConcurrency":"shared","handleTranslation":true}""",
            "agent.status" to """{"agentInfo":"test-agent","protocolVersion":1,"uptimeMs":10,"operatorScope":false,"settings":{"leaseGraceMs":1000,"transportGraceMs":500,"exclusiveByDefault":true,"scanConcurrency":"shared","strictIdentifiers":true,"writePolicyEnforced":true},"slots":{"free":1,"total":2},"connectedClients":1,"otherLeases":0,"leases":[{"handle":"device-1","mine":true,"connected":true,"inGrace":false}]}""",
            "agent.slots" to """{"free":1,"total":2}""",
            "scan" to """[{"handle":"device-1","name":"Sensor","rssi":-50,"eventCount":2,"firstSeen":0,"lastSeen":1,"serviceUuids":["180d"]}]""",
            "scan.result" to """{"handle":"device-1","name":"Sensor","rssi":-50,"serviceUuids":["180d"],"manufacturerData":{"76":"0102"}}""",
            "connect" to """{"handle":"device-1"}""",
            "disconnect" to """{"handle":"device-1"}""",
            "inspect" to """[{"uuid":"180d","characteristics":[{"uuid":"2a37","name":"Heart Rate Measurement","properties":["notify"],"descriptors":["2902"]}]}]""",
            "read" to """{"hex":"64","base64":"ZA==","length":1}""",
            "descriptor.read" to """{"hex":"0000","base64":"AAA=","length":2}""",
            "rssi" to """{"handle":"device-1","rssi":-48}""",
            "observe.notification" to """{"sequence":1,"timestamp":"2026-08-14T00:00:00Z","handle":"device-1","serviceUuid":"180d","characteristicUuid":"2a37","value":{"hex":"64","base64":"ZA==","length":1}}""",
            "write" to """{"handle":"device-1","length":1,"withResponse":true}""",
            "report" to """{"records":[{"timestamp":"2026-08-14T00:00:00Z","operation":"read","result":"ok"}]}""",
            "config.show" to """{"schemaVersion":1,"agent":{"endpoint":"wss://agent.example","clientId":"derived","tokenEnvironmentVariable":"REMOTE_BLE_TOKEN","operatorTokenEnvironmentVariable":"unset"},"policy":{"readOnly":true,"maximumWriteBytes":512},"logging":{"level":"info","directory":"/tmp/remoteble"}}""",
            "config.validate" to """{"valid":true}""",
            "skills.install" to """{"scope":"user","requestedTargets":["auto"],"skillVersion":"0.1.0","cliVersion":"0.1.0","targets":[{"path":"/tmp/.agents/skills/remoteble","targets":["codex","gemini"],"status":"current","changed":false}]}""",
            "skills.doctor" to """{"scope":"project","requestedTargets":["android-studio"],"skillVersion":"0.1.0","cliVersion":"0.1.0","targets":[{"path":"/tmp/.agent/skills/remoteble","targets":["android-studio"],"status":"missing"}]}""",
            "error" to """{"exitCode":9,"message":"GATT operation failed","errorKind":"GATT","gattStatus":133,"holder":{"principal":"operator","clientId":"client-1"}}""",
        )

        val SESSION_RECORDS = listOf(
            """{"schemaVersion":1,"type":"session.ready","timestamp":"2026-08-14T00:00:00Z","sequence":1,"id":"","data":{"sessionId":"s-1","capabilities":["agent.status"],"operatorScope":false}}""",
            """{"schemaVersion":1,"type":"command.accepted","timestamp":"2026-08-14T00:00:00Z","sequence":2,"id":"a","data":{"command":"read"}}""",
            """{"schemaVersion":1,"type":"command.result","timestamp":"2026-08-14T00:00:00Z","sequence":3,"id":"a","data":{"free":1,"total":2}}""",
            """{"schemaVersion":1,"type":"command.result","timestamp":"2026-08-14T00:00:00Z","sequence":4,"id":"b","data":{"hex":"64","base64":"ZA==","length":1}}""",
            """{"schemaVersion":1,"type":"command.result","timestamp":"2026-08-14T00:00:00Z","sequence":5,"id":"c","data":{"handle":"h","length":1,"withResponse":true}}""",
            """{"schemaVersion":1,"type":"command.result","timestamp":"2026-08-14T00:00:00Z","sequence":6,"id":"d","data":{"streamId":1,"stopped":true}}""",
            """{"schemaVersion":1,"type":"command.result","timestamp":"2026-08-14T00:00:00Z","sequence":7,"id":"e","data":{"closed":true}}""",
            """{"schemaVersion":1,"type":"command.error","timestamp":"2026-08-14T00:00:00Z","sequence":8,"id":"f","data":{"exitCode":9,"message":"unavailable","errorKind":"GATT","gattStatus":133}}""",
            """{"schemaVersion":1,"type":"stream.started","timestamp":"2026-08-14T00:00:00Z","sequence":9,"id":"g","streamId":1,"data":{"command":"observe"}}""",
            """{"schemaVersion":1,"type":"stream.event","timestamp":"2026-08-14T00:00:00Z","sequence":10,"id":"g","streamId":1,"data":{"handle":"h","rssi":-50,"serviceUuids":["180d"]}}""",
            """{"schemaVersion":1,"type":"stream.event","timestamp":"2026-08-14T00:00:00Z","sequence":11,"id":"g","streamId":1,"data":{"hex":"64","base64":"ZA==","length":1}}""",
            """{"schemaVersion":1,"type":"stream.closed","timestamp":"2026-08-14T00:00:00Z","sequence":12,"id":"g","streamId":1,"data":{"count":2,"reason":"slow-consumer"}}""",
            """{"schemaVersion":1,"type":"session.closed","timestamp":"2026-08-14T00:00:00Z","sequence":13,"id":"z","data":{"sessionId":"s-1"}}""",
        )
    }
}
