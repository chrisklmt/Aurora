package gr.hua.aurora.transport.hybrid

import gr.hua.aurora.protocol.MessageFrame
import gr.hua.aurora.protocol.MessageFrameCodec
import gr.hua.aurora.protocol.MessageFrameType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridTransportControlFrameFactoryTest {
    @Test
    fun wrapsWifiDirectOfferIntoAuroraFrameAndParsesItBack() {
        val message = HybridTransportControlMessage(
            messageType = HybridTransportControlMessage.MessageType.WIFI_DIRECT_OFFER,
            sessionId = "hybrid-offer-session-001",
            publicPeerIdHint = "peer-offer-001",
            createdAtMillis = 1_721_000_001L,
            capabilityFlags = setOf(
                HybridTransportControlMessage.CapabilityFlag.WIFI_DIRECT_BOOTSTRAP,
                HybridTransportControlMessage.CapabilityFlag.BLE_FALLBACK
            )
        )

        val frame = HybridTransportControlFrameFactory.create(
            message = message,
            frameId = "hybrid-offer-frame-001",
            senderId = "sender-offer",
            ttl = 2
        )
        val parsed = HybridTransportControlFrameFactory.parse(frame)

        assertEquals(MessageFrameType.HYBRID_TRANSPORT_CONTROL, frame.type)
        assertEquals("hybrid-offer-frame-001", frame.id)
        assertEquals("sender-offer", frame.senderId)
        assertEquals(2, frame.ttl)
        assertEquals(message, parsed)
    }

    @Test
    fun wrapsWifiDirectAcceptIntoAuroraFrameAndParsesItBack() {
        val message = HybridTransportControlMessage(
            messageType = HybridTransportControlMessage.MessageType.WIFI_DIRECT_ACCEPT,
            sessionId = "hybrid-accept-session-002",
            publicPeerIdHint = "peer-accept-002",
            createdAtMillis = 1_721_000_002L,
            capabilityFlags = setOf(
                HybridTransportControlMessage.CapabilityFlag.WIFI_DIRECT_BOOTSTRAP
            )
        )

        val frame = HybridTransportControlFrameFactory.create(
            message = message,
            frameId = "hybrid-accept-frame-002",
            senderId = "sender-accept",
            recipientId = "recipient-accept"
        )
        val parsed = HybridTransportControlFrameFactory.parse(frame)

        assertEquals("recipient-accept", frame.recipientId)
        assertEquals(message, parsed)
    }

    @Test
    fun wrapsWifiDirectSocketHintIntoAuroraFrameAndParsesItBack() {
        val message = HybridTransportControlMessage(
            messageType = HybridTransportControlMessage.MessageType.WIFI_DIRECT_SOCKET_HINT,
            sessionId = "hybrid-socket-session-003",
            publicPeerIdHint = "peer-socket-003",
            groupOwnerAddress = "192.168.49.1",
            socketPort = 8988,
            createdAtMillis = 1_721_000_003L,
            capabilityFlags = setOf(
                HybridTransportControlMessage.CapabilityFlag.WIFI_DIRECT_SOCKET_HINT
            )
        )

        val frame = HybridTransportControlFrameFactory.create(
            message = message,
            frameId = "hybrid-socket-frame-003",
            senderId = "sender-socket"
        )
        val parsed = HybridTransportControlFrameFactory.parse(frame)

        assertEquals("192.168.49.1", parsed.groupOwnerAddress)
        assertEquals(8988, parsed.socketPort)
        assertEquals(message, parsed)
    }

    @Test
    fun parserIgnoresNonHybridFramesSafely() {
        val globalFrame = MessageFrame(
            id = "global-frame-1",
            type = MessageFrameType.GLOBAL_TEXT,
            senderId = "self",
            createdAtMillis = 1_721_000_100L,
            payload = "Hello global chat"
        )

        assertNull(HybridTransportControlFrameFactory.parseOrNull(globalFrame))
    }

    @Test
    fun malformedHybridPayloadReturnsNullSafely() {
        val frame = MessageFrame(
            id = "hybrid-bad-1",
            type = MessageFrameType.HYBRID_TRANSPORT_CONTROL,
            senderId = "sender-bad",
            createdAtMillis = 1_721_000_101L,
            payload = "not-a-valid-hybrid-payload"
        )

        assertNull(HybridTransportControlFrameFactory.parseOrNull(frame))
    }

    @Test
    fun encodedHybridFrameDoesNotContainChatPlaintext() {
        val message = HybridTransportControlMessage(
            messageType = HybridTransportControlMessage.MessageType.WIFI_DIRECT_SOCKET_HINT,
            sessionId = "hybrid-session-opaque-004",
            publicPeerIdHint = "peer-opaque-004",
            groupOwnerAddress = "192.168.49.44",
            socketPort = 9000,
            createdAtMillis = 1_721_000_004L
        )
        val frame = HybridTransportControlFrameFactory.create(
            message = message,
            frameId = "hybrid-opaque-frame-004",
            senderId = "sender-opaque"
        )
        val encodedFrame = MessageFrameCodec.encode(frame)

        assertFalse(encodedFrame.contains("Hello global chat"))
        assertFalse(encodedFrame.contains("Private hello"))
        assertFalse(encodedFrame.contains("192.168.49.44"))
        assertFalse(encodedFrame.contains("hybrid-session-opaque-004"))
        assertTrue(encodedFrame.contains(MessageFrameType.HYBRID_TRANSPORT_CONTROL.name))
    }
}
