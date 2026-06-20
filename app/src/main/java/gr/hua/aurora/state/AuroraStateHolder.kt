package gr.hua.aurora.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import gr.hua.aurora.data.GeneratedUsername
import gr.hua.aurora.data.LocalProfileSettings
import gr.hua.aurora.data.LocalProfileSettingsStore
import gr.hua.aurora.data.LocalProfileStore
import gr.hua.aurora.model.ChatMessage
import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.model.OutgoingChatMessage
import gr.hua.aurora.protocol.OutgoingMessageFrameBuilder
import gr.hua.aurora.protocol.OutgoingMessageFrameDraft

class AuroraStateHolder(
    initialState: AuroraUiState,
    private val localProfileStore: LocalProfileSettingsStore
) {
    var uiState by mutableStateOf(initialState)
        private set

    // Οι helpers ενημερώνουν μόνο την τοπική Compose μνήμη ώστε το UI να δείχνει συνεκτικό,
    // χωρίς αποθήκευση μηνυμάτων, δικτύωση ή άλλη business orchestration.
    fun sendGlobalPreviewMessage(text: String) {
        val sanitizedText = text.trim()
        if (sanitizedText.isEmpty()) return
        val outgoingMessage = createOutgoingMessage(
            threadId = "global",
            senderName = uiState.globalChatUsername,
            text = sanitizedText
        )

        uiState = AuroraUiState(
            contacts = uiState.contacts,
            nearbyDevices = uiState.nearbyDevices,
            globalMessages = uiState.globalMessages + outgoingMessage,
            pendingOutgoingMessages = uiState.pendingOutgoingMessages + createQueuedOutgoingChatMessage(
                message = outgoingMessage
            ),
            privateMessagesByPeerId = uiState.privateMessagesByPeerId,
            generatedUsername = uiState.generatedUsername,
            customUsername = uiState.customUsername,
            useCustomUsernameInGlobalChat = uiState.useCustomUsernameInGlobalChat,
            desiredAvailability = uiState.desiredAvailability
        )
    }

    fun sendPrivatePreviewMessage(peerId: String, text: String) {
        val sanitizedText = text.trim()
        if (sanitizedText.isEmpty()) return

        val updatedMessages = privateMessagesForPeerId(peerId) + createOutgoingMessage(
            threadId = "private:$peerId",
            senderName = uiState.privateProfileUsername,
            text = sanitizedText
        )

        uiState = AuroraUiState(
            contacts = uiState.contacts,
            nearbyDevices = uiState.nearbyDevices,
            globalMessages = uiState.globalMessages,
            pendingOutgoingMessages = uiState.pendingOutgoingMessages,
            privateMessagesByPeerId = uiState.privateMessagesByPeerId + (peerId to updatedMessages),
            generatedUsername = uiState.generatedUsername,
            customUsername = uiState.customUsername,
            useCustomUsernameInGlobalChat = uiState.useCustomUsernameInGlobalChat,
            desiredAvailability = uiState.desiredAvailability
        )
    }

    fun updateUsername(username: String) {
        val sanitizedUsername = username.trim()
        if (sanitizedUsername.isEmpty()) return

        uiState = AuroraUiState(
            contacts = uiState.contacts,
            nearbyDevices = uiState.nearbyDevices,
            globalMessages = uiState.globalMessages,
            pendingOutgoingMessages = uiState.pendingOutgoingMessages,
            privateMessagesByPeerId = uiState.privateMessagesByPeerId,
            generatedUsername = uiState.generatedUsername,
            customUsername = sanitizedUsername,
            useCustomUsernameInGlobalChat = uiState.useCustomUsernameInGlobalChat,
            desiredAvailability = uiState.desiredAvailability
        )

        localProfileStore.saveCustomUsername(sanitizedUsername)
    }

    fun updateUseCustomUsernameInGlobalChat(enabled: Boolean) {
        uiState = AuroraUiState(
            contacts = uiState.contacts,
            nearbyDevices = uiState.nearbyDevices,
            globalMessages = uiState.globalMessages,
            pendingOutgoingMessages = uiState.pendingOutgoingMessages,
            privateMessagesByPeerId = uiState.privateMessagesByPeerId,
            generatedUsername = uiState.generatedUsername,
            customUsername = uiState.customUsername,
            useCustomUsernameInGlobalChat = enabled,
            desiredAvailability = uiState.desiredAvailability
        )

        localProfileStore.saveUseCustomUsernameInGlobalChat(enabled)
    }

    fun updateDesiredAvailability(preference: AuroraAvailabilityPreference) {
        uiState = uiState.copy(desiredAvailability = preference)
    }

    fun resetLocalData() {
        // Το reset μένει στο ίδιο κεντρικό flow ώστε να καθαρίζει σταδιακά όλα τα τοπικά profile settings.
        localProfileStore.clearProfile()
        val freshGeneratedUsername = createAndPersistGeneratedUsername()
        uiState = SampleAuroraState.create(
            generatedUsername = freshGeneratedUsername,
            customUsername = null,
            useCustomUsernameInGlobalChat = true,
            desiredAvailability = uiState.desiredAvailability
        )
    }

    fun privateMessagesForPeerId(peerId: String): List<ChatMessage> {
        return uiState.privateMessagesByPeerId[peerId].orEmpty()
    }

    fun displayNameForPeerId(peerId: String): String {
        return uiState.contacts.firstOrNull { it.id == peerId }?.displayName ?: peerId
    }

    fun pendingOutgoingMessagesForThread(threadId: String): List<OutgoingChatMessage> {
        return uiState.pendingOutgoingMessages.filter { it.threadId == threadId }
    }

    fun pendingOutgoingMessageFrameDraftsForThread(threadId: String): List<OutgoingMessageFrameDraft> {
        return pendingOutgoingMessagesForThread(threadId).map(OutgoingMessageFrameBuilder::build)
    }

    private fun createOutgoingMessage(
        threadId: String,
        senderName: String,
        text: String
    ): ChatMessage {
        val now = System.currentTimeMillis()

        return ChatMessage(
            id = "$threadId-$now",
            threadId = threadId,
            senderId = "self",
            senderName = senderName,
            text = text,
            createdAtMillis = now,
            status = MessageStatus.LOCAL_ONLY,
            isOutgoing = true
        )
    }

    private fun createQueuedOutgoingChatMessage(
        message: ChatMessage
    ): OutgoingChatMessage {
        return OutgoingChatMessage(
            messageId = message.id,
            threadId = message.threadId,
            userText = message.text,
            createdAtMillis = message.createdAtMillis,
            status = MessageStatus.QUEUED
        )
    }

    private fun createAndPersistGeneratedUsername(): String {
        return GeneratedUsername.create().also(localProfileStore::saveGeneratedUsername)
    }
}

