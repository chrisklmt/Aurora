package gr.hua.aurora.transport.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridBootstrapCommandExecutorFactoryTest {
    @Test
    fun factoryCreateWithDefaultConfigReturnsARejectingNoOpExecutor() {
        val executor = HybridBootstrapCommandExecutorFactory.create()

        val result = executor.execute(command())

        assertEquals(
            HybridBootstrapCommandExecutionResult.Rejected(
                reason = "Hybrid bootstrap execution is disabled."
            ),
            result
        )
    }

    @Test
    fun factoryCreateWithCustomNoOpConfigPreservesCustomRejectionReason() {
        val executor = HybridBootstrapCommandExecutorFactory.create(
            HybridBootstrapCommandExecutorConfig(
                mode = HybridBootstrapCommandExecutorMode.NO_OP,
                noOpRejectionReason = "Factory custom rejection."
            )
        )

        val result = executor.execute(command())

        assertEquals(
            HybridBootstrapCommandExecutionResult.Rejected(
                reason = "Factory custom rejection."
            ),
            result
        )
    }

    @Test
    fun factoryNoOpReturnsARejectingExecutor() {
        val executor = HybridBootstrapCommandExecutorFactory.noOp()

        val result = executor.execute(command())

        assertEquals(
            HybridBootstrapCommandExecutionResult.Rejected(
                reason = "Hybrid bootstrap execution is disabled."
            ),
            result
        )
    }

    @Test
    fun factoryDefaultRuntimeExecutorDelegatesToDefaultConfigBehavior() {
        val factoryExecutor = HybridBootstrapCommandExecutorFactory.create()
        val runtimeExecutor = HybridBootstrapCommandExecutorFactory.defaultRuntimeExecutor()
        val command = command()

        val factoryResult = factoryExecutor.execute(command)
        val runtimeResult = runtimeExecutor.execute(command.copy())

        assertEquals(factoryResult, runtimeResult)
    }

    @Test
    fun factoryDefaultRuntimeExecutorDoesNotReturnFakeExecutor() {
        val executor = HybridBootstrapCommandExecutorFactory.defaultRuntimeExecutor()

        assertFalse(executor is FakeHybridBootstrapCommandExecutor)
        assertTrue(executor is NoOpHybridBootstrapCommandExecutor)
    }

    @Test
    fun factoryConstructionIsPassiveAndDoesNotExposeExecutionHistory() {
        val executor = HybridBootstrapCommandExecutorFactory.create()
        val methodNames = executor::class.java.methods.map { it.name }

        assertFalse(methodNames.contains("getExecutedCommands"))
        assertFalse(methodNames.contains("getTriggerHistory"))
        assertFalse(methodNames.contains("getLatestResult"))
    }

    @Test
    fun socketPlanDisabledConfigReturnsRejectedDisabledSocketReasonForValidCommand() {
        val executor = HybridBootstrapCommandExecutorFactory.create(
            HybridBootstrapCommandExecutorConfig(
                mode = HybridBootstrapCommandExecutorMode.SOCKET_PLAN_DISABLED
            )
        )

        val result = executor.execute(command())

        assertEquals(
            HybridBootstrapCommandExecutionResult.Rejected(
                reason = "Hybrid bootstrap socket connector is disabled."
            ),
            result
        )
    }

    @Test
    fun socketPlanDisabledConfigPreservesCustomDisabledSocketFailureReason() {
        val executor = HybridBootstrapCommandExecutorFactory.create(
            HybridBootstrapCommandExecutorConfig(
                mode = HybridBootstrapCommandExecutorMode.SOCKET_PLAN_DISABLED,
                disabledSocketConnectorFailureReason = "Factory disabled socket rejection."
            )
        )

        val result = executor.execute(command())

        assertEquals(
            HybridBootstrapCommandExecutionResult.Rejected(
                reason = "Factory disabled socket rejection."
            ),
            result
        )
    }

    @Test
    fun socketPlanDisabledDoesNotReturnFakeExecutor() {
        val executor = HybridBootstrapCommandExecutorFactory.create(
            HybridBootstrapCommandExecutorConfig(
                mode = HybridBootstrapCommandExecutorMode.SOCKET_PLAN_DISABLED
            )
        )

        assertFalse(executor is FakeHybridBootstrapCommandExecutor)
    }

    @Test
    fun socketPlanDisabledDoesNotReturnNoOpBehaviorReason() {
        val executor = HybridBootstrapCommandExecutorFactory.create(
            HybridBootstrapCommandExecutorConfig(
                mode = HybridBootstrapCommandExecutorMode.SOCKET_PLAN_DISABLED
            )
        )

        val result = executor.execute(command())

        assertFalse(
            result == HybridBootstrapCommandExecutionResult.Rejected(
                reason = "Hybrid bootstrap execution is disabled."
            )
        )
    }

    @Test
    fun socketPlanDisabledUsesSocketPlanExecutorType() {
        val executor = HybridBootstrapCommandExecutorFactory.create(
            HybridBootstrapCommandExecutorConfig(
                mode = HybridBootstrapCommandExecutorMode.SOCKET_PLAN_DISABLED
            )
        )

        assertTrue(executor is HybridBootstrapSocketPlanCommandExecutor)
    }

    private fun command(
        peerId: String = "peer-factory",
        sessionId: String = "session-factory",
        bootstrapIdentifier: String = "bootstrap-factory",
        groupOwnerAddress: String = "192.168.49.181",
        socketPort: Int = 9181,
        latestCreatedAtMillis: Long = 1_734_000_000L,
        requestedAtMillis: Long = 1_734_000_001L,
        commandCreatedAtMillis: Long = 1_734_000_002L
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
