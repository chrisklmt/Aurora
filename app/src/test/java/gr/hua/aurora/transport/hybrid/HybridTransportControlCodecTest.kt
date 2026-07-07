package gr.hua.aurora.transport.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridTransportControlCodecTest {
    @Test
    fun encodingAndDecodingWifiDirectOfferPreservesFields() {
        val message = HybridTransportControlMessage(
            messageType = HybridTransportControlMessage.MessageType.WIFI_DIRECT_OFFER,
            sessionId = "bootstrap-token-offer-001",
            publicPeerIdHint = "peer-offer-abc123",
            createdAtMillis = 1_720_000_101L,
            capabilityFlags = setOf(
                HybridTransportControlMessage.CapabilityFlag.WIFI_DIRECT_BOOTSTRAP,
                HybridTransportControlMessage.CapabilityFlag.BLE_FALLBACK
            )
        )

        val decoded = HybridTransportControlCodec.decode(
            HybridTransportControlCodec.encode(message)
        )

        assertEquals(message, decoded)
    }

    @Test
    fun encodingAndDecodingWifiDirectAcceptPreservesFields() {
        val message = HybridTransportControlMessage(
            messageType = HybridTransportControlMessage.MessageType.WIFI_DIRECT_ACCEPT,
            sessionId = "bootstrap-token-accept-002",
            publicPeerIdHint = "peer-accept-xyz789",
            createdAtMillis = 1_720_000_202L,
            capabilityFlags = setOf(
                HybridTransportControlMessage.CapabilityFlag.WIFI_DIRECT_BOOTSTRAP
            )
        )

        val decoded = HybridTransportControlCodec.decode(
            HybridTransportControlCodec.encode(message)
        )

        assertEquals(message, decoded)
    }

    @Test
    fun encodingAndDecodingWifiDirectSocketHintPreservesOptionalSocketFields() {
        val message = HybridTransportControlMessage(
            messageType = HybridTransportControlMessage.MessageType.WIFI_DIRECT_SOCKET_HINT,
            sessionId = "bootstrap-token-socket-003",
            publicPeerIdHint = "peer-socket-uvw456",
            groupOwnerAddress = "192.168.49.1",
            socketPort = 8988,
            createdAtMillis = 1_720_000_303L,
            capabilityFlags = setOf(
                HybridTransportControlMessage.CapabilityFlag.WIFI_DIRECT_SOCKET_HINT,
                HybridTransportControlMessage.CapabilityFlag.BLE_FALLBACK
            )
        )

        val decoded = HybridTransportControlCodec.decode(
            HybridTransportControlCodec.encode(message)
        )

        assertEquals(message, decoded)
        assertEquals("192.168.49.1", decoded.groupOwnerAddress)
        assertEquals(8988, decoded.socketPort)
    }

    @Test
    fun unsupportedProtocolVersionIsRejectedSafely() {
        val encoded = listOf(
            "AURORA_HYBRID_CONTROL",
            "99",
            "WIFI_DIRECT_OFFER",
            "c2Vzc2lvbi0x",
            "~",
            "~",
            "~",
            "1720000404",
            "~"
        ).joinToString("|")

        assertNull(HybridTransportControlCodec.decodeOrNull(encoded))
    }

    @Test
    fun unknownMessageTypeIsRejectedSafely() {
        val encoded = listOf(
            "AURORA_HYBRID_CONTROL",
            HybridTransportControlCodec.currentProtocolVersion.toString(),
            "WIFI_DIRECT_UNKNOWN",
            "c2Vzc2lvbi0y",
            "~",
            "~",
            "~",
            "1720000505",
            "~"
        ).joinToString("|")

        assertNull(HybridTransportControlCodec.decodeOrNull(encoded))
    }

    @Test
    fun malformedPayloadReturnsNullSafely() {
        assertNull(HybridTransportControlCodec.decodeOrNull("not-a-valid-hybrid-control-payload"))
    }

    @Test
    fun tokenAndPeerFieldsArePreservedExactly() {
        val message = HybridTransportControlMessage(
            messageType = HybridTransportControlMessage.MessageType.WIFI_DIRECT_ACCEPT,
            sessionId = "token:Alpha|Beta/0123",
            publicPeerIdHint = "peer:with/slash+plus==",
            createdAtMillis = 1_720_000_606L
        )

        val decoded = HybridTransportControlCodec.decode(
            HybridTransportControlCodec.encode(message)
        )

        assertEquals("token:Alpha|Beta/0123", decoded.sessionId)
        assertEquals("peer:with/slash+plus==", decoded.publicPeerIdHint)
    }

    @Test
    fun optionalSocketFieldsAndCapabilitiesArePreservedExactly() {
        val message = HybridTransportControlMessage(
            messageType = HybridTransportControlMessage.MessageType.WIFI_DIRECT_SOCKET_HINT,
            sessionId = "socket-hint-007",
            publicPeerIdHint = "peer-socket-007",
            groupOwnerAddress = "fe80::1234",
            socketPort = 65535,
            createdAtMillis = 1_720_000_707L,
            capabilityFlags = setOf(
                HybridTransportControlMessage.CapabilityFlag.WIFI_DIRECT_SOCKET_HINT,
                HybridTransportControlMessage.CapabilityFlag.WIFI_DIRECT_BOOTSTRAP
            )
        )

        val decoded = HybridTransportControlCodec.decode(
            HybridTransportControlCodec.encode(message)
        )

        assertEquals("fe80::1234", decoded.groupOwnerAddress)
        assertEquals(65535, decoded.socketPort)
        assertTrue(
            decoded.capabilityFlags.contains(
                HybridTransportControlMessage.CapabilityFlag.WIFI_DIRECT_SOCKET_HINT
            )
        )
        assertTrue(
            decoded.capabilityFlags.contains(
                HybridTransportControlMessage.CapabilityFlag.WIFI_DIRECT_BOOTSTRAP
            )
        )
    }
}
