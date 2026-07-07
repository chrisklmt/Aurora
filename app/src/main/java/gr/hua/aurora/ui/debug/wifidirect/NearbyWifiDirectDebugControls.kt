package gr.hua.aurora.ui.debug.wifidirect

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gr.hua.aurora.ui.components.DebugInfoCard
import gr.hua.aurora.wifidirect.WifiDirectPeer
import gr.hua.aurora.wifidirect.wifiDirectConnectRequestDebugText
import gr.hua.aurora.wifidirect.controller.wifiDirectDiscoveryBlockedReason
import gr.hua.aurora.wifidirect.debug.WifiDirectGlobalDebugSendDiagnostics
import gr.hua.aurora.wifidirect.debug.WifiDirectPrivateDebugSendDiagnostics
import gr.hua.aurora.wifidirect.debug.WifiDirectReceiveBridgeDiagnostics
import gr.hua.aurora.wifidirect.debug.WifiDirectSendBridgeDiagnostics
import gr.hua.aurora.wifidirect.debug.WifiDirectSmokeTestDiagnostics
import gr.hua.aurora.wifidirect.debug.wifiDirectGlobalDebugSendStateSummary
import gr.hua.aurora.wifidirect.debug.wifiDirectReceiveBridgeStateSummary
import gr.hua.aurora.wifidirect.debug.wifiDirectSendBridgeStateSummary
import gr.hua.aurora.wifidirect.frame.WifiDirectFrameTransportState
import gr.hua.aurora.wifidirect.frame.WifiDirectTransportAdapterDiagnostics
import gr.hua.aurora.wifidirect.frame.WifiDirectTransportAdapterState
import gr.hua.aurora.wifidirect.runtime.WifiDirectConnectionRole
import gr.hua.aurora.wifidirect.runtime.WifiDirectConnectionState
import gr.hua.aurora.wifidirect.runtime.WifiDirectDiscoveryState
import gr.hua.aurora.wifidirect.runtime.WifiDirectGroupFormedState
import gr.hua.aurora.wifidirect.runtime.WifiDirectRolePreference
import gr.hua.aurora.wifidirect.runtime.WifiDirectRuntimeStatus
import gr.hua.aurora.wifidirect.runtime.wifiDirectRolePreferenceSummary
import gr.hua.aurora.wifidirect.socket.WifiDirectSocketCommand
import gr.hua.aurora.wifidirect.socket.WifiDirectSocketCommandAvailability
import gr.hua.aurora.wifidirect.socket.WifiDirectSocketDiagnostics
import gr.hua.aurora.wifidirect.socket.WifiDirectSocketState
import gr.hua.aurora.wifidirect.socket.logWifiDirectSocketCommandGuard
import gr.hua.aurora.wifidirect.socket.wifiDirectEffectiveFrameTransportState
import gr.hua.aurora.wifidirect.socket.wifiDirectSocketCommandAvailability
import gr.hua.aurora.wifidirect.socket.wifiDirectSocketCommandGuardSnapshot
import gr.hua.aurora.wifidirect.socket.wifiDirectSocketConnectHostOrNull
import gr.hua.aurora.wifidirect.socket.wifiDirectSocketCommandResultSummary
import gr.hua.aurora.wifidirect.socket.wifiDirectSocketCommandSummary
import gr.hua.aurora.wifidirect.socket.wifiDirectSocketFrameReadinessReason

private const val nearbyWifiDirectDebugControlsLogTag = "NearbyWifiDirectDebugControls"

internal data class NearbyWifiDirectDebugControlsState(
    val canStartDiscovery: Boolean,
    val canStopDiscovery: Boolean,
    val canDisconnect: Boolean,
    val disconnectLabel: String,
    val startDisabledReason: String? = null
)

internal fun nearbyWifiDirectDebugControlsState(
    runtimeStatus: WifiDirectRuntimeStatus
): NearbyWifiDirectDebugControlsState {
    val startDisabledReason = if (
        runtimeStatus.discoveryState == WifiDirectDiscoveryState.ACTIVE
    ) {
        "Wi-Fi Direct discovery already active."
    } else {
        wifiDirectDiscoveryBlockedReason(runtimeStatus.permissionStatus)
    }
    return NearbyWifiDirectDebugControlsState(
        canStartDiscovery = startDisabledReason == null,
        canStopDiscovery =
            runtimeStatus.discoveryState == WifiDirectDiscoveryState.ACTIVE,
        canDisconnect = nearbyCanDisconnectWifiDirect(runtimeStatus),
        disconnectLabel = nearbyWifiDirectDisconnectLabel(runtimeStatus),
        startDisabledReason = startDisabledReason
    )
}

internal data class NearbyWifiDirectSocketControlsState(
    val canStartServer: Boolean,
    val canConnectClient: Boolean,
    val canSendFrame: Boolean,
    val canSendAdapterFrame: Boolean,
    val canSendBridgedFrame: Boolean = false,
    val canSendSmokeTestFrame: Boolean = false,
    val canCloseSocket: Boolean,
    val connectHost: String? = null,
    val startServerBlockedReason: String? = null,
    val connectClientBlockedReason: String? = null,
    val helpText: String? = null
)

internal data class NearbyWifiDirectSocketSetupUiState(
    val headline: String,
    val roleText: String? = null,
    val hostText: String? = null,
    val nextStepText: String,
    val supportingText: String? = null,
    val primaryActionLabel: String? = null,
    val showPrimaryStartServer: Boolean = false,
    val showPrimaryConnectClient: Boolean = false,
    val showCloseSocket: Boolean = false,
    val showFrameActions: Boolean = false,
    val showBridgeControls: Boolean = false,
    val showGlobalControls: Boolean = false
)

internal data class NearbyWifiDirectReceiveBridgeToggleState(
    val showControls: Boolean,
    val canToggle: Boolean,
    val blockedReason: String? = null,
    val socketConnected: Boolean,
    val frameReady: Boolean,
    val adapterReady: Boolean,
    val effectiveReady: Boolean
)

