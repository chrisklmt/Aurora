package gr.hua.aurora.navigation

import gr.hua.aurora.data.LocalProfileSettings
import gr.hua.aurora.data.LocalProfileSettingsStore
import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.model.OutgoingChatMessage
import gr.hua.aurora.protocol.GlobalMeshDeliveryResult
import gr.hua.aurora.protocol.PrivateChatMessageSendResult
import gr.hua.aurora.state.AuroraStateHolder
import gr.hua.aurora.state.SampleAuroraState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavGraphSubmissionTest {
    @Test
    fun globalSubmitWrapsRuntimeExceptionsAsFailed() = runBlocking {
        val queuedMessage = sampleOutgoingMessage(threadId = "global")

        val result = submitGlobalQueuedMessage(
            queuedMessage = queuedMessage,
            currentUsername = { "Chris" },
            submitTransport = { _, _ ->
                error("writer unavailable")
            }
        )

        assertEquals(
            GlobalMeshDeliveryResult.Failed("writer unavailable"),
            result
        )
    }

    @Test
    fun globalSubmitAlsoInvokesWifiDirectDebugTransportWhenProvided() = runBlocking {
        val queuedMessage = sampleOutgoingMessage(threadId = "global")
        val wifiDirectInvocations = mutableListOf<Pair<String, String>>()

        val result = submitGlobalQueuedMessage(
            queuedMessage = queuedMessage,
            currentUsername = { "Chris" },
            submitTransport = { message, senderId ->
                assertEquals(queuedMessage, message)
                assertEquals("Chris", senderId)
                GlobalMeshDeliveryResult.QueuedToActivePeer("peer-123")
            },
            submitWifiDirectDebugTransport = { message, senderId ->
                wifiDirectInvocations += message.messageId to senderId
            }
        )

        assertEquals(
            GlobalMeshDeliveryResult.QueuedToActivePeer("peer-123"),
            result
        )
        assertEquals(listOf(queuedMessage.messageId to "Chris"), wifiDirectInvocations)
    }

    @Test
    fun globalSubmitDoesNotUseWifiDirectDebugTransportWhenNotProvided() = runBlocking {
        val queuedMessage = sampleOutgoingMessage(threadId = "global")
        var wifiDirectInvoked = false

        val result = submitGlobalQueuedMessage(
            queuedMessage = queuedMessage,
            currentUsername = { "Chris" },
            submitTransport = { _, _ ->
                GlobalMeshDeliveryResult.QueuedToActivePeer("peer-123")
            },
            submitWifiDirectDebugTransport = null
        )

        assertEquals(
            GlobalMeshDeliveryResult.QueuedToActivePeer("peer-123"),
            result
        )
        assertFalse(wifiDirectInvoked)
    }

    @Test
    fun globalSubmitDoesNotLetWifiDirectDebugFailuresOverrideBleResult() = runBlocking {
        val queuedMessage = sampleOutgoingMessage(threadId = "global")

        val result = submitGlobalQueuedMessage(
            queuedMessage = queuedMessage,
            currentUsername = { "Chris" },
            submitTransport = { _, _ ->
                GlobalMeshDeliveryResult.QueuedToActivePeer("peer-123")
            },
            submitWifiDirectDebugTransport = { _, _ ->
                error("wifi direct bridge unavailable")
            }
        )

        assertEquals(
            GlobalMeshDeliveryResult.QueuedToActivePeer("peer-123"),
            result
        )
    }

    @Test
    fun globalSubmitKeepsBleFailureEvenWhenWifiDirectDebugCopySucceeds() = runBlocking {
        val queuedMessage = sampleOutgoingMessage(threadId = "global")
        var wifiDirectInvoked = false

        val result = submitGlobalQueuedMessage(
            queuedMessage = queuedMessage,
            currentUsername = { "Chris" },
            submitTransport = { _, _ ->
                GlobalMeshDeliveryResult.Failed("ble sender unavailable")
            },
            submitWifiDirectDebugTransport = { message, senderId ->
                wifiDirectInvoked = true
                assertEquals(queuedMessage.messageId, message.messageId)
                assertEquals("Chris", senderId)
            }
        )

        assertTrue(wifiDirectInvoked)
        assertEquals(
            GlobalMeshDeliveryResult.Failed("ble sender unavailable"),
            result
        )
    }

    @Test
    fun globalSubmitWithWifiDirectDebugCopyDoesNotCreateSecondLocalBubble() = runBlocking {
        val holder = createHolder()
        val queuedMessage = requireNotNull(holder.sendGlobalPreviewMessage("hello mesh"))
        val visibleCountBeforeSubmit = holder.uiState.globalMessages.size

        val result = submitGlobalQueuedMessage(
            queuedMessage = queuedMessage,
            currentUsername = { "Chris" },
            submitTransport = { _, _ ->
                GlobalMeshDeliveryResult.QueuedToActivePeer("peer-123")
            },
            submitWifiDirectDebugTransport = { message, senderId ->
                assertEquals(queuedMessage.messageId, message.messageId)
                assertEquals("Chris", senderId)
            }
        )

        assertEquals(
            GlobalMeshDeliveryResult.QueuedToActivePeer("peer-123"),
            result
        )
        assertEquals(
            1,
            holder.uiState.globalMessages.count { it.id == queuedMessage.messageId }
        )
        assertEquals(visibleCountBeforeSubmit, holder.uiState.globalMessages.size)
        assertNotEquals(MessageStatus.DELIVERED, holder.uiState.globalMessages.last().status)
    }

    @Test
    fun privateSubmitReturnsKeysUnavailableWhenPrivateChatIdIsMissing() = runBlocking {
        val queuedMessage = sampleOutgoingMessage(threadId = "private:alex")

        val result = submitPrivateQueuedMessage(
            queuedMessage = queuedMessage,
            peerId = "alex",
            currentUsername = { "Chris" },
            resolvePrivateChatId = { null },
            submitTransport = { _, _, _ ->
                PrivateChatMessageSendResult.SubmittedLocally
            }
        )

        assertEquals(PrivateChatMessageSendResult.KeysUnavailable, result)
    }

    @Test
    fun privateSubmitReResolvesCurrentPrivateChatIdEachTime() = runBlocking {
        val queuedMessage = sampleOutgoingMessage(threadId = "private:alex")
        var currentPrivateChatId = "chat-old"
        val submittedChatIds = mutableListOf<String>()

        submitPrivateQueuedMessage(
            queuedMessage = queuedMessage,
            peerId = "alex",
            currentUsername = { "Chris" },
            resolvePrivateChatId = { currentPrivateChatId },
            submitTransport = { _, _, privateChatId ->
                submittedChatIds += privateChatId
                PrivateChatMessageSendResult.SubmittedLocally
            }
        )

        currentPrivateChatId = "chat-new"

        submitPrivateQueuedMessage(
            queuedMessage = queuedMessage,
            peerId = "alex",
            currentUsername = { "Chris" },
            resolvePrivateChatId = { currentPrivateChatId },
            submitTransport = { _, _, privateChatId ->
                submittedChatIds += privateChatId
                PrivateChatMessageSendResult.SubmittedLocally
            }
        )

        assertEquals(listOf("chat-old", "chat-new"), submittedChatIds)
    }

    @Test
    fun privateSubmitWrapsRuntimeExceptionsAsFailed() = runBlocking {
        val queuedMessage = sampleOutgoingMessage(threadId = "private:alex")

        val result = submitPrivateQueuedMessage(
            queuedMessage = queuedMessage,
            peerId = "alex",
            currentUsername = { "Chris" },
            resolvePrivateChatId = { "chat-alex" },
            submitTransport = { _, _, _ ->
                error("transport submission failed")
            }
        )

        assertTrue(result is PrivateChatMessageSendResult.Failed)
        assertEquals(
            "transport submission failed",
            requireNotNull((result as PrivateChatMessageSendResult.Failed).reason)
        )
    }

    private fun sampleOutgoingMessage(
        threadId: String
    ): OutgoingChatMessage {
        return OutgoingChatMessage(
            messageId = "$threadId-msg-1",
            threadId = threadId,
            userText = "hello",
            createdAtMillis = 1_000L,
            status = MessageStatus.FAILED
        )
    }

    private fun createHolder(): AuroraStateHolder {
        return AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
    }

    private class FakeProfileStore : LocalProfileSettingsStore {
        override fun loadProfileSettings(): LocalProfileSettings {
            return LocalProfileSettings(
                generatedUsername = "PIAIUFN1",
                customUsername = null,
                useCustomUsernameInGlobalChat = true
            )
        }

        override fun saveGeneratedUsername(username: String) = Unit

        override fun saveCustomUsername(username: String?) = Unit

        override fun saveUseCustomUsernameInGlobalChat(enabled: Boolean) = Unit

        override fun clearProfile() = Unit
    }
}
