package gr.hua.aurora.transport.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridBootstrapCommandExecutorTest {
    @Test
    fun fakeExecutorRecordsOneCommand() {
        val executor = FakeHybridBootstrapCommandExecutor()
        val command = command(
            peerId = "peer-one",
            sessionId = "session-one"
        )

        val result = executor.execute(command)

        assertTrue(result is HybridBootstrapCommandExecutionResult.Accepted)
        assertEquals(listOf(command), executor.executedCommands)
    }

    @Test
    fun fakeExecutorRecordsMultipleCommandsInOrder() {
        val executor = FakeHybridBootstrapCommandExecutor()
        val first = command(
            peerId = "peer-first",
            sessionId = "session-first",
            commandCreatedAtMillis = 1_729_000_003L
        )
        val second = command(
            peerId = "peer-second",
            sessionId = "session-second",
            latestCreatedAtMillis = 1_729_000_010L,
            requestedAtMillis = 1_729_000_011L,
            commandCreatedAtMillis = 1_729_000_012L
        )

        executor.execute(first)
        executor.execute(second)

        assertEquals(listOf(first, second), executor.executedCommands)
    }

    @Test
    fun executedCommandsReturnsDefensiveCopy() {
        val executor = FakeHybridBootstrapCommandExecutor()
        val command = command()
        executor.execute(command)

        val firstRead = executor.executedCommands
        val secondRead = executor.executedCommands
        val mutableCopy = firstRead.toMutableList()
        mutableCopy.clear()

        assertNotSame(firstRead, secondRead)
        assertEquals(listOf(command), secondRead)
        assertEquals(listOf(command), executor.executedCommands)
    }

    @Test
    fun acceptedResultPreservesPeerSessionBootstrapAddressPortAndCommandCreatedAtExactly() {
        val executor = FakeHybridBootstrapCommandExecutor()
        val command = command(
            peerId = "peer/Alpha+01",
            sessionId = "session:Beta|02",
            bootstrapIdentifier = "bootstrap==Gamma/03",
            groupOwnerAddress = "fe80::1234",
            socketPort = 65535,
            commandCreatedAtMillis = 1_729_000_020L
        )

        val result = executor.execute(command)

        assertEquals(
            HybridBootstrapCommandExecutionResult.Accepted(
                peerId = "peer/Alpha+01",
                sessionId = "session:Beta|02",
                bootstrapIdentifier = "bootstrap==Gamma/03",
                groupOwnerAddress = "fe80::1234",
                socketPort = 65535,
                commandCreatedAtMillis = 1_729_000_020L
            ),
            result
        )
    }

    @Test
    fun rejectedResultPreservesConfiguredRejectionReason() {
        val executor = FakeHybridBootstrapCommandExecutor(
            shouldAccept = false,
            rejectionReason = "Configured fake rejection."
        )

        val result = executor.execute(command())

        assertEquals(
            HybridBootstrapCommandExecutionResult.Rejected(
                reason = "Configured fake rejection."
            ),
            result
        )
    }

    @Test
    fun executingCommandDoesNotMutateTheCommand() {
        val executor = FakeHybridBootstrapCommandExecutor()
        val command = command(
            peerId = "peer-stable",
            sessionId = "session-stable",
            bootstrapIdentifier = "bootstrap-stable",
            groupOwnerAddress = "192.168.49.90",
            socketPort = 9090,
            latestCreatedAtMillis = 1_729_000_030L,
            requestedAtMillis = 1_729_000_031L,
            commandCreatedAtMillis = 1_729_000_032L
        )
        val before = command.copy()

        val result = executor.execute(command)

        assertTrue(result is HybridBootstrapCommandExecutionResult.Accepted)
        assertEquals(before, command)
    }

    @Test
    fun fakeExecutorIsPassiveAndDoesNotPerformTransportOrSocketActions() {
        val executor = FakeHybridBootstrapCommandExecutor()

        val result = executor.execute(command())

        assertTrue(result is HybridBootstrapCommandExecutionResult.Accepted)
    }

    private fun command(
        peerId: String = "peer-command",
        sessionId: String = "session-command",
        bootstrapIdentifier: String = "bootstrap-command",
        groupOwnerAddress: String = "192.168.49.2",
        socketPort: Int = 8989,
        latestCreatedAtMillis: Long = 1_729_000_000L,
        requestedAtMillis: Long = 1_729_000_001L,
        commandCreatedAtMillis: Long = 1_729_000_002L
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
