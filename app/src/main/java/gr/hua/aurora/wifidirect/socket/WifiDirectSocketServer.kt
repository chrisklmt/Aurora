package gr.hua.aurora.wifidirect.socket

import java.net.ServerSocket
import java.net.Socket

internal class WifiDirectSocketServer(
    private val requestedPort: Int,
    private val createServerSocket: (Int) -> ServerSocket
) {
    fun openListeningSocket(): Result<ServerSocket> {
        return runCatching {
            createServerSocket(requestedPort)
        }
    }

    fun acceptClient(
        listeningSocket: ServerSocket
    ): Result<Socket> {
        return runCatching {
            listeningSocket.accept()
        }
    }

    fun close(
        listeningSocket: ServerSocket?
    ) {
        runCatching {
            listeningSocket?.close()
        }
    }
}
