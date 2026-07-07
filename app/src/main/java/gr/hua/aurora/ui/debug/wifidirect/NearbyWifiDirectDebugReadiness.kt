package gr.hua.aurora.ui.debug.wifidirect

import gr.hua.aurora.wifidirect.controller.WifiDirectEnabledState
import gr.hua.aurora.wifidirect.controller.wifiDirectDiscoveryBlockedReason
import gr.hua.aurora.wifidirect.debug.WifiDirectGlobalDebugSendDiagnostics
import gr.hua.aurora.wifidirect.debug.WifiDirectPrivateDebugSendDiagnostics
import gr.hua.aurora.wifidirect.debug.WifiDirectReceiveBridgeDiagnostics
import gr.hua.aurora.wifidirect.debug.WifiDirectSendBridgeDiagnostics
import gr.hua.aurora.wifidirect.debug.wifiDirectGlobalDebugSendStateSummary
import gr.hua.aurora.wifidirect.debug.wifiDirectReceiveBridgeStateSummary
import gr.hua.aurora.wifidirect.debug.wifiDirectSendBridgeStateSummary
import gr.hua.aurora.wifidirect.frame.WifiDirectTransportAdapterDiagnostics
import gr.hua.aurora.wifidirect.frame.WifiDirectTransportAdapterState
import gr.hua.aurora.wifidirect.frame.wifiDirectTransportAdapterStateSummary
import gr.hua.aurora.wifidirect.runtime.WifiDirectConnectionRole
import gr.hua.aurora.wifidirect.runtime.WifiDirectConnectionState
import gr.hua.aurora.wifidirect.runtime.WifiDirectDiscoveryState
import gr.hua.aurora.wifidirect.runtime.WifiDirectGroupFormedState
import gr.hua.aurora.wifidirect.runtime.WifiDirectRuntimeStatus
import gr.hua.aurora.wifidirect.socket.WifiDirectSocketDiagnostics
import gr.hua.aurora.wifidirect.socket.WifiDirectSocketState
import gr.hua.aurora.wifidirect.socket.wifiDirectSocketConnectHostOrNull

internal const val nearbyWifiDirectGlobalDebugGuidance =
    "For Wi-Fi Direct Global test: connect group, connect socket, enable send bridge on sender, enable receive bridge on receiver, enable Global send."

internal const val nearbyWifiDirectGlobalDebugBleNote =
    "Normal chat still uses BLE. Private Chat still uses BLE."

private const val nearbyWifiDirectNearbyDevicesSettingsInstruction =
    "Open Android Settings > Apps > Aurora > Permissions > Nearby devices > Allow."

private const val nearbyWifiDirectLocationSettingsInstruction =
    "Open Android Settings > Apps > Aurora > Permissions > Location > Allow."

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

internal data class NearbyWifiDirectPermissionBlocker(
    val title: String,
    val message: String,
    val missingPermissionName: String,
    val settingsInstruction: String
)

internal data class NearbyWifiDirectDisabledBlocker(
    val title: String,
    val message: String,
    val settingsActionLabel: String,
    val refreshActionLabel: String,
    val nextStep: String
)

