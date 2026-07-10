package gr.hua.aurora.transport.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridBootstrapSocketPlanCommandExecutorTest {
    @Test
    fun validCommandWithConnectedFakeConnectorReturnsAccepted() {
        val executor = HybridBootstrapSocketPlanCommandExecutor(
            connector = FakeHybridBootstrapSocketConnector.connected(
                connectedAtMillis = 1_737_000_010L
            )
        )

        val result = executor.execute(command())

        assertTrue(result is HybridBootstrapCommandExecutionResult.Accepted)
    }

    @Test
    fun acceptedResultPreservesPeerId() {
        val result = executeConnected(
            command = command(peerId = "peer/Alpha+01")
        )

        assertEquals(
            "peer/Alpha+01",
            (result as HybridBootstrapCommandExecutionResult.Accepted).peerId
        )
    }

    @Test
    fun acceptedResultPreservesSessionId() {
        val result = executeConnected(
            command = command(sessionId = "session:Beta|02")
        )

        assertEquals(
            "session:Beta|02",
            (result as HybridBootstrapCommandExecutionResult.Accepted).sessionId
        )
    }

    @Test
    fun acceptedResultPreservesBootstrapIdentifier() {
        val result = executeConnected(
            command = command(bootstrapIdentifier = "bootstrap==Gamma/03")
        )

        assertEquals(
            "bootstrap==Gamma/03",
            (result as HybridBootstrapCommandExecutionResult.Accepted).bootstrapIdentifier
        )
    }

    @Test
    fun acceptedResultPreservesGroupOwnerAddress() {
        val result = executeConnected(
            command = command(groupOwnerAddress = "fe80::1234")
        )

        assertEquals(
            "fe80::1234",
            (result as HybridBootstrapCommandExecutionResult.Accepted).groupOwnerAddress
        )
    }

    @Test
    fun acceptedResultPreservesSocketPort() {
        val result = executeConnected(
            command = command(socketPort = 65_535)
        )

        assertEquals(
            65_535,
            (result as HybridBootstrapCommandExecutionResult.Accepted).socketPort
        )
    }

    @Test
    fun acceptedResultPreservesCommandCreatedAtMillisFromThePlan() {
        val result = executeConnected(
            command = command(commandCreatedAtMillis = 1_737_000_012L)
        )

        assertEquals(
            1_737_000_012L,
            (result as HybridBootstrapCommandExecutionResult.Accepted).commandCreatedAtMillis
        )
    }

    @Test
    fun validCommandPassesExactlyOnePlanToConnector() {
        val connector = FakeHybridBootstrapSocketConnector.connected(
            connectedAtMillis = 1_737_000_010L
        )
        val executor = HybridBootstrapSocketPlanCommandExecutor(
            connector = connector
        )

        executor.execute(command())

        assertEquals(1, connector.connectedPlans.size)
    }

    @Test
    fun planPassedToConnectorPreservesCommandFields() {
        val connector = FakeHybridBootstrapSocketConnector.connected(
            connectedAtMillis = 1_737_000_010L
        )
        val executor = HybridBootstrapSocketPlanCommandExecutor(
            connector = connector
        )
        val command = command(
            peerId = "peer-stable",
            sessionId = "session-stable",
            bootstrapIdentifier = "bootstrap-stable",
            groupOwnerAddress = "192.168.49.210",
            socketPort = 9210,
            latestCreatedAtMillis = 1_737_000_020L,
            requestedAtMillis = 1_737_000_021L,
            commandCreatedAtMillis = 1_737_000_022L
        )

        executor.execute(command)

        assertEquals(
            listOf(
                HybridBootstrapSocketExecutionPlan(
                    peerId = "peer-stable",
                    sessionId = "session-stable",
                    bootstrapIdentifier = "bootstrap-stable",
                    groupOwnerAddress = "192.168.49.210",
                    socketPort = 9210,
                    latestCreatedAtMillis = 1_737_000_020L,
                    requestedAtMillis = 1_737_000_021L,
                    commandCreatedAtMillis = 1_737_000_022L,
                    connectTimeoutMillis = 5_000L
                )
            ),
            connector.connectedPlans
        )
    }

    @Test
    fun customConnectTimeoutMillisIsPassedIntoThePlan() {
        val connector = FakeHybridBootstrapSocketConnector.connected(
            connectedAtMillis = 1_737_000_010L
        )
        val executor = HybridBootstrapSocketPlanCommandExecutor(
            connector = connector,
            connectTimeoutMillis = 12_345L
        )

        executor.execute(command())

        assertEquals(12_345L, connector.connectedPlans.single().connectTimeoutMillis)
    }

    @Test
    fun validCommandWithFailedFakeConnectorReturnsRejectedWithExactReason() {
        val executor = HybridBootstrapSocketPlanCommandExecutor(
            connector = FakeHybridBootstrapSocketConnector.failed(
                reason = "Configured socket failure."
            )
        )

        val result = executor.execute(command())

        assertEquals(
            HybridBootstrapCommandExecutionResult.Rejected(
                reason = "Configured socket failure."
            ),
            result
        )
    }

    @Test
    fun invalidCommandBuildResultReturnsRejectedWithExpectedPrefix() {
        val executor = HybridBootstrapSocketPlanCommandExecutor(
            connector = FakeHybridBootstrapSocketConnector.connected(
                connectedAtMillis = 1_737_000_010L
            )
        )

        val result = executor.execute(
            invalidCommand(peerId = " ")
        )

        assertEquals(
            HybridBootstrapCommandExecutionResult.Rejected(
                reason = "Invalid socket execution command: Hybrid bootstrap socket execution plan peerId must not be blank."
            ),
            result
        )
    }

    @Test
    fun invalidTimeoutReturnsRejectedWithExpectedPrefix() {
        val executor = HybridBootstrapSocketPlanCommandExecutor(
            connector = FakeHybridBootstrapSocketConnector.connected(
                connectedAtMillis = 1_737_000_010L
            ),
            connectTimeoutMillis = 0L
        )

        val result = executor.execute(command())

        assertEquals(
            HybridBootstrapCommandExecutionResult.Rejected(
                reason = "Invalid socket execution timeout: Hybrid bootstrap socket execution plan connectTimeoutMillis must be greater than 0."
            ),
            result
        )
    }

    @Test
    fun invalidCommandDoesNotCallConnector() {
        val connector = FakeHybridBootstrapSocketConnector.connected(
            connectedAtMillis = 1_737_000_010L
        )
        val executor = HybridBootstrapSocketPlanCommandExecutor(
            connector = connector
        )

        executor.execute(
            invalidCommand(peerId = " ")
        )

        assertTrue(connector.connectedPlans.isEmpty())
    }

    @Test
    fun invalidTimeoutDoesNotCallConnector() {
        val connector = FakeHybridBootstrapSocketConnector.connected(
            connectedAtMillis = 1_737_000_010L
        )
        val executor = HybridBootstrapSocketPlanCommandExecutor(
            connector = connector,
            connectTimeoutMillis = 0L
        )

        executor.execute(command())

        assertTrue(connector.connectedPlans.isEmpty())
    }

    @Test
    fun executorDoesNotMutateCommand() {
        val connector = FakeHybridBootstrapSocketConnector.connected(
            connectedAtMillis = 1_737_000_010L
        )
        val executor = HybridBootstrapSocketPlanCommandExecutor(
            connector = connector
        )
        val command = command(
            peerId = "peer-stable",
            sessionId = "session-stable",
            bootstrapIdentifier = "bootstrap-stable",
            groupOwnerAddress = "192.168.49.211",
            socketPort = 9211,
            latestCreatedAtMillis = 1_737_000_030L,
            requestedAtMillis = 1_737_000_031L,
            commandCreatedAtMillis = 1_737_000_032L
        )
        val before = command.copy()

        val result = executor.execute(command)

        assertTrue(result is HybridBootstrapCommandExecutionResult.Accepted)
        assertEquals(before, command)
    }

    @Test
    fun executorDoesNotCreateSocketOrServerSocket() {
        val executor = HybridBootstrapSocketPlanCommandExecutor(
            connector = FakeHybridBootstrapSocketConnector.connected(
                connectedAtMillis = 1_737_000_010L
            )
        )

        val result = executor.execute(command())

        assertTrue(result is HybridBootstrapCommandExecutionResult.Accepted)
    }

    @Test
    fun executorIsPassiveAndDoesNotPerformTransportOrSocketActions() {
        val executor = HybridBootstrapSocketPlanCommandExecutor(
            connector = FakeHybridBootstrapSocketConnector.connected(
                connectedAtMillis = 1_737_000_010L
            )
        )

        val result = executor.execute(command())

        assertTrue(result is HybridBootstrapCommandExecutionResult.Accepted)
    }

    private fun executeConnected(
        command: HybridBootstrapAttemptCommand
    ): HybridBootstrapCommandExecutionResult {
        val executor = HybridBootstrapSocketPlanCommandExecutor(
            connector = FakeHybridBootstrapSocketConnector.connected(
                connectedAtMillis = 1_737_000_010L
            )
        )

        return executor.execute(command)
    }

    private fun command(
        peerId: String = "peer-command",
        sessionId: String = "session-command",
        bootstrapIdentifier: String = "bootstrap-command",
        groupOwnerAddress: String = "192.168.49.212",
        socketPort: Int = 9212,
        latestCreatedAtMillis: Long = 1_737_000_000L,
        requestedAtMillis: Long = 1_737_000_001L,
        commandCreatedAtMillis: Long = 1_737_000_002L
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

    private fun invalidCommand(
        peerId: String = "peer-command",
        sessionId: String = "session-command",
        bootstrapIdentifier: String = "bootstrap-command",
        groupOwnerAddress: String = "192.168.49.212",
        socketPort: Int = 9212,
        latestCreatedAtMillis: Long = 1_737_000_000L,
        requestedAtMillis: Long = 1_737_000_001L,
        commandCreatedAtMillis: Long = 1_737_000_002L
    ): HybridBootstrapAttemptCommand {
        val command = command()

        setField(command, "peerId", peerId)
        setField(command, "sessionId", sessionId)
        setField(command, "bootstrapIdentifier", bootstrapIdentifier)
        setField(command, "groupOwnerAddress", groupOwnerAddress)
        setField(command, "socketPort", socketPort)
        setField(command, "latestCreatedAtMillis", latestCreatedAtMillis)
        setField(command, "requestedAtMillis", requestedAtMillis)
        setField(command, "commandCreatedAtMillis", commandCreatedAtMillis)

        return command
    }

    private fun setField(
        target: Any,
        fieldName: String,
        value: Any
    ) {
        val field = HybridBootstrapAttemptCommand::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        when (value) {
            is Int -> field.setInt(target, value)
            is Long -> field.setLong(target, value)
            else -> field.set(target, value)
        }
    }
}
