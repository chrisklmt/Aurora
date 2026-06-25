package gr.hua.aurora.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import gr.hua.aurora.model.ChatMessage
import gr.hua.aurora.protocol.GlobalMeshDeliveryResult
import gr.hua.aurora.protocol.GlobalMeshDiagnostics
import gr.hua.aurora.state.AuroraAvailabilityPreference
import gr.hua.aurora.ui.components.AuroraAvailabilityIndicator
import gr.hua.aurora.ui.components.DebugInfoCard
import gr.hua.aurora.ui.components.DebugInfoCardModel
import gr.hua.aurora.ui.components.DebugInfoItem
import gr.hua.aurora.ui.components.DebugInfoSection
import gr.hua.aurora.ui.components.AuroraTopBarAction
import gr.hua.aurora.ui.components.ChatScaffold
import gr.hua.aurora.ui.components.rememberAuroraAvailabilityUiState
import gr.hua.aurora.ui.components.toMessageListItem
import kotlinx.coroutines.launch

@Composable
fun GlobalChatScreen(
    currentUsername: String,
    messages: List<ChatMessage>,
    queuedOutgoingCount: Int,
    transportSenderSourceLabel: String,
    globalMeshDiagnostics: GlobalMeshDiagnostics,
    lastIncomingMessageStatus: String?,
    lastConnectOnSendStatus: String?,
    lastGlobalMeshStatus: String?,
    meshDeliveryResult: GlobalMeshDeliveryResult?,
    showDebugDiagnostics: Boolean,
    onOpenContacts: () -> Unit,
    onOpenNearby: () -> Unit,
    onOpenSettings: () -> Unit,
    desiredAvailability: AuroraAvailabilityPreference,
    onDesiredAvailabilityChange: (AuroraAvailabilityPreference) -> Unit,
    onSendMessage: (String) -> Unit,
    onResetLocalData: () -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val mappedMessages = messages.map { it.toMessageListItem() }
    val availabilityState = rememberAuroraAvailabilityUiState(desiredAvailability)
    val debugCard = buildGlobalChatDebugCard(
        showDebugDiagnostics = showDebugDiagnostics,
        transportSenderSourceLabel = transportSenderSourceLabel,
        globalMeshDiagnostics = globalMeshDiagnostics,
        lastIncomingMessageStatus = lastIncomingMessageStatus,
        lastConnectOnSendStatus = lastConnectOnSendStatus,
        lastGlobalMeshStatus = lastGlobalMeshStatus,
        meshDeliveryResult = meshDeliveryResult,
        queuedOutgoingCount = queuedOutgoingCount
    )
    val bodyTopContent: (@Composable ColumnScope.() -> Unit)? = if (debugCard != null) {
        {
            DebugInfoCard(card = debugCard)
        }
    } else {
        null
    }

    // Î§ÏÎ·ÏƒÎ¹Î¼Î¿Ï€Î¿Î¹Î¿ÏÎ¼Îµ Ï„Î¿Ï€Î¹ÎºÎ¬ RTL Î¼ÏŒÎ½Î¿ Î³Î¹Î± Ï„Î¿ drawer container ÏŽÏƒÏ„Îµ Ï„Î¿ panel Î½Î± Î±Î½Î¿Î¯Î³ÎµÎ¹ Î±Ï€ÏŒ Î´ÎµÎ¾Î¹Î¬
    // Ï‡Ï‰ÏÎ¯Ï‚ Î½Î± Î±Î»Î»Î¬Î¶ÎµÎ¹ Î· ÎºÎ±Ï„ÎµÏÎ¸Ï…Î½ÏƒÎ· ÏƒÏ„Î¿ Ï…Ï€ÏŒÎ»Î¿Î¹Ï€Î¿ chat UI.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    DrawerContentHost(
                        desiredAvailability = desiredAvailability,
                        onDesiredAvailabilityChange = onDesiredAvailabilityChange,
                        onOpenContacts = {
                            scope.launch {
                                drawerState.close()
                                onOpenContacts()
                            }
                        },
                        onOpenNearby = {
                            scope.launch {
                                drawerState.close()
                                onOpenNearby()
                            }
                        },
                        onOpenSettings = {
                            scope.launch {
                                drawerState.close()
                                onOpenSettings()
                            }
                        }
                    )
                }
            }
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                ChatScaffold(
                    title = "Global Chat",
                    subtitle = null,
                    topBarSubtitleContent = {
                        AuroraAvailabilityIndicator(uiState = availabilityState.uiState)
                    },
                    messages = mappedMessages,
                    topBarUsername = currentUsername,
                    onTopBarUsernameTripleTap = onResetLocalData,
                    topBarRightAction = AuroraTopBarAction.MENU,
                    onTopBarRightAction = {
                        scope.launch { drawerState.open() }
                    },
                    composerHint = "Write a message",
                    bodyTop = bodyTopContent,
                    onSend = onSendMessage
                )
            }
        }
    }
}

