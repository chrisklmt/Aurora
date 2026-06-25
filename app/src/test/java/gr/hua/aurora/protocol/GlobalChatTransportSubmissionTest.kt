package gr.hua.aurora.protocol

import gr.hua.aurora.ble.noop.NoOpBleTransportSender
import gr.hua.aurora.ble.transport.BleGattTransportFrameReassembler
import gr.hua.aurora.ble.transport.BleTransportSendResult
import gr.hua.aurora.ble.transport.BleTransportSender
import gr.hua.aurora.ble.transport.OutgoingBleTransportSendPlan
import gr.hua.aurora.crypto.Sec1PublicKeyEncoding
import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.model.OutgoingChatMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

class GlobalChatTransportSubmissionTest {
    @Test
    fun noOpProviderForSelectedTargetReturnsSessionMaterialUnavailable() {
        val message = globalOutgoingMessage()

        val providerMaterial = NoOpOutgoingSessionMaterialProvider.encryptionMaterialForTarget(
            "peer-target"
        )
        val result = runSuspending {
            GlobalChatTransportSubmission.submitIfReady(
                message = message,
                senderId = "sender-1",
                transportSender = RecordingTransportSender(BleTransportSendResult.QueuedLocally),
                sessionMaterialProvider = NoOpOutgoingSessionMaterialProvider,
                targetPeerId = "peer-target"
            )
        }

        assertEquals(null, providerMaterial)
        assertEquals(GlobalChatTransportSubmissionResult.SessionMaterialUnavailable, result)
        assertEquals(MessageStatus.QUEUED, message.status)
    }

    @Test
    fun noTargetPeerReturnsClearNonSubmissionReason() {
        val message = globalOutgoingMessage()

        val result = runSuspending {
            GlobalChatTransportSubmission.submitIfReady(
                message = message,
                senderId = "sender-1",
                transportSender = RecordingTransportSender(BleTransportSendResult.QueuedLocally),
                sessionMaterialProvider = FakeSessionMaterialProvider(
                    encryptionMaterial = encryptionMaterial(10)
                )
            )
        }

        assertEquals(GlobalChatTransportSubmissionResult.NoSecurePeerSelected, result)
        assertEquals(MessageStatus.QUEUED, message.status)
    }

    @Test
    fun senderUnavailableReturnsClearNonSubmissionReason() {
        val message = globalOutgoingMessage()

        val resultWithoutSender = runSuspending {
            GlobalChatTransportSubmission.submitIfReady(
                message = message,
                senderId = "sender-1",
                transportSender = null,
                sessionMaterialProvider = FakeSessionMaterialProvider(
                    encryptionMaterial = encryptionMaterial(11)
                ),
                targetPeerId = "peer-target"
            )
        }
        val resultWithNoOpSender = runSuspending {
            GlobalChatTransportSubmission.submitIfReady(
                message = message,
                senderId = "sender-1",
                transportSender = NoOpBleTransportSender(),
                sessionMaterialProvider = FakeSessionMaterialProvider(
                    encryptionMaterial = encryptionMaterial(12)
                ),
                targetPeerId = "peer-target"
            )
        }
        val resultFromUnavailableSender = runSuspending {
            GlobalChatTransportSubmission.submitIfReady(
                message = message,
                senderId = "sender-1",
                transportSender = RecordingTransportSender(BleTransportSendResult.NotAvailable),
                sessionMaterialProvider = FakeSessionMaterialProvider(
                    encryptionMaterial = encryptionMaterial(13)
                ),
                targetPeerId = "peer-target"
            )
        }

        assertEquals(GlobalChatTransportSubmissionResult.SenderUnavailable, resultWithoutSender)
        assertEquals(GlobalChatTransportSubmissionResult.SenderUnavailable, resultWithNoOpSender)
        assertEquals(GlobalChatTransportSubmissionResult.SenderUnavailable, resultFromUnavailableSender)
        assertEquals(MessageStatus.QUEUED, message.status)
    }

    @Test
    fun missingSessionForSelectedTargetReturnsSessionMaterialUnavailable() {
        val message = globalOutgoingMessage()

        val result = runSuspending {
            GlobalChatTransportSubmission.submitIfReady(
                message = message,
                senderId = "sender-global",
                transportSender = RecordingTransportSender(BleTransportSendResult.QueuedLocally),
                sessionMaterialProvider = FakeSessionMaterialProvider(
                    encryptionMaterial = null
                ),
                targetPeerId = "peer-target"
            )
        }

        assertEquals(GlobalChatTransportSubmissionResult.SessionMaterialUnavailable, result)
        assertEquals(MessageStatus.QUEUED, message.status)
    }

    @Test
    fun activeConnectedPeerMismatchPreventsEncryptedSubmission() {
        val message = globalOutgoingMessage()
        val sender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)

        val result = runSuspending {
            GlobalChatTransportSubmission.submitIfReady(
                message = message,
                senderId = "sender-global",
                transportSender = sender,
                sessionMaterialProvider = FakeSessionMaterialProvider(
                    encryptionMaterial = encryptionMaterial(18)
                ),
                targetPeerId = "peer-target",
                activeConnectedPeerId = "peer-other"
            )
        }

