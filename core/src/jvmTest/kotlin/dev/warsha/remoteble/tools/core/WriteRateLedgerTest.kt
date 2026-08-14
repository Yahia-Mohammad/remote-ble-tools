package dev.warsha.remoteble.tools.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WriteRateLedgerTest {
    @Test fun `rolling ledger is atomic and bounded`() {
        val path = Files.createTempDirectory("remoteble-ledger").resolve("writes.jsonl")
        val ledger = WriteRateLedger(path.toString())
        assertTrue(ledger.tryConsume("endpoint|device|char", 1, 60_000))
        assertFalse(ledger.tryConsume("endpoint|device|char", 1, 60_000))
        assertTrue(ledger.tryConsume("other", 1, 60_000))
        val persisted = Files.readString(path)
        assertFalse(persisted.contains("endpoint|device|char"), persisted)
        assertTrue(persisted.contains(sha256Hex("endpoint|device|char")), persisted)
    }

    @Test fun `corrupt ledger fails closed`() {
        val path = Files.createTempDirectory("remoteble-ledger-corrupt").resolve("writes.jsonl")
        Files.writeString(path, "not json\n")
        val error = assertFailsWith<CliFailure> { WriteRateLedger(path.toString()).tryConsume("target", 1) }
        assertEquals(ExitCode.FAILURE, error.exitCode)
    }

    @Test fun `sha256 matches the standard test vector`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc"))
    }

    @Test fun `long target keys remain private and bounded`() {
        val path = Files.createTempDirectory("remoteble-ledger-long").resolve("writes.jsonl")
        val key = "endpoint|" + "device-".repeat(2_000) + "|characteristic"
        val ledger = WriteRateLedger(path.toString())

        assertTrue(ledger.tryConsume(key, 1, 60_000))
        assertFalse(ledger.tryConsume(key, 1, 60_000))
        val persisted = Files.readString(path)
        assertFalse(persisted.contains(key), persisted)
        assertTrue(persisted.contains(sha256Hex(key)), persisted)
    }

    @Test fun `concurrent consumers cannot exceed one permitted write`() {
        val path = Files.createTempDirectory("remoteble-ledger-contention").resolve("writes.jsonl")
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = pool.invokeAll(List(16) {
                Callable { WriteRateLedger(path.toString()).tryConsume("shared-target", 1, 60_000) }
            }).map { it.get() }
            assertEquals(1, results.count { it })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun `persistent lock inode serializes contenders and remains reusable`() {
        val directory = Files.createTempDirectory("remoteble-lock")
        val lock = directory.resolve(".state.lock").toString()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit {
                withFileLock(lock) {
                    entered.countDown()
                    assertTrue(release.await(2, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val second = pool.submit { withFileLock(lock) { secondEntered.countDown() } }
            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            first.get(2, TimeUnit.SECONDS)
            second.get(2, TimeUnit.SECONDS)
            assertTrue(Files.exists(java.nio.file.Path.of(lock)))
            withFileLock(lock) { assertTrue(true) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun `persistent lock fails closed across processes then releases on process exit`() {
        val directory = Files.createTempDirectory("remoteble-process-lock")
        val lock = directory.resolve(".state.lock")
        val holder = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            System.getProperty("java.class.path"),
            FileLockHolder::class.java.name,
            lock.toString(),
            "10000",
        ).start()
        try {
            assertEquals("locked", holder.inputStream.bufferedReader().readLine())
            val started = System.nanoTime()
            val failure = assertFailsWith<CliFailure> { withFileLock(lock.toString()) {} }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(ExitCode.FAILURE, failure.exitCode)
            assertTrue(elapsedMs >= 4_500, "lock failed before the five-second deadline: $elapsedMs ms")
            holder.destroyForcibly()
            assertTrue(holder.waitFor(2, TimeUnit.SECONDS))
            assertTrue(Files.exists(lock))
            withFileLock(lock.toString()) { assertTrue(true) }
        } finally {
            holder.destroyForcibly()
        }
    }
}

/** Separate JVM used to prove advisory locks coordinate across process boundaries. */
object FileLockHolder {
    @JvmStatic fun main(args: Array<String>) {
        withFileLock(args[0]) {
            println("locked")
            System.out.flush()
            Thread.sleep(args[1].toLong())
        }
    }
}
