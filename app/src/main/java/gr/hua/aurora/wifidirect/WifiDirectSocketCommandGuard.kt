package gr.hua.aurora.wifidirect

internal data class WifiDirectSocketCommandAvailability(
    val canStartServer: Boolean,
    val canConnectClient: Boolean,
    val canSendPing: Boolean,
    val canCloseSocket: Boolean,
    val connectHost: String? = null,
    val helpText: String? = null
)

internal fun wifiDirectSocketCommandAvailability(
    runtimeStatus: WifiDirectRuntimeStatus,
    diagnostics: WifiDirectSocketDiagnostics
): WifiDirectSocketCommandAvailability {
    return when (diagnostics.state) {
        WifiDirectSocketState.CONNECTED -> WifiDirectSocketCommandAvailability(
            canStartServer = false,
            canConnectClient = false,
            canSendPing = true,
            canCloseSocket = true
        )
        WifiDirectSocketState.STARTING_SERVER,
        WifiDirectSocketState.SERVER_LISTENING,
        WifiDirectSocketState.CONNECTING,
        WifiDirectSocketState.CLOSING -> WifiDirectSocketCommandAvailability(
            canStartServer = false,
            canConnectClient = false,
            canSendPing = false,
            canCloseSocket = true,
            helpText = wifiDirectSocketActivityHint(diagnostics.state)
        )
        WifiDirectSocketState.IDLE,
        WifiDirectSocketState.FAILED -> {
            val connectionStatus = runtimeStatus.connectionStatus
            if (
                connectionStatus.state != WifiDirectConnectionState.CONNECTED ||
                connectionStatus.groupFormed != WifiDirectGroupFormedState.YES
            ) {
                return WifiDirectSocketCommandAvailability(
                    canStartServer = false,
                    canConnectClient = false,
                    canSendPing = false,
                    canCloseSocket = false,
                    helpText = "Wi-Fi Direct group not formed."
                )
            }

            when (connectionStatus.role) {
                WifiDirectConnectionRole.GROUP_OWNER -> WifiDirectSocketCommandAvailability(
                    canStartServer = true,
                    canConnectClient = false,
                    canSendPing = false,
                    canCloseSocket = false
                )
                WifiDirectConnectionRole.CLIENT -> {
                    val ownerAddress = connectionStatus.groupOwnerAddress?.trim()
                        ?.takeIf { it.isNotEmpty() }
                    WifiDirectSocketCommandAvailability(
                        canStartServer = false,
                        canConnectClient = ownerAddress != null,
                        canSendPing = false,
                        canCloseSocket = false,
                        connectHost = ownerAddress,
                        helpText = if (ownerAddress == null) {
                            "Group owner address unavailable."
                        } else {
                            null
                        }
                    )
                }
                WifiDirectConnectionRole.UNKNOWN -> WifiDirectSocketCommandAvailability(
                    canStartServer = false,
                    canConnectClient = false,
                    canSendPing = false,
                    canCloseSocket = false,
                    helpText = "Wi-Fi Direct role unavailable."
                )
            }
        }
    }
}

internal fun wifiDirectSocketActivityHint(
    state: WifiDirectSocketState
): String? {
    return when (state) {
        WifiDirectSocketState.STARTING_SERVER -> "Starting debug socket server."
        WifiDirectSocketState.SERVER_LISTENING -> "Waiting for a socket client."
        WifiDirectSocketState.CONNECTING -> "Connecting to the group owner socket."
        WifiDirectSocketState.CLOSING -> "Closing Wi-Fi Direct debug socket."
        WifiDirectSocketState.IDLE,
        WifiDirectSocketState.CONNECTED,
        WifiDirectSocketState.FAILED -> null
    }
}
