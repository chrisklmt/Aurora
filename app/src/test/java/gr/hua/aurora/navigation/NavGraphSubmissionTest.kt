package gr.hua.aurora.navigation

import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.model.OutgoingChatMessage
import gr.hua.aurora.protocol.GlobalMeshDeliveryResult
import gr.hua.aurora.protocol.PrivateChatMessageSendResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
