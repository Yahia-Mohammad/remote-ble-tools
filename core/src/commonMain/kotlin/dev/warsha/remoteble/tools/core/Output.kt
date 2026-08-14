package dev.warsha.remoteble.tools.core

import kotlin.io.encoding.Base64
import kotlinx.serialization.json.JsonObject
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
