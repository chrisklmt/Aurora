package gr.hua.aurora.protocol

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

object MessageFrameCodec {
    private const val formatVersion = "AURORA_FRAME_V1"
    private const val separator = "|"
    private const val nullToken = "~"
    private const val expectedPartCount = 8
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    // Το codec μένει σκόπιμα dependency-free ώστε να δοκιμάζεται νωρίς χωρίς transport, storage ή άλλη υποδομή.
    fun encode(frame: MessageFrame): String {
        require(frame.id.isNotBlank()) { "Frame id must not be blank." }
        require(frame.senderId.isNotBlank()) { "Frame senderId must not be blank." }
        require(frame.createdAtMillis >= 0L) { "Frame createdAtMillis must be non-negative." }
        require(frame.ttl >= 1) { "Frame ttl must be at least 1." }

        return listOf(
            formatVersion,
            encodeField(frame.id),
            frame.type.name,
            encodeField(frame.senderId),
            frame.recipientId?.let(::encodeField) ?: nullToken,
            frame.createdAtMillis.toString(),
            frame.ttl.toString(),
            encodeField(frame.payload)
        ).joinToString(separator)
    }

    fun decode(encodedFrame: String): MessageFrame {
        val parts = encodedFrame.split(separator, limit = expectedPartCount)
        require(parts.size == expectedPartCount) { "Invalid frame part count: ${parts.size}." }
        require(parts[0] == formatVersion) { "Unsupported frame version: ${parts[0]}." }

        val type = runCatching { MessageFrameType.valueOf(parts[2]) }
            .getOrElse { throw IllegalArgumentException("Invalid frame type: ${parts[2]}.", it) }
        val createdAtMillis = parts[5].toLongOrNull()
            ?: throw IllegalArgumentException("Invalid frame timestamp: ${parts[5]}.")
        val ttl = parts[6].toIntOrNull()
            ?: throw IllegalArgumentException("Invalid frame ttl: ${parts[6]}.")

        require(ttl >= 1) { "Frame ttl must be at least 1." }

        return MessageFrame(
            id = decodeField(parts[1], "id"),
            type = type,
            senderId = decodeField(parts[3], "senderId"),
            recipientId = decodeNullableField(parts[4], "recipientId"),
            createdAtMillis = createdAtMillis,
            ttl = ttl,
            payload = decodeField(parts[7], "payload")
        )
    }

    private fun encodeField(value: String): String {
        return encoder.encodeToString(value.toByteArray(UTF_8))
    }

    private fun decodeNullableField(
        token: String,
        fieldName: String
    ): String? {
        return if (token == nullToken) {
            null
        } else {
            decodeField(token, fieldName)
        }
    }

    private fun decodeField(
        token: String,
        fieldName: String
    ): String {
        return try {
            String(decoder.decode(token), UTF_8)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid frame field encoding for $fieldName.", error)
        }
    }
}
