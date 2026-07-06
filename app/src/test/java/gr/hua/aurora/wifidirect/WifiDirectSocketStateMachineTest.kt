package gr.hua.aurora.wifidirect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDirectSocketStateMachineTest {
    @Test
    fun defaultsToIdleWithConfiguredPort() {
        val stateMachine = WifiDirectSocketStateMachine(initialPort = 8988)

        assertEquals(
            WifiDirectSocketState.IDLE,
            stateMachine.currentDiagnostics().state
        )
        assertEquals(
            8988,
            stateMachine.currentDiagnostics().endpoint?.port
        )
        assertEquals(
            WifiDirectSocketCommand.NONE,
            stateMachine.currentDiagnostics().lastCommand
        )
    }

    @Test
    fun serverTransitionsRemainStable() {
        val stateMachine = WifiDirectSocketStateMachine(initialPort = 8988)
        val token = stateMachine.nextOperationToken()

        assertEquals(
            WifiDirectSocketState.STARTING_SERVER,
            requireNotNull(
                stateMachine.markStartingServer(
                    token = token,
                    hostHint = "192.168.49.1",
                    requestedPort = 8988
                )
            ).state
        )
        assertEquals(
            WifiDirectSocketState.SERVER_LISTENING,
            requireNotNull(
                stateMachine.markServerListening(
                    token = token,
                    hostHint = "192.168.49.1",
                    port = 8999
                )
            ).state
        )
        assertEquals(
            WifiDirectSocketState.CONNECTED,
            requireNotNull(
                stateMachine.markConnected(
                    token = token,
                    role = WifiDirectSocketRole.SERVER
                )
            ).state
        )
        val diagnostics = stateMachine.currentDiagnostics()
        assertEquals(WifiDirectSocketCommand.START_SERVER, diagnostics.lastCommand)
        assertEquals(WifiDirectSocketCommandResult.CONNECTED, diagnostics.lastCommandResult)
        assertEquals(1, diagnostics.serverStartAttempts)
    }

    @Test
    fun sentAndReceivedFrameCountersAccumulate() {
        val stateMachine = WifiDirectSocketStateMachine(initialPort = 8988)
        val token = stateMachine.nextOperationToken()

        stateMachine.markConnected(
            token = token,
            role = WifiDirectSocketRole.CLIENT,
            endpoint = WifiDirectSocketEndpoint(
                host = "127.0.0.1",
                port = 8988
            )
        )
        stateMachine.recordSentFrame(
            token = token,
            message = "ping",
            frameSize = 4,
            bytesSent = 8
        )
        val diagnostics = requireNotNull(
            stateMachine.recordReceivedFrame(
                token = token,
                message = "pong",
                frameSize = 4,
                bytesReceived = 8
            )
        )

        assertEquals("ping", diagnostics.lastSentMessage)
        assertEquals("pong", diagnostics.lastReceivedMessage)
        assertEquals(8L, diagnostics.bytesSent)
        assertEquals(8L, diagnostics.bytesReceived)
        assertEquals(
            WifiDirectFrameTransportState.READY,
            diagnostics.frameDiagnostics.state
        )
        assertEquals(1L, diagnostics.frameDiagnostics.framesSent)
        assertEquals(1L, diagnostics.frameDiagnostics.framesReceived)
        assertEquals(4, diagnostics.frameDiagnostics.lastFrameSize)
    }

    @Test
    fun disposedStateRejectsOldTokens() {
        val stateMachine = WifiDirectSocketStateMachine(initialPort = 8988)
        val token = stateMachine.nextOperationToken()

        stateMachine.markDisposed()

        assertFalse(stateMachine.isCurrentToken(token))
        assertNull(
            stateMachine.markIdle(token)
        )
    }

    @Test
    fun immediateFailureCapturesSafeReason() {
        val stateMachine = WifiDirectSocketStateMachine(initialPort = 8988)

        val diagnostics = stateMachine.markImmediateFailure(
            role = WifiDirectSocketRole.CLIENT,
            reason = "Group owner address unavailable.",
            endpoint = WifiDirectSocketEndpoint(port = 8988)
        )

        assertEquals(WifiDirectSocketState.FAILED, diagnostics.state)
        assertEquals(WifiDirectSocketRole.CLIENT, diagnostics.role)
        assertEquals("Group owner address unavailable.", diagnostics.lastError)
        assertEquals(WifiDirectSocketCommandResult.FAILED, diagnostics.lastCommandResult)
        assertEquals("Group owner address unavailable.", diagnostics.lastCommandError)
        assertTrue(diagnostics.endpoint?.port == 8988)
        assertEquals(
            WifiDirectFrameTransportState.FAILED,
            diagnostics.frameDiagnostics.state
        )
    }

    @Test
    fun resetDiagnosticsKeepsConnectionStateButClearsCounters() {
        val stateMachine = WifiDirectSocketStateMachine(initialPort = 8988)
        val token = stateMachine.nextOperationToken()

        stateMachine.markConnected(
            token = token,
            role = WifiDirectSocketRole.CLIENT,
            endpoint = WifiDirectSocketEndpoint(
                host = "127.0.0.1",
                port = 8988
            )
        )
        stateMachine.recordSentFrame(
            token = token,
            message = "ping",
            frameSize = 4,
            bytesSent = 8
        )
        stateMachine.recordReceivedFrame(
            token = token,
            message = "pong",
            frameSize = 4,
            bytesReceived = 8
        )

        val diagnostics = stateMachine.resetDiagnostics()

        assertEquals(WifiDirectSocketState.CONNECTED, diagnostics.state)
        assertEquals(WifiDirectSocketRole.CLIENT, diagnostics.role)
        assertTrue(diagnostics.isConnected)
        assertNull(diagnostics.lastSentMessage)
        assertNull(diagnostics.lastReceivedMessage)
        assertNull(diagnostics.lastError)
        assertEquals(WifiDirectSocketCommand.NONE, diagnostics.lastCommand)
        assertEquals(WifiDirectSocketCommandResult.NONE, diagnostics.lastCommandResult)
        assertEquals(0L, diagnostics.bytesSent)
        assertEquals(0L, diagnostics.bytesReceived)
        assertEquals(WifiDirectFrameTransportState.READY, diagnostics.frameDiagnostics.state)
        assertEquals(0L, diagnostics.frameDiagnostics.framesSent)
        assertEquals(0L, diagnostics.frameDiagnostics.framesReceived)
        assertEquals(0L, diagnostics.frameDiagnostics.bytesSent)
        assertEquals(0L, diagnostics.frameDiagnostics.bytesReceived)
        assertNull(diagnostics.frameDiagnostics.lastFrameSize)
        assertNull(diagnostics.frameDiagnostics.lastError)
    }

    @Test
    fun clientCommandDiagnosticsTrackHostAndAttemptCount() {
        val stateMachine = WifiDirectSocketStateMachine(initialPort = 8988)
        val token = stateMachine.nextOperationToken()

        val connecting = requireNotNull(
            stateMachine.markConnectingClient(
                token = token,
                host = "192.168.49.1",
                requestedPort = 8988
            )
        )

        assertEquals(WifiDirectSocketCommand.CONNECT_CLIENT, connecting.lastCommand)
        assertEquals(WifiDirectSocketCommandResult.CONNECTING, connecting.lastCommandResult)
        assertEquals("192.168.49.1", connecting.lastCommandHost)
        assertEquals(1, connecting.clientConnectAttempts)

        val connected = requireNotNull(
            stateMachine.markConnected(
                token = token,
                role = WifiDirectSocketRole.CLIENT,
                endpoint = WifiDirectSocketEndpoint(
                    host = "192.168.49.1",
                    port = 8988
                )
            )
        )

        assertEquals(WifiDirectSocketCommandResult.CONNECTED, connected.lastCommandResult)
        assertEquals("192.168.49.1", connected.lastCommandHost)
    }

    @Test
    fun blockedClientCommandTracksAttemptAndReason() {
        val stateMachine = WifiDirectSocketStateMachine(initialPort = 8988)

        val diagnostics = stateMachine.markBlocked(
            command = WifiDirectSocketCommand.CONNECT_CLIENT,
            role = WifiDirectSocketRole.CLIENT,
            reason = "Group owner address unavailable.",
            endpoint = WifiDirectSocketEndpoint(port = 8988)
        )

        assertEquals(WifiDirectSocketState.IDLE, diagnostics.state)
        assertEquals(WifiDirectSocketCommand.CONNECT_CLIENT, diagnostics.lastCommand)
        assertEquals(WifiDirectSocketCommandResult.BLOCKED, diagnostics.lastCommandResult)
        assertEquals("Group owner address unavailable.", diagnostics.lastCommandError)
        assertEquals("Group owner address unavailable.", diagnostics.lastError)
        assertEquals(1, diagnostics.clientConnectAttempts)
    }

    @Test
    fun blockedCommandInCurrentStatePreservesConnectionState() {
        val stateMachine = WifiDirectSocketStateMachine(initialPort = 8988)
        val token = stateMachine.nextOperationToken()

        stateMachine.markConnected(
            token = token,
            role = WifiDirectSocketRole.CLIENT,
            endpoint = WifiDirectSocketEndpoint(
                host = "192.168.49.1",
                port = 8988
            )
        )

        val diagnostics = stateMachine.markBlockedInCurrentState(
            command = WifiDirectSocketCommand.CONNECT_CLIENT,
            reason = "Socket already connected.",
            host = "192.168.49.1"
        )

        assertEquals(WifiDirectSocketState.CONNECTED, diagnostics.state)
        assertEquals(WifiDirectSocketRole.CLIENT, diagnostics.role)
        assertTrue(diagnostics.isConnected)
        assertEquals(WifiDirectSocketCommand.CONNECT_CLIENT, diagnostics.lastCommand)
        assertEquals(WifiDirectSocketCommandResult.BLOCKED, diagnostics.lastCommandResult)
        assertEquals("Socket already connected.", diagnostics.lastCommandError)
        assertEquals("Socket already connected.", diagnostics.lastError)
        assertEquals("192.168.49.1", diagnostics.lastCommandHost)
        assertEquals(1, diagnostics.clientConnectAttempts)
    }
}
