package gr.hua.aurora.wifidirect.socket

import gr.hua.aurora.wifidirect.frame.WifiDirectFrameDiagnostics
import gr.hua.aurora.wifidirect.frame.WifiDirectFrameTransportState

internal const val wifiDirectDebugSocketPort = 8988

private const val wifiDirectSocketFoundationNote =
    "Wi-Fi Direct chat transport not wired yet."

private const val wifiDirectSocketConnectedLabel = "yes"
private const val wifiDirectSocketDisconnectedLabel = "no"

internal enum class WifiDirectSocketState {
    IDLE,
    STARTING_SERVER,
    SERVER_LISTENING,
    CONNECTING,
    CONNECTED,
    CLOSING,
    FAILED
}

internal enum class WifiDirectSocketRole {
    SERVER,
    CLIENT,
    UNKNOWN
}

internal enum class WifiDirectSocketCommand {
    NONE,
    START_SERVER,
    CONNECT_CLIENT,
    CLOSE_SOCKET
}

internal enum class WifiDirectSocketCommandResult {
    NONE,
    STARTING,
    LISTENING,
    CONNECTING,
    CONNECTED,
    CLOSING,
    CLOSED,
    BLOCKED,
    FAILED
}

internal data class WifiDirectSocketEndpoint(
    val host: String? = null,
    val port: Int? = null
)

internal data class WifiDirectSocketDiagnostics(
    val state: WifiDirectSocketState = WifiDirectSocketState.IDLE,
    val role: WifiDirectSocketRole = WifiDirectSocketRole.UNKNOWN,
    val endpoint: WifiDirectSocketEndpoint? = WifiDirectSocketEndpoint(
        port = wifiDirectDebugSocketPort
    ),
    val isConnected: Boolean = false,
    val isReadLoopActive: Boolean = false,
    val lastSentMessage: String? = null,
    val lastReceivedMessage: String? = null,
    val lastOutboundFrameSize: Int? = null,
    val lastInboundFrameSize: Int? = null,
    val lastError: String? = null,
    val bytesSent: Long = 0L,
    val bytesReceived: Long = 0L,
    val lastCommand: WifiDirectSocketCommand = WifiDirectSocketCommand.NONE,
    val lastCommandResult: WifiDirectSocketCommandResult = WifiDirectSocketCommandResult.NONE,
    val lastCommandError: String? = null,
    val lastCommandSequence: Long = 0L,
    val lastOperationToken: Long = 0L,
    val lastCommandAtMillis: Long? = null,
    val lastStateChangedAtMillis: Long? = null,
    val lastStateTransition: String? = null,
    val lastCommandHost: String? = null,
    val lastCloseReason: String? = null,
    val lastDisposeReason: String? = null,
    val serverStartAttempts: Int = 0,
    val clientConnectAttempts: Int = 0,
    val closeAttempts: Int = 0,
    val note: String = wifiDirectSocketFoundationNote,
    val frameDiagnostics: WifiDirectFrameDiagnostics = WifiDirectFrameDiagnostics()
) {
    constructor(
        state: WifiDirectSocketState = WifiDirectSocketState.IDLE,
        role: WifiDirectSocketRole = WifiDirectSocketRole.UNKNOWN,
        endpoint: WifiDirectSocketEndpoint? = WifiDirectSocketEndpoint(
            port = wifiDirectDebugSocketPort
        ),
        isConnected: Boolean = false,
        isReadLoopActive: Boolean = false,
        lastSentMessage: String? = null,
        lastReceivedMessage: String? = null,
        lastOutboundFrameSize: Int? = null,
        lastInboundFrameSize: Int? = null,
        lastError: String? = null,
        bytesSent: Long = 0L,
        bytesReceived: Long = 0L,
        lastCommand: WifiDirectSocketCommand = WifiDirectSocketCommand.NONE,
        lastCommandResult: WifiDirectSocketCommandResult = WifiDirectSocketCommandResult.NONE,
        lastCommandError: String? = null,
        lastCommandSequence: Long = 0L,
        lastCommandHost: String? = null,
        serverStartAttempts: Int = 0,
        clientConnectAttempts: Int = 0,
        closeAttempts: Int = 0,
        note: String = wifiDirectSocketFoundationNote,
        frameDiagnostics: WifiDirectFrameDiagnostics = WifiDirectFrameDiagnostics()
    ) : this(
        state = state,
        role = role,
        endpoint = endpoint,
        isConnected = isConnected,
        isReadLoopActive = isReadLoopActive,
        lastSentMessage = lastSentMessage,
        lastReceivedMessage = lastReceivedMessage,
        lastOutboundFrameSize = lastOutboundFrameSize,
        lastInboundFrameSize = lastInboundFrameSize,
        lastError = lastError,
        bytesSent = bytesSent,
        bytesReceived = bytesReceived,
        lastCommand = lastCommand,
        lastCommandResult = lastCommandResult,
        lastCommandError = lastCommandError,
        lastCommandSequence = lastCommandSequence,
        lastOperationToken = 0L,
        lastCommandAtMillis = null,
        lastStateChangedAtMillis = null,
        lastStateTransition = null,
        lastCommandHost = lastCommandHost,
        lastCloseReason = null,
        lastDisposeReason = null,
        serverStartAttempts = serverStartAttempts,
        clientConnectAttempts = clientConnectAttempts,
        closeAttempts = closeAttempts,
        note = note,
        frameDiagnostics = frameDiagnostics
    )
}

