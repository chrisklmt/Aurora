package gr.hua.aurora.transport.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridBootstrapManualTriggerSnapshotFormatterTest {
    @Test
    fun builtCommandBuildResultSetsCanTriggerNowTrue() {
        val snapshot = HybridBootstrapManualTriggerSnapshotFormatter.format(
            commandBuildResult = builtCommandBuildResult(),
            latestTriggerResult = null
        )

        assertTrue(snapshot.canTriggerNow)
    }

    @Test
    fun noCandidatesSetsCanTriggerNowFalse() {
        val snapshot = HybridBootstrapManualTriggerSnapshotFormatter.format(
            commandBuildResult = HybridBootstrapAttemptCommandBuildResult.NoCandidates,
            latestTriggerResult = null
        )

        assertFalse(snapshot.canTriggerNow)
    }

    @Test
    fun noSocketReadyCandidateSetsCanTriggerNowFalse() {
        val snapshot = HybridBootstrapManualTriggerSnapshotFormatter.format(
            commandBuildResult = HybridBootstrapAttemptCommandBuildResult.NoSocketReadyCandidate,
            latestTriggerResult = null
        )

        assertFalse(snapshot.canTriggerNow)
    }

    @Test
    fun invalidEndpointSetsCanTriggerNowFalse() {
        val snapshot = HybridBootstrapManualTriggerSnapshotFormatter.format(
            commandBuildResult = HybridBootstrapAttemptCommandBuildResult.InvalidEndpoint(
                reason = "Endpoint timestamp is in the future."
            ),
            latestTriggerResult = null
        )

        assertFalse(snapshot.canTriggerNow)
    }

    @Test
    fun endpointTooOldSetsCanTriggerNowFalse() {
        val snapshot = HybridBootstrapManualTriggerSnapshotFormatter.format(
            commandBuildResult = HybridBootstrapAttemptCommandBuildResult.EndpointTooOld(
                ageMillis = 45_000L,
                maxAgeMillis = 30_000L
            ),
            latestTriggerResult = null
        )

        assertFalse(snapshot.canTriggerNow)
    }

    @Test
    fun notAllowedSetsCanTriggerNowFalse() {
        val snapshot = HybridBootstrapManualTriggerSnapshotFormatter.format(
            commandBuildResult = HybridBootstrapAttemptCommandBuildResult.NotAllowed(
                reason = "Command creation timestamp is before request timestamp."
            ),
            latestTriggerResult = null
        )

        assertFalse(snapshot.canTriggerNow)
    }

    @Test
    fun commandStatusTextForBuiltPreservesPeerSessionAddressAndPort() {
        val snapshot = HybridBootstrapManualTriggerSnapshotFormatter.format(
            commandBuildResult = builtCommandBuildResult(),
            latestTriggerResult = null
        )

        assertEquals(
            "Hybrid bootstrap command: built peer=peer-built session=session-built address=192.168.49.90 port=9090",
            snapshot.commandStatusText
        )
    }

    @Test
    fun commandStatusTextForNoCandidatesIsStable() {
        val snapshot = HybridBootstrapManualTriggerSnapshotFormatter.format(
            commandBuildResult = HybridBootstrapAttemptCommandBuildResult.NoCandidates,
            latestTriggerResult = null
        )

        assertEquals("Hybrid bootstrap command: no candidates", snapshot.commandStatusText)
    }

    @Test
    fun commandStatusTextForNoSocketReadyCandidateIsStable() {
        val snapshot = HybridBootstrapManualTriggerSnapshotFormatter.format(
            commandBuildResult = HybridBootstrapAttemptCommandBuildResult.NoSocketReadyCandidate,
            latestTriggerResult = null
        )

        assertEquals(
            "Hybrid bootstrap command: no socket-ready candidate",
            snapshot.commandStatusText
        )
    }

    @Test
    fun commandStatusTextForInvalidEndpointPreservesReason() {
        val snapshot = HybridBootstrapManualTriggerSnapshotFormatter.format(
            commandBuildResult = HybridBootstrapAttemptCommandBuildResult.InvalidEndpoint(
                reason = "Endpoint timestamp is in the future."
            ),
            latestTriggerResult = null
        )

        assertEquals(
            "Hybrid bootstrap command: invalid endpoint: Endpoint timestamp is in the future.",
            snapshot.commandStatusText
        )
    }

    @Test
    fun commandStatusTextForEndpointTooOldPreservesAgeAndMax() {
        val snapshot = HybridBootstrapManualTriggerSnapshotFormatter.format(
            commandBuildResult = HybridBootstrapAttemptCommandBuildResult.EndpointTooOld(
                ageMillis = 45_000L,
                maxAgeMillis = 30_000L
            ),
            latestTriggerResult = null
        )

        assertEquals(
            "Hybrid bootstrap command: endpoint too old age=45000 max=30000",
            snapshot.commandStatusText
        )
    }

    @Test
    fun commandStatusTextForNotAllowedPreservesReason() {
        val snapshot = HybridBootstrapManualTriggerSnapshotFormatter.format(
            commandBuildResult = HybridBootstrapAttemptCommandBuildResult.NotAllowed(
                reason = "Command creation timestamp is before request timestamp."
            ),
            latestTriggerResult = null
        )

        assertEquals(
            "Hybrid bootstrap command: not allowed: Command creation timestamp is before request timestamp.",
            snapshot.commandStatusText
        )
    }

    @Test
    fun nullLatestTriggerResultGivesNullTriggerStatusText() {
        val snapshot = HybridBootstrapManualTriggerSnapshotFormatter.format(
            commandBuildResult = builtCommandBuildResult(),
            latestTriggerResult = null
        )

        assertEquals(null, snapshot.triggerStatusText)
    }

    @Test
    fun executedAcceptedTriggerResultStatusPreservesPeerSessionAddressAndPort() {
        val snapshot = HybridBootstrapManualTriggerSnapshotFormatter.format(
            commandBuildResult = builtCommandBuildResult(),
            latestTriggerResult = acceptedTriggerResult()
        )

        assertEquals(
            "Hybrid bootstrap trigger: accepted peer=peer-trigger session=session-trigger address=192.168.49.91 port=9091",
            snapshot.triggerStatusText
        )
    }

    @Test
    fun executedRejectedTriggerResultStatusPreservesReason() {
        val snapshot = HybridBootstrapManualTriggerSnapshotFormatter.format(
            commandBuildResult = builtCommandBuildResult(),
            latestTriggerResult = HybridBootstrapCommandTriggerResult.Executed(
                HybridBootstrapCommandExecutionResult.Rejected(
                    reason = "Hybrid bootstrap execution is disabled."
                )
            )
        )

        assertEquals(
            "Hybrid bootstrap trigger: rejected: Hybrid bootstrap execution is disabled.",
            snapshot.triggerStatusText
        )
    }

    @Test
    fun noCandidatesTriggerResultStatusIsStable() {
        val snapshot = HybridBootstrapManualTriggerSnapshotFormatter.format(
            commandBuildResult = builtCommandBuildResult(),
            latestTriggerResult = HybridBootstrapCommandTriggerResult.NoCandidates
        )

        assertEquals("Hybrid bootstrap trigger: no candidates", snapshot.triggerStatusText)
    }

    @Test
    fun noSocketReadyCandidateTriggerResultStatusIsStable() {
        val snapshot = HybridBootstrapManualTriggerSnapshotFormatter.format(
            commandBuildResult = builtCommandBuildResult(),
            latestTriggerResult = HybridBootstrapCommandTriggerResult.NoSocketReadyCandidate
        )

        assertEquals(
            "Hybrid bootstrap trigger: no socket-ready candidate",
            snapshot.triggerStatusText
        )
    }

    @Test
    fun invalidEndpointTriggerResultStatusPreservesReason() {
        val snapshot = HybridBootstrapManualTriggerSnapshotFormatter.format(
            commandBuildResult = builtCommandBuildResult(),
            latestTriggerResult = HybridBootstrapCommandTriggerResult.InvalidEndpoint(
                reason = "Endpoint timestamp is in the future."
            )
        )

        assertEquals(
            "Hybrid bootstrap trigger: invalid endpoint: Endpoint timestamp is in the future.",
            snapshot.triggerStatusText
        )
    }

    @Test
    fun endpointTooOldTriggerResultStatusPreservesAgeAndMax() {
        val snapshot = HybridBootstrapManualTriggerSnapshotFormatter.format(
            commandBuildResult = builtCommandBuildResult(),
            latestTriggerResult = HybridBootstrapCommandTriggerResult.EndpointTooOld(
                ageMillis = 45_000L,
                maxAgeMillis = 30_000L
            )
        )

        assertEquals(
            "Hybrid bootstrap trigger: endpoint too old age=45000 max=30000",
            snapshot.triggerStatusText
        )
    }

    @Test
    fun notAllowedTriggerResultStatusPreservesReason() {
        val snapshot = HybridBootstrapManualTriggerSnapshotFormatter.format(
            commandBuildResult = builtCommandBuildResult(),
            latestTriggerResult = HybridBootstrapCommandTriggerResult.NotAllowed(
                reason = "Command creation timestamp is before request timestamp."
            )
        )

        assertEquals(
            "Hybrid bootstrap trigger: not allowed: Command creation timestamp is before request timestamp.",
            snapshot.triggerStatusText
        )
    }

    @Test
    fun formatterDoesNotMutateCommandBuildResult() {
        val commandBuildResult = builtCommandBuildResult()
        val before = commandBuildResult.copy(
            command = commandBuildResult.command.copy()
        )

        val snapshot = HybridBootstrapManualTriggerSnapshotFormatter.format(
            commandBuildResult = commandBuildResult,
            latestTriggerResult = null
        )

        assertEquals(before, commandBuildResult)
        assertNotNull(snapshot)
    }

    @Test
    fun formatterDoesNotMutateLatestTriggerResult() {
        val triggerResult = acceptedTriggerResult()
        val before = triggerResult.copy(
            executionResult = HybridBootstrapCommandExecutionResult.Accepted(
                peerId = "peer-trigger",
                sessionId = "session-trigger",
                bootstrapIdentifier = "bootstrap-trigger",
                groupOwnerAddress = "192.168.49.91",
                socketPort = 9091,
                commandCreatedAtMillis = 1_733_100_011L
            )
        )

        val snapshot = HybridBootstrapManualTriggerSnapshotFormatter.format(
            commandBuildResult = builtCommandBuildResult(),
            latestTriggerResult = triggerResult
        )

        assertEquals(before, triggerResult)
        assertNotNull(snapshot)
    }

    @Test
    fun formatterDoesNotCallExecutorExecute() {
        val executor = RecordingHybridBootstrapCommandExecutor(
            result = HybridBootstrapCommandExecutionResult.Accepted(
                peerId = "peer-unused",
                sessionId = "session-unused",
                bootstrapIdentifier = "bootstrap-unused",
                groupOwnerAddress = "192.168.49.92",
                socketPort = 9092,
                commandCreatedAtMillis = 1_733_100_020L
            )
        )

        val snapshot = HybridBootstrapManualTriggerSnapshotFormatter.format(
            commandBuildResult = builtCommandBuildResult(),
            latestTriggerResult = acceptedTriggerResult()
        )

        assertEquals(0, executor.executeCallCount)
        assertTrue(executor.executedCommands.isEmpty())
        assertTrue(snapshot.canTriggerNow)
    }

    @Test
    fun formatterIsPassiveAndOnlyBuildsSnapshotData() {
        val snapshot = HybridBootstrapManualTriggerSnapshotFormatter.format(
            commandBuildResult = builtCommandBuildResult(),
            latestTriggerResult = acceptedTriggerResult()
        )

        assertEquals(builtCommandBuildResult(), snapshot.commandBuildResult)
        assertEquals(acceptedTriggerResult(), snapshot.latestTriggerResult)
        assertTrue(snapshot.canTriggerNow)
    }

    private fun builtCommandBuildResult(): HybridBootstrapAttemptCommandBuildResult.Built {
        return HybridBootstrapAttemptCommandBuildResult.Built(
            HybridBootstrapAttemptCommand(
                peerId = "peer-built",
                sessionId = "session-built",
                bootstrapIdentifier = "bootstrap-built",
                groupOwnerAddress = "192.168.49.90",
                socketPort = 9090,
                latestCreatedAtMillis = 1_733_100_000L,
                requestedAtMillis = 1_733_100_001L,
                commandCreatedAtMillis = 1_733_100_002L
            )
        )
    }

    private fun acceptedTriggerResult(): HybridBootstrapCommandTriggerResult.Executed {
        return HybridBootstrapCommandTriggerResult.Executed(
            HybridBootstrapCommandExecutionResult.Accepted(
                peerId = "peer-trigger",
                sessionId = "session-trigger",
                bootstrapIdentifier = "bootstrap-trigger",
                groupOwnerAddress = "192.168.49.91",
                socketPort = 9091,
                commandCreatedAtMillis = 1_733_100_011L
            )
        )
    }

    private class RecordingHybridBootstrapCommandExecutor(
        private val result: HybridBootstrapCommandExecutionResult
    ) : HybridBootstrapCommandExecutor {
        private val recordedCommands = mutableListOf<HybridBootstrapAttemptCommand>()

        var executeCallCount: Int = 0
            private set

        val executedCommands: List<HybridBootstrapAttemptCommand>
            get() = recordedCommands.toList()

        override fun execute(
            command: HybridBootstrapAttemptCommand
        ): HybridBootstrapCommandExecutionResult {
            executeCallCount += 1
            recordedCommands += command.copy()
            return result
        }
    }
}