internal fun nearbyWifiDirectReceiveBridgeToggleState(
    runtimeStatus: WifiDirectRuntimeStatus,
    socketDiagnostics: WifiDirectSocketDiagnostics,
    adapterDiagnostics: WifiDirectTransportAdapterDiagnostics,
    receiveBridgeDiagnostics: WifiDirectReceiveBridgeDiagnostics
): NearbyWifiDirectReceiveBridgeToggleState {
    val groupConnected =
        runtimeStatus.connectionStatus.state == WifiDirectConnectionState.CONNECTED &&
            runtimeStatus.connectionStatus.groupFormed ==
            WifiDirectGroupFormedState.YES
    val socketConnected = socketDiagnostics.isConnected
    val frameReady =
        wifiDirectEffectiveFrameTransportState(socketDiagnostics) == WifiDirectFrameTransportState.READY
    val adapterReady = adapterDiagnostics.state == WifiDirectTransportAdapterState.READY
    val effectiveReady = socketConnected && frameReady && adapterReady
    val blockedReason = when {
        receiveBridgeDiagnostics.enabled -> null
        !socketConnected || !frameReady -> {
            "Cannot enable receive bridge: socket/frame not ready (" +
                "${wifiDirectSocketFrameReadinessReason(socketDiagnostics) ?: "unknown"})."
        }
        !adapterReady -> {
            "Cannot enable receive bridge: adapter not ready (" +
                "${adapterDiagnostics.notReadyReason ?: adapterDiagnostics.lastError ?: "Wi-Fi Direct transport adapter not ready."})."
        }
        else -> null
    }

    return NearbyWifiDirectReceiveBridgeToggleState(
        showControls = receiveBridgeDiagnostics.enabled || (groupConnected && (socketConnected || frameReady)),
        canToggle = receiveBridgeDiagnostics.enabled || blockedReason == null,
        blockedReason = blockedReason,
        socketConnected = socketConnected,
        frameReady = frameReady,
        adapterReady = adapterReady,
        effectiveReady = effectiveReady
    )
}

internal fun nearbyHandleReceiveBridgeToggleTap(
    toggleState: NearbyWifiDirectReceiveBridgeToggleState,
    runtimeStatus: WifiDirectRuntimeStatus,
    socketDiagnostics: WifiDirectSocketDiagnostics,
    sendBridgeEnabled: Boolean,
    globalSendEnabled: Boolean,
    receiveBridgeEnabled: Boolean,
    onReportReceiveBridgeToggleBlocked: (String) -> Unit,
    onSetReceiveBridgeEnabled: (Boolean) -> Unit
): Boolean {
    safeNearbyWifiDirectDebugControlsLog(
        "receiveBridge tap received: role=${runtimeStatus.connectionStatus.role.name.lowercase()} " +
            "socketConnected=${toggleState.socketConnected} readLoopActive=${socketDiagnostics.isReadLoopActive} " +
            "frameReady=${toggleState.frameReady} adapterReady=${toggleState.adapterReady} " +
            "effectiveReady=${toggleState.effectiveReady} sendBridgeEnabled=$sendBridgeEnabled " +
            "globalSendEnabled=$globalSendEnabled currentEnabled=$receiveBridgeEnabled " +
            "blockedReason=${toggleState.blockedReason ?: "none"}"
    )
    if (!receiveBridgeEnabled && !toggleState.canToggle) {
        val blockedReason = toggleState.blockedReason ?: "Cannot enable receive bridge."
        safeNearbyWifiDirectDebugControlsLog(
            "receiveBridge tap blocked: $blockedReason callbackInvoked=false"
        )
        onReportReceiveBridgeToggleBlocked(blockedReason)
        return false
    }
    safeNearbyWifiDirectDebugControlsLog(
        "receiveBridge tap accepted: enabling=${!receiveBridgeEnabled} callbackInvoked=true"
    )
    onSetReceiveBridgeEnabled(!receiveBridgeEnabled)
    return true
}

internal fun nearbyHandleResetWifiDirectGroupTap(
    runtimeStatus: WifiDirectRuntimeStatus,
    socketDiagnostics: WifiDirectSocketDiagnostics,
    onCloseSocket: () -> Unit,
    onSetSendBridgeEnabled: (Boolean) -> Unit,
    onSetReceiveBridgeEnabled: (Boolean) -> Unit,
    onResetDiagnostics: () -> Unit,
    onStopDiscovery: () -> Unit,
    onDisconnect: () -> Unit,
    onRefreshStatus: () -> Unit
): String {
    safeNearbyWifiDirectDebugControlsLog(
        "resetGroup tap received: discovery=${runtimeStatus.discoveryState.name.lowercase()} " +
            "connection=${runtimeStatus.connectionStatus.state.name.lowercase()} " +
            "role=${runtimeStatus.connectionStatus.role.name.lowercase()} " +
            "socket=${socketDiagnostics.state.name.lowercase()} connected=${socketDiagnostics.isConnected}"
    )
    val resultText = runCatching {
        onCloseSocket()
        onSetSendBridgeEnabled(false)
        onSetReceiveBridgeEnabled(false)
        onResetDiagnostics()
        if (runtimeStatus.discoveryState == WifiDirectDiscoveryState.ACTIVE) {
            onStopDiscovery()
        }
        onDisconnect()
        onRefreshStatus()
        "Wi-Fi Direct group reset requested."
    }.getOrElse { error ->
        "Wi-Fi Direct group reset failed: ${error::class.java.simpleName}"
    }
    safeNearbyWifiDirectDebugControlsLog("resetGroup result: $resultText")
    return resultText
}

internal fun nearbyWifiDirectRolePreferenceHelpLines(): List<String> {
    return listOf(
        "Role preference applies only when this device starts the Connect action.",
        "The other device's preference is not used unless that device initiates Connect.",
        "Android may still choose the final group owner.",
        "If Android keeps choosing the same host, continue testing with that device as group owner.",
        "Uninstalling the app may not reset Android Wi-Fi Direct group-owner selection.",
        "Use Reset Wi-Fi Direct group, then reconnect."
    )
}

internal fun nearbyWifiDirectRolePreferenceOutcomeLines(
    requestedPreference: WifiDirectRolePreference?,
    runtimeStatus: WifiDirectRuntimeStatus
): List<String> {
    if (requestedPreference == null) {
        return emptyList()
    }

    val lines = mutableListOf(
        "Requested role preference: ${wifiDirectRolePreferenceSummary(requestedPreference)}"
    )
    val connectionStatus = runtimeStatus.connectionStatus
    if (connectionStatus.state == WifiDirectConnectionState.CONNECTED &&
        connectionStatus.groupFormed == WifiDirectGroupFormedState.YES
    ) {
        lines += when (connectionStatus.role) {
            WifiDirectConnectionRole.GROUP_OWNER ->
                "Actual role: group owner. Android selected final role."
            WifiDirectConnectionRole.CLIENT ->
                "Actual role: client. Android selected final role."
            WifiDirectConnectionRole.UNKNOWN ->
                "Actual role: unknown. Android selected final role."
        }
    }
    return lines
}

