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
}
