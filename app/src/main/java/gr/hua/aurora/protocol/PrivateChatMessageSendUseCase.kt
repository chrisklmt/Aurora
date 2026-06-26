package gr.hua.aurora.protocol

import gr.hua.aurora.ble.noop.NoOpBleTransportSender
import gr.hua.aurora.ble.transport.BleTransportSendResult
import gr.hua.aurora.ble.transport.BleTransportSender
import gr.hua.aurora.model.OutgoingChatMessage

sealed interface PrivateChatMessageSendResult {
    data object SubmittedLocally : PrivateChatMessageSendResult
    data object KeysUnavailable : PrivateChatMessageSendResult
    data object ContactUnavailable : PrivateChatMessageSendResult
    data object ContactNotReachable : PrivateChatMessageSendResult
    data class Failed(
        val reason: String
    ) : PrivateChatMessageSendResult {
        init {
            require(reason.isNotBlank()) {
                "Private chat send failure reason must not be blank."
            }
        }
    }
}

object PrivateChatMessageSendUseCase {
    suspend fun send(
        message: OutgoingChatMessage,
        senderPeerId: String?,
        senderUsername: String,
        transportSender: BleTransportSender?,
        sessionMaterialProvider: OutgoingSessionMaterialProvider,
        activeConnectedPeerId: String?,
        isActiveTransportConnected: Boolean
    ): PrivateChatMessageSendResult {
        val draft = runCatching {
            OutgoingMessageFrameBuilder.build(message)
        }.getOrElse {
            return PrivateChatMessageSendResult.ContactUnavailable
        }
        if (draft.type != MessageFrameType.PRIVATE_TEXT) {
            return PrivateChatMessageSendResult.ContactUnavailable
        }

        val targetPeerId = draft.recipientId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return PrivateChatMessageSendResult.ContactUnavailable
        val sanitizedSenderPeerId = senderPeerId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return PrivateChatMessageSendResult.KeysUnavailable
        val encryptionMaterial = sessionMaterialProvider.encryptionMaterialForTarget(targetPeerId)
            ?: return PrivateChatMessageSendResult.KeysUnavailable
        val connectedPeerId = activeConnectedPeerId?.trim()?.takeIf { it.isNotEmpty() }
        if (!isActiveTransportConnected || connectedPeerId != targetPeerId) {
            return PrivateChatMessageSendResult.ContactNotReachable
        }

        val sender = transportSender ?: return PrivateChatMessageSendResult.ContactNotReachable
        if (sender is NoOpBleTransportSender) {
            return PrivateChatMessageSendResult.ContactNotReachable
        }

        val encodedPrivatePayload = runCatching {
            PrivateChatMessagePayloadCodec.encode(
                PrivateChatMessagePayload(
                    senderUsername = senderUsername.trim(),
                    body = draft.payload
                )
            )
        }.getOrElse { error ->
            return PrivateChatMessageSendResult.Failed(
                reason = error.message ?: "Private chat payload is invalid."
            )
        }
        val resolvedFrame = OutgoingMessageFrameResolver.resolve(
            draft = draft.copy(payload = encodedPrivatePayload),
            senderId = sanitizedSenderPeerId
        )
        val sendResult = MessageFrameTransportSendUseCase.send(
            frame = resolvedFrame,
            encryptionMaterial = encryptionMaterial,
            transportSender = sender,
            targetPeerId = targetPeerId
        )
        return when (sendResult) {
            BleTransportSendResult.QueuedLocally ->
                PrivateChatMessageSendResult.SubmittedLocally
            BleTransportSendResult.NotAvailable ->
                PrivateChatMessageSendResult.ContactNotReachable
            is BleTransportSendResult.Failed ->
                PrivateChatMessageSendResult.Failed(
                    reason = sendResult.reason
                )
        }
    }
}
