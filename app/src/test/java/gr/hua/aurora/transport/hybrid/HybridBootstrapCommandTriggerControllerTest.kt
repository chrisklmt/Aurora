package gr.hua.aurora.transport.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridBootstrapCommandTriggerControllerTest {
    @Test
    fun latestResultIsNullBeforeFirstTrigger() {
        val controller = HybridBootstrapCommandTriggerController(
            executor = FakeHybridBootstrapCommandExecutor()
        )

        assertNull(controller.latestResult)
        assertTrue(controller.triggerHistory.isEmpty())
    }

    @Test
    fun triggerWithBuiltReturnsExecutedResult() {
        val controller = HybridBootstrapCommandTriggerController(
            executor = FakeHybridBootstrapCommandExecutor()
        )

        val result = controller.trigger(
            buildResult = builtResult()
        )

        assertEquals(
            HybridBootstrapCommandTriggerResult.Executed(
                executionResult = HybridBootstrapCommandExecutionResult.Accepted(
                    peerId = "peer-ready",
                    sessionId = "session-ready",
                    bootstrapIdentifier = "bootstrap-ready",
                    groupOwnerAddress = "192.168.49.140",
                    socketPort = 9140,
                    commandCreatedAtMillis = 1_731_000_002L
                )
            ),
            result
        )
    }

    @Test
    fun triggerWithBuiltRecordsLatestResult() {
        val controller = HybridBootstrapCommandTriggerController(
            executor = FakeHybridBootstrapCommandExecutor()
        )
        val expected = HybridBootstrapCommandTriggerResult.Executed(
            executionResult = HybridBootstrapCommandExecutionResult.Accepted(
                peerId = "peer-ready",
                sessionId = "session-ready",
                bootstrapIdentifier = "bootstrap-ready",
                groupOwnerAddress = "192.168.49.140",
                socketPort = 9140,
                commandCreatedAtMillis = 1_731_000_002L
            )
        )

        controller.trigger(
            buildResult = builtResult()
        )

        assertEquals(expected, controller.latestResult)
    }

    @Test
    fun triggerWithBuiltRecordsResultInHistory() {
        val controller = HybridBootstrapCommandTriggerController(
            executor = FakeHybridBootstrapCommandExecutor()
        )
        val expected = HybridBootstrapCommandTriggerResult.Executed(
            executionResult = HybridBootstrapCommandExecutionResult.Accepted(
                peerId = "peer-ready",
                sessionId = "session-ready",
                bootstrapIdentifier = "bootstrap-ready",
                groupOwnerAddress = "192.168.49.140",
                socketPort = 9140,
                commandCreatedAtMillis = 1_731_000_002L
            )
        )

        controller.trigger(
            buildResult = builtResult()
        )

        assertEquals(listOf(expected), controller.triggerHistory)
    }

    @Test
    fun multipleTriggersRecordHistoryInOrder() {
        val controller = HybridBootstrapCommandTriggerController(
            executor = FakeHybridBootstrapCommandExecutor(
                shouldAccept = false,
                rejectionReason = "Configured controller rejection."
            )
        )

        val first = controller.trigger(
            buildResult = HybridBootstrapAttemptCommandBuildResult.NoCandidates
        )
        val second = controller.trigger(
            buildResult = builtResult()
        )
        val third = controller.trigger(
            buildResult = HybridBootstrapAttemptCommandBuildResult.InvalidEndpoint(
                reason = "Endpoint timestamp is in the future."
            )
        )

        assertEquals(
            listOf(first, second, third),
            controller.triggerHistory
        )
        assertEquals(third, controller.latestResult)
    }

    @Test
    fun triggerHistoryReturnsDefensiveCopy() {
        val controller = HybridBootstrapCommandTriggerController(
            executor = FakeHybridBootstrapCommandExecutor()
        )
        controller.trigger(
            buildResult = builtResult()
        )

        val firstRead = controller.triggerHistory
        val secondRead = controller.triggerHistory

        assertNotSame(firstRead, secondRead)
        assertEquals(firstRead, secondRead)
    }

    @Test
    fun builtTriggerCallsFakeExecutorExactlyOncePerBuiltCommand() {
        val executor = FakeHybridBootstrapCommandExecutor()
        val controller = HybridBootstrapCommandTriggerController(
            executor = executor
        )

        controller.trigger(buildResult = builtResult(peerId = "peer-built-1"))
        controller.trigger(buildResult = builtResult(peerId = "peer-built-2"))

        assertEquals(2, executor.executedCommands.size)
    }

    @Test
    fun nonBuiltTriggerDoesNotCallFakeExecutor() {
        val executor = FakeHybridBootstrapCommandExecutor()
        val controller = HybridBootstrapCommandTriggerController(
            executor = executor
        )

        controller.trigger(
            buildResult = HybridBootstrapAttemptCommandBuildResult.NoSocketReadyCandidate
        )

        assertTrue(executor.executedCommands.isEmpty())
    }

    @Test
    fun noCandidatesTriggerRecordsNoCandidates() {
        val controller = HybridBootstrapCommandTriggerController(
            executor = FakeHybridBootstrapCommandExecutor()
        )

        val result = controller.trigger(
            buildResult = HybridBootstrapAttemptCommandBuildResult.NoCandidates
        )

        assertEquals(HybridBootstrapCommandTriggerResult.NoCandidates, result)
        assertEquals(HybridBootstrapCommandTriggerResult.NoCandidates, controller.latestResult)
    }

    @Test
    fun noSocketReadyCandidateTriggerRecordsNoSocketReadyCandidate() {
        val controller = HybridBootstrapCommandTriggerController(
            executor = FakeHybridBootstrapCommandExecutor()
        )

        val result = controller.trigger(
            buildResult = HybridBootstrapAttemptCommandBuildResult.NoSocketReadyCandidate
        )

        assertEquals(HybridBootstrapCommandTriggerResult.NoSocketReadyCandidate, result)
        assertEquals(
            HybridBootstrapCommandTriggerResult.NoSocketReadyCandidate,
            controller.latestResult
        )
    }

    @Test
    fun invalidEndpointTriggerPreservesReason() {
        val controller = HybridBootstrapCommandTriggerController(
            executor = FakeHybridBootstrapCommandExecutor()
        )

        val result = controller.trigger(
            buildResult = HybridBootstrapAttemptCommandBuildResult.InvalidEndpoint(
                reason = "Endpoint timestamp is in the future."
            )
        )

        assertEquals(
            HybridBootstrapCommandTriggerResult.InvalidEndpoint(
                reason = "Endpoint timestamp is in the future."
            ),
            result
        )
    }

    @Test
    fun endpointTooOldTriggerPreservesAgeAndMax() {
        val controller = HybridBootstrapCommandTriggerController(
            executor = FakeHybridBootstrapCommandExecutor()
        )

        val result = controller.trigger(
            buildResult = HybridBootstrapAttemptCommandBuildResult.EndpointTooOld(
                ageMillis = 45_000L,
                maxAgeMillis = 30_000L
            )
        )

        assertEquals(
            HybridBootstrapCommandTriggerResult.EndpointTooOld(
                ageMillis = 45_000L,
                maxAgeMillis = 30_000L
            ),
            result
        )
    }

    @Test
    fun notAllowedTriggerPreservesReason() {
        val controller = HybridBootstrapCommandTriggerController(
            executor = FakeHybridBootstrapCommandExecutor()
        )

        val result = controller.trigger(
            buildResult = HybridBootstrapAttemptCommandBuildResult.NotAllowed(
                reason = "Command creation timestamp is before request timestamp."
            )
        )

        assertEquals(
            HybridBootstrapCommandTriggerResult.NotAllowed(
                reason = "Command creation timestamp is before request timestamp."
            ),
            result
        )
    }

    @Test
    fun controllerDoesNotMutateBuildResult() {
        val controller = HybridBootstrapCommandTriggerController(
            executor = FakeHybridBootstrapCommandExecutor()
        )
        val buildResult = builtResult(
            peerId = "peer-stable",
            sessionId = "session-stable",
            bootstrapIdentifier = "bootstrap-stable",
            groupOwnerAddress = "192.168.49.141",
            socketPort = 9141,
            latestCreatedAtMillis = 1_731_000_010L,
            requestedAtMillis = 1_731_000_011L,
            commandCreatedAtMillis = 1_731_000_012L
        )
        val before = buildResult.copy(
            command = buildResult.command.copy()
        )

        val result = controller.trigger(buildResult = buildResult)

        assertTrue(result is HybridBootstrapCommandTriggerResult.Executed)
        assertEquals(before, buildResult)
    }

    @Test
    fun controllerIsPassiveAndUsesFakeExecutorOnly() {
        val controller = HybridBootstrapCommandTriggerController(
            executor = FakeHybridBootstrapCommandExecutor()
        )

        val result = controller.trigger(buildResult = builtResult())

        assertTrue(result is HybridBootstrapCommandTriggerResult.Executed)
    }

    private fun builtResult(
        peerId: String = "peer-ready",
        sessionId: String = "session-ready",
        bootstrapIdentifier: String = "bootstrap-ready",
        groupOwnerAddress: String = "192.168.49.140",
        socketPort: Int = 9140,
        latestCreatedAtMillis: Long = 1_731_000_000L,
        requestedAtMillis: Long = 1_731_000_001L,
        commandCreatedAtMillis: Long = 1_731_000_002L
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
