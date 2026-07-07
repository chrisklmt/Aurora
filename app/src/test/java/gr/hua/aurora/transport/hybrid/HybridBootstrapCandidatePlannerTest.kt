package gr.hua.aurora.transport.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridBootstrapCandidatePlannerTest {
    @Test
    fun offerOnlySessionBecomesPassiveCandidateWithSocketReadyFalse() {
        val snapshot = HybridTransportControlState(
            sessionsByPeerId = mapOf(
                "peer-offer" to mapOf(
                    "offer-session-1" to HybridTransportControlSessionState(
                        latestOffer = offerMessage(
                            sessionId = "offer-session-1",
                            createdAtMillis = 1_723_000_001L
                        )
                    )
                )
            )
        )

        val candidates = HybridBootstrapCandidatePlanner.plan(snapshot)

        assertEquals(1, candidates.size)
        assertEquals("peer-offer", candidates.single().peerId)
        assertEquals("offer-session-1", candidates.single().sessionId)
        assertEquals("offer-session-1", candidates.single().bootstrapIdentifier)
        assertTrue(candidates.single().hasOffer)
        assertFalse(candidates.single().hasAccept)
        assertFalse(candidates.single().hasSocketHint)
        assertFalse(candidates.single().socketReady)
    }

    @Test
    fun acceptOnlySessionBecomesPassiveCandidateWithSocketReadyFalse() {
        val snapshot = HybridTransportControlState(
            sessionsByPeerId = mapOf(
                "peer-accept" to mapOf(
                    "accept-session-1" to HybridTransportControlSessionState(
                        latestAccept = acceptMessage(
                            sessionId = "accept-session-1",
                            createdAtMillis = 1_723_000_002L
                        )
                    )
                )
            )
        )

        val candidate = HybridBootstrapCandidatePlanner.plan(snapshot).single()

        assertEquals("peer-accept", candidate.peerId)
        assertEquals("accept-session-1", candidate.sessionId)
        assertEquals("accept-session-1", candidate.bootstrapIdentifier)
        assertFalse(candidate.socketReady)
        assertFalse(candidate.hasOffer)
        assertTrue(candidate.hasAccept)
        assertFalse(candidate.hasSocketHint)
    }

    @Test
    fun validSocketHintSessionBecomesSocketReady() {
        val snapshot = HybridTransportControlState(
            sessionsByPeerId = mapOf(
                "peer-ready" to mapOf(
                    "ready-session-1" to HybridTransportControlSessionState(
                        latestOffer = offerMessage(
                            sessionId = "ready-session-1",
                            createdAtMillis = 1_723_000_010L
                        ),
                        latestSocketHint = socketHintMessage(
                            sessionId = "ready-session-1",
                            createdAtMillis = 1_723_000_020L,
                            groupOwnerAddress = "192.168.49.20",
                            socketPort = 8988
                        )
                    )
                )
            )
        )

        val candidate = HybridBootstrapCandidatePlanner.plan(snapshot).single()

        assertTrue(candidate.socketReady)
        assertEquals("192.168.49.20", candidate.groupOwnerAddress)
        assertEquals(8988, candidate.socketPort)
        assertEquals(1_723_000_020L, candidate.latestCreatedAtMillis)
    }

    @Test
    fun socketHintWithMissingGroupOwnerAddressIsSocketReadyFalse() {
        val snapshot = stateWithSingleSocketHint(
            sessionId = "missing-address-session",
            createdAtMillis = 1_723_000_030L,
            groupOwnerAddress = null,
            socketPort = 8988
        )

        val candidate = HybridBootstrapCandidatePlanner.plan(snapshot).single()

        assertFalse(candidate.socketReady)
        assertEquals(null, candidate.groupOwnerAddress)
    }

    @Test
    fun socketHintWithNullSocketPortIsSocketReadyFalse() {
        val snapshot = stateWithSingleSocketHint(
            sessionId = "null-port-session",
            createdAtMillis = 1_723_000_040L,
            groupOwnerAddress = "192.168.49.40",
            socketPort = null
        )

        val candidate = HybridBootstrapCandidatePlanner.plan(snapshot).single()

        assertFalse(candidate.socketReady)
        assertEquals(null, candidate.socketPort)
    }

    @Test
    fun sessionIdAndBootstrapIdentifierArePreservedExactly() {
        val snapshot = stateWithSingleSocketHint(
            sessionId = "token:Alpha|Beta/0123",
            createdAtMillis = 1_723_000_050L,
            groupOwnerAddress = "192.168.49.70",
            socketPort = 9070
        )

        val candidate = HybridBootstrapCandidatePlanner.plan(snapshot).single()

        assertEquals("token:Alpha|Beta/0123", candidate.sessionId)
        assertEquals("token:Alpha|Beta/0123", candidate.bootstrapIdentifier)
    }

    @Test
    fun publicPeerIdHintIsPreservedExactly() {
        val snapshot = HybridTransportControlState(
            sessionsByPeerId = mapOf(
                "peer-hint" to mapOf(
                    "hint-session-1" to HybridTransportControlSessionState(
                        latestOffer = offerMessage(
                            sessionId = "hint-session-1",
                            createdAtMillis = 1_723_000_080L,
                            publicPeerIdHint = "peer:with/slash+plus=="
                        )
                    )
                )
            )
        )

        val candidate = HybridBootstrapCandidatePlanner.plan(snapshot).single()

        assertEquals("peer:with/slash+plus==", candidate.publicPeerIdHint)
    }

    @Test
    fun socketFieldsArePreservedExactly() {
        val snapshot = stateWithSingleSocketHint(
            sessionId = "socket-fields-session",
            createdAtMillis = 1_723_000_070L,
            groupOwnerAddress = "fe80::1234",
            socketPort = 65535
        )

        val candidate = HybridBootstrapCandidatePlanner.plan(snapshot).single()

        assertEquals("fe80::1234", candidate.groupOwnerAddress)
        assertEquals(65535, candidate.socketPort)
    }

    @Test
    fun candidatesAreSortedWithSocketReadyFirstThenNewestTimestampThenPeerAndSession() {
        val snapshot = HybridTransportControlState(
            sessionsByPeerId = mapOf(
                "peer-b" to mapOf(
                    "session-older-ready" to HybridTransportControlSessionState(
                        latestSocketHint = socketHintMessage(
                            sessionId = "session-older-ready",
                            createdAtMillis = 1_723_000_100L,
                            groupOwnerAddress = "192.168.49.100",
                            socketPort = 9100
                        )
                    )
                ),
                "peer-a" to mapOf(
                    "session-newest-ready" to HybridTransportControlSessionState(
                        latestSocketHint = socketHintMessage(
                            sessionId = "session-newest-ready",
                            createdAtMillis = 1_723_000_300L,
                            groupOwnerAddress = "192.168.49.101",
                            socketPort = 9101
                        )
                    ),
                    "session-passive-newer" to HybridTransportControlSessionState(
                        latestOffer = offerMessage(
                            sessionId = "session-passive-newer",
                            createdAtMillis = 1_723_000_200L
                        )
                    ),
                    "session-passive-newer-b" to HybridTransportControlSessionState(
                        latestOffer = offerMessage(
                            sessionId = "session-passive-newer-b",
                            createdAtMillis = 1_723_000_200L
                        )
                    )
                )
            )
        )

        val candidates = HybridBootstrapCandidatePlanner.plan(snapshot)

        assertEquals(
            listOf(
                "peer-a|session-newest-ready",
                "peer-b|session-older-ready",
                "peer-a|session-passive-newer",
                "peer-a|session-passive-newer-b"
            ),
            candidates.map { "${it.peerId}|${it.sessionId}" }
        )
    }

    @Test
    fun plannerDoesNotMutateSourceSnapshot() {
        val snapshot = HybridTransportControlState(
            sessionsByPeerId = mapOf(
                "peer-stable" to mapOf(
                    "stable-session" to HybridTransportControlSessionState(
                        latestOffer = offerMessage(
                            sessionId = "stable-session",
                            createdAtMillis = 1_723_000_400L
                        ),
                        latestSocketHint = socketHintMessage(
                            sessionId = "stable-session",
                            createdAtMillis = 1_723_000_401L,
                            groupOwnerAddress = "192.168.49.140",
                            socketPort = 9140
                        )
                    )
                )
            )
        )
        val originalSnapshot = snapshot.copy(
            sessionsByPeerId = snapshot.sessionsByPeerId.mapValues { (_, sessions) ->
                sessions.toMap()
            }.toMap()
        )

        val candidates = HybridBootstrapCandidatePlanner.plan(snapshot)

        assertEquals(1, candidates.size)
        assertEquals(originalSnapshot, snapshot)
    }

    @Test
    fun plannerIsPassiveAndOnlyReadsSnapshotState() {
        val snapshot = stateWithSingleSocketHint(
            sessionId = "passive-session",
            createdAtMillis = 1_723_000_500L,
            groupOwnerAddress = "192.168.49.150",
            socketPort = 9150
        )

        val candidates = HybridBootstrapCandidatePlanner.plan(snapshot)

        assertEquals(1, candidates.size)
        assertTrue(candidates.single().socketReady)
    }

    private fun stateWithSingleSocketHint(
        sessionId: String,
        createdAtMillis: Long,
        groupOwnerAddress: String?,
        socketPort: Int?
    ): HybridTransportControlState {
        return HybridTransportControlState(
            sessionsByPeerId = mapOf(
                "peer-socket" to mapOf(
                    sessionId to HybridTransportControlSessionState(
                        latestSocketHint = socketHintMessage(
                            sessionId = sessionId,
                            createdAtMillis = createdAtMillis,
                            groupOwnerAddress = groupOwnerAddress,
                            socketPort = socketPort
                        )
                    )
                )
            )
        )
    }

    private fun offerMessage(
        sessionId: String,
        createdAtMillis: Long,
        publicPeerIdHint: String = "peer-offer-hint"
    ): HybridTransportControlMessage {
        return HybridTransportControlMessage(
            messageType = HybridTransportControlMessage.MessageType.WIFI_DIRECT_OFFER,
            sessionId = sessionId,
            publicPeerIdHint = publicPeerIdHint,
            createdAtMillis = createdAtMillis,
            capabilityFlags = setOf(
                HybridTransportControlMessage.CapabilityFlag.WIFI_DIRECT_BOOTSTRAP,
                HybridTransportControlMessage.CapabilityFlag.BLE_FALLBACK
            )
        )
    }

    private fun acceptMessage(
        sessionId: String,
        createdAtMillis: Long,
        publicPeerIdHint: String = "peer-accept-hint"
    ): HybridTransportControlMessage {
        return HybridTransportControlMessage(
            messageType = HybridTransportControlMessage.MessageType.WIFI_DIRECT_ACCEPT,
            sessionId = sessionId,
            publicPeerIdHint = publicPeerIdHint,
            createdAtMillis = createdAtMillis,
            capabilityFlags = setOf(
                HybridTransportControlMessage.CapabilityFlag.WIFI_DIRECT_BOOTSTRAP
            )
        )
    }

    private fun socketHintMessage(
        sessionId: String,
        createdAtMillis: Long,
        groupOwnerAddress: String?,
        socketPort: Int?
    ): HybridTransportControlMessage {
        return HybridTransportControlMessage(
            messageType = HybridTransportControlMessage.MessageType.WIFI_DIRECT_SOCKET_HINT,
            sessionId = sessionId,
            publicPeerIdHint = "peer-socket-hint",
            groupOwnerAddress = groupOwnerAddress,
            socketPort = socketPort,
            createdAtMillis = createdAtMillis,
            capabilityFlags = setOf(
                HybridTransportControlMessage.CapabilityFlag.WIFI_DIRECT_SOCKET_HINT,
                HybridTransportControlMessage.CapabilityFlag.BLE_FALLBACK
            )
        )
    }
}
