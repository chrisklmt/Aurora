package gr.hua.aurora.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gr.hua.aurora.ui.components.DebugInfoItem
import gr.hua.aurora.ui.components.DebugInfoSection
import gr.hua.aurora.wifidirect.WifiDirectConnectionState
import gr.hua.aurora.wifidirect.WifiDirectEnabledState
import gr.hua.aurora.wifidirect.WifiDirectPeer
import gr.hua.aurora.wifidirect.WifiDirectRuntimeStatus
import gr.hua.aurora.wifidirect.WifiDirectSocketCommandAvailability
import gr.hua.aurora.wifidirect.WifiDirectSocketDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectSocketState
import gr.hua.aurora.wifidirect.wifiDirectFrameByteSummary
import gr.hua.aurora.wifidirect.wifiDirectFrameCountSummary
import gr.hua.aurora.wifidirect.wifiDirectFrameSizeSummary
import gr.hua.aurora.wifidirect.wifiDirectFrameTransportStateSummary
import gr.hua.aurora.wifidirect.wifiDirectConnectionRoleSummary
import gr.hua.aurora.wifidirect.wifiDirectConnectionSummary
import gr.hua.aurora.wifidirect.wifiDirectDiscoveryBlockedReason
import gr.hua.aurora.wifidirect.wifiDirectDiscoverySummary
import gr.hua.aurora.wifidirect.wifiDirectEnabledSummary
import gr.hua.aurora.wifidirect.wifiDirectGroupFormedSummary
import gr.hua.aurora.wifidirect.wifiDirectMissingPermissionsSummary
import gr.hua.aurora.wifidirect.wifiDirectPeerMatches
import gr.hua.aurora.wifidirect.wifiDirectPermissionsSummary
import gr.hua.aurora.wifidirect.wifiDirectSocketByteSummary
import gr.hua.aurora.wifidirect.wifiDirectSocketConnectedSummary
import gr.hua.aurora.wifidirect.wifiDirectSocketEndpointSummary
import gr.hua.aurora.wifidirect.wifiDirectSocketMessageSummary
import gr.hua.aurora.wifidirect.wifiDirectSocketRoleSummary
import gr.hua.aurora.wifidirect.wifiDirectSocketStateSummary
import gr.hua.aurora.wifidirect.wifiDirectSocketCommandAvailability
import gr.hua.aurora.wifidirect.wifiDirectSupportSummary
import gr.hua.aurora.wifidirect.wifiDirectTransportSummary

internal fun buildNearbyWifiDirectDebugSection(
    runtimeStatus: WifiDirectRuntimeStatus
): DebugInfoSection {
    val connectionStatus = runtimeStatus.connectionStatus
    val items = buildList {
        add(DebugInfoItem("Supported", wifiDirectSupportSummary(runtimeStatus)))
        add(
            DebugInfoItem(
                "Permissions",
                wifiDirectPermissionsSummary(runtimeStatus.permissionStatus)
            )
        )
        wifiDirectMissingPermissionsSummary(runtimeStatus.permissionStatus)?.let { missingText ->
            add(
                DebugInfoItem(
                    "Missing",
                    missingText,
                    preferFullWidth = true
                )
            )
        }
        add(DebugInfoItem("Wi-Fi/P2P", nearbyWifiDirectEnabledValue(runtimeStatus.enabledState)))
        add(
            DebugInfoItem(
                "Discovery",
                wifiDirectDiscoverySummary(runtimeStatus.discoveryState)
            )
        )
        add(
            DebugInfoItem(
                "Transport",
                wifiDirectTransportSummary(runtimeStatus.transportState)
            )
        )
        add(
            DebugInfoItem(
                "Connection",
                wifiDirectConnectionSummary(connectionStatus.state)
            )
        )
        connectionStatus.targetPeer?.let { targetPeer ->
            add(
                DebugInfoItem(
                    "Target",
                    nearbyWifiDirectPeerValue(targetPeer),
                    preferFullWidth = true
                )
            )
        }
        add(
            DebugInfoItem(
                "Group",
                wifiDirectGroupFormedSummary(connectionStatus.groupFormed)
            )
        )
        add(
            DebugInfoItem(
                "Role",
                wifiDirectConnectionRoleSummary(connectionStatus.role)
            )
        )
        connectionStatus.groupOwnerAddress?.let { ownerAddress ->
            add(
                DebugInfoItem(
                    "Owner",
                    ownerAddress,
                    preferFullWidth = true
                )
            )
        }
        add(DebugInfoItem("Peers", runtimeStatus.peerCount.toString()))
        runtimeStatus.peers.takeIf { it.isNotEmpty() }?.let { peers ->
            add(
                DebugInfoItem(
                    "Devices",
                    nearbyWifiDirectPeerListValue(peers),
                    preferFullWidth = true
                )
            )
        }
        connectionStatus.lastError?.takeIf { it.isNotBlank() }?.let { errorText ->
            add(
                DebugInfoItem(
                    "Connect error",
                    errorText,
                    preferFullWidth = true
                )
            )
        }
        runtimeStatus.lastError?.takeIf { it.isNotBlank() }?.let { errorText ->
            add(
                DebugInfoItem(
                    "Last error",
                    errorText,
                    preferFullWidth = true
                )
            )
        }
    }

    return DebugInfoSection(
        title = "Wi-Fi Direct",
        items = items
    )
}

