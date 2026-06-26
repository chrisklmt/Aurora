package gr.hua.aurora.protocol

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

data class PrivateChatMessagePayload(
    val senderUsername: String,
    val body: String
) {
    init {
        require(senderUsername.isNotBlank()) {
            "Private chat senderUsername must not be blank."
        }
        require(body.isNotBlank()) {
            "Private chat body must not be blank."
        }
    }
}

object PrivateChatMessagePayloadCodec {
    private const val formatVersion = "AURORA_PRIVATE_CHAT_V1"
    private const val separator = "|"
    private const val expectedPartCount = 3
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(payload: PrivateChatMessagePayload): String {
        return listOf(
            formatVersion,
            encodeField(payload.senderUsername),
            encodeField(payload.body)
        ).joinToString(separator)
    }

    fun decode(encodedPayload: String): PrivateChatMessagePayload {
        val parts = encodedPayload.split(separator, limit = expectedPartCount)
        require(parts.size == expectedPartCount) {
            "Invalid private chat payload part count: ${parts.size}."
        }
        require(parts[0] == formatVersion) {
            "Unsupported private chat payload version: ${parts[0]}."
        }

        return PrivateChatMessagePayload(
            senderUsername = decodeField(parts[1], "senderUsername"),
            body = decodeField(parts[2], "body")
        )
    }

    fun decodeOrNull(encodedPayload: String): PrivateChatMessagePayload? {
        if (!encodedPayload.startsWith("$formatVersion$separator")) {
            return null
        }

        return runCatching {
            decode(encodedPayload)
        }.getOrNull()
    }

    private fun encodeField(value: String): String {
        return encoder.encodeToString(value.toByteArray(UTF_8))
    }

    private fun decodeField(
        token: String,
        fieldName: String
    ): String {
        return try {
            String(decoder.decode(token), UTF_8)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException(
                "Invalid private chat payload encoding for $fieldName.",
                error
            )
        }
    }
}
