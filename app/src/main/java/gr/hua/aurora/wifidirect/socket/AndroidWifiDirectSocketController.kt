package gr.hua.aurora.wifidirect.socket

import android.util.Log
import gr.hua.aurora.wifidirect.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val wifiDirectSocketConnectTimeoutMillis = 5_000
private const val androidWifiDirectSocketControllerLogTag = "AndroidWifiDirectSocketController"

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
) : WifiDirectSocketController,
    WifiDirectTransportFrameSink,
    WifiDirectTransportFrameSource {

    private val listeners = linkedSetOf<WifiDirectSocketController.Listener>()
    private val transportFrameListeners = linkedSetOf<WifiDirectTransportFrameSource.Listener>()
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
    private val frameIoLoop = WifiDirectFrameIoLoop()

    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null

    override fun currentDiagnostics(): WifiDirectSocketDiagnostics {
        return stateMachine.currentDiagnostics()
    }

    override fun resetDiagnostics() {
        emit(stateMachine.resetDiagnostics())
    }

    override fun startServer(hostHint: String?) {
        val trimmedHostHint = hostHint?.trim()?.takeIf { it.isNotEmpty() }
        val currentDiagnostics = currentDiagnostics()
        safeSocketControllerLogDebug(
            "startServer invoked host=${trimmedHostHint ?: "none"} state=${currentDiagnostics.state.name.lowercase()}"
        )
        startServerBlockedReason(currentDiagnostics)?.let { blockedReason ->
            safeSocketControllerLogDebug(
                "startServer blocked: $blockedReason"
            )
            emit(
                stateMachine.markBlockedInCurrentState(
                    command = WifiDirectSocketCommand.START_SERVER,
                    reason = blockedReason,
                    host = trimmedHostHint
                )
            )
            return
        }
        val token = stateMachine.nextOperationToken()
        emitIfPresent(
            stateMachine.markStartingServer(
                token = token,
                hostHint = trimmedHostHint,
                requestedPort = requestedPort
            )
        )
        safeSocketControllerLogDebug(
            "startServer accepted: token=$token requestedPort=$requestedPort"
        )
        safeSocketControllerLogDebug(
            "startServer starting: token=$token host=${trimmedHostHint ?: "none"} requestedPort=$requestedPort"
        )
        ioScope.launch {
            releaseResources()
            val listeningSocket = socketServer.openListeningSocket().getOrElse { error ->
                safeSocketControllerLogWarning(
                    "startServer failed to open listening socket",
                    error
                )
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
            safeSocketControllerLogDebug(
                "startServer listening: token=$token port=${listeningSocket.localPort}"
            )
            acceptClient(token, listeningSocket)
        }
    }

    override fun connectClient(host: String) {
        val trimmedHost = host.trim()
        val currentDiagnostics = currentDiagnostics()
        safeSocketControllerLogDebug(
            "connectClient invoked host=${trimmedHost.ifEmpty { "none" }} state=${currentDiagnostics.state.name.lowercase()}"
        )
        connectClientBlockedReason(currentDiagnostics)?.let { blockedReason ->
            safeSocketControllerLogDebug(
                "connectClient blocked: $blockedReason"
            )
            emit(
                stateMachine.markBlockedInCurrentState(
                    command = WifiDirectSocketCommand.CONNECT_CLIENT,
                    reason = blockedReason,
                    host = trimmedHost.ifEmpty { null }
                )
            )
            return
        }
        if (trimmedHost.isEmpty()) {
            safeSocketControllerLogDebug(
                "connectClient blocked: Group owner address unavailable."
            )
            emit(
                stateMachine.markBlocked(
                    command = WifiDirectSocketCommand.CONNECT_CLIENT,
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
        emitIfPresent(
            stateMachine.markConnectingClient(
                token = token,
                host = trimmedHost,
                requestedPort = requestedPort
            )
        )
        safeSocketControllerLogDebug(
            "connectClient accepted: token=$token host=$trimmedHost requestedPort=$requestedPort"
        )
        safeSocketControllerLogDebug(
            "connectClient connecting: token=$token host=$trimmedHost requestedPort=$requestedPort"
        )
        ioScope.launch {
            releaseResources()
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
                safeSocketControllerLogWarning(
                    "connectClient failed to connect: host=$trimmedHost port=$targetPort",
                    error
                )
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
            safeSocketControllerLogDebug(
                "connectClient connected: token=$token host=$trimmedHost port=$targetPort"
            )
            startReadLoop(token, clientSocket)
        }
    }

    override fun sendDebugFrame() {
        if (!currentDiagnostics().isConnected) {
            emit(stateMachine.recordNotConnectedError())
            return
        }
        val token = currentTokenSnapshot()
        ioScope.launch {
            sendFramePayload(
                token = token,
                payload = wifiDirectDebugPingFrame().payloadBytes()
            )
        }
    }

    override fun isTransportFrameReady(): Boolean {
        val diagnostics = currentDiagnostics()
        return wifiDirectEffectiveFrameTransportState(diagnostics) ==
            WifiDirectFrameTransportState.READY
    }

    override fun transportFrameReadinessReason(): String? {
        return wifiDirectSocketFrameReadinessReason(currentDiagnostics())
    }

    override fun submitTransportFramePayload(
        payload: ByteArray,
        onResult: (Result<Unit>) -> Unit
    ) {
        if (!currentDiagnostics().isConnected) {
            emit(stateMachine.recordNotConnectedError())
            onResult(
                Result.failure(
                    IllegalStateException("Debug frame transport not connected.")
                )
            )
            return
        }
        val token = currentTokenSnapshot()
        ioScope.launch {
            sendFramePayload(
                token = token,
                payload = payload,
                onResult = onResult
            )
        }
    }

    override fun closeSocket() {
        safeSocketControllerLogDebug(
            "closeSocket invoked: state=${currentDiagnostics().state.name.lowercase()}"
        )
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

    override fun addTransportFrameListener(listener: WifiDirectTransportFrameSource.Listener) {
        transportFrameListeners += listener
    }

    override fun removeTransportFrameListener(listener: WifiDirectTransportFrameSource.Listener) {
        transportFrameListeners -= listener
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
                safeSocketControllerLogWarning(
                    "acceptClient failed: token=$token",
                    error
                )
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
        safeSocketControllerLogDebug(
            "acceptClient connected: token=$token"
        )
        startReadLoop(token, acceptedSocket)
    }

    private fun startReadLoop(
        token: Long,
        activeSocket: Socket
    ) {
        emitIfPresent(stateMachine.markReadLoopActive(token))
        safeSocketControllerLogDebug(
            "readLoop starting: token=$token role=${currentDiagnostics().role.name.lowercase()} connected=${currentDiagnostics().isConnected}"
        )
        ioScope.launch {
            frameIoLoop.readUntilClosed(
                socket = activeSocket,
                isActive = { stateMachine.isCurrentToken(token) },
                onIncomingFrame = { incomingFrame ->
                    val frame = incomingFrame.frame
                    safeSocketControllerLogDebug(
                        "readLoop received: token=$token payloadSize=${frame.payloadSize} bytes=${incomingFrame.frameByteCount}"
                    )
                    emitIncomingTransportFramePayload(
                        payload = frame.payloadBytes(),
                        byteCount = incomingFrame.frameByteCount
                    )
                    emitIfPresent(
                        stateMachine.recordReceivedFrame(
                            token = token,
                            message = wifiDirectFrameDebugLabel(frame),
                            frameSize = frame.payloadSize,
                            bytesReceived = incomingFrame.frameByteCount
                        )
                    )
                    wifiDirectDebugAutoReplyFrameOrNull(frame)?.let { reply ->
                        sendFramePayload(token, reply.payloadBytes())
                    }
                },
                onClosed = {
                    releaseResources()
                    emitIfPresent(stateMachine.markIdle(token))
                    safeSocketControllerLogDebug(
                        "readLoop closed: token=$token"
                    )
                },
                onFailure = { error ->
                    if (stateMachine.isCurrentToken(token)) {
                        safeSocketControllerLogWarning(
                            "readLoop failed: token=$token",
                            error
                        )
                        failSocket(
                            token = token,
                            reason = "Debug frame read failed: ${safeFrameErrorDetail(error)}"
                        )
                    }
                }
            )
        }
    }

    private fun sendFramePayload(
        token: Long,
        payload: ByteArray,
        onResult: (Result<Unit>) -> Unit = {}
    ) {
        val activeSocket = currentSocket(token) ?: run {
            emit(stateMachine.recordNotConnectedError())
            onResult(
                Result.failure(
                    IllegalStateException("Debug frame transport not connected.")
                )
            )
            return
        }
        val frame = runCatching {
            WifiDirectFrame.fromPayload(payload)
        }.getOrElse { error ->
            emit(
                stateMachine.markImmediateFailure(
                    role = currentDiagnostics().role,
                    reason = "Debug frame send failed: ${safeFrameErrorDetail(error)}",
                    endpoint = currentDiagnostics().endpoint
                )
            )
            onResult(Result.failure(error))
            return
        }

        frameIoLoop.writeFrame(
            socket = activeSocket,
            frame = frame
        ).onSuccess { byteCount ->
            safeSocketControllerLogDebug(
                "writeFrame success: token=$token payloadSize=${frame.payloadSize} bytes=$byteCount"
            )
            emitIfPresent(
                stateMachine.recordSentFrame(
                    token = token,
                    message = wifiDirectFrameDebugLabel(frame),
                    frameSize = frame.payloadSize,
                    bytesSent = byteCount
                )
            )
            onResult(Result.success(Unit))
        }.onFailure { error ->
            safeSocketControllerLogWarning(
                "writeFrame failed: token=$token payloadSize=${frame.payloadSize}",
                error
            )
            failSocket(
                token = token,
                reason = "Debug frame send failed: ${safeFrameErrorDetail(error)}"
            )
            onResult(Result.failure(error))
        }
    }

    private fun failSocket(
        token: Long,
        reason: String
    ) {
        safeSocketControllerLogDebug(
            "socket failed: token=$token reason=$reason"
        )
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
        safeSocketControllerLogDebug(
            "emit diagnostics: state=${updated.state.name.lowercase()} " +
                "command=${updated.lastCommand.name.lowercase()} " +
                "result=${updated.lastCommandResult.name.lowercase()} " +
                "seq=${updated.lastCommandSequence} listeners=${listeners.size}"
        )
        listeners.forEach { listener ->
            listener.onSocketDiagnosticsChanged(updated)
        }
    }

    private fun emitIncomingTransportFramePayload(
        payload: ByteArray,
        byteCount: Long
    ) {
        transportFrameListeners.toList().forEach { listener ->
            listener.onTransportFramePayloadReceived(
                payload = payload.copyOf(),
                byteCount = byteCount
            )
        }
    }

    private fun safeFrameErrorDetail(
        error: Throwable
    ): String {
        return error.message?.trim()?.takeIf { it.isNotEmpty() }
            ?: error::class.java.simpleName
    }

    private fun safeSocketControllerLogDebug(
        message: String
    ) {
        runCatching {
            Log.d(
                androidWifiDirectSocketControllerLogTag,
                message
            )
        }
    }

    private fun safeSocketControllerLogWarning(
        message: String,
        error: Throwable
    ) {
        runCatching {
            Log.w(
                androidWifiDirectSocketControllerLogTag,
                message,
                error
            )
        }
    }

    private fun startServerBlockedReason(
        diagnostics: WifiDirectSocketDiagnostics
    ): String? {
        return when (diagnostics.state) {
            WifiDirectSocketState.STARTING_SERVER -> "Socket server already starting."
            WifiDirectSocketState.SERVER_LISTENING -> "Socket server already listening."
            WifiDirectSocketState.CONNECTING -> "Socket client already connecting."
            WifiDirectSocketState.CONNECTED -> "Socket already connected."
            WifiDirectSocketState.CLOSING -> "Socket closing in progress."
            WifiDirectSocketState.IDLE,
            WifiDirectSocketState.FAILED -> null
        }
    }

    private fun connectClientBlockedReason(
        diagnostics: WifiDirectSocketDiagnostics
    ): String? {
        return when (diagnostics.state) {
            WifiDirectSocketState.STARTING_SERVER -> "Socket server already starting."
            WifiDirectSocketState.SERVER_LISTENING -> "Socket server already listening."
            WifiDirectSocketState.CONNECTING -> "Socket client already connecting."
            WifiDirectSocketState.CONNECTED -> "Socket already connected."
            WifiDirectSocketState.CLOSING -> "Socket closing in progress."
            WifiDirectSocketState.IDLE,
            WifiDirectSocketState.FAILED -> null
        }
    }
}
