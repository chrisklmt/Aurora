package gr.hua.aurora.ui.screens

import gr.hua.aurora.wifidirect.WifiDirectConnectionState
import gr.hua.aurora.wifidirect.WifiDirectGlobalDebugSendDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectPrivateDebugSendDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectReceiveBridgeDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectRuntimeStatus
import gr.hua.aurora.wifidirect.WifiDirectSendBridgeDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectSocketDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectTransportAdapterDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectTransportAdapterState
import gr.hua.aurora.wifidirect.wifiDirectDiscoveryBlockedReason
import gr.hua.aurora.wifidirect.wifiDirectGlobalDebugSendStateSummary
import gr.hua.aurora.wifidirect.wifiDirectReceiveBridgeStateSummary
import gr.hua.aurora.wifidirect.wifiDirectSendBridgeStateSummary
import gr.hua.aurora.wifidirect.wifiDirectTransportAdapterStateSummary

internal const val nearbyWifiDirectGlobalDebugGuidance =
    "For Wi-Fi Direct Global test: connect group, connect socket, enable send bridge on sender, enable receive bridge on receiver, enable Global send."

internal const val nearbyWifiDirectGlobalDebugBleNote =
    "Normal chat still uses BLE. Private Chat still uses BLE."

internal data class NearbyWifiDirectManualTestReadiness(
    val overallStatus: String,
    val discoveryStatus: String,
    val groupStatus: String,
    val socketFrameStatus: String,
    val adapterStatus: String,
    val sendBridgeStatus: String,
    val receiveBridgeStatus: String,
    val globalDebugSendStatus: String,
    val privateDebugSendStatus: String
)

internal data class NearbyWifiDirectGlobalDebugReadiness(
    val overallStatus: String,
    val discoveryStatus: String,
    val connectionStatus: String,
    val socketFrameStatus: String,
    val adapterStatus: String,
    val sendBridgeStatus: String,
    val receiveBridgeStatus: String,
    val globalSendStatus: String,
    val canEnableGlobalDebugSend: Boolean,
    val globalSendBlockedReason: String? = null,
    val bridgeMismatchWarning: String? = null,
    val receiveBridgeWarning: String? = null
)

internal fun nearbyWifiDirectGlobalDebugReadiness(
    runtimeStatus: WifiDirectRuntimeStatus,
    socketDiagnostics: WifiDirectSocketDiagnostics,
    adapterDiagnostics: WifiDirectTransportAdapterDiagnostics,
    sendBridgeDiagnostics: WifiDirectSendBridgeDiagnostics,
    globalSendDiagnostics: WifiDirectGlobalDebugSendDiagnostics,
    receiveBridgeDiagnostics: WifiDirectReceiveBridgeDiagnostics
): NearbyWifiDirectGlobalDebugReadiness {
    val socketControlsState = nearbyWifiDirectSocketControlsState(
        runtimeStatus = runtimeStatus,
        diagnostics = socketDiagnostics,
        adapterDiagnostics = adapterDiagnostics,
        sendBridgeDiagnostics = sendBridgeDiagnostics
    )
    val discoveryStatus = when {
        wifiDirectDiscoveryBlockedReason(runtimeStatus.permissionStatus) != null -> "blocked"
        runtimeStatus.discoveryState == gr.hua.aurora.wifidirect.WifiDirectDiscoveryState.ACTIVE -> {
            "active"
        }
        runtimeStatus.peerCount > 0 -> "peers visible"
        else -> "inactive"
    }
    val connectionReady =
        runtimeStatus.connectionStatus.state == WifiDirectConnectionState.CONNECTED &&
            runtimeStatus.connectionStatus.groupFormed == gr.hua.aurora.wifidirect.WifiDirectGroupFormedState.YES
    val socketFrameReady = socketControlsState.canSendFrame
    val adapterReady = adapterDiagnostics.state == WifiDirectTransportAdapterState.READY
    val sendBridgeEnabled = sendBridgeDiagnostics.enabled
    val receiveBridgeEnabled = receiveBridgeDiagnostics.enabled
    val globalSendEnabled = globalSendDiagnostics.enabled
    val canEnableGlobalDebugSend = socketFrameReady && adapterReady && sendBridgeEnabled
    val blockedReason = if (globalSendEnabled) {
        null
    } else {
        when {
            !connectionReady -> "Connect a Wi-Fi Direct group first."
            !socketFrameReady -> "Connect the Wi-Fi Direct socket first."
            !adapterReady -> "Wi-Fi Direct adapter not ready."
            !sendBridgeEnabled -> "Enable the send bridge first."
            else -> null
        }
    }
    val overallStatus = when {
        globalSendEnabled && canEnableGlobalDebugSend && receiveBridgeEnabled -> {
            "Ready for Global debug dual-send"
        }
        globalSendEnabled && canEnableGlobalDebugSend && !receiveBridgeEnabled -> {
            "Waiting for receiver bridge"
        }
        socketFrameReady -> "Ready to send debug frame"
        else -> "Not ready"
    }
    val mismatchWarning = when {
        sendBridgeEnabled && !adapterReady -> "Send bridge enabled but adapter not ready."
        globalSendEnabled && !sendBridgeEnabled -> {
            "Global debug send enabled but the send bridge is disabled."
        }
        globalSendEnabled && !socketFrameReady -> {
            "Global debug send enabled without a ready socket/frame path."
        }
        else -> null
    }

    return NearbyWifiDirectGlobalDebugReadiness(
        overallStatus = overallStatus,
        discoveryStatus = discoveryStatus,
        connectionStatus = if (connectionReady) "ready" else "not ready",
        socketFrameStatus = if (socketFrameReady) "ready" else "not ready",
        adapterStatus = wifiDirectTransportAdapterStateSummary(adapterDiagnostics.state),
        sendBridgeStatus = wifiDirectSendBridgeStateSummary(sendBridgeDiagnostics),
        receiveBridgeStatus = wifiDirectReceiveBridgeStateSummary(receiveBridgeDiagnostics),
        globalSendStatus = wifiDirectGlobalDebugSendStateSummary(globalSendDiagnostics),
        canEnableGlobalDebugSend = canEnableGlobalDebugSend,
        globalSendBlockedReason = blockedReason,
        bridgeMismatchWarning = mismatchWarning,
        receiveBridgeWarning = if (receiveBridgeEnabled) {
            null
        } else {
            "Receive bridge disabled."
        }
    )
}

