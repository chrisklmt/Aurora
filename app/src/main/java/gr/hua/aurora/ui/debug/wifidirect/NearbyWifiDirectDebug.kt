package gr.hua.aurora.ui.debug.wifidirect

import gr.hua.aurora.ui.components.DebugInfoItem
import gr.hua.aurora.ui.components.DebugInfoSection
import gr.hua.aurora.ui.components.DebugInfoCardModel
import gr.hua.aurora.wifidirect.model.WifiDirectPeer
import gr.hua.aurora.wifidirect.controller.WifiDirectEnabledState
import gr.hua.aurora.wifidirect.controller.wifiDirectDiscoveryBlockedReason
import gr.hua.aurora.wifidirect.controller.wifiDirectPeerMatches
import gr.hua.aurora.wifidirect.runtime.WifiDirectRolePreference
import gr.hua.aurora.wifidirect.runtime.WifiDirectRuntimeStatus
import gr.hua.aurora.wifidirect.runtime.wifiDirectConnectionRoleSummary
import gr.hua.aurora.wifidirect.runtime.wifiDirectConnectionSummary
import gr.hua.aurora.wifidirect.runtime.wifiDirectDiscoverySummary
import gr.hua.aurora.wifidirect.runtime.wifiDirectEnabledSummary
import gr.hua.aurora.wifidirect.runtime.wifiDirectGroupFormedSummary
import gr.hua.aurora.wifidirect.runtime.wifiDirectMissingPermissionsSummary
import gr.hua.aurora.wifidirect.runtime.wifiDirectPermissionsSummary
import gr.hua.aurora.wifidirect.runtime.wifiDirectSupportSummary
import gr.hua.aurora.wifidirect.frame.WifiDirectFrameTransportState
import gr.hua.aurora.wifidirect.frame.WifiDirectTransportAdapterDiagnostics
import gr.hua.aurora.wifidirect.frame.wifiDirectFrameByteSummary
import gr.hua.aurora.wifidirect.frame.wifiDirectFrameCountSummary
import gr.hua.aurora.wifidirect.frame.wifiDirectFrameSizeSummary
import gr.hua.aurora.wifidirect.frame.wifiDirectFrameTransportStateSummary
import gr.hua.aurora.wifidirect.frame.wifiDirectTransportAdapterByteSummary
import gr.hua.aurora.wifidirect.frame.wifiDirectTransportAdapterStateSummary
import gr.hua.aurora.wifidirect.runtime.wifiDirectTransportSummary
import gr.hua.aurora.wifidirect.debug.WifiDirectGlobalDebugSendDiagnostics
import gr.hua.aurora.wifidirect.debug.WifiDirectPrivateDebugSendDiagnostics
import gr.hua.aurora.wifidirect.debug.WifiDirectReceiveBridgeDiagnostics
import gr.hua.aurora.wifidirect.debug.WifiDirectSendBridgeDiagnostics
import gr.hua.aurora.wifidirect.debug.WifiDirectSmokeTestDiagnostics
import gr.hua.aurora.wifidirect.debug.wifiDirectGlobalDebugSendModeSummary
import gr.hua.aurora.wifidirect.debug.wifiDirectGlobalDebugSendStateSummary
import gr.hua.aurora.wifidirect.debug.wifiDirectReceiveBridgeStateSummary
import gr.hua.aurora.wifidirect.debug.wifiDirectSendBridgeStateSummary
import gr.hua.aurora.wifidirect.debug.wifiDirectSmokeTestStateSummary
import gr.hua.aurora.wifidirect.socket.WifiDirectSocketDiagnostics
import gr.hua.aurora.wifidirect.socket.wifiDirectEffectiveFrameTransportState
import gr.hua.aurora.wifidirect.socket.wifiDirectSocketByteSummary
import gr.hua.aurora.wifidirect.socket.wifiDirectSocketCommandResultSummary
import gr.hua.aurora.wifidirect.socket.wifiDirectSocketCommandSummary
import gr.hua.aurora.wifidirect.socket.wifiDirectSocketConnectedSummary
import gr.hua.aurora.wifidirect.socket.wifiDirectSocketEndpointSummary
import gr.hua.aurora.wifidirect.socket.wifiDirectSocketFrameReadinessReason
import gr.hua.aurora.wifidirect.socket.wifiDirectSocketMessageSummary
import gr.hua.aurora.wifidirect.socket.wifiDirectSocketRoleSummary
import gr.hua.aurora.wifidirect.socket.wifiDirectSocketStateSummary

