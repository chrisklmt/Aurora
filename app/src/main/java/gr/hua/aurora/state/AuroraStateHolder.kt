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
import gr.hua.aurora.data.persistence.AuroraPersistenceStore
import gr.hua.aurora.data.persistence.PersistedAuroraState
import gr.hua.aurora.data.persistence.toPersistedChatMessage
import gr.hua.aurora.data.persistence.toPersistedContact
import gr.hua.aurora.model.AuroraContact
import gr.hua.aurora.model.ChatMessage
import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.model.OutgoingChatMessage
import gr.hua.aurora.protocol.GlobalMeshDeliveryResult
import gr.hua.aurora.protocol.IncomingTransportMessage
import gr.hua.aurora.protocol.OutgoingMessageFrameBuilder
import gr.hua.aurora.protocol.OutgoingMessageFrameDraft
import gr.hua.aurora.protocol.PrivateChatMessageSendResult

class AuroraStateHolder(
    initialState: AuroraUiState,
    private val localProfileStore: LocalProfileSettingsStore,
    private val persistenceStore: AuroraPersistenceStore? = null
) {
    var uiState by mutableStateOf(initialState)
        private set
    private var localMessageSequence = 0L

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

        uiState = uiState.copy(
            globalMessages = sortVisibleMessages(uiState.globalMessages + outgoingMessage),
            pendingOutgoingMessages = uiState.pendingOutgoingMessages + queuedMessage,
            globalMeshDeliveryResult = null
        )
        persistRestorableState()

        return queuedMessage
    }

    fun sendPrivateChatMessage(peerId: String, text: String): OutgoingChatMessage? {
        val sanitizedText = text.trim()
        if (sanitizedText.isEmpty()) return null
        val sanitizedPeerId = peerId.trim()
        if (sanitizedPeerId.isEmpty()) return null

        val outgoingMessage = createOutgoingMessage(
            threadId = "private:$sanitizedPeerId",
            senderName = uiState.privateProfileUsername,
            text = sanitizedText,
            status = MessageStatus.QUEUED
        )
        val queuedMessage = createQueuedOutgoingChatMessage(
            message = outgoingMessage
        )
        val updatedMessages = sortVisibleMessages(
            privateMessagesForPeerId(sanitizedPeerId) + outgoingMessage
        )

        uiState = uiState.copy(
            pendingOutgoingMessages = uiState.pendingOutgoingMessages + queuedMessage,
            privateMessagesByPeerId = uiState.privateMessagesByPeerId + (sanitizedPeerId to updatedMessages),
            privateChatDeliveryResultsByPeerId = uiState.privateChatDeliveryResultsByPeerId - sanitizedPeerId
        )
        persistRestorableState()

        return queuedMessage
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
            globalMeshDeliveryResult = uiState.globalMeshDeliveryResult,
            privateChatDeliveryResultsByPeerId = uiState.privateChatDeliveryResultsByPeerId
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
            globalMeshDeliveryResult = uiState.globalMeshDeliveryResult,
            privateChatDeliveryResultsByPeerId = uiState.privateChatDeliveryResultsByPeerId
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
            persistRestorableState()
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
        persistenceStore?.clear()
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

    fun latestPrivateChatDeliveryResultForPeerId(
        peerId: String
    ): PrivateChatMessageSendResult? {
        val sanitizedPeerId = peerId.trim()
        if (sanitizedPeerId.isEmpty()) return null
        return uiState.privateChatDeliveryResultsByPeerId[sanitizedPeerId]
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
        persistRestorableState()
    }

    fun handlePrivateChatDeliveryResult(
        peerId: String,
        messageId: String,
        result: PrivateChatMessageSendResult
    ) {
        val sanitizedPeerId = peerId.trim()
        if (sanitizedPeerId.isEmpty()) return

        val updatedMessages = uiState.privateMessagesByPeerId[sanitizedPeerId]
            ?.map { message ->
                if (message.id == messageId && message.isOutgoing) {
                    message.copy(
                        status = visiblePrivateMessageStatusForSendResult(result)
                    )
                } else {
                    message
                }
            }
            ?: return

        uiState = uiState.copy(
            privateMessagesByPeerId = uiState.privateMessagesByPeerId + (sanitizedPeerId to updatedMessages),
            privateChatDeliveryResultsByPeerId =
                uiState.privateChatDeliveryResultsByPeerId + (sanitizedPeerId to result)
        )
        persistRestorableState()
    }

    fun ingestIncomingTransportMessage(
        message: IncomingTransportMessage
    ): IncomingMessageIngestionResult {
        val previousState = uiState
        val outcome = IncomingChatMessageIngestor.ingest(
            state = uiState,
            message = message
        )
        uiState = outcome.updatedState
        if (outcome.updatedState != previousState) {
            persistRestorableState()
        }
        return outcome.result
    }

    private fun persistRestorableState() {
        persistenceStore?.replaceAll(
            PersistedAuroraState(
                contacts = uiState.contacts.map(AuroraContact::toPersistedContact),
                messages = restoredVisibleMessages().map(ChatMessage::toPersistedChatMessage)
            )
        )
    }

    private fun restoredVisibleMessages(): List<ChatMessage> {
        return sortVisibleMessages(
            uiState.globalMessages +
                uiState.privateMessagesByPeerId.values.flatten()
        )
    }

    private fun createOutgoingMessage(
        threadId: String,
        senderName: String,
        text: String,
        status: MessageStatus
    ): ChatMessage {
        val now = System.currentTimeMillis()
        val sequenceToken = localMessageSequence++.toString().padStart(6, '0')

        return ChatMessage(
            id = "$threadId-$now-$sequenceToken",
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

private fun visiblePrivateMessageStatusForSendResult(
    result: PrivateChatMessageSendResult
): MessageStatus {
    return when (result) {
        PrivateChatMessageSendResult.SubmittedLocally -> MessageStatus.SENT
        PrivateChatMessageSendResult.KeysUnavailable,
        PrivateChatMessageSendResult.ContactUnavailable,
        PrivateChatMessageSendResult.ContactNotReachable,
        is PrivateChatMessageSendResult.Failed -> MessageStatus.FAILED
    }
}

@Composable
fun rememberAuroraStateHolder(
    localProfileStore: LocalProfileStore,
    persistenceStore: AuroraPersistenceStore? = null
): AuroraStateHolder {
    return remember(localProfileStore, persistenceStore) {
        createAuroraStateHolder(localProfileStore, persistenceStore)
    }
}

fun createAuroraStateHolder(
    localProfileStore: LocalProfileSettingsStore,
    persistenceStore: AuroraPersistenceStore? = null
): AuroraStateHolder {
    val profileSettings = ensureGeneratedUsername(
        profileSettings = localProfileStore.loadProfileSettings(),
        localProfileStore = localProfileStore
    )
    val initialState = restoreAuroraUiState(
        baseState = SampleAuroraState.create(
            generatedUsername = profileSettings.generatedUsername
                ?: error("generatedUsername must be resolved before state creation."),
            customUsername = profileSettings.customUsername,
            useCustomUsernameInGlobalChat = profileSettings.useCustomUsernameInGlobalChat
        ),
        persistedState = persistenceStore?.load() ?: PersistedAuroraState()
    )

    return AuroraStateHolder(
        initialState = initialState,
        localProfileStore = localProfileStore,
        persistenceStore = persistenceStore
    )
}

internal fun restoreAuroraUiState(
    baseState: AuroraUiState,
    persistedState: PersistedAuroraState
): AuroraUiState {
    val restoredContacts = persistedState.contacts
        .map { it.toRestoredContact() }
        .sortedWith(
            compareByDescending<AuroraContact> { it.hasSession }
                .thenBy { it.displayName.lowercase() }
                .thenBy { it.canonicalPeerId }
        )
    val restoredMessages = persistedState.messages
        .map { it.toRestoredChatMessage() }
        .sortedWith(
            compareBy<ChatMessage>({ it.createdAtMillis }, { it.id })
        )
    val restoredGlobalMessages = restoredMessages.filter { it.threadId == "global" }
    val restoredPrivateMessages = restoredMessages
        .filter { it.threadId.startsWith("private:") }
        .groupBy { it.threadId.removePrefix("private:") }
        .mapValues { (_, messages) ->
            sortVisibleMessages(messages)
        }
        .toSortedMap()

    return baseState.copy(
        contacts = restoredContacts,
        globalMessages = restoredGlobalMessages,
        pendingOutgoingMessages = emptyList(),
        privateMessagesByPeerId = restoredPrivateMessages,
        selectedSecurePeerId = null,
        globalMeshDeliveryResult = null,
        privateChatDeliveryResultsByPeerId = emptyMap()
    )
}

internal fun sortVisibleMessages(
    messages: List<ChatMessage>
): List<ChatMessage> {
    return messages.sortedWith(
        compareBy<ChatMessage>({ it.createdAtMillis }, { it.id })
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
