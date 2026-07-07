package gr.hua.aurora.ui.debug.wifidirect

import gr.hua.aurora.model.AuroraContact
import gr.hua.aurora.model.PrivateChatIdentity
import gr.hua.aurora.ui.components.DebugInfoCardModel
import gr.hua.aurora.ui.components.DebugInfoItem
import gr.hua.aurora.ui.components.DebugInfoSection
import gr.hua.aurora.ui.screens.privateChatDebugIdentifierValue
import gr.hua.aurora.wifidirect.WifiDirectRuntimeStatus
import gr.hua.aurora.wifidirect.WifiDirectTransportAdapterDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectTransportAdapterState
import gr.hua.aurora.wifidirect.wifiDirectDiscoveryBlockedReason
import gr.hua.aurora.wifidirect.wifiDirectTransportAdapterStateSummary
import gr.hua.aurora.wifidirect.wifiDirectFrameTransportStateSummary
import gr.hua.aurora.wifidirect.debug.WifiDirectPrivateDebugSendDiagnostics
import gr.hua.aurora.wifidirect.debug.WifiDirectReceiveBridgeDiagnostics
import gr.hua.aurora.wifidirect.debug.WifiDirectSendBridgeDiagnostics
import gr.hua.aurora.wifidirect.debug.wifiDirectPrivateDebugSendModeSummary
import gr.hua.aurora.wifidirect.debug.wifiDirectPrivateDebugSendStateSummary
import gr.hua.aurora.wifidirect.debug.wifiDirectReceiveBridgeStateSummary
import gr.hua.aurora.wifidirect.debug.wifiDirectSendBridgeStateSummary
import gr.hua.aurora.wifidirect.socket.WifiDirectSocketDiagnostics
import gr.hua.aurora.wifidirect.socket.wifiDirectEffectiveFrameTransportState
import gr.hua.aurora.wifidirect.socket.wifiDirectSocketFrameReadinessReason
import gr.hua.aurora.wifidirect.socket.wifiDirectSocketStateSummary

internal const val privateChatWifiDirectDebugNote =
    "Debug only. Receiver must have the Wi-Fi Direct receive bridge enabled. BLE remains the normal Private Chat path."

internal fun privateChatDebugDetailsToggleLabel(
    expanded: Boolean
): String {
    return if (expanded) {
        "Hide private debug details"
    } else {
        "Show private debug details"
    }
}

internal data class WifiDirectPrivateDebugGuard(
    val privateDebugSendEnabled: Boolean,
    val socketConnected: Boolean,
    val frameReady: Boolean,
    val adapterReady: Boolean,
    val sendBridgeEnabled: Boolean,
    val receiveBridgeEnabled: Boolean,
    val contactExists: Boolean,
    val privateChatIdReady: Boolean,
    val runtimeSessionReady: Boolean,
    val persistsRawSessionSecrets: Boolean = false,
    val exposesPlaintextToRelays: Boolean = false
) {
    val debugCopyReady: Boolean
        get() = privateDebugSendEnabled &&
            socketConnected &&
            frameReady &&
            adapterReady &&
            sendBridgeEnabled &&
            contactExists &&
            privateChatIdReady &&
            runtimeSessionReady

    val receivePathReady: Boolean
        get() = socketConnected &&
            frameReady &&
            adapterReady &&
            receiveBridgeEnabled &&
            contactExists &&
            privateChatIdReady &&
            runtimeSessionReady
}

internal data class WifiDirectPrivateDebugReadiness(
    val overallStatus: String,
    val pathStatus: String,
    val canAttemptWhenWired: Boolean,
    val blockedReason: String? = null,
    val receiveStatus: String,
    val isWired: Boolean = false
)

internal data class WifiDirectPrivateDebugDiagnostics(
    val guard: WifiDirectPrivateDebugGuard,
    val readiness: WifiDirectPrivateDebugReadiness,
    val privateDebugSendStatus: String,
    val socketStatus: String,
    val frameStatus: String,
    val modeStatus: String,
    val adapterStatus: String,
    val sendBridgeStatus: String,
    val receiveBridgeStatus: String,
    val contactStatus: String,
    val privateChatIdStatus: String,
    val sessionStatus: String,
    val submissions: Long,
    val successes: Long,
    val failures: Long,
    val lastMessageId: String?,
    val lastFrameSize: Int?,
    val lastResult: String?,
    val lastError: String?
)

