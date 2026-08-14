@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package dev.warsha.remoteble.tools.core

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.posix.usleep

/**
 * The JVM proves cross-thread exclusion through `OverlappingFileLockException`; Native cannot borrow
 * that result. Its `fcntl` record lock is owned by the *process*, so without an in-process gate two
 * session jobs both enter the critical section and the first to finish releases the file lock for
 * both by closing its descriptor — silently, with no error to observe. This is the coverage whose
 * absence let that through.
 */
class FileLockNativeTest {
    @Test fun `withFileLock excludes concurrent jobs inside one process`() = runBlocking {
        val directory = (environmentVariable("TMPDIR")?.trimEnd('/') ?: "/tmp") + "/remoteble-native-lock"
        ensureDirectory(directory)
        val lock = "$directory/.state.lock"
        val inside = AtomicBoolean(false)
        val violations = AtomicInt(0)
        val waiting = AtomicInt(0)
        val peakWaiting = AtomicInt(0)

        coroutineScope {
            repeat(WORKERS) {
                launch(Dispatchers.Default) {
                    repeat(ROUNDS) {
                        recordPeak(waiting.incrementAndFetch(), peakWaiting)
                        try {
                            withFileLock(lock) {
                                if (!inside.compareAndSet(false, true)) violations.incrementAndFetch()
                                usleep(200u)
                                inside.store(false)
                            }
                        } finally {
                            waiting.decrementAndFetch()
                        }
                    }
                }
            }
        }

        // Without this the zero-violation assertion below could pass on a single-threaded
        // dispatcher without ever having exercised the thing under test.
        assertTrue(peakWaiting.load() > 1, "no contention was produced; the exclusion result is vacuous")
        assertEquals(0, violations.load(), "two jobs held the state lock at the same time")
    }

    private fun recordPeak(observed: Int, peak: AtomicInt) {
        while (true) {
            val current = peak.load()
            if (observed <= current || peak.compareAndSet(current, observed)) return
        }
    }

    private companion object {
        const val WORKERS = 4
        const val ROUNDS = 25
    }
}
