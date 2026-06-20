package gr.hua.aurora.protocol

object OutgoingMessageFrameResolver {
    fun resolve(
        draft: OutgoingMessageFrameDraft,
        senderId: String
    ): MessageFrame {
        require(senderId.isNotBlank()) { "Resolved senderId must not be blank." }

        return MessageFrame(
            id = draft.id,
            type = draft.type,
            senderId = senderId,
            recipientId = draft.recipientId,
            createdAtMillis = draft.createdAtMillis,
            payload = draft.payload
        )
    }
}
