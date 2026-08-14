package dev.warsha.remoteble.tools.cli

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel

/**
 * Per-stream output buffer for the machine session.
 *
 * Events must never let one slow consumer grow memory without bound, so they are offered to a
 * fixed queue and the caller can terminate only that stream when it is full. Terminal records use
 * a suspending send instead: once cancellation starts, the consumer must still see the causal
 * `command.error`/`stream.closed` pair after the already queued events.
 */
internal class BoundedStreamOutput<T>(capacity: Int = CAPACITY) {
    private val queue = Channel<T>(capacity)

    fun trySendEvent(value: T): Boolean = queue.trySend(value).isSuccess

    suspend fun sendTerminal(value: T) = queue.send(value)

    fun close() = queue.close()

    suspend fun forwardTo(destination: SendChannel<T>) {
        for (value in queue) destination.send(value)
    }

    internal companion object {
        const val CAPACITY = 256
    }
}
