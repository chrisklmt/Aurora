package gr.hua.aurora.wifidirect

import android.util.Log

private const val wifiDirectSocketStateMachineLogTag = "WifiDirectSocketStateMachine"

private enum class WifiDirectSocketTokenStatus {
    CURRENT,
    STALE,
    DISPOSED
}

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
            tokenStatusLocked(token) == WifiDirectSocketTokenStatus.CURRENT
        }
    }

    fun recordNotConnectedError(): WifiDirectSocketDiagnostics {
        return update { current ->
            current.copy(
                lastError = "Debug frame transport not connected.",
                lastCommandError = current.lastCommandError ?: "Debug frame transport not connected.",
                isReadLoopActive = false,
                frameDiagnostics = current.frameDiagnostics.copy(
                    lastError = "Debug frame transport not connected."
                )
            )
        }
    }

    fun markBlocked(
        command: WifiDirectSocketCommand,
        role: WifiDirectSocketRole,
        reason: String,
        endpoint: WifiDirectSocketEndpoint? = diagnostics.endpoint,
        host: String? = null
    ): WifiDirectSocketDiagnostics {
        return update { current ->
            current.copy(
                state = WifiDirectSocketState.IDLE,
                role = role,
                endpoint = endpoint,
                isConnected = false,
                isReadLoopActive = false,
                lastError = reason,
                lastCommand = command,
                lastCommandResult = WifiDirectSocketCommandResult.BLOCKED,
                lastCommandError = reason,
                lastCommandSequence = current.lastCommandSequence + 1L,
                lastCommandHost = host,
                serverStartAttempts = if (command == WifiDirectSocketCommand.START_SERVER) {
                    current.serverStartAttempts + 1
                } else {
                    current.serverStartAttempts
                },
                clientConnectAttempts = if (command == WifiDirectSocketCommand.CONNECT_CLIENT) {
                    current.clientConnectAttempts + 1
                } else {
                    current.clientConnectAttempts
                },
                closeAttempts = if (command == WifiDirectSocketCommand.CLOSE_SOCKET) {
                    current.closeAttempts + 1
                } else {
                    current.closeAttempts
                }
            )
        }
    }

    fun markBlockedInCurrentState(
        command: WifiDirectSocketCommand,
        reason: String,
        host: String? = null
    ): WifiDirectSocketDiagnostics {
        return update { current ->
            current.copy(
                lastError = reason,
                lastCommand = command,
                lastCommandResult = WifiDirectSocketCommandResult.BLOCKED,
                lastCommandError = reason,
                lastCommandSequence = current.lastCommandSequence + 1L,
                lastCommandHost = host ?: current.lastCommandHost,
                serverStartAttempts = if (command == WifiDirectSocketCommand.START_SERVER) {
                    current.serverStartAttempts + 1
                } else {
                    current.serverStartAttempts
                },
                clientConnectAttempts = if (command == WifiDirectSocketCommand.CONNECT_CLIENT) {
                    current.clientConnectAttempts + 1
                } else {
                    current.clientConnectAttempts
                },
                closeAttempts = if (command == WifiDirectSocketCommand.CLOSE_SOCKET) {
                    current.closeAttempts + 1
                } else {
                    current.closeAttempts
                }
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
                isReadLoopActive = false,
                lastError = reason,
                lastCommandResult = WifiDirectSocketCommandResult.FAILED,
                lastCommandError = reason,
                lastCommandSequence = current.lastCommandSequence + 1L,
                frameDiagnostics = current.frameDiagnostics.copy(
                    state = WifiDirectFrameTransportState.FAILED,
                    lastError = reason,
                    lastSentFrameSize = null,
                    lastReceivedFrameSize = null
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
                isReadLoopActive = false,
                lastError = null,
                lastCommand = WifiDirectSocketCommand.START_SERVER,
                lastCommandResult = WifiDirectSocketCommandResult.STARTING,
                lastCommandError = null,
                lastCommandSequence = current.lastCommandSequence + 1L,
                lastCommandHost = hostHint,
                serverStartAttempts = current.serverStartAttempts + 1,
                frameDiagnostics = current.frameDiagnostics.copy(
                    state = WifiDirectFrameTransportState.IDLE,
                    lastError = null,
                    lastSentFrameSize = null,
                    lastReceivedFrameSize = null
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
                isReadLoopActive = false,
                lastError = null,
                lastCommand = WifiDirectSocketCommand.START_SERVER,
                lastCommandResult = WifiDirectSocketCommandResult.LISTENING,
                lastCommandError = null,
                lastCommandHost = hostHint,
                frameDiagnostics = current.frameDiagnostics.copy(
                    state = WifiDirectFrameTransportState.IDLE,
                    lastError = null,
                    lastSentFrameSize = null,
                    lastReceivedFrameSize = null
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
                isReadLoopActive = false,
                lastError = null,
                lastCommand = WifiDirectSocketCommand.CONNECT_CLIENT,
                lastCommandResult = WifiDirectSocketCommandResult.CONNECTING,
                lastCommandError = null,
                lastCommandSequence = current.lastCommandSequence + 1L,
                lastCommandHost = host,
                clientConnectAttempts = current.clientConnectAttempts + 1,
                frameDiagnostics = current.frameDiagnostics.copy(
                    state = WifiDirectFrameTransportState.IDLE,
                    lastError = null,
                    lastSentFrameSize = null,
                    lastReceivedFrameSize = null
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
                isReadLoopActive = false,
                lastError = null,
                lastCommand = when (role) {
                    WifiDirectSocketRole.SERVER -> WifiDirectSocketCommand.START_SERVER
                    WifiDirectSocketRole.CLIENT -> WifiDirectSocketCommand.CONNECT_CLIENT
                    WifiDirectSocketRole.UNKNOWN -> current.lastCommand
                },
                lastCommandResult = WifiDirectSocketCommandResult.CONNECTED,
                lastCommandError = null,
                lastCommandHost = endpoint?.host ?: current.lastCommandHost,
                frameDiagnostics = current.frameDiagnostics.copy(
                    state = WifiDirectFrameTransportState.READY,
                    lastError = null
                )
            )
        }
    }

    fun markReadLoopActive(
        token: Long
    ): WifiDirectSocketDiagnostics? {
        return updateIfCurrent(token) { current ->
            current.copy(
                isReadLoopActive = true
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
                isReadLoopActive = false,
                lastError = null,
                lastCommand = WifiDirectSocketCommand.CLOSE_SOCKET,
                lastCommandResult = WifiDirectSocketCommandResult.CLOSING,
                lastCommandError = null,
                lastCommandSequence = current.lastCommandSequence + 1L,
                closeAttempts = current.closeAttempts + 1,
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
                isReadLoopActive = false,
                lastCommandResult = if (
                    current.lastCommand == WifiDirectSocketCommand.CLOSE_SOCKET
                ) {
                    WifiDirectSocketCommandResult.CLOSED
                } else {
                    current.lastCommandResult
                },
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
                isReadLoopActive = false,
                lastError = reason,
                lastCommandResult = WifiDirectSocketCommandResult.FAILED,
                lastCommandError = reason,
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
                lastOutboundFrameSize = frameSize,
                bytesSent = current.bytesSent + bytesSent,
                lastError = null,
                frameDiagnostics = current.frameDiagnostics.copy(
                    state = WifiDirectFrameTransportState.READY,
                    framesSent = current.frameDiagnostics.framesSent + 1L,
                    bytesSent = current.frameDiagnostics.bytesSent + bytesSent,
                    lastFrameSize = frameSize,
                    lastSentFrameSize = frameSize,
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
                lastInboundFrameSize = frameSize,
                bytesReceived = current.bytesReceived + bytesReceived,
                lastError = null,
                frameDiagnostics = current.frameDiagnostics.copy(
                    state = WifiDirectFrameTransportState.READY,
                    framesReceived = current.frameDiagnostics.framesReceived + 1L,
                    bytesReceived = current.frameDiagnostics.bytesReceived + bytesReceived,
                    lastFrameSize = frameSize,
                    lastReceivedFrameSize = frameSize,
                    lastError = null
                )
            )
        }
    }

    fun resetDiagnostics(): WifiDirectSocketDiagnostics {
        return update { current ->
            current.copy(
                lastSentMessage = null,
                lastReceivedMessage = null,
                lastOutboundFrameSize = null,
                lastInboundFrameSize = null,
                isReadLoopActive = current.isConnected,
                lastError = null,
                bytesSent = 0L,
                bytesReceived = 0L,
                lastCommand = WifiDirectSocketCommand.NONE,
                lastCommandResult = WifiDirectSocketCommandResult.NONE,
                lastCommandError = null,
                lastCommandSequence = 0L,
                lastCommandHost = null,
                serverStartAttempts = 0,
                clientConnectAttempts = 0,
                closeAttempts = 0,
                frameDiagnostics = current.frameDiagnostics.copy(
                    framesSent = 0L,
                    framesReceived = 0L,
                    bytesSent = 0L,
                    bytesReceived = 0L,
                    lastFrameSize = null,
                    lastSentFrameSize = null,
                    lastReceivedFrameSize = null,
                    lastError = null
                )
            )
        }
    }

    private fun update(
        transform: (WifiDirectSocketDiagnostics) -> WifiDirectSocketDiagnostics
    ): WifiDirectSocketDiagnostics {
        val updated = synchronized(stateLock) {
            diagnostics = transform(diagnostics)
            diagnostics
        }
        logStateUpdate(updated)
        return updated
    }

    private fun updateIfCurrent(
        token: Long,
        transform: (WifiDirectSocketDiagnostics) -> WifiDirectSocketDiagnostics
    ): WifiDirectSocketDiagnostics? {
        val updateResult = synchronized(stateLock) {
            val tokenStatus = tokenStatusLocked(token)
            if (tokenStatus != WifiDirectSocketTokenStatus.CURRENT) {
                UpdateIfCurrentResult(
                    diagnostics = null,
                    tokenStatus = tokenStatus,
                    currentToken = operationToken
                )
            } else {
                diagnostics = transform(diagnostics)
                UpdateIfCurrentResult(
                    diagnostics = diagnostics,
                    tokenStatus = WifiDirectSocketTokenStatus.CURRENT,
                    currentToken = operationToken
                )
            }
        }
        val updated = updateResult.diagnostics
        if (updated == null) {
            val message = when (updateResult.tokenStatus) {
                WifiDirectSocketTokenStatus.STALE ->
                    "ignored stale token=$token currentToken=${updateResult.currentToken}"
                WifiDirectSocketTokenStatus.DISPOSED ->
                    "ignored token=$token because controller disposed currentToken=${updateResult.currentToken}"
                WifiDirectSocketTokenStatus.CURRENT ->
                    "ignored token=$token currentToken=${updateResult.currentToken}"
            }
            safeSocketStateMachineLogDebug(message)
        } else {
            logStateUpdate(updated)
        }
        return updated
    }

    private fun tokenStatusLocked(
        token: Long
    ): WifiDirectSocketTokenStatus {
        return when {
            isDisposed -> WifiDirectSocketTokenStatus.DISPOSED
            operationToken != token -> WifiDirectSocketTokenStatus.STALE
            else -> WifiDirectSocketTokenStatus.CURRENT
        }
    }

    private fun logStateUpdate(
        diagnostics: WifiDirectSocketDiagnostics
    ) {
        safeSocketStateMachineLogDebug(
            "state=${diagnostics.state.name.lowercase()} " +
                "command=${diagnostics.lastCommand.name.lowercase()} " +
                "result=${diagnostics.lastCommandResult.name.lowercase()} " +
                "seq=${diagnostics.lastCommandSequence} " +
                "host=${diagnostics.lastCommandHost ?: "none"} " +
                "connected=${diagnostics.isConnected} " +
                "error=${diagnostics.lastCommandError ?: diagnostics.lastError ?: "none"}"
        )
    }

    private fun safeSocketStateMachineLogDebug(
        message: String
    ) {
        runCatching {
            Log.d(
                wifiDirectSocketStateMachineLogTag,
                message
            )
        }
    }

    private data class UpdateIfCurrentResult(
        val diagnostics: WifiDirectSocketDiagnostics?,
        val tokenStatus: WifiDirectSocketTokenStatus,
        val currentToken: Long
    )
}