internal fun nearbyWifiDirectManualTestReadiness(
    runtimeStatus: WifiDirectRuntimeStatus,
    socketDiagnostics: WifiDirectSocketDiagnostics,
    adapterDiagnostics: WifiDirectTransportAdapterDiagnostics,
    sendBridgeDiagnostics: WifiDirectSendBridgeDiagnostics,
    globalSendDiagnostics: WifiDirectGlobalDebugSendDiagnostics,
    privateDebugSendDiagnostics: WifiDirectPrivateDebugSendDiagnostics,
    receiveBridgeDiagnostics: WifiDirectReceiveBridgeDiagnostics
): NearbyWifiDirectManualTestReadiness {
    val socketControlsState = nearbyWifiDirectSocketControlsState(
        runtimeStatus = runtimeStatus,
        diagnostics = socketDiagnostics,
        adapterDiagnostics = adapterDiagnostics,
        sendBridgeDiagnostics = sendBridgeDiagnostics
    )
    val discoveryReady = wifiDirectDiscoveryBlockedReason(runtimeStatus.permissionStatus) == null
    val groupConnected =
        runtimeStatus.connectionStatus.state == WifiDirectConnectionState.CONNECTED &&
            runtimeStatus.connectionStatus.groupFormed ==
            gr.hua.aurora.wifidirect.WifiDirectGroupFormedState.YES
    val socketFrameReady = socketControlsState.canSendFrame
    val adapterReady = adapterDiagnostics.state == WifiDirectTransportAdapterState.READY
    val sendBridgeEnabled = sendBridgeDiagnostics.enabled
    val receiveBridgeEnabled = receiveBridgeDiagnostics.enabled
    val globalDebugSendEnabled = globalSendDiagnostics.enabled
    val privateDebugSendEnabled = privateDebugSendDiagnostics.enabled
    val baseDebugSendReady =
        groupConnected && socketFrameReady && adapterReady && sendBridgeEnabled

    val overallStatus = when {
        privateDebugSendEnabled && baseDebugSendReady && receiveBridgeEnabled ->
            "Ready for Private debug send"
        globalDebugSendEnabled && baseDebugSendReady && receiveBridgeEnabled ->
            "Ready for Global debug send"
        baseDebugSendReady -> "Ready for smoke test"
        else -> "Not ready"
    }

    return NearbyWifiDirectManualTestReadiness(
        overallStatus = overallStatus,
        discoveryStatus = if (discoveryReady) "ready" else "not ready",
        groupStatus = if (groupConnected) "connected" else "not connected",
        socketFrameStatus = if (socketFrameReady) "ready" else "not ready",
        adapterStatus = if (adapterReady) "ready" else "not ready",
        sendBridgeStatus = if (sendBridgeEnabled) "enabled" else "disabled",
        receiveBridgeStatus = if (receiveBridgeEnabled) "enabled" else "disabled",
        globalDebugSendStatus = if (globalDebugSendEnabled) "enabled" else "disabled",
        privateDebugSendStatus = if (privateDebugSendEnabled) "enabled" else "disabled"
    )
}
