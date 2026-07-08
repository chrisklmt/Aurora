package gr.hua.aurora.transport.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridBootstrapDiagnosticsFormatterTest {
    @Test
    fun emptyDecisionFormatsAsNoCandidatesDiagnostics() {
        val decision = HybridBootstrapDecision.create(
            candidates = emptyList(),
            selection = HybridBootstrapCandidateSelection.NoCandidates
        )

        val diagnostics = HybridBootstrapDiagnosticsFormatter.format(decision)

        assertEquals(0, diagnostics.candidateCount)
        assertEquals(0, diagnostics.socketReadyCandidateCount)
        assertEquals(HybridBootstrapDiagnostics.SelectionStatus.NoCandidates, diagnostics.selectionStatus)
        assertEquals(null, diagnostics.selectedPeerId)
        assertEquals(null, diagnostics.selectedSessionId)
        assertEquals(null, diagnostics.selectedGroupOwnerAddress)
        assertEquals(null, diagnostics.selectedSocketPort)
        assertEquals(null, diagnostics.selectedLatestCreatedAtMillis)
    }

    @Test
    fun nonReadyDecisionFormatsAsNoSocketReadyCandidatesDiagnostics() {
        val decision = HybridBootstrapDecision.create(
            candidates = listOf(
                candidate(
                    peerId = "peer-a",
                    sessionId = "session-a",
                    latestCreatedAtMillis = 1_727_000_001L,
                    socketReady = false
                )
            ),
            selection = HybridBootstrapCandidateSelection.NoSocketReadyCandidates
        )

        val diagnostics = HybridBootstrapDiagnosticsFormatter.format(decision)

        assertEquals(1, diagnostics.candidateCount)
        assertEquals(0, diagnostics.socketReadyCandidateCount)
        assertEquals(
            HybridBootstrapDiagnostics.SelectionStatus.NoSocketReadyCandidates,
            diagnostics.selectionStatus
        )
        assertEquals(null, diagnostics.selectedPeerId)
        assertEquals(null, diagnostics.selectedSessionId)
        assertEquals(null, diagnostics.selectedGroupOwnerAddress)
        assertEquals(null, diagnostics.selectedSocketPort)
        assertEquals(null, diagnostics.selectedLatestCreatedAtMillis)
    }

    @Test
    fun selectedDecisionPreservesSelectedPeerSessionAddressPortAndTimestamp() {
        val selectedCandidate = candidate(
            peerId = "peer-selected",
            sessionId = "session-selected",
            latestCreatedAtMillis = 1_727_000_010L,
            socketReady = true,
            groupOwnerAddress = "192.168.49.20",
            socketPort = 8988
        )
        val decision = HybridBootstrapDecision.create(
            candidates = listOf(selectedCandidate),
            selection = HybridBootstrapCandidateSelection.Selected(selectedCandidate)
        )

        val diagnostics = HybridBootstrapDiagnosticsFormatter.format(decision)

        assertEquals("peer-selected", diagnostics.selectedPeerId)
        assertEquals("session-selected", diagnostics.selectedSessionId)
        assertEquals("192.168.49.20", diagnostics.selectedGroupOwnerAddress)
        assertEquals(8988, diagnostics.selectedSocketPort)
        assertEquals(1_727_000_010L, diagnostics.selectedLatestCreatedAtMillis)
    }

    @Test
    fun candidateCountsAreCorrect() {
        val readyA = candidate(
            peerId = "peer-a",
            sessionId = "session-a",
            latestCreatedAtMillis = 1_727_000_020L,
            socketReady = true
        )
        val readyB = candidate(
            peerId = "peer-b",
            sessionId = "session-b",
            latestCreatedAtMillis = 1_727_000_021L,
            socketReady = true
        )
        val passive = candidate(
            peerId = "peer-c",
            sessionId = "session-c",
            latestCreatedAtMillis = 1_727_000_022L,
            socketReady = false
        )
        val decision = HybridBootstrapDecision.create(
            candidates = listOf(readyA, passive, readyB),
            selection = HybridBootstrapCandidateSelection.Selected(readyB)
        )

        val diagnostics = HybridBootstrapDiagnosticsFormatter.format(decision)

        assertEquals(3, diagnostics.candidateCount)
    }

    @Test
    fun socketReadyCandidateCountIsCorrect() {
        val decision = HybridBootstrapDecision.create(
            candidates = listOf(
                candidate(
                    peerId = "peer-a",
                    sessionId = "session-a",
                    latestCreatedAtMillis = 1_727_000_030L,
                    socketReady = true
                ),
                candidate(
                    peerId = "peer-b",
                    sessionId = "session-b",
                    latestCreatedAtMillis = 1_727_000_031L,
                    socketReady = false
                ),
                candidate(
                    peerId = "peer-c",
                    sessionId = "session-c",
                    latestCreatedAtMillis = 1_727_000_032L,
                    socketReady = true
                )
            ),
            selection = HybridBootstrapCandidateSelection.Selected(
                candidate(
                    peerId = "peer-c",
                    sessionId = "session-c",
                    latestCreatedAtMillis = 1_727_000_032L,
                    socketReady = true
                )
            )
        )

        val diagnostics = HybridBootstrapDiagnosticsFormatter.format(decision)

        assertEquals(2, diagnostics.socketReadyCandidateCount)
    }

    @Test
    fun statusTextIsStableForEmptyDecision() {
        val diagnostics = HybridBootstrapDiagnosticsFormatter.format(
            HybridBootstrapDecision.create(
                candidates = emptyList(),
                selection = HybridBootstrapCandidateSelection.NoCandidates
            )
        )

        assertEquals("No hybrid bootstrap candidates", diagnostics.statusText)
    }

    @Test
    fun statusTextIsStableForNonReadyDecision() {
        val diagnostics = HybridBootstrapDiagnosticsFormatter.format(
            HybridBootstrapDecision.create(
                candidates = listOf(
                    candidate(
                        peerId = "peer-passive",
                        sessionId = "session-passive",
                        latestCreatedAtMillis = 1_727_000_040L,
                        socketReady = false
                    )
                ),
                selection = HybridBootstrapCandidateSelection.NoSocketReadyCandidates
            )
        )

        assertEquals(
            "Hybrid bootstrap candidates available, none socket-ready",
            diagnostics.statusText
        )
    }

    @Test
    fun statusTextIsStableForSelectedDecision() {
        val selectedCandidate = candidate(
            peerId = "peer-status",
            sessionId = "session-status",
            latestCreatedAtMillis = 1_727_000_050L,
            socketReady = true,
            groupOwnerAddress = "192.168.49.50",
            socketPort = 9050
        )
        val diagnostics = HybridBootstrapDiagnosticsFormatter.format(
            HybridBootstrapDecision.create(
                candidates = listOf(selectedCandidate),
                selection = HybridBootstrapCandidateSelection.Selected(selectedCandidate)
            )
        )

        assertEquals(
            "Hybrid bootstrap candidate ready: peer=peer-status session=session-status address=192.168.49.50 port=9050",
            diagnostics.statusText
        )
    }

    @Test
    fun formatterDoesNotMutateTheDecision() {
        val selectedCandidate = candidate(
            peerId = "peer-stable",
            sessionId = "session-stable",
            latestCreatedAtMillis = 1_727_000_060L,
            socketReady = true
        )
        val decision = HybridBootstrapDecision.create(
            candidates = listOf(
                selectedCandidate,
                candidate(
                    peerId = "peer-passive",
                    sessionId = "session-passive",
                    latestCreatedAtMillis = 1_727_000_061L,
                    socketReady = false
                )
            ),
            selection = HybridBootstrapCandidateSelection.Selected(selectedCandidate)
        )
        val before = decision.candidates

        val diagnostics = HybridBootstrapDiagnosticsFormatter.format(decision)
        val after = decision.candidates

        assertEquals(before, after)
        assertNotSame(before, after)
        assertTrue(diagnostics.selectionStatus == HybridBootstrapDiagnostics.SelectionStatus.Selected)
    }

    @Test
    fun formatterIsPassiveAndOnlyBuildsDiagnostics() {
        val diagnostics = HybridBootstrapDiagnosticsFormatter.format(
            HybridBootstrapDecision.create(
                candidates = listOf(
                    candidate(
                        peerId = "peer-passive",
                        sessionId = "session-passive",
                        latestCreatedAtMillis = 1_727_000_070L,
                        socketReady = true
                    )
                ),
                selection = HybridBootstrapCandidateSelection.Selected(
                    candidate(
                        peerId = "peer-passive",
                        sessionId = "session-passive",
                        latestCreatedAtMillis = 1_727_000_070L,
                        socketReady = true
                    )
                )
            )
        )

        assertEquals(1, diagnostics.candidateCount)
        assertEquals(1, diagnostics.socketReadyCandidateCount)
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
