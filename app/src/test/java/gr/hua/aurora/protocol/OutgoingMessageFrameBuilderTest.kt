package gr.hua.aurora.protocol

import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.model.OutgoingChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutgoingMessageFrameBuilderTest {
    @Test
    fun queuedGlobalMessageMapsToProtocolDraft() {
        val queuedMessage = OutgoingChatMessage(
            messageId = "global-123",
            threadId = "global",
            userText = "hello global",
            createdAtMillis = 1_715_000_001L,
            status = MessageStatus.QUEUED
        )

        val draft = OutgoingMessageFrameBuilder.build(queuedMessage)

        assertEquals("global-123", draft.id)
        assertEquals("global", draft.threadId)
        assertEquals(MessageFrameType.GLOBAL_TEXT, draft.type)
        assertNull(draft.recipientId)
        assertEquals("hello global", draft.payload)
        assertEquals(1_715_000_001L, draft.createdAtMillis)
    }

    @Test
    fun queuedPrivateMessageMapsToProtocolDraftWithRecipient() {
        val queuedMessage = OutgoingChatMessage(
            messageId = "private-456",
            threadId = "private:alex",
            userText = "hello alex",
            createdAtMillis = 1_715_000_321L,
            status = MessageStatus.QUEUED
        )

        val draft = OutgoingMessageFrameBuilder.build(queuedMessage)

        assertEquals("private-456", draft.id)
        assertEquals("private:alex", draft.threadId)
        assertEquals(MessageFrameType.PRIVATE_TEXT, draft.type)
        assertEquals("alex", draft.recipientId)
        assertEquals("hello alex", draft.payload)
    }

    @Test
    fun buildLeavesQueuedUserMessageStatusUntouched() {
        val queuedMessage = OutgoingChatMessage(
            messageId = "global-789",
            threadId = "global",
            userText = "still queued",
            createdAtMillis = 1_715_000_999L,
            status = MessageStatus.QUEUED
        )

        OutgoingMessageFrameBuilder.build(queuedMessage)

        assertEquals(MessageStatus.QUEUED, queuedMessage.status)
    }
}