internal interface WifiDirectSocketController {
    interface Listener {
        fun onSocketDiagnosticsChanged(diagnostics: WifiDirectSocketDiagnostics)
    }

    fun currentDiagnostics(): WifiDirectSocketDiagnostics
    fun resetDiagnostics()
    fun startServer(hostHint: String? = null)
    fun connectClient(host: String)
    fun sendDebugFrame()
    fun closeSocket(reason: String? = null)
    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)
    fun dispose(reason: String? = null)
}

internal fun wifiDirectEffectiveFrameTransportState(
    diagnostics: WifiDirectSocketDiagnostics
): WifiDirectFrameTransportState {
    return when {
        diagnostics.frameDiagnostics.state == WifiDirectFrameTransportState.FAILED ->
            WifiDirectFrameTransportState.FAILED
        diagnostics.frameDiagnostics.state == WifiDirectFrameTransportState.READY ->
            WifiDirectFrameTransportState.READY
        diagnostics.isConnected -> WifiDirectFrameTransportState.READY
        else -> WifiDirectFrameTransportState.IDLE
    }
}

internal fun wifiDirectSocketFrameReadinessReason(
    diagnostics: WifiDirectSocketDiagnostics
): String? {
    return when {
        diagnostics.frameDiagnostics.state == WifiDirectFrameTransportState.FAILED -> {
            diagnostics.frameDiagnostics.lastError
                ?: diagnostics.lastError
                ?: "Wi-Fi Direct frame transport failed."
        }
        diagnostics.frameDiagnostics.state == WifiDirectFrameTransportState.READY -> null
        diagnostics.isConnected -> null
        diagnostics.state == WifiDirectSocketState.STARTING_SERVER ->
            "Socket server starting."
        diagnostics.state == WifiDirectSocketState.SERVER_LISTENING ->
            "Waiting for a socket client."
        diagnostics.state == WifiDirectSocketState.CONNECTING ->
            "Connecting socket client."
        diagnostics.state == WifiDirectSocketState.CLOSING ->
            "Socket closing."
        diagnostics.lastError?.isNotBlank() == true -> diagnostics.lastError
        else -> "Wi-Fi Direct debug socket not connected."
    }
}

internal fun wifiDirectSocketStateSummary(
    state: WifiDirectSocketState
): String {
    return when (state) {
        WifiDirectSocketState.IDLE -> "idle"
        WifiDirectSocketState.STARTING_SERVER -> "starting server"
        WifiDirectSocketState.SERVER_LISTENING -> "listening"
        WifiDirectSocketState.CONNECTING -> "connecting"
        WifiDirectSocketState.CONNECTED -> "connected"
        WifiDirectSocketState.CLOSING -> "closing"
        WifiDirectSocketState.FAILED -> "failed"
    }
}

internal fun wifiDirectSocketRoleSummary(
    role: WifiDirectSocketRole
): String {
    return when (role) {
        WifiDirectSocketRole.SERVER -> "server"
        WifiDirectSocketRole.CLIENT -> "client"
        WifiDirectSocketRole.UNKNOWN -> "unknown"
    }
}

internal fun wifiDirectSocketEndpointSummary(
    endpoint: WifiDirectSocketEndpoint?
): String {
    if (endpoint == null) {
        return "unavailable"
    }
    val host = endpoint.host?.trim()?.takeIf { it.isNotEmpty() }
    val port = endpoint.port
    return when {
        host != null && port != null -> "$host:$port"
        host != null -> host
        port != null -> "port $port"
        else -> "unavailable"
    }
}

internal fun wifiDirectSocketConnectedSummary(
    isConnected: Boolean
): String {
    return if (isConnected) {
        wifiDirectSocketConnectedLabel
    } else {
        wifiDirectSocketDisconnectedLabel
    }
}

internal fun wifiDirectSocketMessageSummary(
    message: String?
): String {
    return message?.trim()?.takeIf { it.isNotEmpty() } ?: "none"
}

internal fun wifiDirectSocketByteSummary(
    diagnostics: WifiDirectSocketDiagnostics
): String {
    return "${diagnostics.bytesSent}/${diagnostics.bytesReceived}"
}

internal fun wifiDirectSocketCommandSummary(
    diagnostics: WifiDirectSocketDiagnostics
): String {
    return when (diagnostics.lastCommand) {
        WifiDirectSocketCommand.NONE -> "none"
        WifiDirectSocketCommand.START_SERVER -> "startServer"
        WifiDirectSocketCommand.CONNECT_CLIENT -> "connectClient"
        WifiDirectSocketCommand.CLOSE_SOCKET -> "closeSocket"
    }
}

internal fun wifiDirectSocketCommandResultSummary(
    diagnostics: WifiDirectSocketDiagnostics
): String {
    return when (diagnostics.lastCommandResult) {
        WifiDirectSocketCommandResult.NONE -> "none"
        WifiDirectSocketCommandResult.STARTING -> "Starting socket server..."
        WifiDirectSocketCommandResult.LISTENING -> "Socket server listening."
        WifiDirectSocketCommandResult.CONNECTING -> "Connecting socket client..."
        WifiDirectSocketCommandResult.CONNECTED -> "Socket connected."
        WifiDirectSocketCommandResult.CLOSING -> "Closing socket..."
        WifiDirectSocketCommandResult.CLOSED -> "Socket closed."
        WifiDirectSocketCommandResult.BLOCKED -> "Socket action blocked."
        WifiDirectSocketCommandResult.FAILED -> "Socket action failed."
    }
}
