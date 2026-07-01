package gr.hua.aurora.ui.screens

import gr.hua.aurora.model.AuroraContact
import gr.hua.aurora.model.PrivateChatIdentity
import gr.hua.aurora.ui.components.DebugInfoItem
import gr.hua.aurora.ui.components.DebugInfoSection
import gr.hua.aurora.wifidirect.WifiDirectPrivateDebugSendDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectReceiveBridgeDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectRuntimeStatus
import gr.hua.aurora.wifidirect.WifiDirectSendBridgeDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectTransportAdapterDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectTransportAdapterState
import gr.hua.aurora.wifidirect.wifiDirectDiscoveryBlockedReason
import gr.hua.aurora.wifidirect.wifiDirectPrivateDebugSendModeSummary
import gr.hua.aurora.wifidirect.wifiDirectPrivateDebugSendStateSummary
import gr.hua.aurora.wifidirect.wifiDirectReceiveBridgeStateSummary
import gr.hua.aurora.wifidirect.wifiDirectSendBridgeStateSummary
import gr.hua.aurora.wifidirect.wifiDirectTransportAdapterStateSummary

internal const val privateChatWifiDirectDebugNote =
    "Debug only. Receiver must have the Wi-Fi Direct receive bridge enabled. BLE remains the normal Private Chat path."

internal data class WifiDirectPrivateDebugGuard(
    val privateDebugSendEnabled: Boolean,
    val adapterReady: Boolean,
    val sendBridgeEnabled: Boolean,
    val receiveBridgeEnabled: Boolean,
    val contactExists: Boolean,
    val privateChatIdReady: Boolean,
    val runtimeSessionReady: Boolean,
    val persistsRawSessionSecrets: Boolean = false,
    val exposesPlaintextToRelays: Boolean = false
) {
    val prerequisitesReady: Boolean
        get() = privateDebugSendEnabled &&
            adapterReady &&
            sendBridgeEnabled &&
            contactExists &&
            privateChatIdReady &&
            runtimeSessionReady
}

internal data class WifiDirectPrivateDebugReadiness(
    val overallStatus: String,
    val pathStatus: String,
    val canAttemptWhenWired: Boolean,
    val blockedReason: String? = null,
    val isWired: Boolean = false
)

internal data class WifiDirectPrivateDebugDiagnostics(
    val guard: WifiDirectPrivateDebugGuard,
    val readiness: WifiDirectPrivateDebugReadiness,
    val privateDebugSendStatus: String,
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
    adapterDiagnostics: WifiDirectTransportAdapterDiagnostics,
    sendBridgeDiagnostics: WifiDirectSendBridgeDiagnostics,
    privateDebugSendDiagnostics: WifiDirectPrivateDebugSendDiagnostics,
    receiveBridgeDiagnostics: WifiDirectReceiveBridgeDiagnostics
): WifiDirectPrivateDebugDiagnostics {
    val guard = WifiDirectPrivateDebugGuard(
        privateDebugSendEnabled = privateDebugSendDiagnostics.enabled,
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
        !guard.adapterReady -> "Wi-Fi Direct adapter not ready."
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
                guard.prerequisitesReady -> "ready"
                guard.privateDebugSendEnabled -> "not ready"
                else -> "disabled"
            },
            pathStatus = if (guard.privateDebugSendEnabled) {
                "BLE primary + Wi-Fi Direct debug copy"
            } else {
                "BLE only"
            },
            canAttemptWhenWired = guard.prerequisitesReady,
            blockedReason = blockedReason,
            isWired = guard.privateDebugSendEnabled
        ),
        privateDebugSendStatus = wifiDirectPrivateDebugSendStateSummary(privateDebugSendDiagnostics),
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
            add(DebugInfoItem("Path", diagnostics.readiness.pathStatus))
            add(DebugInfoItem("Private send", diagnostics.privateDebugSendStatus))
            add(DebugInfoItem("Mode", diagnostics.modeStatus))
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
            add(
                DebugInfoItem(
                    "Last result",
                    diagnostics.lastResult ?: "none"
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
            diagnostics.lastError?.let { lastError ->
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
