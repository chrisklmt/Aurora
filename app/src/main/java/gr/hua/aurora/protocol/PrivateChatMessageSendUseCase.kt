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
        privateChatId: String,
        senderPeerId: String?,
        senderUsername: String,
        transportSender: BleTransportSender?,
        sessionMaterialProvider: OutgoingSessionMaterialProvider,
        activeConnectedPeerId: String?,
        isActiveTransportConnected: Boolean,
        submitDebugCopy: ((PreparedPrivateChatTransportFrame) -> Unit)? = null
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

        val preparedTransportFrame = runCatching {
            PrivateChatTransportFrameFactory.build(
                message = message,
                privateChatId = privateChatId,
                senderPeerId = sanitizedSenderPeerId,
                senderUsername = senderUsername,
                encryptionMaterial = encryptionMaterial
            )
        }.getOrElse { error ->
            return PrivateChatMessageSendResult.Failed(
                reason = error.message ?: "Private chat payload is invalid."
            )
        }

        val connectedPeerId = activeConnectedPeerId?.trim()?.takeIf { it.isNotEmpty() }
        val sender = transportSender
        val bleResult = if (
            !isActiveTransportConnected ||
            connectedPeerId != targetPeerId ||
            sender == null ||
            sender is NoOpBleTransportSender
        ) {
            PrivateChatMessageSendResult.ContactNotReachable
        } else {
            when (
                val sendResult = MessageFrameTransportSendUseCase.sendEncryptedEnvelope(
                    envelope = preparedTransportFrame.encryptedEnvelope,
                    transportSender = sender,
                    targetPeerId = preparedTransportFrame.targetPeerId,
                    sourceCreatedAtMillis = preparedTransportFrame.frame.createdAtMillis
                )
            ) {
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
        runCatching {
            submitDebugCopy?.invoke(preparedTransportFrame)
        }
        return bleResult
    }
}
