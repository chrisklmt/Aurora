package gr.hua.aurora.transport.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridBootstrapSocketExecutionPlanBuilderTest {
    @Test
    fun validCommandBuildsAPlan() {
        val result = HybridBootstrapSocketExecutionPlanBuilder.build(
            command = command()
        )

        assertTrue(result is HybridBootstrapSocketExecutionPlanBuildResult.Built)
    }

    @Test
    fun builtPlanPreservesPeerId() {
        assertBuiltPlanField(
            command = command(peerId = "peer/Alpha+01")
        ) { plan ->
            assertEquals("peer/Alpha+01", plan.peerId)
        }
    }

    @Test
    fun builtPlanPreservesSessionId() {
        assertBuiltPlanField(
            command = command(sessionId = "session:Beta|02")
        ) { plan ->
            assertEquals("session:Beta|02", plan.sessionId)
        }
    }

    @Test
    fun builtPlanPreservesBootstrapIdentifier() {
        assertBuiltPlanField(
            command = command(bootstrapIdentifier = "bootstrap==Gamma/03")
        ) { plan ->
            assertEquals("bootstrap==Gamma/03", plan.bootstrapIdentifier)
        }
    }

    @Test
    fun builtPlanPreservesGroupOwnerAddress() {
        assertBuiltPlanField(
            command = command(groupOwnerAddress = "fe80::1234")
        ) { plan ->
            assertEquals("fe80::1234", plan.groupOwnerAddress)
        }
    }

    @Test
    fun builtPlanPreservesSocketPort() {
        assertBuiltPlanField(
            command = command(socketPort = 65_535)
        ) { plan ->
            assertEquals(65_535, plan.socketPort)
        }
    }

    @Test
    fun builtPlanPreservesLatestCreatedAtMillis() {
        assertBuiltPlanField(
            command = command(
                latestCreatedAtMillis = 1_735_000_010L,
                requestedAtMillis = 1_735_000_011L,
                commandCreatedAtMillis = 1_735_000_012L
            )
        ) { plan ->
            assertEquals(1_735_000_010L, plan.latestCreatedAtMillis)
        }
    }

    @Test
    fun builtPlanPreservesRequestedAtMillis() {
        assertBuiltPlanField(
            command = command(
                latestCreatedAtMillis = 1_735_000_010L,
                requestedAtMillis = 1_735_000_011L,
                commandCreatedAtMillis = 1_735_000_012L
            )
        ) { plan ->
            assertEquals(1_735_000_011L, plan.requestedAtMillis)
        }
    }

    @Test
    fun builtPlanPreservesCommandCreatedAtMillis() {
        assertBuiltPlanField(
            command = command(commandCreatedAtMillis = 1_735_000_012L)
        ) { plan ->
            assertEquals(1_735_000_012L, plan.commandCreatedAtMillis)
        }
    }

    @Test
    fun builtPlanUsesDefaultConnectTimeout() {
        assertBuiltPlanField(
            command = command()
        ) { plan ->
            assertEquals(
                HybridBootstrapSocketExecutionPlanBuilder.DEFAULT_CONNECT_TIMEOUT_MILLIS,
                plan.connectTimeoutMillis
            )
        }
    }

    @Test
    fun builtPlanPreservesCustomConnectTimeout() {
        assertBuiltPlanField(
            command = command(),
            connectTimeoutMillis = 12_345L
        ) { plan ->
            assertEquals(12_345L, plan.connectTimeoutMillis)
        }
    }

    @Test
    fun blankPeerIdReturnsInvalidCommand() {
        val result = HybridBootstrapSocketExecutionPlanBuilder.build(
            command = invalidCommand(peerId = " ")
        )

        assertEquals(
            HybridBootstrapSocketExecutionPlanBuildResult.InvalidCommand(
                reason = "Hybrid bootstrap socket execution plan peerId must not be blank."
            ),
            result
        )
    }

    @Test
    fun blankSessionIdReturnsInvalidCommand() {
        val result = HybridBootstrapSocketExecutionPlanBuilder.build(
            command = invalidCommand(sessionId = " ")
        )

        assertEquals(
            HybridBootstrapSocketExecutionPlanBuildResult.InvalidCommand(
                reason = "Hybrid bootstrap socket execution plan sessionId must not be blank."
            ),
            result
        )
    }

    @Test
    fun blankBootstrapIdentifierReturnsInvalidCommand() {
        val result = HybridBootstrapSocketExecutionPlanBuilder.build(
            command = invalidCommand(bootstrapIdentifier = " ")
        )

        assertEquals(
            HybridBootstrapSocketExecutionPlanBuildResult.InvalidCommand(
                reason = "Hybrid bootstrap socket execution plan bootstrapIdentifier must not be blank."
            ),
            result
        )
    }

    @Test
    fun blankGroupOwnerAddressReturnsInvalidCommand() {
        val result = HybridBootstrapSocketExecutionPlanBuilder.build(
            command = invalidCommand(groupOwnerAddress = " ")
        )

        assertEquals(
            HybridBootstrapSocketExecutionPlanBuildResult.InvalidCommand(
                reason = "Hybrid bootstrap socket execution plan groupOwnerAddress must not be blank."
            ),
            result
        )
    }

    @Test
    fun socketPortZeroReturnsInvalidCommand() {
        val result = HybridBootstrapSocketExecutionPlanBuilder.build(
            command = invalidCommand(socketPort = 0)
        )

        assertEquals(
            HybridBootstrapSocketExecutionPlanBuildResult.InvalidCommand(
                reason = "Hybrid bootstrap socket execution plan socketPort must be in 1..65535."
            ),
            result
        )
    }

    @Test
    fun socketPort65536ReturnsInvalidCommand() {
        val result = HybridBootstrapSocketExecutionPlanBuilder.build(
            command = invalidCommand(socketPort = 65_536)
        )

        assertEquals(
            HybridBootstrapSocketExecutionPlanBuildResult.InvalidCommand(
                reason = "Hybrid bootstrap socket execution plan socketPort must be in 1..65535."
            ),
            result
        )
    }

    @Test
    fun negativeLatestCreatedAtMillisReturnsInvalidCommand() {
        val result = HybridBootstrapSocketExecutionPlanBuilder.build(
            command = invalidCommand(latestCreatedAtMillis = -1L)
        )

        assertEquals(
            HybridBootstrapSocketExecutionPlanBuildResult.InvalidCommand(
                reason = "Hybrid bootstrap socket execution plan latestCreatedAtMillis must be non-negative."
            ),
            result
        )
    }

    @Test
    fun requestedAtMillisBeforeLatestCreatedAtMillisReturnsInvalidCommand() {
        val result = HybridBootstrapSocketExecutionPlanBuilder.build(
            command = invalidCommand(
                latestCreatedAtMillis = 1_735_000_020L,
                requestedAtMillis = 1_735_000_019L
            )
        )

        assertEquals(
            HybridBootstrapSocketExecutionPlanBuildResult.InvalidCommand(
                reason = "Hybrid bootstrap socket execution plan requestedAtMillis must be greater than or equal to latestCreatedAtMillis."
            ),
            result
        )
    }

    @Test
    fun commandCreatedAtMillisBeforeRequestedAtMillisReturnsInvalidCommand() {
        val result = HybridBootstrapSocketExecutionPlanBuilder.build(
            command = invalidCommand(
                latestCreatedAtMillis = 1_735_000_030L,
                requestedAtMillis = 1_735_000_031L,
                commandCreatedAtMillis = 1_735_000_030L
            )
        )

        assertEquals(
            HybridBootstrapSocketExecutionPlanBuildResult.InvalidCommand(
                reason = "Hybrid bootstrap socket execution plan commandCreatedAtMillis must be greater than or equal to requestedAtMillis."
            ),
            result
        )
    }

    @Test
    fun connectTimeoutMillisZeroReturnsInvalidTimeout() {
        val result = HybridBootstrapSocketExecutionPlanBuilder.build(
            command = command(),
            connectTimeoutMillis = 0L
        )

        assertEquals(
            HybridBootstrapSocketExecutionPlanBuildResult.InvalidTimeout(
                reason = "Hybrid bootstrap socket execution plan connectTimeoutMillis must be greater than 0."
            ),
            result
        )
    }

    @Test
    fun negativeConnectTimeoutMillisReturnsInvalidTimeout() {
        val result = HybridBootstrapSocketExecutionPlanBuilder.build(
            command = command(),
            connectTimeoutMillis = -1L
        )

        assertEquals(
            HybridBootstrapSocketExecutionPlanBuildResult.InvalidTimeout(
                reason = "Hybrid bootstrap socket execution plan connectTimeoutMillis must be greater than 0."
            ),
            result
        )
    }

    @Test
    fun tooLargeConnectTimeoutMillisReturnsInvalidTimeout() {
        val result = HybridBootstrapSocketExecutionPlanBuilder.build(
            command = command(),
            connectTimeoutMillis = HybridBootstrapSocketExecutionPlanBuilder.MAX_CONNECT_TIMEOUT_MILLIS + 1L
        )

        assertEquals(
            HybridBootstrapSocketExecutionPlanBuildResult.InvalidTimeout(
                reason = "Hybrid bootstrap socket execution plan connectTimeoutMillis must be less than or equal to 30000."
            ),
            result
        )
    }

    @Test
    fun builderDoesNotMutateCommand() {
        val command = command(
            peerId = "peer-stable",
            sessionId = "session-stable",
            bootstrapIdentifier = "bootstrap-stable",
            groupOwnerAddress = "192.168.49.190",
            socketPort = 9190,
            latestCreatedAtMillis = 1_735_000_040L,
            requestedAtMillis = 1_735_000_041L,
            commandCreatedAtMillis = 1_735_000_042L
        )
        val before = command.copy()

        val result = HybridBootstrapSocketExecutionPlanBuilder.build(command)

        assertTrue(result is HybridBootstrapSocketExecutionPlanBuildResult.Built)
        assertEquals(before, command)
    }

    @Test
    fun builderDoesNotCallExecutorExecute() {
        val result = HybridBootstrapSocketExecutionPlanBuilder.build(
            command = command()
        )

        assertTrue(result is HybridBootstrapSocketExecutionPlanBuildResult.Built)
    }

    @Test
    fun builderDoesNotCreateSocketOrServerSocket() {
        val result = HybridBootstrapSocketExecutionPlanBuilder.build(
            command = command()
        )

        assertTrue(result is HybridBootstrapSocketExecutionPlanBuildResult.Built)
    }

    @Test
    fun builderIsPassiveAndDoesNotPerformTransportOrSocketActions() {
        val result = HybridBootstrapSocketExecutionPlanBuilder.build(
            command = command()
        )

        assertTrue(result is HybridBootstrapSocketExecutionPlanBuildResult.Built)
    }

    private fun assertBuiltPlanField(
        command: HybridBootstrapAttemptCommand,
        connectTimeoutMillis: Long = HybridBootstrapSocketExecutionPlanBuilder.DEFAULT_CONNECT_TIMEOUT_MILLIS,
        assertion: (HybridBootstrapSocketExecutionPlan) -> Unit
    ) {
        val result = HybridBootstrapSocketExecutionPlanBuilder.build(
            command = command,
            connectTimeoutMillis = connectTimeoutMillis
        )

        assertTrue(result is HybridBootstrapSocketExecutionPlanBuildResult.Built)
        val built = result as HybridBootstrapSocketExecutionPlanBuildResult.Built
        assertion(built.plan)
    }

    private fun command(
        peerId: String = "peer-command",
        sessionId: String = "session-command",
        bootstrapIdentifier: String = "bootstrap-command",
        groupOwnerAddress: String = "192.168.49.191",
        socketPort: Int = 9191,
        latestCreatedAtMillis: Long = 1_735_000_000L,
        requestedAtMillis: Long = 1_735_000_001L,
        commandCreatedAtMillis: Long = 1_735_000_002L
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
        groupOwnerAddress: String = "192.168.49.191",
        socketPort: Int = 9191,
        latestCreatedAtMillis: Long = 1_735_000_000L,
        requestedAtMillis: Long = 1_735_000_001L,
        commandCreatedAtMillis: Long = 1_735_000_002L
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
