package gr.hua.aurora.transport.hybrid

import gr.hua.aurora.state.currentHybridBootstrapCommandExecutorConfig
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridBootstrapSocketConnectorFactoryTest {
    @Test
    fun defaultRuntimeConnectorReturnsFailedWithDefaultReasonForValidPlan() {
        val connector = HybridBootstrapSocketConnectorFactory.defaultRuntimeConnector()

        val result = connector.connect(plan())

        assertEquals(
            HybridBootstrapSocketConnectionResult.Failed(
                reason = "Hybrid bootstrap socket connector is disabled."
            ),
            result
        )
    }

    @Test
    fun disabledReturnsFailedWithDefaultReason() {
        val connector = HybridBootstrapSocketConnectorFactory.disabled()

        val result = connector.connect(plan())

        assertEquals(
            HybridBootstrapSocketConnectionResult.Failed(
                reason = "Hybrid bootstrap socket connector is disabled."
            ),
            result
        )
    }

    @Test
    fun disabledPreservesCustomReasonExactly() {
        val connector = HybridBootstrapSocketConnectorFactory.disabled(
            failureReason = "Custom disabled connector reason."
        )

        val result = connector.connect(plan())

        assertEquals(
            HybridBootstrapSocketConnectionResult.Failed(
                reason = "Custom disabled connector reason."
            ),
            result
        )
    }

    @Test
    fun dialerBackedUsesProvidedDialerOnConnect() {
        val dialer = FakeHybridBootstrapSocketDialer.connected(
            connectedAtMillis = 1_742_000_001L
        )
        val connector = HybridBootstrapSocketConnectorFactory.dialerBacked(dialer)

        connector.connect(plan())

        assertEquals(1, dialer.dialRequests.size)
    }

    @Test
    fun dialerBackedPassesAddressPortAndTimeoutFromPlan() {
        val dialer = FakeHybridBootstrapSocketDialer.connected(
            connectedAtMillis = 1_742_000_002L
        )
        val connector = HybridBootstrapSocketConnectorFactory.dialerBacked(dialer)

        connector.connect(
            plan(
                groupOwnerAddress = "192.168.49.150",
                socketPort = 9_150,
                connectTimeoutMillis = 12_345L
            )
        )

        assertEquals(
            listOf(
                FakeHybridBootstrapSocketDialer.DialRequest(
                    address = "192.168.49.150",
                    port = 9_150,
                    connectTimeoutMillis = 12_345L
                )
            ),
            dialer.dialRequests
        )
    }

    @Test
    fun dialerBackedMapsFakeConnectedResultToConnectedConnectionResult() {
        val connector = HybridBootstrapSocketConnectorFactory.dialerBacked(
            FakeHybridBootstrapSocketDialer { _ ->
                HybridBootstrapSocketDialResult.Connected(
                    address = "192.168.49.151",
                    port = 9_151,
                    connectedAtMillis = 1_742_000_003L
                )
            }
        )

        val result = connector.connect(
            plan(
                peerId = "peer-alpha",
                sessionId = "session-alpha",
                bootstrapIdentifier = "bootstrap-alpha"
            )
        )

        assertEquals(
            HybridBootstrapSocketConnectionResult.Connected(
                peerId = "peer-alpha",
                sessionId = "session-alpha",
                bootstrapIdentifier = "bootstrap-alpha",
                groupOwnerAddress = "192.168.49.151",
                socketPort = 9_151,
                connectedAtMillis = 1_742_000_003L
            ),
            result
        )
    }

    @Test
    fun dialerBackedMapsFakeFailedResultToFailedConnectionResult() {
        val connector = HybridBootstrapSocketConnectorFactory.dialerBacked(
            FakeHybridBootstrapSocketDialer.failed(
                reason = "Configured fake dial failure."
            )
        )

        val result = connector.connect(plan())

        assertEquals(
            HybridBootstrapSocketConnectionResult.Failed(
                reason = "Configured fake dial failure."
            ),
            result
        )
    }

    @Test
    fun javaNetConstructionDoesNotDial() {
        val connector = HybridBootstrapSocketConnectorFactory.javaNet()

        assertTrue(connector is DialerHybridBootstrapSocketConnector)
    }

    @Test
    fun factoryConstructionDoesNotCallConnectorConnect() {
        val dialer = FakeHybridBootstrapSocketDialer.connected(
            connectedAtMillis = 1_742_000_004L
        )

        HybridBootstrapSocketConnectorFactory.disabled()
        HybridBootstrapSocketConnectorFactory.dialerBacked(dialer)
        HybridBootstrapSocketConnectorFactory.javaNet()
        HybridBootstrapSocketConnectorFactory.defaultRuntimeConnector()

        assertTrue(dialer.dialRequests.isEmpty())
    }

    @Test
    fun factoryConstructionDoesNotCallDialerDial() {
        val dialer = FakeHybridBootstrapSocketDialer.connected(
            connectedAtMillis = 1_742_000_005L
        )

        HybridBootstrapSocketConnectorFactory.dialerBacked(dialer)

        assertTrue(dialer.dialRequests.isEmpty())
    }

    @Test
    fun factoryDoesNotImportJavaNetSocket() {
        val source = sourceText(
            "app/src/main/java/gr/hua/aurora/transport/hybrid/HybridBootstrapSocketConnectorFactory.kt"
        )

        assertFalse(source.contains("import java.net.Socket"))
    }

    @Test
    fun socketImportRemainsIsolatedToJavaNetHybridBootstrapSocketDialer() {
        val factorySource = sourceText(
            "app/src/main/java/gr/hua/aurora/transport/hybrid/HybridBootstrapSocketConnectorFactory.kt"
        )
        val javaNetSource = sourceText(
            "app/src/main/java/gr/hua/aurora/transport/hybrid/JavaNetHybridBootstrapSocketDialer.kt"
        )

        assertFalse(factorySource.contains("import java.net.Socket"))
        assertTrue(javaNetSource.contains("import java.net.Socket"))
    }

    @Test
    fun noServerSocketAppearsInNewFactoryFiles() {
        newMainSourcePaths().forEach { path ->
            assertFalse(sourceText(path).contains("ServerSocket"))
        }
    }

    @Test
    fun noWifiP2pApisAppearInNewFactoryFiles() {
        newMainSourcePaths().forEach { path ->
            val source = sourceText(path)

            assertFalse(source.contains("WifiP2pManager"))
            assertFalse(source.contains("WifiP2pConfig"))
        }
    }

    @Test
    fun auroraBleRuntimeHostIsUnchanged() {
        val source = sourceText(
            "app/src/main/java/gr/hua/aurora/state/AuroraBleRuntimeHost.kt"
        )

        assertFalse(source.contains("HybridBootstrapSocketConnectorFactory"))
    }

    @Test
    fun hybridBootstrapCommandExecutorFactoryDoesNotUseJavaNetConnectorFactory() {
        val source = sourceText(
            "app/src/main/java/gr/hua/aurora/transport/hybrid/HybridBootstrapCommandExecutorFactory.kt"
        )

        assertFalse(source.contains("HybridBootstrapSocketConnectorFactory.javaNet("))
    }

    @Test
    fun uiIsUnchanged() {
        val nearbySource = sourceText(
            "app/src/main/java/gr/hua/aurora/ui/screens/NearbyDevicesScreen.kt"
        )
        val navSource = sourceText(
            "app/src/main/java/gr/hua/aurora/navigation/NavGraph.kt"
        )

        assertFalse(nearbySource.contains("HybridBootstrapSocketConnectorFactory"))
        assertFalse(navSource.contains("HybridBootstrapSocketConnectorFactory"))
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
    fun factoryAdditionDoesNotTriggerWifiDirectDiscoveryOrGroupActions() {
        val connector = HybridBootstrapSocketConnectorFactory.defaultRuntimeConnector()

        val result = connector.connect(plan())

        assertTrue(result is HybridBootstrapSocketConnectionResult.Failed)
    }

    @Test
    fun factoryAdditionDoesNotSendBleOrWifiDirectFrames() {
        val connector = HybridBootstrapSocketConnectorFactory.disabled()

        val result = connector.connect(plan())

        assertTrue(result is HybridBootstrapSocketConnectionResult.Failed)
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
        latestCreatedAtMillis: Long = 1_742_000_000L,
        requestedAtMillis: Long = 1_742_000_001L,
        commandCreatedAtMillis: Long = 1_742_000_002L,
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

    private fun newMainSourcePaths(): List<String> {
        return listOf(
            "app/src/main/java/gr/hua/aurora/transport/hybrid/HybridBootstrapSocketConnectorFactory.kt"
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
