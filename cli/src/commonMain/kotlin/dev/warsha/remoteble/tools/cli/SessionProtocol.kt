@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dev.warsha.remoteble.tools.cli

import dev.warsha.remoteble.protocol.ScanFilter
import dev.warsha.remoteble.tools.core.AgentFeature
import dev.warsha.remoteble.tools.core.CliFailure
import dev.warsha.remoteble.tools.core.ExitCode
import dev.warsha.remoteble.tools.core.supports
import dev.warsha.remoteble.tools.core.unsupportedFeature
import dev.warsha.remoteble.tools.core.RemoteBleGateway
import dev.warsha.remoteble.tools.core.WriteOperationService
import dev.warsha.remoteble.tools.core.RemoteOperationService
import dev.warsha.remoteble.tools.core.collectBoundedStream
import dev.warsha.remoteble.tools.core.base64
import dev.warsha.remoteble.tools.core.bytesJsonValue
import dev.warsha.remoteble.tools.core.hex
import dev.warsha.remoteble.tools.core.installInterruptHandler
import dev.warsha.remoteble.tools.core.normalizeUuid
import dev.warsha.remoteble.tools.core.readStandardInputLine
import dev.warsha.remoteble.tools.core.restoreInterruptHandler
import dev.warsha.remoteble.tools.core.consumeInterrupt
import dev.warsha.remoteble.tools.core.writeStandardOutput
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.concurrent.atomics.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

private val sessionJson = Json { ignoreUnknownKeys = false; encodeDefaults = true; prettyPrint = false }

internal class SessionCommand(root: RootCommand) : RootChild(root, "session", "Run a persistent machine-readable JSONL session.") {
    private val operator: Boolean by option("--operator", help = "Present the configured operator credential on this session.").flag()

    override fun run() = execute {
        if (root.outputMode() != "jsonl") throw CliFailure(ExitCode.USAGE, "session requires --jsonl")
        runBlocking {
            withScopedInterruptHandler {
                SessionEngine(root, root.openPersistentGateway(operator), operator).run()
            }
        }
    }
}

internal class ShellCommand(root: RootCommand) : RootChild(root, "shell", "Run a human interactive RemoteBLE shell.") {
    private val operator: Boolean by option("--operator", help = "Present the configured operator credential on this shell session.").flag()

    override fun run() = execute {
        runBlocking {
            withScopedInterruptHandler {
                if (root.outputMode() == "jsonl") {
                    SessionEngine(root, root.openPersistentGateway(operator), operator).run()
                } else {
                    HumanShell(root, root.openPersistentGateway(operator), operator).run()
                }
            }
        }
    }
}

/**
 * Keeps SIGINT interception inside persistent frontends. One-shot commands retain the runtime's
 * default Ctrl-C behavior; a session or shell uses cancellation so its stream and gateway cleanup
 * runs before the command returns.
 */
private suspend fun withScopedInterruptHandler(block: suspend () -> Unit) = supervisorScope {
    if (!installInterruptHandler()) {
        block()
        return@supervisorScope
    }
    val interrupted = AtomicBoolean(false)
    val operation = async { block() }
    val watcher = launch(Dispatchers.Default) {
        while (operation.isActive) {
            if (consumeInterrupt()) {
                interrupted.store(true)
                operation.cancel(CancellationException("interrupted by SIGINT"))
                break
            }
            delay(25)
        }
    }
    try {
        operation.await()
    } catch (error: CancellationException) {
        if (!interrupted.load()) throw error
    } finally {
        withContext(NonCancellable) { watcher.cancelAndJoin() }
        restoreInterruptHandler()
    }
}