internal data class NearbyWifiDirectManualNextStep(
    val title: String,
    val detail: String? = null
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

internal fun nearbyWifiDirectPermissionBlocker(
    runtimeStatus: WifiDirectRuntimeStatus
): NearbyWifiDirectPermissionBlocker? {
    val permissionStatus = runtimeStatus.permissionStatus
    val missingPermissionName = permissionStatus.missingPermissionLabels.joinToString(separator = ", ")
        .ifBlank { "Unknown permission" }
    return when {
        permissionStatus.hasMissingNearbyWifiPermission ->
            NearbyWifiDirectPermissionBlocker(
                title = "Wi-Fi Direct permission required",
                message = "Grant Nearby devices permission to start Wi-Fi Direct discovery.",
                missingPermissionName = missingPermissionName,
                settingsInstruction = nearbyWifiDirectNearbyDevicesSettingsInstruction
            )

        permissionStatus.hasMissingLocationPermission ->
            NearbyWifiDirectPermissionBlocker(
                title = "Wi-Fi Direct permission required",
                message = "Grant location permission to start Wi-Fi Direct discovery.",
                missingPermissionName = missingPermissionName,
                settingsInstruction = nearbyWifiDirectLocationSettingsInstruction
            )

        !permissionStatus.allRequiredGranted ->
            NearbyWifiDirectPermissionBlocker(
                title = "Wi-Fi Direct permission required",
                message = "Grant the missing Wi-Fi Direct permission before starting discovery.",
                missingPermissionName = missingPermissionName,
                settingsInstruction = nearbyWifiDirectNearbyDevicesSettingsInstruction
            )

        else -> null
    }
}

internal fun nearbyWifiDirectDisabledBlocker(
    runtimeStatus: WifiDirectRuntimeStatus
): NearbyWifiDirectDisabledBlocker? {
    if (nearbyWifiDirectPermissionBlocker(runtimeStatus) != null) {
        return null
    }
    return if (runtimeStatus.permissionStatus.enabledState == WifiDirectEnabledState.DISABLED) {
        NearbyWifiDirectDisabledBlocker(
            title = "Wi-Fi Direct is disabled",
            message = "Turn on Wi-Fi to use Wi-Fi Direct discovery.",
            settingsActionLabel = "Open Wi-Fi settings",
            refreshActionLabel = "Refresh status",
            nextStep = "Turn on Wi-Fi, then return to Aurora."
        )
    } else {
        null
    }
}

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
        runtimeStatus.discoveryState == WifiDirectDiscoveryState.ACTIVE -> {
            "active"
        }
        runtimeStatus.peerCount > 0 -> "peers visible"
        else -> "inactive"
    }
    val connectionReady =
        runtimeStatus.connectionStatus.state == WifiDirectConnectionState.CONNECTED &&
            runtimeStatus.connectionStatus.groupFormed == WifiDirectGroupFormedState.YES
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
            !adapterReady -> {
                adapterDiagnostics.notReadyReason ?: "Wi-Fi Direct adapter not ready."
            }
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
            WifiDirectGroupFormedState.YES
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

