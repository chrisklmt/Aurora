package gr.hua.aurora.wifidirect

import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidWifiDirectSocketControllerTest {
    @Test
    fun socketStateDefaultsToIdle() {
        val controller = AndroidWifiDirectSocketController(requestedPort = 0)

        try {
            assertEquals(
                WifiDirectSocketState.IDLE,
                controller.currentDiagnostics().state
            )
            assertEquals(
                WifiDirectSocketRole.UNKNOWN,
                controller.currentDiagnostics().role
            )
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun serverTransitionsToListening() {
        val controller = AndroidWifiDirectSocketController(requestedPort = 0)

        try {
            controller.startServer(hostHint = "192.168.49.1")

            assertEquals(
                WifiDirectSocketCommand.START_SERVER,
                controller.currentDiagnostics().lastCommand
            )
            assertEquals(
                WifiDirectSocketCommandResult.STARTING,
                controller.currentDiagnostics().lastCommandResult
            )
            assertEquals(1, controller.currentDiagnostics().serverStartAttempts)

            awaitCondition {
                controller.currentDiagnostics().state == WifiDirectSocketState.SERVER_LISTENING
            }

            val diagnostics = controller.currentDiagnostics()
            assertEquals(WifiDirectSocketRole.SERVER, diagnostics.role)
            assertEquals("192.168.49.1", diagnostics.endpoint?.host)
            assertTrue((diagnostics.endpoint?.port ?: 0) > 0)
            assertEquals(WifiDirectSocketCommand.START_SERVER, diagnostics.lastCommand)
            assertEquals(WifiDirectSocketCommandResult.LISTENING, diagnostics.lastCommandResult)
            assertEquals(1, diagnostics.serverStartAttempts)
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun clientTransitionsThroughConnectingToConnectedAndCanSendDebugFrame() {
        val server = AndroidWifiDirectSocketController(requestedPort = 0)

        try {
            server.startServer(hostHint = "192.168.49.1")
            awaitCondition {
                server.currentDiagnostics().state == WifiDirectSocketState.SERVER_LISTENING
            }
            val listeningPort = requireNotNull(server.currentDiagnostics().endpoint?.port)
            val client = AndroidWifiDirectSocketController(requestedPort = listeningPort)

            try {
                client.connectClient("127.0.0.1")

                assertEquals(
                    WifiDirectSocketCommand.CONNECT_CLIENT,
                    client.currentDiagnostics().lastCommand
                )
                assertEquals(
                    WifiDirectSocketCommandResult.CONNECTING,
                    client.currentDiagnostics().lastCommandResult
                )
                assertEquals(
                    1,
                    client.currentDiagnostics().clientConnectAttempts
                )

                awaitCondition {
                    client.currentDiagnostics().state in setOf(
                        WifiDirectSocketState.CONNECTING,
                        WifiDirectSocketState.CONNECTED
                    )
                }
                awaitCondition {
                    client.currentDiagnostics().state == WifiDirectSocketState.CONNECTED &&
                        server.currentDiagnostics().state == WifiDirectSocketState.CONNECTED
                }

                client.sendDebugFrame()

                awaitCondition {
                    server.currentDiagnostics().lastReceivedMessage == "ping" &&
                        client.currentDiagnostics().lastReceivedMessage == "pong"
                }

                assertEquals(
                    WifiDirectSocketRole.CLIENT,
                    client.currentDiagnostics().role
                )
                assertEquals(
                    WifiDirectSocketCommand.CONNECT_CLIENT,
                    client.currentDiagnostics().lastCommand
                )
                assertEquals(
                    WifiDirectSocketCommandResult.CONNECTED,
                    client.currentDiagnostics().lastCommandResult
                )
                assertEquals(
                    1,
                    client.currentDiagnostics().clientConnectAttempts
                )
                assertEquals(
                    "ping",
                    client.currentDiagnostics().lastSentMessage
                )
                assertEquals(
                    "pong",
                    server.currentDiagnostics().lastSentMessage
                )
                assertEquals(
                    WifiDirectFrameTransportState.READY,
                    client.currentDiagnostics().frameDiagnostics.state
                )
                assertEquals(
                    1L,
                    client.currentDiagnostics().frameDiagnostics.framesSent
                )
                assertEquals(
                    1L,
                    client.currentDiagnostics().frameDiagnostics.framesReceived
                )
                assertEquals(
                    8L,
                    client.currentDiagnostics().frameDiagnostics.bytesSent
                )
                assertEquals(
                    8L,
                    client.currentDiagnostics().frameDiagnostics.bytesReceived
                )
                assertEquals(
                    4,
                    client.currentDiagnostics().frameDiagnostics.lastFrameSize
                )
                assertEquals(
                    4,
                    client.currentDiagnostics().lastOutboundFrameSize
                )
                assertEquals(
                    4,
                    client.currentDiagnostics().lastInboundFrameSize
                )
                assertTrue(client.currentDiagnostics().isReadLoopActive)
                assertTrue(server.currentDiagnostics().isReadLoopActive)
                assertTrue(server.isTransportFrameReady())
                assertTrue(client.isTransportFrameReady())
            } finally {
                client.dispose()
            }
        } finally {
            server.dispose()
        }
    }

    @Test
    fun sendFrameFailsClearlyWhenNotConnected() {
        val controller = AndroidWifiDirectSocketController(requestedPort = 0)

        try {
            controller.sendDebugFrame()

            assertEquals(
                "Debug frame transport not connected.",
                controller.currentDiagnostics().lastError
            )
            assertEquals(
                WifiDirectSocketState.IDLE,
                controller.currentDiagnostics().state
            )
            assertEquals(
                "Debug frame transport not connected.",
                controller.currentDiagnostics().frameDiagnostics.lastError
            )
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun closeIsSafeWhenIdleAndAlreadyClosed() {
        val controller = AndroidWifiDirectSocketController(requestedPort = 0)

        try {
            controller.closeSocket()
            controller.closeSocket()

            assertEquals(
                WifiDirectSocketState.IDLE,
                controller.currentDiagnostics().state
            )
            assertEquals(false, controller.currentDiagnostics().isConnected)
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun serverStartFailureMapsToFailedWithSafeError() {
        val controller = AndroidWifiDirectSocketController(
            requestedPort = 0,
            createServerSocket = {
                throw IOException("bind failed")
            }
        )

        try {
            controller.startServer(hostHint = "192.168.49.1")

            awaitCondition {
                controller.currentDiagnostics().state == WifiDirectSocketState.FAILED
            }

            assertEquals(
                "Debug socket server failed: IOException",
                controller.currentDiagnostics().lastError
            )
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun clientConnectFailureMapsToFailedWithSafeError() {
        val controller = AndroidWifiDirectSocketController(
            requestedPort = 8988,
            createClientSocket = {
                object : Socket() {
                    override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) {
                        throw IOException("connect failed")
                    }
                }
            }
        )

        try {
            controller.connectClient("127.0.0.1")

            awaitCondition {
                controller.currentDiagnostics().state == WifiDirectSocketState.FAILED
            }

            assertEquals(
                "Debug socket connect failed: IOException",
                controller.currentDiagnostics().lastError
            )
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun emptyClientHostMapsToBlockedDiagnostic() {
        val controller = AndroidWifiDirectSocketController(requestedPort = 8988)

        try {
            controller.connectClient("   ")

            val diagnostics = controller.currentDiagnostics()
            assertEquals(WifiDirectSocketState.IDLE, diagnostics.state)
            assertEquals(WifiDirectSocketCommand.CONNECT_CLIENT, diagnostics.lastCommand)
            assertEquals(WifiDirectSocketCommandResult.BLOCKED, diagnostics.lastCommandResult)
            assertEquals("Group owner address unavailable.", diagnostics.lastCommandError)
            assertEquals(1, diagnostics.clientConnectAttempts)
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun closeReleasesListeningSocketSoPortCanBeReused() {
        val port = availableLocalPort()
        val firstController = AndroidWifiDirectSocketController(requestedPort = port)

        try {
            firstController.startServer(hostHint = "192.168.49.1")
            awaitCondition {
                firstController.currentDiagnostics().state == WifiDirectSocketState.SERVER_LISTENING
            }

            firstController.closeSocket()
            awaitCondition {
                firstController.currentDiagnostics().state == WifiDirectSocketState.IDLE
            }

            val secondController = AndroidWifiDirectSocketController(requestedPort = port)
            try {
                secondController.startServer(hostHint = "192.168.49.1")
                awaitCondition {
                    secondController.currentDiagnostics().state == WifiDirectSocketState.SERVER_LISTENING
                }
                assertTrue((secondController.currentDiagnostics().endpoint?.port ?: 0) == port)
            } finally {
                secondController.dispose()
            }
        } finally {
            firstController.dispose()
        }
    }

    @Test
    fun repeatedStartServerWhileListeningIsBlockedWithoutResettingState() {
        val controller = AndroidWifiDirectSocketController(requestedPort = 0)

        try {
            controller.startServer(hostHint = "192.168.49.1")
            awaitCondition {
                controller.currentDiagnostics().state == WifiDirectSocketState.SERVER_LISTENING
            }

            controller.startServer(hostHint = "192.168.49.1")

            val diagnostics = controller.currentDiagnostics()
            assertEquals(WifiDirectSocketState.SERVER_LISTENING, diagnostics.state)
            assertEquals(WifiDirectSocketCommand.START_SERVER, diagnostics.lastCommand)
            assertEquals(WifiDirectSocketCommandResult.BLOCKED, diagnostics.lastCommandResult)
            assertEquals("Socket server already listening.", diagnostics.lastCommandError)
            assertEquals(2, diagnostics.serverStartAttempts)
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun closeWhileConnectedReturnsToIdleWithoutFrameFailure() {
        val server = AndroidWifiDirectSocketController(requestedPort = 0)

        try {
            server.startServer(hostHint = "192.168.49.1")
            awaitCondition {
                server.currentDiagnostics().state == WifiDirectSocketState.SERVER_LISTENING
            }
            val listeningPort = requireNotNull(server.currentDiagnostics().endpoint?.port)
            val client = AndroidWifiDirectSocketController(requestedPort = listeningPort)

            try {
                client.connectClient("127.0.0.1")
                awaitCondition {
                    client.currentDiagnostics().state == WifiDirectSocketState.CONNECTED &&
                        server.currentDiagnostics().state == WifiDirectSocketState.CONNECTED
                }

                client.closeSocket()

                awaitCondition {
                    client.currentDiagnostics().state == WifiDirectSocketState.IDLE
                }

                assertEquals(
                    WifiDirectFrameTransportState.IDLE,
                    client.currentDiagnostics().frameDiagnostics.state
                )
                assertEquals(
                    WifiDirectSocketCommand.CLOSE_SOCKET,
                    client.currentDiagnostics().lastCommand
                )
                assertEquals(
                    WifiDirectSocketCommandResult.CLOSED,
                    client.currentDiagnostics().lastCommandResult
                )
            } finally {
                client.dispose()
            }
        } finally {
            server.dispose()
        }
    }

    @Test
    fun repeatedConnectClientWhileConnectedIsBlockedWithoutResettingState() {
        val server = AndroidWifiDirectSocketController(requestedPort = 0)

        try {
            server.startServer(hostHint = "192.168.49.1")
            awaitCondition {
                server.currentDiagnostics().state == WifiDirectSocketState.SERVER_LISTENING
            }
            val listeningPort = requireNotNull(server.currentDiagnostics().endpoint?.port)
            val client = AndroidWifiDirectSocketController(requestedPort = listeningPort)

            try {
                client.connectClient("127.0.0.1")
                awaitCondition {
                    client.currentDiagnostics().state == WifiDirectSocketState.CONNECTED &&
                        server.currentDiagnostics().state == WifiDirectSocketState.CONNECTED
                }

                client.connectClient("127.0.0.1")

                val diagnostics = client.currentDiagnostics()
                assertEquals(WifiDirectSocketState.CONNECTED, diagnostics.state)
                assertEquals(WifiDirectSocketCommand.CONNECT_CLIENT, diagnostics.lastCommand)
                assertEquals(WifiDirectSocketCommandResult.BLOCKED, diagnostics.lastCommandResult)
                assertEquals("Socket already connected.", diagnostics.lastCommandError)
                assertEquals(2, diagnostics.clientConnectAttempts)
            } finally {
                client.dispose()
            }
        } finally {
            server.dispose()
        }
    }

    @Test
    fun resetDiagnosticsWhileConnectedClearsCountersWithoutDisconnecting() {
        val server = AndroidWifiDirectSocketController(requestedPort = 0)

        try {
            server.startServer(hostHint = "192.168.49.1")
            awaitCondition {
                server.currentDiagnostics().state == WifiDirectSocketState.SERVER_LISTENING
            }
            val listeningPort = requireNotNull(server.currentDiagnostics().endpoint?.port)
            val client = AndroidWifiDirectSocketController(requestedPort = listeningPort)

            try {
                client.connectClient("127.0.0.1")
                awaitCondition {
                    client.currentDiagnostics().state == WifiDirectSocketState.CONNECTED &&
                        server.currentDiagnostics().state == WifiDirectSocketState.CONNECTED
                }

                client.sendDebugFrame()

                awaitCondition {
                    client.currentDiagnostics().lastReceivedMessage == "pong"
                }

                client.resetDiagnostics()

                assertEquals(WifiDirectSocketState.CONNECTED, client.currentDiagnostics().state)
                assertTrue(client.currentDiagnostics().isConnected)
                assertEquals(WifiDirectFrameTransportState.READY, client.currentDiagnostics().frameDiagnostics.state)
                assertEquals(0L, client.currentDiagnostics().bytesSent)
                assertEquals(0L, client.currentDiagnostics().bytesReceived)
                assertEquals(0L, client.currentDiagnostics().frameDiagnostics.framesSent)
                assertEquals(0L, client.currentDiagnostics().frameDiagnostics.framesReceived)
                assertEquals(null, client.currentDiagnostics().lastSentMessage)
                assertEquals(null, client.currentDiagnostics().lastReceivedMessage)
                assertEquals(null, client.currentDiagnostics().lastError)
            } finally {
                client.dispose()
            }
        } finally {
            server.dispose()
        }
    }

    private fun awaitCondition(
        timeoutMillis: Long = 5_000L,
        condition: () -> Boolean
    ) {
        val startMillis = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - startMillis > timeoutMillis) {
                throw AssertionError("Timed out waiting for socket condition.")
            }
            Thread.sleep(25L)
        }
    }

    private fun availableLocalPort(): Int {
        return ServerSocket(0).use { serverSocket ->
            serverSocket.localPort
        }
    }
}