internal fun nearbyWifiDirectSocketActionResultText(
    diagnostics: WifiDirectSocketDiagnostics
): String {
    val sequencePrefix = if (diagnostics.lastCommandSequence > 0L) {
        "#${diagnostics.lastCommandSequence} "
    } else {
        ""
    }
    return "Socket action: ${sequencePrefix}${wifiDirectSocketCommandSummary(diagnostics)} | ${wifiDirectSocketCommandResultSummary(diagnostics)}"
}

internal fun nearbyWifiDirectSocketAttemptSummaryText(
    diagnostics: WifiDirectSocketDiagnostics
): String {
    return "Attempts: server ${diagnostics.serverStartAttempts} | client ${diagnostics.clientConnectAttempts} | close ${diagnostics.closeAttempts}"
}

internal fun nearbyWifiDirectSocketHostText(
    controlsState: NearbyWifiDirectSocketControlsState
): String {
    return "Client host: ${controlsState.connectHost ?: "unavailable"}"
}

internal fun nearbyHandleStartSocketServerTap(
    runtimeStatus: WifiDirectRuntimeStatus,
    controlsState: NearbyWifiDirectSocketControlsState,
    onStartSocketServer: (String?) -> Unit
): Boolean {
    val hostHint = runtimeStatus.connectionStatus.groupOwnerAddress?.trim()?.takeIf { it.isNotEmpty() }
    val guardSnapshot = wifiDirectSocketCommandGuardSnapshot(
        command = WifiDirectSocketCommand.START_SERVER,
        runtimeStatus = runtimeStatus,
        availability = WifiDirectSocketCommandAvailability(
            canStartServer = controlsState.canStartServer,
            canConnectClient = controlsState.canConnectClient,
            canSendFrame = controlsState.canSendFrame,
            canCloseSocket = controlsState.canCloseSocket,
            connectHost = controlsState.connectHost,
            startServerBlockedReason = controlsState.startServerBlockedReason,
            connectClientBlockedReason = controlsState.connectClientBlockedReason,
            helpText = controlsState.helpText
        )
    )
    safeNearbyWifiDirectDebugControlsLog(
        "startServer tap received: role=${runtimeStatus.connectionStatus.role.name.lowercase()} " +
            "group=${runtimeStatus.connectionStatus.groupFormed.name.lowercase()} " +
            "ownerHost=${hostHint ?: "none"}"
    )
    logWifiDirectSocketCommandGuard(guardSnapshot)
    if (!controlsState.canStartServer) {
        safeNearbyWifiDirectDebugControlsLog(
            "tap startServer blocked: ${controlsState.startServerBlockedReason ?: "unknown"}"
        )
        return false
    }
    safeNearbyWifiDirectDebugControlsLog(
        "tap startServer accepted: invoking callback"
    )
    onStartSocketServer(hostHint)
    return true
}

internal fun nearbyHandleConnectSocketClientTap(
    runtimeStatus: WifiDirectRuntimeStatus,
    controlsState: NearbyWifiDirectSocketControlsState,
    onConnectSocketClient: (String) -> Unit
): Boolean {
    val rawOwnerAddress = runtimeStatus.connectionStatus.groupOwnerAddress?.trim()?.takeIf { it.isNotEmpty() }
    val guardSnapshot = wifiDirectSocketCommandGuardSnapshot(
        command = WifiDirectSocketCommand.CONNECT_CLIENT,
        runtimeStatus = runtimeStatus,
        availability = WifiDirectSocketCommandAvailability(
            canStartServer = controlsState.canStartServer,
            canConnectClient = controlsState.canConnectClient,
            canSendFrame = controlsState.canSendFrame,
            canCloseSocket = controlsState.canCloseSocket,
            connectHost = controlsState.connectHost,
            startServerBlockedReason = controlsState.startServerBlockedReason,
            connectClientBlockedReason = controlsState.connectClientBlockedReason,
            helpText = controlsState.helpText
        )
    )
    safeNearbyWifiDirectDebugControlsLog(
        "connectClient tap received: role=${runtimeStatus.connectionStatus.role.name.lowercase()} " +
            "group=${runtimeStatus.connectionStatus.groupFormed.name.lowercase()} " +
            "ownerHost=${controlsState.connectHost ?: rawOwnerAddress ?: "none"}"
    )
    logWifiDirectSocketCommandGuard(guardSnapshot)
    val host = controlsState.connectHost
    if (!controlsState.canConnectClient || host == null) {
        safeNearbyWifiDirectDebugControlsLog(
            "tap connectClient blocked: ${controlsState.connectClientBlockedReason ?: "unknown"}"
        )
        return false
    }
    safeNearbyWifiDirectDebugControlsLog(
        "tap connectClient accepted: invoking callback host=$host"
    )
    onConnectSocketClient(host)
    return true
}

