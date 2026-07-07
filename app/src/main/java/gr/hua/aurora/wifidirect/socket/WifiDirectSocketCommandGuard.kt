package gr.hua.aurora.wifidirect.socket

import android.util.Log
import gr.hua.aurora.wifidirect.*

private const val wifiDirectSocketCommandGuardLogTag = "WifiDirectSocketCommandGuard"

internal data class WifiDirectSocketCommandAvailability(
    val canStartServer: Boolean,
    val canConnectClient: Boolean,
    val canSendFrame: Boolean,
    val canCloseSocket: Boolean,
    val connectHost: String? = null,
    val startServerBlockedReason: String? = null,
    val connectClientBlockedReason: String? = null,
    val helpText: String? = null
)

internal data class WifiDirectSocketCommandGuardSnapshot(
    val command: WifiDirectSocketCommand,
    val role: WifiDirectConnectionRole,
    val groupFormed: WifiDirectGroupFormedState,
    val rawOwnerAddress: String?,
    val connectHost: String?,
    val accepted: Boolean,
    val blockedReason: String?
)

private val wifiDirectMacAddressRegex = Regex(
    pattern = "^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$"
)

internal fun wifiDirectSocketCommandAvailability(
    runtimeStatus: WifiDirectRuntimeStatus,
    diagnostics: WifiDirectSocketDiagnostics
): WifiDirectSocketCommandAvailability {
    return when (diagnostics.state) {
        WifiDirectSocketState.CONNECTED -> WifiDirectSocketCommandAvailability(
            canStartServer = false,
            canConnectClient = false,
            canSendFrame = true,
            canCloseSocket = true
        )
        WifiDirectSocketState.STARTING_SERVER,
        WifiDirectSocketState.SERVER_LISTENING,
        WifiDirectSocketState.CONNECTING,
        WifiDirectSocketState.CLOSING -> WifiDirectSocketCommandAvailability(
            canStartServer = false,
            canConnectClient = false,
            canSendFrame = false,
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
                    canSendFrame = false,
                    canCloseSocket = false,
                    startServerBlockedReason = "Wi-Fi Direct group not formed.",
                    connectClientBlockedReason = "Wi-Fi Direct group not formed.",
                    helpText = "Wi-Fi Direct group not formed."
                )
            }

            when (connectionStatus.role) {
                WifiDirectConnectionRole.GROUP_OWNER -> WifiDirectSocketCommandAvailability(
                    canStartServer = true,
                    canConnectClient = false,
                    canSendFrame = false,
                    canCloseSocket = false,
                    connectClientBlockedReason = "Connect client only on Wi-Fi Direct client."
                )
                WifiDirectConnectionRole.CLIENT -> {
                    val rawOwnerAddress = connectionStatus.groupOwnerAddress?.trim()
                        ?.takeIf { it.isNotEmpty() }
                    val ownerAddress = wifiDirectSocketConnectHostOrNull(rawOwnerAddress)
                    val blockedReason = when {
                        rawOwnerAddress == null -> "Group owner address missing."
                        ownerAddress == null -> "Socket client needs the group owner IP address."
                        else -> null
                    }
                    WifiDirectSocketCommandAvailability(
                        canStartServer = false,
                        canConnectClient = ownerAddress != null,
                        canSendFrame = false,
                        canCloseSocket = false,
                        connectHost = ownerAddress,
                        startServerBlockedReason = "Start server only on group owner.",
                        connectClientBlockedReason = blockedReason,
                        helpText = blockedReason
                    )
                }
                WifiDirectConnectionRole.UNKNOWN -> WifiDirectSocketCommandAvailability(
                    canStartServer = false,
                    canConnectClient = false,
                    canSendFrame = false,
                    canCloseSocket = false,
                    startServerBlockedReason = "Wi-Fi Direct role unavailable.",
                    connectClientBlockedReason = "Wi-Fi Direct role unavailable.",
                    helpText = "Wi-Fi Direct role unavailable."
                )
            }
        }
    }
}

