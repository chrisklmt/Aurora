package gr.hua.aurora.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gr.hua.aurora.model.AuroraContact
import gr.hua.aurora.ui.components.AuroraTopBarAction
import gr.hua.aurora.ui.components.ChatComposer
import gr.hua.aurora.ui.components.TransportStatusCard
import gr.hua.aurora.ui.components.TransportStatusTone

internal data class PrivateChatPlaceholderContent(
    val title: String,
    val shortPeerId: String,
    val keyStatusText: String?,
    val setupText: String,
    val isMissingContact: Boolean,
    val isComposerEnabled: Boolean
)

@Composable
fun PrivateChatScreen(
    requestedPeerId: String,
    contact: AuroraContact?,
    currentUsername: String,
    onBack: () -> Unit,
    onResetLocalData: () -> Unit
) {
    val content = buildPrivateChatPlaceholderContent(
        requestedPeerId = requestedPeerId,
        contact = contact
    )

    PlaceholderScreenScaffold(
        title = "Private Chat",
        subtitle = content.title,
        username = currentUsername,
        onUsernameTripleTap = onResetLocalData,
        rightAction = AuroraTopBarAction.BACK,
        onRightActionClick = onBack
    ) {
        if (content.isMissingContact) {
            TransportStatusCard(
                summary = "Contact not found",
                detail = content.setupText,
                tone = TransportStatusTone.WARNING,
                note = "Peer: ${content.shortPeerId}"
            )
        } else {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = content.title,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Peer: ${content.shortPeerId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = requireNotNull(content.keyStatusText),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            TransportStatusCard(
                summary = requireNotNull(content.keyStatusText),
                detail = content.setupText,
                tone = if (contact?.hasSession == true) {
                    TransportStatusTone.HEALTHY
                } else {
                    TransportStatusTone.WARNING
                }
            )

            ChatComposer(
                value = "",
                onValueChange = { },
                onSend = { },
                hint = "Private messaging coming next",
                sendLabel = "Send",
                enabled = content.isComposerEnabled
            )
        }
    }
}

internal fun buildPrivateChatPlaceholderContent(
    requestedPeerId: String,
    contact: AuroraContact?
): PrivateChatPlaceholderContent {
    val resolvedPeerId = contact?.canonicalPeerId ?: requestedPeerId
    return if (contact == null) {
        PrivateChatPlaceholderContent(
            title = "Contact not found",
            shortPeerId = privateChatShortPeerId(resolvedPeerId),
            keyStatusText = null,
            setupText = "Open Nearby or Contacts and select a saved contact first.",
            isMissingContact = true,
            isComposerEnabled = false
        )
    } else {
        PrivateChatPlaceholderContent(
            title = contact.displayName,
            shortPeerId = privateChatShortPeerId(contact.canonicalPeerId),
            keyStatusText = privateChatKeyStatusText(contact),
            setupText = privateChatSetupText(contact),
            isMissingContact = false,
            isComposerEnabled = false
        )
    }
}

internal fun privateChatKeyStatusText(contact: AuroraContact): String {
    return if (contact.hasSession) {
        "Keys ready"
    } else {
        "Keys missing"
    }
}

internal fun privateChatSetupText(contact: AuroraContact): String {
    return if (contact.hasSession) {
        "Private chat setup is ready."
    } else {
        "Exchange keys from Nearby before sending private messages."
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
