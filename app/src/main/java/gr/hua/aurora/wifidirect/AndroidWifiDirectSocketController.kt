package gr.hua.aurora.wifidirect

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val wifiDirectSocketConnectTimeoutMillis = 5_000
private const val wifiDirectDebugPingMessage = "ping"

internal class AndroidWifiDirectSocketController internal constructor(
    private val requestedPort: Int = wifiDirectDebugSocketPort,
    createServerSocket: (Int) -> ServerSocket = { port ->
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
    },
    createClientSocket: () -> Socket = { Socket() },
    connectTimeoutMillis: Int = wifiDirectSocketConnectTimeoutMillis,
    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : WifiDirectSocketController {

    private val listeners = linkedSetOf<WifiDirectSocketController.Listener>()
    private val resourceLock = Any()
    private val stateMachine = WifiDirectSocketStateMachine(initialPort = requestedPort)
    private val socketServer = WifiDirectSocketServer(
        requestedPort = requestedPort,
        createServerSocket = createServerSocket
    )
    private val socketClient = WifiDirectSocketClient(
        createClientSocket = createClientSocket,
        connectTimeoutMillis = connectTimeoutMillis
    )
    private val socketIoLoop = WifiDirectSocketIoLoop()

    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null

    override fun currentDiagnostics(): WifiDirectSocketDiagnostics {
        return stateMachine.currentDiagnostics()
    }

    override fun startServer(hostHint: String?) {
        val trimmedHostHint = hostHint?.trim()?.takeIf { it.isNotEmpty() }
        val token = stateMachine.nextOperationToken()
        ioScope.launch {
            releaseResources()
            emitIfPresent(
                stateMachine.markStartingServer(
                    token = token,
                    hostHint = trimmedHostHint,
                    requestedPort = requestedPort
                )
            )
            val listeningSocket = socketServer.openListeningSocket().getOrElse { error ->
                failSocket(
                    token = token,
                    reason = "Debug socket server failed: ${error::class.java.simpleName}"
                )
                return@launch
            }
            if (!adoptServerSocket(token, listeningSocket)) {
                socketServer.close(listeningSocket)
                return@launch
            }
            emitIfPresent(
                stateMachine.markServerListening(
                    token = token,
                    hostHint = trimmedHostHint,
                    port = listeningSocket.localPort
                )
            )
            acceptClient(token, listeningSocket)
        }
    }

    override fun connectClient(host: String) {
        val trimmedHost = host.trim()
        if (trimmedHost.isEmpty()) {
            emit(
                stateMachine.markImmediateFailure(
                    role = WifiDirectSocketRole.CLIENT,
                    reason = "Group owner address unavailable.",
                    endpoint = WifiDirectSocketEndpoint(
                        port = requestedPort.takeIf { it > 0 }
                    )
                )
            )
            return
        }

        val token = stateMachine.nextOperationToken()
        ioScope.launch {
            releaseResources()
            emitIfPresent(
                stateMachine.markConnectingClient(
                    token = token,
                    host = trimmedHost,
                    requestedPort = requestedPort
                )
            )
            val targetPort = currentDiagnostics().endpoint?.port ?: requestedPort
            val clientSocket = socketClient.newSocket()
            if (!adoptClientSocket(token, clientSocket)) {
                socketClient.close(clientSocket)
                return@launch
            }
            socketClient.connect(
                socket = clientSocket,
                host = trimmedHost,
                port = targetPort
            ).getOrElse { error ->
                failSocket(
                    token = token,
                    reason = "Debug socket connect failed: ${error::class.java.simpleName}"
                )
                return@launch
            }
            emitIfPresent(
                stateMachine.markConnected(
                    token = token,
                    role = WifiDirectSocketRole.CLIENT,
                    endpoint = WifiDirectSocketEndpoint(
                        host = trimmedHost,
                        port = targetPort
                    )
                )
            )
            startReadLoop(token, clientSocket)
        }
    }

    override fun sendDebugPing() {
        if (!currentDiagnostics().isConnected) {
            emit(stateMachine.recordNotConnectedError())
            return
        }
        val token = currentTokenSnapshot()
        ioScope.launch {
            sendMessage(
                token = token,
                message = wifiDirectDebugPingMessage
            )
        }
    }

    override fun closeSocket() {
        val token = stateMachine.nextOperationToken()
        emitIfPresent(stateMachine.markClosing(token))
        releaseResources()
        emitIfPresent(stateMachine.markIdle(token))
    }

    override fun addListener(listener: WifiDirectSocketController.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: WifiDirectSocketController.Listener) {
        listeners -= listener
    }

    override fun dispose() {
        stateMachine.markDisposed()
        releaseResources()
        ioScope.cancel()
        listeners.clear()
    }

    private fun acceptClient(
        token: Long,
        listeningSocket: ServerSocket
    ) {
        val acceptedSocket = socketServer.acceptClient(listeningSocket).getOrElse { error ->
            if (stateMachine.isCurrentToken(token)) {
                failSocket(
                    token = token,
                    reason = "Debug socket accept failed: ${error::class.java.simpleName}"
                )
            }
            return
        }
        if (!adoptAcceptedSocket(token, acceptedSocket)) {
            socketClient.close(acceptedSocket)
            return
        }
        socketServer.close(listeningSocket)
        emitIfPresent(
            stateMachine.markConnected(
                token = token,
                role = WifiDirectSocketRole.SERVER
            )
        )
        startReadLoop(token, acceptedSocket)
    }

    private fun startReadLoop(
        token: Long,
        activeSocket: Socket
    ) {
        ioScope.launch {
            socketIoLoop.readUntilClosed(
                socket = activeSocket,
                isActive = { stateMachine.isCurrentToken(token) },
                onIncomingMessage = { incomingMessage ->
                    emitIfPresent(
                        stateMachine.recordReceivedMessage(
                            token = token,
                            message = incomingMessage.text,
                            bytesReceived = incomingMessage.byteCount
                        )
                    )
                    incomingMessage.autoReply?.let { reply ->
                        sendMessage(token, reply)
                    }
                },
                onClosed = {
                    releaseResources()
                    emitIfPresent(stateMachine.markIdle(token))
                },
                onFailure = { error ->
                    if (stateMachine.isCurrentToken(token)) {
                        failSocket(
                            token = token,
                            reason = "Debug socket read failed: ${error::class.java.simpleName}"
                        )
                    }
                }
            )
        }
    }

    private fun sendMessage(
        token: Long,
        message: String
    ) {
        val activeSocket = currentSocket(token) ?: run {
            emit(stateMachine.recordNotConnectedError())
            return
        }

        socketIoLoop.writeLine(
            socket = activeSocket,
            message = message
        ).onSuccess { byteCount ->
            emitIfPresent(
                stateMachine.recordSentMessage(
                    token = token,
                    message = message,
                    bytesSent = byteCount
                )
            )
        }.onFailure { error ->
            failSocket(
                token = token,
                reason = "Debug socket send failed: ${error::class.java.simpleName}"
            )
        }
    }

    private fun failSocket(
        token: Long,
        reason: String
    ) {
        releaseResources()
        emitIfPresent(stateMachine.markFailed(token, reason))
    }

    private fun releaseResources() {
        val serverToClose: ServerSocket?
        val socketToClose: Socket?
        synchronized(resourceLock) {
            serverToClose = serverSocket
            socketToClose = socket
            serverSocket = null
            socket = null
        }
        socketClient.close(socketToClose)
        socketServer.close(serverToClose)
    }

    private fun adoptServerSocket(
        token: Long,
        listeningSocket: ServerSocket
    ): Boolean {
        synchronized(resourceLock) {
            if (!stateMachine.isCurrentToken(token)) {
                return false
            }
            serverSocket = listeningSocket
            return true
        }
    }

    private fun adoptClientSocket(
        token: Long,
        clientSocket: Socket
    ): Boolean {
        synchronized(resourceLock) {
            if (!stateMachine.isCurrentToken(token)) {
                return false
            }
            socket = clientSocket
            return true
        }
    }

    private fun adoptAcceptedSocket(
        token: Long,
        acceptedSocket: Socket
    ): Boolean {
        synchronized(resourceLock) {
            if (!stateMachine.isCurrentToken(token)) {
                return false
            }
            serverSocket = null
            socket = acceptedSocket
            return true
        }
    }

    private fun currentSocket(
        token: Long
    ): Socket? {
        synchronized(resourceLock) {
            if (!stateMachine.isCurrentToken(token)) {
                return null
            }
            return socket
        }
    }

    private fun currentTokenSnapshot(): Long {
        return stateMachine.currentOperationToken()
    }

    private fun emitIfPresent(
        diagnostics: WifiDirectSocketDiagnostics?
    ) {
        diagnostics?.let(::emit)
    }

    private fun emit(
        updated: WifiDirectSocketDiagnostics
    ) {
        listeners.forEach { listener ->
            listener.onSocketDiagnosticsChanged(updated)
        }
    }
}
