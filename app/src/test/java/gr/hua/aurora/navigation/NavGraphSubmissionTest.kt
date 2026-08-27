package gr.hua.aurora.navigation

import gr.hua.aurora.data.LocalProfileSettings
import gr.hua.aurora.data.LocalProfileSettingsStore
import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.model.OutgoingChatMessage
import gr.hua.aurora.protocol.GlobalMeshDeliveryResult
import gr.hua.aurora.protocol.OutgoingMessageSendEncryptionMaterial
import gr.hua.aurora.protocol.PrivateChatTransportFrameFactory
import gr.hua.aurora.protocol.PrivateChatMessageSendResult
import gr.hua.aurora.state.PrivateChatTransportSubmission
import gr.hua.aurora.state.AuroraStateHolder
import gr.hua.aurora.state.SampleAuroraState
import gr.hua.aurora.state.submitGlobalQueuedMessage
import gr.hua.aurora.state.submitPrivateQueuedMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

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
    fun globalSubmitInvokesRuntimeSubmissionExactlyOnce() = runBlocking {
        val queuedMessage = sampleOutgoingMessage(threadId = "global")
        var runtimeSubmitCount = 0

        val result = submitGlobalQueuedMessage(
            queuedMessage = queuedMessage,
            currentUsername = { "Chris" },
            submitTransport = { message, senderId ->
                runtimeSubmitCount += 1
                assertEquals(queuedMessage, message)
                assertEquals("Chris", senderId)
                GlobalMeshDeliveryResult.QueuedToActivePeer("peer-123")
            }
        )

        assertEquals(1, runtimeSubmitCount)
        assertEquals(
            GlobalMeshDeliveryResult.QueuedToActivePeer("peer-123"),
            result
        )
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
                PrivateChatTransportSubmission(
                    result = PrivateChatMessageSendResult.SubmittedLocally
                )
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
                PrivateChatTransportSubmission(
                    result = PrivateChatMessageSendResult.SubmittedLocally
                )
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
                PrivateChatTransportSubmission(
                    result = PrivateChatMessageSendResult.SubmittedLocally
                )
            }
        )

        assertEquals(listOf("chat-old", "chat-new"), submittedChatIds)
    }

    @Test
    fun privateSubmitInvokesEncryptedRuntimeSubmissionExactlyOnceWithResolvedChatId() = runBlocking {
        val queuedMessage = sampleOutgoingMessage(threadId = "private:alex")
        var resolvePrivateChatIdCount = 0
        var runtimeSubmitCount = 0

        val result = submitPrivateQueuedMessage(
            queuedMessage = queuedMessage,
            peerId = "alex",
            currentUsername = { "Chris" },
            resolvePrivateChatId = {
                resolvePrivateChatIdCount += 1
                "chat-alex"
            },
            submitTransport = { message, senderId, privateChatId ->
                runtimeSubmitCount += 1
                assertEquals(queuedMessage, message)
                assertEquals("Chris", senderId)
                assertEquals("chat-alex", privateChatId)
                PrivateChatTransportSubmission(
                    result = PrivateChatMessageSendResult.SubmittedLocally
                )
            }
        )

        assertEquals(1, resolvePrivateChatIdCount)
        assertEquals(1, runtimeSubmitCount)
        assertEquals(PrivateChatMessageSendResult.SubmittedLocally, result)
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

    @Test
    fun privateSubmitAlsoInvokesWifiDirectDebugTransportWhenPreparedFrameIsAvailable() = runBlocking {
        val queuedMessage = sampleOutgoingMessage(threadId = "private:alex")
        val debugMessageIds = mutableListOf<String>()
        val debugTypes = mutableListOf<gr.hua.aurora.protocol.MessageFrameType>()

        val result = submitPrivateQueuedMessage(
            queuedMessage = queuedMessage,
            peerId = "alex",
            currentUsername = { "Chris" },
            resolvePrivateChatId = { "chat-alex" },
            submitTransport = { _, _, _ ->
                PrivateChatTransportSubmission(
                    result = PrivateChatMessageSendResult.SubmittedLocally,
                    preparedTransportFrame = samplePreparedPrivateFrame(queuedMessage)
                )
            },
            submitWifiDirectDebugTransport = { preparedTransportFrame ->
                debugMessageIds += preparedTransportFrame.frame.id
                debugTypes += preparedTransportFrame.frame.type
            }
        )

        assertEquals(PrivateChatMessageSendResult.SubmittedLocally, result)
        assertEquals(listOf(queuedMessage.messageId), debugMessageIds)
        assertEquals(
            listOf(gr.hua.aurora.protocol.MessageFrameType.PRIVATE_TEXT),
            debugTypes
        )
    }

    @Test
    fun privateSubmitDoesNotLetWifiDirectDebugFailureOverrideBleResult() = runBlocking {
        val queuedMessage = sampleOutgoingMessage(threadId = "private:alex")

        val result = submitPrivateQueuedMessage(
            queuedMessage = queuedMessage,
            peerId = "alex",
            currentUsername = { "Chris" },
            resolvePrivateChatId = { "chat-alex" },
            submitTransport = { _, _, _ ->
                PrivateChatTransportSubmission(
                    result = PrivateChatMessageSendResult.SubmittedLocally,
                    preparedTransportFrame = samplePreparedPrivateFrame(queuedMessage)
                )
            },
            submitWifiDirectDebugTransport = {
                error("wifi direct bridge unavailable")
            }
        )

        assertEquals(PrivateChatMessageSendResult.SubmittedLocally, result)
    }

    @Test
    fun privateSubmitKeepsBleFailureEvenWhenWifiDirectDebugCopySucceeds() = runBlocking {
        val queuedMessage = sampleOutgoingMessage(threadId = "private:alex")
        var wifiDirectInvoked = false

        val result = submitPrivateQueuedMessage(
            queuedMessage = queuedMessage,
            peerId = "alex",
            currentUsername = { "Chris" },
            resolvePrivateChatId = { "chat-alex" },
            submitTransport = { _, _, _ ->
                PrivateChatTransportSubmission(
                    result = PrivateChatMessageSendResult.Failed("ble sender unavailable"),
                    preparedTransportFrame = samplePreparedPrivateFrame(queuedMessage)
                )
            },
            submitWifiDirectDebugTransport = {
                wifiDirectInvoked = true
            }
        )

        assertTrue(wifiDirectInvoked)
        assertEquals(
            PrivateChatMessageSendResult.Failed("ble sender unavailable"),
            result
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

    private fun samplePreparedPrivateFrame(
        message: OutgoingChatMessage
    ): gr.hua.aurora.protocol.PreparedPrivateChatTransportFrame {
        return PrivateChatTransportFrameFactory.build(
            message = message.copy(userText = "hello private"),
            privateChatId = "chat-alex",
            senderPeerId = "sender-canonical",
            senderUsername = "Chris",
            encryptionMaterial = OutgoingMessageSendEncryptionMaterial(
                senderPublicKey = senderPublicKeyBytes(),
                keyBytes = ByteArray(32) { index -> (index + 71).toByte() },
                authenticatedData = "nav-private-debug".toByteArray(UTF_8)
            )
        )
    }

    private fun senderPublicKeyBytes(): ByteArray {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val publicKey = generator.generateKeyPair().public as ECPublicKey
        return gr.hua.aurora.crypto.Sec1PublicKeyEncoding.encodeUncompressed(publicKey)
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
