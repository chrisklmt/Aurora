package gr.hua.aurora.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class OutgoingMessageFrameResolverTest {
    @Test
    fun globalDraftResolvesToMessageFrameWithSenderId() {
        val draft = OutgoingMessageFrameDraft(
            id = "global-123",
            threadId = "global",
            type = MessageFrameType.GLOBAL_TEXT,
            createdAtMillis = 1_715_000_001L,
            payload = "hello global"
        )

        val frame = OutgoingMessageFrameResolver.resolve(
            draft = draft,
            senderId = "sender-1"
        )

        assertEquals("global-123", frame.id)
        assertEquals(MessageFrameType.GLOBAL_TEXT, frame.type)
        assertEquals("sender-1", frame.senderId)
        assertNull(frame.recipientId)
        assertEquals(1_715_000_001L, frame.createdAtMillis)
        assertEquals("hello global", frame.payload)
    }

    @Test
    fun privateDraftResolvesWithRecipientId() {
        val draft = OutgoingMessageFrameDraft(
            id = "private-456",
            threadId = "private:alex",
            type = MessageFrameType.PRIVATE_TEXT,
            recipientId = "alex",
            createdAtMillis = 1_715_000_321L,
            payload = "hello alex"
        )

        val frame = OutgoingMessageFrameResolver.resolve(
            draft = draft,
            senderId = "sender-1"
        )

        assertEquals("private-456", frame.id)
        assertEquals(MessageFrameType.PRIVATE_TEXT, frame.type)
        assertEquals("sender-1", frame.senderId)
        assertEquals("alex", frame.recipientId)
        assertEquals(1_715_000_321L, frame.createdAtMillis)
        assertEquals("hello alex", frame.payload)
    }

    @Test
    fun resolvedGlobalFrameEncodesAndDecodesLosslessly() {
        val draft = OutgoingMessageFrameDraft(
            id = "global-encoding-1",
            threadId = "global",
            type = MessageFrameType.GLOBAL_TEXT,
            createdAtMillis = 1_715_100_001L,
            payload = "hello global codec"
        )

        val resolvedFrame = OutgoingMessageFrameResolver.resolve(
            draft = draft,
            senderId = "sender-global"
        )
        val encodedFrame = MessageFrameCodec.encode(resolvedFrame)
        val decodedFrame = MessageFrameCodec.decode(encodedFrame)

        assertEquals(resolvedFrame, decodedFrame)
        assertEquals("global-encoding-1", decodedFrame.id)
        assertEquals(MessageFrameType.GLOBAL_TEXT, decodedFrame.type)
        assertEquals("sender-global", decodedFrame.senderId)
        assertNull(decodedFrame.recipientId)
        assertEquals(1_715_100_001L, decodedFrame.createdAtMillis)
        assertEquals("hello global codec", decodedFrame.payload)
    }

    @Test
    fun resolvedPrivateFrameEncodesAndDecodesLosslessly() {
        val draft = OutgoingMessageFrameDraft(
            id = "private-encoding-2",
            threadId = "private:alex",
            type = MessageFrameType.PRIVATE_TEXT,
            recipientId = "alex",
            createdAtMillis = 1_715_100_321L,
            payload = "hello private codec"
        )

        val resolvedFrame = OutgoingMessageFrameResolver.resolve(
            draft = draft,
            senderId = "sender-private"
        )
        val encodedFrame = MessageFrameCodec.encode(resolvedFrame)
        val decodedFrame = MessageFrameCodec.decode(encodedFrame)

        assertEquals(resolvedFrame, decodedFrame)
        assertEquals("private-encoding-2", decodedFrame.id)
        assertEquals(MessageFrameType.PRIVATE_TEXT, decodedFrame.type)
        assertEquals("sender-private", decodedFrame.senderId)
        assertEquals("alex", decodedFrame.recipientId)
        assertEquals(1_715_100_321L, decodedFrame.createdAtMillis)
        assertEquals("hello private codec", decodedFrame.payload)
    }

    @Test
    fun blankSenderIdIsRejectedInsteadOfBeingFaked() {
        val draft = OutgoingMessageFrameDraft(
            id = "global-789",
            threadId = "global",
            type = MessageFrameType.GLOBAL_TEXT,
            createdAtMillis = 1_715_000_999L,
            payload = "still local"
        )

        assertThrows(IllegalArgumentException::class.java) {
            OutgoingMessageFrameResolver.resolve(
                draft = draft,
                senderId = "   "
            )
        }
    }
}
