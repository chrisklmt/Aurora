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
    fun encodingAndDecodingPhaseReadyPreservesWifiDirectCorrelationFields() {
        val message = HybridTransportControlMessage(
            messageType = HybridTransportControlMessage.MessageType.AUTOMATED_DIAGNOSTICS_PHASE_READY,
            sessionId = "diag-run-011",
            publicPeerIdHint = "coordinator-peer-011",
            relatedPeerIdHint = "participant-peer-011",
            senderPeerIdHint = "participant-peer-011",
            expectedPeerIdHint = "coordinator-peer-011",
            wifiDirectCorrelationToken = "a1b2c3d4e5f60718293a4b5c6d7e8f90",
            wifiDirectDeviceName = "Aurora White",
            createdAtMillis = 1_720_000_404L,
            associatedSessionId = "secure-session-011",
            expiresAtMillis = 1_720_008_404L,
            capabilityFlags = setOf(
                HybridTransportControlMessage.CapabilityFlag.BLE_FALLBACK
            )
        )

        val encoded = HybridTransportControlCodec.encode(message)
        val decoded = HybridTransportControlCodec.decode(encoded)

        assertEquals(18, encoded.split("|").size)
        assertEquals(message, decoded)
    }

    @Test
    fun encodingAndDecodingPhaseReadyPreservesDiagnosticsApplicationProbePayload() {
        val message = HybridTransportControlMessage(
            messageType = HybridTransportControlMessage.MessageType.AUTOMATED_DIAGNOSTICS_PHASE_READY,
            sessionId = "diag-run-012",
            publicPeerIdHint = "coordinator-peer-012",
            relatedPeerIdHint = "participant-peer-012",
            senderPeerIdHint = "coordinator-peer-012",
            expectedPeerIdHint = "participant-peer-012",
            diagnosticsStepNumber = 18,
            diagnosticsPhaseState = "RUNNING",
            diagnosticsAttemptNumber = 2,
            diagnosticsApplicationProbePayload =
                "GLOBAL\u001Fglobal-123\u001EPRIVATE\u001Fprivate-456",
            createdAtMillis = 1_720_000_412L,
            associatedSessionId = "secure-session-012",
            expiresAtMillis = 1_720_008_412L,
            capabilityFlags = setOf(
                HybridTransportControlMessage.CapabilityFlag.BLE_FALLBACK
            )
        )

        val encoded = HybridTransportControlCodec.encode(message)
        val decoded = HybridTransportControlCodec.decode(encoded)

        assertEquals(22, encoded.split("|").size)
        assertEquals(message, decoded)
    }

    @Test
    fun legacyPhaseReadyAddressPayloadStillDecodesWithoutToken() {
        val legacyMessage = HybridTransportControlMessage(
            messageType = HybridTransportControlMessage.MessageType.AUTOMATED_DIAGNOSTICS_PHASE_READY,
            sessionId = "diag-run-legacy",
            publicPeerIdHint = "coordinator-peer-legacy",
            relatedPeerIdHint = "participant-peer-legacy",
            senderPeerIdHint = "participant-peer-legacy",
            expectedPeerIdHint = "coordinator-peer-legacy",
            wifiDirectDeviceAddress = "aa:bb:cc:dd:ee:11",
            wifiDirectDeviceName = "Aurora White",
            createdAtMillis = 1_720_000_405L,
            associatedSessionId = "secure-session-legacy",
            expiresAtMillis = 1_720_008_405L,
            capabilityFlags = setOf(
                HybridTransportControlMessage.CapabilityFlag.BLE_FALLBACK
            )
        )

        val legacyParts = listOf(
            "AURORA_HYBRID_CONTROL",
            HybridTransportControlCodec.currentProtocolVersion.toString(),
            legacyMessage.messageType.name,
            "ZGlhZy1ydW4tbGVnYWN5",
            "Y29vcmRpbmF0b3ItcGVlci1sZWdhY3k",
            "cGFydGljaXBhbnQtcGVlci1sZWdhY3k",
            "~",
            "~",
            legacyMessage.createdAtMillis.toString(),
            "c2VjdXJlLXNlc3Npb24tbGVnYWN5",
            legacyMessage.expiresAtMillis.toString(),
            "~",
            "BLE_FALLBACK",
            "cGFydGljaXBhbnQtcGVlci1sZWdhY3k",
            "Y29vcmRpbmF0b3ItcGVlci1sZWdhY3k",
            "YWE6YmI6Y2M6ZGQ6ZWU6MTE",
            "QXVyb3JhIFdoaXRl"
        ).joinToString("|")

        val decoded = HybridTransportControlCodec.decode(legacyParts)

        assertEquals(null, decoded.wifiDirectCorrelationToken)
        assertEquals("aa:bb:cc:dd:ee:11", decoded.wifiDirectDeviceAddress)
        assertEquals("Aurora White", decoded.wifiDirectDeviceName)
    }

    @Test
    fun legacyMessagesKeepLegacyPartCount() {
        val message = HybridTransportControlMessage(
            messageType = HybridTransportControlMessage.MessageType.WIFI_DIRECT_OFFER,
            sessionId = "bootstrap-token-offer-legacy",
            publicPeerIdHint = "peer-offer-legacy",
            createdAtMillis = 1_720_000_150L
        )

        val encoded = HybridTransportControlCodec.encode(message)

        assertEquals(13, encoded.split("|").size)
        assertEquals(message, HybridTransportControlCodec.decode(encoded))
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
