package dev.warsha.remoteble.tools.core

import dev.warsha.remoteble.protocol.AgentError
import dev.warsha.remoteble.protocol.AgentException
import dev.warsha.remoteble.protocol.ErrorKind
import io.ktor.client.plugins.ClientRequestException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.io.IOException

/** Stable process exit codes for scripting callers. */
enum class ExitCode(val value: Int) {
    OK(0),
    USAGE(2),
    AUTHENTICATION(4),
    NOT_FOUND(5),
    BUSY(6),
    UNSUPPORTED(7),
    INDETERMINATE(8),
    RETRYABLE(9),
    FAILURE(1),
}

class CliFailure(
    val exitCode: ExitCode,
    override val message: String,
    cause: Throwable? = null,
    val agentError: AgentError? = null,
) : RuntimeException(message, cause)

/** Maps protocol and transport failures to the stable contract shared by every frontend. */
fun operationFailure(error: Throwable): CliFailure = when (error) {
    is CliFailure -> error
    is AgentException -> {
        val code = when (error.error.kind) {
            ErrorKind.UNKNOWN_DEVICE, ErrorKind.CHARACTERISTIC_NOT_FOUND -> ExitCode.NOT_FOUND
            ErrorKind.PERIPHERAL_BUSY -> ExitCode.BUSY
            ErrorKind.POLICY_DENIED, ErrorKind.UNSUPPORTED, ErrorKind.INCOMPATIBLE_PROTOCOL -> ExitCode.UNSUPPORTED
            else -> if (error.error.kind.transient) ExitCode.RETRYABLE else ExitCode.FAILURE
        }
        CliFailure(code, error.error.message ?: error.error.kind.name, error, error.error)
    }
    is TimeoutCancellationException -> CliFailure(ExitCode.RETRYABLE, "Operation deadline exceeded", error)
    // Must precede the generic branch: a coroutine's cancellation message is an internal class name
    // ("LazyStandaloneCoroutine was cancelled"), and this mapper feeds the stable JSONL protocol.
    is kotlin.coroutines.cancellation.CancellationException ->
        CliFailure(ExitCode.RETRYABLE, "operation was cancelled before it completed", error)
    is ClientRequestException -> CliFailure(ExitCode.AUTHENTICATION, "Agent rejected the handshake or authentication", error)
    is IOException -> CliFailure(ExitCode.RETRYABLE, "Agent connection failed: ${error.message}", error)
    else -> CliFailure(ExitCode.FAILURE, error.message ?: error::class.simpleName.orEmpty(), error)
}

fun unsupportedFeature(feature: AgentFeature, command: String): Nothing =
    throw CliFailure(
        ExitCode.UNSUPPORTED,
        "$command requires the agent feature ${feature.name}. Upgrade the RemoteBLE agent to a release that advertises it.",
    )
