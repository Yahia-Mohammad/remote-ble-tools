package dev.warsha.remoteble.tools.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class BoundedStreamOutputTest {
    @Test fun `a full event queue refuses one stream while preserving its terminal record`() = runBlocking {
        val stream = BoundedStreamOutput<Int>()
        repeat(BoundedStreamOutput.CAPACITY) { assertTrue(stream.trySendEvent(it)) }
        assertFalse(stream.trySendEvent(BoundedStreamOutput.CAPACITY), "a slow stream must not grow beyond its fixed queue")

        // A terminal record is deliberately not discarded. It waits until forwarding creates room,
        // then remains after every event already accepted by the queue.
        val terminal = async { stream.sendTerminal(-1) }
        assertFalse(terminal.isCompleted, "terminal output must wait for capacity rather than be dropped")

        val delivered = Channel<Int>()
        val forwarder = launch { stream.forwardTo(delivered) }
        repeat(BoundedStreamOutput.CAPACITY) { assertEquals(it, delivered.receive()) }
        assertEquals(-1, delivered.receive())
        terminal.await()
        stream.close()
        forwarder.join()
    }
}
