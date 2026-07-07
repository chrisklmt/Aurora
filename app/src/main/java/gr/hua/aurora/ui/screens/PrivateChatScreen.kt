package gr.hua.aurora.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import gr.hua.aurora.model.AuroraContact
import gr.hua.aurora.model.ChatMessage
import gr.hua.aurora.model.PrivateChatIdentity
import gr.hua.aurora.protocol.PeerSessionRegistryDiagnostics
import gr.hua.aurora.protocol.PrivateChatMessageSendResult
import gr.hua.aurora.ui.debug.wifidirect.WifiDirectPrivateDebugDiagnostics
import gr.hua.aurora.ui.debug.wifidirect.buildPrivateChatWifiDirectDebugSection
import gr.hua.aurora.ui.debug.wifidirect.buildPrivateChatWifiDirectDetailsSection
import gr.hua.aurora.ui.debug.wifidirect.privateChatDebugDetailsToggleLabel
import gr.hua.aurora.ui.debug.wifidirect.privateChatWifiDirectDebugDiagnostics
import gr.hua.aurora.ui.components.AuroraTopBarAction
import gr.hua.aurora.ui.components.ChatScaffold
import gr.hua.aurora.ui.components.DebugInfoCard
import gr.hua.aurora.ui.components.DebugInfoCardModel
import gr.hua.aurora.ui.components.DebugInfoItem
import gr.hua.aurora.ui.components.DebugInfoSection
import gr.hua.aurora.ui.components.toMessageListItem
import gr.hua.aurora.wifidirect.frame.WifiDirectTransportAdapterDiagnostics
import gr.hua.aurora.wifidirect.debug.WifiDirectPrivateDebugSendDiagnostics
import gr.hua.aurora.wifidirect.debug.WifiDirectReceiveBridgeDiagnostics
import gr.hua.aurora.wifidirect.debug.WifiDirectSendBridgeDiagnostics
import gr.hua.aurora.wifidirect.runtime.WifiDirectRuntimeStatus
import gr.hua.aurora.wifidirect.socket.WifiDirectSocketDiagnostics

internal data class PrivateChatScreenContent(
    val title: String,
    val shortPeerId: String,
    val statusText: String?,
    val helperText: String?,
    val isMissingContact: Boolean,
    val isComposerEnabled: Boolean,
    val composerHint: String,
    val emptyStateText: String
)