internal fun buildGlobalChatDebugCard(
    showDebugDiagnostics: Boolean,
    transportSenderSourceLabel: String,
    globalMeshDiagnostics: GlobalMeshDiagnostics,
    lastIncomingMessageStatus: String?,
    lastConnectOnSendStatus: String?,
    lastGlobalMeshStatus: String?,
    meshDeliveryResult: GlobalMeshDeliveryResult?,
    queuedOutgoingCount: Int
): DebugInfoCardModel? {
    if (!showDebugDiagnostics) {
        return null
    }

    val meshStatusItems = buildList {
        add(DebugInfoItem("Reachable", globalMeshDiagnostics.reachablePeerCount.toString()))
        add(
            DebugInfoItem(
                "Active",
                globalMeshDiagnostics.activeTransportPeerId?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: "none"
            )
        )
        val transportValue = globalChatTransportSourceValue(transportSenderSourceLabel)
        if (transportValue != "Android") {
            add(DebugInfoItem("Transport", transportValue))
        }
    }
    val lastMeshEventItems = buildList {
        add(
            DebugInfoItem(
                "Send",
                globalChatDeliveryValue(meshDeliveryResult, lastGlobalMeshStatus)
            )
        )
        add(
            DebugInfoItem(
                "Incoming",
                globalChatIncomingValue(lastIncomingMessageStatus)
            )
        )
        globalChatConnectOnSendValue(lastConnectOnSendStatus)?.let { connectValue ->
            add(DebugInfoItem("Connect", connectValue))
        }
    }

    return DebugInfoCardModel(
        title = "Debug",
        sections = listOf(
            DebugInfoSection(
                title = "Mesh",
                items = meshStatusItems
            ),
            DebugInfoSection(
                title = "Events",
                items = lastMeshEventItems
            ),
            DebugInfoSection(
                title = "Queue",
                items = listOf(
                    DebugInfoItem("Pending", queuedOutgoingCount.toString())
                )
            )
        )
    )
}

internal fun globalChatMeshReachabilityText(
    diagnostics: GlobalMeshDiagnostics
): String {
    return "Reachable Aurora peers: ${diagnostics.reachablePeerCount}"
}

internal fun globalChatTransportRoutingText(
    transportSenderSourceLabel: String,
    activeTransportPeerId: String?
): String {
    val activePeerText = activeTransportPeerId?.trim()?.takeIf { it.isNotEmpty() } ?: "None"
    return "Transport sender: $transportSenderSourceLabel | Active transport peer: $activePeerText"
}

internal fun globalChatMeshLimitText(
    diagnostics: GlobalMeshDiagnostics
): String {
    return if (diagnostics.onlyOneActiveTransportPeerSupported) {
        "Only one active transport peer currently supported."
    } else {
        "Multiple active transport peers supported."
    }
}

internal fun globalChatTransportSourceValue(
    transportSenderSourceLabel: String
): String {
    val source = transportSenderSourceLabel.trim()
    return when {
        source.contains("android", ignoreCase = true) -> "Android"
        source.contains("noop", ignoreCase = true) -> "NoOp"
        source.isEmpty() -> "unavailable"
        else -> source
    }
}

internal fun globalChatConnectOnSendText(
    diagnostics: GlobalMeshDiagnostics,
    lastConnectOnSendStatus: String?
): String {
    if (!lastConnectOnSendStatus.isNullOrBlank()) {
        return lastConnectOnSendStatus
    }

    return when {
        !diagnostics.activeTransportPeerId.isNullOrBlank() ->
            "Public mesh connect-on-send: active peer already connected."

        diagnostics.reachablePeerCount > 0 ->
            "Public mesh connect-on-send: ready to try a reachable Aurora peer."

        else ->
            "Public mesh connect-on-send: waiting for a reachable Aurora peer."
    }
}

internal fun globalChatConnectOnSendValue(
    lastConnectOnSendStatus: String?
): String? {
    val status = lastConnectOnSendStatus?.trim() ?: return null
    return when {
        status.contains("succeeded", ignoreCase = true) -> "ok"
        status.contains("already connected", ignoreCase = true) -> "ok"
        status.contains("not needed", ignoreCase = true) -> "ok"
        status.contains("failed", ignoreCase = true) -> "failed"
        else -> null
    }
}