private class SessionEngine(
    private val root: RootCommand,
    private val gateway: RemoteBleGateway,
    private val operatorRequested: Boolean,
) {
    private val active = mutableMapOf<Long, Job>()
    private val streamOutputs = mutableMapOf<Long, BoundedStreamOutput<PendingOutput>>()
    private val streamForwarders = mutableMapOf<Long, Job>()
    private val slowStreams = mutableSetOf<Long>()
    private val commands = mutableMapOf<String, Job>()
    private val activeIds = mutableSetOf<String>()
    private val stateLock = Mutex()
    private var sequence = 0L
    // Set by the writer coroutine and read from every command job on other threads.
    // `sequence` stays a plain var: only the single writer coroutine ever touches it.
    private val outputBroken = AtomicBoolean(false)
    private val output = Channel<PendingOutput>(64)
    private val sessionId = "s-${gateway.session.nextStreamId()}"
    private val audit = root.auditLogger()

    private data class PendingOutput(val type: String, val id: String, val streamId: Long?, val data: JsonElement)

    suspend fun run() = coroutineScope {
        val writer = launch(Dispatchers.Default) {
            for (pending in output) {
                if (outputBroken.load()) continue
                sequence++
                val objectValue = buildJsonObject {
                    put("schemaVersion", 1); put("type", pending.type); put("timestamp", Clock.System.now().toString()); put("sequence", sequence); put("id", pending.id)
                    pending.streamId?.let { put("streamId", it) }; put("data", pending.data)
                }
                try { writeStandardOutput((sessionJson.encodeToString(JsonObject.serializer(), objectValue) + "\n").encodeToByteArray()) }
                catch (_: Throwable) {
                    outputBroken.store(true)
                    cancelAllActive()
                }
            }
        }
        try {
            try {
                gateway.awaitReady()
            } catch (error: Throwable) {
                emitError("", null, RootCommand.failure(error))
                return@coroutineScope
            }
            val capabilities = gateway.capabilities()
            val operatorScope = if (dev.warsha.remoteble.protocol.Capabilities.AGENT_STATUS in capabilities) {
                gateway.status().operatorScope
            } else {
                false
            }
            if (operatorRequested && !operatorScope) {
                throw CliFailure(ExitCode.AUTHENTICATION, "agent did not grant operator scope")
            }
            emit("session.ready", "", null, buildJsonObject {
                put("sessionId", sessionId)
                put("capabilities", kotlinx.serialization.json.JsonArray(capabilities.sorted().map(::JsonPrimitive)))
                put("operatorScope", operatorScope)
            })
            while (!outputBroken.load()) {
                // stdin may block indefinitely at an idle terminal or pipe. Keep it off the
                // runBlocking event loop so WebSocket replies, stream jobs, and shutdown continue.
                val line = try {
                    withContext(Dispatchers.Default) { readStandardInputLine() }
                } catch (error: CliFailure) {
                    // The reader consumed through the record boundary before raising, so an
                    // oversized line is one rejected record rather than the end of the session.
                    emitError("", null, error)
                    continue
                } ?: break
                if (line.isBlank()) continue
                val input = runCatching { sessionJson.parseToJsonElement(line).jsonObject }.getOrElse {
                    emitError("", null, CliFailure(ExitCode.USAGE, "malformed JSON input")); continue
                }
                val schemaVersion = input["schemaVersion"]?.takeIf { it is JsonPrimitive }?.jsonPrimitive
                if (input.keys.any { it !in setOf("schemaVersion", "id", "command", "arguments") } ||
                    schemaVersion == null || schemaVersion.isString || schemaVersion.content != "1") {
                    emitError(input["id"].asSessionId(), null, CliFailure(ExitCode.USAGE, "schemaVersion 1 and only id, command, arguments are accepted")); continue
                }
                val idPrimitive = input["id"]?.takeIf { it is JsonPrimitive }?.jsonPrimitive
                val id = idPrimitive?.takeIf { it.isString && it.content.isNotEmpty() }?.content ?: run {
                    emitError("", null, CliFailure(ExitCode.USAGE, "input record requires id")); continue
                }
                if (id.encodeToByteArray().size > 128 || !reserveId(id)) {
                    emitError(id, null, CliFailure(ExitCode.USAGE, "id must be unique and at most 128 UTF-8 bytes")); continue
                }
                val command = input["command"]?.takeIf { it is JsonPrimitive }?.jsonPrimitive?.takeIf { it.isString }?.content
                val arguments = input["arguments"]?.takeIf { it is JsonObject }?.jsonObject
                if (command == null || arguments == null) {
                    releaseId(id); emitError(id, null, CliFailure(ExitCode.USAGE, "command must be a string and arguments must be an object")); continue
                }
                try { validateArguments(command, arguments) } catch (error: Throwable) {
                    releaseId(id); emitError(id, null, RootCommand.failure(error)); continue
                }
                if (command == "session.close") {
                    emitResult(id, buildJsonObject { put("closed", true) })
                    releaseId(id)
                    // A caller that pipes several commands and a close expects every reply. Drain
                    // in-flight command work before teardown instead of cancelling it out from
                    // under itself; streams are unbounded and are still cancelled below.
                    drainCommands()
                    break
                }
                emit("command.accepted", id, null, buildJsonObject { put("command", command) })
                if (root.debugLoggingEnabled()) audit.debug("command accepted", mapOf("id" to id, "command" to command))
                launchCommand(this, id, command, arguments)
            }
        } finally {
            withContext(NonCancellable) {
                cancelAndJoinAll()
                gateway.close()
                emit("session.closed", "", null, buildJsonObject { put("sessionId", sessionId) })
                output.close()
                writer.join()
            }
        }
    }

    /**
     * Waits for non-stream commands that are still running. Bounded by the operation deadline so a
     * wedged command cannot hold the session open indefinitely; anything still running past that is
     * cancelled by the ordinary teardown.
     */
    private suspend fun drainCommands() {
        val pending = stateLock.withLock { commands.values.toList() }
        if (pending.isEmpty()) return
        kotlinx.coroutines.withTimeoutOrNull(root.resolvedConfig().operationTimeout) {
            pending.forEach { runCatching { it.join() } }
        }
    }

    private suspend fun reserveId(id: String): Boolean = stateLock.withLock { activeIds.add(id) }
    private suspend fun releaseId(id: String) = stateLock.withLock { activeIds.remove(id); commands.remove(id) }

    private fun launchCommand(scope: CoroutineScope, id: String, command: String, arguments: JsonObject) {
        val job = scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            // A stream command hands its id to the stream job, which releases it when the stream
            // ends. That handover only happens once the job exists: an argument or start failure
            // returns here instead, and must release the id itself or the caller could never reuse
            // it for the rest of the session.
            var streamOwnsId = false
            try {
                when (command) {
                    "agent.status" -> emitResult(id, rootStatus(id, arguments))
                    "agent.slots" -> emitResult(id, rootSlots(id))
                    "read" -> emitResult(id, read(id, arguments))
                    "write" -> emitResult(id, write(id, arguments))
                    "scan" -> { launchScan(this, id, arguments); streamOwnsId = true }
                    "observe" -> { launchObserve(this, id, arguments); streamOwnsId = true }
                    "stream.stop" -> emitResult(id, stopStream(arguments))
                    else -> throw CliFailure(ExitCode.USAGE, "unknown session command '$command'")
                }
            } catch (error: Throwable) {
                emitError(id, null, RootCommand.failure(error))
            } finally {
                if (!streamOwnsId) releaseId(id)
            }
        }
        scope.launch {
            stateLock.withLock { commands[id] = job }
            job.start()
        }
    }

    private suspend fun cancelAllActive() {
        val jobs = stateLock.withLock { (active.values + commands.values).distinct() }
        jobs.forEach { it.cancel() }
    }

    private suspend fun cancelAndJoinAll() {
        val jobs = stateLock.withLock {
            val snapshot = (active.values + commands.values + streamForwarders.values).distinct()
            streamOutputs.values.forEach { it.close() }
            active.clear(); commands.clear(); activeIds.clear(); streamOutputs.clear(); streamForwarders.clear(); slowStreams.clear()
            snapshot
        }
        jobs.forEach { it.cancelAndJoin() }
    }

    /** Each stream has its own bounded queue, so an unread stream cannot consume all session memory. */
    private suspend fun registerStreamOutput(scope: CoroutineScope, streamId: Long) {
        val events = BoundedStreamOutput<PendingOutput>()
        val forwarder = scope.launch(Dispatchers.Default) {
            events.forwardTo(output)
        }
        stateLock.withLock {
            streamOutputs[streamId] = events
            streamForwarders[streamId] = forwarder
        }
    }

    private suspend fun closeStreamOutput(streamId: Long) {
        val state = stateLock.withLock {
            streamOutputs.remove(streamId)?.let { it to streamForwarders.remove(streamId) }
        } ?: return
        state.first.close()
        state.second?.join()
    }

    private suspend fun rootStatus(commandId: String, arguments: JsonObject): JsonElement {
        if (arguments.isNotEmpty()) throw CliFailure(ExitCode.USAGE, "agent.status takes no arguments; operator scope is selected at session startup")
        requireFeature(AgentFeature.STATUS, "agent.status")
        return operations(commandId).status().jsonForSession()
    }

    private suspend fun rootSlots(commandId: String): JsonElement {
        requireFeature(AgentFeature.GLOBAL_SLOTS, "agent.slots")
        return operations(commandId).slots().let { buildJsonObject { put("free", it.free); put("total", it.total) } }
    }

    /**
     * The same capability gate the one-shot commands apply. `slots` in particular is answered from
     * an event an agent without the capability never sends, so an ungated call waits out the whole
     * operation deadline and reports retryable instead of unsupported.
     */
    private suspend fun requireFeature(feature: AgentFeature, command: String) {
        if (!gateway.capabilities().supports(feature)) unsupportedFeature(feature, command)
    }

    private suspend fun read(commandId: String, arguments: JsonObject): JsonElement {
        val (handle, service, characteristic) = characteristicArgs("read", arguments)
        val bytes = operations(commandId).read(handle, service, characteristic)
        return bytesJsonValue(bytes)
    }

    private fun operations(commandId: String? = null) =
        RemoteOperationService(gateway, root.resolvedConfig().agent.endpoint, audit, sessionId, commandId)

    private suspend fun write(commandId: String, arguments: JsonObject): JsonElement {
        val (handle, service, characteristic) = characteristicArgs("write", arguments)
        val payload = decodePayload(arguments)
        val type = arguments.stringArgument("write", "writeType") ?: throw CliFailure(ExitCode.USAGE, "writeType is required")
        if (type != "with-response" && type != "without-response") throw CliFailure(ExitCode.USAGE, "writeType is invalid")
        val endpoint = root.resolvedConfig().agent.endpoint
        WriteOperationService(gateway, root.config().policy, endpoint, audit).execute(
            WriteOperationService.Request(handle, service, characteristic, payload, type == "with-response", sessionId = sessionId, commandId = commandId),
        )
        return buildJsonObject { put("handle", handle); put("length", payload.size); put("withResponse", type == "with-response") }
    }

    private suspend fun launchScan(scope: CoroutineScope, id: String, arguments: JsonObject) {
        val filters = listOfNotNull(
            ScanFilter(
                arguments.stringArgument("scan", "serviceUuid")?.let(::normalizeUuid),
                arguments.stringArgument("scan", "name"),
            ),
        )
        val count = arguments.intArgument("scan", "count") ?: 100
        if (count <= 0 || count > root.config().policy.maximumNotificationCount) throw CliFailure(ExitCode.USAGE, "scan count exceeds the local event limit")
        val timeout = arguments.stringArgument("scan", "timeout")?.let(::parseDurationArgument) ?: root.resolvedConfig().scanDuration
        val managed = operations(id).managedScan(filters)
        val streamId = managed.streamId
        registerStreamOutput(scope, streamId)
        val job = scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            var events = 0
            var result = "complete"
            var terminalError: CliFailure? = null
            try {
                // Inside the try: this is the first emit that can fail, and its
                // failure still has to run the teardown in `finally`.
                emit("stream.started", id, streamId, buildJsonObject { put("command", "scan") })
                val completion = collectBoundedStream(managed.events, count, timeout) { ad ->
                    emit("stream.event", id, streamId, ad.json())
                }
                events = completion.count
                result = completion.reason.auditResult
            } catch (error: Throwable) {
                result = streamFailureResult(error, streamId)
                terminalError = terminalErrorFor(result, error)
            } finally {
                emitStreamTerminal(id, streamId, events, result, terminalError, managed)
            }
        }
        startStream(streamId, job)
    }

    private suspend fun launchObserve(scope: CoroutineScope, id: String, arguments: JsonObject) {
        val (handle, service, characteristic) = characteristicArgs("observe", arguments)
        val streamId = gateway.session.nextStreamId()
        val count = arguments.intArgument("observe", "count")
        val timeout = arguments.stringArgument("observe", "timeout")?.let(::parseDurationArgument)
        val unbounded = arguments.booleanArgument("observe", "unbounded") == true
        if (count != null && (count <= 0 || count > root.config().policy.maximumNotificationCount)) throw CliFailure(ExitCode.USAGE, "observe count exceeds the local policy limit")
        if (unbounded && (count != null || timeout != null)) throw CliFailure(ExitCode.USAGE, "unbounded cannot be combined with count or timeout")
        if (count == null && timeout == null && (!unbounded || !root.config().policy.allowUnboundedStreams)) throw CliFailure(ExitCode.USAGE, "observe requires count or timeout unless policy-approved unbounded is explicit")
        registerStreamOutput(scope, streamId)
        val job = scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            var events = 0
            var managed: RemoteOperationService.ManagedOperation<ByteArray>? = null
            var result = "complete"
            var terminalError: CliFailure? = null
            try {
                // Inside the try: this is the first emit that can fail, and its
                // failure still has to run the teardown in `finally`.
                emit("stream.started", id, streamId, buildJsonObject { put("command", "observe") })
                managed = operations(id).managedObserve(handle, service, characteristic, streamId)
                val completion = collectBoundedStream(managed.events, count, timeout) { value ->
                    emit("stream.event", id, streamId, bytesJsonValue(value))
                }
                events = completion.count
                result = completion.reason.auditResult
            } catch (error: Throwable) {
                result = streamFailureResult(error, streamId)
                terminalError = terminalErrorFor(result, error)
            } finally {
                emitStreamTerminal(id, streamId, events, result, terminalError, managed)
            }
        }
        startStream(streamId, job)
    }

    private suspend fun streamFailureResult(error: Throwable, streamId: Long): String = when (error) {
        is kotlinx.coroutines.TimeoutCancellationException -> "timeout"
        is kotlinx.coroutines.CancellationException ->
            if (withContext(NonCancellable) { stateLock.withLock { slowStreams.remove(streamId) } }) "slow-consumer" else "stopped"
        else -> "error"
    }

    private fun terminalErrorFor(result: String, error: Throwable): CliFailure? = when (result) {
        "error" -> RootCommand.failure(error)
        "slow-consumer" -> CliFailure(ExitCode.RETRYABLE, "stream closed because its 256-record output queue was full")
        else -> null
    }

    /**
     * Closes a stream out: its terminal records, the acknowledged BLE stop, and its bookkeeping.
     *
     * All of it runs `NonCancellable`. `stream.stop` and slow-consumer handling reach this through a
     * cancelled job, and the promised `command.error` / `stream.closed` pair is emitted with
     * suspending sends — which a cancelled coroutine would rethrow out of rather than deliver. The
     * records still go through the stream's own queue, so they stay behind the events already
     * queued ahead of them; a downstream that has stopped reading altogether breaks the pipe, and
     * `emit` drops out on `outputBroken` rather than waiting here forever.
     */
    private suspend fun emitStreamTerminal(
        id: String,
        streamId: Long,
        events: Int,
        result: String,
        terminalError: CliFailure?,
        managed: RemoteOperationService.ManagedOperation<*>?,
    ) = withContext(NonCancellable) {
        terminalError?.let { emitError(id, streamId, it) }
        emit("stream.closed", id, streamId, buildJsonObject { put("count", events); put("reason", result) })
        // After the records: a stop that hangs must not swallow the close the caller is waiting on.
        managed?.let { runCatching { it.stopAndAwait(result) } }
        closeStreamOutput(streamId)
        stateLock.withLock { active.remove(streamId); activeIds.remove(id); commands.remove(id) }
    }

    /**
     * Publishes a stream job and starts it. Registration already created this stream's output queue
     * and forwarder, so a failure here has to close them: nothing else holds a reference once the
     * command coroutine unwinds.
     */
    private suspend fun startStream(streamId: Long, job: Job) {
        try {
            stateLock.withLock { active[streamId] = job }
            job.start()
        } catch (error: Throwable) {
            job.cancel()
            withContext(NonCancellable) {
                stateLock.withLock { active.remove(streamId) }
                closeStreamOutput(streamId)
            }
            throw error
        }
    }

    private suspend fun stopStream(arguments: JsonObject): JsonElement {
        val streamId = arguments.integerArgument("stream.stop", "streamId") ?: throw CliFailure(ExitCode.USAGE, "streamId is required")
        val job = stateLock.withLock { active.remove(streamId) } ?: throw CliFailure(ExitCode.NOT_FOUND, "unknown streamId $streamId")
        job.cancelAndJoin()
        return buildJsonObject { put("streamId", streamId); put("stopped", true) }
    }

    private suspend fun emitResult(id: String, value: JsonElement) = emit("command.result", id, null, value)
    private suspend fun emitError(id: String, streamId: Long?, failure: CliFailure) = emit("command.error", id, streamId, buildJsonObject {
        put("exitCode", failure.exitCode.value); put("message", failure.message)
        failure.agentError?.let { error ->
            put("errorKind", error.kind.name)
            error.holder?.let { holder -> put("holder", buildJsonObject { put("principal", holder.principal); holder.clientId?.let { put("clientId", it) } }) }
        }
    })
    private suspend fun emit(type: String, id: String, streamId: Long?, data: JsonElement) {
        if (outputBroken.load()) return
        val pending = PendingOutput(type, id, streamId, data)
        val streamQueue = streamId?.let { stateLock.withLock { streamOutputs[it] } }
        if (streamQueue != null) {
            if (type == "stream.event" && !streamQueue.trySendEvent(pending)) {
                // A saturated stream is terminated independently; command replies and other
                // streams retain their own queues and continue in causal order.
                streamId.let { id -> stateLock.withLock { slowStreams += id; active[id] }?.cancel() }
            } else if (type != "stream.event") {
                streamQueue.sendTerminal(pending)
            }
        } else {
            output.send(pending)
        }
    }

    private fun characteristicArgs(command: String, arguments: JsonObject): Triple<String, String, String> {
        val handle = arguments.stringArgument(command, "handle")?.takeIf { it.isNotEmpty() }
            ?: throw CliFailure(ExitCode.USAGE, "handle is required")
        val service = arguments.stringArgument(command, "serviceUuid")?.let(::normalizeUuid)
            ?: throw CliFailure(ExitCode.USAGE, "serviceUuid is required")
        val characteristic = arguments.stringArgument(command, "characteristicUuid")?.let(::normalizeUuid)
            ?: throw CliFailure(ExitCode.USAGE, "characteristicUuid is required")
        return Triple(handle, service, characteristic)
    }

    private fun validateArguments(command: String, arguments: JsonObject) {
        val allowed = when (command) {
            "agent.status" -> emptySet()
            "agent.slots", "session.close" -> emptySet()
            "read" -> setOf("handle", "serviceUuid", "characteristicUuid")
            "write" -> setOf("handle", "serviceUuid", "characteristicUuid", "hex", "base64", "text", "stdin", "writeType")
            "scan" -> setOf("serviceUuid", "name", "count", "timeout")
            "observe" -> setOf("handle", "serviceUuid", "characteristicUuid", "count", "timeout", "unbounded")
            "stream.stop" -> setOf("streamId")
            else -> throw CliFailure(ExitCode.USAGE, "unknown session command '$command'")
        }
        val unknown = arguments.keys - allowed
        if (unknown.isNotEmpty()) throw CliFailure(ExitCode.USAGE, "unknown argument(s): ${unknown.sorted().joinToString()}")
    }

    private fun decodePayload(arguments: JsonObject): ByteArray {
        // Count keys, not decodable values: the schema's oneOf rejects a record naming two payload
        // sources even when only one of them holds usable data.
        val present = listOf("hex", "base64", "text", "stdin").filter { it in arguments }
        val key = present.singleOrNull()
            ?: throw CliFailure(ExitCode.USAGE, "write requires exactly one payload source")
        if (key == "stdin") throw CliFailure(ExitCode.USAGE, "stdin payloads are only supported by one-shot write")
        val value = arguments.stringArgument("write", key)
            ?: throw CliFailure(ExitCode.USAGE, "write argument '$key' must be a string")
        return when (key) {
            "hex" -> decodeHexSession(value)
            "base64" -> runCatching { Base64.decode(value) }.getOrElse { throw CliFailure(ExitCode.USAGE, "invalid Base64 payload") }
            else -> value.encodeToByteArray()
        }
    }
}

