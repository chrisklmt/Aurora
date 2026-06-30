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
            current.copy(lastError = "Debug socket not connected.")
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
                lastError = reason
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
                lastError = null
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
                lastError = null
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
                lastError = null
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
                lastError = null
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
                lastError = null
            )
        }
    }

    fun markIdle(
        token: Long
    ): WifiDirectSocketDiagnostics? {
        return updateIfCurrent(token) { current ->
            current.copy(
                state = WifiDirectSocketState.IDLE,
                isConnected = false
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
                lastError = reason
            )
        }
    }

    fun recordSentMessage(
        token: Long,
        message: String,
        bytesSent: Long
    ): WifiDirectSocketDiagnostics? {
        return updateIfCurrent(token) { current ->
            current.copy(
                lastSentMessage = message,
                bytesSent = current.bytesSent + bytesSent,
                lastError = null
            )
        }
    }

    fun recordReceivedMessage(
        token: Long,
        message: String,
        bytesReceived: Long
    ): WifiDirectSocketDiagnostics? {
        return updateIfCurrent(token) { current ->
            current.copy(
                lastReceivedMessage = message,
                bytesReceived = current.bytesReceived + bytesReceived,
                lastError = null
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
