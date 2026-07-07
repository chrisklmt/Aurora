package gr.hua.aurora.wifidirect.socket

import java.net.InetSocketAddress
import java.net.Socket

internal class WifiDirectSocketClient(
    private val createClientSocket: () -> Socket,
    private val connectTimeoutMillis: Int
) {
    fun newSocket(): Socket {
        return createClientSocket()
    }

    fun connect(
        socket: Socket,
        host: String,
        port: Int
    ): Result<Unit> {
        return runCatching {
            socket.connect(
                InetSocketAddress(host, port),
                connectTimeoutMillis
            )
        }
    }

    fun close(
        socket: Socket?
    ) {
        runCatching {
            socket?.close()
        }
    }
}
