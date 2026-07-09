package gr.hua.aurora.transport.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoOpHybridBootstrapCommandExecutorTest {
    @Test
    fun noOpExecutorRejectsAValidCommand() {
        val executor = NoOpHybridBootstrapCommandExecutor()

        val result = executor.execute(command())

        assertEquals(
            HybridBootstrapCommandExecutionResult.Rejected(
                reason = "Hybrid bootstrap execution is disabled."
            ),
            result
        )
    }

    @Test
    fun defaultRejectionReasonIsExactlyHybridBootstrapExecutionIsDisabled() {
        val executor = NoOpHybridBootstrapCommandExecutor()

        val result = executor.execute(command())

        assertEquals(
            "Hybrid bootstrap execution is disabled.",
            (result as HybridBootstrapCommandExecutionResult.Rejected).reason
        )
    }

    @Test
    fun customRejectionReasonIsPreservedExactly() {
        val executor = NoOpHybridBootstrapCommandExecutor(
            rejectionReason = "Custom no-op rejection."
        )

        val result = executor.execute(command())

        assertEquals(
            HybridBootstrapCommandExecutionResult.Rejected(
                reason = "Custom no-op rejection."
            ),
            result
        )
    }

    @Test
    fun executingAValidCommandDoesNotMutateTheCommand() {
        val executor = NoOpHybridBootstrapCommandExecutor()
        val command = command(
            peerId = "peer-stable",
            sessionId = "session-stable",
            bootstrapIdentifier = "bootstrap-stable",
            groupOwnerAddress = "192.168.49.150",
            socketPort = 9150,
            latestCreatedAtMillis = 1_732_000_010L,
            requestedAtMillis = 1_732_000_011L,
            commandCreatedAtMillis = 1_732_000_012L
        )
        val before = command.copy()

        val result = executor.execute(command)

        assertTrue(result is HybridBootstrapCommandExecutionResult.Rejected)
        assertEquals(before, command)
    }

    @Test
    fun executingMultipleValidCommandsAlwaysReturnsRejected() {
        val executor = NoOpHybridBootstrapCommandExecutor()

        val first = executor.execute(
            command(peerId = "peer-first", sessionId = "session-first")
        )
        val second = executor.execute(
            command(peerId = "peer-second", sessionId = "session-second")
        )

        assertTrue(first is HybridBootstrapCommandExecutionResult.Rejected)
        assertTrue(second is HybridBootstrapCommandExecutionResult.Rejected)
    }

    @Test
    fun noOpExecutorDoesNotRecordCommandsOrExposeHistory() {
        val methodNames = NoOpHybridBootstrapCommandExecutor::class.java.methods
            .map { it.name }

        assertFalse(methodNames.contains("getExecutedCommands"))
        assertFalse(methodNames.contains("getTriggerHistory"))
        assertFalse(methodNames.contains("getLatestResult"))
    }

    @Test
    fun noOpExecutorIsPassiveAndDoesNotPerformTransportOrSocketActions() {
        val executor = NoOpHybridBootstrapCommandExecutor()

        val result = executor.execute(command())

        assertTrue(result is HybridBootstrapCommandExecutionResult.Rejected)
    }

    private fun command(
        peerId: String = "peer-command",
        sessionId: String = "session-command",
        bootstrapIdentifier: String = "bootstrap-command",
        groupOwnerAddress: String = "192.168.49.151",
        socketPort: Int = 9151,
        latestCreatedAtMillis: Long = 1_732_000_000L,
        requestedAtMillis: Long = 1_732_000_001L,
        commandCreatedAtMillis: Long = 1_732_000_002L
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
}
