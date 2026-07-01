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
import gr.hua.aurora.wifidirect.WifiDirectReceiveBridgeDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectGlobalDebugSendDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectSendBridgeDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectSmokeTestDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectTransportAdapterDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectTransportAdapterState
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
import gr.hua.aurora.wifidirect.wifiDirectTransportAdapterByteSummary
import gr.hua.aurora.wifidirect.wifiDirectTransportAdapterStateSummary
import gr.hua.aurora.wifidirect.wifiDirectReceiveBridgeStateSummary
import gr.hua.aurora.wifidirect.wifiDirectGlobalDebugSendModeSummary
import gr.hua.aurora.wifidirect.wifiDirectGlobalDebugSendStateSummary
import gr.hua.aurora.wifidirect.wifiDirectSendBridgeStateSummary
import gr.hua.aurora.wifidirect.wifiDirectSmokeTestStateSummary

private const val nearbyWifiDirectGlobalDebugGuidance =
    "For Wi-Fi Direct Global test: connect group, connect socket, enable send bridge on sender, enable receive bridge on receiver, enable Global send."

private const val nearbyWifiDirectGlobalDebugBleNote =
    "Normal chat still uses BLE. Private Chat still uses BLE."

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

internal fun buildNearbyWifiDirectAdapterDebugSection(
    diagnostics: WifiDirectTransportAdapterDiagnostics
): DebugInfoSection {
    val items = buildList {
        add(
            DebugInfoItem(
                "Adapter",
                wifiDirectTransportAdapterStateSummary(diagnostics.state)
            )
        )
        add(
            DebugInfoItem(
                "Submitted",
                diagnostics.framesSubmitted.toString()
            )
        )
        add(
            DebugInfoItem(
                "Received",
                diagnostics.framesReceived.toString()
            )
        )
        add(
            DebugInfoItem(
                "Bytes",
                wifiDirectTransportAdapterByteSummary(diagnostics)
            )
        )
        add(
            DebugInfoItem(
                "Last size",
                wifiDirectFrameSizeSummary(diagnostics.lastFrameSize)
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
        title = "Adapter",
        items = items
    )
}

internal fun buildNearbyWifiDirectSendBridgeDebugSection(
    diagnostics: WifiDirectSendBridgeDiagnostics
): DebugInfoSection {
    val items = buildList {
        add(
            DebugInfoItem(
                "Bridge",
                wifiDirectSendBridgeStateSummary(diagnostics)
            )
        )
        add(
            DebugInfoItem(
                "Submitted",
                diagnostics.framesSubmitted.toString()
            )
        )
        add(
            DebugInfoItem(
                "Failures",
                diagnostics.submitFailures.toString()
            )
        )
        add(
            DebugInfoItem(
                "Last size",
                wifiDirectFrameSizeSummary(diagnostics.lastSubmittedFrameSize)
            )
        )
        diagnostics.lastSendBridgeError?.takeIf { it.isNotBlank() }?.let { errorText ->
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
        title = "Send bridge",
        items = items
    )
}

internal fun buildNearbyWifiDirectGlobalSendDebugSection(
    diagnostics: WifiDirectGlobalDebugSendDiagnostics
): DebugInfoSection {
    val items = buildList {
        add(
            DebugInfoItem(
                "Global send",
                wifiDirectGlobalDebugSendStateSummary(diagnostics)
            )
        )
        add(
            DebugInfoItem(
                "Mode",
                wifiDirectGlobalDebugSendModeSummary(diagnostics)
            )
        )
        add(
            DebugInfoItem(
                "Attempts",
                diagnostics.globalSubmissionAttempts.toString()
            )
        )
        add(
            DebugInfoItem(
                "Success",
                diagnostics.globalSubmissionSuccesses.toString()
            )
        )
        add(
            DebugInfoItem(
                "Failures",
                diagnostics.globalSubmitFailures.toString()
            )
        )
        add(
            DebugInfoItem(
                "Last msg",
                diagnostics.lastGlobalMessageId ?: "none"
            )
        )
        diagnostics.lastGlobalSendResult?.takeIf { it.isNotBlank() }?.let { resultText ->
            add(
                DebugInfoItem(
                    "Last result",
                    resultText
                )
            )
        }
        add(
            DebugInfoItem(
                "Last size",
                wifiDirectFrameSizeSummary(diagnostics.lastGlobalFrameSize)
            )
        )
        diagnostics.lastGlobalSendError?.takeIf { it.isNotBlank() }?.let { errorText ->
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
        title = "Global send",
        items = items
    )
}

internal fun buildNearbyWifiDirectSmokeTestDebugSection(
    diagnostics: WifiDirectSmokeTestDiagnostics
): DebugInfoSection {
    val items = buildList {
        add(
            DebugInfoItem(
                "Smoke",
                wifiDirectSmokeTestStateSummary(diagnostics)
            )
        )
        add(
            DebugInfoItem(
                "Bridge",
                wifiDirectSendBridgeStateSummary(
                    WifiDirectSendBridgeDiagnostics(enabled = diagnostics.sendBridgeEnabled)
                )
            )
        )
        add(
            DebugInfoItem(
                "Adapter",
                wifiDirectTransportAdapterStateSummary(diagnostics.adapterState)
            )
        )
        add(
            DebugInfoItem(
                "Sent",
                diagnostics.smokeFramesSent.toString()
            )
        )
        add(
            DebugInfoItem(
                "Failures",
                diagnostics.smokeSendFailures.toString()
            )
        )
        diagnostics.lastSmokeSendResult?.takeIf { it.isNotBlank() }?.let { resultText ->
            add(
                DebugInfoItem(
                    "Last result",
                    resultText
                )
            )
        }
        add(
            DebugInfoItem(
                "Last size",
                wifiDirectFrameSizeSummary(diagnostics.lastSmokeFrameSize)
            )
        )
        diagnostics.lastSmokeError?.takeIf { it.isNotBlank() }?.let { errorText ->
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
        title = "Smoke",
        items = items
    )
}

internal fun buildNearbyWifiDirectReceiveBridgeDebugSection(
    diagnostics: WifiDirectReceiveBridgeDiagnostics
): DebugInfoSection {
    val items = buildList {
        add(
            DebugInfoItem(
                "Bridge",
                wifiDirectReceiveBridgeStateSummary(diagnostics)
            )
        )
        add(
            DebugInfoItem(
                "Bridged",
                diagnostics.framesBridged.toString()
            )
        )
        add(
            DebugInfoItem(
                "Failures",
                diagnostics.bridgeFailures.toString()
            )
        )
        add(
            DebugInfoItem(
                "Last size",
                wifiDirectFrameSizeSummary(diagnostics.lastBridgedFrameSize)
            )
        )
        diagnostics.lastBridgeError?.takeIf { it.isNotBlank() }?.let { errorText ->
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
        title = "Receive bridge",
        items = items
    )
}

internal fun buildNearbyWifiDirectDiscoveryDebugSection(
    runtimeStatus: WifiDirectRuntimeStatus
): DebugInfoSection {
    val items = buildList {
        add(DebugInfoItem("Supported", wifiDirectSupportSummary(runtimeStatus)))
        add(
            DebugInfoItem(
                "Permissions",
                wifiDirectPermissionsSummary(runtimeStatus.permissionStatus)
            )
        )
        wifiDirectMissingPermissionsSummary(runtimeStatus.permissionStatus)?.let { missingText ->
            add(DebugInfoItem("Missing", missingText, preferFullWidth = true))
        }
        add(DebugInfoItem("Wi-Fi/P2P", nearbyWifiDirectEnabledValue(runtimeStatus.enabledState)))
        add(
            DebugInfoItem(
                "Discovery",
                wifiDirectDiscoverySummary(runtimeStatus.discoveryState)
            )
        )
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
        runtimeStatus.lastError?.takeIf { it.isNotBlank() }?.let { errorText ->
            add(DebugInfoItem("Last error", errorText, preferFullWidth = true))
        }
    }

    return DebugInfoSection(
        title = "Discovery",
        items = items
    )
}

internal fun buildNearbyWifiDirectConnectionDebugSection(
    runtimeStatus: WifiDirectRuntimeStatus
): DebugInfoSection {
    val connectionStatus = runtimeStatus.connectionStatus
    val items = buildList {
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
            add(DebugInfoItem("Owner", ownerAddress, preferFullWidth = true))
        }
        connectionStatus.lastError?.takeIf { it.isNotBlank() }?.let { errorText ->
            add(DebugInfoItem("Connect error", errorText, preferFullWidth = true))
        }
    }

    return DebugInfoSection(
        title = "Connection/group",
        items = items
    )
}

internal fun buildNearbyWifiDirectSocketFrameDebugSection(
    diagnostics: WifiDirectSocketDiagnostics,
    adapterDiagnostics: WifiDirectTransportAdapterDiagnostics
): DebugInfoSection {
    val frameDiagnostics = diagnostics.frameDiagnostics
    val items = buildList {
        add(DebugInfoItem("Socket", wifiDirectSocketStateSummary(diagnostics.state)))
        add(DebugInfoItem("Connected", wifiDirectSocketConnectedSummary(diagnostics.isConnected)))
        add(DebugInfoItem("Endpoint", wifiDirectSocketEndpointSummary(diagnostics.endpoint)))
        add(
            DebugInfoItem(
                "Socket bytes",
                wifiDirectSocketByteSummary(diagnostics)
            )
        )
        add(
            DebugInfoItem(
                "Frame",
                wifiDirectFrameTransportStateSummary(frameDiagnostics.state)
            )
        )
        add(
            DebugInfoItem(
                "Frame bytes",
                wifiDirectFrameByteSummary(frameDiagnostics)
            )
        )
        add(
            DebugInfoItem(
                "Adapter",
                wifiDirectTransportAdapterStateSummary(adapterDiagnostics.state)
            )
        )
        add(
            DebugInfoItem(
                "Adapter bytes",
                wifiDirectTransportAdapterByteSummary(adapterDiagnostics)
            )
        )
        add(DebugInfoItem("Sent", wifiDirectSocketMessageSummary(diagnostics.lastSentMessage)))
        add(
            DebugInfoItem(
                "Received",
                wifiDirectSocketMessageSummary(diagnostics.lastReceivedMessage)
            )
        )
        add(
            DebugInfoItem(
                "Last size",
                wifiDirectFrameSizeSummary(
                    frameDiagnostics.lastFrameSize ?: adapterDiagnostics.lastFrameSize
                )
            )
        )
        diagnostics.lastError?.takeIf { it.isNotBlank() }?.let { errorText ->
            add(DebugInfoItem("Socket error", errorText, preferFullWidth = true))
        }
        frameDiagnostics.lastError?.takeIf { it.isNotBlank() }?.let { errorText ->
            add(DebugInfoItem("Frame error", errorText, preferFullWidth = true))
        }
        adapterDiagnostics.lastError?.takeIf { it.isNotBlank() }?.let { errorText ->
            add(DebugInfoItem("Adapter error", errorText, preferFullWidth = true))
        }
        add(
            DebugInfoItem(
                "Note",
                "${diagnostics.note} ${adapterDiagnostics.note}".trim(),
                preferFullWidth = true
            )
        )
    }

    return DebugInfoSection(
        title = "Socket/frame",
        items = items
    )
}

internal fun buildNearbyWifiDirectBridgesDebugSection(
    sendBridgeDiagnostics: WifiDirectSendBridgeDiagnostics,
    smokeTestDiagnostics: WifiDirectSmokeTestDiagnostics,
    receiveBridgeDiagnostics: WifiDirectReceiveBridgeDiagnostics,
    readiness: NearbyWifiDirectGlobalDebugReadiness
): DebugInfoSection {
    val items = buildList {
        add(
            DebugInfoItem(
                "Send bridge",
                wifiDirectSendBridgeStateSummary(sendBridgeDiagnostics)
            )
        )
        add(DebugInfoItem("Submitted", sendBridgeDiagnostics.framesSubmitted.toString()))
        add(DebugInfoItem("Send fail", sendBridgeDiagnostics.submitFailures.toString()))
        add(
            DebugInfoItem(
                "Receive bridge",
                wifiDirectReceiveBridgeStateSummary(receiveBridgeDiagnostics)
            )
        )
        add(DebugInfoItem("Bridged", receiveBridgeDiagnostics.framesBridged.toString()))
        add(DebugInfoItem("Recv fail", receiveBridgeDiagnostics.bridgeFailures.toString()))
        add(
            DebugInfoItem(
                "Smoke",
                wifiDirectSmokeTestStateSummary(smokeTestDiagnostics)
            )
        )
        add(DebugInfoItem("Smoke sent", smokeTestDiagnostics.smokeFramesSent.toString()))
        add(DebugInfoItem("Smoke fail", smokeTestDiagnostics.smokeSendFailures.toString()))
        readiness.bridgeMismatchWarning?.let { warningText ->
            add(DebugInfoItem("Warning", warningText, preferFullWidth = true))
        }
        readiness.receiveBridgeWarning?.let { warningText ->
            add(DebugInfoItem("Receive warn", warningText, preferFullWidth = true))
        }
    }

    return DebugInfoSection(
        title = "Bridges",
        items = items
    )
}

internal fun buildNearbyWifiDirectGlobalWorkflowDebugSection(
    runtimeStatus: WifiDirectRuntimeStatus,
    socketDiagnostics: WifiDirectSocketDiagnostics,
    adapterDiagnostics: WifiDirectTransportAdapterDiagnostics,
    sendBridgeDiagnostics: WifiDirectSendBridgeDiagnostics,
    globalSendDiagnostics: WifiDirectGlobalDebugSendDiagnostics,
    receiveBridgeDiagnostics: WifiDirectReceiveBridgeDiagnostics
): DebugInfoSection {
    val readiness = nearbyWifiDirectGlobalDebugReadiness(
        runtimeStatus = runtimeStatus,
        socketDiagnostics = socketDiagnostics,
        adapterDiagnostics = adapterDiagnostics,
        sendBridgeDiagnostics = sendBridgeDiagnostics,
        globalSendDiagnostics = globalSendDiagnostics,
        receiveBridgeDiagnostics = receiveBridgeDiagnostics
    )
    val items = buildList {
        add(DebugInfoItem("Overall", readiness.overallStatus, preferFullWidth = true))
        add(DebugInfoItem("Discovery", readiness.discoveryStatus))
        add(DebugInfoItem("Connection", readiness.connectionStatus))
        add(DebugInfoItem("Socket/frame", readiness.socketFrameStatus))
        add(DebugInfoItem("Adapter", readiness.adapterStatus))
        add(DebugInfoItem("Send bridge", readiness.sendBridgeStatus))
        add(DebugInfoItem("Receive bridge", readiness.receiveBridgeStatus))
        add(DebugInfoItem("Global send", readiness.globalSendStatus))
        add(
            DebugInfoItem(
                "Mode",
                wifiDirectGlobalDebugSendModeSummary(globalSendDiagnostics),
                preferFullWidth = true
            )
        )
        add(
            DebugInfoItem(
                "Last msg",
                globalSendDiagnostics.lastGlobalMessageId ?: "none"
            )
        )
        globalSendDiagnostics.lastGlobalSendResult?.takeIf { it.isNotBlank() }?.let { resultText ->
            add(DebugInfoItem("Last result", resultText))
        }
        add(
            DebugInfoItem(
                "Last size",
                wifiDirectFrameSizeSummary(globalSendDiagnostics.lastGlobalFrameSize)
            )
        )
        readiness.globalSendBlockedReason?.let { reasonText ->
            add(DebugInfoItem("Blocked", reasonText, preferFullWidth = true))
        }
        globalSendDiagnostics.lastGlobalSendError?.takeIf { it.isNotBlank() }?.let { errorText ->
            add(DebugInfoItem("Last error", errorText, preferFullWidth = true))
        }
        add(
            DebugInfoItem(
                "Guide",
                nearbyWifiDirectGlobalDebugGuidance,
                preferFullWidth = true
            )
        )
        add(
            DebugInfoItem(
                "Note",
                nearbyWifiDirectGlobalDebugBleNote,
                preferFullWidth = true
            )
        )
    }

    return DebugInfoSection(
        title = "Global debug send",
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
                text = "Send bridge: ${readiness.sendBridgeStatus} | Receive bridge: ${readiness.receiveBridgeStatus}",
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
                text = "Global send: ${readiness.globalSendStatus}",
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

internal fun nearbyWifiDirectEnabledValue(
    state: WifiDirectEnabledState
): String {
    return wifiDirectEnabledSummary(state)
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
