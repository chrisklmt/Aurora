package gr.hua.aurora.protocol

import gr.hua.aurora.model.OutgoingChatMessage
import java.nio.charset.StandardCharsets.UTF_8

data class PreparedPrivateChatTransportFrame(
    val frame: MessageFrame,
    val encryptedEnvelope: EncryptedMessageEnvelope,
    val targetPeerId: String
)

object PrivateChatTransportFrameFactory {
    fun build(
        message: OutgoingChatMessage,
        privateChatId: String,
        senderPeerId: String?,
        senderUsername: String,
        encryptionMaterial: OutgoingMessageSendEncryptionMaterial?
    ): PreparedPrivateChatTransportFrame {
        val draft = OutgoingMessageFrameBuilder.build(message)
        require(draft.type == MessageFrameType.PRIVATE_TEXT) {
            "Private chat transport requires PRIVATE_TEXT messages."
        }

        val targetPeerId = draft.recipientId?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Private chat recipient is required.")
        val sanitizedSenderPeerId = senderPeerId?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Private chat sender peer id is required.")
        val resolvedEncryptionMaterial = encryptionMaterial
            ?: throw IllegalArgumentException("Private chat encryption material is required.")
        val encodedPrivatePayload = PrivateChatMessagePayloadCodec.encode(
            PrivateChatMessagePayload(
                privateChatId = privateChatId.trim(),
                senderUsername = senderUsername.trim(),
                body = draft.payload
            )
        )
        val resolvedFrame = OutgoingMessageFrameResolver.resolve(
            draft = draft.copy(payload = encodedPrivatePayload),
            senderId = sanitizedSenderPeerId
        )
        val encodedFrameBytes = MessageFrameCodec.encode(resolvedFrame).toByteArray(UTF_8)
        val encryptedEnvelope = runCatching {
            EncryptedMessageEnvelopeBuilder.build(
                senderPublicKey = resolvedEncryptionMaterial.senderPublicKey,
                keyBytes = resolvedEncryptionMaterial.keyBytes,
                plaintext = encodedFrameBytes,
                authenticatedData = resolvedEncryptionMaterial.authenticatedData,
                relayMetadata = EncryptedMessageRelayMetadata(
                    messageId = resolvedFrame.id,
                    messageType = resolvedFrame.type,
                    ttl = resolvedFrame.ttl
                )
            )
        }.getOrElse { error ->
            throw IllegalArgumentException(
                error.message ?: "Private chat encryption material is invalid.",
                error
            )
        }

        return PreparedPrivateChatTransportFrame(
            frame = resolvedFrame,
            encryptedEnvelope = encryptedEnvelope,
            targetPeerId = targetPeerId
        )
    }
}
