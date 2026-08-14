package dev.warsha.remoteble.tools.core

private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

/** Normalizes 16-, 32-, and 128-bit Bluetooth UUID spellings at the CLI boundary. */
fun normalizeUuid(value: String): String {
    val compact = value.trim().removePrefix("0x").replace("-", "").lowercase()
    if (compact.length !in setOf(4, 8, 32) || compact.any { it.digitToIntOrNull(16) == null }) {
        throw CliFailure(ExitCode.USAGE, "invalid Bluetooth UUID '$value'")
    }
    return when (compact.length) {
        4 -> "0000$compact$BASE_SUFFIX"
        8 -> "$compact$BASE_SUFFIX"
        else -> compact.chunked(8).let { "${it[0]}-${it[1].take(4)}-${it[1].drop(4)}-${it[2].take(4)}-${it[2].drop(4)}${it[3]}" }
    }
}

private val SIG_NAMES = mapOf(
    "00001800-0000-1000-8000-00805f9b34fb" to "Generic Access",
    "00001801-0000-1000-8000-00805f9b34fb" to "Generic Attribute",
    "0000180d-0000-1000-8000-00805f9b34fb" to "Heart Rate",
    "0000180f-0000-1000-8000-00805f9b34fb" to "Battery Service",
    "00002a19-0000-1000-8000-00805f9b34fb" to "Battery Level",
    "00002a37-0000-1000-8000-00805f9b34fb" to "Heart Rate Measurement",
    "00002a38-0000-1000-8000-00805f9b34fb" to "Body Sensor Location",
    "00002a39-0000-1000-8000-00805f9b34fb" to "Heart Rate Control Point",
)

fun sigName(uuid: String): String? = SIG_NAMES[runCatching { normalizeUuid(uuid) }.getOrNull()]

fun gattPropertyNames(properties: Int): List<String> = buildList {
    if (properties and 0x01 != 0) add("broadcast")
    if (properties and 0x02 != 0) add("read")
    if (properties and 0x04 != 0) add("write-without-response")
    if (properties and 0x08 != 0) add("write")
    if (properties and 0x10 != 0) add("notify")
    if (properties and 0x20 != 0) add("indicate")
    if (properties and 0x40 != 0) add("authenticated-signed-writes")
    if (properties and 0x80 != 0) add("extended")
}