internal fun nearbyWifiDirectSocketSetupUiState(
    runtimeStatus: WifiDirectRuntimeStatus,
    socketControlsState: NearbyWifiDirectSocketControlsState,
    socketDiagnostics: WifiDirectSocketDiagnostics,
    adapterDiagnostics: WifiDirectTransportAdapterDiagnostics
): NearbyWifiDirectSocketSetupUiState {
    val groupConnected =
        runtimeStatus.connectionStatus.state == WifiDirectConnectionState.CONNECTED &&
            runtimeStatus.connectionStatus.groupFormed ==
            WifiDirectGroupFormedState.YES
    val adapterReady = adapterDiagnostics.state == WifiDirectTransportAdapterState.READY
    val socketReady = socketControlsState.canSendFrame
    val canCloseSocket = socketControlsState.canCloseSocket
    val roleText = when (runtimeStatus.connectionStatus.role) {
        WifiDirectConnectionRole.GROUP_OWNER -> "Role: group owner"
        WifiDirectConnectionRole.CLIENT -> "Role: client"
        WifiDirectConnectionRole.UNKNOWN -> "Role: unknown"
    }

    if (!groupConnected) {
        return NearbyWifiDirectSocketSetupUiState(
            headline = "Socket setup: waiting for Wi-Fi Direct group",
            nextStepText = "Next step: connect/group devices first."
        )
    }

    if (socketReady) {
        return NearbyWifiDirectSocketSetupUiState(
            headline = "Socket/frame: ready",
            roleText = roleText,
            hostText = socketControlsState.connectHost?.let { "Group owner host: $it" },
            nextStepText = if (adapterReady) {
                "Next step: Send debug frame or enable bridges."
            } else {
                "Next step: Verify adapter/frame setup."
            },
            supportingText = "Adapter: ${if (adapterReady) "ready" else "not ready"}",
            showCloseSocket = canCloseSocket,
            showFrameActions = true,
            showBridgeControls = adapterReady,
            showGlobalControls = adapterReady
        )
    }

    return when (runtimeStatus.connectionStatus.role) {
        WifiDirectConnectionRole.GROUP_OWNER -> {
            val waitingText = when (socketDiagnostics.state) {
                WifiDirectSocketState.STARTING_SERVER -> "Starting socket server..."
                WifiDirectSocketState.SERVER_LISTENING -> "Waiting for client to connect."
                else -> null
            }
            NearbyWifiDirectSocketSetupUiState(
                headline = "Socket setup: waiting for server connection",
                roleText = roleText,
                nextStepText = if (socketDiagnostics.state == WifiDirectSocketState.SERVER_LISTENING) {
                    "Next step: On the other device, connect socket client."
                } else {
                    "Next step: Start socket server."
                },
                supportingText = waitingText,
                primaryActionLabel = "Start socket server",
                showPrimaryStartServer = socketDiagnostics.state !in setOf(
                    WifiDirectSocketState.STARTING_SERVER,
                    WifiDirectSocketState.SERVER_LISTENING
                ),
                showCloseSocket = canCloseSocket
            )
        }
        WifiDirectConnectionRole.CLIENT -> {
            val rawOwnerAddress = runtimeStatus.connectionStatus.groupOwnerAddress?.trim()
                ?.takeIf { it.isNotEmpty() }
            val connectHost = socketControlsState.connectHost
            val blockedHostReason = when {
                rawOwnerAddress == null -> "Cannot connect: group owner IP missing."
                wifiDirectSocketConnectHostOrNull(rawOwnerAddress) == null ->
                    "Cannot connect: group owner host is not an IP."
                else -> null
            }
            val connectingText = when (socketDiagnostics.state) {
                WifiDirectSocketState.CONNECTING -> "Connecting socket client..."
                else -> null
            }
            NearbyWifiDirectSocketSetupUiState(
                headline = "Socket setup: waiting for client connection",
                roleText = roleText,
                hostText = if (connectHost != null) {
                    "Group owner host: $connectHost"
                } else if (rawOwnerAddress != null) {
                    "Group owner host: $rawOwnerAddress"
                } else {
                    null
                },
                nextStepText = if (connectHost != null) {
                    "Next step: Connect socket client."
                } else {
                    blockedHostReason ?: "Next step: Connect socket client."
                },
                supportingText = connectingText ?: blockedHostReason,
                primaryActionLabel = "Connect socket client",
                showPrimaryConnectClient = socketDiagnostics.state != WifiDirectSocketState.CONNECTING,
                showCloseSocket = canCloseSocket
            )
        }
        WifiDirectConnectionRole.UNKNOWN -> NearbyWifiDirectSocketSetupUiState(
            headline = "Socket setup: waiting for Wi-Fi Direct role",
            roleText = roleText,
            nextStepText = "Next step: verify the Wi-Fi Direct connection role.",
            supportingText = socketControlsState.helpText,
            showCloseSocket = canCloseSocket
        )
    }
}

private fun nearbyLogForceRefreshSocketStatusTap(
    onRefreshStatus: () -> Unit
) {
    safeNearbyWifiDirectDebugControlsLog(
        "tap forceRefreshSocketStatus: invoking refresh callback"
    )
    onRefreshStatus()
}

private fun safeNearbyWifiDirectDebugControlsLog(
    message: String
) {
    runCatching {
        Log.d(
            nearbyWifiDirectDebugControlsLogTag,
            message
        )
    }
}

internal fun nearbyWifiDirectSocketControlsState(
    runtimeStatus: WifiDirectRuntimeStatus,
    diagnostics: WifiDirectSocketDiagnostics,
    adapterDiagnostics: WifiDirectTransportAdapterDiagnostics = WifiDirectTransportAdapterDiagnostics(),
    sendBridgeDiagnostics: WifiDirectSendBridgeDiagnostics = WifiDirectSendBridgeDiagnostics(),
    smokeTestDiagnostics: WifiDirectSmokeTestDiagnostics = WifiDirectSmokeTestDiagnostics()
): NearbyWifiDirectSocketControlsState {
    return wifiDirectSocketCommandAvailability(
        runtimeStatus = runtimeStatus,
        diagnostics = diagnostics
    ).toNearbySocketControlsState(
        canSendAdapterFrame = adapterDiagnostics.state == WifiDirectTransportAdapterState.READY,
        canSendBridgedFrame = adapterDiagnostics.state == WifiDirectTransportAdapterState.READY &&
            sendBridgeDiagnostics.enabled,
        canSendSmokeTestFrame = smokeTestDiagnostics.ready
    )
}

internal data class NearbyWifiDirectPeerActionState(
    val connectLabel: String,
    val canConnect: Boolean,
    val disabledReason: String? = null
)

