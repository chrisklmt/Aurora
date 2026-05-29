package gr.hua.aurora.protocol

import java.util.Base64

object EncryptedPayloadFrameCodec {
    private const val kindToken = "AURORA_ENCRYPTED_PAYLOAD"
    private const val separator = "|"
    private const val expectedPartCount = 4
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(frame: EncryptedPayloadFrame): String {
        return listOf(
            kindToken,
            frame.protocolVersion.toString(),
            encoder.encodeToString(frame.nonce),
            encoder.encodeToString(frame.ciphertext)
        ).joinToString(separator)
    }

    fun decode(encoded: String): EncryptedPayloadFrame {
        val parts = encoded.split(separator, limit = expectedPartCount)
        require(parts.size == expectedPartCount) {
            "Invalid encrypted payload part count: ${parts.size}."
        }
        require(parts[0] == kindToken) {
            "Unsupported encrypted payload kind: ${parts[0]}."
        }

        val protocolVersion = parts[1].toIntOrNull()
            ?: throw IllegalArgumentException("Invalid encrypted payload version: ${parts[1]}.")

        return EncryptedPayloadFrame(
            protocolVersion = protocolVersion,
            nonce = decodeField(parts[2], "nonce"),
            ciphertext = decodeField(parts[3], "ciphertext")
        )
    }

    private fun decodeField(
        token: String,
        fieldName: String
    ): ByteArray {
        return try {
            decoder.decode(token)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException(
                "Invalid encrypted payload field encoding for $fieldName.",
                error
            )
        }
    }
}
