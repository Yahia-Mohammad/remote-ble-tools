package dev.warsha.remoteble.tools.cli

import dev.warsha.remoteble.tools.core.withFileLock
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fflush
import platform.posix.sleep
import platform.posix.stdout

/** Test-only Native entry point used by the matching-host JVM-to-Native lock handoff suite. */
@OptIn(ExperimentalForeignApi::class)
fun nativeLockHolder(args: Array<String>) {
    require(args.size == 2) { "usage: native-lock-holder PATH HOLD_SECONDS" }
    println("ready")
    fflush(stdout)
    require(readln() == "go") { "native-lock-holder expected the go signal" }
    println("attempting")
    fflush(stdout)
    withFileLock(args[0]) {
        println("locked")
        fflush(stdout)
        sleep(args[1].toUInt())
    }
}