        assertTrue(result is GlobalChatTransportSubmissionResult.Failed)
        assertEquals(
            "Selected secure peer peer-target does not match active connected peer peer-other.",
            (result as GlobalChatTransportSubmissionResult.Failed).reason
        )
        assertNull(sender.capturedPlan)
        assertEquals(MessageStatus.QUEUED, message.status)
    }

    @Test
    fun selectedTargetPeerAllowsSubmissionPathInTests() {
        val message = globalOutgoingMessage()
        val sender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)
        val provider = FakeSessionMaterialProvider(
            encryptionMaterial = encryptionMaterial(21)
        )
        val expectedMaterial = requireNotNull(provider.encryptionMaterial)

        val result = runSuspending {
            GlobalChatTransportSubmission.submitIfReady(
                message = message,
                senderId = "sender-global",
                transportSender = sender,
                sessionMaterialProvider = provider,
                targetPeerId = "peer-target"
            )
        }

        val capturedPlan = requireNotNull(sender.capturedPlan)
        val reassembledEnvelopeBytes = BleGattTransportFrameReassembler.reassemble(
            capturedPlan.framesInSendOrder()
        )
        val decodedEnvelope = EncryptedMessageEnvelopeCodec.decode(
            String(reassembledEnvelopeBytes, UTF_8)
        )
        val decryptedFrameBytes = EncryptedMessageEnvelopeDecryptor.decrypt(
            envelope = decodedEnvelope,
            keyBytes = expectedMaterial.keyBytes,
            authenticatedData = expectedMaterial.authenticatedData
        )
        val decodedFrame = MessageFrameCodec.decode(String(decryptedFrameBytes, UTF_8))

        assertEquals(GlobalChatTransportSubmissionResult.SubmittedLocally, result)
        assertEquals(null, provider.lastMessage)
        assertEquals("peer-target", provider.lastTargetPeerId)
        assertNotNull(sender.capturedPlan)
        assertEquals(message.messageId, capturedPlan.messageId)
        assertEquals("peer-target", capturedPlan.targetPeerId)
        assertEquals(message.messageId, decodedFrame.id)
        assertEquals("sender-global", decodedFrame.senderId)
        assertEquals(null, decodedFrame.recipientId)
        assertEquals(message.userText, decodedFrame.payload)
        assertEquals(message.createdAtMillis, decodedFrame.createdAtMillis)
        assertArrayEquals(expectedMaterial.senderPublicKey, decodedEnvelope.senderPublicKey)
        assertEquals(MessageStatus.QUEUED, message.status)
    }

    @Test
    fun senderFailureIsPropagatedWithoutMutatingMessageStatus() {
        val message = globalOutgoingMessage()
        val failed = BleTransportSendResult.Failed("local transport unavailable")

        val result = runSuspending {
            GlobalChatTransportSubmission.submitIfReady(
                message = message,
                senderId = "sender-global",
                transportSender = RecordingTransportSender(failed),
                sessionMaterialProvider = FakeSessionMaterialProvider(
                    encryptionMaterial = encryptionMaterial(31)
                ),
                targetPeerId = "peer-target"
            )
        }

        assertTrue(result is GlobalChatTransportSubmissionResult.Failed)
        assertEquals(MessageStatus.QUEUED, message.status)
    }

    private fun globalOutgoingMessage(): OutgoingChatMessage {
        return OutgoingChatMessage(
            messageId = "global-queued-1",
            threadId = "global",
            userText = "hello pipeline",
            createdAtMillis = 1_715_260_777L,
            status = MessageStatus.QUEUED
        )
    }

    private fun encryptionMaterial(offset: Int): OutgoingMessageSendEncryptionMaterial {
        return OutgoingMessageSendEncryptionMaterial(
            senderPublicKey = senderPublicKeyBytes(),
            keyBytes = ByteArray(32) { index -> (index + offset).toByte() },
            authenticatedData = "global-aad-$offset".toByteArray(UTF_8)
        )
    }

    private fun senderPublicKeyBytes(): ByteArray {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val publicKey = generator.generateKeyPair().public as ECPublicKey
        return Sec1PublicKeyEncoding.encodeUncompressed(publicKey)
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

    private class FakeSessionMaterialProvider(
        val encryptionMaterial: OutgoingMessageSendEncryptionMaterial?
    ) : OutgoingSessionMaterialProvider {
        var lastMessage: OutgoingChatMessage? = null
        var lastTargetPeerId: String? = null

        override fun encryptionMaterialFor(
            message: OutgoingChatMessage
        ): OutgoingMessageSendEncryptionMaterial? {
            lastMessage = message
            return encryptionMaterial
        }

        override fun encryptionMaterialForTarget(
            peerId: String
        ): OutgoingMessageSendEncryptionMaterial? {
            lastTargetPeerId = peerId
            return encryptionMaterial
        }
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
            "Suspending submission did not complete synchronously in the test harness."
        }.getOrThrow()
    }
}
