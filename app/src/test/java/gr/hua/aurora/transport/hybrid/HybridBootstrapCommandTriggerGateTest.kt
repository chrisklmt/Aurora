package gr.hua.aurora.transport.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridBootstrapCommandTriggerGateTest {
    @Test
    fun builtCommandCallsFakeExecutorExactlyOnce() {
        val executor = FakeHybridBootstrapCommandExecutor()

        val result = HybridBootstrapCommandTriggerGate.trigger(
            buildResult = builtResult(),
            executor = executor
        )

        assertTrue(result is HybridBootstrapCommandTriggerResult.Executed)
        assertEquals(1, executor.executedCommands.size)
    }

    @Test
    fun builtCommandRecordsExactCommandInFakeExecutor() {
        val executor = FakeHybridBootstrapCommandExecutor()
        val buildResult = builtResult(
            peerId = "peer-recorded",
            sessionId = "session-recorded",
            bootstrapIdentifier = "bootstrap-recorded",
            groupOwnerAddress = "192.168.49.120",
            socketPort = 9120,
            latestCreatedAtMillis = 1_730_000_010L,
            requestedAtMillis = 1_730_000_011L,
            commandCreatedAtMillis = 1_730_000_012L
        )

        HybridBootstrapCommandTriggerGate.trigger(
            buildResult = buildResult,
            executor = executor
        )

        assertEquals(
            listOf(
                HybridBootstrapAttemptCommand(
                    peerId = "peer-recorded",
                    sessionId = "session-recorded",
                    bootstrapIdentifier = "bootstrap-recorded",
                    groupOwnerAddress = "192.168.49.120",
                    socketPort = 9120,
                    latestCreatedAtMillis = 1_730_000_010L,
                    requestedAtMillis = 1_730_000_011L,
                    commandCreatedAtMillis = 1_730_000_012L
                )
            ),
            executor.executedCommands
        )
    }

    @Test
    fun builtCommandReturnsExecutedAcceptedWhenFakeExecutorAccepts() {
        val executor = FakeHybridBootstrapCommandExecutor()

        val result = HybridBootstrapCommandTriggerGate.trigger(
            buildResult = builtResult(),
            executor = executor
        )

        assertEquals(
            HybridBootstrapCommandTriggerResult.Executed(
                executionResult = HybridBootstrapCommandExecutionResult.Accepted(
                    peerId = "peer-ready",
                    sessionId = "session-ready",
                    bootstrapIdentifier = "bootstrap-ready",
                    groupOwnerAddress = "192.168.49.110",
                    socketPort = 9110,
                    commandCreatedAtMillis = 1_730_000_002L
                )
            ),
            result
        )
    }

    @Test
    fun builtCommandReturnsExecutedRejectedWhenFakeExecutorRejects() {
        val executor = FakeHybridBootstrapCommandExecutor(
            shouldAccept = false,
            rejectionReason = "Configured fake trigger rejection."
        )

        val result = HybridBootstrapCommandTriggerGate.trigger(
            buildResult = builtResult(),
            executor = executor
        )

        assertEquals(
            HybridBootstrapCommandTriggerResult.Executed(
                executionResult = HybridBootstrapCommandExecutionResult.Rejected(
                    reason = "Configured fake trigger rejection."
                )
            ),
            result
        )
    }

    @Test
    fun noCandidatesBuildResultDoesNotCallExecutorAndMapsToNoCandidates() {
        val executor = FakeHybridBootstrapCommandExecutor()

        val result = HybridBootstrapCommandTriggerGate.trigger(
            buildResult = HybridBootstrapAttemptCommandBuildResult.NoCandidates,
            executor = executor
        )

        assertEquals(HybridBootstrapCommandTriggerResult.NoCandidates, result)
        assertTrue(executor.executedCommands.isEmpty())
    }

    @Test
    fun noSocketReadyCandidateBuildResultDoesNotCallExecutorAndMapsToNoSocketReadyCandidate() {
        val executor = FakeHybridBootstrapCommandExecutor()

        val result = HybridBootstrapCommandTriggerGate.trigger(
            buildResult = HybridBootstrapAttemptCommandBuildResult.NoSocketReadyCandidate,
            executor = executor
        )

        assertEquals(HybridBootstrapCommandTriggerResult.NoSocketReadyCandidate, result)
        assertTrue(executor.executedCommands.isEmpty())
    }

    @Test
    fun invalidEndpointBuildResultDoesNotCallExecutorAndPreservesReason() {
        val executor = FakeHybridBootstrapCommandExecutor()

        val result = HybridBootstrapCommandTriggerGate.trigger(
            buildResult = HybridBootstrapAttemptCommandBuildResult.InvalidEndpoint(
                reason = "Endpoint timestamp is in the future."
            ),
            executor = executor
        )

        assertEquals(
            HybridBootstrapCommandTriggerResult.InvalidEndpoint(
                reason = "Endpoint timestamp is in the future."
            ),
            result
        )
        assertTrue(executor.executedCommands.isEmpty())
    }

    @Test
    fun endpointTooOldBuildResultDoesNotCallExecutorAndPreservesAgeAndMax() {
        val executor = FakeHybridBootstrapCommandExecutor()

        val result = HybridBootstrapCommandTriggerGate.trigger(
            buildResult = HybridBootstrapAttemptCommandBuildResult.EndpointTooOld(
                ageMillis = 45_000L,
                maxAgeMillis = 30_000L
            ),
            executor = executor
        )

        assertEquals(
            HybridBootstrapCommandTriggerResult.EndpointTooOld(
                ageMillis = 45_000L,
                maxAgeMillis = 30_000L
            ),
            result
        )
        assertTrue(executor.executedCommands.isEmpty())
    }

    @Test
    fun notAllowedBuildResultDoesNotCallExecutorAndPreservesReason() {
        val executor = FakeHybridBootstrapCommandExecutor()

        val result = HybridBootstrapCommandTriggerGate.trigger(
            buildResult = HybridBootstrapAttemptCommandBuildResult.NotAllowed(
                reason = "Command creation timestamp is before request timestamp."
            ),
            executor = executor
        )

        assertEquals(
            HybridBootstrapCommandTriggerResult.NotAllowed(
                reason = "Command creation timestamp is before request timestamp."
            ),
            result
        )
        assertTrue(executor.executedCommands.isEmpty())
    }

    @Test
    fun triggerGateDoesNotMutateBuildResult() {
        val buildResult = builtResult(
            peerId = "peer-stable",
            sessionId = "session-stable",
            bootstrapIdentifier = "bootstrap-stable",
            groupOwnerAddress = "192.168.49.130",
            socketPort = 9130,
            latestCreatedAtMillis = 1_730_000_020L,
            requestedAtMillis = 1_730_000_021L,
            commandCreatedAtMillis = 1_730_000_022L
        )
        val before = buildResult.copy(
            command = buildResult.command.copy()
        )

        val result = HybridBootstrapCommandTriggerGate.trigger(
            buildResult = buildResult,
            executor = FakeHybridBootstrapCommandExecutor()
        )

        assertTrue(result is HybridBootstrapCommandTriggerResult.Executed)
        assertEquals(before, buildResult)
    }

    @Test
    fun triggerGateIsPassiveAndUsesOnlyFakeExecutor() {
        val result = HybridBootstrapCommandTriggerGate.trigger(
            buildResult = builtResult(),
            executor = FakeHybridBootstrapCommandExecutor()
        )

        assertTrue(result is HybridBootstrapCommandTriggerResult.Executed)
    }

    private fun builtResult(
        peerId: String = "peer-ready",
        sessionId: String = "session-ready",
        bootstrapIdentifier: String = "bootstrap-ready",
        groupOwnerAddress: String = "192.168.49.110",
        socketPort: Int = 9110,
        latestCreatedAtMillis: Long = 1_730_000_000L,
        requestedAtMillis: Long = 1_730_000_001L,
        commandCreatedAtMillis: Long = 1_730_000_002L
    ): HybridBootstrapAttemptCommandBuildResult.Built {
        return HybridBootstrapAttemptCommandBuildResult.Built(
            command = HybridBootstrapAttemptCommand(
                peerId = peerId,
                sessionId = sessionId,
                bootstrapIdentifier = bootstrapIdentifier,
                groupOwnerAddress = groupOwnerAddress,
                socketPort = socketPort,
                latestCreatedAtMillis = latestCreatedAtMillis,
                requestedAtMillis = requestedAtMillis,
                commandCreatedAtMillis = commandCreatedAtMillis
            )
        )
    }
}
