package gr.hua.aurora.wifidirect

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val wifiDirectDebugPingMessage = "ping"
private const val wifiDirectDebugPongMessage = "pong"
private const val wifiDirectSocketConnectTimeoutMillis = 5_000

internal class AndroidWifiDirectSocketController internal constructor(
    private val requestedPort: Int = wifiDirectDebugSocketPort,
    private val createServerSocket: (Int) -> ServerSocket = { port ->
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
    },
    private val createClientSocket: () -> Socket = { Socket() },
    private val connectTimeoutMillis: Int = wifiDirectSocketConnectTimeoutMillis,
    private val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : WifiDirectSocketController {

    private val listeners = linkedSetOf<WifiDirectSocketController.Listener>()
    private val stateLock = Any()

    private var diagnostics = WifiDirectSocketDiagnostics(
        endpoint = WifiDirectSocketEndpoint(port = requestedPort.takeIf { it > 0 })
    )
    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private var operationToken: Long = 0L
    private var isDisposed: Boolean = false

    override fun currentDiagnostics(): WifiDirectSocketDiagnostics {
        return synchronized(stateLock) { diagnostics }
    }

    override fun startServer(hostHint: String?) {
        val trimmedHostHint = hostHint?.trim()?.takeIf { it.isNotEmpty() }
        val token = nextOperationToken()
        ioScope.launch {
            releaseResources()
            updateDiagnosticsIfCurrent(token) { current ->
                current.copy(
                    state = WifiDirectSocketState.STARTING_SERVER,
                    role = WifiDirectSocketRole.SERVER,
                    endpoint = WifiDirectSocketEndpoint(
                        host = trimmedHostHint,
                        port = current.endpoint?.port ?: requestedPort.takeIf { it > 0 }
                    ),
                    isConnected = false,
                    lastError = null
                )
            }
            val listeningSocket = runCatching {
                createServerSocket(requestedPort)
            }.getOrElse { error ->
                failSocket(
                    token = token,
                    reason = "Debug socket server failed: ${error::class.java.simpleName}"
                )
                return@launch
            }
            var canContinue = true
            synchronized(stateLock) {
                if (!isCurrentTokenLocked(token)) {
                    runCatching { listeningSocket.close() }
                    canContinue = false
                } else {
                    serverSocket = listeningSocket
                }
            }
            if (!canContinue) {
                return@launch
            }
            updateDiagnosticsIfCurrent(token) { current ->
                current.copy(
                    state = WifiDirectSocketState.SERVER_LISTENING,
                    role = WifiDirectSocketRole.SERVER,
                    endpoint = WifiDirectSocketEndpoint(
                        host = trimmedHostHint,
                        port = listeningSocket.localPort
                    ),
                    isConnected = false,
                    lastError = null
                )
            }
            acceptClient(token, listeningSocket)
        }
    }

    override fun connectClient(host: String) {
        val trimmedHost = host.trim()
        if (trimmedHost.isEmpty()) {
            updateDiagnostics { current ->
                current.copy(
                    state = WifiDirectSocketState.FAILED,
                    role = WifiDirectSocketRole.CLIENT,
                    isConnected = false,
                    lastError = "Group owner address unavailable."
                )
            }
            return
        }

        val token = nextOperationToken()
        ioScope.launch {
            releaseResources()
            updateDiagnosticsIfCurrent(token) { current ->
                current.copy(
                    state = WifiDirectSocketState.CONNECTING,
                    role = WifiDirectSocketRole.CLIENT,
                    endpoint = WifiDirectSocketEndpoint(
                        host = trimmedHost,
                        port = current.endpoint?.port ?: requestedPort.takeIf { it > 0 }
                    ),
                    isConnected = false,
                    lastError = null
                )
            }
            val targetPort = currentDiagnostics().endpoint?.port ?: requestedPort
            val clientSocket = createClientSocket()
            var canContinue = true
            synchronized(stateLock) {
                if (!isCurrentTokenLocked(token)) {
                    runCatching { clientSocket.close() }
                    canContinue = false
                } else {
                    socket = clientSocket
                }
            }
            if (!canContinue) {
                return@launch
            }
            runCatching {
                clientSocket.connect(InetSocketAddress(trimmedHost, targetPort), connectTimeoutMillis)
            }.getOrElse { error ->
                failSocket(
                    token = token,
                    reason = "Debug socket connect failed: ${error::class.java.simpleName}"
                )
                return@launch
            }
            updateDiagnosticsIfCurrent(token) { current ->
                current.copy(
                    state = WifiDirectSocketState.CONNECTED,
                    role = WifiDirectSocketRole.CLIENT,
                    endpoint = WifiDirectSocketEndpoint(
                        host = trimmedHost,
                        port = targetPort
                    ),
                    isConnected = true,
                    lastError = null
                )
            }
            startReadLoop(token, clientSocket)
        }
    }

    override fun sendDebugPing() {
        val token = synchronized(stateLock) { operationToken }
        if (!currentDiagnostics().isConnected) {
            updateDiagnostics { current ->
                current.copy(lastError = "Debug socket not connected.")
            }
            return
        }
        ioScope.launch {
            sendMessage(
                token = token,
                message = wifiDirectDebugPingMessage
            )
        }
    }

    override fun closeSocket() {
        val token = nextOperationToken()
        updateDiagnosticsIfCurrent(token) { current ->
            current.copy(
                state = WifiDirectSocketState.CLOSING,
                isConnected = false,
                lastError = null
            )
        }
        releaseResources()
        updateDiagnosticsIfCurrent(token) { current ->
            current.copy(
                state = WifiDirectSocketState.IDLE,
                isConnected = false
            )
        }
    }

    override fun addListener(listener: WifiDirectSocketController.Listener) {
        listeners += listener
    }

    override fun removeListener(listener: WifiDirectSocketController.Listener) {
        listeners -= listener
    }

    override fun dispose() {
        synchronized(stateLock) {
            isDisposed = true
            operationToken += 1L
        }
        releaseResources()
        ioScope.cancel()
        listeners.clear()
    }

    private fun acceptClient(
        token: Long,
        listeningSocket: ServerSocket
    ) {
        val acceptedSocket = runCatching {
            listeningSocket.accept()
        }.getOrElse { error ->
            if (isCurrentToken(token)) {
                failSocket(
                    token = token,
                    reason = "Debug socket accept failed: ${error::class.java.simpleName}"
                )
            }
            return
        }
        var canContinue = true
        synchronized(stateLock) {
            if (!isCurrentTokenLocked(token)) {
                runCatching { acceptedSocket.close() }
                canContinue = false
            } else {
                serverSocket = null
                socket = acceptedSocket
            }
        }
        if (!canContinue) {
            return
        }
        runCatching { listeningSocket.close() }
        updateDiagnosticsIfCurrent(token) { current ->
            current.copy(
                state = WifiDirectSocketState.CONNECTED,
                role = WifiDirectSocketRole.SERVER,
                isConnected = true,
                lastError = null
            )
        }
        startReadLoop(token, acceptedSocket)
    }

    private fun startReadLoop(
        token: Long,
        activeSocket: Socket
    ) {
        ioScope.launch {
            val reader = BufferedReader(
                InputStreamReader(activeSocket.getInputStream(), StandardCharsets.UTF_8)
            )
            while (isCurrentToken(token)) {
                val line = runCatching {
                    reader.readLine()
                }.getOrElse { error ->
                    if (isCurrentToken(token)) {
                        failSocket(
                            token = token,
                            reason = "Debug socket read failed: ${error::class.java.simpleName}"
                        )
                    }
                    return@launch
                }
                if (line == null) {
                    releaseResources()
                    updateDiagnosticsIfCurrent(token) { current ->
                        current.copy(
                            state = WifiDirectSocketState.IDLE,
                            isConnected = false
                        )
                    }
                    return@launch
                }
                val receivedBytes = messageBytes(line).size.toLong()
                updateDiagnosticsIfCurrent(token) { current ->
                    current.copy(
                        lastReceivedMessage = line,
                        bytesReceived = current.bytesReceived + receivedBytes,
                        lastError = null
                    )
                }
                if (line == wifiDirectDebugPingMessage) {
                    sendMessage(
                        token = token,
                        message = wifiDirectDebugPongMessage
                    )
                }
            }
        }
    }

    private fun sendMessage(
        token: Long,
        message: String
    ) {
        val activeSocket = synchronized(stateLock) {
            if (isCurrentTokenLocked(token)) {
                socket
            } else {
                null
            }
        } ?: run {
            updateDiagnosticsIfCurrent(token) { current ->
                current.copy(lastError = "Debug socket not connected.")
            }
            return
        }

        val bytes = messageBytes(message)
        runCatching {
            val writer = BufferedWriter(
                OutputStreamWriter(activeSocket.getOutputStream(), StandardCharsets.UTF_8)
            )
            writer.write(message)
            writer.newLine()
            writer.flush()
        }.onSuccess {
            updateDiagnosticsIfCurrent(token) { current ->
                current.copy(
                    lastSentMessage = message,
                    bytesSent = current.bytesSent + bytes.size,
                    lastError = null
                )
            }
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
        updateDiagnosticsIfCurrent(token) { current ->
            current.copy(
                state = WifiDirectSocketState.FAILED,
                isConnected = false,
                lastError = reason
            )
        }
    }

    private fun releaseResources() {
        val serverToClose: ServerSocket?
        val socketToClose: Socket?
        synchronized(stateLock) {
            serverToClose = serverSocket
            socketToClose = socket
            serverSocket = null
            socket = null
        }
        runCatching { socketToClose?.close() }
        runCatching { serverToClose?.close() }
    }

    private fun nextOperationToken(): Long {
        return synchronized(stateLock) {
            if (!isDisposed) {
                operationToken += 1L
            }
            operationToken
        }
    }

    private fun isCurrentToken(
        token: Long
    ): Boolean {
        return synchronized(stateLock) {
            isCurrentTokenLocked(token)
        }
    }

    private fun isCurrentTokenLocked(
        token: Long
    ): Boolean {
        return !isDisposed && operationToken == token
    }

    private fun updateDiagnostics(
        transform: (WifiDirectSocketDiagnostics) -> WifiDirectSocketDiagnostics
    ) {
        val updated = synchronized(stateLock) {
            diagnostics = transform(diagnostics)
            diagnostics
        }
        emit(updated)
    }

    private fun updateDiagnosticsIfCurrent(
        token: Long,
        transform: (WifiDirectSocketDiagnostics) -> WifiDirectSocketDiagnostics
    ) {
        val updated = synchronized(stateLock) {
            if (!isCurrentTokenLocked(token)) {
                null
            } else {
                diagnostics = transform(diagnostics)
                diagnostics
            }
        } ?: return
        emit(updated)
    }

    private fun emit(
        updated: WifiDirectSocketDiagnostics
    ) {
        listeners.forEach { listener ->
            listener.onSocketDiagnosticsChanged(updated)
        }
    }

    private fun messageBytes(
        message: String
    ): ByteArray {
        return "$message\n".toByteArray(StandardCharsets.UTF_8)
    }
}
