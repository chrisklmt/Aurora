package gr.hua.aurora.transport.hybrid

import gr.hua.aurora.wifidirect.frame.WifiDirectTransportAdapterDiagnostics
import gr.hua.aurora.wifidirect.frame.WifiDirectTransportAdapterState
import gr.hua.aurora.wifidirect.socket.WifiDirectSocketDiagnostics
import gr.hua.aurora.wifidirect.socket.WifiDirectSocketEndpoint
import gr.hua.aurora.wifidirect.socket.WifiDirectSocketRole
import gr.hua.aurora.wifidirect.socket.WifiDirectSocketState
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectedWifiDirectTransportHybridBootstrapSocketConnectorTest {
    @Test
    fun connectedTransportAcceptsMatchingConnectedSocketAndReadyAdapter() {
        val connector = ConnectedWifiDirectTransportHybridBootstrapSocketConnector(
            currentSocketDiagnostics = {
                connectedSocketDiagnostics(
                    host = "192.168.49.1",
                    port = 8988
                )
            },
            currentAdapterDiagnostics = {
                readyAdapterDiagnostics()
            },
            nowMillis = { 1_742_000_010L }
        )

        val result = connector.connect(validPlan())

        assertEquals(
            HybridBootstrapSocketConnectionResult.Connected(
                peerId = "peer-runtime",
                sessionId = "session-runtime",
                bootstrapIdentifier = "bootstrap-runtime",
                groupOwnerAddress = "192.168.49.1",
                socketPort = 8988,
                connectedAtMillis = 1_742_000_010L
            ),
            result
        )
    }

    @Test
    fun connectedTransportPreservesPlanFieldsExactly() {
        val connector = ConnectedWifiDirectTransportHybridBootstrapSocketConnector(
            currentSocketDiagnostics = {
                connectedSocketDiagnostics(
                    host = "192.168.49.77",
                    port = 9077
                )
            },
            currentAdapterDiagnostics = { readyAdapterDiagnostics() },
            nowMillis = { 1_742_000_011L }
        )

        val result = connector.connect(
            validPlan(
                peerId = "peer/Alpha+01",
                sessionId = "session:Beta|02",
                bootstrapIdentifier = "bootstrap==Gamma/03",
                groupOwnerAddress = "192.168.49.77",
                socketPort = 9077
            )
        )

        assertEquals(
            HybridBootstrapSocketConnectionResult.Connected(
                peerId = "peer/Alpha+01",
                sessionId = "session:Beta|02",
                bootstrapIdentifier = "bootstrap==Gamma/03",
                groupOwnerAddress = "192.168.49.77",
                socketPort = 9077,
                connectedAtMillis = 1_742_000_011L
            ),
            result
        )
    }

    @Test
    fun connectedTransportRejectsWhenSocketIsNotConnected() {
        val connector = ConnectedWifiDirectTransportHybridBootstrapSocketConnector(
            currentSocketDiagnostics = {
                connectedSocketDiagnostics(
                    isConnected = false,
                    isReadLoopActive = false
                )
            },
            currentAdapterDiagnostics = { readyAdapterDiagnostics() }
        )

        val result = connector.connect(validPlan())

        assertEquals(
            HybridBootstrapSocketConnectionResult.Failed(
                reason = "Hybrid bootstrap existing Wi-Fi Direct socket is not connected."
            ),
            result
        )
    }

    @Test
    fun connectedTransportRejectsWhenReadLoopIsInactive() {
        val connector = ConnectedWifiDirectTransportHybridBootstrapSocketConnector(
            currentSocketDiagnostics = {
                connectedSocketDiagnostics(
                    isReadLoopActive = false
                )
            },
            currentAdapterDiagnostics = { readyAdapterDiagnostics() }
        )

        val result = connector.connect(validPlan())

        assertEquals(
            HybridBootstrapSocketConnectionResult.Failed(
                reason = "Hybrid bootstrap existing Wi-Fi Direct read loop is not active."
            ),
            result
        )
    }

    @Test
    fun connectedTransportRejectsWhenAdapterIsNotReady() {
        val connector = ConnectedWifiDirectTransportHybridBootstrapSocketConnector(
            currentSocketDiagnostics = { connectedSocketDiagnostics() },
            currentAdapterDiagnostics = {
                WifiDirectTransportAdapterDiagnostics(
                    state = WifiDirectTransportAdapterState.NOT_READY,
                    notReadyReason = "Socket closed."
                )
            }
        )

        val result = connector.connect(validPlan())

        assertEquals(
            HybridBootstrapSocketConnectionResult.Failed(
                reason = "Hybrid bootstrap existing Wi-Fi Direct transport adapter is not ready: Socket closed."
            ),
            result
        )
    }

    @Test
    fun connectedTransportRejectsWhenConnectedAddressDoesNotMatchPlan() {
        val connector = ConnectedWifiDirectTransportHybridBootstrapSocketConnector(
            currentSocketDiagnostics = {
                connectedSocketDiagnostics(
                    host = "192.168.49.200"
                )
            },
            currentAdapterDiagnostics = { readyAdapterDiagnostics() }
        )

        val result = connector.connect(validPlan())

        assertEquals(
            HybridBootstrapSocketConnectionResult.Failed(
                reason = "Hybrid bootstrap existing Wi-Fi Direct endpoint mismatch: connected address 192.168.49.200 does not match requested 192.168.49.1."
            ),
            result
        )
    }

    @Test
    fun connectedTransportRejectsWhenConnectedPortDoesNotMatchPlan() {
        val connector = ConnectedWifiDirectTransportHybridBootstrapSocketConnector(
            currentSocketDiagnostics = {
                connectedSocketDiagnostics(
                    port = 9001
                )
            },
            currentAdapterDiagnostics = { readyAdapterDiagnostics() }
        )

        val result = connector.connect(validPlan())

        assertEquals(
            HybridBootstrapSocketConnectionResult.Failed(
                reason = "Hybrid bootstrap existing Wi-Fi Direct endpoint mismatch: connected port 9001 does not match requested 8988."
            ),
            result
        )
    }

    @Test
    fun connectedTransportDoesNotMutatePlan() {
        val connector = ConnectedWifiDirectTransportHybridBootstrapSocketConnector(
            currentSocketDiagnostics = { connectedSocketDiagnostics() },
            currentAdapterDiagnostics = { readyAdapterDiagnostics() }
        )
        val plan = validPlan()
        val before = plan.copy()

        val result = connector.connect(plan)

        assertTrue(result is HybridBootstrapSocketConnectionResult.Connected)
        assertEquals(before, plan)
    }

    @Test
    fun connectedTransportConnectorDoesNotCreateJavaNetSocketObjectsDirectly() {
        val source = sourceText(
            "app/src/main/java/gr/hua/aurora/transport/hybrid/ConnectedWifiDirectTransportHybridBootstrapSocketConnector.kt"
        )

        assertFalse(source.contains("import java.net.Socket"))
        assertFalse(source.contains("ServerSocket"))
    }

    private fun validPlan(
        peerId: String = "peer-runtime",
        sessionId: String = "session-runtime",
        bootstrapIdentifier: String = "bootstrap-runtime",
        groupOwnerAddress: String = "192.168.49.1",
        socketPort: Int = 8988
    ): HybridBootstrapSocketExecutionPlan {
        return HybridBootstrapSocketExecutionPlan(
            peerId = peerId,
            sessionId = sessionId,
            bootstrapIdentifier = bootstrapIdentifier,
            groupOwnerAddress = groupOwnerAddress,
            socketPort = socketPort,
            latestCreatedAtMillis = 1_742_000_000L,
            requestedAtMillis = 1_742_000_001L,
            commandCreatedAtMillis = 1_742_000_002L,
            connectTimeoutMillis = 5_000L
        )
    }

    private fun connectedSocketDiagnostics(
        host: String = "192.168.49.1",
        port: Int = 8988,
        isConnected: Boolean = true,
        isReadLoopActive: Boolean = true
    ): WifiDirectSocketDiagnostics {
        return WifiDirectSocketDiagnostics(
            state = WifiDirectSocketState.CONNECTED,
            role = WifiDirectSocketRole.CLIENT,
            endpoint = WifiDirectSocketEndpoint(
                host = host,
                port = port
            ),
            isConnected = isConnected,
            isReadLoopActive = isReadLoopActive
        )
    }

    private fun readyAdapterDiagnostics(): WifiDirectTransportAdapterDiagnostics {
        return WifiDirectTransportAdapterDiagnostics(
            state = WifiDirectTransportAdapterState.READY
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
