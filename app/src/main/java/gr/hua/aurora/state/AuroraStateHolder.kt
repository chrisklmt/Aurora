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
import gr.hua.aurora.model.AuroraContact
import gr.hua.aurora.model.ChatMessage
import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.model.OutgoingChatMessage
import gr.hua.aurora.protocol.GlobalMeshDeliveryResult
import gr.hua.aurora.protocol.IncomingTransportMessage
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
    fun sendGlobalPreviewMessage(text: String): OutgoingChatMessage? {
        val sanitizedText = text.trim()
        if (sanitizedText.isEmpty()) return null
        val outgoingMessage = createOutgoingMessage(
            threadId = "global",
            senderName = uiState.globalChatUsername,
            text = sanitizedText,
            status = MessageStatus.QUEUED
        )
        val queuedMessage = createQueuedOutgoingChatMessage(
            message = outgoingMessage
        )

        uiState = AuroraUiState(
            contacts = uiState.contacts,
            nearbyDevices = uiState.nearbyDevices,
            globalMessages = uiState.globalMessages + outgoingMessage,
            pendingOutgoingMessages = uiState.pendingOutgoingMessages + queuedMessage,
            privateMessagesByPeerId = uiState.privateMessagesByPeerId,
            generatedUsername = uiState.generatedUsername,
            customUsername = uiState.customUsername,
            useCustomUsernameInGlobalChat = uiState.useCustomUsernameInGlobalChat,
            isDebugModeEnabled = uiState.isDebugModeEnabled,
            desiredAvailability = uiState.desiredAvailability,
            selectedSecurePeerId = uiState.selectedSecurePeerId,
            globalMeshDeliveryResult = null
        )

        return queuedMessage
    }

    fun sendPrivatePreviewMessage(peerId: String, text: String) {
        val sanitizedText = text.trim()
        if (sanitizedText.isEmpty()) return

        val updatedMessages = privateMessagesForPeerId(peerId) + createOutgoingMessage(
            threadId = "private:$peerId",
            senderName = uiState.privateProfileUsername,
            text = sanitizedText,
            status = MessageStatus.LOCAL_ONLY
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
            isDebugModeEnabled = uiState.isDebugModeEnabled,
            desiredAvailability = uiState.desiredAvailability,
            selectedSecurePeerId = uiState.selectedSecurePeerId,
            globalMeshDeliveryResult = uiState.globalMeshDeliveryResult
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
            isDebugModeEnabled = uiState.isDebugModeEnabled,
            desiredAvailability = uiState.desiredAvailability,
            selectedSecurePeerId = uiState.selectedSecurePeerId,
            globalMeshDeliveryResult = uiState.globalMeshDeliveryResult
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
            isDebugModeEnabled = uiState.isDebugModeEnabled,
            desiredAvailability = uiState.desiredAvailability,
            selectedSecurePeerId = uiState.selectedSecurePeerId,
            globalMeshDeliveryResult = uiState.globalMeshDeliveryResult
        )

        localProfileStore.saveUseCustomUsernameInGlobalChat(enabled)
    }

    fun updateDebugMode(enabled: Boolean) {
        uiState = uiState.copy(
            isDebugModeEnabled = enabled
        )
    }

    fun updateDesiredAvailability(preference: AuroraAvailabilityPreference) {
        uiState = uiState.copy(desiredAvailability = preference)
    }

    fun selectSecurePeer(peerId: String) {
        val sanitizedPeerId = peerId.trim()
        if (sanitizedPeerId.isEmpty()) return

        uiState = uiState.copy(
            selectedSecurePeerId = sanitizedPeerId
        )
    }

    fun clearSelectedSecurePeer() {
        if (uiState.selectedSecurePeerId == null) return

        uiState = uiState.copy(
            selectedSecurePeerId = null
        )
    }

    fun addOrUpdateContact(
        canonicalPeerId: String,
        displayName: String,
        lastSeenMillis: Long? = null,
        hasSession: Boolean = false
    ): AuroraContact {
        val sanitizedPeerId = canonicalPeerId.trim()
        val sanitizedDisplayName = displayName.trim()
        require(sanitizedPeerId.isNotEmpty()) {
            "Aurora contact canonicalPeerId must not be blank."
        }
        require(sanitizedDisplayName.isNotEmpty()) {
            "Aurora contact displayName must not be blank."
        }

        val existingContact = uiState.contacts.firstOrNull { it.canonicalPeerId == sanitizedPeerId }
        val resolvedLastSeenMillis = lastSeenMillis ?: existingContact?.lastSeenMillis
        val updatedContact = if (existingContact == null) {
            AuroraContact(
                canonicalPeerId = sanitizedPeerId,
                displayName = sanitizedDisplayName,
                createdAtMillis = System.currentTimeMillis(),
                lastSeenMillis = resolvedLastSeenMillis,
                hasSession = hasSession
            )
        } else {
            existingContact.copy(
                displayName = sanitizedDisplayName,
                lastSeenMillis = resolvedLastSeenMillis,
                hasSession = existingContact.hasSession || hasSession
            )
        }

        if (existingContact != updatedContact) {
            val updatedContacts = if (existingContact == null) {
                uiState.contacts + updatedContact
            } else {
                uiState.contacts.map { contact ->
                    if (contact.canonicalPeerId == sanitizedPeerId) {
                        updatedContact
                    } else {
                        contact
                    }
                }
            }.sortedWith(
                compareByDescending<AuroraContact> { it.hasSession }
                    .thenBy { it.displayName.lowercase() }
            )

            uiState = uiState.copy(
                contacts = updatedContacts
            )
        }

        return updatedContact
    }

    fun findContactByPeerId(peerId: String): AuroraContact? {
        val sanitizedPeerId = peerId.trim()
        if (sanitizedPeerId.isEmpty()) return null
        return uiState.contacts.firstOrNull { it.canonicalPeerId == sanitizedPeerId }
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
        return findContactByPeerId(peerId)?.displayName ?: peerId
    }

    fun pendingOutgoingMessagesForThread(threadId: String): List<OutgoingChatMessage> {
        return uiState.pendingOutgoingMessages.filter { it.threadId == threadId }
    }

    fun pendingOutgoingMessageFrameDraftsForThread(threadId: String): List<OutgoingMessageFrameDraft> {
        return pendingOutgoingMessagesForThread(threadId).map(OutgoingMessageFrameBuilder::build)
    }

    fun handleGlobalMeshDeliveryResult(
        messageId: String,
        result: GlobalMeshDeliveryResult
    ) {
        uiState = uiState.copy(
            globalMessages = uiState.globalMessages.map { message ->
                if (message.id == messageId && message.isOutgoing) {
                    message.copy(
                        status = visibleGlobalMessageStatusForMeshResult(
                            currentStatus = message.status,
                            result = result
                        )
                    )
                } else {
                    message
                }
            },
            globalMeshDeliveryResult = result
        )
    }

    fun ingestIncomingTransportMessage(
        message: IncomingTransportMessage
    ): IncomingMessageIngestionResult {
        val outcome = IncomingChatMessageIngestor.ingest(
            state = uiState,
            message = message
        )
        uiState = outcome.updatedState
        return outcome.result
    }

    private fun createOutgoingMessage(
        threadId: String,
        senderName: String,
        text: String,
        status: MessageStatus
    ): ChatMessage {
        val now = System.currentTimeMillis()

        return ChatMessage(
            id = "$threadId-$now",
            threadId = threadId,
            senderId = "self",
            senderName = senderName,
            text = text,
            createdAtMillis = now,
            status = status,
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

private fun visibleGlobalMessageStatusForMeshResult(
    currentStatus: MessageStatus,
    result: GlobalMeshDeliveryResult
): MessageStatus {
    return when (result) {
        is GlobalMeshDeliveryResult.QueuedToActivePeer -> MessageStatus.SENT
        GlobalMeshDeliveryResult.NoReachablePeers,
        GlobalMeshDeliveryResult.SenderUnavailable,
        is GlobalMeshDeliveryResult.ConnectOnSendFailed -> MessageStatus.QUEUED
        is GlobalMeshDeliveryResult.Failed -> MessageStatus.FAILED
        is GlobalMeshDeliveryResult.SkippedDuplicate,
        is GlobalMeshDeliveryResult.SkippedSourcePeer,
        is GlobalMeshDeliveryResult.SkippedTtlExpired -> currentStatus
    }
}

@Composable
fun rememberAuroraStateHolder(
    localProfileStore: LocalProfileStore
): AuroraStateHolder {
    return remember(localProfileStore) {
        createAuroraStateHolder(localProfileStore)
    }
}

fun createAuroraStateHolder(
    localProfileStore: LocalProfileSettingsStore
): AuroraStateHolder {
    val profileSettings = ensureGeneratedUsername(
        profileSettings = localProfileStore.loadProfileSettings(),
        localProfileStore = localProfileStore
    )

    return AuroraStateHolder(
        initialState = SampleAuroraState.create(
            generatedUsername = profileSettings.generatedUsername
                ?: error("generatedUsername must be resolved before state creation."),
            customUsername = profileSettings.customUsername,
            useCustomUsernameInGlobalChat = profileSettings.useCustomUsernameInGlobalChat
        ),
        localProfileStore = localProfileStore
    )
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
