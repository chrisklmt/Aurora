package gr.hua.aurora.transport.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridBootstrapSocketConnectorTest {
    @Test
    fun connectorContractReturnsConnected() {
        val connector = FakeHybridBootstrapSocketConnector.connected(
            connectedAtMillis = 1_736_000_010L
        )

        val result = connector.connect(plan())

        assertTrue(result is HybridBootstrapSocketConnectionResult.Connected)
    }

    @Test
    fun connectedResultPreservesPeerId() {
        val result = FakeHybridBootstrapSocketConnector.connected(
            connectedAtMillis = 1_736_000_010L
        ).connect(
            plan(peerId = "peer/Alpha+01")
        )

        assertEquals(
            "peer/Alpha+01",
            (result as HybridBootstrapSocketConnectionResult.Connected).peerId
        )
    }

    @Test
    fun connectedResultPreservesSessionId() {
        val result = FakeHybridBootstrapSocketConnector.connected(
            connectedAtMillis = 1_736_000_010L
        ).connect(
            plan(sessionId = "session:Beta|02")
        )

        assertEquals(
            "session:Beta|02",
            (result as HybridBootstrapSocketConnectionResult.Connected).sessionId
        )
    }

    @Test
    fun connectedResultPreservesBootstrapIdentifier() {
        val result = FakeHybridBootstrapSocketConnector.connected(
            connectedAtMillis = 1_736_000_010L
        ).connect(
            plan(bootstrapIdentifier = "bootstrap==Gamma/03")
        )

        assertEquals(
            "bootstrap==Gamma/03",
            (result as HybridBootstrapSocketConnectionResult.Connected).bootstrapIdentifier
        )
    }

    @Test
    fun connectedResultPreservesGroupOwnerAddress() {
        val result = FakeHybridBootstrapSocketConnector.connected(
            connectedAtMillis = 1_736_000_010L
        ).connect(
            plan(groupOwnerAddress = "fe80::1234")
        )

        assertEquals(
            "fe80::1234",
            (result as HybridBootstrapSocketConnectionResult.Connected).groupOwnerAddress
        )
    }

    @Test
    fun connectedResultPreservesSocketPort() {
        val result = FakeHybridBootstrapSocketConnector.connected(
            connectedAtMillis = 1_736_000_010L
        ).connect(
            plan(socketPort = 65_535)
        )

        assertEquals(
            65_535,
            (result as HybridBootstrapSocketConnectionResult.Connected).socketPort
        )
    }

    @Test
    fun connectedResultPreservesConnectedAtMillis() {
        val result = FakeHybridBootstrapSocketConnector.connected(
            connectedAtMillis = 1_736_000_010L
        ).connect(plan())

        assertEquals(
            1_736_000_010L,
            (result as HybridBootstrapSocketConnectionResult.Connected).connectedAtMillis
        )
    }

    @Test
    fun connectorContractReturnsFailedWithReason() {
        val result = FakeHybridBootstrapSocketConnector.failed(
            reason = "Configured socket failure."
        ).connect(plan())

        assertEquals(
            HybridBootstrapSocketConnectionResult.Failed(
                reason = "Configured socket failure."
            ),
            result
        )
    }

    @Test
    fun fakeConnectorRecordsOnePlan() {
        val connector = FakeHybridBootstrapSocketConnector.connected(
            connectedAtMillis = 1_736_000_010L
        )
        val plan = plan(
            peerId = "peer-one",
            sessionId = "session-one"
        )

        connector.connect(plan)

        assertEquals(listOf(plan), connector.connectedPlans)
    }

    @Test
    fun fakeConnectorRecordsMultiplePlansInOrder() {
        val connector = FakeHybridBootstrapSocketConnector.connected(
            connectedAtMillis = 1_736_000_010L
        )
        val first = plan(
            peerId = "peer-first",
            sessionId = "session-first",
            commandCreatedAtMillis = 1_736_000_003L
        )
        val second = plan(
            peerId = "peer-second",
            sessionId = "session-second",
            latestCreatedAtMillis = 1_736_000_010L,
            requestedAtMillis = 1_736_000_011L,
            commandCreatedAtMillis = 1_736_000_012L
        )

        connector.connect(first)
        connector.connect(second)

        assertEquals(listOf(first, second), connector.connectedPlans)
    }

    @Test
    fun fakeConnectorExposesDefensiveCopyHistory() {
        val connector = FakeHybridBootstrapSocketConnector.connected(
            connectedAtMillis = 1_736_000_010L
        )
        val plan = plan()
        connector.connect(plan)

        val firstRead = connector.connectedPlans
        val secondRead = connector.connectedPlans
        val mutableCopy = firstRead.toMutableList()
        mutableCopy.clear()

        assertNotSame(firstRead, secondRead)
        assertEquals(listOf(plan), secondRead)
        assertEquals(listOf(plan), connector.connectedPlans)
    }

    @Test
    fun fakeConnectorDoesNotMutatePlan() {
        val connector = FakeHybridBootstrapSocketConnector.connected(
            connectedAtMillis = 1_736_000_010L
        )
        val plan = plan(
            peerId = "peer-stable",
            sessionId = "session-stable",
            bootstrapIdentifier = "bootstrap-stable",
            groupOwnerAddress = "192.168.49.200",
            socketPort = 9200,
            latestCreatedAtMillis = 1_736_000_020L,
            requestedAtMillis = 1_736_000_021L,
            commandCreatedAtMillis = 1_736_000_022L,
            connectTimeoutMillis = 6_000L
        )
        val before = plan.copy()

        val result = connector.connect(plan)

        assertTrue(result is HybridBootstrapSocketConnectionResult.Connected)
        assertEquals(before, plan)
    }

    @Test
    fun fakeConnectorCanReturnDeterministicConnectedResult() {
        val result = FakeHybridBootstrapSocketConnector.connected(
            connectedAtMillis = 1_736_000_030L
        ).connect(plan())

        assertEquals(
            HybridBootstrapSocketConnectionResult.Connected(
                peerId = "peer-plan",
                sessionId = "session-plan",
                bootstrapIdentifier = "bootstrap-plan",
                groupOwnerAddress = "192.168.49.201",
                socketPort = 9201,
                connectedAtMillis = 1_736_000_030L
            ),
            result
        )
    }

    @Test
    fun fakeConnectorCanReturnDeterministicFailedResult() {
        val result = FakeHybridBootstrapSocketConnector.failed(
            reason = "Deterministic socket failure."
        ).connect(plan())

        assertEquals(
            HybridBootstrapSocketConnectionResult.Failed(
                reason = "Deterministic socket failure."
            ),
            result
        )
    }

    @Test
    fun fakeConnectorDoesNotCreateSocketOrServerSocket() {
        val result = FakeHybridBootstrapSocketConnector.connected(
            connectedAtMillis = 1_736_000_010L
        ).connect(plan())

        assertTrue(result is HybridBootstrapSocketConnectionResult.Connected)
    }

    @Test
    fun fakeConnectorIsPassiveAndDoesNotPerformTransportOrSocketActions() {
        val result = FakeHybridBootstrapSocketConnector.connected(
            connectedAtMillis = 1_736_000_010L
        ).connect(plan())

        assertTrue(result is HybridBootstrapSocketConnectionResult.Connected)
    }

    private fun plan(
        peerId: String = "peer-plan",
        sessionId: String = "session-plan",
        bootstrapIdentifier: String = "bootstrap-plan",
        groupOwnerAddress: String = "192.168.49.201",
        socketPort: Int = 9201,
        latestCreatedAtMillis: Long = 1_736_000_000L,
        requestedAtMillis: Long = 1_736_000_001L,
        commandCreatedAtMillis: Long = 1_736_000_002L,
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
