package gr.hua.aurora.protocol

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

object EncryptedMessageEnvelopeCodec {
    private const val kindToken = "AURORA_ENCRYPTED_MESSAGE"
    private const val separator = "|"
    private const val expectedPartCount = 4
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(envelope: EncryptedMessageEnvelope): String {
        val encodedPayload = EncryptedPayloadFrameCodec.encode(envelope.payload)

        return listOf(
            kindToken,
            envelope.protocolVersion.toString(),
            encoder.encodeToString(envelope.senderPublicKey),
            encoder.encodeToString(encodedPayload.toByteArray(UTF_8))
        ).joinToString(separator)
    }

    fun decode(encoded: String): EncryptedMessageEnvelope {
        val parts = encoded.split(separator, limit = expectedPartCount)
        require(parts.size == expectedPartCount) {
            "Invalid encrypted message part count: ${parts.size}."
        }
        require(parts[0] == kindToken) {
            "Unsupported encrypted message kind: ${parts[0]}."
        }

        val protocolVersion = parts[1].toIntOrNull()
            ?: throw IllegalArgumentException("Invalid encrypted message version: ${parts[1]}.")
        val payloadToken = String(decodeField(parts[3], "payload"), UTF_8)

        return EncryptedMessageEnvelope(
            protocolVersion = protocolVersion,
            senderPublicKey = decodeField(parts[2], "senderPublicKey"),
            payload = EncryptedPayloadFrameCodec.decode(payloadToken)
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
                "Invalid encrypted message field encoding for $fieldName.",
                error
            )
        }
    }
}