internal fun privateChatWifiDirectDebugDiagnostics(
    contact: AuroraContact?,
    privateChatIdentity: PrivateChatIdentity?,
    hasRuntimeSession: Boolean,
    runtimeStatus: WifiDirectRuntimeStatus,
    socketDiagnostics: WifiDirectSocketDiagnostics,
    adapterDiagnostics: WifiDirectTransportAdapterDiagnostics,
    sendBridgeDiagnostics: WifiDirectSendBridgeDiagnostics,
    privateDebugSendDiagnostics: WifiDirectPrivateDebugSendDiagnostics,
    receiveBridgeDiagnostics: WifiDirectReceiveBridgeDiagnostics
): WifiDirectPrivateDebugDiagnostics {
    val frameReadyReason = wifiDirectSocketFrameReadinessReason(socketDiagnostics)
    val adapterBlockedReason = adapterDiagnostics.notReadyReason
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: adapterDiagnostics.lastError?.trim()?.takeIf { it.isNotEmpty() }
        ?: "Wi-Fi Direct adapter not ready."
    val guard = WifiDirectPrivateDebugGuard(
        privateDebugSendEnabled = privateDebugSendDiagnostics.enabled,
        socketConnected = socketDiagnostics.isConnected,
        frameReady = wifiDirectEffectiveFrameTransportState(socketDiagnostics) ==
            gr.hua.aurora.wifidirect.WifiDirectFrameTransportState.READY,
        adapterReady = adapterDiagnostics.state == WifiDirectTransportAdapterState.READY,
        sendBridgeEnabled = sendBridgeDiagnostics.enabled,
        receiveBridgeEnabled = receiveBridgeDiagnostics.enabled,
        contactExists = contact != null,
        privateChatIdReady = !privateChatIdentity?.privateChatId.isNullOrBlank(),
        runtimeSessionReady = hasRuntimeSession
    )
    val blockedReason = when {
        wifiDirectDiscoveryBlockedReason(runtimeStatus.permissionStatus) != null -> {
            wifiDirectDiscoveryBlockedReason(runtimeStatus.permissionStatus)
        }
        !guard.privateDebugSendEnabled -> "Enable Private Wi-Fi Direct debug send to add a debug copy."
        !guard.socketConnected -> "Connect Wi-Fi Direct socket."
        !guard.frameReady -> {
            val reason = frameReadyReason ?: "Wi-Fi Direct frame path not ready."
            "Wi-Fi Direct frame path not ready ($reason)."
        }
        !guard.adapterReady -> if (adapterBlockedReason == "Wi-Fi Direct adapter not ready.") {
            "Wi-Fi Direct adapter not ready."
        } else {
            "Wi-Fi Direct adapter not ready ($adapterBlockedReason)."
        }
        !guard.sendBridgeEnabled -> "Enable the Wi-Fi Direct send bridge first."
        !guard.contactExists -> "Private contact required."
        !guard.privateChatIdReady -> "Private chat id required."
        !guard.runtimeSessionReady -> "Private session required."
        else -> null
    }

    return WifiDirectPrivateDebugDiagnostics(
        guard = guard,
        readiness = WifiDirectPrivateDebugReadiness(
            overallStatus = when {
                guard.debugCopyReady -> "ready"
                !guard.privateDebugSendEnabled && guard.receivePathReady -> "receive ready"
                guard.privateDebugSendEnabled -> "not ready"
                else -> "disabled"
            },
            pathStatus = when {
                guard.privateDebugSendEnabled -> "BLE primary + Wi-Fi Direct debug copy"
                guard.receivePathReady -> "BLE primary + Wi-Fi Direct receive ready"
                else -> "BLE only"
            },
            canAttemptWhenWired = guard.debugCopyReady,
            blockedReason = blockedReason,
            receiveStatus = if (guard.receivePathReady) "ready" else "not ready",
            isWired = guard.privateDebugSendEnabled
        ),
        privateDebugSendStatus = wifiDirectPrivateDebugSendStateSummary(privateDebugSendDiagnostics),
        socketStatus = wifiDirectSocketStateSummary(socketDiagnostics.state),
        frameStatus = wifiDirectFrameTransportStateSummary(
            wifiDirectEffectiveFrameTransportState(socketDiagnostics)
        ),
        modeStatus = wifiDirectPrivateDebugSendModeSummary(privateDebugSendDiagnostics),
        adapterStatus = wifiDirectTransportAdapterStateSummary(adapterDiagnostics.state),
        sendBridgeStatus = wifiDirectSendBridgeStateSummary(sendBridgeDiagnostics),
        receiveBridgeStatus = wifiDirectReceiveBridgeStateSummary(receiveBridgeDiagnostics),
        contactStatus = if (guard.contactExists) "present" else "missing",
        privateChatIdStatus = if (guard.privateChatIdReady) "ready" else "missing",
        sessionStatus = if (guard.runtimeSessionReady) "ready" else "missing",
        submissions = privateDebugSendDiagnostics.privateSubmissionAttempts,
        successes = privateDebugSendDiagnostics.privateSubmissionSuccesses,
        failures = privateDebugSendDiagnostics.privateSubmitFailures,
        lastMessageId = privateDebugSendDiagnostics.lastPrivateMessageId,
        lastFrameSize = privateDebugSendDiagnostics.lastPrivateFrameSize,
        lastResult = privateDebugSendDiagnostics.lastPrivateSendResult,
        lastError = privateDebugSendDiagnostics.lastPrivateSendError
    )
}

