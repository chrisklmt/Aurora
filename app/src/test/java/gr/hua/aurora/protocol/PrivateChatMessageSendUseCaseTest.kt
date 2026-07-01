package gr.hua.aurora.protocol

import gr.hua.aurora.ble.transport.BleTransportSendResult
import gr.hua.aurora.ble.transport.BleTransportSender
import gr.hua.aurora.ble.transport.OutgoingBleTransportSendPlan
import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.model.OutgoingChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class PrivateChatMessageSendUseCaseTest {
    @Test
    fun privateSendUsesEncryptedPrivatePathAndNotGlobalText() {
        val material = testEncryptionMaterial()
        val sender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)
        val message = OutgoingChatMessage(
            messageId = "private-send-1",
            threadId = "private:alex",
            userText = "hello private",
            createdAtMillis = 1_715_260_001L,
            status = MessageStatus.QUEUED
        )

        val result = runSuspending {
            PrivateChatMessageSendUseCase.send(
                message = message,
                privateChatId = "chat-alex",
                senderPeerId = "sender-canonical",
                senderUsername = "Alice",
                transportSender = sender,
                sessionMaterialProvider = FakeOutgoingSessionMaterialProvider(
                    materialByPeerId = mapOf("alex" to material)
                ),
                activeConnectedPeerId = "alex",
                isActiveTransportConnected = true
            )
        }

        val decodedFrame = decodeRecordedFrame(requireNotNull(sender.capturedPlan), material)
        val decodedPayload = PrivateChatMessagePayloadCodec.decode(decodedFrame.payload)

        assertEquals(PrivateChatMessageSendResult.SubmittedLocally, result)
        assertEquals(MessageFrameType.PRIVATE_TEXT, decodedFrame.type)
        assertEquals("sender-canonical", decodedFrame.senderId)
        assertEquals("alex", decodedFrame.recipientId)
        assertEquals("Alice", decodedPayload.senderUsername)
        assertEquals("hello private", decodedPayload.body)
        assertEquals("chat-alex", decodedPayload.privateChatId)
        assertEquals("alex", requireNotNull(sender.capturedPlan).targetPeerId)
    }

    @Test
    fun privateSendReturnsKeysUnavailableWhenSessionIsMissing() {
        val sender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)
        val message = OutgoingChatMessage(
            messageId = "private-send-2",
            threadId = "private:alex",
            userText = "hello private",
            createdAtMillis = 1_715_260_002L,
            status = MessageStatus.QUEUED
        )

        val result = runSuspending {
            PrivateChatMessageSendUseCase.send(
                message = message,
                privateChatId = "chat-alex",
                senderPeerId = "sender-canonical",
                senderUsername = "Alice",
                transportSender = sender,
                sessionMaterialProvider = FakeOutgoingSessionMaterialProvider(),
                activeConnectedPeerId = "alex",
                isActiveTransportConnected = true
            )
        }

        assertEquals(PrivateChatMessageSendResult.KeysUnavailable, result)
        assertNull(sender.capturedPlan)
    }

    @Test
    fun privateSendReturnsContactNotReachableWhenActivePeerDoesNotMatch() {
        val material = testEncryptionMaterial()
        val sender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)
        val message = OutgoingChatMessage(
            messageId = "private-send-3",
            threadId = "private:alex",
            userText = "hello private",
            createdAtMillis = 1_715_260_003L,
            status = MessageStatus.QUEUED
        )

        val result = runSuspending {
            PrivateChatMessageSendUseCase.send(
                message = message,
                privateChatId = "chat-alex",
                senderPeerId = "sender-canonical",
                senderUsername = "Alice",
                transportSender = sender,
                sessionMaterialProvider = FakeOutgoingSessionMaterialProvider(
                    materialByPeerId = mapOf("alex" to material)
                ),
                activeConnectedPeerId = "bea",
                isActiveTransportConnected = true
            )
        }

        assertEquals(PrivateChatMessageSendResult.ContactNotReachable, result)
        assertNull(sender.capturedPlan)
    }

    @Test
    fun privateSendReturnsContactUnavailableForNonPrivateThread() {
        val sender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)
        val result = runSuspending {
            PrivateChatMessageSendUseCase.send(
                message = OutgoingChatMessage(
                    messageId = "private-send-4",
                    threadId = "global",
                    userText = "hello",
                    createdAtMillis = 1_715_260_004L,
                    status = MessageStatus.QUEUED
                ),
                privateChatId = "chat-global",
                senderPeerId = "sender-canonical",
                senderUsername = "Alice",
                transportSender = sender,
                sessionMaterialProvider = FakeOutgoingSessionMaterialProvider(),
                activeConnectedPeerId = null,
                isActiveTransportConnected = false
            )
        }

        assertEquals(PrivateChatMessageSendResult.ContactUnavailable, result)
        assertNull(sender.capturedPlan)
    }

    @Test
    fun privateSendPropagatesTransportFailure() {
        val material = testEncryptionMaterial()
        val sender = RecordingTransportSender(
            BleTransportSendResult.Failed("writer unavailable")
        )
        val message = OutgoingChatMessage(
            messageId = "private-send-5",
            threadId = "private:alex",
            userText = "hello private",
            createdAtMillis = 1_715_260_005L,
            status = MessageStatus.QUEUED
        )

        val result = runSuspending {
            PrivateChatMessageSendUseCase.send(
                message = message,
                privateChatId = "chat-alex",
                senderPeerId = "sender-canonical",
                senderUsername = "Alice",
                transportSender = sender,
                sessionMaterialProvider = FakeOutgoingSessionMaterialProvider(
                    materialByPeerId = mapOf("alex" to material)
                ),
                activeConnectedPeerId = "alex",
                isActiveTransportConnected = true
            )
        }

        assertTrue(result is PrivateChatMessageSendResult.Failed)
        assertEquals("writer unavailable", (result as PrivateChatMessageSendResult.Failed).reason)
    }

    @Test
    fun privateSendFailsCleanlyWhenEncodedPayloadExceedsTransportChunkLimit() {
        val material = testEncryptionMaterial()
        val sender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)
        val oversizedBody = "x".repeat(8_000)
        val message = OutgoingChatMessage(
            messageId = "private-send-oversized",
            threadId = "private:alex",
            userText = oversizedBody,
            createdAtMillis = 1_715_260_006L,
            status = MessageStatus.QUEUED
        )

        val result = runSuspending {
            PrivateChatMessageSendUseCase.send(
                message = message,
                privateChatId = "chat-alex",
                senderPeerId = "sender-canonical",
                senderUsername = "Alice",
                transportSender = sender,
                sessionMaterialProvider = FakeOutgoingSessionMaterialProvider(
                    materialByPeerId = mapOf("alex" to material)
                ),
                activeConnectedPeerId = "alex",
                isActiveTransportConnected = true
            )
        }

        assertTrue(result is PrivateChatMessageSendResult.Failed)
        assertTrue(
            requireNotNull((result as PrivateChatMessageSendResult.Failed).reason)
                .contains("supported limit")
        )
    }

    private class FakeOutgoingSessionMaterialProvider(
        private val materialByPeerId: Map<String, OutgoingMessageSendEncryptionMaterial> = emptyMap()
    ) : OutgoingSessionMaterialProvider {
        override fun encryptionMaterialFor(
            message: OutgoingChatMessage
        ): OutgoingMessageSendEncryptionMaterial? {
            val peerId = message.threadId.removePrefix("private:")
            return materialByPeerId[peerId]
        }

        override fun encryptionMaterialForTarget(
            peerId: String
        ): OutgoingMessageSendEncryptionMaterial? {
            return materialByPeerId[peerId]
        }
    }

    private class RecordingTransportSender(
        private val result: BleTransportSendResult
    ) : BleTransportSender {
        var capturedPlan: OutgoingBleTransportSendPlan? = null

        override fun send(
            plan: OutgoingBleTransportSendPlan,
            listener: BleTransportSender.Listener
        ) {
            capturedPlan = plan
            listener.onSendResult(result)
        }
    }

    private fun testEncryptionMaterial(): OutgoingMessageSendEncryptionMaterial {
        return OutgoingMessageSendEncryptionMaterial(
            senderPublicKey = senderPublicKeyBytes(),
            keyBytes = ByteArray(32) { index -> (index + 31).toByte() },
            authenticatedData = "private-chat-aad".toByteArray(UTF_8)
        )
    }

    private fun senderPublicKeyBytes(): ByteArray {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val publicKey = generator.generateKeyPair().public as ECPublicKey
        return gr.hua.aurora.crypto.Sec1PublicKeyEncoding.encodeUncompressed(publicKey)
    }

    private fun decodeRecordedFrame(
        plan: OutgoingBleTransportSendPlan,
        material: OutgoingMessageSendEncryptionMaterial
    ): MessageFrame {
        val envelopeBytes = gr.hua.aurora.ble.transport.BleGattTransportFrameReassembler.reassemble(
            plan.framesInSendOrder()
        )
        val envelope = EncryptedMessageEnvelopeCodec.decode(String(envelopeBytes, UTF_8))
        val frameBytes = EncryptedMessageEnvelopeDecryptor.decrypt(
            envelope = envelope,
            keyBytes = material.keyBytes,
            authenticatedData = material.authenticatedData
        )
        return MessageFrameCodec.decode(String(frameBytes, UTF_8))
    }

    private fun <T> runSuspending(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(result: Result<T>) {
                    outcome = result
                }
            }
        )

        return requireNotNull(outcome) {
            "Suspending use-case did not complete synchronously in the test harness."
        }.getOrThrow()
    }
}