internal fun buildNearbyWifiDirectSocketDebugSection(
    diagnostics: WifiDirectSocketDiagnostics
): DebugInfoSection {
    val items = buildList {
        add(
            DebugInfoItem(
                "Socket",
                wifiDirectSocketStateSummary(diagnostics.state)
            )
        )
        add(
            DebugInfoItem(
                "Role",
                wifiDirectSocketRoleSummary(diagnostics.role)
            )
        )
        add(
            DebugInfoItem(
                "Connected",
                wifiDirectSocketConnectedSummary(diagnostics.isConnected)
            )
        )
        add(
            DebugInfoItem(
                "Endpoint",
                wifiDirectSocketEndpointSummary(diagnostics.endpoint)
            )
        )
        add(
            DebugInfoItem(
                "Sent",
                wifiDirectSocketMessageSummary(diagnostics.lastSentMessage)
            )
        )
        add(
            DebugInfoItem(
                "Received",
                wifiDirectSocketMessageSummary(diagnostics.lastReceivedMessage)
            )
        )
        add(
            DebugInfoItem(
                "Bytes",
                wifiDirectSocketByteSummary(diagnostics)
            )
        )
        diagnostics.lastError?.takeIf { it.isNotBlank() }?.let { errorText ->
            add(
                DebugInfoItem(
                    "Last error",
                    errorText,
                    preferFullWidth = true
                )
            )
        }
        add(
            DebugInfoItem(
                "Note",
                diagnostics.note,
                preferFullWidth = true
            )
        )
    }

    return DebugInfoSection(
        title = "Socket",
        items = items
    )
}

internal fun buildNearbyWifiDirectFrameDebugSection(
    diagnostics: WifiDirectSocketDiagnostics
): DebugInfoSection {
    val frameDiagnostics = diagnostics.frameDiagnostics
    val items = buildList {
        add(
            DebugInfoItem(
                "Transport",
                wifiDirectFrameTransportStateSummary(frameDiagnostics.state)
            )
        )
        add(
            DebugInfoItem(
                "Frames",
                wifiDirectFrameCountSummary(frameDiagnostics)
            )
        )
        add(
            DebugInfoItem(
                "Bytes",
                wifiDirectFrameByteSummary(frameDiagnostics)
            )
        )
        add(
            DebugInfoItem(
                "Last size",
                wifiDirectFrameSizeSummary(frameDiagnostics.lastFrameSize)
            )
        )
        frameDiagnostics.lastError?.takeIf { it.isNotBlank() }?.let { errorText ->
            add(
                DebugInfoItem(
                    "Last error",
                    errorText,
                    preferFullWidth = true
                )
            )
        }
        add(
            DebugInfoItem(
                "Note",
                frameDiagnostics.note,
                preferFullWidth = true
            )
        )
    }

    return DebugInfoSection(
        title = "Frame",
        items = items
    )
}

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
        runtimeStatus.discoveryState == gr.hua.aurora.wifidirect.WifiDirectDiscoveryState.ACTIVE
    ) {
        "Wi-Fi Direct discovery already active."
    } else {
        wifiDirectDiscoveryBlockedReason(runtimeStatus.permissionStatus)
    }
    return NearbyWifiDirectDebugControlsState(
        canStartDiscovery = startDisabledReason == null,
        canStopDiscovery = true,
        canDisconnect = nearbyCanDisconnectWifiDirect(runtimeStatus),
        disconnectLabel = nearbyWifiDirectDisconnectLabel(runtimeStatus),
        startDisabledReason = startDisabledReason
    )
}

