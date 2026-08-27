package gr.hua.aurora.transport.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridBootstrapSocketEndpointResolverTest {
    @Test
    fun noCandidatesDecisionReturnsNoCandidatesResolution() {
        val decision = decision(
            candidates = emptyList(),
            selection = HybridBootstrapCandidateSelection.NoCandidates
        )

        val result = HybridBootstrapSocketEndpointResolver.resolve(decision)

        assertEquals(
            HybridBootstrapSocketEndpointResolution.NoCandidates,
            result
        )
    }

    @Test
    fun noSocketReadyCandidatesDecisionReturnsNoSocketReadyCandidateResolution() {
        val decision = decision(
            candidates = listOf(
                candidate(
                    peerId = "peer-passive",
                    sessionId = "session-passive",
                    latestCreatedAtMillis = 1_726_000_001L,
                    socketReady = false
                )
            ),
            selection = HybridBootstrapCandidateSelection.NoSocketReadyCandidates
        )

        val result = HybridBootstrapSocketEndpointResolver.resolve(decision)

        assertEquals(
            HybridBootstrapSocketEndpointResolution.NoSocketReadyCandidate,
            result
        )
    }

    @Test
    fun selectedSocketReadyCandidateReturnsResolvedEndpoint() {
        val candidate = candidate(
            peerId = "peer-ready",
            sessionId = "session-ready",
            bootstrapIdentifier = "bootstrap-ready",
            groupOwnerAddress = "192.168.49.10",
            socketPort = 8988,
            latestCreatedAtMillis = 1_726_000_010L,
            socketReady = true
        )
        val decision = decision(
            candidates = listOf(candidate),
            selection = HybridBootstrapCandidateSelection.Selected(candidate)
        )

        val result = HybridBootstrapSocketEndpointResolver.resolve(decision)

        assertEquals(
            HybridBootstrapSocketEndpointResolution.Resolved(
                HybridBootstrapSocketEndpoint(
                    peerId = "peer-ready",
                    sessionId = "session-ready",
                    bootstrapIdentifier = "bootstrap-ready",
                    groupOwnerAddress = "192.168.49.10",
                    socketPort = 8988,
                    latestCreatedAtMillis = 1_726_000_010L
                )
            ),
            result
        )
    }

    @Test
    fun matchingSocketHintObservationIsPreservedOnResolvedEndpoint() {
        val candidate = candidate(
            peerId = "peer-ready",
            sessionId = "session-ready",
            bootstrapIdentifier = "bootstrap-ready",
            groupOwnerAddress = "192.168.49.10",
            socketPort = 8988,
            latestCreatedAtMillis = 1_726_000_011L,
            socketReady = true
        )
        val decision = decision(
            candidates = listOf(candidate),
            selection = HybridBootstrapCandidateSelection.Selected(candidate)
        )

        val result = HybridBootstrapSocketEndpointResolver.resolve(
            decision = decision,
            socketHintObservation = HybridBootstrapSocketHintObservation(
                peerId = "peer-ready",
                sessionId = "session-ready",
                groupOwnerAddress = "192.168.49.10",
                socketPort = 8988,
                createdAtMillis = 1_726_000_011L,
                observedAtMonotonicMillis = 4_321L
            )
        )

        assertEquals(
            HybridBootstrapSocketEndpointResolution.Resolved(
                HybridBootstrapSocketEndpoint(
                    peerId = "peer-ready",
                    sessionId = "session-ready",
                    bootstrapIdentifier = "bootstrap-ready",
                    groupOwnerAddress = "192.168.49.10",
                    socketPort = 8988,
                    latestCreatedAtMillis = 1_726_000_011L,
                    localSocketHintObservedAtMonotonicMillis = 4_321L
                )
            ),
            result
        )
    }

    @Test
    fun resolvedEndpointPreservesPeerSessionBootstrapAddressPortAndTimestampExactly() {
        val candidate = candidate(
            peerId = "peer/Alpha+01",
            sessionId = "session:Beta|02",
            bootstrapIdentifier = "bootstrap==Gamma/03",
            groupOwnerAddress = "fe80::1234",
            socketPort = 65535,
            latestCreatedAtMillis = 1_726_000_020L,
            socketReady = true
        )
        val decision = decision(
            candidates = listOf(candidate),
            selection = HybridBootstrapCandidateSelection.Selected(candidate)
        )

        val result = HybridBootstrapSocketEndpointResolver.resolve(decision)

        assertEquals(
            HybridBootstrapSocketEndpointResolution.Resolved(
                HybridBootstrapSocketEndpoint(
                    peerId = "peer/Alpha+01",
                    sessionId = "session:Beta|02",
                    bootstrapIdentifier = "bootstrap==Gamma/03",
                    groupOwnerAddress = "fe80::1234",
                    socketPort = 65535,
                    latestCreatedAtMillis = 1_726_000_020L
                )
            ),
            result
        )
    }

    @Test
    fun selectedButNonSocketReadyCandidateReturnsInvalidSelectedCandidate() {
        val candidate = candidate(
            peerId = "peer-not-ready",
            sessionId = "session-not-ready",
            latestCreatedAtMillis = 1_726_000_030L,
            socketReady = false
        )
        val decision = decision(
            candidates = listOf(candidate),
            selection = HybridBootstrapCandidateSelection.Selected(candidate)
        )

        val result = HybridBootstrapSocketEndpointResolver.resolve(decision)

        assertEquals(
            HybridBootstrapSocketEndpointResolution.InvalidSelectedCandidate(
                "Selected hybrid bootstrap candidate is not socket-ready."
            ),
            result
        )
    }

    @Test
    fun selectedCandidateWithMissingAddressReturnsInvalidSelectedCandidate() {
        val candidate = candidate(
            peerId = "peer-missing-address",
            sessionId = "session-missing-address",
            latestCreatedAtMillis = 1_726_000_040L,
            groupOwnerAddress = null,
            socketPort = 8988,
            socketReady = true
        )
        val decision = decision(
            candidates = listOf(candidate),
            selection = HybridBootstrapCandidateSelection.Selected(candidate)
        )

        val result = HybridBootstrapSocketEndpointResolver.resolve(decision)

        assertEquals(
            HybridBootstrapSocketEndpointResolution.InvalidSelectedCandidate(
                "Selected hybrid bootstrap candidate groupOwnerAddress is missing."
            ),
            result
        )
    }

    @Test
    fun selectedCandidateWithMissingPortReturnsInvalidSelectedCandidate() {
        val candidate = candidate(
            peerId = "peer-missing-port",
            sessionId = "session-missing-port",
            latestCreatedAtMillis = 1_726_000_050L,
            groupOwnerAddress = "192.168.49.50",
            socketPort = null,
            socketReady = true
        )
        val decision = decision(
            candidates = listOf(candidate),
            selection = HybridBootstrapCandidateSelection.Selected(candidate)
        )

        val result = HybridBootstrapSocketEndpointResolver.resolve(decision)

        assertEquals(
            HybridBootstrapSocketEndpointResolution.InvalidSelectedCandidate(
                "Selected hybrid bootstrap candidate socketPort is missing."
            ),
            result
        )
    }

    @Test
    fun resolverDoesNotMutateDecision() {
        val candidate = candidate(
            peerId = "peer-stable",
            sessionId = "session-stable",
            latestCreatedAtMillis = 1_726_000_060L,
            socketReady = true
        )
        val decision = decision(
            candidates = listOf(candidate),
            selection = HybridBootstrapCandidateSelection.Selected(candidate)
        )
        val candidatesBefore = decision.candidates
        val selectionBefore = decision.selection

        val result = HybridBootstrapSocketEndpointResolver.resolve(decision)

        assertTrue(result is HybridBootstrapSocketEndpointResolution.Resolved)
        assertEquals(candidatesBefore, decision.candidates)
        assertEquals(selectionBefore, decision.selection)
    }

    @Test
    fun resolverIsPassiveAndDoesNotPerformTransportActions() {
        val candidate = candidate(
            peerId = "peer-passive",
            sessionId = "session-passive",
            latestCreatedAtMillis = 1_726_000_070L,
            socketReady = true
        )
        val decision = decision(
            candidates = listOf(candidate),
            selection = HybridBootstrapCandidateSelection.Selected(candidate)
        )

        val result = HybridBootstrapSocketEndpointResolver.resolve(decision)

        assertTrue(result is HybridBootstrapSocketEndpointResolution.Resolved)
    }

    private fun decision(
        candidates: List<HybridBootstrapCandidate>,
        selection: HybridBootstrapCandidateSelection
    ): HybridBootstrapDecision {
        return HybridBootstrapDecision.create(
            candidates = candidates,
            selection = selection
        )
    }

    private fun candidate(
        peerId: String,
        sessionId: String,
        latestCreatedAtMillis: Long,
        socketReady: Boolean,
        bootstrapIdentifier: String = sessionId,
        groupOwnerAddress: String? = if (socketReady) "192.168.49.1" else null,
        socketPort: Int? = if (socketReady) 8988 else null
    ): HybridBootstrapCandidate {
        return HybridBootstrapCandidate(
            peerId = peerId,
            sessionId = sessionId,
            bootstrapIdentifier = bootstrapIdentifier,
            publicPeerIdHint = "peer-hint",
            groupOwnerAddress = groupOwnerAddress,
            socketPort = socketPort,
            latestCreatedAtMillis = latestCreatedAtMillis,
            hasOffer = true,
            hasAccept = true,
            hasSocketHint = socketReady,
            socketReady = socketReady
        )
    }
}
