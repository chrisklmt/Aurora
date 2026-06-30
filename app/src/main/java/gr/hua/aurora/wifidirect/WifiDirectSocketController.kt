package gr.hua.aurora.wifidirect

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
    val lastSentMessage: String? = null,
    val lastReceivedMessage: String? = null,
    val lastError: String? = null,
    val bytesSent: Long = 0L,
    val bytesReceived: Long = 0L,
    val note: String = wifiDirectSocketFoundationNote,
    val frameDiagnostics: WifiDirectFrameDiagnostics = WifiDirectFrameDiagnostics()
)

internal interface WifiDirectSocketController {
    interface Listener {
        fun onSocketDiagnosticsChanged(diagnostics: WifiDirectSocketDiagnostics)
    }

    fun currentDiagnostics(): WifiDirectSocketDiagnostics
    fun startServer(hostHint: String? = null)
    fun connectClient(host: String)
    fun sendDebugFrame()
    fun closeSocket()
    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)
    fun dispose()
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
