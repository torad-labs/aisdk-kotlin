package ai.torad.aisdk.providers

import ai.torad.aisdk.*
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * AWS Smithy binary event-stream framing for Bedrock `converse-stream`.
 *
 * Reads one Smithy binary frame at a time off the response channel — the 4-byte
 * total-length prelude, then the remaining bytes — decodes each frame's
 * `:event-type`/`:exception-type`/`:error-code` headers and JSON payload, and reshapes it into the
 * JSON-line payload the stream-state consumer expects (mirrors the v6
 * `:event-type` wrapping).
 */
internal object BedrockEventStream {
    /** Read and decode Smithy binary event-stream frames off [channel] one at a
     *  time, sending each reshaped payload through [scope] as it arrives. */
    suspend fun sendFrames(scope: ProducerScope<String>, channel: ByteReadChannel) {
        while (true) {
            val prelude = readFrame(channel, 4) ?: break
            val totalLength = readInt32BE(prelude, 0)
            if (totalLength < 16) break
            val rest = readFrame(channel, totalLength - 4) ?: break
            for (message in decode(prelude + rest)) {
                scope.send(messagePayload(message))
            }
        }
    }

    /** Reshape one decoded event-stream message into the JSON-line payload the
     *  stream state consumes (mirrors the v6 `:event-type` wrapping). */
    private fun messagePayload(message: Message): String {
        val payload = runCatching { aiSdkJson.parseToJsonElement(message.payloadText) }.getOrNull()
        val eventType = message.eventType
        return if (eventType.isBlank()) {
            message.payloadText
        } else if (payload is JsonObject && payload.size == 1 && payload[eventType] != null) {
            message.payloadText
        } else {
            buildJsonObject {
                put(eventType, payload ?: JsonPrimitive(message.payloadText))
            }.toString()
        }
    }

    /** Read exactly [count] bytes off the channel, or null at a clean EOF (the
     *  buffered decoder likewise stops on an incomplete trailing frame). */
    private suspend fun readFrame(channel: ByteReadChannel, count: Int): ByteArray? {
        val out = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = channel.readAvailable(out, read, count - read)
            if (n < 0) {
                // A clean EOF at a frame boundary (read == 0) is the normal end of
                // the stream. EOF after a partial read means the server cut a frame
                // mid-flight — surface it instead of silently returning a truncated
                // generation. (readAvailable suspends via awaitContent when the
                // buffer is momentarily empty, so this never busy-spins.)
                if (read == 0) return null
                throw InvalidResponseDataError(null, "Bedrock event stream truncated: got $read of $count frame bytes before EOF")
            }
            read += n
        }
        return out
    }

    private data class Message(
        val messageType: String,
        val eventType: String,
        val payloadText: String,
    )

    private fun decode(bytes: ByteArray): List<Message> {
        val messages = mutableListOf<Message>()
        var offset = 0
        while (offset + 16 <= bytes.size) {
            val totalLength = readInt32BE(bytes, offset)
            val headersLength = readInt32BE(bytes, offset + 4)
            if (totalLength < 16 || headersLength < 0 || offset + totalLength > bytes.size) break
            validateCrc32(
                expected = readInt32BE(bytes, offset + 8),
                actual = crc32(bytes, offset, offset + 8),
                label = "prelude",
            )
            validateCrc32(
                expected = readInt32BE(bytes, offset + totalLength - 4),
                actual = crc32(bytes, offset, offset + totalLength - 4),
                label = "message",
            )
            val headersStart = offset + 12
            val payloadStart = headersStart + headersLength
            val payloadEnd = offset + totalLength - 4
            if (payloadStart > payloadEnd || payloadEnd > bytes.size) break
            val headers = readSmithyHeaders(bytes, headersStart, payloadStart)
            val payload = bytes.copyOfRange(payloadStart, payloadEnd).decodeToString()
            messages += Message(
                messageType = headers[":message-type"].orEmpty(),
                eventType = headers[":event-type"].orEmpty()
                    .ifBlank { headers[":exception-type"].orEmpty() }
                    .ifBlank { headers[":error-code"].orEmpty().replaceFirstChar { it.lowercaseChar() } },
                payloadText = payload,
            )
            offset += totalLength
        }
        return messages
    }

    private fun readSmithyHeaders(bytes: ByteArray, start: Int, end: Int): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        var offset = start
        while (offset < end) {
            val nameLength = bytes[offset].toInt() and 0xff
            offset += 1
            if (nameLength == 0 || offset + nameLength + 1 > end) break
            val name = bytes.copyOfRange(offset, offset + nameLength).decodeToString()
            offset += nameLength
            val type = bytes[offset].toInt() and 0xff
            offset += 1
            when (type) {
                0, 1 -> headers[name] = type.toString()
                2 -> offset += 1
                3 -> offset += 2
                4 -> offset += 4
                5, 8 -> offset += 8
                6, 7 -> {
                    if (offset + 2 > end) break
                    val length = readUInt16BE(bytes, offset)
                    offset += 2
                    if (offset + length > end) break
                    if (type == 7) headers[name] = bytes.copyOfRange(offset, offset + length).decodeToString()
                    offset += length
                }
                9 -> offset += 16
                else -> break
            }
        }
        return headers
    }

    private fun readInt32BE(bytes: ByteArray, index: Int): Int =
        ((bytes[index].toInt() and 0xff) shl 24) or
            ((bytes[index + 1].toInt() and 0xff) shl 16) or
            ((bytes[index + 2].toInt() and 0xff) shl 8) or
            (bytes[index + 3].toInt() and 0xff)

    private fun readUInt16BE(bytes: ByteArray, index: Int): Int =
        ((bytes[index].toInt() and 0xff) shl 8) or (bytes[index + 1].toInt() and 0xff)

    private fun validateCrc32(expected: Int, actual: Int, label: String) {
        if (expected != actual) {
            throw InvalidResponseDataError(
                null,
                "Bedrock event stream $label CRC mismatch: expected ${expected.toUIntString()}, got ${actual.toUIntString()}",
            )
        }
    }

    private fun crc32(bytes: ByteArray, start: Int, end: Int): Int {
        var crc = -1
        for (index in start until end) {
            crc = crc xor (bytes[index].toInt() and 0xff)
            repeat(8) {
                crc = if ((crc and 1) != 0) {
                    (crc ushr 1) xor CRC32_POLYNOMIAL
                } else {
                    crc ushr 1
                }
            }
        }
        return crc.inv()
    }

    private fun Int.toUIntString(): String = (toLong() and 0xffff_ffffL).toString()

    private const val CRC32_POLYNOMIAL: Int = -306674912 // 0xedb88320
}
