package gr.hua.aurora.protocol

import gr.hua.aurora.ble.noop.NoOpBleTransportSender
import gr.hua.aurora.ble.transport.BleTransportSendResult
import gr.hua.aurora.ble.transport.BleTransportSender
import gr.hua.aurora.model.OutgoingChatMessage
import gr.hua.aurora.state.IncomingMessageIngestionResult

private const val defaultGlobalMeshTtl = 2
private const val maxRememberedGlobalMeshMessageIds = 256

data class GlobalMeshDiagnostics(
    val reachablePeerCount: Int,
    val reachablePeerIds: List<String>,
    val activeTransportPeerId: String?,
    val seenMessageCount: Int,
    val onlyOneActiveTransportPeerSupported: Boolean,
    val lastResult: GlobalMeshDeliveryResult?
)

sealed interface GlobalMeshDeliveryResult {
    data class QueuedToActivePeer(
        val peerId: String
    ) : GlobalMeshDeliveryResult

    data object NoReachablePeers : GlobalMeshDeliveryResult

    data object SenderUnavailable : GlobalMeshDeliveryResult

    data class ConnectOnSendFailed(
        val peerId: String,
        val reason: String
    ) : GlobalMeshDeliveryResult {
        init {
            require(peerId.isNotBlank()) {
                "Global mesh connect-on-send peer id must not be blank."
            }
            require(reason.isNotBlank()) {
                "Global mesh connect-on-send failure reason must not be blank."
            }
        }
    }

    data class SkippedDuplicate(
        val messageId: String
    ) : GlobalMeshDeliveryResult

    data class SkippedSourcePeer(
        val peerId: String
    ) : GlobalMeshDeliveryResult

    data class SkippedTtlExpired(
        val messageId: String
    ) : GlobalMeshDeliveryResult

    data class Failed(
        val reason: String
    ) : GlobalMeshDeliveryResult {
        init {
            require(reason.isNotBlank()) {
                "Global mesh delivery failure reason must not be blank."
            }
        }
    }
}

class GlobalMeshDeliveryCoordinator {
    private val seenMessageIds = LinkedHashSet<String>()
    private var lastResult: GlobalMeshDeliveryResult? = null

    fun diagnosticsSnapshot(
        reachablePeerIds: List<String>,
        activeTransportPeerId: String?
    ): GlobalMeshDiagnostics {
        val sanitizedReachablePeerIds = reachablePeerIds
            .mapNotNull { peerId -> peerId.trim().takeIf { it.isNotEmpty() } }
            .distinct()
            .sorted()
        val sanitizedActivePeerId = activeTransportPeerId?.trim()?.takeIf { it.isNotEmpty() }
        return GlobalMeshDiagnostics(
            reachablePeerCount = sanitizedReachablePeerIds.size,
            reachablePeerIds = sanitizedReachablePeerIds,
            activeTransportPeerId = sanitizedActivePeerId,
            seenMessageCount = seenMessageIds.size,
            onlyOneActiveTransportPeerSupported = true,
            lastResult = lastResult
        )
    }

    suspend fun submitLocalMessage(
        message: OutgoingChatMessage,
        senderId: String,
        transportSender: BleTransportSender?,
        activeTransportPeerId: String?
    ): GlobalMeshDeliveryResult {
        require(message.threadId == globalThreadId) {
            "Global mesh delivery only supports the global thread."
        }

        val targetPeerId = sanitizePeerId(activeTransportPeerId)
            ?: return record(GlobalMeshDeliveryResult.NoReachablePeers)
        val sender = resolveTransportSender(transportSender)
            ?: return record(GlobalMeshDeliveryResult.SenderUnavailable)
        val resolvedFrame = OutgoingMessageFrameResolver.resolve(
            draft = OutgoingMessageFrameBuilder.build(message),
            senderId = senderId
        ).copy(ttl = defaultGlobalMeshTtl)
        val sendResult = MessageFrameTransportSendUseCase.sendPublic(
            frame = resolvedFrame,
            transportSender = sender,
            targetPeerId = targetPeerId
        )

        return when (sendResult) {
            BleTransportSendResult.QueuedLocally -> {
                rememberSeenMessageId(resolvedFrame.id)
                record(GlobalMeshDeliveryResult.QueuedToActivePeer(peerId = targetPeerId))
            }
            BleTransportSendResult.NotAvailable ->
                record(GlobalMeshDeliveryResult.SenderUnavailable)

            is BleTransportSendResult.Failed ->
                record(GlobalMeshDeliveryResult.Failed(reason = sendResult.reason))
        }
    }

    suspend fun relayReceivedMessage(
        message: IncomingTransportMessage,
        ingestionResult: IncomingMessageIngestionResult,
        transportSender: BleTransportSender?,
        activeTransportPeerId: String?,
        immediateSourcePeerId: String? = null
    ): GlobalMeshDeliveryResult {
        val frame = message.frame
        require(frame.type == MessageFrameType.GLOBAL_TEXT) {
            "Global mesh relay only supports global text frames."
        }

        if (!rememberSeenMessageId(frame.id) || ingestionResult is IncomingMessageIngestionResult.Duplicate) {
            return record(GlobalMeshDeliveryResult.SkippedDuplicate(messageId = frame.id))
        }
        if (frame.ttl <= 1) {
            return record(GlobalMeshDeliveryResult.SkippedTtlExpired(messageId = frame.id))
        }

        val targetPeerId = sanitizePeerId(activeTransportPeerId)
            ?: return record(GlobalMeshDeliveryResult.NoReachablePeers)
        val sourcePeerId = sanitizePeerId(immediateSourcePeerId)
        if (sourcePeerId != null && sourcePeerId == targetPeerId) {
            return record(GlobalMeshDeliveryResult.SkippedSourcePeer(peerId = sourcePeerId))
        }

        val sender = resolveTransportSender(transportSender)
            ?: return record(GlobalMeshDeliveryResult.SenderUnavailable)
        val relayFrame = frame.copy(ttl = frame.ttl - 1)
        val sendResult = MessageFrameTransportSendUseCase.sendPublic(
            frame = relayFrame,
            transportSender = sender,
            targetPeerId = targetPeerId
        )

        return when (sendResult) {
            BleTransportSendResult.QueuedLocally ->
                record(GlobalMeshDeliveryResult.QueuedToActivePeer(peerId = targetPeerId))

            BleTransportSendResult.NotAvailable ->
                record(GlobalMeshDeliveryResult.SenderUnavailable)

            is BleTransportSendResult.Failed ->
                record(GlobalMeshDeliveryResult.Failed(reason = sendResult.reason))
        }
    }

    private fun resolveTransportSender(
        transportSender: BleTransportSender?
    ): BleTransportSender? {
        return when (transportSender) {
            null -> null
            is NoOpBleTransportSender -> null
            else -> transportSender
        }
    }

    private fun sanitizePeerId(
        peerId: String?
    ): String? {
        return peerId?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun rememberSeenMessageId(
        messageId: String
    ): Boolean {
        val wasAdded = seenMessageIds.add(messageId)
        if (wasAdded && seenMessageIds.size > maxRememberedGlobalMeshMessageIds) {
            val oldestMessageId = seenMessageIds.firstOrNull()
            if (oldestMessageId != null) {
                seenMessageIds.remove(oldestMessageId)
            }
        }
        return wasAdded
    }

    private fun record(
        result: GlobalMeshDeliveryResult
    ): GlobalMeshDeliveryResult {
        lastResult = result
        return result
    }

    private companion object {
        private const val globalThreadId = "global"
    }
}
