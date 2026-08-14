package dev.warsha.remoteble.tools.core

import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withTimeoutOrNull

/** Normal terminal reasons for a caller-bounded stream. */
enum class StreamCompletionReason(val auditResult: String) {
    COUNT("count"),
    TIMEOUT("timeout"),
    COMPLETE("complete"),
}

data class BoundedStreamCompletion(val count: Int, val reason: StreamCompletionReason)

/**
 * Collects a bounded stream without turning its caller-provided deadline into an exception.
 * Cancellation and upstream failures deliberately propagate to the frontend so it can distinguish
 * explicit stop, slow-consumer closure, broken output, and agent errors.
 */
suspend fun <T> collectBoundedStream(
    events: Flow<T>,
    maximumEvents: Int? = null,
    timeout: Duration? = null,
    consume: suspend (T) -> Unit,
): BoundedStreamCompletion {
    var count = 0
    val bounded = if (maximumEvents == null) events else events.take(maximumEvents)
    val completed = if (timeout == null) {
        bounded.collect { event ->
            consume(event)
            count += 1
        }
        true
    } else {
        withTimeoutOrNull(timeout) {
            bounded.collect { event ->
                consume(event)
                count += 1
            }
            true
        } == true
    }
    val reason = when {
        !completed -> StreamCompletionReason.TIMEOUT
        maximumEvents != null && count >= maximumEvents -> StreamCompletionReason.COUNT
        else -> StreamCompletionReason.COMPLETE
    }
    return BoundedStreamCompletion(count, reason)
}
