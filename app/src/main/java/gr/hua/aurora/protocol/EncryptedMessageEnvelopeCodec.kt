package gr.hua.aurora.protocol

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

object EncryptedMessageEnvelopeCodec {
    private const val kindToken = "AURORA_ENCRYPTED_MESSAGE"
    private const val separator = "|"
    private const val legacyExpectedPartCount = 4
    private const val relayAwareExpectedPartCount = 7
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(envelope: EncryptedMessageEnvelope): String {
        val encodedPayload = EncryptedPayloadFrameCodec.encode(envelope.payload)

        return if (envelope.relayMetadata == null) {
            listOf(
                kindToken,
                envelope.protocolVersion.toString(),
                encoder.encodeToString(envelope.senderPublicKey),
                encoder.encodeToString(encodedPayload.toByteArray(UTF_8))
            ).joinToString(separator)
        } else {
            listOf(
                kindToken,
                envelope.protocolVersion.toString(),
                encoder.encodeToString(envelope.senderPublicKey),
                encodeField(envelope.relayMetadata.messageId),
                envelope.relayMetadata.messageType.name,
                envelope.relayMetadata.ttl.toString(),
                encoder.encodeToString(encodedPayload.toByteArray(UTF_8))
            ).joinToString(separator)
        }
    }

    fun decode(encoded: String): EncryptedMessageEnvelope {
        val allParts = encoded.split(separator)
        require(allParts.size >= legacyExpectedPartCount) {
            "Invalid encrypted message part count: ${allParts.size}."
        }
        require(allParts[0] == kindToken) {
            "Unsupported encrypted message kind: ${allParts[0]}."
        }

        val protocolVersion = allParts[1].toIntOrNull()
            ?: throw IllegalArgumentException("Invalid encrypted message version: ${allParts[1]}.")
        return when (protocolVersion) {
            EncryptedMessageEnvelope.LEGACY_PROTOCOL_VERSION -> decodeLegacy(allParts)
            EncryptedMessageEnvelope.RELAY_AWARE_PROTOCOL_VERSION -> decodeRelayAware(allParts)
            else -> throw IllegalArgumentException("Unsupported encrypted message version: $protocolVersion.")
        }
    }

    private fun decodeLegacy(
        parts: List<String>
    ): EncryptedMessageEnvelope {
        require(parts.size == legacyExpectedPartCount) {
            "Invalid encrypted message part count: ${parts.size}."
        }
        val payloadToken = String(decodeField(parts[3], "payload"), UTF_8)
        return EncryptedMessageEnvelope(
            protocolVersion = EncryptedMessageEnvelope.LEGACY_PROTOCOL_VERSION,
            senderPublicKey = decodeField(parts[2], "senderPublicKey"),
            payload = EncryptedPayloadFrameCodec.decode(payloadToken)
        )
    }

    private fun decodeRelayAware(
        parts: List<String>
    ): EncryptedMessageEnvelope {
        require(parts.size == relayAwareExpectedPartCount) {
            "Invalid encrypted message part count: ${parts.size}."
        }
        val relayMessageType = runCatching {
            MessageFrameType.valueOf(parts[4])
        }.getOrElse {
            throw IllegalArgumentException("Invalid encrypted relay message type: ${parts[4]}.", it)
        }
        val relayTtl = parts[5].toIntOrNull()
            ?: throw IllegalArgumentException("Invalid encrypted relay ttl: ${parts[5]}.")
        val payloadToken = String(decodeField(parts[6], "payload"), UTF_8)
        return EncryptedMessageEnvelope(
            protocolVersion = EncryptedMessageEnvelope.RELAY_AWARE_PROTOCOL_VERSION,
            senderPublicKey = decodeField(parts[2], "senderPublicKey"),
            relayMetadata = EncryptedMessageRelayMetadata(
                messageId = decodeStringField(parts[3], "messageId"),
                messageType = relayMessageType,
                ttl = relayTtl
            ),
            payload = EncryptedPayloadFrameCodec.decode(payloadToken)
        )
    }

    private fun encodeField(
        value: String
    ): String {
        return encoder.encodeToString(value.toByteArray(UTF_8))
    }

    private fun decodeStringField(
        token: String,
        fieldName: String
    ): String {
        return String(decodeField(token, fieldName), UTF_8)
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