private class HumanShell(
    private val root: RootCommand,
    private val gateway: RemoteBleGateway,
    private val operatorRequested: Boolean,
) {
    private val jobs = mutableMapOf<Long, Job>()
    private val jobsLock = Mutex()
    private val outputLock = Mutex()

    private fun operations() = RemoteOperationService(gateway, root.resolvedConfig().agent.endpoint, root.auditLogger())

    /** The shell reaches the same operations as the one-shot commands and owes the same gate. */
    private suspend fun requireFeature(feature: AgentFeature, command: String) {
        if (!gateway.capabilities().supports(feature)) unsupportedFeature(feature, command)
    }

    /**
     * Serialized, explicitly flushed output. Background stream jobs run on real dispatcher threads,
     * so their lines have to be kept from interleaving with the prompt; `print` would also leave the
     * prompt itself sitting in a buffer, with no trailing newline to flush it.
     */
    private suspend fun emit(text: String) = outputLock.withLock { writeStandardOutput(text.encodeToByteArray()) }

    private suspend fun emitLine(text: String) = emit(text + "\n")

    private suspend fun register(streamId: Long, job: Job) = jobsLock.withLock { jobs[streamId] = job }

    private suspend fun forget(streamId: Long) = jobsLock.withLock { jobs.remove(streamId) }

    suspend fun run() = coroutineScope {
        try {
            gateway.awaitReady()
            if (operatorRequested) {
                if (dev.warsha.remoteble.protocol.Capabilities.AGENT_STATUS !in gateway.capabilities() || !gateway.status().operatorScope) {
                    throw CliFailure(ExitCode.AUTHENTICATION, "agent did not grant operator scope")
                }
            }
            while (true) {
                emit("rble> ")
                // A blocking stdin read must not sit on the runBlocking event loop. Background stream
                // jobs are dispatched from the same context, so reading here froze every `&` job until
                // the operator typed the next line — the machine session already avoids this.
                val line = withContext(Dispatchers.Default) { readStandardInputLine() } ?: break
                val tokens = shellTokens(line)
                if (tokens.isEmpty()) continue
                if (tokens.first() == "exit" || tokens.first() == "quit") break
                // One mistyped argument should not end the session the operator is working in.
                try {
                    dispatch(this, tokens)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    emitLine("error: ${RootCommand.failure(error).message}")
                }
            }
        } finally {
            withContext(NonCancellable) {
                jobsLock.withLock { jobs.values.toList() }.forEach { it.cancelAndJoin() }
                gateway.close()
            }
        }
    }

    private suspend fun dispatch(scope: CoroutineScope, tokens: List<String>) {
        when (tokens.first()) {
            "help" -> emitLine("agent status | agent slots | scan [seconds] [&] | read HANDLE SERVICE CHAR | observe HANDLE SERVICE CHAR --count N [&] | write HANDLE SERVICE CHAR --hex VALUE --write-type TYPE | jobs | stop STREAM_ID | exit")
            "jobs" -> jobsLock.withLock { jobs.keys.sorted() }.forEach { emitLine("$it running") }
            "stop" -> tokens.getOrNull(1)?.toLongOrNull()?.let { streamId ->
                val job = forget(streamId)
                if (job == null) emitLine("unknown stream $streamId") else job.cancelAndJoin()
            } ?: emitLine("usage: stop STREAM_ID")
            "agent" -> when (tokens.getOrNull(1)) {
                "status" -> {
                    requireFeature(AgentFeature.STATUS, "agent status")
                    emitLine(operations().status().human())
                }
                "slots" -> {
                    requireFeature(AgentFeature.GLOBAL_SLOTS, "agent slots")
                    operations().slots().also { emitLine("free=${it.free} total=${it.total}") }
                }
                else -> emitLine("usage: agent status|slots")
            }
            "read" -> if (tokens.size >= 4) {
                emitLine(operations().read(tokens[1], normalizeUuid(tokens[2]), normalizeUuid(tokens[3])).hex())
            } else emitLine("usage: read HANDLE SERVICE_UUID CHARACTERISTIC_UUID")
            "observe" -> observe(scope, tokens)
            "write" -> write(tokens)
            "scan" -> scan(scope, tokens)
            else -> emitLine("unknown command; type help")
        }
    }

    private suspend fun observe(scope: CoroutineScope, tokens: List<String>) {
        if (tokens.size < 4) {
            emitLine("usage: observe HANDLE SERVICE_UUID CHARACTERISTIC_UUID --count N|--timeout Ns [&]")
            return
        }
        val background = tokens.lastOrNull() == "&"
        val count = tokens.windowed(2).firstOrNull { it[0] == "--count" }?.get(1)?.toIntOrNull()
        val timeout = tokens.windowed(2).firstOrNull { it[0] == "--timeout" }?.get(1)?.let(::parseDurationArgument)
        if (count == null && timeout == null) {
            emitLine("observe requires --count or --timeout")
            return
        }
        val handle = tokens[1]
        val service = normalizeUuid(tokens[2])
        val characteristic = normalizeUuid(tokens[3])
        val streamId = gateway.session.nextStreamId()
        val job = scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            var managed: RemoteOperationService.ManagedOperation<ByteArray>? = null
            var result = "complete"
            try {
                managed = operations().managedObserve(handle, service, characteristic, streamId)
                result = collectBoundedStream(managed.events, count, timeout) { value ->
                    emitLine("[$streamId] ${value.hex()}")
                }.reason.auditResult
            } catch (error: Throwable) {
                result = "error"
                throw error
            } finally {
                withContext(NonCancellable) {
                    managed?.let { runCatching { it.stopAndAwait(result) } }
                    forget(streamId)
                }
            }
        }
        register(streamId, job)
        job.start()
        if (!background) job.join()
    }

    private suspend fun scan(scope: CoroutineScope, tokens: List<String>) {
        val background = tokens.lastOrNull() == "&"
        val duration = tokens.drop(1).firstOrNull { it != "&" }?.toDoubleOrNull()?.let { Duration.parse("${it}s") }
            ?: root.resolvedConfig().scanDuration
        val streamId = gateway.session.nextStreamId()
        val job = scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            val managed = operations().managedScan(emptyList(), streamId)
            var result = "complete"
            try {
                result = collectBoundedStream(managed.events, timeout = duration) { ad ->
                    emitLine("[$streamId] ${ad.device.value}\t${ad.rssi} dBm")
                }.reason.auditResult
            } catch (error: Throwable) {
                result = "error"
                throw error
            } finally {
                withContext(NonCancellable) {
                    runCatching { managed.stopAndAwait(result) }
                    forget(streamId)
                }
            }
        }
        register(streamId, job)
        job.start()
        if (!background) job.join()
    }

    private suspend fun write(tokens: List<String>) {
        if (tokens.size < 4) {
            emitLine("usage: write HANDLE SERVICE_UUID CHARACTERISTIC_UUID --hex|--base64|--text VALUE --write-type TYPE")
            return
        }
        val payloadArg = tokens.windowed(2).firstOrNull { it[0] == "--hex" || it[0] == "--base64" || it[0] == "--text" }
        val type = tokens.windowed(2).firstOrNull { it[0] == "--write-type" }?.get(1)
        if (payloadArg == null || type == null || type !in setOf("with-response", "without-response")) {
            emitLine("write requires one payload and --write-type")
            return
        }
        val payload = when (payloadArg[0]) {
            "--hex" -> decodeHexSession(payloadArg[1])
            "--text" -> payloadArg[1].encodeToByteArray()
            else -> runCatching { Base64.decode(payloadArg[1]) }.getOrNull() ?: run {
                emitLine("invalid Base64 payload")
                return
            }
        }
        val handle = tokens[1]
        val service = normalizeUuid(tokens[2])
        val characteristic = normalizeUuid(tokens[3])
        try {
            WriteOperationService(gateway, root.config().policy, root.resolvedConfig().agent.endpoint, root.auditLogger()).execute(
                WriteOperationService.Request(handle, service, characteristic, payload, type == "with-response"),
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            emitLine("write failed: ${RootCommand.failure(error).message}")
            return
        }
        emitLine("wrote ${payload.size} bytes")
    }
}