private val nearbyWifiDirectManualTestSteps = listOf(
    "Enable Debug Mode.",
    "Start Wi-Fi Direct discovery.",
    "Connect/group devices.",
    "Start socket server on group owner.",
    "Connect socket client on peer.",
    "Verify ping/pong or debug frame.",
    "Enable send bridge on sender.",
    "Enable receive bridge on receiver.",
    "For Global: enable Global Wi-Fi Direct debug send, then send Global Chat message.",
    "For Private: open Private Chat, enable Private Wi-Fi Direct debug send, then send Private Chat message.",
    "Verify receiver sees one message only.",
    "Verify BLE still works if Wi-Fi Direct debug send fails.",
    "Verify disabling receive bridge prevents Wi-Fi Direct frames from changing chat UI.",
    "Verify no Delivered/ACK appears."
)

internal data class NearbyWifiDirectCompactSummary(
    val status: String,
    val discovery: String,
    val group: String,
    val role: String,
    val socket: String,
    val adapter: String,
    val sendBridge: String,
    val receiveBridge: String,
    val globalDebugSend: String,
    val nextStep: String
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
                    "Peer type",
                    "Generic Wi-Fi Direct peers",
                    preferFullWidth = true
                )
            )
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
        add(
            DebugInfoItem(
                "Action",
                wifiDirectSocketCommandSummary(diagnostics)
            )
        )
        add(
            DebugInfoItem(
                "Result",
                wifiDirectSocketCommandResultSummary(diagnostics)
            )
        )
        add(
            DebugInfoItem(
                "Attempts",
                "S${diagnostics.serverStartAttempts} C${diagnostics.clientConnectAttempts} X${diagnostics.closeAttempts}"
            )
        )
        diagnostics.lastCommandHost?.takeIf { it.isNotBlank() }?.let { hostText ->
            add(
                DebugInfoItem(
                    "Host",
                    hostText,
                    preferFullWidth = true
                )
            )
        }
        diagnostics.lastCommandError?.takeIf { it.isNotBlank() }?.let { errorText ->
            add(
                DebugInfoItem(
                    "Action error",
                    errorText,
                    preferFullWidth = true
                )
            )
        }
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
    val effectiveFrameState = wifiDirectEffectiveFrameTransportState(diagnostics)
    val items = buildList {
        add(
            DebugInfoItem(
                "Transport",
                wifiDirectFrameTransportStateSummary(effectiveFrameState)
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
        wifiDirectSocketFrameReadinessReason(diagnostics)?.takeIf { it.isNotBlank() }?.let { reason ->
            add(
                DebugInfoItem(
                    "Reason",
                    reason,
                    preferFullWidth = true
                )
            )
        }
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
        diagnostics.notReadyReason?.takeIf { it.isNotBlank() }?.let { reasonText ->
            add(
                DebugInfoItem(
                    "Blocked",
                    reasonText,
                    preferFullWidth = true
                )
            )
        }
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
                "Transport",
                diagnostics.transportFramesObserved.toString()
            )
        )
        add(
            DebugInfoItem(
                "Duplicates",
                diagnostics.duplicateFramesDropped.toString()
            )
        )
        add(
            DebugInfoItem(
                "Failures",
                diagnostics.bridgeFailures.toString()
            )
        )
        diagnostics.lastToggleAction?.takeIf { it.isNotBlank() }?.let { actionText ->
            add(
                DebugInfoItem(
                    "Action",
                    actionText,
                    preferFullWidth = true
                )
            )
        }
        diagnostics.lastToggleResult?.takeIf { it.isNotBlank() }?.let { resultText ->
            add(
                DebugInfoItem(
                    "Toggle",
                    resultText
                )
            )
        }
        diagnostics.lastToggleBlockedReason?.takeIf { it.isNotBlank() }?.let { reasonText ->
            add(
                DebugInfoItem(
                    "Blocked",
                    reasonText,
                    preferFullWidth = true
                )
            )
        }
        diagnostics.lastBridgeResult?.takeIf { it.isNotBlank() }?.let { resultText ->
            add(
                DebugInfoItem(
                    "Last result",
                    resultText,
                    preferFullWidth = true
                )
            )
        }
        add(
            DebugInfoItem(
                "Last size",
                wifiDirectFrameSizeSummary(diagnostics.lastTransportFrameSize)
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
                    "Peer type",
                    "Generic Wi-Fi Direct peers",
                    preferFullWidth = true
                )
            )
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
    val effectiveFrameState = wifiDirectEffectiveFrameTransportState(diagnostics)
    val items = buildList {
        add(DebugInfoItem("Socket", wifiDirectSocketStateSummary(diagnostics.state)))
        add(DebugInfoItem("Role", wifiDirectSocketRoleSummary(diagnostics.role)))
        add(DebugInfoItem("Connected", wifiDirectSocketConnectedSummary(diagnostics.isConnected)))
        add(
            DebugInfoItem(
                "Read loop",
                if (diagnostics.isReadLoopActive) "active" else "idle"
            )
        )
        add(DebugInfoItem("Endpoint", wifiDirectSocketEndpointSummary(diagnostics.endpoint)))
        add(
            DebugInfoItem(
                "Socket bytes",
                wifiDirectSocketByteSummary(diagnostics)
            )
        )
        add(
            DebugInfoItem(
                "Action",
                wifiDirectSocketCommandSummary(diagnostics)
            )
        )
        add(
            DebugInfoItem(
                "Result",
                wifiDirectSocketCommandResultSummary(diagnostics)
            )
        )
        diagnostics.lastCommandHost?.takeIf { it.isNotBlank() }?.let { hostText ->
            add(
                DebugInfoItem(
                    "Host",
                    hostText,
                    preferFullWidth = true
                )
            )
        }
        add(
            DebugInfoItem(
                "Frame",
                wifiDirectFrameTransportStateSummary(effectiveFrameState)
            )
        )
        add(
            DebugInfoItem(
                "Write path",
                if (diagnostics.isConnected && effectiveFrameState == WifiDirectFrameTransportState.READY) {
                    "ready"
                } else {
                    "not ready"
                }
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
                "Out size",
                wifiDirectFrameSizeSummary(
                    diagnostics.lastOutboundFrameSize ?: frameDiagnostics.lastSentFrameSize
                )
            )
        )
        add(
            DebugInfoItem(
                "In size",
                wifiDirectFrameSizeSummary(
                    diagnostics.lastInboundFrameSize ?: frameDiagnostics.lastReceivedFrameSize
                )
            )
        )
        wifiDirectSocketFrameReadinessReason(diagnostics)?.takeIf { it.isNotBlank() }?.let { reasonText ->
            add(DebugInfoItem("Frame reason", reasonText, preferFullWidth = true))
        }
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
        add(
            DebugInfoItem(
                "Adapter out",
                wifiDirectFrameSizeSummary(adapterDiagnostics.lastSubmittedFrameSize)
            )
        )
        add(
            DebugInfoItem(
                "Adapter in",
                wifiDirectFrameSizeSummary(adapterDiagnostics.lastReceivedFrameSize)
            )
        )
        adapterDiagnostics.notReadyReason?.takeIf { it.isNotBlank() }?.let { reasonText ->
            add(DebugInfoItem("Adapter reason", reasonText, preferFullWidth = true))
        }
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
        receiveBridgeDiagnostics.lastToggleAction?.takeIf { it.isNotBlank() }?.let { actionText ->
            add(DebugInfoItem("Receive action", actionText, preferFullWidth = true))
        }
        receiveBridgeDiagnostics.lastToggleResult?.takeIf { it.isNotBlank() }?.let { resultText ->
            add(DebugInfoItem("Receive toggle", resultText))
        }
        receiveBridgeDiagnostics.lastToggleBlockedReason?.takeIf { it.isNotBlank() }?.let { reasonText ->
            add(DebugInfoItem("Receive blocked", reasonText, preferFullWidth = true))
        }
        add(DebugInfoItem("Bridged", receiveBridgeDiagnostics.framesBridged.toString()))
        add(DebugInfoItem("Transport", receiveBridgeDiagnostics.transportFramesObserved.toString()))
        add(DebugInfoItem("Dup", receiveBridgeDiagnostics.duplicateFramesDropped.toString()))
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

internal fun buildNearbyWifiDirectManualReadinessSection(
    readiness: NearbyWifiDirectManualTestReadiness
): DebugInfoSection {
    return DebugInfoSection(
        title = "Manual test readiness",
        items = listOf(
            DebugInfoItem("Overall", readiness.overallStatus, preferFullWidth = true),
            DebugInfoItem("Discovery", readiness.discoveryStatus),
            DebugInfoItem("Group", readiness.groupStatus),
            DebugInfoItem("Socket/frame", readiness.socketFrameStatus),
            DebugInfoItem("Adapter", readiness.adapterStatus),
            DebugInfoItem("Send bridge", readiness.sendBridgeStatus),
            DebugInfoItem("Receive bridge", readiness.receiveBridgeStatus),
            DebugInfoItem("Global send", readiness.globalDebugSendStatus),
            DebugInfoItem("Private send", readiness.privateDebugSendStatus)
        )
    )
}

internal fun buildNearbyWifiDirectManualChecklistSection(): DebugInfoSection {
    return DebugInfoSection(
        title = "Manual test",
        items = nearbyWifiDirectManualTestSteps.mapIndexed { index, step ->
            DebugInfoItem(
                label = "Step ${index + 1}",
                value = step,
                preferFullWidth = true
            )
        }
    )
}

internal fun buildNearbyWifiDirectDetailsCard(
    runtimeStatus: WifiDirectRuntimeStatus,
    socketDiagnostics: WifiDirectSocketDiagnostics,
    adapterDiagnostics: WifiDirectTransportAdapterDiagnostics,
    sendBridgeDiagnostics: WifiDirectSendBridgeDiagnostics,
    globalSendDiagnostics: WifiDirectGlobalDebugSendDiagnostics,
    smokeTestDiagnostics: WifiDirectSmokeTestDiagnostics,
    receiveBridgeDiagnostics: WifiDirectReceiveBridgeDiagnostics
): DebugInfoCardModel {
    return DebugInfoCardModel(
        title = "Wi-Fi Direct details",
        sections = listOf(
            buildNearbyWifiDirectDiscoveryDebugSection(runtimeStatus),
            buildNearbyWifiDirectConnectionDebugSection(runtimeStatus),
            buildNearbyWifiDirectSocketFrameDebugSection(
                diagnostics = socketDiagnostics,
                adapterDiagnostics = adapterDiagnostics
            ),
            buildNearbyWifiDirectBridgesDebugSection(
                sendBridgeDiagnostics = sendBridgeDiagnostics,
                smokeTestDiagnostics = smokeTestDiagnostics,
                receiveBridgeDiagnostics = receiveBridgeDiagnostics,
                readiness = nearbyWifiDirectGlobalDebugReadiness(
                    runtimeStatus = runtimeStatus,
                    socketDiagnostics = socketDiagnostics,
                    adapterDiagnostics = adapterDiagnostics,
                    sendBridgeDiagnostics = sendBridgeDiagnostics,
                    globalSendDiagnostics = globalSendDiagnostics,
                    receiveBridgeDiagnostics = receiveBridgeDiagnostics
                )
            ),
            buildNearbyWifiDirectGlobalWorkflowDebugSection(
                runtimeStatus = runtimeStatus,
                socketDiagnostics = socketDiagnostics,
                adapterDiagnostics = adapterDiagnostics,
                sendBridgeDiagnostics = sendBridgeDiagnostics,
                globalSendDiagnostics = globalSendDiagnostics,
                receiveBridgeDiagnostics = receiveBridgeDiagnostics
            ),
            buildNearbyWifiDirectManualChecklistSection()
        )
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

internal fun nearbyWifiDirectEnabledValue(
    state: WifiDirectEnabledState
): String {
    return wifiDirectEnabledSummary(state)
}

internal fun nearbyWifiDirectCompactSummary(
    runtimeStatus: WifiDirectRuntimeStatus,
    socketDiagnostics: WifiDirectSocketDiagnostics,
    adapterDiagnostics: WifiDirectTransportAdapterDiagnostics,
    sendBridgeDiagnostics: WifiDirectSendBridgeDiagnostics,
    globalSendDiagnostics: WifiDirectGlobalDebugSendDiagnostics,
    privateDebugSendDiagnostics: WifiDirectPrivateDebugSendDiagnostics,
    receiveBridgeDiagnostics: WifiDirectReceiveBridgeDiagnostics
): NearbyWifiDirectCompactSummary {
    val manualReadiness = nearbyWifiDirectManualTestReadiness(
        runtimeStatus = runtimeStatus,
        socketDiagnostics = socketDiagnostics,
        adapterDiagnostics = adapterDiagnostics,
        sendBridgeDiagnostics = sendBridgeDiagnostics,
        globalSendDiagnostics = globalSendDiagnostics,
        privateDebugSendDiagnostics = privateDebugSendDiagnostics,
        receiveBridgeDiagnostics = receiveBridgeDiagnostics
    )
    val nextStep = nearbyWifiDirectManualNextStep(
        runtimeStatus = runtimeStatus,
        socketDiagnostics = socketDiagnostics,
        adapterDiagnostics = adapterDiagnostics,
        sendBridgeDiagnostics = sendBridgeDiagnostics,
        globalSendDiagnostics = globalSendDiagnostics,
        privateDebugSendDiagnostics = privateDebugSendDiagnostics,
        receiveBridgeDiagnostics = receiveBridgeDiagnostics
    )
    val socketReady = wifiDirectEffectiveFrameTransportState(socketDiagnostics) ==
        WifiDirectFrameTransportState.READY

    return NearbyWifiDirectCompactSummary(
        status = if (manualReadiness.overallStatus.startsWith("Ready")) "Ready" else "Not ready",
        discovery = manualReadiness.discoveryStatus,
        group = manualReadiness.groupStatus,
        role = wifiDirectConnectionRoleSummary(runtimeStatus.connectionStatus.role),
        socket = if (socketReady) "ready" else "not ready",
        adapter = if (adapterDiagnostics.state == gr.hua.aurora.wifidirect.frame.WifiDirectTransportAdapterState.READY) {
            "ready"
        } else {
            "not ready"
        },
        sendBridge = manualReadiness.sendBridgeStatus,
        receiveBridge = manualReadiness.receiveBridgeStatus,
        globalDebugSend = manualReadiness.globalDebugSendStatus,
        nextStep = nextStep.title
    )
}

internal fun nearbyAdvancedSectionToggleLabel(
    title: String,
    expanded: Boolean
): String {
    require(title.isNotBlank()) {
        "Nearby advanced section title must not be blank."
    }

    return if (expanded) {
        "Hide $title"
    } else {
        "Show $title"
    }
}

internal fun buildNearbyWifiDirectDetailsAdvancedCard(
    runtimeStatus: WifiDirectRuntimeStatus
): DebugInfoCardModel {
    return DebugInfoCardModel(
        title = "Wi-Fi Direct details",
        sections = listOf(
            buildNearbyWifiDirectDiscoveryDebugSection(runtimeStatus),
            buildNearbyWifiDirectConnectionDebugSection(runtimeStatus)
        )
    )
}

internal fun buildNearbyWifiDirectSocketDiagnosticsCard(
    socketDiagnostics: WifiDirectSocketDiagnostics,
    adapterDiagnostics: WifiDirectTransportAdapterDiagnostics
): DebugInfoCardModel {
    return DebugInfoCardModel(
        title = "Socket diagnostics",
        sections = listOf(
            buildNearbyWifiDirectSocketDebugSection(socketDiagnostics),
            buildNearbyWifiDirectFrameDebugSection(socketDiagnostics),
            buildNearbyWifiDirectAdapterDebugSection(adapterDiagnostics)
        )
    )
}

internal fun buildNearbyWifiDirectBridgeDiagnosticsCard(
    sendBridgeDiagnostics: WifiDirectSendBridgeDiagnostics,
    smokeTestDiagnostics: WifiDirectSmokeTestDiagnostics,
    receiveBridgeDiagnostics: WifiDirectReceiveBridgeDiagnostics
): DebugInfoCardModel {
    return DebugInfoCardModel(
        title = "Bridge diagnostics",
        sections = listOf(
            buildNearbyWifiDirectSendBridgeDebugSection(sendBridgeDiagnostics),
            buildNearbyWifiDirectReceiveBridgeDebugSection(receiveBridgeDiagnostics),
            buildNearbyWifiDirectSmokeTestDebugSection(smokeTestDiagnostics)
        )
    )
}

internal fun buildNearbyWifiDirectGlobalDiagnosticsCard(
    diagnostics: WifiDirectGlobalDebugSendDiagnostics
): DebugInfoCardModel {
    return DebugInfoCardModel(
        title = "Global debug diagnostics",
        sections = listOf(
            buildNearbyWifiDirectGlobalSendDebugSection(diagnostics)
        )
    )
}

internal fun buildNearbyWifiDirectRolePreferenceHelpCard(
    requestedPreference: WifiDirectRolePreference?,
    runtimeStatus: WifiDirectRuntimeStatus
): DebugInfoCardModel {
    val items = buildList {
        nearbyWifiDirectRolePreferenceHelpLines().forEachIndexed { index, helpText ->
            add(
                DebugInfoItem(
                    label = "Help ${index + 1}",
                    value = helpText,
                    preferFullWidth = true
                )
            )
        }
        nearbyWifiDirectRolePreferenceOutcomeLines(
            requestedPreference = requestedPreference,
            runtimeStatus = runtimeStatus
        ).forEachIndexed { index, outcomeText ->
            add(
                DebugInfoItem(
                    label = "Outcome ${index + 1}",
                    value = outcomeText,
                    preferFullWidth = true
                )
            )
        }
    }

    return DebugInfoCardModel(
        title = "Role preference help",
        sections = listOf(
            DebugInfoSection(
                title = "Role preference",
                items = items
            )
        )
    )
}

internal fun buildNearbyWifiDirectManualGuideCard(): DebugInfoCardModel {
    return DebugInfoCardModel(
        title = "Manual test guide",
        sections = listOf(
            buildNearbyWifiDirectManualChecklistSection()
        )
    )
}
