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

        val item = message.toMessageListItem(showRetryAction = true)

        assertEquals("Pending", item.supportingLabel)
        assertNull(item.actionLabel)
    }

    @Test
    fun outgoingSentMessageUsesSentLabel() {
        val message = createMessage(
            status = MessageStatus.SENT,
            isOutgoing = true
        )

        val item = message.toMessageListItem(showRetryAction = true)

        assertEquals("Sent", item.supportingLabel)
        assertNull(item.actionLabel)
    }

    @Test
    fun incomingReceivedMessageHidesStatusLabel() {
        val message = createMessage(
            status = MessageStatus.RECEIVED,
            isOutgoing = false
        )

        val item = message.toMessageListItem(showRetryAction = true)

        assertNull(item.supportingLabel)
        assertNull(item.actionLabel)
    }

    @Test
    fun outgoingFailedMessageShowsRetryAction() {
        val message = createMessage(
            status = MessageStatus.FAILED,
            isOutgoing = true
        )

        val item = message.toMessageListItem(showRetryAction = true)

        assertEquals("Failed", item.supportingLabel)
        assertEquals("Retry", item.actionLabel)
        assertEquals("msg-1", item.actionMessageId)
    }

    @Test
    fun outgoingDeliveredMessageFallsBackToSentLabel() {
        val message = createMessage(
            status = MessageStatus.DELIVERED,
            isOutgoing = true
        )

        val item = message.toMessageListItem(showRetryAction = true)

        assertEquals("Sent", item.supportingLabel)
        assertNull(item.actionLabel)
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
