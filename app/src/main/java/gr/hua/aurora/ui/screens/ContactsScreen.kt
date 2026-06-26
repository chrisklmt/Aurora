package gr.hua.aurora.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gr.hua.aurora.model.AuroraContact
import gr.hua.aurora.protocol.PeerSessionRegistryDiagnostics
import gr.hua.aurora.state.AuroraAvailabilityPreference
import gr.hua.aurora.ui.components.AuroraAvailabilityIndicator
import gr.hua.aurora.ui.components.AuroraTopBarAction
import gr.hua.aurora.ui.components.DebugInfoCard
import gr.hua.aurora.ui.components.DebugInfoCardModel
import gr.hua.aurora.ui.components.DebugInfoItem
import gr.hua.aurora.ui.components.DebugInfoSection
import gr.hua.aurora.ui.components.rememberAuroraAvailabilityUiState

@Composable
fun ContactsScreen(
    contacts: List<AuroraContact>,
    currentUsername: String,
    desiredAvailability: AuroraAvailabilityPreference,
    showDebugDiagnostics: Boolean,
    peerSessionDiagnostics: PeerSessionRegistryDiagnostics,
    lastIdentityExchangeStatus: String?,
    onOpenChat: (String) -> Unit,
    onResetLocalData: () -> Unit,
    onBack: () -> Unit
) {
    val availabilityState = rememberAuroraAvailabilityUiState(desiredAvailability)
    val debugCard = buildContactsDebugCard(
        showDebugDiagnostics = showDebugDiagnostics,
        contacts = contacts,
        peerSessionDiagnostics = peerSessionDiagnostics,
        lastIdentityExchangeStatus = lastIdentityExchangeStatus
    )

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
            debugCard?.let { card ->
                item {
                    DebugInfoCard(card = card)
                }
            }

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
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = contact.displayName,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = contactsProductStatusText(contact),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            contactsSeenStatusText(contact)?.let { seenText ->
                                Text(
                                    text = seenText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = { onOpenChat(contactChatPeerId(contact)) }
                            ) {
                                Text("Open chat")
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun contactsProductStatusText(contact: AuroraContact): String {
    return if (contact.hasSession) {
        "Private chat ready"
    } else {
        "Setup needed"
    }
}

internal fun contactsSeenStatusText(contact: AuroraContact): String? {
    return if (contact.lastSeenMillis != null) {
        "Seen recently"
    } else {
        null
    }
}

internal fun buildContactsDebugCard(
    showDebugDiagnostics: Boolean,
    contacts: List<AuroraContact>,
    peerSessionDiagnostics: PeerSessionRegistryDiagnostics,
    lastIdentityExchangeStatus: String?
): DebugInfoCardModel? {
    if (!showDebugDiagnostics) {
        return null
    }

    val readyCount = contacts.count { it.hasSession }
    val seenCount = contacts.count { it.lastSeenMillis != null }
    val peerListValue = contacts.joinToString(separator = ", ") { contact ->
        privateChatShortPeerId(contact.canonicalPeerId)
    }.ifBlank { "none" }

    val contactItems = buildList {
        add(DebugInfoItem("Count", contacts.size.toString()))
        add(DebugInfoItem("Ready", readyCount.toString()))
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
