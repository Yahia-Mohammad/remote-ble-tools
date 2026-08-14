package dev.warsha.remoteble.tools.core

import kotlin.time.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Cross-process rolling write counter. The lock covers read, prune, count, and append. */
class WriteRateLedger(private val path: String = "${defaultLogDirectory()}/write-rate.jsonl") {
    private val json = Json { ignoreUnknownKeys = false }

    fun tryConsume(key: String, maximum: Int, windowMillis: Long = 60_000L): Boolean {
        require(maximum > 0)
        // A non-positive window prunes every prior entry, so the count below is always zero and the
        // limiter permits everything. Configuration rejects it; refuse it here too rather than
        // letting a caller disable the limit by passing one.
        require(windowMillis > 0) { "write-rate window must be positive" }
        ensureDirectory(path.substringBeforeLast('/'))
        return withFileLock("${path.substringBeforeLast('/')}/.state.lock") {
            val now = Clock.System.now().toEpochMilliseconds()
            val target = sha256Hex(key)
            val entries = if (fileExists(path)) {
                readFileText(path).lineSequence().filter { it.isNotBlank() }.map { line ->
                    try {
                        val objectValue = json.parseToJsonElement(line).jsonObject
                        val storedKey = objectValue["key"]?.jsonPrimitive?.content
                            ?: throw IllegalArgumentException("missing key")
                        val at = objectValue["at"]?.jsonPrimitive?.long
                            ?: throw IllegalArgumentException("missing timestamp")
                        if (objectValue.keys != setOf("key", "at")) throw IllegalArgumentException("unexpected field")
                        storedKey to at
                    } catch (error: Throwable) {
                        throw CliFailure(ExitCode.FAILURE, "write-rate state is corrupt; refusing write", error)
                    }
                }.filter { (_, at) -> now - at < windowMillis }.toList()
            } else emptyList()
            if (entries.count { it.first == target } >= maximum) return@withFileLock false
            val next = entries.map { (existingKey, at) -> buildJsonObject { put("key", existingKey); put("at", at) } } + buildJsonObject {
                put("key", target); put("at", now)
            }
            writeFileTextAtomically(path, next.joinToString("\n") { json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), it) } + "\n")
            true
        }
    }
}
