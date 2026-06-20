package gr.hua.aurora.state

import gr.hua.aurora.data.GeneratedUsername
import gr.hua.aurora.data.LocalProfileSettings
import gr.hua.aurora.data.LocalProfileSettingsStore
import gr.hua.aurora.model.MessageStatus
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
        assertEquals(AuroraAvailabilityPreference.ONLINE, state.desiredAvailability)
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
    fun globalSendAppendsLocalOnlyMessage() {
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
        assertEquals(MessageStatus.LOCAL_ONLY, appendedMessage.status)
        assertNotEquals(MessageStatus.SENT, appendedMessage.status)
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
