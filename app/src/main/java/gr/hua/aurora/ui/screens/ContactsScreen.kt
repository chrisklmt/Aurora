package gr.hua.aurora.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsRunState
import gr.hua.aurora.diagnostics.automated.automatedDiagnosticsCompactSummaryText
import gr.hua.aurora.model.AuroraContact
import gr.hua.aurora.model.PrivateChatIdentity
import gr.hua.aurora.protocol.PeerSessionRegistryDiagnostics
import gr.hua.aurora.protocol.hasSessionForPeer
import gr.hua.aurora.state.AuroraAvailabilityPreference
import gr.hua.aurora.ui.components.AuroraAvailabilityIndicator
import gr.hua.aurora.ui.components.AuroraTopBarAction
import gr.hua.aurora.ui.components.CompactDiagnosticsCard
import gr.hua.aurora.ui.components.DebugInfoCard
import gr.hua.aurora.ui.components.DebugInfoCardModel
import gr.hua.aurora.ui.components.DebugInfoItem
import gr.hua.aurora.ui.components.DebugInfoSection
import gr.hua.aurora.ui.components.rememberAuroraAvailabilityUiState

internal data class ContactChatSummary(
    val peerId: String,
    val displayName: String,
    val isPrivateChatReady: Boolean,
    val hasPrivateChatSetup: Boolean,
    val visibilityText: String?
)

