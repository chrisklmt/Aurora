package gr.hua.aurora.state

import gr.hua.aurora.data.GeneratedUsername
import gr.hua.aurora.data.LocalProfileSettings
import gr.hua.aurora.data.LocalProfileSettingsStore
import gr.hua.aurora.model.AuroraContact
import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.protocol.GlobalMeshDeliveryResult
import gr.hua.aurora.protocol.MessageFrameType
import gr.hua.aurora.protocol.OutgoingMessageFrameResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuroraStateHolderTest {
    @Test
    fun defaultToggleIsTrueInSampleState() {
        val state = SampleAuroraState.create(generatedUsername = "PIAIUFN1")

        assertTrue(state.useCustomUsernameInGlobalChat)
        assertEquals(false, state.isDebugModeEnabled)
        assertEquals(AuroraAvailabilityPreference.ONLINE, state.desiredAvailability)
        assertTrue(state.globalMessages.isEmpty())
    }

    @Test
    fun updateUsernameStoresCustomWithoutChangingGeneratedUsername() {
        val store = FakeProfileStore()
        val holder = createHolder(
            store = store,
            generatedUsername = "PIAIUFN1"
        )

        holder.updateUsername(" John ")

        assertEquals("PIAIUFN1", holder.uiState.generatedUsername)
        assertEquals("John", holder.uiState.customUsername)
        assertEquals("John", holder.uiState.privateProfileUsername)
        assertEquals("John", store.customUsername)
    }

    @Test
    fun blankApplyIsIgnored() {
        val store = FakeProfileStore()
        val holder = createHolder(
            store = store,
            generatedUsername = "PIAIUFN1"
        )

        holder.updateUsername("   ")

        assertNull(holder.uiState.customUsername)
        assertNull(store.customUsername)
        assertEquals("PIAIUFN1", holder.uiState.generatedUsername)
    }

    @Test
    fun globalChatUsesCustomUsernameWhenToggleIsTrue() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1",
            customUsername = "John",
            useCustomUsernameInGlobalChat = true
        )

        holder.sendGlobalPreviewMessage("hello")

        assertEquals("John", holder.uiState.globalChatUsername)
        assertEquals("John", holder.uiState.globalMessages.last().senderName)
    }

    @Test
    fun globalChatStillDoesNotRequireContacts() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )

        assertTrue(holder.uiState.contacts.isEmpty())
        val queuedMessage = holder.sendGlobalPreviewMessage("public hello")

        assertEquals("public hello", holder.uiState.globalMessages.last().text)
        assertEquals("global", requireNotNull(queuedMessage).threadId)
    }

    @Test
    fun globalSendAppendsPendingMessage() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )
        val initialCount = holder.uiState.globalMessages.size

        holder.sendGlobalPreviewMessage(" hello local ")

        val appendedMessage = holder.uiState.globalMessages.last()
        assertEquals(initialCount + 1, holder.uiState.globalMessages.size)
        assertEquals("hello local", appendedMessage.text)
        assertTrue(appendedMessage.isOutgoing)
        assertEquals(MessageStatus.QUEUED, appendedMessage.status)
        assertNotEquals(MessageStatus.DELIVERED, appendedMessage.status)
    }

    @Test
    fun globalSendQueuesOutgoingUserMessage() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )
        val initialQueueSize = holder.uiState.pendingOutgoingMessages.size

        holder.sendGlobalPreviewMessage("queued later")

        val appendedVisibleMessage = holder.uiState.globalMessages.last()
        val queuedMessage = holder.uiState.pendingOutgoingMessages.last()
        assertEquals(initialQueueSize + 1, holder.uiState.pendingOutgoingMessages.size)
        assertEquals(appendedVisibleMessage.id, queuedMessage.messageId)
        assertEquals("global", queuedMessage.threadId)
        assertEquals("queued later", queuedMessage.userText)
        assertEquals(MessageStatus.QUEUED, queuedMessage.status)
        assertNotEquals(MessageStatus.SENT, queuedMessage.status)
        assertNotEquals(MessageStatus.DELIVERED, queuedMessage.status)
    }

    @Test
    fun queuedGlobalMeshDeliveryPromotesVisibleGlobalMessageToSent() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )

        val queuedMessage = requireNotNull(holder.sendGlobalPreviewMessage("queue for transport"))

        holder.handleGlobalMeshDeliveryResult(
            messageId = queuedMessage.messageId,
            result = GlobalMeshDeliveryResult.QueuedToActivePeer("peer-123")
        )

        val visibleMessage = holder.uiState.globalMessages.last()
        assertEquals(queuedMessage.messageId, visibleMessage.id)
        assertEquals(MessageStatus.SENT, visibleMessage.status)
        assertEquals(
            GlobalMeshDeliveryResult.QueuedToActivePeer("peer-123"),
            holder.uiState.globalMeshDeliveryResult
        )
        assertEquals(1, holder.uiState.pendingOutgoingMessages.size)
        assertEquals(MessageStatus.QUEUED, holder.uiState.pendingOutgoingMessages.single().status)
        assertNotEquals(MessageStatus.DELIVERED, visibleMessage.status)
    }

    @Test
    fun missingSessionMeshDeliveryKeepsVisibleGlobalMessagePending() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )

        val queuedMessage = requireNotNull(holder.sendGlobalPreviewMessage("stay local"))

        holder.handleGlobalMeshDeliveryResult(
            messageId = queuedMessage.messageId,
            result = GlobalMeshDeliveryResult.SenderUnavailable
        )

        val visibleMessage = holder.uiState.globalMessages.last()
        assertEquals(MessageStatus.QUEUED, visibleMessage.status)
        assertEquals(
            GlobalMeshDeliveryResult.SenderUnavailable,
            holder.uiState.globalMeshDeliveryResult
        )
        assertEquals(1, holder.uiState.pendingOutgoingMessages.size)
        assertEquals(MessageStatus.QUEUED, holder.uiState.pendingOutgoingMessages.single().status)
    }

    @Test
    fun noReachablePeerMeshDeliveryKeepsVisibleGlobalMessagePending() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )

        val queuedMessage = requireNotNull(holder.sendGlobalPreviewMessage("wait for peer"))

        holder.handleGlobalMeshDeliveryResult(
            messageId = queuedMessage.messageId,
            result = GlobalMeshDeliveryResult.NoReachablePeers
        )

        val visibleMessage = holder.uiState.globalMessages.last()
        assertEquals(MessageStatus.QUEUED, visibleMessage.status)
        assertEquals(
            GlobalMeshDeliveryResult.NoReachablePeers,
            holder.uiState.globalMeshDeliveryResult
        )
        assertEquals(1, holder.uiState.pendingOutgoingMessages.size)
        assertEquals(MessageStatus.QUEUED, holder.uiState.pendingOutgoingMessages.single().status)
    }

    @Test
    fun failedMeshDeliveryMarksVisibleGlobalMessageFailedWithoutConsumingQueue() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )

        val queuedMessage = requireNotNull(holder.sendGlobalPreviewMessage("hard failure"))

        holder.handleGlobalMeshDeliveryResult(
            messageId = queuedMessage.messageId,
            result = GlobalMeshDeliveryResult.Failed("writer unavailable")
        )

        assertEquals(MessageStatus.FAILED, holder.uiState.globalMessages.last().status)
        assertEquals(1, holder.uiState.pendingOutgoingMessages.size)
        assertEquals(MessageStatus.QUEUED, holder.uiState.pendingOutgoingMessages.single().status)
        assertNotEquals(MessageStatus.DELIVERED, holder.uiState.globalMessages.last().status)
    }

    @Test
    fun selectingSecurePeerUpdatesStateWithoutClearingMeshResult() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )
        val queuedMessage = requireNotNull(holder.sendGlobalPreviewMessage("select peer"))
        holder.handleGlobalMeshDeliveryResult(
            messageId = queuedMessage.messageId,
            result = GlobalMeshDeliveryResult.NoReachablePeers
        )

        holder.selectSecurePeer(" peer-123 ")

        assertEquals("peer-123", holder.uiState.selectedSecurePeerId)
        assertEquals(
            GlobalMeshDeliveryResult.NoReachablePeers,
            holder.uiState.globalMeshDeliveryResult
        )
    }

    @Test
    fun clearingSecurePeerClearsSelectionWithoutClearingMeshResult() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )
        holder.selectSecurePeer("peer-123")
        val queuedMessage = requireNotNull(holder.sendGlobalPreviewMessage("clear peer"))
        holder.handleGlobalMeshDeliveryResult(
            messageId = queuedMessage.messageId,
            result = GlobalMeshDeliveryResult.SenderUnavailable
        )

        holder.clearSelectedSecurePeer()

        assertNull(holder.uiState.selectedSecurePeerId)
        assertEquals(
            GlobalMeshDeliveryResult.SenderUnavailable,
            holder.uiState.globalMeshDeliveryResult
        )
    }

    @Test
    fun addContactStoresItInStateWithMissingKeys() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )

        val contact = holder.addOrUpdateContact(
            canonicalPeerId = "peer-123",
            displayName = "Alex",
            lastSeenMillis = 1234L,
            hasSession = false
        )

        assertEquals(
            AuroraContact(
                canonicalPeerId = "peer-123",
                displayName = "Alex",
                createdAtMillis = contact.createdAtMillis,
                lastSeenMillis = 1234L,
                hasSession = false
            ),
            contact
        )
        assertEquals(listOf(contact), holder.uiState.contacts)
        assertEquals("Alex", holder.displayNameForPeerId("peer-123"))
    }

    @Test
    fun addingSameContactUpdatesInsteadOfDuplicating() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )
        val originalContact = holder.addOrUpdateContact(
            canonicalPeerId = "peer-123",
            displayName = "Alex",
            lastSeenMillis = 1000L,
            hasSession = false
        )

        val updatedContact = holder.addOrUpdateContact(
            canonicalPeerId = "peer-123",
            displayName = "Alexandra",
            lastSeenMillis = 2000L,
            hasSession = false
        )

        assertEquals(1, holder.uiState.contacts.size)
        assertEquals(originalContact.createdAtMillis, updatedContact.createdAtMillis)
        assertEquals("Alexandra", updatedContact.displayName)
        assertEquals(2000L, updatedContact.lastSeenMillis)
        assertEquals(false, updatedContact.hasSession)
    }

    @Test
    fun keyExchangeSuccessPromotesExistingContactToReady() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )
        holder.addOrUpdateContact(
            canonicalPeerId = "peer-123",
            displayName = "Alex",
            hasSession = false
        )

        val updatedContact = holder.addOrUpdateContact(
            canonicalPeerId = "peer-123",
            displayName = "Alex",
            hasSession = true
        )

        assertTrue(updatedContact.hasSession)
        assertTrue(holder.findContactByPeerId("peer-123")?.hasSession == true)
    }

    @Test
    fun failedKeyExchangeDoesNotFakeReadyContact() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )

        val contact = holder.addOrUpdateContact(
            canonicalPeerId = "peer-123",
            displayName = "Alex",
            hasSession = false
        )

        val updatedContact = holder.addOrUpdateContact(
            canonicalPeerId = "peer-123",
            displayName = "Alex",
            hasSession = false
        )

        assertEquals(false, contact.hasSession)
        assertEquals(false, updatedContact.hasSession)
        assertEquals(false, holder.findContactByPeerId("peer-123")?.hasSession)
    }

    @Test
    fun pendingOutgoingMessagesCanBeFilteredByThread() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )

        holder.sendGlobalPreviewMessage("first")
        holder.sendGlobalPreviewMessage("second")

        val globalQueue = holder.pendingOutgoingMessagesForThread("global")
        assertEquals(2, globalQueue.size)
        assertTrue(globalQueue.all { it.threadId == "global" })
    }

    @Test
    fun queuedOutgoingMessageKeepsUserLevelPlaintext() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )

        holder.sendGlobalPreviewMessage("plain user text")

        val queuedMessage = holder.uiState.pendingOutgoingMessages.last()
        assertEquals("plain user text", queuedMessage.userText)
        assertTrue(queuedMessage.messageId.startsWith("global-"))
    }

    @Test
    fun queuedOutgoingMessagesCanBeProjectedToProtocolDrafts() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )

        holder.sendGlobalPreviewMessage("protocol draft")

        val queuedMessage = holder.uiState.pendingOutgoingMessages.last()
        val draft = holder.pendingOutgoingMessageFrameDraftsForThread("global").single()

        assertEquals(1, holder.uiState.pendingOutgoingMessages.size)
        assertEquals(queuedMessage.messageId, draft.id)
        assertEquals(queuedMessage.threadId, draft.threadId)
        assertEquals(MessageFrameType.GLOBAL_TEXT, draft.type)
        assertEquals(queuedMessage.userText, draft.payload)
        assertEquals(MessageStatus.QUEUED, queuedMessage.status)
        assertNotEquals(MessageStatus.SENT, queuedMessage.status)
        assertNotEquals(MessageStatus.DELIVERED, queuedMessage.status)
    }

    @Test
    fun resolvingProtocolDraftDoesNotConsumeQueuedOutgoingMessage() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )

        holder.sendGlobalPreviewMessage("resolve later")

        val draft = holder.pendingOutgoingMessageFrameDraftsForThread("global").single()
        val queuedMessage = holder.uiState.pendingOutgoingMessages.single()
        val frame = OutgoingMessageFrameResolver.resolve(
            draft = draft,
            senderId = "sender-1"
        )

        assertEquals("sender-1", frame.senderId)
        assertEquals(1, holder.uiState.pendingOutgoingMessages.size)
        assertEquals(queuedMessage.messageId, holder.uiState.pendingOutgoingMessages.single().messageId)
        assertEquals(MessageStatus.QUEUED, holder.uiState.pendingOutgoingMessages.single().status)
    }

    @Test
    fun globalChatUsesGeneratedUsernameWhenToggleIsFalse() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1",
            customUsername = "John",
            useCustomUsernameInGlobalChat = true
        )

        holder.updateUseCustomUsernameInGlobalChat(false)
        holder.sendGlobalPreviewMessage("hello")

        assertEquals("PIAIUFN1", holder.uiState.globalChatUsername)
        assertEquals("PIAIUFN1", holder.uiState.globalMessages.last().senderName)
    }

    @Test
    fun privateProfileUsesCustomUsernameWhenAvailable() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1",
            customUsername = "John"
        )

        holder.sendPrivatePreviewMessage("alex", "hello")

        assertEquals("John", holder.uiState.privateProfileUsername)
        assertEquals("John", holder.privateMessagesForPeerId("alex").last().senderName)
    }

    @Test
    fun resetCreatesFreshGeneratedUsernameClearsCustomAndResetsToggle() {
        val store = FakeProfileStore()
        val holder = createHolder(
            store = store,
            generatedUsername = "PIAIUFN1",
            customUsername = "John",
            useCustomUsernameInGlobalChat = false,
            desiredAvailability = AuroraAvailabilityPreference.OFFLINE
        )

        holder.resetLocalData()

        assertTrue(GeneratedUsername.matchesFormat(holder.uiState.generatedUsername))
        assertNull(holder.uiState.customUsername)
        assertTrue(holder.uiState.useCustomUsernameInGlobalChat)
        assertEquals(AuroraAvailabilityPreference.OFFLINE, holder.uiState.desiredAvailability)
        assertEquals(holder.uiState.generatedUsername, store.generatedUsername)
        assertEquals(1, store.clearCalls)
    }

    @Test
    fun desiredAvailabilityCanBeUpdatedWithoutChangingProfileState() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1",
            customUsername = "John",
            useCustomUsernameInGlobalChat = false
        )

        holder.updateDesiredAvailability(AuroraAvailabilityPreference.OFFLINE)

        assertEquals(AuroraAvailabilityPreference.OFFLINE, holder.uiState.desiredAvailability)
        assertEquals("John", holder.uiState.customUsername)
        assertEquals("PIAIUFN1", holder.uiState.generatedUsername)
        assertEquals(false, holder.uiState.useCustomUsernameInGlobalChat)
    }

    @Test
    fun debugModeCanBeUpdatedWithoutChangingChatState() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )
        holder.sendGlobalPreviewMessage("hello")
        val messageCountBefore = holder.uiState.globalMessages.size

        holder.updateDebugMode(true)

        assertTrue(holder.uiState.isDebugModeEnabled)
        assertEquals(messageCountBefore, holder.uiState.globalMessages.size)
    }

    private fun createHolder(
        store: FakeProfileStore,
        generatedUsername: String,
        customUsername: String? = null,
        useCustomUsernameInGlobalChat: Boolean = true,
        desiredAvailability: AuroraAvailabilityPreference = AuroraAvailabilityPreference.ONLINE
    ): AuroraStateHolder {
        return AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = generatedUsername,
                customUsername = customUsername,
                useCustomUsernameInGlobalChat = useCustomUsernameInGlobalChat,
                desiredAvailability = desiredAvailability
            ),
            localProfileStore = store
        )
    }

    private class FakeProfileStore : LocalProfileSettingsStore {
        var generatedUsername: String? = null
        var customUsername: String? = null
        var useCustomUsernameInGlobalChat: Boolean = true
        var clearCalls: Int = 0

        override fun loadProfileSettings(): LocalProfileSettings {
            return LocalProfileSettings(
                generatedUsername = generatedUsername,
                customUsername = customUsername,
                useCustomUsernameInGlobalChat = useCustomUsernameInGlobalChat
            )
        }

        override fun saveGeneratedUsername(username: String) {
            generatedUsername = username
        }

        override fun saveCustomUsername(username: String?) {
            customUsername = username
        }

        override fun saveUseCustomUsernameInGlobalChat(enabled: Boolean) {
            useCustomUsernameInGlobalChat = enabled
        }

        override fun clearProfile() {
            clearCalls += 1
            generatedUsername = null
            customUsername = null
            useCustomUsernameInGlobalChat = true
        }
    }
}