private fun shellTokens(line: String): List<String> = Regex("(?:\\\\.|'[^']*'|\"[^\"]*\"|\\S)+").findAll(line).map {
    it.value.trim('"', '\'').replace("\\ ", " ").replace("\\\"", "\"").replace("\\\\", "\\")
}.toList()

/** A malformed duration is a caller mistake, not an internal failure: keep it on the usage code. */
private fun parseDurationArgument(value: String): Duration = runCatching { Duration.parse(value) }
    .getOrElse { throw CliFailure(ExitCode.USAGE, "'$value' is not a valid duration") }

private fun decodeHexSession(value: String): ByteArray {
    val text = value.trim()
    if (text.length % 2 != 0 || text.any { it.digitToIntOrNull(16) == null }) throw CliFailure(ExitCode.USAGE, "invalid hex payload")
    return ByteArray(text.length / 2) { i -> ((text[i * 2].digitToInt(16) shl 4) or text[i * 2 + 1].digitToInt(16)).toByte() }
}

private fun dev.warsha.remoteble.protocol.AdvertisementDto.json(): JsonObject = buildJsonObject {
    put("handle", device.value); name?.let { put("name", it.replace(Regex("[\\u0000-\\u001f\\u007f]"), "?")) }; put("rssi", rssi)
    put("serviceUuids", kotlinx.serialization.json.buildJsonArray { serviceUuids.forEach { add(JsonPrimitive(it)) } })
}

