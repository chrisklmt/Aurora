package gr.hua.aurora.state

import gr.hua.aurora.model.OutgoingChatMessage
import gr.hua.aurora.protocol.GlobalMeshDeliveryResult
import gr.hua.aurora.protocol.PreparedPrivateChatTransportFrame
import gr.hua.aurora.protocol.PrivateChatMessageSendResult

data class GlobalQueuedChatSubmissionResult(
    val queuedMessage: OutgoingChatMessage,
    val transportResult: GlobalMeshDeliveryResult
)

data class PrivateQueuedChatSubmissionResult(
    val queuedMessage: OutgoingChatMessage,
    val transportResult: PrivateChatMessageSendResult
)

suspend fun sendGlobalChatMessageThroughProductionPath(
    text: String,
    stateHolder: AuroraStateHolder,
    currentUsername: () -> String,
    submitTransport: suspend (OutgoingChatMessage, String) -> GlobalMeshDeliveryResult,
    submitWifiDirectDebugTransport: ((OutgoingChatMessage, String) -> Unit)? = null
): GlobalQueuedChatSubmissionResult? {
    val queuedMessage = stateHolder.sendGlobalPreviewMessage(text) ?: return null
    val transportResult = submitGlobalQueuedMessage(
        queuedMessage = queuedMessage,
        currentUsername = currentUsername,
        submitTransport = submitTransport,
        submitWifiDirectDebugTransport = submitWifiDirectDebugTransport
    )
    stateHolder.handleGlobalMeshDeliveryResult(
        messageId = queuedMessage.messageId,
        result = transportResult
    )
    return GlobalQueuedChatSubmissionResult(
        queuedMessage = queuedMessage,
        transportResult = transportResult
    )
}

suspend fun retryGlobalChatMessageThroughProductionPath(
    messageId: String,
    stateHolder: AuroraStateHolder,
    currentUsername: () -> String,
    submitTransport: suspend (OutgoingChatMessage, String) -> GlobalMeshDeliveryResult,
    submitWifiDirectDebugTransport: ((OutgoingChatMessage, String) -> Unit)? = null
): GlobalQueuedChatSubmissionResult? {
    val queuedMessage = stateHolder.retryGlobalOutgoingMessage(messageId) ?: return null
    val transportResult = submitGlobalQueuedMessage(
        queuedMessage = queuedMessage,
        currentUsername = currentUsername,
        submitTransport = submitTransport,
        submitWifiDirectDebugTransport = submitWifiDirectDebugTransport
    )
    stateHolder.handleGlobalMeshDeliveryResult(
        messageId = queuedMessage.messageId,
        result = transportResult
    )
    return GlobalQueuedChatSubmissionResult(
        queuedMessage = queuedMessage,
        transportResult = transportResult
    )
}

suspend fun sendPrivateChatMessageThroughProductionPath(
    peerId: String,
    text: String,
    stateHolder: AuroraStateHolder,
    currentUsername: () -> String,
    resolvePrivateChatId: (String) -> String?,
    submitTransport: suspend (OutgoingChatMessage, String, String) -> PrivateChatTransportSubmission,
    submitWifiDirectDebugTransport: ((PreparedPrivateChatTransportFrame) -> Unit)? = null
): PrivateQueuedChatSubmissionResult? {
    val queuedMessage = stateHolder.sendPrivateChatMessage(peerId, text) ?: return null
    val transportResult = submitPrivateQueuedMessage(
        queuedMessage = queuedMessage,
        peerId = peerId,
        currentUsername = currentUsername,
        resolvePrivateChatId = resolvePrivateChatId,
        submitTransport = submitTransport,
        submitWifiDirectDebugTransport = submitWifiDirectDebugTransport
    )
    stateHolder.handlePrivateChatDeliveryResult(
        peerId = peerId,
        messageId = queuedMessage.messageId,
        result = transportResult
    )
    return PrivateQueuedChatSubmissionResult(
        queuedMessage = queuedMessage,
        transportResult = transportResult
    )
}

suspend fun retryPrivateChatMessageThroughProductionPath(
    peerId: String,
    messageId: String,
    stateHolder: AuroraStateHolder,
    currentUsername: () -> String,
    resolvePrivateChatId: (String) -> String?,
    submitTransport: suspend (OutgoingChatMessage, String, String) -> PrivateChatTransportSubmission,
    submitWifiDirectDebugTransport: ((PreparedPrivateChatTransportFrame) -> Unit)? = null
): PrivateQueuedChatSubmissionResult? {
    val queuedMessage = stateHolder.retryPrivateChatOutgoingMessage(peerId, messageId) ?: return null
    val transportResult = submitPrivateQueuedMessage(
        queuedMessage = queuedMessage,
        peerId = peerId,
        currentUsername = currentUsername,
        resolvePrivateChatId = resolvePrivateChatId,
        submitTransport = submitTransport,
        submitWifiDirectDebugTransport = submitWifiDirectDebugTransport
    )
    stateHolder.handlePrivateChatDeliveryResult(
        peerId = peerId,
        messageId = queuedMessage.messageId,
        result = transportResult
    )
    return PrivateQueuedChatSubmissionResult(
        queuedMessage = queuedMessage,
        transportResult = transportResult
    )
}

suspend fun submitGlobalQueuedMessage(
    queuedMessage: OutgoingChatMessage,
    currentUsername: () -> String,
    submitTransport: suspend (OutgoingChatMessage, String) -> GlobalMeshDeliveryResult,
    submitWifiDirectDebugTransport: ((OutgoingChatMessage, String) -> Unit)? = null
): GlobalMeshDeliveryResult {
    val senderId = currentUsername().trim()
    val transportResult = runCatching {
        submitTransport(
            queuedMessage,
            senderId
        )
    }.getOrElse { error ->
        GlobalMeshDeliveryResult.Failed(
            reason = error.message ?: "Public mesh transport submission failed."
        )
    }
    runCatching {
        submitWifiDirectDebugTransport?.invoke(
            queuedMessage,
            senderId
        )
    }
    return transportResult
}

suspend fun submitPrivateQueuedMessage(
    queuedMessage: OutgoingChatMessage,
    peerId: String,
    currentUsername: () -> String,
    resolvePrivateChatId: (String) -> String?,
    submitTransport: suspend (OutgoingChatMessage, String, String) -> PrivateChatTransportSubmission,
    submitWifiDirectDebugTransport: ((PreparedPrivateChatTransportFrame) -> Unit)? = null
): PrivateChatMessageSendResult {
    val privateChatId = resolvePrivateChatId(peerId)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return PrivateChatMessageSendResult.KeysUnavailable

    val submission = runCatching {
        submitTransport(
            queuedMessage,
            currentUsername().trim(),
            privateChatId
        )
    }.getOrElse { error ->
        return PrivateChatMessageSendResult.Failed(
            reason = error.message ?: "Private chat transport submission failed."
        )
    }
    runCatching {
        submission.preparedTransportFrame?.let { preparedTransportFrame ->
            submitWifiDirectDebugTransport?.invoke(preparedTransportFrame)
        }
    }
    return submission.result
}
