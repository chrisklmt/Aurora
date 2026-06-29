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
import gr.hua.aurora.data.persistence.toPersistedPrivateChat
import gr.hua.aurora.model.AuroraContact
import gr.hua.aurora.model.ChatMessage
import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.model.OutgoingChatMessage
import gr.hua.aurora.model.PrivateChatIdentity
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
            pendingOutgoingMessages = upsertPendingOutgoingMessage(
                uiState.pendingOutgoingMessages,
                queuedMessage
            ),
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
        if (findContactByPeerId(sanitizedPeerId) == null) return null

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
            pendingOutgoingMessages = upsertPendingOutgoingMessage(
                uiState.pendingOutgoingMessages,
                queuedMessage
            ),
            privateMessagesByPeerId = uiState.privateMessagesByPeerId + (sanitizedPeerId to updatedMessages),
            privateChatDeliveryResultsByPeerId = uiState.privateChatDeliveryResultsByPeerId - sanitizedPeerId
        )
        persistRestorableState()

        return queuedMessage
    }

    fun retryGlobalOutgoingMessage(messageId: String): OutgoingChatMessage? {
        val retryMessage = uiState.globalMessages
            .firstOrNull { it.id == messageId && it.isOutgoing }
            ?.takeIf { canRetryOutgoingMessage(it.status) }
            ?: return null

        val refreshedQueueEntry = createQueuedOutgoingChatMessage(
            message = retryMessage.copy(status = MessageStatus.QUEUED)
        )
        uiState = uiState.copy(
            globalMessages = uiState.globalMessages.map { message ->
                if (message.id == messageId && message.isOutgoing) {
                    message.copy(status = MessageStatus.QUEUED)
                } else {
                    message
                }
            },
            pendingOutgoingMessages = upsertPendingOutgoingMessage(
                uiState.pendingOutgoingMessages,
                refreshedQueueEntry
            ),
            globalMeshDeliveryResult = null
        )
        persistRestorableState()
        return refreshedQueueEntry
    }

    fun retryPrivateChatOutgoingMessage(
        peerId: String,
        messageId: String
    ): OutgoingChatMessage? {
        val sanitizedPeerId = peerId.trim()
        if (sanitizedPeerId.isEmpty()) return null
        val threadId = "private:$sanitizedPeerId"
        val retryMessage = uiState.privateMessagesByPeerId[sanitizedPeerId]
            ?.firstOrNull { it.id == messageId && it.isOutgoing && it.threadId == threadId }
            ?.takeIf { canRetryOutgoingMessage(it.status) }
            ?: return null

        val refreshedQueueEntry = createQueuedOutgoingChatMessage(
            message = retryMessage.copy(status = MessageStatus.QUEUED)
        )
        val updatedMessages = requireNotNull(uiState.privateMessagesByPeerId[sanitizedPeerId]).map { message ->
            if (message.id == messageId && message.isOutgoing) {
                message.copy(status = MessageStatus.QUEUED)
            } else {
                message
            }
        }
        uiState = uiState.copy(
            pendingOutgoingMessages = upsertPendingOutgoingMessage(
                uiState.pendingOutgoingMessages,
                refreshedQueueEntry
            ),
            privateMessagesByPeerId = uiState.privateMessagesByPeerId + (sanitizedPeerId to updatedMessages),
            privateChatDeliveryResultsByPeerId = uiState.privateChatDeliveryResultsByPeerId - sanitizedPeerId
        )
        persistRestorableState()
        return refreshedQueueEntry
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
            privateChatIdentitiesByPeerId = uiState.privateChatIdentitiesByPeerId,
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
            privateChatIdentitiesByPeerId = uiState.privateChatIdentitiesByPeerId,
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
        return upsertContact(
            canonicalPeerId = canonicalPeerId,
            displayName = displayName,
            lastSeenMillis = lastSeenMillis,
            hasSession = hasSession,
            createPrivateChatIdentity = true
        )
    }

    fun promoteContactSession(
        canonicalPeerId: String,
        displayName: String,
        lastSeenMillis: Long? = null
    ): AuroraContact {
        return upsertContact(
            canonicalPeerId = canonicalPeerId,
            displayName = displayName,
            lastSeenMillis = lastSeenMillis,
            hasSession = true,
            createPrivateChatIdentity = false
        )
    }

    fun refreshContactLastSeen(
        canonicalPeerId: String,
        lastSeenMillis: Long
    ): AuroraContact? {
        val existingContact = findContactByPeerId(canonicalPeerId) ?: return null
        if (existingContact.lastSeenMillis == lastSeenMillis) {
            return existingContact
        }
        return upsertContact(
            canonicalPeerId = existingContact.canonicalPeerId,
            displayName = existingContact.displayName,
            lastSeenMillis = lastSeenMillis,
            hasSession = false,
            createPrivateChatIdentity = false
        )
    }

    fun findContactByPeerId(peerId: String): AuroraContact? {
        val sanitizedPeerId = peerId.trim()
        if (sanitizedPeerId.isEmpty()) return null
        return uiState.contacts.firstOrNull { it.canonicalPeerId == sanitizedPeerId }
    }

    fun privateChatIdentityForPeerId(peerId: String): PrivateChatIdentity? {
        val sanitizedPeerId = peerId.trim()
        if (sanitizedPeerId.isEmpty()) return null
        return uiState.privateChatIdentitiesByPeerId[sanitizedPeerId]
    }

    fun privateChatDisplayNameForPeerId(peerId: String): String {
        val sanitizedPeerId = peerId.trim()
        if (sanitizedPeerId.isEmpty()) return peerId
        val identity = uiState.privateChatIdentitiesByPeerId[sanitizedPeerId]
        return identity?.displayNameOrNull()
            ?: findContactByPeerId(sanitizedPeerId)?.displayName
            ?: sanitizedPeerId
    }

    fun isPrivateChatReadyForPeerId(peerId: String): Boolean {
        val sanitizedPeerId = peerId.trim()
        if (sanitizedPeerId.isEmpty()) return false
        val contact = findContactByPeerId(sanitizedPeerId) ?: return false
        val identity = privateChatIdentityForPeerId(sanitizedPeerId) ?: return false
        return contact.hasSession && identity.isEstablished
    }

    fun renamePrivateChat(
        peerId: String,
        customChatName: String?
    ): PrivateChatIdentity? {
        val sanitizedPeerId = peerId.trim()
        if (sanitizedPeerId.isEmpty()) return null
        val existingIdentity = privateChatIdentityForPeerId(sanitizedPeerId) ?: return null
        val normalizedCustomName = customChatName?.trim()?.takeIf { it.isNotEmpty() }
        val updatedIdentity = existingIdentity.copy(
            customChatName = normalizedCustomName,
            lastUpdatedMillis = System.currentTimeMillis()
        )
        if (updatedIdentity == existingIdentity) {
            return existingIdentity
        }

        uiState = uiState.copy(
            privateChatIdentitiesByPeerId =
                uiState.privateChatIdentitiesByPeerId + (sanitizedPeerId to updatedIdentity)
        )
        persistRestorableState()
        return updatedIdentity
    }

    fun deletePrivateChat(peerId: String) {
        val sanitizedPeerId = peerId.trim()
        if (sanitizedPeerId.isEmpty()) return
        if (!uiState.privateMessagesByPeerId.containsKey(sanitizedPeerId) &&
            !uiState.privateChatIdentitiesByPeerId.containsKey(sanitizedPeerId)
        ) {
            return
        }

        uiState = uiState.copy(
            privateMessagesByPeerId = uiState.privateMessagesByPeerId - sanitizedPeerId,
            privateChatIdentitiesByPeerId = uiState.privateChatIdentitiesByPeerId - sanitizedPeerId,
            privateChatDeliveryResultsByPeerId = uiState.privateChatDeliveryResultsByPeerId - sanitizedPeerId,
            pendingOutgoingMessages = uiState.pendingOutgoingMessages.filterNot {
                it.threadId == "private:$sanitizedPeerId"
            }
        )
        persistRestorableState()
    }

    fun recordReceivedPrivateChatProposal(
        peerId: String,
        remoteProposalId: String?
    ): PrivateChatIdentity? {
        val sanitizedPeerId = peerId.trim()
        val sanitizedRemoteProposalId = remoteProposalId?.trim()?.takeIf { it.isNotEmpty() }
        if (sanitizedPeerId.isEmpty() || sanitizedRemoteProposalId == null) return null
        val now = System.currentTimeMillis()
        val existingIdentity = uiState.privateChatIdentitiesByPeerId[sanitizedPeerId]
        val updatedIdentity = resolveSharedPrivateChatIdentity(
            identity = existingIdentity ?: PrivateChatIdentity(
                canonicalPeerId = sanitizedPeerId,
                createdAtMillis = now,
                lastUpdatedMillis = now
            ),
            localProposalId = existingIdentity?.localProposalId,
            remoteProposalId = sanitizedRemoteProposalId,
            updatedAtMillis = now
        )
        if (updatedIdentity != existingIdentity) {
            uiState = uiState.copy(
                privateChatIdentitiesByPeerId =
                    uiState.privateChatIdentitiesByPeerId + (sanitizedPeerId to updatedIdentity)
            )
            persistRestorableState()
        }
        return updatedIdentity
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
        return privateChatDisplayNameForPeerId(peerId)
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
            pendingOutgoingMessages = updatePendingOutgoingMessageStatus(
                messages = uiState.pendingOutgoingMessages,
                messageId = messageId,
                status = pendingOutgoingStatusForGlobalMeshResult(
                    currentStatus = uiState.pendingOutgoingMessages
                        .firstOrNull { it.messageId == messageId }
                        ?.status
                        ?: MessageStatus.QUEUED,
                    result = result
                )
            ),
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
            pendingOutgoingMessages = updatePendingOutgoingMessageStatus(
                messages = uiState.pendingOutgoingMessages,
                messageId = messageId,
                status = pendingOutgoingStatusForPrivateSendResult(result)
            ),
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
                messages = restoredVisibleMessages().map(ChatMessage::toPersistedChatMessage),
                privateChats = uiState.privateChatIdentitiesByPeerId.values
                    .map(PrivateChatIdentity::toPersistedPrivateChat)
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
            status = message.status
        )
    }

    private fun upsertContact(
        canonicalPeerId: String,
        displayName: String,
        lastSeenMillis: Long? = null,
        hasSession: Boolean = false,
        createPrivateChatIdentity: Boolean
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
        val now = System.currentTimeMillis()
        val updatedContact = if (existingContact == null) {
            AuroraContact(
                canonicalPeerId = sanitizedPeerId,
                displayName = sanitizedDisplayName,
                createdAtMillis = now,
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
        val existingIdentity = uiState.privateChatIdentitiesByPeerId[sanitizedPeerId]
        val updatedIdentity = if (createPrivateChatIdentity) {
            ensurePrivateChatIdentityForExplicitSetup(
                peerId = sanitizedPeerId,
                existingIdentity = existingIdentity,
                createdAtMillis = now
            )
        } else {
            existingIdentity
        }

        if (existingContact != updatedContact || updatedIdentity != existingIdentity) {
            uiState = uiState.copy(
                contacts = updatedContacts,
                privateChatIdentitiesByPeerId = if (updatedIdentity != null) {
                    uiState.privateChatIdentitiesByPeerId + (sanitizedPeerId to updatedIdentity)
                } else {
                    uiState.privateChatIdentitiesByPeerId
                }
            )
            persistRestorableState()
        }

        return updatedContact
    }

    private fun ensurePrivateChatIdentityForExplicitSetup(
        peerId: String,
        existingIdentity: PrivateChatIdentity?,
        createdAtMillis: Long
    ): PrivateChatIdentity {
        val normalizedExistingIdentity = existingIdentity ?: PrivateChatIdentity(
            canonicalPeerId = peerId,
            createdAtMillis = createdAtMillis,
            lastUpdatedMillis = createdAtMillis
        )
        if (normalizedExistingIdentity.privateChatId != null) {
            return normalizedExistingIdentity
        }

        val localProposalId = normalizedExistingIdentity.localProposalId
            ?: PrivateChatIdentity.generateProposalId()
        return resolveSharedPrivateChatIdentity(
            identity = normalizedExistingIdentity,
            localProposalId = localProposalId,
            remoteProposalId = normalizedExistingIdentity.remoteProposalId,
            updatedAtMillis = createdAtMillis
        )
    }

    private fun resolveSharedPrivateChatIdentity(
        identity: PrivateChatIdentity,
        localProposalId: String?,
        remoteProposalId: String?,
        updatedAtMillis: Long
    ): PrivateChatIdentity {
        val sharedChatId = if (!localProposalId.isNullOrBlank() && !remoteProposalId.isNullOrBlank()) {
            PrivateChatIdentity.deriveSharedChatId(
                localProposalId = localProposalId,
                remoteProposalId = remoteProposalId
            )
        } else {
            null
        }
        return identity.copy(
            privateChatId = sharedChatId,
            localProposalId = localProposalId,
            remoteProposalId = remoteProposalId,
            lastUpdatedMillis = updatedAtMillis
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
        is GlobalMeshDeliveryResult.QueuedToActivePeer,
        is GlobalMeshDeliveryResult.QueuedToPeers -> MessageStatus.SENT
        GlobalMeshDeliveryResult.NoReachablePeers,
        GlobalMeshDeliveryResult.SenderUnavailable,
        is GlobalMeshDeliveryResult.ConnectOnSendFailed,
        is GlobalMeshDeliveryResult.Failed -> MessageStatus.FAILED
        is GlobalMeshDeliveryResult.SkippedDuplicate,
        is GlobalMeshDeliveryResult.SkippedSourcePeer,
        is GlobalMeshDeliveryResult.SkippedTtlExpired -> currentStatus
    }
}

private fun pendingOutgoingStatusForGlobalMeshResult(
    currentStatus: MessageStatus,
    result: GlobalMeshDeliveryResult
): MessageStatus {
    return when (result) {
        is GlobalMeshDeliveryResult.QueuedToActivePeer,
        is GlobalMeshDeliveryResult.QueuedToPeers -> MessageStatus.SENT
        GlobalMeshDeliveryResult.NoReachablePeers,
        GlobalMeshDeliveryResult.SenderUnavailable,
        is GlobalMeshDeliveryResult.ConnectOnSendFailed,
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

private fun pendingOutgoingStatusForPrivateSendResult(
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
    val restoredPrivateChats = persistedState.privateChats
        .map { it.toRestoredPrivateChatIdentity() }
        .associateBy { it.canonicalPeerId }

    return baseState.copy(
        contacts = restoredContacts,
        globalMessages = restoredGlobalMessages,
        pendingOutgoingMessages = restoredRetryableOutgoingMessages(restoredMessages),
        privateMessagesByPeerId = restoredPrivateMessages,
        privateChatIdentitiesByPeerId = restoredPrivateChats,
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

private fun restoredRetryableOutgoingMessages(
    messages: List<ChatMessage>
): List<OutgoingChatMessage> {
    return messages
        .filter { it.isOutgoing && canRetryOutgoingMessage(it.status) }
        .map { message ->
            OutgoingChatMessage(
                messageId = message.id,
                threadId = message.threadId,
                userText = message.text,
                createdAtMillis = message.createdAtMillis,
                status = message.status
            )
        }
        .sortedWith(
            compareBy<OutgoingChatMessage>({ it.createdAtMillis }, { it.messageId })
        )
}

private fun canRetryOutgoingMessage(
    status: MessageStatus
): Boolean {
    return when (status) {
        MessageStatus.QUEUED,
        MessageStatus.LOCAL_ONLY,
        MessageStatus.FAILED -> true
        MessageStatus.DRAFT,
        MessageStatus.RECEIVED,
        MessageStatus.SENT,
        MessageStatus.DELIVERED -> false
    }
}

private fun upsertPendingOutgoingMessage(
    messages: List<OutgoingChatMessage>,
    message: OutgoingChatMessage
): List<OutgoingChatMessage> {
    return (messages.filterNot { it.messageId == message.messageId } + message).sortedWith(
        compareBy<OutgoingChatMessage>({ it.createdAtMillis }, { it.messageId })
    )
}

private fun updatePendingOutgoingMessageStatus(
    messages: List<OutgoingChatMessage>,
    messageId: String,
    status: MessageStatus
): List<OutgoingChatMessage> {
    return messages.map { message ->
        if (message.messageId == messageId) {
            message.copy(status = status)
        } else {
            message
        }
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