private fun dev.warsha.remoteble.protocol.AgentStatusDto.jsonForSession(): JsonObject = buildJsonObject {
    agentInfo?.let { put("agentInfo", it) }
    put("protocolVersion", protocolVersion)
    put("uptimeMs", uptimeMs)
    put("operatorScope", operatorScope)
    put("connectedClients", connectedClients)
    put("otherLeases", otherLeases)
    put("slots", buildJsonObject { put("free", slots.free); put("total", slots.total) })
    put("settings", buildJsonObject {
        put("transportGraceMs", settings.transportGraceMs)
        put("leaseGraceMs", settings.leaseGraceMs)
        put("exclusiveByDefault", settings.exclusiveByDefault)
        put("scanConcurrency", settings.scanConcurrency)
        put("strictIdentifiers", settings.strictIdentifiers)
        put("writePolicyEnforced", settings.writePolicyEnforced)
    })
    put("leases", kotlinx.serialization.json.buildJsonArray {
        leases.forEach { lease -> add(buildJsonObject {
            put("handle", lease.handle)
            lease.name?.let { put("name", it) }
            lease.holder?.let { put("holder", it) }
            put("mine", lease.mine)
            put("connected", lease.connected)
            put("inGrace", lease.inGrace)
            lease.remainingGraceMs?.let { put("remainingGraceMs", it) }
        }) }
    })
}