internal fun nearbyWifiDirectManualNextStep(
    runtimeStatus: WifiDirectRuntimeStatus,
    socketDiagnostics: WifiDirectSocketDiagnostics,
    adapterDiagnostics: WifiDirectTransportAdapterDiagnostics,
    sendBridgeDiagnostics: WifiDirectSendBridgeDiagnostics,
    globalSendDiagnostics: WifiDirectGlobalDebugSendDiagnostics,
    privateDebugSendDiagnostics: WifiDirectPrivateDebugSendDiagnostics,
    receiveBridgeDiagnostics: WifiDirectReceiveBridgeDiagnostics
): NearbyWifiDirectManualNextStep {
    nearbyWifiDirectPermissionBlocker(runtimeStatus)?.let { blocker ->
        return NearbyWifiDirectManualNextStep(
            title = blocker.message,
            detail = "Missing: ${blocker.missingPermissionName}"
        )
    }

    nearbyWifiDirectDisabledBlocker(runtimeStatus)?.let { blocker ->
        return NearbyWifiDirectManualNextStep(
            title = blocker.nextStep
        )
    }

    val blockedReason = wifiDirectDiscoveryBlockedReason(runtimeStatus.permissionStatus)
    if (blockedReason != null) {
        return NearbyWifiDirectManualNextStep(
            title = blockedReason,
            detail = "Resolve Wi-Fi Direct readiness before starting discovery."
        )
    }

    val groupConnected =
        runtimeStatus.connectionStatus.state == WifiDirectConnectionState.CONNECTED &&
            runtimeStatus.connectionStatus.groupFormed ==
            WifiDirectGroupFormedState.YES
    if (
        !groupConnected &&
        runtimeStatus.discoveryState != WifiDirectDiscoveryState.ACTIVE
    ) {
        return NearbyWifiDirectManualNextStep(
            title = if (runtimeStatus.peerCount > 0) {
                "Connect/group devices."
            } else {
                "Start Wi-Fi Direct discovery."
            }
        )
    }
    if (!groupConnected) {
        return NearbyWifiDirectManualNextStep(
            title = "Connect/group devices."
        )
    }

    val socketControlsState = nearbyWifiDirectSocketControlsState(
        runtimeStatus = runtimeStatus,
        diagnostics = socketDiagnostics,
        adapterDiagnostics = adapterDiagnostics,
        sendBridgeDiagnostics = sendBridgeDiagnostics
    )
    if (!socketControlsState.canSendFrame) {
        return when (runtimeStatus.connectionStatus.role) {
            WifiDirectConnectionRole.GROUP_OWNER -> {
                if (socketDiagnostics.state == WifiDirectSocketState.SERVER_LISTENING) {
                    NearbyWifiDirectManualNextStep(
                        title = "On the other device, connect socket client.",
                        detail = "Waiting for client to connect."
                    )
                } else {
                    NearbyWifiDirectManualNextStep(
                        title = "Start socket server.",
                        detail = "This device is the Wi-Fi Direct group owner."
                    )
                }
            }
            WifiDirectConnectionRole.CLIENT -> {
                val rawOwnerAddress = runtimeStatus.connectionStatus.groupOwnerAddress?.trim()
                    ?.takeIf { it.isNotEmpty() }
                when {
                    rawOwnerAddress == null -> NearbyWifiDirectManualNextStep(
                        title = "Connect socket client.",
                        detail = "Cannot connect: group owner IP missing."
                    )
                    wifiDirectSocketConnectHostOrNull(rawOwnerAddress) == null ->
                        NearbyWifiDirectManualNextStep(
                            title = "Connect socket client.",
                            detail = "Cannot connect: group owner host is not an IP."
                        )
                    socketDiagnostics.state == WifiDirectSocketState.CONNECTING ->
                        NearbyWifiDirectManualNextStep(
                            title = "Connect socket client.",
                            detail = "Connecting socket client..."
                        )
                    else -> NearbyWifiDirectManualNextStep(
                        title = "Connect socket client.",
                        detail = "Use group owner host $rawOwnerAddress."
                    )
                }
            }
            WifiDirectConnectionRole.UNKNOWN -> NearbyWifiDirectManualNextStep(
                title = "Verify the Wi-Fi Direct connection role.",
                detail = socketControlsState.helpText ?: "Socket setup needs a valid Wi-Fi Direct role."
            )
        }
    }

    if (adapterDiagnostics.state != WifiDirectTransportAdapterState.READY) {
        return NearbyWifiDirectManualNextStep(
            title = "Verify adapter/frame setup.",
            detail = adapterDiagnostics.notReadyReason
                ?: "Wi-Fi Direct transport adapter not ready yet."
        )
    }

    if (!sendBridgeDiagnostics.enabled) {
        return NearbyWifiDirectManualNextStep(
            title = "Enable send bridge on the sender."
        )
    }

    if (!receiveBridgeDiagnostics.enabled) {
        return NearbyWifiDirectManualNextStep(
            title = "Enable receive bridge on the receiver."
        )
    }

    if (!globalSendDiagnostics.enabled && !privateDebugSendDiagnostics.enabled) {
        return NearbyWifiDirectManualNextStep(
            title = "Choose a manual test path.",
            detail = "Enable Global debug send here, or enable Private Wi-Fi Direct debug send in Private Chat."
        )
    }

    if (!globalSendDiagnostics.enabled) {
        return NearbyWifiDirectManualNextStep(
            title = "Enable Global debug send for the Global test."
        )
    }

    if (!privateDebugSendDiagnostics.enabled) {
        return NearbyWifiDirectManualNextStep(
            title = "Enable Private Wi-Fi Direct debug send in Private Chat for the Private test."
        )
    }

    return NearbyWifiDirectManualNextStep(
        title = "Run the Wi-Fi Direct manual test.",
        detail = "Send a Global or Private debug message and verify the receiver bridge."
    )
}
