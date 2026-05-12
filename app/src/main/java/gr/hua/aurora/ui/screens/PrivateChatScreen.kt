package gr.hua.aurora.ui.screens

import androidx.compose.runtime.Composable
import gr.hua.aurora.ui.components.AuroraTopBarAction
import gr.hua.aurora.ui.components.ChatScaffold
import gr.hua.aurora.ui.components.MessageListItem
import gr.hua.aurora.ui.components.TransportStatusCard
import gr.hua.aurora.ui.components.TransportStatusTone

@Composable
fun PrivateChatScreen(
    peerId: String,
    onBack: () -> Unit
) {
    val previewMessages = listOf(
        MessageListItem(
            sender = peerId,
            text = "This private conversation is currently rendered as a reusable UI preview.",
            timestampLabel = "11:03"
        ),
        MessageListItem(
            sender = "You",
            text = "The screen layout is ready before message state and transport are added.",
            timestampLabel = "11:05",
            supportingLabel = "UI preview"
        )
    )

    ChatScaffold(
        title = "Private Chat",
        subtitle = "Peer: $peerId",
        messages = previewMessages,
        localUsername = "You",
        topBarUsername = "You",
        topBarRightAction = AuroraTopBarAction.BACK,
        onTopBarRightAction = onBack,
        composerHint = "Reply in preview mode",
        bodyTop = {
            TransportStatusCard(
                summary = "Preview conversation",
                detail = "This screen shows reusable chat UI without live peer or transport state.",
                tone = TransportStatusTone.HEALTHY,
                note = "Message flow and identity details will be added in later stages."
            )
        },
        onSend = {
            // Το send μένει σκόπιμα no-op μέχρι να προστεθεί πραγματική λογική συνομιλίας.
        }
    )
}