internal fun nearbyWifiDirectPeerActionState(
    runtimeStatus: WifiDirectRuntimeStatus,
    peer: WifiDirectPeer
): NearbyWifiDirectPeerActionState {
    val blockedReason = wifiDirectDiscoveryBlockedReason(runtimeStatus.permissionStatus)
    if (blockedReason != null) {
        return NearbyWifiDirectPeerActionState(
            connectLabel = "Connect",
            canConnect = false,
            disabledReason = blockedReason
        )
    }
    if (peer.deviceAddress.isNullOrBlank()) {
        return NearbyWifiDirectPeerActionState(
            connectLabel = "Connect",
            canConnect = false,
            disabledReason = "Wi-Fi Direct peer address unavailable."
        )
    }

    val connectionStatus = runtimeStatus.connectionStatus
    val isTargetPeer = gr.hua.aurora.wifidirect.controller.wifiDirectPeerMatches(connectionStatus.targetPeer, peer)
    return when (connectionStatus.state) {
        WifiDirectConnectionState.CONNECTING -> {
            if (isTargetPeer) {
                NearbyWifiDirectPeerActionState(
                    connectLabel = "Connecting",
                    canConnect = false
                )
            } else {
                NearbyWifiDirectPeerActionState(
                    connectLabel = "Connect",
                    canConnect = false,
                    disabledReason = "Wi-Fi Direct connection already in progress."
                )
            }
        }
        WifiDirectConnectionState.CONNECTED -> {
            if (isTargetPeer) {
                NearbyWifiDirectPeerActionState(
                    connectLabel = "Connected",
                    canConnect = false
                )
            } else {
                NearbyWifiDirectPeerActionState(
                    connectLabel = "Connect",
                    canConnect = false,
                    disabledReason = "Disconnect current Wi-Fi Direct peer first."
                )
            }
        }
        WifiDirectConnectionState.DISCONNECTING -> {
            NearbyWifiDirectPeerActionState(
                connectLabel = "Disconnecting",
                canConnect = false,
                disabledReason = "Wi-Fi Direct disconnect already in progress."
            )
        }
        WifiDirectConnectionState.FAILED -> {
            NearbyWifiDirectPeerActionState(
                connectLabel = if (isTargetPeer) "Retry connect" else "Connect",
                canConnect = true
            )
        }
        WifiDirectConnectionState.DISCONNECTED -> {
            NearbyWifiDirectPeerActionState(
                connectLabel = "Connect",
                canConnect = true
            )
        }
    }
}

internal fun nearbyWifiDirectDisconnectLabel(
    runtimeStatus: WifiDirectRuntimeStatus
): String {
    return when (runtimeStatus.connectionStatus.state) {
        WifiDirectConnectionState.CONNECTING -> "Cancel Wi-Fi Direct"
        WifiDirectConnectionState.DISCONNECTING -> "Disconnecting..."
        WifiDirectConnectionState.FAILED -> "Clear Wi-Fi Direct"
        else -> "Disconnect Wi-Fi Direct"
    }
}

internal fun nearbyCanDisconnectWifiDirect(
    runtimeStatus: WifiDirectRuntimeStatus
): Boolean {
    return when (runtimeStatus.connectionStatus.state) {
        WifiDirectConnectionState.CONNECTING,
        WifiDirectConnectionState.CONNECTED,
        WifiDirectConnectionState.FAILED -> true
        WifiDirectConnectionState.DISCONNECTING,
        WifiDirectConnectionState.DISCONNECTED -> false
    }
}

