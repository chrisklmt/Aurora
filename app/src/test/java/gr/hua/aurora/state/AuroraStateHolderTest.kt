package gr.hua.aurora.state

import gr.hua.aurora.data.GeneratedUsername
import gr.hua.aurora.data.LocalProfileSettings
import gr.hua.aurora.data.LocalProfileSettingsStore
import gr.hua.aurora.data.persistence.InMemoryAuroraPersistenceStore
import gr.hua.aurora.model.AuroraContact
import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.model.PrivateChatIdentity
import gr.hua.aurora.protocol.GlobalMeshDeliveryResult
import gr.hua.aurora.protocol.MessageFrameType
import gr.hua.aurora.protocol.OutgoingMessageFrameResolver
import gr.hua.aurora.protocol.PrivateChatMessageSendResult
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
        assertEquals(MessageStatus.SENT, holder.uiState.pendingOutgoingMessages.single().status)
        assertNotEquals(MessageStatus.DELIVERED, visibleMessage.status)
    }

    @Test
    fun missingSessionMeshDeliveryMarksVisibleGlobalMessageFailedButRetryable() {
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
        assertEquals(MessageStatus.FAILED, visibleMessage.status)
        assertEquals(
            GlobalMeshDeliveryResult.SenderUnavailable,
            holder.uiState.globalMeshDeliveryResult
        )
        assertEquals(1, holder.uiState.pendingOutgoingMessages.size)
        assertEquals(MessageStatus.FAILED, holder.uiState.pendingOutgoingMessages.single().status)
    }

    @Test
    fun noReachablePeerMeshDeliveryMarksVisibleGlobalMessageFailedButRetryable() {
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
        assertEquals(MessageStatus.FAILED, visibleMessage.status)
        assertEquals(
            GlobalMeshDeliveryResult.NoReachablePeers,
            holder.uiState.globalMeshDeliveryResult
        )
        assertEquals(1, holder.uiState.pendingOutgoingMessages.size)
        assertEquals(MessageStatus.FAILED, holder.uiState.pendingOutgoingMessages.single().status)
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
        assertEquals(MessageStatus.FAILED, holder.uiState.pendingOutgoingMessages.single().status)
        assertNotEquals(MessageStatus.DELIVERED, holder.uiState.globalMessages.last().status)
    }

    @Test
    fun globalRetryReusesSameMessageWithoutDuplicatingBubble() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )

        val queuedMessage = requireNotNull(holder.sendGlobalPreviewMessage("retry me"))
        holder.handleGlobalMeshDeliveryResult(
            messageId = queuedMessage.messageId,
            result = GlobalMeshDeliveryResult.NoReachablePeers
        )

        val retriedMessage = requireNotNull(holder.retryGlobalOutgoingMessage(queuedMessage.messageId))

        assertEquals(1, holder.uiState.globalMessages.size)
        assertEquals(queuedMessage.messageId, retriedMessage.messageId)
        assertEquals(MessageStatus.QUEUED, holder.uiState.globalMessages.single().status)
        assertEquals(MessageStatus.QUEUED, holder.uiState.pendingOutgoingMessages.single().status)
        assertNull(holder.uiState.globalMeshDeliveryResult)
    }

    @Test
    fun globalRetryCanPromoteSameMessageToSentOnLaterSuccess() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )

        val queuedMessage = requireNotNull(holder.sendGlobalPreviewMessage("retry success"))
        holder.handleGlobalMeshDeliveryResult(
            messageId = queuedMessage.messageId,
            result = GlobalMeshDeliveryResult.SenderUnavailable
        )
        requireNotNull(holder.retryGlobalOutgoingMessage(queuedMessage.messageId))

        holder.handleGlobalMeshDeliveryResult(
            messageId = queuedMessage.messageId,
            result = GlobalMeshDeliveryResult.QueuedToActivePeer("peer-123")
        )

        assertEquals(1, holder.uiState.globalMessages.size)
        assertEquals(MessageStatus.SENT, holder.uiState.globalMessages.single().status)
        assertEquals(MessageStatus.SENT, holder.uiState.pendingOutgoingMessages.single().status)
    }

    @Test
    fun globalRetryCanFailAgainWithoutDuplicatingBubble() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )

        val queuedMessage = requireNotNull(holder.sendGlobalPreviewMessage("retry fail again"))
        holder.handleGlobalMeshDeliveryResult(
            messageId = queuedMessage.messageId,
            result = GlobalMeshDeliveryResult.NoReachablePeers
        )
        requireNotNull(holder.retryGlobalOutgoingMessage(queuedMessage.messageId))

        holder.handleGlobalMeshDeliveryResult(
            messageId = queuedMessage.messageId,
            result = GlobalMeshDeliveryResult.ConnectOnSendFailed(
                peerId = "peer-123",
                reason = "connection did not reach ready state"
            )
        )

        assertEquals(1, holder.uiState.globalMessages.size)
        assertEquals(MessageStatus.FAILED, holder.uiState.globalMessages.single().status)
        assertEquals(MessageStatus.FAILED, holder.uiState.pendingOutgoingMessages.single().status)
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
    fun refreshingContactLastSeenKeepsKnownContactWithoutFakingReadyState() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )
        val originalContact = holder.addOrUpdateContact(
            canonicalPeerId = "peer-123",
            displayName = "Alex",
            lastSeenMillis = 1_000L,
            hasSession = false
        )

        val refreshedContact = holder.refreshContactLastSeen(
            canonicalPeerId = "peer-123",
            lastSeenMillis = 2_000L
        )

        assertEquals(originalContact.createdAtMillis, refreshedContact?.createdAtMillis)
        assertEquals(2_000L, refreshedContact?.lastSeenMillis)
        assertEquals(false, refreshedContact?.hasSession)
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

        prepareReadyPrivateChat(holder, peerId = "alex")
        holder.sendPrivateChatMessage("alex", "hello")

        assertEquals("John", holder.uiState.privateProfileUsername)
        assertEquals("John", holder.privateMessagesForPeerId("alex").last().senderName)
    }

    @Test
    fun privateSendAppendsOnlyToSelectedPrivateChatAndQueuesOutgoingMessage() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )

        prepareReadyPrivateChat(holder, peerId = "alex")
        val queuedMessage = requireNotNull(holder.sendPrivateChatMessage("alex", " hello "))

        val privateMessages = holder.privateMessagesForPeerId("alex")
        assertEquals(1, privateMessages.size)
        assertTrue(holder.uiState.globalMessages.isEmpty())
        assertEquals("private:alex", privateMessages.single().threadId)
        assertEquals("hello", privateMessages.single().text)
        assertEquals(MessageStatus.QUEUED, privateMessages.single().status)
        assertEquals(queuedMessage.messageId, holder.uiState.pendingOutgoingMessages.single().messageId)
        assertEquals("private:alex", holder.uiState.pendingOutgoingMessages.single().threadId)
        assertNotEquals(MessageStatus.DELIVERED, privateMessages.single().status)
    }

    @Test
    fun privateSendMarkedSubmittedLocallyPromotesVisibleMessageToSentWithoutConsumingQueue() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )
        prepareReadyPrivateChat(holder, peerId = "alex")
        val queuedMessage = requireNotNull(holder.sendPrivateChatMessage("alex", "hello"))

        holder.handlePrivateChatDeliveryResult(
            peerId = "alex",
            messageId = queuedMessage.messageId,
            result = PrivateChatMessageSendResult.SubmittedLocally
        )

        val visibleMessage = holder.privateMessagesForPeerId("alex").single()
        assertEquals(MessageStatus.SENT, visibleMessage.status)
        assertEquals(
            PrivateChatMessageSendResult.SubmittedLocally,
            holder.latestPrivateChatDeliveryResultForPeerId("alex")
        )
        assertEquals(1, holder.uiState.pendingOutgoingMessages.size)
        assertEquals(MessageStatus.SENT, holder.uiState.pendingOutgoingMessages.single().status)
        assertNotEquals(MessageStatus.DELIVERED, visibleMessage.status)
    }

    @Test
    fun privateSendMarkedMissingKeysFailsVisibleMessageWithoutConsumingQueue() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )
        prepareReadyPrivateChat(holder, peerId = "alex")
        val queuedMessage = requireNotNull(holder.sendPrivateChatMessage("alex", "hello"))

        holder.handlePrivateChatDeliveryResult(
            peerId = "alex",
            messageId = queuedMessage.messageId,
            result = PrivateChatMessageSendResult.KeysUnavailable
        )

        val visibleMessage = holder.privateMessagesForPeerId("alex").single()
        assertEquals(MessageStatus.FAILED, visibleMessage.status)
        assertEquals(
            PrivateChatMessageSendResult.KeysUnavailable,
            holder.latestPrivateChatDeliveryResultForPeerId("alex")
        )
        assertEquals(1, holder.uiState.pendingOutgoingMessages.size)
        assertEquals(MessageStatus.FAILED, holder.uiState.pendingOutgoingMessages.single().status)
    }

    @Test
    fun privateSendMarkedUnreachableFailsVisibleMessageWithoutLeakingToGlobalChat() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )
        prepareReadyPrivateChat(holder, peerId = "alex")
        val queuedMessage = requireNotNull(holder.sendPrivateChatMessage("alex", "hello"))

        holder.handlePrivateChatDeliveryResult(
            peerId = "alex",
            messageId = queuedMessage.messageId,
            result = PrivateChatMessageSendResult.ContactNotReachable
        )

        assertTrue(holder.uiState.globalMessages.isEmpty())
        assertEquals(MessageStatus.FAILED, holder.privateMessagesForPeerId("alex").single().status)
        assertEquals(
            PrivateChatMessageSendResult.ContactNotReachable,
            holder.latestPrivateChatDeliveryResultForPeerId("alex")
        )
        assertEquals(MessageStatus.FAILED, holder.uiState.pendingOutgoingMessages.single().status)
    }

    @Test
    fun privateSendWithoutReadySessionCanStillFailCleanlyInsteadOfStayingPendingForever() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )
        holder.addOrUpdateContact(
            canonicalPeerId = "alex",
            displayName = "Alex",
            hasSession = false
        )
        val queuedMessage = requireNotNull(holder.sendPrivateChatMessage("alex", "hello"))

        holder.handlePrivateChatDeliveryResult(
            peerId = "alex",
            messageId = queuedMessage.messageId,
            result = PrivateChatMessageSendResult.KeysUnavailable
        )

        assertEquals(MessageStatus.FAILED, holder.privateMessagesForPeerId("alex").single().status)
        assertEquals(MessageStatus.FAILED, holder.uiState.pendingOutgoingMessages.single().status)
    }

    @Test
    fun privateRetryReusesSameMessageWithoutDuplicatingBubble() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )
        prepareReadyPrivateChat(holder, peerId = "alex")
        val queuedMessage = requireNotNull(holder.sendPrivateChatMessage("alex", "hello"))
        holder.handlePrivateChatDeliveryResult(
            peerId = "alex",
            messageId = queuedMessage.messageId,
            result = PrivateChatMessageSendResult.ContactNotReachable
        )

        val retriedMessage = requireNotNull(holder.retryPrivateChatOutgoingMessage("alex", queuedMessage.messageId))

        assertEquals(1, holder.privateMessagesForPeerId("alex").size)
        assertEquals(queuedMessage.messageId, retriedMessage.messageId)
        assertEquals(MessageStatus.QUEUED, holder.privateMessagesForPeerId("alex").single().status)
        assertEquals(MessageStatus.QUEUED, holder.uiState.pendingOutgoingMessages.single().status)
        assertNull(holder.latestPrivateChatDeliveryResultForPeerId("alex"))
    }

    @Test
    fun privateRetryCanPromoteSameMessageToSentAfterRecovery() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )
        prepareReadyPrivateChat(holder, peerId = "alex")
        val queuedMessage = requireNotNull(holder.sendPrivateChatMessage("alex", "hello"))
        holder.handlePrivateChatDeliveryResult(
            peerId = "alex",
            messageId = queuedMessage.messageId,
            result = PrivateChatMessageSendResult.ContactNotReachable
        )
        requireNotNull(holder.retryPrivateChatOutgoingMessage("alex", queuedMessage.messageId))

        holder.handlePrivateChatDeliveryResult(
            peerId = "alex",
            messageId = queuedMessage.messageId,
            result = PrivateChatMessageSendResult.SubmittedLocally
        )

        assertEquals(1, holder.privateMessagesForPeerId("alex").size)
        assertEquals(MessageStatus.SENT, holder.privateMessagesForPeerId("alex").single().status)
        assertEquals(MessageStatus.SENT, holder.uiState.pendingOutgoingMessages.single().status)
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
    fun resetClearsContactsMessagesQueuesAndSelections() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )
        holder.addOrUpdateContact(
            canonicalPeerId = "peer-123",
            displayName = "Alex",
            hasSession = true
        )
        holder.recordReceivedPrivateChatProposal(
            peerId = "peer-123",
            remoteProposalId = "remote-peer-123"
        )
        holder.selectSecurePeer("peer-123")
        val globalQueuedMessage = requireNotNull(holder.sendGlobalPreviewMessage("hello global"))
        val privateQueuedMessage = requireNotNull(holder.sendPrivateChatMessage("peer-123", "hello private"))
        holder.handleGlobalMeshDeliveryResult(
            messageId = globalQueuedMessage.messageId,
            result = GlobalMeshDeliveryResult.NoReachablePeers
        )
        holder.handlePrivateChatDeliveryResult(
            peerId = "peer-123",
            messageId = privateQueuedMessage.messageId,
            result = PrivateChatMessageSendResult.ContactNotReachable
        )

        holder.resetLocalData()

        assertTrue(holder.uiState.contacts.isEmpty())
        assertTrue(holder.uiState.globalMessages.isEmpty())
        assertTrue(holder.uiState.privateMessagesByPeerId.isEmpty())
        assertTrue(holder.uiState.pendingOutgoingMessages.isEmpty())
        assertNull(holder.uiState.selectedSecurePeerId)
        assertNull(holder.uiState.globalMeshDeliveryResult)
        assertTrue(holder.uiState.privateChatDeliveryResultsByPeerId.isEmpty())
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

    @Test
    fun contactsSurviveStateRecreationThroughPersistenceWithoutFakeReadyState() {
        val profileStore = FakeProfileStore().apply {
            generatedUsername = "PIAIUFN1"
        }
        val persistenceStore = InMemoryAuroraPersistenceStore()
        val holder = createAuroraStateHolder(
            localProfileStore = profileStore,
            persistenceStore = persistenceStore
        )

        holder.addOrUpdateContact(
            canonicalPeerId = "peer-123",
            displayName = "Alex",
            lastSeenMillis = 2_000L,
            hasSession = true
        )

        val restoredHolder = createAuroraStateHolder(
            localProfileStore = profileStore,
            persistenceStore = persistenceStore
        )
        val restoredContact = restoredHolder.findContactByPeerId("peer-123")

        assertEquals("Alex", restoredContact?.displayName)
        assertEquals(2_000L, restoredContact?.lastSeenMillis)
        assertEquals(false, restoredContact?.hasSession)
    }

    @Test
    fun privateChatIdentityAndCustomNameSurviveRestartWithoutFakeReadyState() {
        val profileStore = FakeProfileStore().apply {
            generatedUsername = "PIAIUFN1"
        }
        val persistenceStore = InMemoryAuroraPersistenceStore()
        val holder = createAuroraStateHolder(
            localProfileStore = profileStore,
            persistenceStore = persistenceStore
        )
        val establishedIdentity = prepareReadyPrivateChat(holder, peerId = "peer-123")
        holder.renamePrivateChat("peer-123", "Family chat")

        val restoredHolder = createAuroraStateHolder(
            localProfileStore = profileStore,
            persistenceStore = persistenceStore
        )
        val restoredIdentity = restoredHolder.privateChatIdentityForPeerId("peer-123")

        assertEquals(establishedIdentity.privateChatId, restoredIdentity?.privateChatId)
        assertEquals(establishedIdentity.localProposalId, restoredIdentity?.localProposalId)
        assertEquals(establishedIdentity.remoteProposalId, restoredIdentity?.remoteProposalId)
        assertEquals("Family chat", restoredIdentity?.customChatName)
        assertEquals("Family chat", restoredHolder.displayNameForPeerId("peer-123"))
        assertEquals(false, restoredHolder.findContactByPeerId("peer-123")?.hasSession)
        assertEquals(false, restoredHolder.isPrivateChatReadyForPeerId("peer-123"))
    }

    @Test
    fun globalAndPrivateMessagesSurviveStateRecreationWithRetryableOutboxRestored() {
        val profileStore = FakeProfileStore().apply {
            generatedUsername = "PIAIUFN1"
        }
        val persistenceStore = InMemoryAuroraPersistenceStore()
        val holder = createAuroraStateHolder(
            localProfileStore = profileStore,
            persistenceStore = persistenceStore
        )

        holder.sendGlobalPreviewMessage("hello global")
        prepareReadyPrivateChat(holder, peerId = "peer-123")
        holder.sendPrivateChatMessage("peer-123", "hello private")

        val restoredHolder = createAuroraStateHolder(
            localProfileStore = profileStore,
            persistenceStore = persistenceStore
        )

        assertEquals(1, restoredHolder.uiState.globalMessages.size)
        assertEquals("hello global", restoredHolder.uiState.globalMessages.single().text)
        assertEquals(MessageStatus.FAILED, restoredHolder.uiState.globalMessages.single().status)
        assertEquals(1, restoredHolder.privateMessagesForPeerId("peer-123").size)
        assertEquals("hello private", restoredHolder.privateMessagesForPeerId("peer-123").single().text)
        assertEquals(MessageStatus.FAILED, restoredHolder.privateMessagesForPeerId("peer-123").single().status)
        assertEquals(
            listOf("global", "private:peer-123"),
            restoredHolder.uiState.pendingOutgoingMessages.map { it.threadId }
        )
        assertTrue(restoredHolder.uiState.pendingOutgoingMessages.all { it.status == MessageStatus.FAILED })
        assertTrue(restoredHolder.uiState.globalMessages.none { it.status == MessageStatus.DELIVERED })
        assertTrue(restoredHolder.privateMessagesForPeerId("peer-123").none { it.status == MessageStatus.DELIVERED })
    }

    @Test
    fun deletingPrivateChatClearsHistoryIdentityAndLeavesKnownContact() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )
        prepareReadyPrivateChat(holder, peerId = "peer-123")
        requireNotNull(holder.sendPrivateChatMessage("peer-123", "hello private"))
        holder.renamePrivateChat("peer-123", "Family chat")

        holder.deletePrivateChat("peer-123")

        assertTrue(holder.privateMessagesForPeerId("peer-123").isEmpty())
        assertNull(holder.privateChatIdentityForPeerId("peer-123"))
        assertNull(holder.latestPrivateChatDeliveryResultForPeerId("peer-123"))
        assertTrue(holder.uiState.pendingOutgoingMessages.none { it.threadId == "private:peer-123" })
        assertEquals("Alex", holder.findContactByPeerId("peer-123")?.displayName)
        assertEquals(false, holder.isPrivateChatReadyForPeerId("peer-123"))
    }

    @Test
    fun closingAndRecreatingAppDoesNotClearPersistedData() {
        val profileStore = FakeProfileStore().apply {
            generatedUsername = "PIAIUFN1"
        }
        val persistenceStore = InMemoryAuroraPersistenceStore()
        val firstHolder = createAuroraStateHolder(
            localProfileStore = profileStore,
            persistenceStore = persistenceStore
        )

        firstHolder.addOrUpdateContact(
            canonicalPeerId = "peer-123",
            displayName = "Alex"
        )
        firstHolder.sendGlobalPreviewMessage("hello again")

        val secondHolder = createAuroraStateHolder(
            localProfileStore = profileStore,
            persistenceStore = persistenceStore
        )

        assertEquals(1, secondHolder.uiState.contacts.size)
        assertEquals(1, secondHolder.uiState.globalMessages.size)
        assertEquals("hello again", secondHolder.uiState.globalMessages.single().text)
        assertEquals("PIAIUFN1", secondHolder.uiState.generatedUsername)
    }

    @Test
    fun clearLocalDataClearsPersistedContactsAndMessages() {
        val profileStore = FakeProfileStore().apply {
            generatedUsername = "PIAIUFN1"
        }
        val persistenceStore = InMemoryAuroraPersistenceStore()
        val holder = createAuroraStateHolder(
            localProfileStore = profileStore,
            persistenceStore = persistenceStore
        )
        holder.addOrUpdateContact(
            canonicalPeerId = "peer-123",
            displayName = "Alex"
        )
        holder.sendGlobalPreviewMessage("hello")
        prepareReadyPrivateChat(holder, peerId = "peer-123")
        holder.sendPrivateChatMessage("peer-123", "private")

        holder.resetLocalData()

        val restoredHolder = createAuroraStateHolder(
            localProfileStore = profileStore,
            persistenceStore = persistenceStore
        )

        assertTrue(restoredHolder.uiState.contacts.isEmpty())
        assertTrue(restoredHolder.uiState.globalMessages.isEmpty())
        assertTrue(restoredHolder.uiState.privateMessagesByPeerId.isEmpty())
        assertTrue(restoredHolder.uiState.pendingOutgoingMessages.isEmpty())
    }

    @Test
    fun removeMessagesByIdsRemovesOnlyExactKnownIdsAndKeepsMarkerLookingUserMessages() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )
        val markerLookingGlobal = requireNotNull(
            holder.sendGlobalPreviewMessage("AURORA_DIAG|user|text|that|must|stay")
        )
        val removableGlobal = requireNotNull(
            holder.sendGlobalPreviewMessage("phase 3 removable global")
        )
        prepareReadyPrivateChat(holder, peerId = "peer-123")
        val removablePrivate = requireNotNull(
            holder.sendPrivateChatMessage("peer-123", "phase 3 removable private")
        )
        val markerLookingPrivate = requireNotNull(
            holder.sendPrivateChatMessage("peer-123", "AURORA_DIAG|user|private|text|must|stay")
        )

        val removedIds = holder.removeMessagesByIds(
            setOf(removableGlobal.messageId, removablePrivate.messageId)
        )

        assertEquals(
            setOf(removableGlobal.messageId, removablePrivate.messageId),
            removedIds
        )
        assertTrue(holder.uiState.globalMessages.any { it.id == markerLookingGlobal.messageId })
        assertTrue(holder.uiState.globalMessages.none { it.id == removableGlobal.messageId })
        assertTrue(
            holder.privateMessagesForPeerId("peer-123").any { it.id == markerLookingPrivate.messageId }
        )
        assertTrue(
            holder.privateMessagesForPeerId("peer-123").none { it.id == removablePrivate.messageId }
        )
        assertTrue(
            holder.uiState.pendingOutgoingMessages.any { it.messageId == markerLookingGlobal.messageId }
        )
        assertTrue(
            holder.uiState.pendingOutgoingMessages.any { it.messageId == markerLookingPrivate.messageId }
        )
        assertTrue(
            holder.uiState.pendingOutgoingMessages.none { pending ->
                pending.messageId == removableGlobal.messageId ||
                    pending.messageId == removablePrivate.messageId
            }
        )
    }

    @Test
    fun delayedMessagesAreOrderedByTimestampThenMessageId() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )

        holder.ingestIncomingTransportMessage(
            gr.hua.aurora.protocol.IncomingTransportMessage(
                frame = gr.hua.aurora.protocol.MessageFrame(
                    id = "msg-2",
                    type = MessageFrameType.GLOBAL_TEXT,
                    senderId = "peer-late",
                    createdAtMillis = 2_000L,
                    payload = "newer"
                ),
                senderPublicKey = byteArrayOf(1, 2, 3)
            )
        )
        holder.ingestIncomingTransportMessage(
            gr.hua.aurora.protocol.IncomingTransportMessage(
                frame = gr.hua.aurora.protocol.MessageFrame(
                    id = "msg-1",
                    type = MessageFrameType.GLOBAL_TEXT,
                    senderId = "peer-early",
                    createdAtMillis = 1_000L,
                    payload = "older"
                ),
                senderPublicKey = byteArrayOf(1, 2, 4)
            )
        )

        assertEquals(
            listOf("msg-1", "msg-2"),
            holder.uiState.globalMessages.map { it.id }
        )
    }

    @Test
    fun localUsernameChangesAffectFuturePrivateMessagesOnlyAfterRestore() {
        val profileStore = FakeProfileStore().apply {
            generatedUsername = "PIAIUFN1"
        }
        val persistenceStore = InMemoryAuroraPersistenceStore()
        val holder = createAuroraStateHolder(
            localProfileStore = profileStore,
            persistenceStore = persistenceStore
        )

        holder.updateUsername("Alex")
        prepareReadyPrivateChat(holder, peerId = "peer-123")
        holder.sendPrivateChatMessage("peer-123", "first")
        holder.updateUsername("Maria")
        holder.sendPrivateChatMessage("peer-123", "second")

        val restoredHolder = createAuroraStateHolder(
            localProfileStore = profileStore,
            persistenceStore = persistenceStore
        )
        val restoredNames = restoredHolder.privateMessagesForPeerId("peer-123")
            .map { it.senderName }

        assertEquals(listOf("Alex", "Maria"), restoredNames)
    }

    @Test
    fun incomingPrivateMessageUpdatesContactDisplayNameWithoutRenamingOlderMessages() {
        val profileStore = FakeProfileStore().apply {
            generatedUsername = "PIAIUFN1"
        }
        val persistenceStore = InMemoryAuroraPersistenceStore()
        val holder = createAuroraStateHolder(
            localProfileStore = profileStore,
            persistenceStore = persistenceStore
        )
        prepareReadyPrivateChat(holder, peerId = "peer-123", displayName = "Aurora device 10325476")
        holder.ingestIncomingTransportMessage(
            gr.hua.aurora.protocol.IncomingTransportMessage(
                frame = gr.hua.aurora.protocol.MessageFrame(
                    id = "private-1",
                    type = MessageFrameType.PRIVATE_TEXT,
                    senderId = "peer-123",
                    recipientId = "self",
                    createdAtMillis = 1_000L,
                    payload = gr.hua.aurora.protocol.PrivateChatMessagePayloadCodec.encode(
                        gr.hua.aurora.protocol.PrivateChatMessagePayload(
                            privateChatId = requireNotNull(
                                holder.privateChatIdentityForPeerId("peer-123")?.privateChatId
                            ),
                            senderUsername = "Alex",
                            body = "hello"
                        )
                    )
                ),
                senderPublicKey = byteArrayOf(1, 2, 3, 4)
            )
        )
        holder.ingestIncomingTransportMessage(
            gr.hua.aurora.protocol.IncomingTransportMessage(
                frame = gr.hua.aurora.protocol.MessageFrame(
                    id = "private-2",
                    type = MessageFrameType.PRIVATE_TEXT,
                    senderId = "peer-123",
                    recipientId = "self",
                    createdAtMillis = 2_000L,
                    payload = gr.hua.aurora.protocol.PrivateChatMessagePayloadCodec.encode(
                        gr.hua.aurora.protocol.PrivateChatMessagePayload(
                            privateChatId = requireNotNull(
                                holder.privateChatIdentityForPeerId("peer-123")?.privateChatId
                            ),
                            senderUsername = "Maria",
                            body = "hi again"
                        )
                    )
                ),
                senderPublicKey = byteArrayOf(1, 2, 3, 5)
            )
        )

        val restoredHolder = createAuroraStateHolder(
            localProfileStore = profileStore,
            persistenceStore = persistenceStore
        )
        val restoredMessages = restoredHolder.privateMessagesForPeerId("peer-123")

        assertEquals(listOf("Alex", "Maria"), restoredMessages.map { it.senderName })
        assertEquals("Maria", restoredHolder.findContactByPeerId("peer-123")?.displayName)
    }

    @Test
    fun customChatNameWinsOverIncomingRemoteUsernameUpdates() {
        val holder = createHolder(
            store = FakeProfileStore(),
            generatedUsername = "PIAIUFN1"
        )
        val establishedIdentity = prepareReadyPrivateChat(holder, peerId = "peer-123", displayName = "Alex")
        holder.renamePrivateChat("peer-123", "Family chat")

        holder.ingestIncomingTransportMessage(
            gr.hua.aurora.protocol.IncomingTransportMessage(
                frame = gr.hua.aurora.protocol.MessageFrame(
                    id = "private-custom-1",
                    type = MessageFrameType.PRIVATE_TEXT,
                    senderId = "peer-123",
                    recipientId = "self",
                    createdAtMillis = 3_000L,
                    payload = gr.hua.aurora.protocol.PrivateChatMessagePayloadCodec.encode(
                        gr.hua.aurora.protocol.PrivateChatMessagePayload(
                            privateChatId = requireNotNull(establishedIdentity.privateChatId),
                            senderUsername = "Maria",
                            body = "hello after rename"
                        )
                    )
                ),
                senderPublicKey = byteArrayOf(1, 2, 3, 6)
            )
        )

        assertEquals("Family chat", holder.displayNameForPeerId("peer-123"))
        assertEquals("Maria", holder.findContactByPeerId("peer-123")?.displayName)
        assertEquals("Maria", holder.privateMessagesForPeerId("peer-123").single().senderName)
    }

    private fun createHolder(
        store: FakeProfileStore,
        generatedUsername: String,
        customUsername: String? = null,
        useCustomUsernameInGlobalChat: Boolean = true,
        desiredAvailability: AuroraAvailabilityPreference = AuroraAvailabilityPreference.ONLINE,
        persistenceStore: InMemoryAuroraPersistenceStore? = null
    ): AuroraStateHolder {
        return AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = generatedUsername,
                customUsername = customUsername,
                useCustomUsernameInGlobalChat = useCustomUsernameInGlobalChat,
                desiredAvailability = desiredAvailability
            ),
            localProfileStore = store,
            persistenceStore = persistenceStore
        )
    }

    private fun prepareReadyPrivateChat(
        holder: AuroraStateHolder,
        peerId: String,
        displayName: String = "Alex"
    ): PrivateChatIdentity {
        holder.addOrUpdateContact(
            canonicalPeerId = peerId,
            displayName = displayName,
            hasSession = true
        )
        return requireNotNull(
            holder.recordReceivedPrivateChatProposal(
                peerId = peerId,
                remoteProposalId = "remote-$peerId"
            )
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