@Composable
internal fun PrivateChatScreen(
    requestedPeerId: String,
    contact: AuroraContact?,
    privateChatIdentity: PrivateChatIdentity?,
    hasRuntimeSession: Boolean,
    isNearbyVisible: Boolean,
    currentUsername: String,
    messages: List<ChatMessage>,
    lastDeliveryResult: PrivateChatMessageSendResult?,
    showDebugDiagnostics: Boolean,
    peerSessionDiagnostics: PeerSessionRegistryDiagnostics,
    activeTransportPeerId: String?,
    lastIdentityExchangeStatus: String?,
    wifiDirectRuntimeStatus: WifiDirectRuntimeStatus,
    wifiDirectSocketDiagnostics: WifiDirectSocketDiagnostics,
    wifiDirectAdapterDiagnostics: WifiDirectTransportAdapterDiagnostics,
    wifiDirectSendBridgeDiagnostics: WifiDirectSendBridgeDiagnostics,
    wifiDirectPrivateDebugSendDiagnostics: WifiDirectPrivateDebugSendDiagnostics,
    wifiDirectReceiveBridgeDiagnostics: WifiDirectReceiveBridgeDiagnostics,
    onSetPrivateWifiDirectDebugSendEnabled: (Boolean) -> Unit,
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit,
    onRetryMessage: (String) -> Unit,
    onResetLocalData: () -> Unit
) {
    val content = buildPrivateChatScreenContent(
        requestedPeerId = requestedPeerId,
        contact = contact,
        privateChatIdentity = privateChatIdentity,
        hasRuntimeSession = hasRuntimeSession,
        isNearbyVisible = isNearbyVisible
    )
    var showPrivateDebugDetails by remember {
        mutableStateOf(false)
    }
    val mappedMessages = messages.map { it.toMessageListItem(showRetryAction = true) }
    val wifiDirectDebugDiagnostics = privateChatWifiDirectDebugDiagnostics(
        contact = contact,
        privateChatIdentity = privateChatIdentity,
        hasRuntimeSession = hasRuntimeSession,
        runtimeStatus = wifiDirectRuntimeStatus,
        socketDiagnostics = wifiDirectSocketDiagnostics,
        adapterDiagnostics = wifiDirectAdapterDiagnostics,
        sendBridgeDiagnostics = wifiDirectSendBridgeDiagnostics,
        privateDebugSendDiagnostics = wifiDirectPrivateDebugSendDiagnostics,
        receiveBridgeDiagnostics = wifiDirectReceiveBridgeDiagnostics
    )
    val debugCard = buildPrivateChatDebugCard(
        showDebugDiagnostics = showDebugDiagnostics,
        requestedPeerId = requestedPeerId,
        contact = contact,
        privateChatIdentity = privateChatIdentity,
        hasRuntimeSession = hasRuntimeSession,
        isNearbyVisible = isNearbyVisible,
        messages = messages,
        lastDeliveryResult = lastDeliveryResult,
        peerSessionDiagnostics = peerSessionDiagnostics,
        activeTransportPeerId = activeTransportPeerId,
        lastIdentityExchangeStatus = lastIdentityExchangeStatus,
        wifiDirectDiagnostics = wifiDirectDebugDiagnostics,
        isComposerEnabled = content.isComposerEnabled
    )
    val debugDetailsCard = buildPrivateChatDebugDetailsCard(
        showDebugDiagnostics = showDebugDiagnostics,
        requestedPeerId = requestedPeerId,
        contact = contact,
        privateChatIdentity = privateChatIdentity,
        hasRuntimeSession = hasRuntimeSession,
        isNearbyVisible = isNearbyVisible,
        messages = messages,
        lastDeliveryResult = lastDeliveryResult,
        peerSessionDiagnostics = peerSessionDiagnostics,
        activeTransportPeerId = activeTransportPeerId,
        lastIdentityExchangeStatus = lastIdentityExchangeStatus,
        wifiDirectDiagnostics = wifiDirectDebugDiagnostics,
        isComposerEnabled = content.isComposerEnabled
    )
    val bodyTopContent: (@Composable ColumnScope.() -> Unit)? = when {
        content.statusText != null || content.helperText != null || debugCard != null -> {
            {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    content.statusText?.let { statusText ->
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    content.helperText?.let { helperText ->
                        Text(
                            text = helperText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    PrivateChatWifiDirectDebugControls(
                        showDebugDiagnostics = showDebugDiagnostics,
                        privateDebugSendEnabled = wifiDirectPrivateDebugSendDiagnostics.enabled,
                        onSetEnabled = onSetPrivateWifiDirectDebugSendEnabled
                    )
                    debugCard?.let { card ->
                        DebugInfoCard(card = card)
                    }
                    debugDetailsCard?.let { detailsCard ->
                        TextButton(
                            onClick = {
                                showPrivateDebugDetails = !showPrivateDebugDetails
                            }
                        ) {
                            Text(privateChatDebugDetailsToggleLabel(showPrivateDebugDetails))
                        }
                        if (showPrivateDebugDetails) {
                            DebugInfoCard(card = detailsCard)
                        }
                    }
                }
            }
        }
        else -> null
    }

    if (content.isMissingContact) {
        PlaceholderScreenScaffold(
            title = "Private Chat",
            subtitle = null,
            username = currentUsername,
            onUsernameTripleTap = onResetLocalData,
            rightAction = AuroraTopBarAction.BACK,
            onRightActionClick = onBack
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = content.title,
                    style = MaterialTheme.typography.titleMedium
                )
                content.helperText?.let { helperText ->
                    Text(
                        text = helperText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                PrivateChatWifiDirectDebugControls(
                    showDebugDiagnostics = showDebugDiagnostics,
                    privateDebugSendEnabled = wifiDirectPrivateDebugSendDiagnostics.enabled,
                    onSetEnabled = onSetPrivateWifiDirectDebugSendEnabled
                )
                debugCard?.let { card ->
                    DebugInfoCard(card = card)
                }
                debugDetailsCard?.let { detailsCard ->
                    TextButton(
                        onClick = {
                            showPrivateDebugDetails = !showPrivateDebugDetails
                        }
                    ) {
                        Text(privateChatDebugDetailsToggleLabel(showPrivateDebugDetails))
                    }
                    if (showPrivateDebugDetails) {
                        DebugInfoCard(card = detailsCard)
                    }
                }
            }
        }
        return
    }

    ChatScaffold(
        title = "Private Chat",
        subtitle = content.title,
        messages = mappedMessages,
        onSend = onSendMessage,
        topBarUsername = currentUsername,
        onTopBarUsernameTripleTap = onResetLocalData,
        topBarRightAction = AuroraTopBarAction.BACK,
        onTopBarRightAction = onBack,
        composerHint = content.composerHint,
        composerEnabled = content.isComposerEnabled,
        composerStateKey = contact?.canonicalPeerId ?: requestedPeerId,
        emptyStateText = content.emptyStateText,
        bodyTop = bodyTopContent,
        onRetryMessage = onRetryMessage
    )
}

internal fun buildPrivateChatScreenContent(
    requestedPeerId: String,
    contact: AuroraContact?,
    privateChatIdentity: PrivateChatIdentity?,
    hasRuntimeSession: Boolean,
    isNearbyVisible: Boolean
): PrivateChatScreenContent {
    val resolvedPeerId = contact?.canonicalPeerId ?: requestedPeerId
    if (contact == null) {
        return PrivateChatScreenContent(
            title = "Contact not found",
            shortPeerId = privateChatShortPeerId(resolvedPeerId),
            statusText = "Contact not found",
            helperText = "Add this device from Nearby to start a private chat.",
            isMissingContact = true,
            isComposerEnabled = false,
            composerHint = "Private messaging coming next",
            emptyStateText = "Contact not found"
        )
    }

    val hasPrivateChatSetup = privateChatIdentity != null
    val hasReadyPrivateChat = hasRuntimeSession && privateChatIdentity?.isEstablished == true
    val title = privateChatIdentity?.displayNameOrNull() ?: contact.displayName
    val visibilityText = when {
        isNearbyVisible -> "Seen nearby"
        contact.lastSeenMillis != null -> "Not currently visible"
        else -> null
    }
    val helperText = when {
        hasReadyPrivateChat -> visibilityText
        !hasPrivateChatSetup -> "Add this device from Nearby to start a private chat."
        isNearbyVisible -> "Seen nearby. Open Nearby to finish private chat setup."
        else -> "Not currently visible. Open Nearby to finish private chat setup."
    }
    return PrivateChatScreenContent(
        title = title,
        shortPeerId = privateChatShortPeerId(contact.canonicalPeerId),
        statusText = when {
            hasReadyPrivateChat -> null
            hasPrivateChatSetup -> "Retry setup"
            else -> "Setup needed"
        },
        helperText = helperText,
        isMissingContact = false,
        isComposerEnabled = hasReadyPrivateChat,
        composerHint = if (hasReadyPrivateChat) {
            "Private message"
        } else {
            "Setup needed before sending"
        },
        emptyStateText = "No private messages yet."
    )
}

internal fun buildPrivateChatDebugCard(
    showDebugDiagnostics: Boolean,
    requestedPeerId: String,
    contact: AuroraContact?,
    privateChatIdentity: PrivateChatIdentity?,
    hasRuntimeSession: Boolean,
    isNearbyVisible: Boolean,
    messages: List<ChatMessage>,
    lastDeliveryResult: PrivateChatMessageSendResult?,
    peerSessionDiagnostics: PeerSessionRegistryDiagnostics,
    activeTransportPeerId: String?,
    lastIdentityExchangeStatus: String?,
    wifiDirectDiagnostics: WifiDirectPrivateDebugDiagnostics,
    isComposerEnabled: Boolean
): DebugInfoCardModel? {
    if (!showDebugDiagnostics) {
        return null
    }

    return DebugInfoCardModel(
        title = "Debug",
        sections = listOf(
            buildPrivateChatWifiDirectDebugSection(
                diagnostics = wifiDirectDiagnostics
            ),
            DebugInfoSection(
                title = "Events",
                items = listOf(
                    DebugInfoItem(
                        "Last send",
                        privateChatDebugDeliveryValue(lastDeliveryResult)
                    )
                )
            )
        )
    )
}

internal fun buildPrivateChatDebugDetailsCard(
    showDebugDiagnostics: Boolean,
    requestedPeerId: String,
    contact: AuroraContact?,
    privateChatIdentity: PrivateChatIdentity?,
    hasRuntimeSession: Boolean,
    isNearbyVisible: Boolean,
    messages: List<ChatMessage>,
    lastDeliveryResult: PrivateChatMessageSendResult?,
    peerSessionDiagnostics: PeerSessionRegistryDiagnostics,
    activeTransportPeerId: String?,
    lastIdentityExchangeStatus: String?,
    wifiDirectDiagnostics: WifiDirectPrivateDebugDiagnostics,
    isComposerEnabled: Boolean
): DebugInfoCardModel? {
    if (!showDebugDiagnostics) {
        return null
    }

    val targetPeerId = (contact?.canonicalPeerId ?: requestedPeerId).trim()
    val canonicalPeerId = privateChatCanonicalPeerId(
        peerId = targetPeerId,
        diagnostics = peerSessionDiagnostics
    )
    val eventItems = buildList {
        add(
            DebugInfoItem(
                "Last send",
                privateChatDebugDeliveryValue(lastDeliveryResult)
            )
        )
        if (!lastIdentityExchangeStatus.isNullOrBlank()) {
            add(
                DebugInfoItem(
                    "Last identity",
                    lastIdentityExchangeStatus,
                    preferFullWidth = true
                )
            )
        }
    }

    return DebugInfoCardModel(
        title = "Private debug details",
        sections = listOf(
            DebugInfoSection(
                title = "Target",
                items = buildList {
                    add(DebugInfoItem("Peer", privateChatShortPeerId(targetPeerId)))
                    add(
                        DebugInfoItem(
                            "Chat",
                            if (privateChatIdentity?.isEstablished == true) "ready" else "missing"
                        )
                    )
                    add(
                        DebugInfoItem(
                            "Local prop",
                            privateChatDebugIdentifierValue(privateChatIdentity?.localProposalId)
                        )
                    )
                    add(
                        DebugInfoItem(
                            "Remote prop",
                            privateChatDebugIdentifierValue(privateChatIdentity?.remoteProposalId)
                        )
                    )
                    add(
                        DebugInfoItem(
                            "Chat id",
                            privateChatDebugIdentifierValue(privateChatIdentity?.privateChatId)
                        )
                    )
                    add(
                        DebugInfoItem(
                            "Visible",
                            privateChatVisibilityDebugValue(
                                isNearbyVisible = isNearbyVisible,
                                lastSeenMillis = contact?.lastSeenMillis
                            )
                        )
                    )
                    add(DebugInfoItem("Messages", messages.size.toString()))
                    privateChatIdentity?.customChatName?.let { customName ->
                        add(
                            DebugInfoItem(
                                "Custom",
                                customName,
                                preferFullWidth = true
                            )
                        )
                    }
                }
            ),
            DebugInfoSection(
                title = "Runtime",
                items = listOf(
                    DebugInfoItem("Session", if (hasRuntimeSession) "ready" else "missing"),
                    DebugInfoItem(
                        "Active",
                        privateChatActivePeerValue(
                            activeTransportPeerId = activeTransportPeerId,
                            targetPeerId = canonicalPeerId ?: targetPeerId
                        )
                    ),
                    DebugInfoItem(
                        "Composer",
                        if (isComposerEnabled) "enabled" else "blocked"
                    ),
                    DebugInfoItem(
                        "Keys",
                        privateChatDebugKeyStatusText(hasRuntimeSession)
                    )
                )
            ),
            buildPrivateChatWifiDirectDetailsSection(
                diagnostics = wifiDirectDiagnostics
            ),
            DebugInfoSection(
                title = "Events",
                items = eventItems
            )
        )
    )
}

@Composable
private fun PrivateChatWifiDirectDebugControls(
    showDebugDiagnostics: Boolean,
    privateDebugSendEnabled: Boolean,
    onSetEnabled: (Boolean) -> Unit
) {
    if (!showDebugDiagnostics) {
        return
    }

    Row {
        TextButton(
            onClick = {
                onSetEnabled(!privateDebugSendEnabled)
            }
        ) {
            Text(
                text = privateChatWifiDirectDebugToggleLabel(privateDebugSendEnabled)
            )
        }
    }
}

internal fun privateChatWifiDirectDebugToggleLabel(
    privateDebugSendEnabled: Boolean
): String {
    return if (privateDebugSendEnabled) {
        "Disable Private Wi-Fi Direct send"
    } else {
        "Enable Private Wi-Fi Direct send"
    }
}

internal fun privateChatDebugKeyStatusText(hasRuntimeSession: Boolean): String {
    return if (hasRuntimeSession) {
        "ready"
    } else {
        "missing"
    }
}

internal fun privateChatDebugDeliveryValue(
    result: PrivateChatMessageSendResult?
): String {
    return when (result) {
        null -> "none"
        PrivateChatMessageSendResult.SubmittedLocally -> "queued"
        PrivateChatMessageSendResult.KeysUnavailable -> "setup needed"
        PrivateChatMessageSendResult.ContactUnavailable -> "contact missing"
        PrivateChatMessageSendResult.ContactNotReachable -> "not reachable"
        is PrivateChatMessageSendResult.Failed -> "failed"
    }
}

internal fun privateChatCanonicalPeerId(
    peerId: String,
    diagnostics: PeerSessionRegistryDiagnostics
): String? {
    val sanitizedPeerId = peerId.trim().takeIf { it.isNotEmpty() } ?: return null
    return when {
        diagnostics.establishedPeerIds.contains(sanitizedPeerId) -> sanitizedPeerId
        else -> diagnostics.canonicalPeerIdByAlias[sanitizedPeerId]
    }
}

internal fun privateChatActivePeerValue(
    activeTransportPeerId: String?,
    targetPeerId: String
): String {
    val sanitizedActivePeerId = activeTransportPeerId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return "none"
    return if (sanitizedActivePeerId == targetPeerId) {
        "match"
    } else {
        privateChatShortPeerId(sanitizedActivePeerId)
    }
}

internal fun privateChatDebugIdentifierValue(
    value: String?
): String {
    val sanitizedValue = value?.trim()?.takeIf { it.isNotEmpty() } ?: return "missing"
    return privateChatShortPeerId(sanitizedValue)
}

internal fun privateChatVisibilityDebugValue(
    isNearbyVisible: Boolean,
    lastSeenMillis: Long?
): String {
    return when {
        isNearbyVisible -> "nearby"
        lastSeenMillis != null -> "not visible"
        else -> "unknown"
    }
}

internal fun privateChatShortPeerId(peerId: String): String {
    val sanitizedPeerId = peerId.trim()
    require(sanitizedPeerId.isNotEmpty()) {
        "Private chat peer id must not be blank."
    }

    return if (sanitizedPeerId.length <= 12) {
        sanitizedPeerId
    } else {
        "${sanitizedPeerId.take(12)}..."
    }
}