@Composable
internal fun NearbyWifiDirectDebugControls(
    runtimeStatus: WifiDirectRuntimeStatus,
    socketDiagnostics: WifiDirectSocketDiagnostics,
    adapterDiagnostics: WifiDirectTransportAdapterDiagnostics,
    sendBridgeDiagnostics: WifiDirectSendBridgeDiagnostics,
    globalSendDiagnostics: WifiDirectGlobalDebugSendDiagnostics,
    privateDebugSendDiagnostics: WifiDirectPrivateDebugSendDiagnostics,
    smokeTestDiagnostics: WifiDirectSmokeTestDiagnostics,
    receiveBridgeDiagnostics: WifiDirectReceiveBridgeDiagnostics,
    onRequestPermissions: () -> Unit,
    onOpenPermissionSettings: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onRefreshStatus: () -> Unit,
    onStartDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
    onConnectToPeer: (WifiDirectPeer, WifiDirectRolePreference) -> Unit,
    onDisconnect: () -> Unit,
    onStartSocketServer: (String?) -> Unit,
    onConnectSocketClient: (String) -> Unit,
    onSendSocketFrame: () -> Unit,
    onSendAdapterFrame: () -> Unit,
    onSendBridgedFrame: () -> Unit,
    onSendSmokeTestFrame: () -> Unit,
    onSetGlobalDebugSendEnabled: (Boolean) -> Unit,
    onSetSendBridgeEnabled: (Boolean) -> Unit,
    onSetReceiveBridgeEnabled: (Boolean) -> Unit,
    onReportReceiveBridgeToggleBlocked: (String) -> Unit,
    onResetDiagnostics: () -> Unit,
    onCloseSocket: () -> Unit
) {
    var rolePreference by remember {
        mutableStateOf(WifiDirectRolePreference.AUTOMATIC)
    }
    var lastGroupResetStatus by remember {
        mutableStateOf<String?>(null)
    }
    var lastRequestedRolePreference by remember {
        mutableStateOf<WifiDirectRolePreference?>(null)
    }
    var showWifiDirectDetails by remember {
        mutableStateOf(false)
    }
    var showSocketDiagnostics by remember {
        mutableStateOf(false)
    }
    var showBridgeDiagnostics by remember {
        mutableStateOf(false)
    }
    var showGlobalDiagnostics by remember {
        mutableStateOf(false)
    }
    var showRolePreferenceHelp by remember {
        mutableStateOf(false)
    }
    var showManualGuide by remember {
        mutableStateOf(false)
    }
    val controlsState = nearbyWifiDirectDebugControlsState(runtimeStatus)
    val socketControlsState = nearbyWifiDirectSocketControlsState(
        runtimeStatus = runtimeStatus,
        diagnostics = socketDiagnostics,
        adapterDiagnostics = adapterDiagnostics,
        sendBridgeDiagnostics = sendBridgeDiagnostics,
        smokeTestDiagnostics = smokeTestDiagnostics
    )
    val readiness = nearbyWifiDirectGlobalDebugReadiness(
        runtimeStatus = runtimeStatus,
        socketDiagnostics = socketDiagnostics,
        adapterDiagnostics = adapterDiagnostics,
        sendBridgeDiagnostics = sendBridgeDiagnostics,
        globalSendDiagnostics = globalSendDiagnostics,
        receiveBridgeDiagnostics = receiveBridgeDiagnostics
    )
    val manualReadiness = nearbyWifiDirectManualTestReadiness(
        runtimeStatus = runtimeStatus,
        socketDiagnostics = socketDiagnostics,
        adapterDiagnostics = adapterDiagnostics,
        sendBridgeDiagnostics = sendBridgeDiagnostics,
        globalSendDiagnostics = globalSendDiagnostics,
        privateDebugSendDiagnostics = privateDebugSendDiagnostics,
        receiveBridgeDiagnostics = receiveBridgeDiagnostics
    )
    val permissionBlocker = nearbyWifiDirectPermissionBlocker(runtimeStatus)
    val disabledBlocker = nearbyWifiDirectDisabledBlocker(runtimeStatus)
    val nextStep = nearbyWifiDirectManualNextStep(
        runtimeStatus = runtimeStatus,
        socketDiagnostics = socketDiagnostics,
        adapterDiagnostics = adapterDiagnostics,
        sendBridgeDiagnostics = sendBridgeDiagnostics,
        globalSendDiagnostics = globalSendDiagnostics,
        privateDebugSendDiagnostics = privateDebugSendDiagnostics,
        receiveBridgeDiagnostics = receiveBridgeDiagnostics
    )
    val socketSetupUiState = nearbyWifiDirectSocketSetupUiState(
        runtimeStatus = runtimeStatus,
        socketControlsState = socketControlsState,
        socketDiagnostics = socketDiagnostics,
        adapterDiagnostics = adapterDiagnostics
    )
    val receiveBridgeToggleState = nearbyWifiDirectReceiveBridgeToggleState(
        runtimeStatus = runtimeStatus,
        socketDiagnostics = socketDiagnostics,
        adapterDiagnostics = adapterDiagnostics,
        receiveBridgeDiagnostics = receiveBridgeDiagnostics
    )
    val showBridgeControls =
        socketSetupUiState.showBridgeControls ||
            receiveBridgeToggleState.showControls ||
            sendBridgeDiagnostics.enabled
    val compactSummary = nearbyWifiDirectCompactSummary(
        runtimeStatus = runtimeStatus,
        socketDiagnostics = socketDiagnostics,
        adapterDiagnostics = adapterDiagnostics,
        sendBridgeDiagnostics = sendBridgeDiagnostics,
        globalSendDiagnostics = globalSendDiagnostics,
        privateDebugSendDiagnostics = privateDebugSendDiagnostics,
        receiveBridgeDiagnostics = receiveBridgeDiagnostics
    )

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Wi-Fi Direct debug",
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = "Status: ${compactSummary.status}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Discovery: ${compactSummary.discovery} | Group: ${compactSummary.group}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Role: ${compactSummary.role} | Socket: ${compactSummary.socket} | Adapter: ${compactSummary.adapter}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Bridges: send ${compactSummary.sendBridge}, receive ${compactSummary.receiveBridge} | Global: ${compactSummary.globalDebugSend}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Next step: ${compactSummary.nextStep}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            permissionBlocker?.let { blocker ->
                NearbyWifiDirectControlGroup(title = blocker.title) {
                    Text(
                        text = blocker.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Missing: ${blocker.missingPermissionName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = blocker.settingsInstruction,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    NearbyWifiDirectActionButton(
                        label = "Grant permission",
                        onClick = onRequestPermissions
                    )
                    NearbyWifiDirectActionButton(
                        label = "Open app settings",
                        onClick = onOpenPermissionSettings
                    )
                }
            }
            disabledBlocker?.let { blocker ->
                NearbyWifiDirectControlGroup(title = blocker.title) {
                    Text(
                        text = blocker.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    NearbyWifiDirectActionButton(
                        label = blocker.settingsActionLabel,
                        onClick = onOpenWifiSettings
                    )
                    NearbyWifiDirectActionButton(
                        label = blocker.refreshActionLabel,
                        onClick = onRefreshStatus
                    )
                }
            }
            NearbyWifiDirectControlGroup(title = "Next step") {
                Text(
                    text = nextStep.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                nextStep.detail?.let { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            NearbyWifiDirectControlGroup(title = "Discovery") {
                NearbyWifiDirectButtonRow(
                    firstLabel = "Start Wi-Fi Direct",
                    firstEnabled = controlsState.canStartDiscovery,
                    firstOnClick = onStartDiscovery,
                    secondLabel = "Stop Wi-Fi Direct",
                    secondEnabled = controlsState.canStopDiscovery,
                    secondOnClick = onStopDiscovery
                )
                Text(
                    text = "Discovery: ${readiness.discoveryStatus}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                controlsState.startDisabledReason?.let { reasonText ->
                    Text(
                        text = reasonText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            NearbyWifiDirectControlGroup(title = "Connection/group") {
                NearbyWifiDirectActionButton(
                    label = controlsState.disconnectLabel,
                    enabled = controlsState.canDisconnect,
                    onClick = onDisconnect
                )
                Text(
                    text = "Next connect preference: ${wifiDirectRolePreferenceSummary(rolePreference)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                NearbyWifiDirectButtonRow(
                    firstLabel = "Automatic",
                    firstEnabled = rolePreference != WifiDirectRolePreference.AUTOMATIC,
                    firstOnClick = {
                        rolePreference = WifiDirectRolePreference.AUTOMATIC
                    },
                    secondLabel = "Prefer owner",
                    secondEnabled = rolePreference != WifiDirectRolePreference.PREFER_GROUP_OWNER,
                    secondOnClick = {
                        rolePreference = WifiDirectRolePreference.PREFER_GROUP_OWNER
                    }
                )
                NearbyWifiDirectActionButton(
                    label = "Prefer client",
                    enabled = rolePreference != WifiDirectRolePreference.PREFER_CLIENT,
                    onClick = {
                        rolePreference = WifiDirectRolePreference.PREFER_CLIENT
                    }
                )
                NearbyWifiDirectActionButton(
                    label = "Reset Wi-Fi Direct group",
                    onClick = {
                        lastRequestedRolePreference = null
                        lastGroupResetStatus = nearbyHandleResetWifiDirectGroupTap(
                            runtimeStatus = runtimeStatus,
                            socketDiagnostics = socketDiagnostics,
                            onCloseSocket = onCloseSocket,
                            onSetSendBridgeEnabled = onSetSendBridgeEnabled,
                            onSetReceiveBridgeEnabled = onSetReceiveBridgeEnabled,
                            onResetDiagnostics = onResetDiagnostics,
                            onStopDiscovery = onStopDiscovery,
                            onDisconnect = onDisconnect,
                            onRefreshStatus = onRefreshStatus
                        )
                    }
                )
                lastGroupResetStatus?.let { statusText ->
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                runtimeStatus.connectionStatus.lastError?.takeIf { it.isNotBlank() }?.let { errorText ->
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                runtimeStatus.lastError?.takeIf { it.isNotBlank() }?.let { errorText ->
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                runtimeStatus.peers.forEach { peer ->
                    NearbyWifiDirectPeerActionRow(
                        peer = peer,
                        actionState = nearbyWifiDirectPeerActionState(
                            runtimeStatus = runtimeStatus,
                            peer = peer
                        ),
                        onConnectToPeer = { selectedPeer ->
                            safeNearbyWifiDirectDebugControlsLog(
                                "connectPeer tap received: ${wifiDirectConnectRequestDebugText(selectedPeer, rolePreference)}"
                            )
                            lastRequestedRolePreference = rolePreference
                            onConnectToPeer(
                                selectedPeer,
                                rolePreference
                            )
                            rolePreference = WifiDirectRolePreference.AUTOMATIC
                        }
                    )
                }
                TextButton(
                    onClick = {
                        showRolePreferenceHelp = !showRolePreferenceHelp
                    }
                ) {
                    Text(
                        nearbyAdvancedSectionToggleLabel(
                            title = "role preference help",
                            expanded = showRolePreferenceHelp
                        )
                    )
                }
                if (showRolePreferenceHelp) {
                    DebugInfoCard(
                        card = buildNearbyWifiDirectRolePreferenceHelpCard(
                            requestedPreference = lastRequestedRolePreference,
                            runtimeStatus = runtimeStatus
                        )
                    )
                }
            }
            NearbyWifiDirectControlGroup(title = "Socket setup") {
                Text(
                    text = socketSetupUiState.headline,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                socketSetupUiState.roleText?.let { roleText ->
                    Text(
                        text = roleText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                socketSetupUiState.hostText?.let { hostText ->
                    Text(
                        text = hostText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = socketSetupUiState.nextStepText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                socketSetupUiState.supportingText?.let { supportingText ->
                    Text(
                        text = supportingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (socketSetupUiState.showPrimaryStartServer) {
                    NearbyWifiDirectActionButton(
                        label = socketSetupUiState.primaryActionLabel ?: "Start socket server",
                        enabled = socketControlsState.canStartServer,
                        onClick = {
                            nearbyHandleStartSocketServerTap(
                                runtimeStatus = runtimeStatus,
                                controlsState = socketControlsState,
                                onStartSocketServer = onStartSocketServer
                            )
                        }
                    )
                }
                if (socketSetupUiState.showPrimaryConnectClient) {
                    NearbyWifiDirectActionButton(
                        label = socketSetupUiState.primaryActionLabel ?: "Connect socket client",
                        enabled = socketControlsState.canConnectClient,
                        onClick = {
                            nearbyHandleConnectSocketClientTap(
                                runtimeStatus = runtimeStatus,
                                controlsState = socketControlsState,
                                onConnectSocketClient = onConnectSocketClient
                            )
                        }
                    )
                }
                if (socketSetupUiState.showFrameActions) {
                    NearbyWifiDirectButtonRow(
                        firstLabel = "Send debug frame",
                        firstEnabled = socketControlsState.canSendFrame,
                        firstOnClick = onSendSocketFrame,
                        secondLabel = "Send adapter frame",
                        secondEnabled = socketControlsState.canSendAdapterFrame,
                        secondOnClick = onSendAdapterFrame
                    )
                }
                if (socketSetupUiState.showCloseSocket) {
                    NearbyWifiDirectActionButton(
                        label = "Close socket",
                        enabled = socketControlsState.canCloseSocket,
                        onClick = onCloseSocket
                    )
                }
                NearbyWifiDirectActionButton(
                    label = "Force refresh socket status",
                    onClick = {
                        nearbyLogForceRefreshSocketStatusTap(onRefreshStatus)
                    }
                )
                Text(
                    text = "Socket: ${compactSummary.socket} | Adapter: ${compactSummary.adapter}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                socketControlsState.startServerBlockedReason?.takeIf {
                    !socketControlsState.canStartServer
                }?.let { reasonText ->
                    Text(
                        text = reasonText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                socketControlsState.connectClientBlockedReason?.takeIf {
                    !socketControlsState.canConnectClient
                }?.let { reasonText ->
                    Text(
                        text = reasonText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                socketDiagnostics.lastCommandError?.takeIf { it.isNotBlank() }?.let { errorText ->
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (showBridgeControls) {
                NearbyWifiDirectControlGroup(title = "Bridges") {
                    NearbyWifiDirectButtonRow(
                        firstLabel = if (sendBridgeDiagnostics.enabled) {
                            "Disable send bridge"
                        } else {
                            "Enable send bridge"
                        },
                        firstOnClick = {
                            onSetSendBridgeEnabled(!sendBridgeDiagnostics.enabled)
                        },
                        secondLabel = if (receiveBridgeDiagnostics.enabled) {
                            "Disable receive bridge"
                        } else {
                            "Enable receive bridge"
                        },
                        secondEnabled = receiveBridgeDiagnostics.enabled ||
                            receiveBridgeToggleState.canToggle,
                        secondOnClick = {
                            nearbyHandleReceiveBridgeToggleTap(
                                toggleState = receiveBridgeToggleState,
                                runtimeStatus = runtimeStatus,
                                socketDiagnostics = socketDiagnostics,
                                sendBridgeEnabled = sendBridgeDiagnostics.enabled,
                                globalSendEnabled = globalSendDiagnostics.enabled,
                                receiveBridgeEnabled = receiveBridgeDiagnostics.enabled,
                                onReportReceiveBridgeToggleBlocked =
                                onReportReceiveBridgeToggleBlocked,
                                onSetReceiveBridgeEnabled = onSetReceiveBridgeEnabled
                            )
                        }
                    )
                    NearbyWifiDirectButtonRow(
                        firstLabel = "Send bridged frame",
                        firstEnabled = socketControlsState.canSendBridgedFrame,
                        firstOnClick = onSendBridgedFrame,
                        secondLabel = "Send smoke test frame",
                        secondEnabled = socketControlsState.canSendSmokeTestFrame,
                        secondOnClick = onSendSmokeTestFrame
                    )
                    Text(
                        text = "Send bridge: ${wifiDirectSendBridgeStateSummary(sendBridgeDiagnostics)} | Receive bridge: ${wifiDirectReceiveBridgeStateSummary(receiveBridgeDiagnostics)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    readiness.bridgeMismatchWarning?.let { warningText ->
                        Text(
                            text = warningText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    readiness.receiveBridgeWarning?.let { warningText ->
                        Text(
                            text = warningText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    receiveBridgeToggleState.blockedReason?.let { reasonText ->
                        Text(
                            text = reasonText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    receiveBridgeDiagnostics.lastToggleBlockedReason?.let { reasonText ->
                        Text(
                            text = reasonText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (socketSetupUiState.showGlobalControls) {
                NearbyWifiDirectControlGroup(title = "Global debug send") {
                    NearbyWifiDirectActionButton(
                        label = if (globalSendDiagnostics.enabled) {
                            "Disable Global send"
                        } else {
                            "Enable Global send"
                        },
                        enabled = globalSendDiagnostics.enabled || readiness.canEnableGlobalDebugSend,
                        onClick = {
                            onSetGlobalDebugSendEnabled(!globalSendDiagnostics.enabled)
                        }
                    )
                    Text(
                        text = "Global send: ${wifiDirectGlobalDebugSendStateSummary(globalSendDiagnostics)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    readiness.globalSendBlockedReason?.let { reasonText ->
                        Text(
                            text = reasonText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            NearbyWifiDirectControlGroup(title = "Diagnostics") {
                NearbyWifiDirectActionButton(
                    label = "Reset diagnostics",
                    onClick = onResetDiagnostics
                )
                Text(
                    text = "Clears Wi-Fi Direct debug counters and results only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Does not disconnect the group or clear chats, contacts, or identity.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            NearbyWifiDirectControlGroup(title = "Advanced") {
                NearbyWifiDirectButtonRow(
                    firstLabel = nearbyAdvancedSectionToggleLabel(
                        title = "Wi-Fi Direct details",
                        expanded = showWifiDirectDetails
                    ),
                    firstOnClick = {
                        showWifiDirectDetails = !showWifiDirectDetails
                    },
                    secondLabel = nearbyAdvancedSectionToggleLabel(
                        title = "socket diagnostics",
                        expanded = showSocketDiagnostics
                    ),
                    secondOnClick = {
                        showSocketDiagnostics = !showSocketDiagnostics
                    }
                )
                NearbyWifiDirectButtonRow(
                    firstLabel = nearbyAdvancedSectionToggleLabel(
                        title = "bridge diagnostics",
                        expanded = showBridgeDiagnostics
                    ),
                    firstOnClick = {
                        showBridgeDiagnostics = !showBridgeDiagnostics
                    },
                    secondLabel = nearbyAdvancedSectionToggleLabel(
                        title = "global debug diagnostics",
                        expanded = showGlobalDiagnostics
                    ),
                    secondOnClick = {
                        showGlobalDiagnostics = !showGlobalDiagnostics
                    }
                )
                NearbyWifiDirectActionButton(
                    label = nearbyAdvancedSectionToggleLabel(
                        title = "manual test guide",
                        expanded = showManualGuide
                    ),
                    onClick = {
                        showManualGuide = !showManualGuide
                    }
                )
                Text(
                    text = "BLE remains primary. Wi-Fi Direct debug copy is optional.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (showWifiDirectDetails) {
                    DebugInfoCard(
                        card = buildNearbyWifiDirectDetailsAdvancedCard(runtimeStatus)
                    )
                }
                if (showSocketDiagnostics) {
                    DebugInfoCard(
                        card = buildNearbyWifiDirectSocketDiagnosticsCard(
                            socketDiagnostics = socketDiagnostics,
                            adapterDiagnostics = adapterDiagnostics
                        )
                    )
                }
                if (showBridgeDiagnostics) {
                    DebugInfoCard(
                        card = buildNearbyWifiDirectBridgeDiagnosticsCard(
                            sendBridgeDiagnostics = sendBridgeDiagnostics,
                            smokeTestDiagnostics = smokeTestDiagnostics,
                            receiveBridgeDiagnostics = receiveBridgeDiagnostics
                        )
                    )
                }
                if (showGlobalDiagnostics) {
                    DebugInfoCard(
                        card = buildNearbyWifiDirectGlobalDiagnosticsCard(
                            diagnostics = globalSendDiagnostics
                        )
                    )
                }
                if (showManualGuide) {
                    DebugInfoCard(
                        card = buildNearbyWifiDirectManualGuideCard()
                    )
                }
            }
        }
    }
}

@Composable
private fun NearbyWifiDirectControlGroup(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        content()
    }
}

@Composable
private fun NearbyWifiDirectButtonRow(
    firstLabel: String,
    firstEnabled: Boolean = true,
    firstOnClick: () -> Unit,
    secondLabel: String,
    secondEnabled: Boolean = true,
    secondOnClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        NearbyWifiDirectActionButton(
            label = firstLabel,
            enabled = firstEnabled,
            onClick = firstOnClick,
            modifier = Modifier.weight(1f)
        )
        NearbyWifiDirectActionButton(
            label = secondLabel,
            enabled = secondEnabled,
            onClick = secondOnClick,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun NearbyWifiDirectActionButton(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth()
    ) {
        Text(label)
    }
}

@Composable
private fun NearbyWifiDirectPeerActionRow(
    peer: WifiDirectPeer,
    actionState: NearbyWifiDirectPeerActionState,
    onConnectToPeer: (WifiDirectPeer) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = nearbyWifiDirectPeerValue(peer),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .padding(end = 8.dp)
        )
        TextButton(
            onClick = { onConnectToPeer(peer) },
            enabled = actionState.canConnect
        ) {
            Text(actionState.connectLabel)
        }
    }
}

private fun WifiDirectSocketCommandAvailability.toNearbySocketControlsState(
    canSendAdapterFrame: Boolean,
    canSendBridgedFrame: Boolean,
    canSendSmokeTestFrame: Boolean
): NearbyWifiDirectSocketControlsState {
    return NearbyWifiDirectSocketControlsState(
        canStartServer = canStartServer,
        canConnectClient = canConnectClient,
        canSendFrame = canSendFrame,
        canSendAdapterFrame = canSendAdapterFrame,
        canSendBridgedFrame = canSendBridgedFrame,
        canSendSmokeTestFrame = canSendSmokeTestFrame,
        canCloseSocket = canCloseSocket,
        connectHost = connectHost,
        startServerBlockedReason = startServerBlockedReason,
        connectClientBlockedReason = connectClientBlockedReason,
        helpText = helpText
    )
}