@Composable
fun ContactsScreen(
    contacts: List<AuroraContact>,
    privateChatIdentitiesByPeerId: Map<String, PrivateChatIdentity>,
    nearbyVisiblePeerIds: Set<String>,
    currentUsername: String,
    desiredAvailability: AuroraAvailabilityPreference,
    automatedDiagnosticsState: AutomatedDiagnosticsRunState,
    showDebugDiagnostics: Boolean,
    peerSessionDiagnostics: PeerSessionRegistryDiagnostics,
    lastIdentityExchangeStatus: String?,
    onOpenAutomatedDiagnostics: () -> Unit,
    onOpenChat: (String) -> Unit,
    onRenameChat: (String, String?) -> Unit,
    onDeleteChat: (String) -> Unit,
    onResetLocalData: () -> Unit,
    onBack: () -> Unit
) {
    val availabilityState = rememberAuroraAvailabilityUiState(desiredAvailability)
    val debugCard = buildContactsDebugCard(
        showDebugDiagnostics = showDebugDiagnostics,
        contacts = contacts,
        privateChatIdentitiesByPeerId = privateChatIdentitiesByPeerId,
        nearbyVisiblePeerIds = nearbyVisiblePeerIds,
        peerSessionDiagnostics = peerSessionDiagnostics,
        lastIdentityExchangeStatus = lastIdentityExchangeStatus
    )
    var managedPeerId by remember { mutableStateOf<String?>(null) }
    var renamePeerId by remember { mutableStateOf<String?>(null) }
    var deletePeerId by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var showAdvancedRawDiagnostics by remember { mutableStateOf(false) }

    PlaceholderScreenScaffold(
        title = "Contacts",
        subtitle = null,
        subtitleContent = {
            AuroraAvailabilityIndicator(uiState = availabilityState.uiState)
        },
        username = currentUsername,
        onUsernameTripleTap = onResetLocalData,
        rightAction = AuroraTopBarAction.BACK,
        onRightActionClick = onBack
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (contacts.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "No contacts yet.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Add peers from Nearby Devices first, then open private chats here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(contacts) { contact ->
                    val summary = buildContactChatSummary(
                        contact = contact,
                        identity = privateChatIdentitiesByPeerId[contact.canonicalPeerId],
                        hasRuntimeSession = peerSessionDiagnostics.hasSessionForPeer(
                            contact.canonicalPeerId
                        ),
                        isNearbyVisible = nearbyVisiblePeerIds.contains(contact.canonicalPeerId)
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = summary.displayName,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = contactsProductStatusText(
                                    isPrivateChatReady = summary.isPrivateChatReady,
                                    hasPrivateChatSetup = summary.hasPrivateChatSetup
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            summary.visibilityText?.let { visibilityText ->
                                Text(
                                    text = visibilityText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { onOpenChat(summary.peerId) }
                                ) {
                                    Text("Open chat")
                                }
                                TextButton(
                                    onClick = {
                                        managedPeerId = summary.peerId
                                    }
                                ) {
                                    Text("Manage")
                                }
                            }
                        }
                    }
                }
            }

            if (showDebugDiagnostics) {
                item {
                    CompactDiagnosticsCard(
                        summaryText = automatedDiagnosticsCompactSummaryText(
                            automatedDiagnosticsState
                        ),
                        onOpenAutomatedDiagnostics = onOpenAutomatedDiagnostics,
                        rawDiagnosticsExpanded = showAdvancedRawDiagnostics,
                        onToggleRawDiagnostics = if (debugCard != null) {
                            { showAdvancedRawDiagnostics = !showAdvancedRawDiagnostics }
                        } else {
                            null
                        },
                        supportingText = "Contacts remain first; detailed diagnostics stay collapsed."
                    )
                }
            }

            if (showDebugDiagnostics && showAdvancedRawDiagnostics) {
                debugCard?.let { card ->
                    item {
                        DebugInfoCard(card = card)
                    }
                }
            }
        }

        managedPeerId?.let { peerId ->
            val contact = contacts.firstOrNull { it.canonicalPeerId == peerId }
            val summary = contact?.let {
                buildContactChatSummary(
                    contact = it,
                    identity = privateChatIdentitiesByPeerId[peerId],
                    hasRuntimeSession = peerSessionDiagnostics.hasSessionForPeer(peerId),
                    isNearbyVisible = nearbyVisiblePeerIds.contains(peerId)
                )
            }
            if (summary != null) {
                AlertDialog(
                    onDismissRequest = { managedPeerId = null },
                    title = { Text(summary.displayName) },
                    text = {
                        Text(
                            text = if (summary.isPrivateChatReady) {
                                "Manage this private chat."
                            } else {
                                "Manage this contact and finish setup again when needed."
                            }
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                renameDraft = privateChatIdentitiesByPeerId[peerId]?.customChatName.orEmpty()
                                renamePeerId = peerId
                                managedPeerId = null
                            }
                        ) {
                            Text("Rename chat")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                deletePeerId = peerId
                                managedPeerId = null
                            }
                        ) {
                            Text("Delete chat")
                        }
                    }
                )
            }
        }

        renamePeerId?.let { peerId ->
            AlertDialog(
                onDismissRequest = { renamePeerId = null },
                title = { Text("Rename chat") },
                text = {
                    OutlinedTextField(
                        value = renameDraft,
                        onValueChange = { renameDraft = it },
                        label = { Text("Chat name") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onRenameChat(peerId, renameDraft)
                            renamePeerId = null
                        }
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { renamePeerId = null }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        deletePeerId?.let { peerId ->
            AlertDialog(
                onDismissRequest = { deletePeerId = null },
                title = { Text("Delete chat") },
                text = {
                    Text("This clears private history and setup for this contact.")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDeleteChat(peerId)
                            deletePeerId = null
                        }
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { deletePeerId = null }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

internal fun buildContactChatSummary(
    contact: AuroraContact,
    identity: PrivateChatIdentity?,
    hasRuntimeSession: Boolean,
    isNearbyVisible: Boolean
): ContactChatSummary {
    return ContactChatSummary(
        peerId = contact.canonicalPeerId,
        displayName = identity?.displayNameOrNull() ?: contact.displayName,
        isPrivateChatReady = hasRuntimeSession && identity?.isEstablished == true,
        hasPrivateChatSetup = identity != null,
        visibilityText = contactsVisibilityText(
            contact = contact,
            isNearbyVisible = isNearbyVisible
        )
    )
}

internal fun contactsProductStatusText(
    isPrivateChatReady: Boolean,
    hasPrivateChatSetup: Boolean
): String {
    return when {
        isPrivateChatReady -> "Private chat ready"
        hasPrivateChatSetup -> "Retry setup"
        else -> "Setup needed"
    }
}

internal fun contactsVisibilityText(
    contact: AuroraContact,
    isNearbyVisible: Boolean
): String? {
    return when {
        isNearbyVisible -> "Seen nearby"
        contact.lastSeenMillis != null -> "Not currently visible"
        else -> null
    }
}

internal fun buildContactsDebugCard(
    showDebugDiagnostics: Boolean,
    contacts: List<AuroraContact>,
    privateChatIdentitiesByPeerId: Map<String, PrivateChatIdentity>,
    nearbyVisiblePeerIds: Set<String>,
    peerSessionDiagnostics: PeerSessionRegistryDiagnostics,
    lastIdentityExchangeStatus: String?
): DebugInfoCardModel? {
    if (!showDebugDiagnostics) {
        return null
    }

    val readyCount = contacts.count { contact ->
        peerSessionDiagnostics.hasSessionForPeer(contact.canonicalPeerId) &&
            privateChatIdentitiesByPeerId[contact.canonicalPeerId]?.isEstablished == true
    }
    val seenCount = contacts.count { it.lastSeenMillis != null }
    val visibleCount = contacts.count { nearbyVisiblePeerIds.contains(it.canonicalPeerId) }
    val chatCount = privateChatIdentitiesByPeerId.size
    val peerListValue = contacts.joinToString(separator = ", ") { contact ->
        privateChatShortPeerId(contact.canonicalPeerId)
    }.ifBlank { "none" }

    val contactItems = buildList {
        add(DebugInfoItem("Count", contacts.size.toString()))
        add(DebugInfoItem("Ready", readyCount.toString()))
        add(DebugInfoItem("Chats", chatCount.toString()))
        add(DebugInfoItem("Visible", visibleCount.toString()))
        add(DebugInfoItem("Seen", seenCount.toString()))
        add(DebugInfoItem("Sessions", peerSessionDiagnostics.establishedPeerIds.size.toString()))
        add(
            DebugInfoItem(
                label = "Peers",
                value = peerListValue,
                preferFullWidth = true
            )
        )
        if (!lastIdentityExchangeStatus.isNullOrBlank()) {
            add(
                DebugInfoItem(
                    label = "Last exchange",
                    value = lastIdentityExchangeStatus,
                    preferFullWidth = true
                )
            )
        }
    }

    return DebugInfoCardModel(
        title = "Debug",
        sections = listOf(
            DebugInfoSection(
                title = "Contacts",
                items = contactItems
            )
        )
    )
}

internal fun contactChatPeerId(contact: AuroraContact): String {
    return contact.canonicalPeerId
}