internal fun globalChatMeshStatusText(
    meshDeliveryResult: GlobalMeshDeliveryResult?
): String? {
    return when (meshDeliveryResult) {
        is GlobalMeshDeliveryResult.QueuedToActivePeer ->
            "Global mesh queued to active peer ${meshDeliveryResult.peerId}."

        GlobalMeshDeliveryResult.NoReachablePeers ->
            "No reachable Aurora peers."

        GlobalMeshDeliveryResult.SenderUnavailable ->
            "Mesh transport sender unavailable."

        is GlobalMeshDeliveryResult.ConnectOnSendFailed ->
            "Public mesh connect-on-send failed for ${meshDeliveryResult.peerId}: ${meshDeliveryResult.reason}"

        is GlobalMeshDeliveryResult.SkippedDuplicate ->
            "Global mesh relay skipped duplicate ${meshDeliveryResult.messageId}."

        is GlobalMeshDeliveryResult.SkippedSourcePeer ->
            "Global mesh relay skipped source peer ${meshDeliveryResult.peerId}."

        is GlobalMeshDeliveryResult.SkippedTtlExpired ->
            "Global mesh relay stopped at TTL for ${meshDeliveryResult.messageId}."

        is GlobalMeshDeliveryResult.Failed ->
            "Global mesh failed: ${meshDeliveryResult.reason}"

        null -> null
    }
}

internal fun globalChatDeliveryValue(
    meshDeliveryResult: GlobalMeshDeliveryResult?,
    lastGlobalMeshStatus: String?
): String {
    val status = lastGlobalMeshStatus?.trim()?.takeIf { it.isNotEmpty() }?.removeSuffix(".")
    return when (meshDeliveryResult) {
        is GlobalMeshDeliveryResult.QueuedToActivePeer -> meshDeliveryResult.peerId
        GlobalMeshDeliveryResult.NoReachablePeers -> "failed"
        GlobalMeshDeliveryResult.SenderUnavailable -> "failed"
        is GlobalMeshDeliveryResult.ConnectOnSendFailed -> "failed"
        is GlobalMeshDeliveryResult.SkippedDuplicate -> "skipped"
        is GlobalMeshDeliveryResult.SkippedSourcePeer -> "skipped"
        is GlobalMeshDeliveryResult.SkippedTtlExpired -> "skipped"
        is GlobalMeshDeliveryResult.Failed -> "failed"
        null -> when {
            status == null -> "none"
            status.contains("queued to active peer ", ignoreCase = true) ->
                status.substringAfterLast("queued to active peer ").trim()
            status.contains("failed", ignoreCase = true) -> "failed"
            status.contains("unavailable", ignoreCase = true) -> "failed"
            status.contains("no reachable", ignoreCase = true) -> "failed"
            status.contains("skipped", ignoreCase = true) -> "skipped"
            else -> status
        }
    }
}

internal fun globalChatIncomingValue(
    lastIncomingMessageStatus: String?
): String {
    val status = lastIncomingMessageStatus?.trim()?.takeIf { it.isNotEmpty() } ?: return "none"
    val fromIndex = status.indexOf(" from ", ignoreCase = true)
    return if (fromIndex >= 0) {
        status.substring(fromIndex + 6).removeSuffix(".").trim()
    } else {
        status.removeSuffix(".")
    }
}

internal fun globalChatTransportNote(
    desiredAvailability: AuroraAvailabilityPreference,
    queuedOutgoingCount: Int,
    meshDeliveryResult: GlobalMeshDeliveryResult?
): String? {
    require(queuedOutgoingCount >= 0) {
        "Queued outgoing count must not be negative."
    }

    return when {
        desiredAvailability == AuroraAvailabilityPreference.OFFLINE && queuedOutgoingCount > 0 -> {
            "Offline: messages stay on this device until mesh delivery is available."
        }
        desiredAvailability == AuroraAvailabilityPreference.OFFLINE -> {
            "Offline: messages stay on this device."
        }
        meshDeliveryResult is GlobalMeshDeliveryResult.QueuedToActivePeer -> {
            null
        }
        queuedOutgoingCount > 0 &&
            meshDeliveryResult == GlobalMeshDeliveryResult.NoReachablePeers -> {
            "Queued locally until an Aurora peer is reachable."
        }
        queuedOutgoingCount > 0 &&
            meshDeliveryResult == GlobalMeshDeliveryResult.SenderUnavailable -> {
            "Queued locally until public mesh transport is available."
        }
        queuedOutgoingCount > 0 &&
            meshDeliveryResult is GlobalMeshDeliveryResult.ConnectOnSendFailed -> {
            "Queued locally until public mesh connect-on-send succeeds."
        }
        queuedOutgoingCount > 0 &&
            meshDeliveryResult is GlobalMeshDeliveryResult.Failed -> {
            "Queued locally. Mesh delivery is not available right now."
        }
        queuedOutgoingCount > 0 -> {
            "Queued locally until mesh delivery is available."
        }
        meshDeliveryResult == GlobalMeshDeliveryResult.NoReachablePeers -> {
            "Saved locally. No reachable Aurora peers."
        }
        meshDeliveryResult == GlobalMeshDeliveryResult.SenderUnavailable -> {
            "Saved locally. Public mesh delivery is not available right now."
        }
        meshDeliveryResult is GlobalMeshDeliveryResult.ConnectOnSendFailed -> {
            "Saved locally. Public mesh connect-on-send is not available right now."
        }
        meshDeliveryResult is GlobalMeshDeliveryResult.Failed -> {
            "Saved locally. Mesh delivery is not available right now."
        }
        else -> null
    }
}