internal data class NearbyWifiDirectSocketControlsState(
    val canStartServer: Boolean,
    val canConnectClient: Boolean,
    val canSendFrame: Boolean,
    val canCloseSocket: Boolean,
    val connectHost: String? = null,
    val helpText: String? = null
)

internal fun nearbyWifiDirectSocketControlsState(
    runtimeStatus: WifiDirectRuntimeStatus,
    diagnostics: WifiDirectSocketDiagnostics
): NearbyWifiDirectSocketControlsState {
    return wifiDirectSocketCommandAvailability(
        runtimeStatus = runtimeStatus,
        diagnostics = diagnostics
    ).toNearbySocketControlsState()
}

internal fun nearbyWifiDirectPeerListValue(
    peers: List<WifiDirectPeer>
): String {
    return peers.joinToString(separator = ", ") { peer ->
        val name = peer.deviceName?.trim()?.takeIf { it.isNotEmpty() } ?: "unnamed"
        val address = peer.deviceAddress?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown"
        "$name ($address)"
    }
}

internal fun nearbyWifiDirectPeerValue(
    peer: WifiDirectPeer
): String {
    val name = peer.deviceName?.trim()?.takeIf { it.isNotEmpty() } ?: "unnamed"
    val address = peer.deviceAddress?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown"
    return "$name ($address)"
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
    val isTargetPeer = wifiDirectPeerMatches(connectionStatus.targetPeer, peer)
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
    onStartDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
    onConnectToPeer: (WifiDirectPeer) -> Unit,
    onDisconnect: () -> Unit,
    onStartSocketServer: (String?) -> Unit,
    onConnectSocketClient: (String) -> Unit,
    onSendSocketFrame: () -> Unit,
    onCloseSocket: () -> Unit
) {
    val controlsState = nearbyWifiDirectDebugControlsState(runtimeStatus)
    val socketControlsState = nearbyWifiDirectSocketControlsState(
        runtimeStatus = runtimeStatus,
        diagnostics = socketDiagnostics
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(
                onClick = onStartDiscovery,
                enabled = controlsState.canStartDiscovery
            ) {
                Text("Start Wi-Fi Direct")
            }
            TextButton(
                onClick = onStopDiscovery,
                enabled = controlsState.canStopDiscovery
            ) {
                Text("Stop Wi-Fi Direct")
            }
            TextButton(
                onClick = onDisconnect,
                enabled = controlsState.canDisconnect
            ) {
                Text(controlsState.disconnectLabel)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(
                onClick = {
                    onStartSocketServer(runtimeStatus.connectionStatus.groupOwnerAddress)
                },
                enabled = socketControlsState.canStartServer
            ) {
                Text("Start socket server")
            }
            TextButton(
                onClick = {
                    socketControlsState.connectHost?.let(onConnectSocketClient)
                },
                enabled = socketControlsState.canConnectClient
            ) {
                Text("Connect socket client")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(
                onClick = onSendSocketFrame,
                enabled = socketControlsState.canSendFrame
            ) {
                Text("Send debug frame")
            }
            TextButton(
                onClick = onCloseSocket,
                enabled = socketControlsState.canCloseSocket
            ) {
                Text("Close socket")
            }
        }
        socketControlsState.helpText?.let { helpText ->
            Text(
                text = helpText,
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
                onConnectToPeer = onConnectToPeer
            )
        }
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

internal fun nearbyWifiDirectEnabledValue(
    state: WifiDirectEnabledState
): String {
    return wifiDirectEnabledSummary(state)
}

private fun WifiDirectSocketCommandAvailability.toNearbySocketControlsState(): NearbyWifiDirectSocketControlsState {
    return NearbyWifiDirectSocketControlsState(
        canStartServer = canStartServer,
        canConnectClient = canConnectClient,
        canSendFrame = canSendFrame,
        canCloseSocket = canCloseSocket,
        connectHost = connectHost,
        helpText = helpText
    )
}
