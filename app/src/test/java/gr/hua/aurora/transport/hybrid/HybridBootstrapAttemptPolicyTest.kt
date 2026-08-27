package gr.hua.aurora.transport.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HybridBootstrapAttemptPolicyTest {
    @Test
    fun noCandidatesResolutionReturnsNoCandidatesAttemptDecision() {
        val result = HybridBootstrapAttemptPolicy.decide(
            resolution = HybridBootstrapSocketEndpointResolution.NoCandidates,
            requestedAtMillis = 1_727_000_001L,
            currentMonotonicMillis = 1_001L,
            maxEndpointAgeMillis = 30_000L
        )

        assertEquals(HybridBootstrapAttemptDecision.NoCandidates, result)
    }

    @Test
    fun noSocketReadyCandidateResolutionReturnsNoSocketReadyCandidate() {
        val result = HybridBootstrapAttemptPolicy.decide(
            resolution = HybridBootstrapSocketEndpointResolution.NoSocketReadyCandidate,
            requestedAtMillis = 1_727_000_002L,
            currentMonotonicMillis = 1_002L,
            maxEndpointAgeMillis = 30_000L
        )

        assertEquals(HybridBootstrapAttemptDecision.NoSocketReadyCandidate, result)
    }

    @Test
    fun invalidSelectedCandidateResolutionReturnsInvalidEndpointWithSameReason() {
        val result = HybridBootstrapAttemptPolicy.decide(
            resolution = HybridBootstrapSocketEndpointResolution.InvalidSelectedCandidate(
                "Selected hybrid bootstrap candidate socketPort is missing."
            ),
            requestedAtMillis = 1_727_000_003L,
            currentMonotonicMillis = 1_003L,
            maxEndpointAgeMillis = 30_000L
        )

        assertEquals(
            HybridBootstrapAttemptDecision.InvalidEndpoint(
                "Selected hybrid bootstrap candidate socketPort is missing."
            ),
            result
        )
    }

    @Test
    fun resolvedFreshEndpointReturnsAllowed() {
        val result = HybridBootstrapAttemptPolicy.decide(
            resolution = resolvedEndpoint(
                latestCreatedAtMillis = 1_727_000_010L,
                localSocketHintObservedAtMonotonicMillis = 1_990L
            ),
            requestedAtMillis = 1_727_000_020L,
            currentMonotonicMillis = 2_000L,
            maxEndpointAgeMillis = 30_000L
        )

        assertTrue(result is HybridBootstrapAttemptDecision.Allowed)
    }

    @Test
    fun allowedRequestPreservesPeerSessionBootstrapAddressPortLatestCreatedAtAndRequestedAtExactly() {
        val result = HybridBootstrapAttemptPolicy.decide(
            resolution = resolvedEndpoint(
                peerId = "peer/Alpha+01",
                sessionId = "session:Beta|02",
                bootstrapIdentifier = "bootstrap==Gamma/03",
                groupOwnerAddress = "fe80::1234",
                socketPort = 65535,
                latestCreatedAtMillis = 1_727_000_030L,
                localSocketHintObservedAtMonotonicMillis = 2_090L
            ),
            requestedAtMillis = 1_727_000_040L,
            currentMonotonicMillis = 2_100L,
            maxEndpointAgeMillis = 30_000L
        )

        assertEquals(
            HybridBootstrapAttemptDecision.Allowed(
                HybridBootstrapAttemptRequest(
                    peerId = "peer/Alpha+01",
                    sessionId = "session:Beta|02",
                    bootstrapIdentifier = "bootstrap==Gamma/03",
                    groupOwnerAddress = "fe80::1234",
                    socketPort = 65535,
                    latestCreatedAtMillis = 1_727_000_030L,
                    requestedAtMillis = 1_727_000_040L
                )
            ),
            result
        )
    }

    @Test
    fun endpointOlderThanMaxAgeReturnsEndpointTooOldWithExactAgeAndMax() {
        val result = HybridBootstrapAttemptPolicy.decide(
            resolution = resolvedEndpoint(
                latestCreatedAtMillis = 1_727_000_050L,
                localSocketHintObservedAtMonotonicMillis = 100L
            ),
            requestedAtMillis = 1_727_030_051L,
            currentMonotonicMillis = 30_101L,
            maxEndpointAgeMillis = 30_000L
        )

        assertEquals(
            HybridBootstrapAttemptDecision.EndpointTooOld(
                ageMillis = 30_001L,
                maxAgeMillis = 30_000L
            ),
            result
        )
    }

    @Test
    fun missingLocalSocketHintObservationReturnsInvalidEndpoint() {
        val result = HybridBootstrapAttemptPolicy.decide(
            resolution = resolvedEndpoint(
                latestCreatedAtMillis = 1_727_000_070L,
                localSocketHintObservedAtMonotonicMillis = null
            ),
            requestedAtMillis = 1_727_000_069L,
            currentMonotonicMillis = 2_200L,
            maxEndpointAgeMillis = 30_000L
        )

        assertEquals(
            HybridBootstrapAttemptDecision.InvalidEndpoint(
                "Socket hint has not been observed locally."
            ),
            result
        )
    }

    @Test
    fun socketHintObservationTimestampInTheFutureReturnsInvalidEndpoint() {
        val result = HybridBootstrapAttemptPolicy.decide(
            resolution = resolvedEndpoint(
                latestCreatedAtMillis = 1_727_000_080L,
                localSocketHintObservedAtMonotonicMillis = 2_301L
            ),
            requestedAtMillis = 1_727_000_081L,
            currentMonotonicMillis = 2_300L,
            maxEndpointAgeMillis = 30_000L
        )

        assertEquals(
            HybridBootstrapAttemptDecision.InvalidEndpoint(
                "Socket hint observation timestamp is in the future."
            ),
            result
        )
    }

    @Test
    fun remoteEndpointCreatedAtMayBeAheadOfLocalWallClockWhenLocalObservationMatches() {
        val result = HybridBootstrapAttemptPolicy.decide(
            resolution = resolvedEndpoint(
                latestCreatedAtMillis = 1_727_120_000L,
                localSocketHintObservedAtMonotonicMillis = 2_390L
            ),
            requestedAtMillis = 1_727_000_100L,
            currentMonotonicMillis = 2_400L,
            maxEndpointAgeMillis = 30_000L
        )

        assertTrue(result is HybridBootstrapAttemptDecision.Allowed)
    }

    @Test
    fun negativeRequestedAtMillisReturnsInvalidEndpoint() {
        val result = HybridBootstrapAttemptPolicy.decide(
            resolution = resolvedEndpoint(
                latestCreatedAtMillis = 1_727_000_080L,
                localSocketHintObservedAtMonotonicMillis = 2_490L
            ),
            requestedAtMillis = -1L,
            currentMonotonicMillis = 2_500L,
            maxEndpointAgeMillis = 30_000L
        )

        assertEquals(
            HybridBootstrapAttemptDecision.InvalidEndpoint(
                "Requested at millis must be non-negative."
            ),
            result
        )
    }

    @Test
    fun negativeCurrentMonotonicMillisReturnsInvalidEndpoint() {
        val result = HybridBootstrapAttemptPolicy.decide(
            resolution = resolvedEndpoint(
                latestCreatedAtMillis = 1_727_000_090L,
                localSocketHintObservedAtMonotonicMillis = 2_590L
            ),
            requestedAtMillis = 1_727_000_100L,
            currentMonotonicMillis = -1L,
            maxEndpointAgeMillis = 30_000L
        )

        assertEquals(
            HybridBootstrapAttemptDecision.InvalidEndpoint(
                "Current monotonic millis must be non-negative."
            ),
            result
        )
    }

    @Test
    fun negativeMaxEndpointAgeMillisReturnsInvalidEndpoint() {
        val result = HybridBootstrapAttemptPolicy.decide(
            resolution = resolvedEndpoint(
                latestCreatedAtMillis = 1_727_000_090L,
                localSocketHintObservedAtMonotonicMillis = 2_590L
            ),
            requestedAtMillis = 1_727_000_100L,
            currentMonotonicMillis = 2_600L,
            maxEndpointAgeMillis = -1L
        )

        assertEquals(
            HybridBootstrapAttemptDecision.InvalidEndpoint(
                "Max endpoint age millis must be non-negative."
            ),
            result
        )
    }

    @Test
    fun attemptRequestValidationRejectsBlankPeerId() {
        assertValidationFailure(
            "Hybrid bootstrap attempt request peerId must not be blank."
        ) {
            request(peerId = " ")
        }
    }

    @Test
    fun attemptRequestValidationRejectsBlankSessionId() {
        assertValidationFailure(
            "Hybrid bootstrap attempt request sessionId must not be blank."
        ) {
            request(sessionId = " ")
        }
    }

    @Test
    fun attemptRequestValidationRejectsBlankBootstrapIdentifier() {
        assertValidationFailure(
            "Hybrid bootstrap attempt request bootstrapIdentifier must not be blank."
        ) {
            request(bootstrapIdentifier = " ")
        }
    }

    @Test
    fun attemptRequestValidationRejectsBlankGroupOwnerAddress() {
        assertValidationFailure(
            "Hybrid bootstrap attempt request groupOwnerAddress must not be blank."
        ) {
            request(groupOwnerAddress = " ")
        }
    }

    @Test
    fun attemptRequestValidationRejectsPortZero() {
        assertValidationFailure(
            "Hybrid bootstrap attempt request socketPort must be in 1..65535."
        ) {
            request(socketPort = 0)
        }
    }

    @Test
    fun attemptRequestValidationRejectsPort65536() {
        assertValidationFailure(
            "Hybrid bootstrap attempt request socketPort must be in 1..65535."
        ) {
            request(socketPort = 65_536)
        }
    }

    @Test
    fun attemptRequestValidationRejectsNegativeLatestCreatedAtMillis() {
        assertValidationFailure(
            "Hybrid bootstrap attempt request latestCreatedAtMillis must be non-negative."
        ) {
            request(latestCreatedAtMillis = -1L)
        }
    }

    @Test
    fun policyDoesNotMutateEndpointResolution() {
        val resolution = resolvedEndpoint(
            peerId = "peer-stable",
            sessionId = "session-stable",
            bootstrapIdentifier = "bootstrap-stable",
            groupOwnerAddress = "192.168.49.90",
            socketPort = 9090,
            latestCreatedAtMillis = 1_727_000_130L,
            localSocketHintObservedAtMonotonicMillis = 2_690L
        )
        val before = resolution.copy(
            endpoint = resolution.endpoint.copy()
        )

        val result = HybridBootstrapAttemptPolicy.decide(
            resolution = resolution,
            requestedAtMillis = 1_727_000_131L,
            currentMonotonicMillis = 2_700L,
            maxEndpointAgeMillis = 30_000L
        )

        assertTrue(result is HybridBootstrapAttemptDecision.Allowed)
        assertEquals(before, resolution)
    }

    @Test
    fun policyIsPassiveAndDoesNotPerformTransportOrSocketActions() {
        val result = HybridBootstrapAttemptPolicy.decide(
            resolution = resolvedEndpoint(
                latestCreatedAtMillis = 1_727_000_140L,
                localSocketHintObservedAtMonotonicMillis = 2_790L
            ),
            requestedAtMillis = 1_727_000_141L,
            currentMonotonicMillis = 2_800L,
            maxEndpointAgeMillis = 30_000L
        )

        assertTrue(result is HybridBootstrapAttemptDecision.Allowed)
    }

    private fun resolvedEndpoint(
        peerId: String = "peer-ready",
        sessionId: String = "session-ready",
        bootstrapIdentifier: String = sessionId,
        groupOwnerAddress: String = "192.168.49.1",
        socketPort: Int = 8988,
        latestCreatedAtMillis: Long,
        localSocketHintObservedAtMonotonicMillis: Long? = 1_000L
    ): HybridBootstrapSocketEndpointResolution.Resolved {
        return HybridBootstrapSocketEndpointResolution.Resolved(
            HybridBootstrapSocketEndpoint(
                peerId = peerId,
                sessionId = sessionId,
                bootstrapIdentifier = bootstrapIdentifier,
                groupOwnerAddress = groupOwnerAddress,
                socketPort = socketPort,
                latestCreatedAtMillis = latestCreatedAtMillis,
                localSocketHintObservedAtMonotonicMillis =
                    localSocketHintObservedAtMonotonicMillis
            )
        )
    }

    private fun request(
        peerId: String = "peer-request",
        sessionId: String = "session-request",
        bootstrapIdentifier: String = "bootstrap-request",
        groupOwnerAddress: String = "192.168.49.2",
        socketPort: Int = 8989,
        latestCreatedAtMillis: Long = 1_727_000_110L,
        requestedAtMillis: Long = 1_727_000_111L
    ): HybridBootstrapAttemptRequest {
        return HybridBootstrapAttemptRequest(
            peerId = peerId,
            sessionId = sessionId,
            bootstrapIdentifier = bootstrapIdentifier,
            groupOwnerAddress = groupOwnerAddress,
            socketPort = socketPort,
            latestCreatedAtMillis = latestCreatedAtMillis,
            requestedAtMillis = requestedAtMillis
        )
    }

    private fun assertValidationFailure(
        expectedMessage: String,
        block: () -> Unit
    ) {
        try {
            block()
            fail("Expected IllegalArgumentException with message: $expectedMessage")
        } catch (error: IllegalArgumentException) {
            assertEquals(expectedMessage, error.message)
        }
    }
}
