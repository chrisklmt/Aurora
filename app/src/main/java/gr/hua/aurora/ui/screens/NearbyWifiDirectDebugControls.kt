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
import gr.hua.aurora.wifidirect.WifiDirectConnectionState
import gr.hua.aurora.wifidirect.WifiDirectPeer
import gr.hua.aurora.wifidirect.WifiDirectRuntimeStatus
import gr.hua.aurora.wifidirect.WifiDirectSocketCommandAvailability
import gr.hua.aurora.wifidirect.WifiDirectSocketDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectTransportAdapterDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectSendBridgeDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectSmokeTestDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectReceiveBridgeDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectGlobalDebugSendDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectTransportAdapterState
import gr.hua.aurora.wifidirect.wifiDirectDiscoveryBlockedReason
import gr.hua.aurora.wifidirect.wifiDirectGlobalDebugSendStateSummary
import gr.hua.aurora.wifidirect.wifiDirectReceiveBridgeStateSummary
import gr.hua.aurora.wifidirect.wifiDirectSendBridgeStateSummary
import gr.hua.aurora.wifidirect.wifiDirectSmokeTestStateSummary
import gr.hua.aurora.wifidirect.wifiDirectSocketCommandAvailability

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
    val canSendAdapterFrame: Boolean,
    val canSendBridgedFrame: Boolean = false,
    val canSendSmokeTestFrame: Boolean = false,
    val canCloseSocket: Boolean,
    val connectHost: String? = null,
    val helpText: String? = null
)

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
    val isTargetPeer = gr.hua.aurora.wifidirect.wifiDirectPeerMatches(connectionStatus.targetPeer, peer)
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
    smokeTestDiagnostics: WifiDirectSmokeTestDiagnostics,
    receiveBridgeDiagnostics: WifiDirectReceiveBridgeDiagnostics,
    onStartDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
    onConnectToPeer: (WifiDirectPeer) -> Unit,
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
    onCloseSocket: () -> Unit
) {
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

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Wi-Fi Direct debug readiness: ${readiness.overallStatus}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = nearbyWifiDirectGlobalDebugGuidance,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = nearbyWifiDirectGlobalDebugBleNote,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        NearbyWifiDirectControlGroup(title = "Discovery") {
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
            }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = onDisconnect,
                    enabled = controlsState.canDisconnect
                ) {
                    Text(controlsState.disconnectLabel)
                }
            }
            Text(
                text = "Connection: ${readiness.connectionStatus}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
        NearbyWifiDirectControlGroup(title = "Socket/frame") {
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
                    onClick = onSendAdapterFrame,
                    enabled = socketControlsState.canSendAdapterFrame
                ) {
                    Text("Send adapter frame")
                }
                TextButton(
                    onClick = onCloseSocket,
                    enabled = socketControlsState.canCloseSocket
                ) {
                    Text("Close socket")
                }
            }
            Text(
                text = "Socket/frame: ${readiness.socketFrameStatus} | Adapter: ${readiness.adapterStatus}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            socketControlsState.helpText?.let { helpText ->
                Text(
                    text = helpText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        NearbyWifiDirectControlGroup(title = "Bridges") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = {
                        onSetSendBridgeEnabled(!sendBridgeDiagnostics.enabled)
                    }
                ) {
                    Text(
                        if (sendBridgeDiagnostics.enabled) {
                            "Disable send bridge"
                        } else {
                            "Enable send bridge"
                        }
                    )
                }
                TextButton(
                    onClick = {
                        onSetReceiveBridgeEnabled(!receiveBridgeDiagnostics.enabled)
                    }
                ) {
                    Text(
                        if (receiveBridgeDiagnostics.enabled) {
                            "Disable receive bridge"
                        } else {
                            "Enable receive bridge"
                        }
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = onSendBridgedFrame,
                    enabled = socketControlsState.canSendBridgedFrame
                ) {
                    Text("Send bridged frame")
                }
                TextButton(
                    onClick = onSendSmokeTestFrame,
                    enabled = socketControlsState.canSendSmokeTestFrame
                ) {
                    Text("Send smoke test frame")
                }
            }
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
        }
        NearbyWifiDirectControlGroup(title = "Global debug send") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = {
                        onSetGlobalDebugSendEnabled(!globalSendDiagnostics.enabled)
                    },
                    enabled = globalSendDiagnostics.enabled || readiness.canEnableGlobalDebugSend
                ) {
                    Text(
                        if (globalSendDiagnostics.enabled) {
                            "Disable Global send"
                        } else {
                            "Enable Global send"
                        }
                    )
                }
            }
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
        helpText = helpText
    )
}
