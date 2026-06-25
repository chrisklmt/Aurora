package gr.hua.aurora.protocol

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

object PeerIdentityExchangeCodec {
    private const val formatVersion = "AURORA_PEER_IDENTITY_V1"
    private const val separator = "|"
    private const val expectedPartCount = 4
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(
        message: PeerIdentityExchangeMessage
    ): String {
        return listOf(
            formatVersion,
            encodeField(message.peerId.toByteArray(UTF_8)),
            message.createdAtMillis.toString(),
            encodeField(message.publicAgreementKeyBytes())
        ).joinToString(separator)
    }

    fun decode(
        encoded: String
    ): PeerIdentityExchangeMessage {
        val parts = encoded.split(separator, limit = expectedPartCount)
        require(parts.size == expectedPartCount) {
            "Invalid peer identity exchange part count: ${parts.size}."
        }
        require(parts[0] == formatVersion) {
            "Unsupported peer identity exchange version: ${parts[0]}."
        }

        val createdAtMillis = parts[2].toLongOrNull()
            ?: throw IllegalArgumentException(
                "Invalid peer identity exchange timestamp: ${parts[2]}."
            )

        return PeerIdentityExchangeMessage(
            peerId = String(decodeField(parts[1], "peerId"), UTF_8),
            publicAgreementKeyBytes = decodeField(parts[3], "publicAgreementKeyBytes"),
            createdAtMillis = createdAtMillis
        )
    }

    private fun encodeField(
        bytes: ByteArray
    ): String {
        return encoder.encodeToString(bytes.copyOf())
    }

    private fun decodeField(
        token: String,
        fieldName: String
    ): ByteArray {
        return try {
            decoder.decode(token)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException(
                "Invalid peer identity exchange field encoding for $fieldName.",
                error
            )
        }
    }
}
