package gr.hua.aurora.ui.screens

import androidx.compose.runtime.Composable
import gr.hua.aurora.model.ChatMessage
import gr.hua.aurora.ui.components.AuroraTopBarAction
import gr.hua.aurora.ui.components.ChatScaffold
import gr.hua.aurora.ui.components.TransportStatusCard
import gr.hua.aurora.ui.components.TransportStatusTone
import gr.hua.aurora.ui.components.toMessageListItem

@Composable
fun PrivateChatScreen(
    peerId: String,
    peerDisplayName: String,
    currentUsername: String,
    messages: List<ChatMessage>,
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit,
    onResetLocalData: () -> Unit
) {
    val mappedMessages = messages.map { it.toMessageListItem() }

    ChatScaffold(
        title = "Private Chat",
        subtitle = "Peer: $peerDisplayName",
        messages = mappedMessages,
        topBarUsername = currentUsername,
        onTopBarUsernameTripleTap = onResetLocalData,
        topBarRightAction = AuroraTopBarAction.BACK,
        onTopBarRightAction = onBack,
        composerHint = "Reply in preview mode",
        bodyTop = {
            TransportStatusCard(
                summary = "Preview conversation",
                detail = "This screen shows local preview state without live peer or transport state.",
                tone = TransportStatusTone.HEALTHY,
                note = "Peer id: $peerId. Message flow and identity details will be added in later stages."
            )
        },
        onSend = onSendMessage
    )
}
