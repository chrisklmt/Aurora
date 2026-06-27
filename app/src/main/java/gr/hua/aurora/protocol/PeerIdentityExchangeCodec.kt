package gr.hua.aurora.protocol

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

object PeerIdentityExchangeCodec {
    private const val currentFormatVersion = "AURORA_PEER_IDENTITY_V2"
    private const val legacyFormatVersion = "AURORA_PEER_IDENTITY_V1"
    private const val separator = "|"
    private const val currentExpectedPartCount = 5
    private const val legacyExpectedPartCount = 4
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(
        message: PeerIdentityExchangeMessage
    ): String {
        return listOf(
            currentFormatVersion,
            encodeField(message.peerId.toByteArray(UTF_8)),
            message.createdAtMillis.toString(),
            encodeField(message.publicAgreementKeyBytes()),
            message.privateChatProposalId?.let { encodeField(it.toByteArray(UTF_8)) }.orEmpty()
        ).joinToString(separator)
    }

    fun decode(
        encoded: String
    ): PeerIdentityExchangeMessage {
        val parts = encoded.split(separator)
        val version = parts.firstOrNull()
            ?: throw IllegalArgumentException("Peer identity exchange payload is empty.")
        when (version) {
            currentFormatVersion -> require(parts.size == currentExpectedPartCount) {
                "Invalid peer identity exchange part count: ${parts.size}."
            }
            legacyFormatVersion -> require(parts.size == legacyExpectedPartCount) {
                "Invalid peer identity exchange part count: ${parts.size}."
            }
            else -> throw IllegalArgumentException(
                "Unsupported peer identity exchange version: $version."
            )
        }

        val createdAtMillis = parts[2].toLongOrNull()
            ?: throw IllegalArgumentException(
                "Invalid peer identity exchange timestamp: ${parts[2]}."
            )

        return PeerIdentityExchangeMessage(
            peerId = String(decodeField(parts[1], "peerId"), UTF_8),
            publicAgreementKeyBytes = decodeField(parts[3], "publicAgreementKeyBytes"),
            createdAtMillis = createdAtMillis,
            privateChatProposalId = parts.getOrNull(4)
                ?.takeIf { it.isNotEmpty() }
                ?.let { String(decodeField(it, "privateChatProposalId"), UTF_8) }
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
