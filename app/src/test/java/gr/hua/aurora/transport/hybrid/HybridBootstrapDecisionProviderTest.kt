package gr.hua.aurora.transport.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridBootstrapDecisionProviderTest {
    @Test
    fun emptyStoreReturnsEmptyCandidatesAndNoCandidates() {
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)

        val decision = provider.currentDecision()

        assertTrue(decision.candidates.isEmpty())
        assertEquals(HybridBootstrapCandidateSelection.NoCandidates, decision.selection)
    }

    @Test
    fun offerOnlyStoredStateReturnsPassiveCandidateAndNoSocketReadyCandidates() {
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        store.record(
            peerId = "peer-offer",
            message = offerMessage(
                sessionId = "offer-session-1",
                createdAtMillis = 1_726_000_001L
            )
        )

        val decision = provider.currentDecision()

        assertEquals(1, decision.candidates.size)
        assertEquals("peer-offer", decision.candidates.single().peerId)
        assertEquals(false, decision.candidates.single().socketReady)
        assertEquals(
            HybridBootstrapCandidateSelection.NoSocketReadyCandidates,
            decision.selection
        )
    }

    @Test
    fun socketReadyStoredStateReturnsSelectedCandidate() {
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        store.record(
            peerId = "peer-ready",
            message = socketHintMessage(
                sessionId = "ready-session-1",
                createdAtMillis = 1_726_000_020L,
                groupOwnerAddress = "192.168.49.20",
                socketPort = 8988
            )
        )

        val decision = provider.currentDecision()

        assertEquals(1, decision.candidates.size)
        assertEquals(
            HybridBootstrapCandidateSelection.Selected(decision.candidates.single()),
            decision.selection
        )
    }

    @Test
    fun providerReflectsLatestStoreSnapshotAcrossRepeatedCalls() {
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)

        val firstDecision = provider.currentDecision()
        store.record(
            peerId = "peer-updated",
            message = socketHintMessage(
                sessionId = "updated-session-1",
                createdAtMillis = 1_726_000_100L,
                groupOwnerAddress = "192.168.49.100",
                socketPort = 9100
            )
        )
        val secondDecision = provider.currentDecision()

        assertEquals(HybridBootstrapCandidateSelection.NoCandidates, firstDecision.selection)
        assertEquals(1, secondDecision.candidates.size)
        assertTrue(secondDecision.selection is HybridBootstrapCandidateSelection.Selected)
    }

    @Test
    fun providerDoesNotMutateTheStore() {
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        store.record(
            peerId = "peer-stable",
            message = offerMessage(
                sessionId = "stable-session-1",
                createdAtMillis = 1_726_000_200L
            )
        )
        val snapshotBefore = store.snapshot()

        val decision = provider.currentDecision()
        val snapshotAfter = store.snapshot()

        assertEquals(1, decision.candidates.size)
        assertEquals(snapshotBefore, snapshotAfter)
    }

    @Test
    fun returnedDecisionCannotMutateProviderOrStoreInternalState() {
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        store.record(
            peerId = "peer-ready",
            message = socketHintMessage(
                sessionId = "ready-session-2",
                createdAtMillis = 1_726_000_300L,
                groupOwnerAddress = "192.168.49.150",
                socketPort = 9150
            )
        )

        val decision = provider.currentDecision()
        val firstRead = decision.candidates
        val secondRead = decision.candidates

        assertNotSame(firstRead, secondRead)
        assertEquals(firstRead, secondRead)

        val mutableCopy = firstRead.toMutableList()
        mutableCopy.clear()

        assertEquals(1, decision.candidates.size)
        assertEquals(1, provider.currentDecision().candidates.size)
        assertEquals(
            store.snapshot().sessionStateFor("peer-ready", "ready-session-2")?.latestSocketHint,
            socketHintMessage(
                sessionId = "ready-session-2",
                createdAtMillis = 1_726_000_300L,
                groupOwnerAddress = "192.168.49.150",
                socketPort = 9150
            )
        )
    }

    @Test
    fun providerIsPassiveAndOnlyReadsStoreState() {
        val store = InMemoryHybridTransportControlStore()
        val provider = HybridBootstrapDecisionProvider(store)
        store.record(
            peerId = "peer-passive",
            message = socketHintMessage(
                sessionId = "passive-session-1",
                createdAtMillis = 1_726_000_400L,
                groupOwnerAddress = "192.168.49.160",
                socketPort = 9160
            )
        )

        val decision = provider.currentDecision()

        assertEquals(1, decision.candidates.size)
        assertTrue(decision.selection is HybridBootstrapCandidateSelection.Selected)
        assertEquals(
            1,
            store.snapshot().sessionsByPeerId["peer-passive"]?.size
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

    private fun socketHintMessage(
        sessionId: String,
        createdAtMillis: Long,
        groupOwnerAddress: String,
        socketPort: Int
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
