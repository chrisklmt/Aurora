package gr.hua.aurora.protocol

import gr.hua.aurora.ble.noop.NoOpBleTransportSender
import gr.hua.aurora.ble.transport.BleTransportSendResult
import gr.hua.aurora.ble.transport.BleTransportSender
import gr.hua.aurora.model.OutgoingChatMessage

object GlobalChatTransportSubmission {
    private const val globalThreadId = "global"

    suspend fun submitIfReady(
        message: OutgoingChatMessage,
        senderId: String,
        transportSender: BleTransportSender?,
        sessionMaterialProvider: OutgoingSessionMaterialProvider,
        targetPeerId: String? = null,
        activeConnectedPeerId: String? = null
    ): GlobalChatTransportSubmissionResult {
        require(message.threadId == globalThreadId) {
            "Global chat transport submission only supports the global thread."
        }

        val selectedTargetPeerId = targetPeerId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return GlobalChatTransportSubmissionResult.NoSecurePeerSelected
        val connectedPeerId = activeConnectedPeerId?.trim()?.takeIf { it.isNotEmpty() }
        if (connectedPeerId != null && connectedPeerId != selectedTargetPeerId) {
            return GlobalChatTransportSubmissionResult.Failed(
                reason = "Selected secure peer $selectedTargetPeerId does not match active connected peer $connectedPeerId."
            )
        }
        val sender = transportSender ?: return GlobalChatTransportSubmissionResult.SenderUnavailable
        if (sender is NoOpBleTransportSender) {
            return GlobalChatTransportSubmissionResult.SenderUnavailable
        }

        val material = sessionMaterialProvider.encryptionMaterialForTarget(selectedTargetPeerId)
            ?: return GlobalChatTransportSubmissionResult.SessionMaterialUnavailable
        val result = OutgoingMessageSendUseCase.send(
            message = message,
            senderId = senderId,
            encryptionMaterial = material,
            transportSender = sender,
            targetPeerId = selectedTargetPeerId
        )
        return when (result) {
            BleTransportSendResult.QueuedLocally -> GlobalChatTransportSubmissionResult.SubmittedLocally
            BleTransportSendResult.NotAvailable -> GlobalChatTransportSubmissionResult.SenderUnavailable
            is BleTransportSendResult.Failed -> GlobalChatTransportSubmissionResult.Failed(
                reason = result.reason
            )
        }
    }
}
