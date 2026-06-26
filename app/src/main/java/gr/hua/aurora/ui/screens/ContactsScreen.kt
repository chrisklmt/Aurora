package gr.hua.aurora.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import gr.hua.aurora.state.AuroraAvailabilityPreference
import gr.hua.aurora.ui.components.AuroraAvailabilityIndicator
import gr.hua.aurora.ui.components.AuroraTopBarAction
import gr.hua.aurora.ui.components.rememberAuroraAvailabilityUiState

@Composable
fun ContactsScreen(
    contacts: List<AuroraContact>,
    currentUsername: String,
    desiredAvailability: AuroraAvailabilityPreference,
    onOpenChat: (String) -> Unit,
    onResetLocalData: () -> Unit,
    onBack: () -> Unit
) {
    val availabilityState = rememberAuroraAvailabilityUiState(desiredAvailability)

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
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Contacts",
                style = MaterialTheme.typography.headlineSmall
            )

            if (contacts.isEmpty()) {
                Text(
                    text = "No contacts yet.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Add peers from Nearby Devices first, then open private chats here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
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
                                    text = contactsKeyStatusText(contact),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                contact.lastSeenMillis?.let {
                                    Text(
                                        text = "Seen recently",
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
}

internal fun contactsKeyStatusText(contact: AuroraContact): String {
    return if (contact.hasSession) {
        "Keys ready"
    } else {
        "Keys missing"
    }
}

internal fun contactChatPeerId(contact: AuroraContact): String {
    return contact.canonicalPeerId
}
