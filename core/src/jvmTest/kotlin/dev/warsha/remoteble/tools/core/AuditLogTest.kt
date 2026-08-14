package dev.warsha.remoteble.tools.core

import java.nio.file.attribute.PosixFilePermission
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuditLogTest {
    @Test fun `audit redacts endpoint credentials and never writes payload contents`() {
        val directory = Files.createTempDirectory("remoteble-audit")
        AuditLogger(directory.toString()).audit(
            operation = "write",
            operationId = "op-test",
            endpoint = "wss://user:secret@example.test/agent?token=bad",
            payloadLength = 3,
            result = "ok",
        )
        val text = Files.walk(directory).use { paths -> paths.filter { it.toString().contains("audit-") }.findFirst().get().let(Files::readString) }
        assertTrue(text.contains("<redacted>"), text)
        assertFalse(text.contains("secret"), text)
        assertFalse(text.contains("token=bad"), text)
        assertTrue(text.contains("\"operationId\":\"op-test\""), text)
    }

    @Test fun `audit rotation retains only the configured number of daily files`() {
        val directory = Files.createTempDirectory("remoteble-audit-rotation")
        Files.writeString(directory.resolve("audit-2000-01-01.jsonl"), "{}\n")
        Files.writeString(directory.resolve("audit-2000-01-02.jsonl"), "{}\n")
        Files.writeString(directory.resolve("audit-2000-01-03.jsonl"), "{}\n")

        AuditLogger(directory.toString(), auditRetentionDays = 2).audit("read", result = "ok")

        val files = Files.list(directory).use { paths ->
            paths.map { it.fileName.toString() }.filter { it.startsWith("audit-") }.toList()
        }
        assertEquals(2, files.size)
        assertFalse(files.contains("audit-2000-01-01.jsonl"))
        assertFalse(files.contains("audit-2000-01-02.jsonl"))
    }

    @Test fun `diagnostic records are limited and returned chronologically`() {
        val directory = Files.createTempDirectory("remoteble-audit-report")
        Files.writeString(directory.resolve("audit-2000-01-01.jsonl"), """{"timestamp":"2000-01-01T00:00:00Z","operation":"older","result":"ok"}
""")
        Files.writeString(directory.resolve("audit-2000-01-02.jsonl"), """{"timestamp":"2000-01-02T00:00:00Z","operation":"newer","result":"error"}
""")

        val records = AuditLogger(directory.toString()).records(limit = 1)

        assertEquals(1, records.size)
        assertEquals("newer", records.single()["operation"]?.toString()?.trim('"'))
        assertEquals("error", records.single()["result"]?.toString()?.trim('"'))
    }

    @Test fun `diagnostic record limit does not read older audit files`() {
        val directory = Files.createTempDirectory("remoteble-audit-report-limit")
        Files.writeString(directory.resolve("audit-2000-01-01.jsonl"), "not-json\n")
        Files.writeString(directory.resolve("audit-2000-01-02.jsonl"), """{"timestamp":"2000-01-02T00:00:00Z","operation":"newer","result":"ok"}
""")

        val records = AuditLogger(directory.toString()).records(limit = 1)

        assertEquals("newer", records.single()["operation"]?.toString()?.trim('"'))
    }

    @Test fun `diagnostic records reject objects outside the published schema`() {
        val directory = Files.createTempDirectory("remoteble-audit-report-schema")
        Files.writeString(directory.resolve("audit-2000-01-01.jsonl"), "{}\n")

        val failure = kotlin.test.assertFailsWith<CliFailure> { AuditLogger(directory.toString()).records() }

        assertEquals(ExitCode.FAILURE, failure.exitCode)
    }

    @Test fun `diagnostic records reject an oversized line without loading the complete file`() {
        val directory = Files.createTempDirectory("remoteble-audit-report-size")
        Files.writeString(directory.resolve("audit-2000-01-01.jsonl"), "x".repeat(65 * 1024) + "\n")

        val failure = kotlin.test.assertFailsWith<CliFailure> { AuditLogger(directory.toString()).records(limit = 1) }

        assertEquals(ExitCode.FAILURE, failure.exitCode)
    }

    @Test fun `diagnostic records are empty before an audit directory is created`() {
        val directory = Files.createTempDirectory("remoteble-audit-empty").resolve("not-created")

        assertTrue(AuditLogger(directory.toString()).records().isEmpty())
    }

    @Test fun `audit failure blocks a mutation but not a read`() {
        val path = Files.createTempFile("remoteble-audit-file", ".tmp")
        val logger = AuditLogger(path.toString())

        logger.audit("read", result = "ok")
        val failure = kotlin.test.assertFailsWith<CliFailure> {
            logger.audit("write", result = "attempt", mutation = true)
        }
        assertEquals(ExitCode.FAILURE, failure.exitCode)
    }

    @Test fun `audit and debug files are owner-only and debug rotation is retained`() {
        val directory = Files.createTempDirectory("remoteble-audit-permissions")
        val oldDebug = listOf("debug-2000-01-01.jsonl", "debug-2000-01-02.jsonl").map { name ->
            directory.resolve(name).also { Files.writeString(it, "{}\n"); setOwnerOnly(it.toString(), directory = false) }
        }
        AuditLogger(directory.toString(), debugRetentionDays = 2).apply {
            audit("read", result = "ok")
            debug("trace", mapOf("detail" to "safe"))
        }

        val audit = Files.list(directory).use { paths -> paths.filter { it.fileName.toString().startsWith("audit-") }.findFirst().get() }
        val debugFiles = Files.list(directory).use { paths -> paths.filter { it.fileName.toString().startsWith("debug-") }.toList() }
        assertEquals(2, debugFiles.size)
        assertOwnerOnly(directory, directory = true)
        assertOwnerOnly(audit, directory = false)
        debugFiles.forEach { assertOwnerOnly(it, directory = false) }
    }

    @Test fun `debug logging falls back without affecting the caller`() {
        val file = Files.createTempFile("remoteble-debug-unavailable", ".tmp")
        AuditLogger(file.toString()).debug("this diagnostic must not throw")
        assertTrue(Files.isRegularFile(file))
    }

    @Test fun `concurrent processes append complete audit records`() {
        val directory = Files.createTempDirectory("remoteble-audit-processes")
        val processes = List(8) { index -> helper("audit", directory.toString(), "op-$index") }
        processes.forEach { process ->
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "audit helper did not exit")
            assertEquals(0, process.exitValue(), process.errorStream.readBytes().decodeToString())
        }

        val records = AuditLogger(directory.toString()).records(limit = 16)
        assertEquals((0 until 8).map { "op-$it" }.toSet(), records.map { it["operationId"]!!.toString().trim('"') }.toSet())
        assertTrue(records.all { it["operation"]?.toString()?.trim('"') == "helper" })
    }

    @Test fun `concurrent processes create one stable owner-only identity`() {
        val identity = Files.createTempDirectory("remoteble-identity-race").resolve("client-id")
        val processes = List(8) { helper("identity", identity.toString()) }
        val values = processes.map { process ->
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "identity helper did not exit")
            assertEquals(0, process.exitValue(), process.errorStream.readBytes().decodeToString())
            process.inputStream.bufferedReader().readText().trim()
        }
        assertEquals(1, values.toSet().size, values.toString())
        assertEquals(values.first(), Files.readString(identity).trim())
        assertTrue(isAutoGeneratedClientIdentity(values.first()))
        assertOwnerOnly(identity, directory = false)
    }

    @Test fun `invalid persisted identity fails closed in a fresh process`() {
        val identity = Files.createTempDirectory("remoteble-invalid-identity").resolve("client-id")
        Files.writeString(identity, "not valid!\n")
        val process = helper("identity", identity.toString())
        assertTrue(process.waitFor(15, TimeUnit.SECONDS))
        assertTrue(process.exitValue() != 0, "an invalid persisted identity was accepted")
    }

    private fun helper(mode: String, path: String, operationId: String? = null): Process = ProcessBuilder(
        listOf(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"), StateProcessHelper::class.java.name, mode, path,
        ) + listOfNotNull(operationId),
    ).apply {
        if (mode == "identity") environment()["REMOTE_BLE_CLIENT_ID_FILE"] = path
    }.start()

    private fun assertOwnerOnly(path: Path, directory: Boolean) {
        val permissions = Files.getPosixFilePermissions(path)
        val expected = if (directory) {
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
        } else {
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        }
        assertEquals(expected, permissions, "$path has non-owner permissions")
    }
}

/** Separate JVM entry point so persistence races use the same environment boundary as the CLI. */
object StateProcessHelper {
    @JvmStatic fun main(args: Array<String>) {
        when (args[0]) {
            "identity" -> println(persistedClientIdentity())
            "audit" -> AuditLogger(args[1]).audit("helper", operationId = args[2], result = "ok")
            else -> error("unknown helper mode ${args[0]}")
        }
    }
}
