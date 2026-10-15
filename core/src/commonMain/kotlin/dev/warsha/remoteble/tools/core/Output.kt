package dev.warsha.remoteble.tools.core

import dev.warsha.remoteble.protocol.AdvertisementDto
import kotlin.io.encoding.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val HEX_DIGITS = "0123456789abcdef"

fun ByteArray.hex(): String = buildString(size * 2) {
    for (byte in this@hex) {
        val value = byte.toInt() and 0xff
        append(HEX_DIGITS[value ushr 4])
        append(HEX_DIGITS[value and 0x0f])
    }
}
fun ByteArray.base64(): String = Base64.encode(this)

fun bytesJsonValue(value: ByteArray): JsonObject = buildJsonObject {
    put("hex", value.hex())
    put("base64", value.base64())
    put("length", value.size)
}

fun escapeDeviceText(value: String?, maximumLength: Int = 256): String? = value?.take(maximumLength)?.replace(Regex("[\\u0000-\\u001f\\u007f]")) {
    "\\u" + it.value[0].code.toString(16).padStart(4, '0')
}

/**
 * The `scan.fields` an agent sent (RemoteBLE 0.15+), each written only when present: an agent that
 * does not advertise the capability, or a platform that does not report a field, leaves it out.
 * Absence says the agent's platform did not report the value, not that the advertisement lacked it.
 */
fun JsonObjectBuilder.putScanFields(advertisement: AdvertisementDto) {
    if (advertisement.serviceData.isNotEmpty()) {
        put("serviceData", buildJsonObject {
            advertisement.serviceData.entries.sortedBy { it.key }.forEach { (uuid, value) -> put(uuid, bytesJsonValue(value)) }
        })
    }
    advertisement.txPower?.let { put("txPower", it) }
    advertisement.isConnectable?.let { put("connectable", it) }
    escapeDeviceText(advertisement.peripheralName)?.let { put("peripheralName", it) }
}