@Composable
fun rememberAuroraStateHolder(
    localProfileStore: LocalProfileStore
): AuroraStateHolder {
    val profileSettings = remember(localProfileStore) {
        ensureGeneratedUsername(
            profileSettings = localProfileStore.loadProfileSettings(),
            localProfileStore = localProfileStore
        )
    }

    return remember(localProfileStore) {
        AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = profileSettings.generatedUsername
                    ?: error("generatedUsername must be resolved before state creation."),
                customUsername = profileSettings.customUsername,
                useCustomUsernameInGlobalChat = profileSettings.useCustomUsernameInGlobalChat
            ),
            localProfileStore = localProfileStore
        )
    }
}

private fun ensureGeneratedUsername(
    profileSettings: LocalProfileSettings,
    localProfileStore: LocalProfileSettingsStore
): LocalProfileSettings {
    val resolvedGeneratedUsername = profileSettings.generatedUsername ?: GeneratedUsername.create().also {
        // Το generated όνομα γράφεται μία φορά όταν λείπει ώστε να μείνει σταθερό στα επόμενα ανοίγματα.
        localProfileStore.saveGeneratedUsername(it)
    }

    return LocalProfileSettings(
        generatedUsername = resolvedGeneratedUsername,
        customUsername = profileSettings.customUsername,
        useCustomUsernameInGlobalChat = profileSettings.useCustomUsernameInGlobalChat
    )
}
