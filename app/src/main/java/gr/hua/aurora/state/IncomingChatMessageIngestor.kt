package gr.hua.aurora.state

import gr.hua.aurora.model.ChatMessage
import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.protocol.IncomingTransportMessage
import gr.hua.aurora.protocol.MessageFrame
import gr.hua.aurora.protocol.MessageFrameType

data class IncomingMessageIngestionOutcome(
    val updatedState: AuroraUiState,
    val result: IncomingMessageIngestionResult
)

object IncomingChatMessageIngestor {
    fun ingest(
        state: AuroraUiState,
        message: IncomingTransportMessage
    ): IncomingMessageIngestionOutcome {
        return ingest(
            state = state,
            frame = message.frame
        )
    }

    fun ingest(
        state: AuroraUiState,
        frame: MessageFrame
    ): IncomingMessageIngestionOutcome {
        if (state.containsVisibleMessageId(frame.id)) {
            return IncomingMessageIngestionOutcome(
                updatedState = state,
                result = IncomingMessageIngestionResult.Duplicate(
                    messageId = frame.id
                )
            )
        }

        return when (frame.type) {
            MessageFrameType.GLOBAL_TEXT -> appendGlobalMessage(
                state = state,
                frame = frame
            )
            MessageFrameType.PRIVATE_TEXT -> appendPrivateMessage(
                state = state,
                frame = frame
            )
            MessageFrameType.IDENTITY_EXCHANGE -> IncomingMessageIngestionOutcome(
                updatedState = state,
                result = IncomingMessageIngestionResult.UnsupportedType(
                    reason = "Incoming identity exchange frames need a dedicated peer identity handler before chat ingestion."
                )
            )
            MessageFrameType.CONTROL -> IncomingMessageIngestionOutcome(
                updatedState = state,
                result = IncomingMessageIngestionResult.UnsupportedType(
                    reason = "Incoming control frames are not supported for chat ingestion."
                )
            )
        }
    }

    private fun appendGlobalMessage(
        state: AuroraUiState,
        frame: MessageFrame
    ): IncomingMessageIngestionOutcome {
        val chatMessage = ChatMessage(
            id = frame.id,
            threadId = "global",
            senderId = frame.senderId,
            senderName = frame.senderId,
            text = frame.payload,
            createdAtMillis = frame.createdAtMillis,
            status = MessageStatus.RECEIVED,
            isOutgoing = false
        )

        return IncomingMessageIngestionOutcome(
            updatedState = state.copy(
                globalMessages = state.globalMessages + chatMessage
            ),
            result = IncomingMessageIngestionResult.Appended(
                message = chatMessage
            )
        )
    }

    private fun appendPrivateMessage(
        state: AuroraUiState,
        frame: MessageFrame
    ): IncomingMessageIngestionOutcome {
        val peerId = frame.senderId.trim()
        if (peerId.isEmpty()) {
            return IncomingMessageIngestionOutcome(
                updatedState = state,
                result = IncomingMessageIngestionResult.UnsupportedThread(
                    reason = "Incoming private text frame senderId must not be blank."
                )
            )
        }

        val chatMessage = ChatMessage(
            id = frame.id,
            threadId = "private:$peerId",
            senderId = peerId,
            senderName = state.contacts.firstOrNull { it.canonicalPeerId == peerId }?.displayName ?: peerId,
            text = frame.payload,
            createdAtMillis = frame.createdAtMillis,
            status = MessageStatus.RECEIVED,
            isOutgoing = false
        )
        val updatedMessages = state.privateMessagesByPeerId[peerId].orEmpty() + chatMessage

        return IncomingMessageIngestionOutcome(
            updatedState = state.copy(
                privateMessagesByPeerId = state.privateMessagesByPeerId + (peerId to updatedMessages)
            ),
            result = IncomingMessageIngestionResult.Appended(
                message = chatMessage
            )
        )
    }

    private fun AuroraUiState.containsVisibleMessageId(
        messageId: String
    ): Boolean {
        return globalMessages.any { it.id == messageId } ||
            privateMessagesByPeerId.values
                .asSequence()
                .flatten()
                .any { it.id == messageId }
    }
}
