package gr.hua.aurora.ui.components

import gr.hua.aurora.model.ChatMessage
import gr.hua.aurora.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatMessageUiMapperTest {
    @Test
    fun outgoingQueuedMessageUsesPendingLabel() {
        val message = createMessage(
            status = MessageStatus.QUEUED,
            isOutgoing = true
        )

        val item = message.toMessageListItem()

        assertEquals("Pending", item.supportingLabel)
    }

    @Test
    fun outgoingSentMessageUsesSentLabel() {
        val message = createMessage(
            status = MessageStatus.SENT,
            isOutgoing = true
        )

        val item = message.toMessageListItem()

        assertEquals("Sent", item.supportingLabel)
    }

    @Test
    fun incomingReceivedMessageHidesStatusLabel() {
        val message = createMessage(
            status = MessageStatus.RECEIVED,
            isOutgoing = false
        )

        val item = message.toMessageListItem()

        assertNull(item.supportingLabel)
    }

    private fun createMessage(
        status: MessageStatus,
        isOutgoing: Boolean
    ): ChatMessage {
        return ChatMessage(
            id = "msg-1",
            threadId = "global",
            senderId = if (isOutgoing) "self" else "peer-1",
            senderName = if (isOutgoing) "Self" else "Peer",
            text = "hello",
            createdAtMillis = 1_000L,
            status = status,
            isOutgoing = isOutgoing
        )
    }
}
