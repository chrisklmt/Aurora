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

            awaitCondition {
                controller.currentDiagnostics().state == WifiDirectSocketState.SERVER_LISTENING
            }

            val diagnostics = controller.currentDiagnostics()
            assertEquals(WifiDirectSocketRole.SERVER, diagnostics.role)
            assertEquals("192.168.49.1", diagnostics.endpoint?.host)
            assertTrue((diagnostics.endpoint?.port ?: 0) > 0)
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
