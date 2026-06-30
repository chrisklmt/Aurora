package gr.hua.aurora.wifidirect

internal class WifiDirectSocketStateMachine(
    initialPort: Int
) {
    private val stateLock = Any()

    private var diagnostics = WifiDirectSocketDiagnostics(
        endpoint = WifiDirectSocketEndpoint(port = initialPort.takeIf { it > 0 })
    )
    private var operationToken: Long = 0L
    private var isDisposed: Boolean = false

    fun currentDiagnostics(): WifiDirectSocketDiagnostics {
        return synchronized(stateLock) { diagnostics }
    }

    fun nextOperationToken(): Long {
        return synchronized(stateLock) {
            if (!isDisposed) {
                operationToken += 1L
            }
            operationToken
        }
    }

    fun currentOperationToken(): Long {
        return synchronized(stateLock) { operationToken }
    }

    fun markDisposed() {
        synchronized(stateLock) {
            isDisposed = true
            operationToken += 1L
        }
    }

    fun isCurrentToken(
        token: Long
    ): Boolean {
        return synchronized(stateLock) {
            isCurrentTokenLocked(token)
        }
    }

    fun recordNotConnectedError(): WifiDirectSocketDiagnostics {
        return update { current ->
            current.copy(
                lastError = "Debug frame transport not connected.",
                frameDiagnostics = current.frameDiagnostics.copy(
                    lastError = "Debug frame transport not connected."
                )
            )
        }
    }

    fun markImmediateFailure(
        role: WifiDirectSocketRole,
        reason: String,
        endpoint: WifiDirectSocketEndpoint? = diagnostics.endpoint
    ): WifiDirectSocketDiagnostics {
        return update { current ->
            current.copy(
                state = WifiDirectSocketState.FAILED,
                role = role,
                endpoint = endpoint,
                isConnected = false,
                lastError = reason,
                frameDiagnostics = current.frameDiagnostics.copy(
                    state = WifiDirectFrameTransportState.FAILED,
                    lastError = reason
                )
            )
        }
    }

    fun markStartingServer(
        token: Long,
        hostHint: String?,
        requestedPort: Int
    ): WifiDirectSocketDiagnostics? {
        return updateIfCurrent(token) { current ->
            current.copy(
                state = WifiDirectSocketState.STARTING_SERVER,
                role = WifiDirectSocketRole.SERVER,
                endpoint = WifiDirectSocketEndpoint(
                    host = hostHint,
                    port = current.endpoint?.port ?: requestedPort.takeIf { it > 0 }
                ),
                isConnected = false,
                lastError = null,
                frameDiagnostics = current.frameDiagnostics.copy(
                    state = WifiDirectFrameTransportState.IDLE,
                    lastError = null
                )
            )
        }
    }

    fun markServerListening(
        token: Long,
        hostHint: String?,
        port: Int
    ): WifiDirectSocketDiagnostics? {
        return updateIfCurrent(token) { current ->
            current.copy(
                state = WifiDirectSocketState.SERVER_LISTENING,
                role = WifiDirectSocketRole.SERVER,
                endpoint = WifiDirectSocketEndpoint(
                    host = hostHint,
                    port = port
                ),
                isConnected = false,
                lastError = null,
                frameDiagnostics = current.frameDiagnostics.copy(
                    state = WifiDirectFrameTransportState.IDLE,
                    lastError = null
                )
            )
        }
    }

    fun markConnectingClient(
        token: Long,
        host: String,
        requestedPort: Int
    ): WifiDirectSocketDiagnostics? {
        return updateIfCurrent(token) { current ->
            current.copy(
                state = WifiDirectSocketState.CONNECTING,
                role = WifiDirectSocketRole.CLIENT,
                endpoint = WifiDirectSocketEndpoint(
                    host = host,
                    port = current.endpoint?.port ?: requestedPort.takeIf { it > 0 }
                ),
                isConnected = false,
                lastError = null,
                frameDiagnostics = current.frameDiagnostics.copy(
                    state = WifiDirectFrameTransportState.IDLE,
                    lastError = null
                )
            )
        }
    }

    fun markConnected(
        token: Long,
        role: WifiDirectSocketRole,
        endpoint: WifiDirectSocketEndpoint? = null
    ): WifiDirectSocketDiagnostics? {
        return updateIfCurrent(token) { current ->
            current.copy(
                state = WifiDirectSocketState.CONNECTED,
                role = role,
                endpoint = endpoint ?: current.endpoint,
                isConnected = true,
                lastError = null,
                frameDiagnostics = current.frameDiagnostics.copy(
                    state = WifiDirectFrameTransportState.READY,
                    lastError = null
                )
            )
        }
    }

    fun markClosing(
        token: Long
    ): WifiDirectSocketDiagnostics? {
        return updateIfCurrent(token) { current ->
            current.copy(
                state = WifiDirectSocketState.CLOSING,
                isConnected = false,
                lastError = null,
                frameDiagnostics = current.frameDiagnostics.copy(
                    state = WifiDirectFrameTransportState.IDLE,
                    lastError = null
                )
            )
        }
    }

    fun markIdle(
        token: Long
    ): WifiDirectSocketDiagnostics? {
        return updateIfCurrent(token) { current ->
            current.copy(
                state = WifiDirectSocketState.IDLE,
                isConnected = false,
                frameDiagnostics = current.frameDiagnostics.copy(
                    state = WifiDirectFrameTransportState.IDLE
                )
            )
        }
    }

    fun markFailed(
        token: Long,
        reason: String
    ): WifiDirectSocketDiagnostics? {
        return updateIfCurrent(token) { current ->
            current.copy(
                state = WifiDirectSocketState.FAILED,
                isConnected = false,
                lastError = reason,
                frameDiagnostics = current.frameDiagnostics.copy(
                    state = WifiDirectFrameTransportState.FAILED,
                    lastError = reason
                )
            )
        }
    }

    fun recordSentFrame(
        token: Long,
        message: String,
        frameSize: Int,
        bytesSent: Long
    ): WifiDirectSocketDiagnostics? {
        return updateIfCurrent(token) { current ->
            current.copy(
                lastSentMessage = message,
                bytesSent = current.bytesSent + bytesSent,
                lastError = null,
                frameDiagnostics = current.frameDiagnostics.copy(
                    state = WifiDirectFrameTransportState.READY,
                    framesSent = current.frameDiagnostics.framesSent + 1L,
                    bytesSent = current.frameDiagnostics.bytesSent + bytesSent,
                    lastFrameSize = frameSize,
                    lastError = null
                )
            )
        }
    }

    fun recordReceivedFrame(
        token: Long,
        message: String,
        frameSize: Int,
        bytesReceived: Long
    ): WifiDirectSocketDiagnostics? {
        return updateIfCurrent(token) { current ->
            current.copy(
                lastReceivedMessage = message,
                bytesReceived = current.bytesReceived + bytesReceived,
                lastError = null,
                frameDiagnostics = current.frameDiagnostics.copy(
                    state = WifiDirectFrameTransportState.READY,
                    framesReceived = current.frameDiagnostics.framesReceived + 1L,
                    bytesReceived = current.frameDiagnostics.bytesReceived + bytesReceived,
                    lastFrameSize = frameSize,
                    lastError = null
                )
            )
        }
    }

    private fun update(
        transform: (WifiDirectSocketDiagnostics) -> WifiDirectSocketDiagnostics
    ): WifiDirectSocketDiagnostics {
        return synchronized(stateLock) {
            diagnostics = transform(diagnostics)
            diagnostics
        }
    }

    private fun updateIfCurrent(
        token: Long,
        transform: (WifiDirectSocketDiagnostics) -> WifiDirectSocketDiagnostics
    ): WifiDirectSocketDiagnostics? {
        return synchronized(stateLock) {
            if (!isCurrentTokenLocked(token)) {
                null
            } else {
                diagnostics = transform(diagnostics)
                diagnostics
            }
        }
    }

    private fun isCurrentTokenLocked(
        token: Long
    ): Boolean {
        return !isDisposed && operationToken == token
    }
}
