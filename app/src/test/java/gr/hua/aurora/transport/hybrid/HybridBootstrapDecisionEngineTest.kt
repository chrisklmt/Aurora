package gr.hua.aurora.transport.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridBootstrapDecisionEngineTest {
    @Test
    fun emptySnapshotReturnsEmptyCandidatesAndNoCandidates() {
        val decision = HybridBootstrapDecisionEngine.decide(
            HybridTransportControlState()
        )

        assertTrue(decision.candidates.isEmpty())
        assertEquals(HybridBootstrapCandidateSelection.NoCandidates, decision.selection)
    }

    @Test
    fun offerOnlySnapshotReturnsPassiveCandidateAndNoSocketReadyCandidates() {
        val snapshot = HybridTransportControlState(
            sessionsByPeerId = mapOf(
                "peer-offer" to mapOf(
                    "offer-session-1" to HybridTransportControlSessionState(
                        latestOffer = offerMessage(
                            sessionId = "offer-session-1",
                            createdAtMillis = 1_725_000_001L
                        )
                    )
                )
            )
        )

        val decision = HybridBootstrapDecisionEngine.decide(snapshot)

        assertEquals(1, decision.candidates.size)
        assertEquals("peer-offer", decision.candidates.single().peerId)
        assertEquals(false, decision.candidates.single().socketReady)
        assertEquals(
            HybridBootstrapCandidateSelection.NoSocketReadyCandidates,
            decision.selection
        )
    }

    @Test
    fun socketReadySnapshotReturnsSelectedExpectedCandidate() {
        val snapshot = HybridTransportControlState(
            sessionsByPeerId = mapOf(
                "peer-ready" to mapOf(
                    "ready-session-1" to HybridTransportControlSessionState(
                        latestOffer = offerMessage(
                            sessionId = "ready-session-1",
                            createdAtMillis = 1_725_000_010L
                        ),
                        latestSocketHint = socketHintMessage(
                            sessionId = "ready-session-1",
                            createdAtMillis = 1_725_000_020L,
                            groupOwnerAddress = "192.168.49.20",
                            socketPort = 8988
                        )
                    )
                )
            )
        )

        val decision = HybridBootstrapDecisionEngine.decide(snapshot)

        assertEquals(1, decision.candidates.size)
        assertEquals(
            HybridBootstrapCandidateSelection.Selected(decision.candidates.single()),
            decision.selection
        )
    }

    @Test
    fun multipleCandidatesPreservePlannerOrdering() {
        val snapshot = HybridTransportControlState(
            sessionsByPeerId = mapOf(
                "peer-b" to mapOf(
                    "session-older-ready" to HybridTransportControlSessionState(
                        latestSocketHint = socketHintMessage(
                            sessionId = "session-older-ready",
                            createdAtMillis = 1_725_000_100L,
                            groupOwnerAddress = "192.168.49.100",
                            socketPort = 9100
                        )
                    )
                ),
                "peer-a" to mapOf(
                    "session-newest-ready" to HybridTransportControlSessionState(
                        latestSocketHint = socketHintMessage(
                            sessionId = "session-newest-ready",
                            createdAtMillis = 1_725_000_300L,
                            groupOwnerAddress = "192.168.49.101",
                            socketPort = 9101
                        )
                    ),
                    "session-passive-newer" to HybridTransportControlSessionState(
                        latestOffer = offerMessage(
                            sessionId = "session-passive-newer",
                            createdAtMillis = 1_725_000_200L
                        )
                    ),
                    "session-passive-newer-b" to HybridTransportControlSessionState(
                        latestOffer = offerMessage(
                            sessionId = "session-passive-newer-b",
                            createdAtMillis = 1_725_000_200L
                        )
                    )
                )
            )
        )

        val decision = HybridBootstrapDecisionEngine.decide(snapshot)
        val expectedCandidates = HybridBootstrapCandidatePlanner.plan(snapshot)

        assertEquals(expectedCandidates, decision.candidates)
        assertEquals(
            listOf(
                "peer-a|session-newest-ready",
                "peer-b|session-older-ready",
                "peer-a|session-passive-newer",
                "peer-a|session-passive-newer-b"
            ),
            decision.candidates.map { "${it.peerId}|${it.sessionId}" }
        )
    }

    @Test
    fun selectorResultMatchesBestSocketReadyCandidate() {
        val snapshot = HybridTransportControlState(
            sessionsByPeerId = mapOf(
                "peer-z" to mapOf(
                    "session-z" to HybridTransportControlSessionState(
                        latestSocketHint = socketHintMessage(
                            sessionId = "session-z",
                            createdAtMillis = 1_725_000_399L,
                            groupOwnerAddress = "192.168.49.130",
                            socketPort = 9130
                        )
                    )
                ),
                "peer-a" to mapOf(
                    "session-a" to HybridTransportControlSessionState(
                        latestSocketHint = socketHintMessage(
                            sessionId = "session-a",
                            createdAtMillis = 1_725_000_400L,
                            groupOwnerAddress = "192.168.49.131",
                            socketPort = 9131
                        )
                    )
                )
            )
        )

        val decision = HybridBootstrapDecisionEngine.decide(snapshot)
        val expectedSelection = HybridBootstrapCandidateSelector.select(decision.candidates)

        assertEquals(expectedSelection, decision.selection)
        assertEquals(
            HybridBootstrapCandidateSelection.Selected(decision.candidates.first()),
            decision.selection
        )
    }

    @Test
    fun decisionDoesNotMutateSourceSnapshot() {
        val snapshot = HybridTransportControlState(
            sessionsByPeerId = mapOf(
                "peer-stable" to mapOf(
                    "stable-session" to HybridTransportControlSessionState(
                        latestOffer = offerMessage(
                            sessionId = "stable-session",
                            createdAtMillis = 1_725_000_500L
                        ),
                        latestSocketHint = socketHintMessage(
                            sessionId = "stable-session",
                            createdAtMillis = 1_725_000_501L,
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

        val decision = HybridBootstrapDecisionEngine.decide(snapshot)

        assertEquals(1, decision.candidates.size)
        assertEquals(originalSnapshot, snapshot)
    }

    @Test
    fun returnedDecisionCandidateListCannotBeUsedToMutateInternalState() {
        val snapshot = HybridTransportControlState(
            sessionsByPeerId = mapOf(
                "peer-ready" to mapOf(
                    "ready-session-2" to HybridTransportControlSessionState(
                        latestSocketHint = socketHintMessage(
                            sessionId = "ready-session-2",
                            createdAtMillis = 1_725_000_600L,
                            groupOwnerAddress = "192.168.49.150",
                            socketPort = 9150
                        )
                    )
                )
            )
        )

        val decision = HybridBootstrapDecisionEngine.decide(snapshot)
        val firstRead = decision.candidates
        val secondRead = decision.candidates

        assertNotSame(firstRead, secondRead)
        assertEquals(firstRead, secondRead)

        val mutableCopy = firstRead.toMutableList()
        mutableCopy.clear()

        assertEquals(1, decision.candidates.size)
        assertEquals(firstRead, decision.candidates)
    }

    @Test
    fun decisionEngineIsPassiveAndOnlyComposesPlannerAndSelector() {
        val snapshot = HybridTransportControlState(
            sessionsByPeerId = mapOf(
                "peer-passive" to mapOf(
                    "passive-session" to HybridTransportControlSessionState(
                        latestSocketHint = socketHintMessage(
                            sessionId = "passive-session",
                            createdAtMillis = 1_725_000_700L,
                            groupOwnerAddress = "192.168.49.160",
                            socketPort = 9160
                        )
                    )
                )
            )
        )

        val decision = HybridBootstrapDecisionEngine.decide(snapshot)

        assertTrue(decision.selection is HybridBootstrapCandidateSelection.Selected)
        assertEquals(1, decision.candidates.size)
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
