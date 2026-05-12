package gr.hua.aurora.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MessageFrameCodecTest {
    @Test
    fun globalTextFrameRoundTripPreservesData() {
        val frame = MessageFrame(
            id = "global-1",
            type = MessageFrameType.GLOBAL_TEXT,
            senderId = "self",
            createdAtMillis = 1_715_000_001L,
            payload = "Hello global chat"
        )

        val encoded = MessageFrameCodec.encode(frame)
        val decoded = MessageFrameCodec.decode(encoded)

        assertEquals(frame, decoded)
    }

    @Test
    fun privateTextFrameRoundTripPreservesData() {
        val frame = MessageFrame(
            id = "private-42",
            type = MessageFrameType.PRIVATE_TEXT,
            senderId = "self",
            recipientId = "alex",
            createdAtMillis = 1_715_000_321L,
            ttl = 3,
            payload = "Private hello"
        )

        val encoded = MessageFrameCodec.encode(frame)
        val decoded = MessageFrameCodec.decode(encoded)

        assertEquals(frame, decoded)
    }

    @Test
    fun invalidFrameInputFails() {
        assertThrows(IllegalArgumentException::class.java) {
            MessageFrameCodec.decode("bad|frame")
        }
    }

    @Test
    fun payloadWithSeparatorsAndSpecialCharactersRoundTripsSafely() {
        val frame = MessageFrame(
            id = "special-1",
            type = MessageFrameType.CONTROL,
            senderId = "system",
            recipientId = "peer|42",
            createdAtMillis = 1_715_000_999L,
            ttl = 2,
            payload = "line1|line2\nsymbols: = ? & / \\ and Greek: καλημέρα"
        )

        val encoded = MessageFrameCodec.encode(frame)
        val decoded = MessageFrameCodec.decode(encoded)

        assertEquals(frame, decoded)
    }
}