internal fun buildPrivateChatWifiDirectDebugSection(
    diagnostics: WifiDirectPrivateDebugDiagnostics
): DebugInfoSection {
    return DebugInfoSection(
        title = "Wi-Fi Direct private",
        items = buildList {
            add(
                DebugInfoItem(
                    "Overall",
                    diagnostics.readiness.overallStatus,
                    preferFullWidth = true
                )
            )
            add(
                DebugInfoItem(
                    "Path",
                    diagnostics.readiness.pathStatus,
                    preferFullWidth = true
                )
            )
            add(DebugInfoItem("Private send", diagnostics.privateDebugSendStatus))
            add(DebugInfoItem("Socket", diagnostics.socketStatus))
            add(DebugInfoItem("Adapter", diagnostics.adapterStatus))
            add(DebugInfoItem("Send bridge", diagnostics.sendBridgeStatus))
            add(DebugInfoItem("Receive bridge", diagnostics.receiveBridgeStatus))
            add(DebugInfoItem("Contact", diagnostics.contactStatus))
            add(DebugInfoItem("Chat id", diagnostics.privateChatIdStatus))
            add(DebugInfoItem("Session", diagnostics.sessionStatus))
            add(DebugInfoItem("Submissions", diagnostics.submissions.toString()))
            add(DebugInfoItem("Successes", diagnostics.successes.toString()))
            add(DebugInfoItem("Failures", diagnostics.failures.toString()))
            add(
                DebugInfoItem(
                    "Last result",
                    diagnostics.lastResult ?: "none",
                    preferFullWidth = true
                )
            )
            diagnostics.readiness.blockedReason?.let { blockedReason ->
                add(
                    DebugInfoItem(
                        "Blocked",
                        blockedReason,
                        preferFullWidth = true
                    )
                )
            }
            diagnostics.lastError?.takeIf { it.isNotBlank() }?.let { lastError ->
                add(
                    DebugInfoItem(
                        "Last error",
                        lastError,
                        preferFullWidth = true
                    )
                )
            }
        }
    )
}

internal fun buildPrivateChatWifiDirectDetailsSection(
    diagnostics: WifiDirectPrivateDebugDiagnostics
): DebugInfoSection {
    return DebugInfoSection(
        title = "Wi-Fi Direct details",
        items = buildList {
            add(DebugInfoItem("Frame", diagnostics.frameStatus))
            add(DebugInfoItem("Mode", diagnostics.modeStatus))
            add(DebugInfoItem("Receive path", diagnostics.readiness.receiveStatus))
            add(
                DebugInfoItem(
                    "Last msg",
                    privateChatDebugIdentifierValue(diagnostics.lastMessageId)
                )
            )
            add(
                DebugInfoItem(
                    "Last size",
                    diagnostics.lastFrameSize?.let { "$it B" } ?: "none"
                )
            )
            diagnostics.lastError?.takeIf { it.isNotBlank() }?.let { lastError ->
                add(
                    DebugInfoItem(
                        "Last error",
                        lastError,
                        preferFullWidth = true
                    )
                )
            }
            add(
                DebugInfoItem(
                    "Note",
                    privateChatWifiDirectDebugNote,
                    preferFullWidth = true
                )
            )
        }
    )
}

internal fun buildPrivateChatWifiDirectDetailsCard(
    diagnostics: WifiDirectPrivateDebugDiagnostics
): DebugInfoCardModel {
    return DebugInfoCardModel(
        title = "Private debug details",
        sections = listOf(
            buildPrivateChatWifiDirectDetailsSection(diagnostics)
        )
    )
}