internal fun wifiDirectSocketConnectHostOrNull(
    rawAddress: String?
): String? {
    val normalizedAddress = rawAddress?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return if (wifiDirectMacAddressRegex.matches(normalizedAddress)) {
        null
    } else {
        normalizedAddress
    }
}

internal fun wifiDirectSocketCommandGuardSnapshot(
    command: WifiDirectSocketCommand,
    runtimeStatus: WifiDirectRuntimeStatus,
    availability: WifiDirectSocketCommandAvailability
): WifiDirectSocketCommandGuardSnapshot {
    val connectionStatus = runtimeStatus.connectionStatus
    val accepted = when (command) {
        WifiDirectSocketCommand.START_SERVER -> availability.canStartServer
        WifiDirectSocketCommand.CONNECT_CLIENT -> availability.canConnectClient
        WifiDirectSocketCommand.CLOSE_SOCKET -> availability.canCloseSocket
        WifiDirectSocketCommand.NONE -> false
    }
    val blockedReason = when (command) {
        WifiDirectSocketCommand.START_SERVER -> availability.startServerBlockedReason
        WifiDirectSocketCommand.CONNECT_CLIENT -> availability.connectClientBlockedReason
        WifiDirectSocketCommand.CLOSE_SOCKET -> null
        WifiDirectSocketCommand.NONE -> null
    }
    return WifiDirectSocketCommandGuardSnapshot(
        command = command,
        role = connectionStatus.role,
        groupFormed = connectionStatus.groupFormed,
        rawOwnerAddress = connectionStatus.groupOwnerAddress?.trim()?.takeIf { it.isNotEmpty() },
        connectHost = availability.connectHost,
        accepted = accepted,
        blockedReason = blockedReason
    )
}

internal fun wifiDirectSocketCommandGuardSummary(
    snapshot: WifiDirectSocketCommandGuardSnapshot
): String {
    val rawOwnerAddress = snapshot.rawOwnerAddress ?: "none"
    val host = snapshot.connectHost ?: rawOwnerAddress
    val blockedReason = snapshot.blockedReason ?: "none"
    return "command=${wifiDirectSocketCommandLabel(snapshot.command)} " +
        "role=${wifiDirectSocketRoleLabel(snapshot.role)} " +
        "group=${wifiDirectSocketGroupLabel(snapshot.groupFormed)} " +
        "host=$host accepted=${snapshot.accepted} blocked=$blockedReason"
}

internal fun logWifiDirectSocketCommandGuard(
    snapshot: WifiDirectSocketCommandGuardSnapshot
) {
    runCatching {
        Log.d(
            wifiDirectSocketCommandGuardLogTag,
            wifiDirectSocketCommandGuardSummary(snapshot)
        )
    }
}

private fun wifiDirectSocketCommandLabel(
    command: WifiDirectSocketCommand
): String {
    return when (command) {
        WifiDirectSocketCommand.NONE -> "none"
        WifiDirectSocketCommand.START_SERVER -> "startServer"
        WifiDirectSocketCommand.CONNECT_CLIENT -> "connectClient"
        WifiDirectSocketCommand.CLOSE_SOCKET -> "closeSocket"
    }
}

private fun wifiDirectSocketRoleLabel(
    role: WifiDirectConnectionRole
): String {
    return when (role) {
        WifiDirectConnectionRole.GROUP_OWNER -> "groupOwner"
        WifiDirectConnectionRole.CLIENT -> "client"
        WifiDirectConnectionRole.UNKNOWN -> "unknown"
    }
}

private fun wifiDirectSocketGroupLabel(
    state: WifiDirectGroupFormedState
): String {
    return when (state) {
        WifiDirectGroupFormedState.YES -> "yes"
        WifiDirectGroupFormedState.NO -> "no"
        WifiDirectGroupFormedState.UNKNOWN -> "unknown"
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
