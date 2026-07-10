package gr.hua.aurora.transport.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisabledHybridBootstrapSocketConnectorTest {
    @Test
    fun disabledConnectorReturnsFailedForAValidPlan() {
        val connector = DisabledHybridBootstrapSocketConnector()

        val result = connector.connect(plan())

        assertTrue(result is HybridBootstrapSocketConnectionResult.Failed)
    }

    @Test
    fun defaultFailureReasonIsExact() {
        val connector = DisabledHybridBootstrapSocketConnector()

        val result = connector.connect(plan())

        assertEquals(
            HybridBootstrapSocketConnectionResult.Failed(
                reason = "Hybrid bootstrap socket connector is disabled."
            ),
            result
        )
    }

    @Test
    fun customFailureReasonIsPreservedExactly() {
        val connector = DisabledHybridBootstrapSocketConnector(
            failureReason = "Custom disabled socket connector reason."
        )

        val result = connector.connect(plan())

        assertEquals(
            HybridBootstrapSocketConnectionResult.Failed(
                reason = "Custom disabled socket connector reason."
            ),
            result
        )
    }

    @Test
    fun multipleConnectCallsAlwaysReturnFailed() {
        val connector = DisabledHybridBootstrapSocketConnector()

        val first = connector.connect(plan(peerId = "peer-first"))
        val second = connector.connect(plan(peerId = "peer-second"))

        assertTrue(first is HybridBootstrapSocketConnectionResult.Failed)
        assertTrue(second is HybridBootstrapSocketConnectionResult.Failed)
    }

    @Test
    fun disabledConnectorDoesNotMutateThePlan() {
        val connector = DisabledHybridBootstrapSocketConnector()
        val plan = plan(
            peerId = "peer-stable",
            sessionId = "session-stable",
            bootstrapIdentifier = "bootstrap-stable",
            groupOwnerAddress = "192.168.49.220",
            socketPort = 9220,
            latestCreatedAtMillis = 1_738_000_020L,
            requestedAtMillis = 1_738_000_021L,
            commandCreatedAtMillis = 1_738_000_022L,
            connectTimeoutMillis = 6_000L
        )
        val before = plan.copy()

        val result = connector.connect(plan)

        assertTrue(result is HybridBootstrapSocketConnectionResult.Failed)
        assertEquals(before, plan)
    }

    @Test
    fun disabledConnectorDoesNotRecordPlans() {
        val methodNames = DisabledHybridBootstrapSocketConnector::class.java.methods
            .map { it.name }

        assertFalse(methodNames.contains("getConnectedPlans"))
        assertFalse(methodNames.contains("getRecordedPlans"))
    }

    @Test
    fun disabledConnectorDoesNotExposeHistory() {
        val methodNames = DisabledHybridBootstrapSocketConnector::class.java.methods
            .map { it.name }

        assertFalse(methodNames.contains("history"))
        assertFalse(methodNames.contains("getHistory"))
    }

    @Test
    fun disabledConnectorDoesNotCreateSocketOrServerSocket() {
        val connector = DisabledHybridBootstrapSocketConnector()

        val result = connector.connect(plan())

        assertTrue(result is HybridBootstrapSocketConnectionResult.Failed)
    }

    @Test
    fun disabledConnectorDoesNotPerformTransportOrSocketActions() {
        val connector = DisabledHybridBootstrapSocketConnector()

        val result = connector.connect(plan())

        assertTrue(result is HybridBootstrapSocketConnectionResult.Failed)
    }

    private fun plan(
        peerId: String = "peer-plan",
        sessionId: String = "session-plan",
        bootstrapIdentifier: String = "bootstrap-plan",
        groupOwnerAddress: String = "192.168.49.221",
        socketPort: Int = 9221,
        latestCreatedAtMillis: Long = 1_738_000_000L,
        requestedAtMillis: Long = 1_738_000_001L,
        commandCreatedAtMillis: Long = 1_738_000_002L,
        connectTimeoutMillis: Long = 5_000L
    ): HybridBootstrapSocketExecutionPlan {
        return HybridBootstrapSocketExecutionPlan(
            peerId = peerId,
            sessionId = sessionId,
            bootstrapIdentifier = bootstrapIdentifier,
            groupOwnerAddress = groupOwnerAddress,
            socketPort = socketPort,
            latestCreatedAtMillis = latestCreatedAtMillis,
            requestedAtMillis = requestedAtMillis,
            commandCreatedAtMillis = commandCreatedAtMillis,
            connectTimeoutMillis = connectTimeoutMillis
        )
    }
}
