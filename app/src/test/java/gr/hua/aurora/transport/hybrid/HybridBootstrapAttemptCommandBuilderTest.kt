package gr.hua.aurora.transport.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HybridBootstrapAttemptCommandBuilderTest {
    @Test
    fun allowedAttemptDecisionBuildsCommand() {
        val result = HybridBootstrapAttemptCommandBuilder.build(
            decision = allowedDecision(),
            commandCreatedAtMillis = 1_728_000_002L
        )

        assertTrue(result is HybridBootstrapAttemptCommandBuildResult.Built)
    }

    @Test
    fun builtCommandPreservesPeerSessionBootstrapAddressPortLatestCreatedAtRequestedAtAndCommandCreatedAtExactly() {
        val result = HybridBootstrapAttemptCommandBuilder.build(
            decision = allowedDecision(
                peerId = "peer/Alpha+01",
                sessionId = "session:Beta|02",
                bootstrapIdentifier = "bootstrap==Gamma/03",
                groupOwnerAddress = "fe80::1234",
                socketPort = 65535,
                latestCreatedAtMillis = 1_728_000_010L,
                requestedAtMillis = 1_728_000_011L
            ),
            commandCreatedAtMillis = 1_728_000_012L
        )

        assertEquals(
            HybridBootstrapAttemptCommandBuildResult.Built(
                command = HybridBootstrapAttemptCommand(
                    peerId = "peer/Alpha+01",
                    sessionId = "session:Beta|02",
                    bootstrapIdentifier = "bootstrap==Gamma/03",
                    groupOwnerAddress = "fe80::1234",
                    socketPort = 65535,
                    latestCreatedAtMillis = 1_728_000_010L,
                    requestedAtMillis = 1_728_000_011L,
                    commandCreatedAtMillis = 1_728_000_012L
                )
            ),
            result
        )
    }

    @Test
    fun noCandidatesDecisionMapsToNoCandidatesBuildResult() {
        val result = HybridBootstrapAttemptCommandBuilder.build(
            decision = HybridBootstrapAttemptDecision.NoCandidates,
            commandCreatedAtMillis = 1_728_000_020L
        )

        assertEquals(
            HybridBootstrapAttemptCommandBuildResult.NoCandidates,
            result
        )
    }

    @Test
    fun noSocketReadyCandidateDecisionMapsToNoSocketReadyCandidateBuildResult() {
        val result = HybridBootstrapAttemptCommandBuilder.build(
            decision = HybridBootstrapAttemptDecision.NoSocketReadyCandidate,
            commandCreatedAtMillis = 1_728_000_021L
        )

        assertEquals(
            HybridBootstrapAttemptCommandBuildResult.NoSocketReadyCandidate,
            result
        )
    }

    @Test
    fun invalidEndpointDecisionMapsToInvalidEndpointWithSameReason() {
        val result = HybridBootstrapAttemptCommandBuilder.build(
            decision = HybridBootstrapAttemptDecision.InvalidEndpoint(
                reason = "Endpoint timestamp is in the future."
            ),
            commandCreatedAtMillis = 1_728_000_022L
        )

        assertEquals(
            HybridBootstrapAttemptCommandBuildResult.InvalidEndpoint(
                reason = "Endpoint timestamp is in the future."
            ),
            result
        )
    }

    @Test
    fun endpointTooOldDecisionMapsToEndpointTooOldWithExactAgeAndMax() {
        val result = HybridBootstrapAttemptCommandBuilder.build(
            decision = HybridBootstrapAttemptDecision.EndpointTooOld(
                ageMillis = 45_000L,
                maxAgeMillis = 30_000L
            ),
            commandCreatedAtMillis = 1_728_000_023L
        )

        assertEquals(
            HybridBootstrapAttemptCommandBuildResult.EndpointTooOld(
                ageMillis = 45_000L,
                maxAgeMillis = 30_000L
            ),
            result
        )
    }

    @Test
    fun negativeCommandCreatedAtMillisReturnsNotAllowed() {
        val result = HybridBootstrapAttemptCommandBuilder.build(
            decision = allowedDecision(),
            commandCreatedAtMillis = -1L
        )

        assertEquals(
            HybridBootstrapAttemptCommandBuildResult.NotAllowed(
                reason = "Command creation timestamp is negative."
            ),
            result
        )
    }

    @Test
    fun commandCreatedAtMillisBeforeRequestedAtMillisReturnsNotAllowed() {
        val result = HybridBootstrapAttemptCommandBuilder.build(
            decision = allowedDecision(requestedAtMillis = 1_728_000_030L),
            commandCreatedAtMillis = 1_728_000_029L
        )

        assertEquals(
            HybridBootstrapAttemptCommandBuildResult.NotAllowed(
                reason = "Command creation timestamp is before request timestamp."
            ),
            result
        )
    }

    @Test
    fun commandValidationRejectsBlankPeerId() {
        assertValidationFailure(
            "Hybrid bootstrap attempt command peerId must not be blank."
        ) {
            command(peerId = " ")
        }
    }

    @Test
    fun commandValidationRejectsBlankSessionId() {
        assertValidationFailure(
            "Hybrid bootstrap attempt command sessionId must not be blank."
        ) {
            command(sessionId = " ")
        }
    }

    @Test
    fun commandValidationRejectsBlankBootstrapIdentifier() {
        assertValidationFailure(
            "Hybrid bootstrap attempt command bootstrapIdentifier must not be blank."
        ) {
            command(bootstrapIdentifier = " ")
        }
    }

    @Test
    fun commandValidationRejectsBlankGroupOwnerAddress() {
        assertValidationFailure(
            "Hybrid bootstrap attempt command groupOwnerAddress must not be blank."
        ) {
            command(groupOwnerAddress = " ")
        }
    }

    @Test
    fun commandValidationRejectsPortZero() {
        assertValidationFailure(
            "Hybrid bootstrap attempt command socketPort must be in 1..65535."
        ) {
            command(socketPort = 0)
        }
    }

    @Test
    fun commandValidationRejectsPort65536() {
        assertValidationFailure(
            "Hybrid bootstrap attempt command socketPort must be in 1..65535."
        ) {
            command(socketPort = 65_536)
        }
    }

    @Test
    fun commandValidationRejectsNegativeLatestCreatedAtMillis() {
        assertValidationFailure(
            "Hybrid bootstrap attempt command latestCreatedAtMillis must be non-negative."
        ) {
            command(latestCreatedAtMillis = -1L)
        }
    }

    @Test
    fun commandValidationRejectsCommandCreatedAtMillisBeforeRequestedAtMillis() {
        assertValidationFailure(
            "Hybrid bootstrap attempt command commandCreatedAtMillis must be greater than or equal to requestedAtMillis."
        ) {
            command(
                latestCreatedAtMillis = 1_728_000_049L,
                requestedAtMillis = 1_728_000_050L,
                commandCreatedAtMillis = 1_728_000_049L
            )
        }
    }

    @Test
    fun builderDoesNotMutateAttemptDecision() {
        val decision = allowedDecision(
            peerId = "peer-stable",
            sessionId = "session-stable",
            bootstrapIdentifier = "bootstrap-stable",
            groupOwnerAddress = "192.168.49.90",
            socketPort = 9090,
            latestCreatedAtMillis = 1_728_000_060L,
            requestedAtMillis = 1_728_000_061L
        )
        val before = decision.copy(
            request = decision.request.copy()
        )

        val result = HybridBootstrapAttemptCommandBuilder.build(
            decision = decision,
            commandCreatedAtMillis = 1_728_000_062L
        )

        assertTrue(result is HybridBootstrapAttemptCommandBuildResult.Built)
        assertEquals(before, decision)
    }

    @Test
    fun builderIsPassiveAndDoesNotPerformTransportOrSocketActions() {
        val result = HybridBootstrapAttemptCommandBuilder.build(
            decision = allowedDecision(),
            commandCreatedAtMillis = 1_728_000_070L
        )

        assertTrue(result is HybridBootstrapAttemptCommandBuildResult.Built)
    }

    private fun allowedDecision(
        peerId: String = "peer-ready",
        sessionId: String = "session-ready",
        bootstrapIdentifier: String = sessionId,
        groupOwnerAddress: String = "192.168.49.1",
        socketPort: Int = 8988,
        latestCreatedAtMillis: Long = 1_728_000_000L,
        requestedAtMillis: Long = 1_728_000_001L
    ): HybridBootstrapAttemptDecision.Allowed {
        return HybridBootstrapAttemptDecision.Allowed(
            request = HybridBootstrapAttemptRequest(
                peerId = peerId,
                sessionId = sessionId,
                bootstrapIdentifier = bootstrapIdentifier,
                groupOwnerAddress = groupOwnerAddress,
                socketPort = socketPort,
                latestCreatedAtMillis = latestCreatedAtMillis,
                requestedAtMillis = requestedAtMillis
            )
        )
    }

    private fun command(
        peerId: String = "peer-command",
        sessionId: String = "session-command",
        bootstrapIdentifier: String = "bootstrap-command",
        groupOwnerAddress: String = "192.168.49.2",
        socketPort: Int = 8989,
        latestCreatedAtMillis: Long = 1_728_000_080L,
        requestedAtMillis: Long = 1_728_000_081L,
        commandCreatedAtMillis: Long = 1_728_000_082L
    ): HybridBootstrapAttemptCommand {
        return HybridBootstrapAttemptCommand(
            peerId = peerId,
            sessionId = sessionId,
            bootstrapIdentifier = bootstrapIdentifier,
            groupOwnerAddress = groupOwnerAddress,
            socketPort = socketPort,
            latestCreatedAtMillis = latestCreatedAtMillis,
            requestedAtMillis = requestedAtMillis,
            commandCreatedAtMillis = commandCreatedAtMillis
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
