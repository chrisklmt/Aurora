package gr.hua.aurora.transport.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridBootstrapCandidateSelectorTest {
    @Test
    fun emptyCandidateListReturnsNoCandidates() {
        val result = HybridBootstrapCandidateSelector.select(emptyList())

        assertEquals(HybridBootstrapCandidateSelection.NoCandidates, result)
    }

    @Test
    fun onlyNonReadyCandidatesReturnNoSocketReadyCandidates() {
        val result = HybridBootstrapCandidateSelector.select(
            listOf(
                candidate(
                    peerId = "peer-a",
                    sessionId = "session-a",
                    latestCreatedAtMillis = 1_724_000_001L,
                    socketReady = false
                ),
                candidate(
                    peerId = "peer-b",
                    sessionId = "session-b",
                    latestCreatedAtMillis = 1_724_000_002L,
                    socketReady = false
                )
            )
        )

        assertEquals(HybridBootstrapCandidateSelection.NoSocketReadyCandidates, result)
    }

    @Test
    fun singleSocketReadyCandidateIsSelected() {
        val candidate = candidate(
            peerId = "peer-ready",
            sessionId = "session-ready",
            latestCreatedAtMillis = 1_724_000_010L,
            socketReady = true
        )

        val result = HybridBootstrapCandidateSelector.select(listOf(candidate))

        assertEquals(
            HybridBootstrapCandidateSelection.Selected(candidate),
            result
        )
    }

    @Test
    fun newestSocketReadyCandidateWins() {
        val olderReady = candidate(
            peerId = "peer-old",
            sessionId = "session-old",
            latestCreatedAtMillis = 1_724_000_020L,
            socketReady = true
        )
        val newerReady = candidate(
            peerId = "peer-new",
            sessionId = "session-new",
            latestCreatedAtMillis = 1_724_000_021L,
            socketReady = true
        )

        val result = HybridBootstrapCandidateSelector.select(
            listOf(olderReady, newerReady)
        )

        assertEquals(
            HybridBootstrapCandidateSelection.Selected(newerReady),
            result
        )
    }

    @Test
    fun peerIdAscendingBreaksTimestampTies() {
        val peerB = candidate(
            peerId = "peer-b",
            sessionId = "session-a",
            latestCreatedAtMillis = 1_724_000_030L,
            socketReady = true
        )
        val peerA = candidate(
            peerId = "peer-a",
            sessionId = "session-z",
            latestCreatedAtMillis = 1_724_000_030L,
            socketReady = true
        )

        val result = HybridBootstrapCandidateSelector.select(
            listOf(peerB, peerA)
        )

        assertEquals(
            HybridBootstrapCandidateSelection.Selected(peerA),
            result
        )
    }

    @Test
    fun sessionIdAscendingBreaksPeerAndTimestampTies() {
        val laterSession = candidate(
            peerId = "peer-a",
            sessionId = "session-z",
            latestCreatedAtMillis = 1_724_000_040L,
            socketReady = true
        )
        val earlierSession = candidate(
            peerId = "peer-a",
            sessionId = "session-a",
            latestCreatedAtMillis = 1_724_000_040L,
            socketReady = true
        )

        val result = HybridBootstrapCandidateSelector.select(
            listOf(laterSession, earlierSession)
        )

        assertEquals(
            HybridBootstrapCandidateSelection.Selected(earlierSession),
            result
        )
    }

    @Test
    fun nonReadyCandidatesNeverWinOverReadyCandidates() {
        val nonReady = candidate(
            peerId = "peer-newer",
            sessionId = "session-newer",
            latestCreatedAtMillis = 1_724_000_050L,
            socketReady = false
        )
        val ready = candidate(
            peerId = "peer-ready",
            sessionId = "session-ready",
            latestCreatedAtMillis = 1_724_000_049L,
            socketReady = true
        )

        val result = HybridBootstrapCandidateSelector.select(
            listOf(nonReady, ready)
        )

        assertEquals(
            HybridBootstrapCandidateSelection.Selected(ready),
            result
        )
    }

    @Test
    fun selectedCandidateIsPreservedExactly() {
        val candidate = candidate(
            peerId = "peer-preserved",
            sessionId = "session-preserved",
            bootstrapIdentifier = "token:Alpha|Beta/0123",
            publicPeerIdHint = "peer:with/slash+plus==",
            groupOwnerAddress = "fe80::1234",
            socketPort = 65535,
            latestCreatedAtMillis = 1_724_000_060L,
            hasOffer = true,
            hasAccept = true,
            hasSocketHint = true,
            socketReady = true
        )

        val result = HybridBootstrapCandidateSelector.select(listOf(candidate))

        assertEquals(
            HybridBootstrapCandidateSelection.Selected(candidate),
            result
        )
    }

    @Test
    fun selectorDoesNotMutateInputList() {
        val originalCandidates = mutableListOf(
            candidate(
                peerId = "peer-b",
                sessionId = "session-b",
                latestCreatedAtMillis = 1_724_000_070L,
                socketReady = true
            ),
            candidate(
                peerId = "peer-a",
                sessionId = "session-a",
                latestCreatedAtMillis = 1_724_000_071L,
                socketReady = false
            ),
            candidate(
                peerId = "peer-c",
                sessionId = "session-c",
                latestCreatedAtMillis = 1_724_000_072L,
                socketReady = true
            )
        )
        val snapshotBefore = originalCandidates.toList()

        val result = HybridBootstrapCandidateSelector.select(originalCandidates)

        assertTrue(result is HybridBootstrapCandidateSelection.Selected)
        assertEquals(snapshotBefore, originalCandidates)
    }

    @Test
    fun selectorIsPassiveAndOnlyChoosesAmongCandidates() {
        val result = HybridBootstrapCandidateSelector.select(
            listOf(
                candidate(
                    peerId = "peer-passive",
                    sessionId = "session-passive",
                    latestCreatedAtMillis = 1_724_000_080L,
                    socketReady = true
                )
            )
        )

        assertTrue(result is HybridBootstrapCandidateSelection.Selected)
    }

    private fun candidate(
        peerId: String,
        sessionId: String,
        latestCreatedAtMillis: Long,
        socketReady: Boolean,
        bootstrapIdentifier: String = sessionId,
        publicPeerIdHint: String? = "peer-hint",
        groupOwnerAddress: String? = if (socketReady) "192.168.49.1" else null,
        socketPort: Int? = if (socketReady) 8988 else null,
        hasOffer: Boolean = true,
        hasAccept: Boolean = false,
        hasSocketHint: Boolean = socketReady
    ): HybridBootstrapCandidate {
        return HybridBootstrapCandidate(
            peerId = peerId,
            sessionId = sessionId,
            bootstrapIdentifier = bootstrapIdentifier,
            publicPeerIdHint = publicPeerIdHint,
            groupOwnerAddress = groupOwnerAddress,
            socketPort = socketPort,
            latestCreatedAtMillis = latestCreatedAtMillis,
            hasOffer = hasOffer,
            hasAccept = hasAccept,
            hasSocketHint = hasSocketHint,
            socketReady = socketReady
        )
    }
}
