package dev.warsha.remoteble.tools.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

class BoundedStreamTest {
    @Test fun `count is a normal completion`() = runBlocking {
        val values = mutableListOf<Int>()

        val completion = collectBoundedStream(flowOf(1, 2, 3), maximumEvents = 2) { values += it }

        assertEquals(listOf(1, 2), values)
        assertEquals(BoundedStreamCompletion(2, StreamCompletionReason.COUNT), completion)
    }

    @Test fun `deadline is a normal timeout completion`() = runBlocking {
        val completion = collectBoundedStream<Int>(flow { awaitCancellation() }, timeout = 1.milliseconds) {}

        assertEquals(BoundedStreamCompletion(0, StreamCompletionReason.TIMEOUT), completion)
    }

    @Test fun `upstream failure remains an error`() {
        runBlocking {
            assertFailsWith<IllegalStateException> {
                collectBoundedStream<Int>(flow { throw IllegalStateException("agent failed") }) {}
            }
        }
    }
}
