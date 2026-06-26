package gr.hua.aurora.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gr.hua.aurora.model.AuroraContact
import gr.hua.aurora.model.ChatMessage
import gr.hua.aurora.protocol.PrivateChatMessageSendResult
import gr.hua.aurora.ui.components.AuroraTopBarAction
import gr.hua.aurora.ui.components.ChatComposer
import gr.hua.aurora.ui.components.MessageList
import gr.hua.aurora.ui.components.TransportStatusCard
import gr.hua.aurora.ui.components.TransportStatusTone
import gr.hua.aurora.ui.components.toMessageListItem

internal data class PrivateChatScreenContent(
    val title: String,
    val shortPeerId: String,
    val keyStatusText: String?,
    val setupText: String,
    val isMissingContact: Boolean,
    val isComposerEnabled: Boolean,
    val composerHint: String
) {
    val shouldShowComposer: Boolean
        get() = !isMissingContact
}

@Composable
fun PrivateChatScreen(
    requestedPeerId: String,
    contact: AuroraContact?,
    currentUsername: String,
    messages: List<ChatMessage>,
    lastDeliveryResult: PrivateChatMessageSendResult?,
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit,
    onResetLocalData: () -> Unit
) {
    val content = buildPrivateChatScreenContent(
        requestedPeerId = requestedPeerId,
        contact = contact
    )
    val mappedMessages = messages.map { it.toMessageListItem() }
    var composerValue by rememberSaveable(content.shortPeerId) {
        mutableStateOf("")
    }

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
            return@PlaceholderScreenScaffold
        }

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
            },
            note = lastDeliveryResult?.let(::privateChatDeliveryStatusText)
        )

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Messages",
                    style = MaterialTheme.typography.titleSmall
                )
                if (mappedMessages.isEmpty()) {
                    Text(
                        text = "No private messages yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    MessageList(
                        messages = mappedMessages,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 260.dp)
                    )
                }
            }
        }

        if (content.shouldShowComposer) {
            ChatComposer(
                value = composerValue,
                onValueChange = { composerValue = it },
                onSend = { typedText ->
                    onSendMessage(typedText)
                    composerValue = ""
                },
                hint = content.composerHint,
                sendLabel = "Send",
                enabled = content.isComposerEnabled
            )
        }
    }
}

internal fun buildPrivateChatScreenContent(
    requestedPeerId: String,
    contact: AuroraContact?
): PrivateChatScreenContent {
    val resolvedPeerId = contact?.canonicalPeerId ?: requestedPeerId
    return if (contact == null) {
        PrivateChatScreenContent(
            title = "Contact not found",
            shortPeerId = privateChatShortPeerId(resolvedPeerId),
            keyStatusText = null,
            setupText = "Open Nearby or Contacts and select a saved contact first.",
            isMissingContact = true,
            isComposerEnabled = false,
            composerHint = "Private messaging coming next"
        )
    } else {
        val hasReadyKeys = contact.hasSession
        PrivateChatScreenContent(
            title = contact.displayName,
            shortPeerId = privateChatShortPeerId(contact.canonicalPeerId),
            keyStatusText = privateChatKeyStatusText(contact),
            setupText = privateChatSetupText(contact),
            isMissingContact = false,
            isComposerEnabled = hasReadyKeys,
            composerHint = if (hasReadyKeys) {
                "Private message"
            } else {
                "Exchange keys from Nearby before sending private messages."
            }
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

internal fun privateChatDeliveryStatusText(
    result: PrivateChatMessageSendResult
): String {
    return when (result) {
        PrivateChatMessageSendResult.SubmittedLocally ->
            "Private message handed to local encrypted transport."
        PrivateChatMessageSendResult.KeysUnavailable ->
            "Keys unavailable."
        PrivateChatMessageSendResult.ContactUnavailable ->
            "Contact unavailable."
        PrivateChatMessageSendResult.ContactNotReachable ->
            "Contact not reachable."
        is PrivateChatMessageSendResult.Failed ->
            "Private send failed: ${result.reason}"
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
