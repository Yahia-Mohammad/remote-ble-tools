package dev.warsha.remoteble.tools.core

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Redacted, append-only operational audit trail shared by one-shot and session clients. */
class AuditLogger(
    private val directory: String = defaultLogDirectory(),
    private val auditRetentionDays: Int = 14,
    private val debugRetentionDays: Int = 7,
) {
    private val json = Json { encodeDefaults = true; prettyPrint = false }

    fun audit(
        operation: String,
        operationId: String? = null,
        sessionId: String? = null,
        commandId: String? = null,
        streamId: String? = null,
        endpoint: String? = null,
        clientId: String? = null,
        device: String? = null,
        serviceUuid: String? = null,
        characteristicUuid: String? = null,
        writeType: String? = null,
        payloadLength: Int? = null,
        durationMs: Long? = null,
        result: String? = null,
        errorKind: String? = null,
        mutation: Boolean = false,
    ) {
        val record = buildJsonObject {
            put("timestamp", Clock.System.now().toString())
            put("operation", operation)
            operationId?.let { put("operationId", it) }
            sessionId?.let { put("sessionId", it) }
            commandId?.let { put("commandId", it) }
            streamId?.let { put("streamId", it) }
            endpoint?.let { put("endpoint", sanitizeEndpoint(it)) }
            clientId?.let { put("clientId", it) }
            device?.let { put("device", it) }
            serviceUuid?.let { put("serviceUuid", it) }
            characteristicUuid?.let { put("characteristicUuid", it) }
            writeType?.let { put("writeType", it) }
            payloadLength?.let { put("payloadLength", it) }
            durationMs?.let { put("durationMs", it) }
            result?.let { put("result", it) }
            errorKind?.let { put("errorKind", it) }
        }
        try {
            ensureDirectory(directory)
            setOwnerOnly(directory, directory = true)
            val file = "$directory/audit-${utcDay()}.jsonl"
            withFileLock("$directory/.state.lock") {
                appendFileText(file, json.encodeToString(JsonObject.serializer(), record) + "\n")
                setOwnerOnly(file, directory = false)
                rotate("audit-", auditRetentionDays)
            }
        } catch (error: Throwable) {
            if (mutation) throw CliFailure(ExitCode.FAILURE, "audit logging failed; mutation was not dispatched", error)
            // Read-only operations remain useful when a filesystem is unavailable. Keep the
            // warning generic so paths, credentials, and command data never reach stderr.
            runCatching { writeStandardError("remoteble: audit logging unavailable; continuing read-only operation\n".encodeToByteArray()) }
        }
    }

    fun debug(message: String, fields: Map<String, String> = emptyMap()) {
        runCatching {
            ensureDirectory(directory)
            val record = buildJsonObject {
                put("timestamp", Clock.System.now().toString())
                put("message", message.take(512))
                fields.forEach { (key, value) -> put(key, value.take(512)) }
            }
            withFileLock("$directory/.state.lock") {
                val file = "$directory/debug-${utcDay()}.jsonl"
                appendFileText(file, json.encodeToString(JsonObject.serializer(), record) + "\n")
                setOwnerOnly(file, directory = false)
                rotate("debug-", debugRetentionDays)
            }
        }.onFailure {
            runCatching { writeStandardError("remoteble: debug log unavailable; continuing with stderr diagnostics\n".encodeToByteArray()) }
        }
    }

    /**
     * Returns the most recent redacted audit records in chronological order.
     *
     * Reports must never infer success from a missing record: an unreadable or malformed audit
     * file is a local failure callers can surface explicitly, while an empty directory is a valid
     * report for a newly configured client.
     */
    fun records(limit: Int = 100): List<JsonObject> {
        require(limit > 0) { "limit must be positive" }
        // A client that has not run a command yet has no log directory. Treat that as an empty
        // report; every other filesystem or parsing failure remains explicit below.
        if (!fileExists(directory)) return emptyList()
        return try {
            val newestFirst = mutableListOf<JsonObject>()
            for (path in listDirectory(directory)
                .filter { it.substringAfterLast('/').startsWith("audit-") }
                .sortedDescending()) {
                // Keep only this file's newest records, then stop opening older files as soon as
                // the requested report is full. This avoids flattening every retained day for a
                // small diagnostic report.
                val fileRecords = ArrayDeque<JsonObject>(limit)
                forEachFileLine(path, MAX_AUDIT_RECORD_BYTES) { line ->
                    if (line.isBlank()) return@forEachFileLine
                    val record = json.parseToJsonElement(line) as? JsonObject
                        ?: throw IllegalArgumentException("audit record is not an object")
                    validateAuditRecord(record)
                    if (fileRecords.size == limit) fileRecords.removeFirst()
                    fileRecords.addLast(record)
                }
                for (record in fileRecords.reversed()) {
                    newestFirst += record
                    if (newestFirst.size == limit) break
                }
                if (newestFirst.size == limit) break
            }
            newestFirst.asReversed()
        } catch (error: Throwable) {
            throw CliFailure(ExitCode.FAILURE, "unable to read the local audit log", error)
        }
    }

    private fun validateAuditRecord(record: JsonObject) {
        if (record.keys.any { it !in AUDIT_FIELDS }) {
            throw IllegalArgumentException("audit record contains an unknown field")
        }
        val timestamp = requiredString(record, "timestamp")
        runCatching { Instant.parse(timestamp) }
            .getOrElse { throw IllegalArgumentException("audit timestamp is not RFC 3339", it) }
        requiredString(record, "operation")
        OPTIONAL_STRING_FIELDS.forEach { field -> record[field]?.let { optionalString(record, field) } }
        record["writeType"]?.let {
            if (optionalString(record, "writeType") !in setOf("with-response", "without-response")) {
                throw IllegalArgumentException("audit writeType is invalid")
            }
        }
        listOf("payloadLength", "durationMs").forEach { field ->
            record[field]?.let { value ->
                val primitive = value as? JsonPrimitive
                val number = if (primitive != null && !primitive.isString) primitive.content.toLongOrNull() else null
                if (number == null || number < 0) throw IllegalArgumentException("audit $field is invalid")
            }
        }
    }

    private fun requiredString(record: JsonObject, field: String): String =
        optionalString(record, field).takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("audit $field is required")

    private fun optionalString(record: JsonObject, field: String): String {
        val primitive = record[field] as? JsonPrimitive
        if (primitive == null || !primitive.isString) throw IllegalArgumentException("audit $field must be a string")
        return primitive.content
    }

    private fun rotate(prefix: String, retention: Int) {
        val files = listDirectory(directory).filter { it.substringAfterLast('/').startsWith(prefix) }.sorted()
        files.dropLast(retention.coerceAtLeast(1)).forEach(::deleteFile)
    }

    private fun utcDay(): String = Clock.System.now().toString().substringBefore('T')

    private fun sanitizeEndpoint(value: String): String = value
        .substringBefore('?')
        .substringBefore('#')
        .replace(Regex("(wss?://)([^/@]+):([^/@]+)@"), "$1<redacted>@")

    companion object {
        private const val MAX_AUDIT_RECORD_BYTES = 64 * 1024
        private val OPTIONAL_STRING_FIELDS = setOf(
            "operationId", "sessionId", "commandId", "streamId", "endpoint", "clientId", "device",
            "serviceUuid", "characteristicUuid", "result", "errorKind",
        )
        private val AUDIT_FIELDS = OPTIONAL_STRING_FIELDS + setOf(
            "timestamp", "operation", "writeType", "payloadLength", "durationMs",
        )

        fun defaultLogDirectory(): String {
            environmentVariable("REMOTE_BLE_LOG_DIR")?.takeIf { it.isNotBlank() }?.let { return it }
            environmentVariable("XDG_STATE_HOME")?.takeIf { it.isNotBlank() }?.let { return "$it/remoteble/logs" }
            val home = homeDirectory()
            return if (isApplePlatform()) {
                "$home/Library/Logs/remoteble"
            } else {
                "$home/.local/state/remoteble/logs"
            }
        }
    }
}

fun defaultLogDirectory(): String = AuditLogger.defaultLogDirectory()
