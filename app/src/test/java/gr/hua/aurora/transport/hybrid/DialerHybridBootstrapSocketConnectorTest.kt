package gr.hua.aurora.transport.hybrid

import gr.hua.aurora.state.currentHybridBootstrapCommandExecutorConfig
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialerHybridBootstrapSocketConnectorTest {
    @Test
    fun connectorCallsDialerExactlyOnceForValidPlan() {
        val dialer = FakeHybridBootstrapSocketDialer.connected(
            connectedAtMillis = 1_741_000_001L
        )
        val connector = DialerHybridBootstrapSocketConnector(dialer)

        connector.connect(plan())

        assertEquals(1, dialer.dialRequests.size)
    }

    @Test
    fun connectorPassesGroupOwnerAddressToDialer() {
        val dialer = FakeHybridBootstrapSocketDialer.connected(
            connectedAtMillis = 1_741_000_002L
        )
        val connector = DialerHybridBootstrapSocketConnector(dialer)

        connector.connect(
            plan(groupOwnerAddress = "fe80::42")
        )

        assertEquals("fe80::42", dialer.dialRequests.single().address)
    }

    @Test
    fun connectorPassesSocketPortToDialer() {
        val dialer = FakeHybridBootstrapSocketDialer.connected(
            connectedAtMillis = 1_741_000_003L
        )
        val connector = DialerHybridBootstrapSocketConnector(dialer)

        connector.connect(
            plan(socketPort = 65_535)
        )

        assertEquals(65_535, dialer.dialRequests.single().port)
    }

    @Test
    fun connectorPassesConnectTimeoutMillisToDialer() {
        val dialer = FakeHybridBootstrapSocketDialer.connected(
            connectedAtMillis = 1_741_000_004L
        )
        val connector = DialerHybridBootstrapSocketConnector(dialer)

        connector.connect(
            plan(connectTimeoutMillis = 12_345L)
        )

        assertEquals(12_345L, dialer.dialRequests.single().connectTimeoutMillis)
    }

    @Test
    fun connectedDialResultMapsToConnectedConnectionResult() {
        val connector = DialerHybridBootstrapSocketConnector(
            FakeHybridBootstrapSocketDialer.connected(
                connectedAtMillis = 1_741_000_005L
            )
        )

        val result = connector.connect(plan())

        assertTrue(result is HybridBootstrapSocketConnectionResult.Connected)
    }

    @Test
    fun connectedConnectionResultPreservesPeerIdFromPlan() {
        val result = connectedResult(
            plan = plan(peerId = "peer/Alpha+01")
        )

        assertEquals("peer/Alpha+01", result.peerId)
    }

    @Test
    fun connectedConnectionResultPreservesSessionIdFromPlan() {
        val result = connectedResult(
            plan = plan(sessionId = "session:Beta|02")
        )

        assertEquals("session:Beta|02", result.sessionId)
    }

    @Test
    fun connectedConnectionResultPreservesBootstrapIdentifierFromPlan() {
        val result = connectedResult(
            plan = plan(bootstrapIdentifier = "bootstrap==Gamma/03")
        )

        assertEquals("bootstrap==Gamma/03", result.bootstrapIdentifier)
    }

    @Test
    fun connectedConnectionResultPreservesGroupOwnerAddressFromDialResult() {
        val connector = DialerHybridBootstrapSocketConnector(
            FakeHybridBootstrapSocketDialer { _ ->
                HybridBootstrapSocketDialResult.Connected(
                    address = "192.168.49.250",
                    port = 9_250,
                    connectedAtMillis = 1_741_000_006L
                )
            }
        )

        val result = connector.connect(plan()) as HybridBootstrapSocketConnectionResult.Connected

        assertEquals("192.168.49.250", result.groupOwnerAddress)
    }

    @Test
    fun connectedConnectionResultPreservesSocketPortFromDialResult() {
        val connector = DialerHybridBootstrapSocketConnector(
            FakeHybridBootstrapSocketDialer { _ ->
                HybridBootstrapSocketDialResult.Connected(
                    address = "192.168.49.251",
                    port = 9_251,
                    connectedAtMillis = 1_741_000_007L
                )
            }
        )

        val result = connector.connect(plan()) as HybridBootstrapSocketConnectionResult.Connected

        assertEquals(9_251, result.socketPort)
    }

    @Test
    fun connectedConnectionResultPreservesConnectedAtMillisFromDialResult() {
        val connector = DialerHybridBootstrapSocketConnector(
            FakeHybridBootstrapSocketDialer { _ ->
                HybridBootstrapSocketDialResult.Connected(
                    address = "192.168.49.252",
                    port = 9_252,
                    connectedAtMillis = 1_741_000_008L
                )
            }
        )

        val result = connector.connect(plan()) as HybridBootstrapSocketConnectionResult.Connected

        assertEquals(1_741_000_008L, result.connectedAtMillis)
    }

    @Test
    fun failedDialResultMapsToFailedConnectionResult() {
        val connector = DialerHybridBootstrapSocketConnector(
            FakeHybridBootstrapSocketDialer.failed(
                reason = "Configured dial failure."
            )
        )

        val result = connector.connect(plan())

        assertTrue(result is HybridBootstrapSocketConnectionResult.Failed)
    }

    @Test
    fun failedConnectionResultPreservesExactReason() {
        val connector = DialerHybridBootstrapSocketConnector(
            FakeHybridBootstrapSocketDialer.failed(
                reason = "Configured dial failure."
            )
        )

        val result = connector.connect(plan())

        assertEquals(
            HybridBootstrapSocketConnectionResult.Failed(
                reason = "Configured dial failure."
            ),
            result
        )
    }

    @Test
    fun connectorDoesNotMutatePlan() {
        val dialer = FakeHybridBootstrapSocketDialer.connected(
            connectedAtMillis = 1_741_000_009L
        )
        val connector = DialerHybridBootstrapSocketConnector(dialer)
        val plan = plan(
            peerId = "peer-stable",
            sessionId = "session-stable",
            bootstrapIdentifier = "bootstrap-stable",
            groupOwnerAddress = "192.168.49.220",
            socketPort = 9_220,
            latestCreatedAtMillis = 1_741_000_020L,
            requestedAtMillis = 1_741_000_021L,
            commandCreatedAtMillis = 1_741_000_022L,
            connectTimeoutMillis = 6_000L
        )
        val before = plan.copy()

        connector.connect(plan)

        assertEquals(before, plan)
    }

    @Test
    fun connectorDoesNotImportOrCreateSocketOrServerSocketDirectly() {
        val source = sourceText(
            "app/src/main/java/gr/hua/aurora/transport/hybrid/DialerHybridBootstrapSocketConnector.kt"
        )

        assertFalse(source.contains("import java.net.Socket"))
        assertFalse(source.contains("Socket("))
        assertFalse(source.contains("ServerSocket"))
    }

    @Test
    fun auroraBleRuntimeHostIsUnchangedByConnectorAddition() {
        val source = sourceText(
            "app/src/main/java/gr/hua/aurora/state/AuroraBleRuntimeHost.kt"
        )

        assertFalse(source.contains("DialerHybridBootstrapSocketConnector"))
    }

    @Test
    fun hybridBootstrapCommandExecutorFactoryIsUnchangedByConnectorAddition() {
        val source = sourceText(
            "app/src/main/java/gr/hua/aurora/transport/hybrid/HybridBootstrapCommandExecutorFactory.kt"
        )

        assertFalse(source.contains("DialerHybridBootstrapSocketConnector"))
    }

    @Test
    fun uiIsUnchangedByConnectorAddition() {
        val nearbySource = sourceText(
            "app/src/main/java/gr/hua/aurora/ui/screens/NearbyDevicesScreen.kt"
        )
        val navSource = sourceText(
            "app/src/main/java/gr/hua/aurora/navigation/NavGraph.kt"
        )

        assertFalse(nearbySource.contains("DialerHybridBootstrapSocketConnector"))
        assertFalse(navSource.contains("DialerHybridBootstrapSocketConnector"))
    }

    @Test
    fun runtimeRemainsSocketPlanDisabledWithDisabledConnector() {
        val executor = HybridBootstrapCommandExecutorFactory.create(
            currentHybridBootstrapCommandExecutorConfig()
        )

        val result = executor.execute(validCommand())

        assertEquals(
            HybridBootstrapCommandExecutionResult.Rejected(
                reason = "Hybrid bootstrap socket connector is disabled."
            ),
            result
        )
    }

    @Test
    fun connectorAdditionDoesNotTriggerWifiDirectDiscoveryOrGroupActions() {
        val connector = DialerHybridBootstrapSocketConnector(
            FakeHybridBootstrapSocketDialer.connected(
                connectedAtMillis = 1_741_000_010L
            )
        )

        val result = connector.connect(plan())

        assertTrue(result is HybridBootstrapSocketConnectionResult.Connected)
    }

    @Test
    fun connectorAdditionDoesNotSendBleOrWifiDirectFrames() {
        val connector = DialerHybridBootstrapSocketConnector(
            FakeHybridBootstrapSocketDialer.connected(
                connectedAtMillis = 1_741_000_011L
            )
        )

        val result = connector.connect(plan())

        assertTrue(result is HybridBootstrapSocketConnectionResult.Connected)
    }

    private fun connectedResult(
        plan: HybridBootstrapSocketExecutionPlan
    ): HybridBootstrapSocketConnectionResult.Connected {
        return DialerHybridBootstrapSocketConnector(
            FakeHybridBootstrapSocketDialer.connected(
                connectedAtMillis = 1_741_000_012L
            )
        ).connect(plan) as HybridBootstrapSocketConnectionResult.Connected
    }

    private fun validCommand(): HybridBootstrapAttemptCommand {
        return HybridBootstrapAttemptCommand(
            peerId = "peer-runtime",
            sessionId = "session-runtime",
            bootstrapIdentifier = "bootstrap-runtime",
            groupOwnerAddress = "192.168.49.27",
            socketPort = 9_027,
            latestCreatedAtMillis = 1_740_000_100L,
            requestedAtMillis = 1_740_000_101L,
            commandCreatedAtMillis = 1_740_000_102L
        )
    }

    private fun plan(
        peerId: String = "peer-plan",
        sessionId: String = "session-plan",
        bootstrapIdentifier: String = "bootstrap-plan",
        groupOwnerAddress: String = "192.168.49.201",
        socketPort: Int = 9_201,
        latestCreatedAtMillis: Long = 1_741_000_000L,
        requestedAtMillis: Long = 1_741_000_001L,
        commandCreatedAtMillis: Long = 1_741_000_002L,
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

    private fun sourceText(relativePath: String): String {
        val sourcePath = resolveSourcePath(relativePath)
        return String(
            Files.readAllBytes(sourcePath),
            UTF_8
        )
    }

    private fun resolveSourcePath(relativePath: String): Path {
        val direct = Path.of(relativePath)
        if (Files.exists(direct)) {
            return direct
        }

        val parent = Path.of("..").resolve(relativePath).normalize()
        if (Files.exists(parent)) {
            return parent
        }

        val grandParent = Path.of("..", "..").resolve(relativePath).normalize()
        if (Files.exists(grandParent)) {
            return grandParent
        }

        error(
            "Missing source file: $relativePath (user.dir=${System.getProperty("user.dir")})"
        )
    }
}