private fun JsonElement?.asSessionId(): String = (this as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content ?: ""

/**
 * Typed argument access matching `session-input-v1.json`. Every accessor returns `null` only for an
 * *absent* key and rejects a present-but-wrong-typed one, because JSON `null` is not an empty slot
 * here: `JsonNull.content` is the string `"null"`, so a lenient read turns `{"handle": null}` into a
 * device handle literally named `null` and forwards it to the agent. Non-primitives are rejected for
 * the same reason rather than throwing out of `jsonPrimitive` as an internal error.
 */
internal fun JsonObject.stringArgument(command: String, key: String): String? = this[key]?.let { element ->
    (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw CliFailure(ExitCode.USAGE, "$command argument '$key' must be a string")
}

internal fun JsonObject.integerArgument(command: String, key: String): Long? = this[key]?.let { element ->
    (element as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
        ?: throw CliFailure(ExitCode.USAGE, "$command argument '$key' must be an integer")
}

internal fun JsonObject.intArgument(command: String, key: String): Int? = integerArgument(command, key)?.let {
    if (it < Int.MIN_VALUE.toLong() || it > Int.MAX_VALUE.toLong()) {
        throw CliFailure(ExitCode.USAGE, "$command argument '$key' is out of range")
    }
    it.toInt()
}

internal fun JsonObject.booleanArgument(command: String, key: String): Boolean? = this[key]?.let { element ->
    (element as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
        ?: throw CliFailure(ExitCode.USAGE, "$command argument '$key' must be a boolean")
}
