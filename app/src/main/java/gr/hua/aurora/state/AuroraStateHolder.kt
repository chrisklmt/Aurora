package gr.hua.aurora.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import gr.hua.aurora.model.ChatMessage
import gr.hua.aurora.model.MessageStatus

class AuroraStateHolder(
    initialState: AuroraUiState
) {
    var uiState by mutableStateOf(initialState)
        private set

    // Οι helpers ενημερώνουν μόνο την τοπική Compose μνήμη ώστε το UI να δείχνει συνεκτικό,
    // χωρίς αποθήκευση, δικτύωση ή άλλη business orchestration.
    fun sendGlobalPreviewMessage(text: String) {
        val sanitizedText = text.trim()
        if (sanitizedText.isEmpty()) return

        uiState = AuroraUiState(
            contacts = uiState.contacts,
            globalMessages = uiState.globalMessages + createOutgoingMessage(
                threadId = "global",
                text = sanitizedText
            ),
            privateMessagesByPeerId = uiState.privateMessagesByPeerId,
            currentUsername = uiState.currentUsername
        )
    }

    fun sendPrivatePreviewMessage(peerId: String, text: String) {
        val sanitizedText = text.trim()
        if (sanitizedText.isEmpty()) return

        val updatedMessages = privateMessagesForPeerId(peerId) + createOutgoingMessage(
            threadId = "private:$peerId",
            text = sanitizedText
        )

        uiState = AuroraUiState(
            contacts = uiState.contacts,
            globalMessages = uiState.globalMessages,
            privateMessagesByPeerId = uiState.privateMessagesByPeerId + (peerId to updatedMessages),
            currentUsername = uiState.currentUsername
        )
    }

    fun updateUsername(username: String) {
        val sanitizedUsername = username.trim()
        if (sanitizedUsername.isEmpty()) return

        uiState = AuroraUiState(
            contacts = uiState.contacts,
            globalMessages = uiState.globalMessages,
            privateMessagesByPeerId = uiState.privateMessagesByPeerId,
            currentUsername = sanitizedUsername
        )
    }

    fun privateMessagesForPeerId(peerId: String): List<ChatMessage> {
        return uiState.privateMessagesByPeerId[peerId].orEmpty()
    }

    fun displayNameForPeerId(peerId: String): String {
        return uiState.contacts.firstOrNull { it.id == peerId }?.displayName ?: peerId
    }

    private fun createOutgoingMessage(
        threadId: String,
        text: String
    ): ChatMessage {
        val now = System.currentTimeMillis()

        return ChatMessage(
            id = "$threadId-$now",
            threadId = threadId,
            senderId = "self",
            senderName = uiState.currentUsername,
            text = text,
            createdAtMillis = now,
            status = MessageStatus.SENT,
            isOutgoing = true
        )
    }
}

@Composable
fun rememberAuroraStateHolder(): AuroraStateHolder {
    return remember { AuroraStateHolder(SampleAuroraState.create()) }
}
