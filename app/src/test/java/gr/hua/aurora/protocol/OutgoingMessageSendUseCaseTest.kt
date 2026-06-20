package gr.hua.aurora.protocol

import gr.hua.aurora.ble.transport.BleGattTransportFrameReassembler
import gr.hua.aurora.ble.transport.BleTransportSendResult
import gr.hua.aurora.ble.transport.BleTransportSender
import gr.hua.aurora.ble.transport.OutgoingBleTransportSendPlan
import gr.hua.aurora.crypto.Sec1PublicKeyEncoding
import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.model.OutgoingChatMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class OutgoingMessageSendUseCaseTest {
    @Test
    fun privateOutgoingMessagePassesThroughFullPipelineAndCallsTransportSender() {
        val message = OutgoingChatMessage(
            messageId = "private-send-1",
            threadId = "private:alex",
            userText = "hello private send",
            createdAtMillis = 1_715_260_001L,
            status = MessageStatus.QUEUED
        )
        val originalMessage = message.copy()
        val sender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)
        val encryptionMaterial = OutgoingMessageSendEncryptionMaterial(
            senderPublicKey = senderPublicKeyBytes(),
            keyBytes = deterministicKey(101),
            authenticatedData = "private-send-aad".toByteArray(UTF_8)
        )

        val result = runSuspending {
            OutgoingMessageSendUseCase.send(
                message = message,
                senderId = "sender-private",
                encryptionMaterial = encryptionMaterial,
                transportSender = sender
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
            keyBytes = encryptionMaterial.keyBytes,
            authenticatedData = encryptionMaterial.authenticatedData
        )
        val decodedFrame = MessageFrameCodec.decode(String(decryptedFrameBytes, UTF_8))

        assertEquals(BleTransportSendResult.QueuedLocally, result)
        assertEquals(originalMessage, message)
        assertEquals(MessageStatus.QUEUED, message.status)
        assertEquals(message.messageId, capturedPlan.messageId)
        assertEquals("alex", capturedPlan.targetPeerId)
        assertEquals(message.createdAtMillis, capturedPlan.sourceCreatedAtMillis)
        assertEquals("private:alex", decodedFrame.recipientId?.let { "private:$it" })
        assertEquals(message.messageId, decodedFrame.id)
        assertEquals("alex", decodedFrame.recipientId)
        assertEquals(message.userText, decodedFrame.payload)
        assertEquals(message.createdAtMillis, decodedFrame.createdAtMillis)
        assertEquals(MessageFrameType.PRIVATE_TEXT, decodedFrame.type)
    }

    @Test
    fun sendPlanReassemblesToEncryptedEnvelopeBytesThatDecryptBackToOriginalMessageFrame() {
        val message = OutgoingChatMessage(
            messageId = "private-send-2",
            threadId = "private:bea",
            userText = "hello decrypt path",
            createdAtMillis = 1_715_260_123L,
            status = MessageStatus.QUEUED
        )
        val encryptionMaterial = OutgoingMessageSendEncryptionMaterial(
            senderPublicKey = senderPublicKeyBytes(),
            keyBytes = deterministicKey(111),
            authenticatedData = "decrypt-path-aad".toByteArray(UTF_8)
        )
        val sender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)

        val result = runSuspending {
            OutgoingMessageSendUseCase.send(
                message = message,
                senderId = "sender-bea",
                encryptionMaterial = encryptionMaterial,
                transportSender = sender
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
            keyBytes = encryptionMaterial.keyBytes,
            authenticatedData = encryptionMaterial.authenticatedData
        )
        val decodedFrame = MessageFrameCodec.decode(String(decryptedFrameBytes, UTF_8))

        assertEquals(BleTransportSendResult.QueuedLocally, result)
        assertArrayEquals(encryptionMaterial.senderPublicKey, decodedEnvelope.senderPublicKey)
        assertEquals(message.messageId, decodedFrame.id)
        assertEquals("sender-bea", decodedFrame.senderId)
        assertEquals("bea", decodedFrame.recipientId)
        assertEquals(message.userText, decodedFrame.payload)
        assertEquals(message.createdAtMillis, decodedFrame.createdAtMillis)
    }

    @Test
    fun senderFailureResultIsPropagated() {
        val message = OutgoingChatMessage(
            messageId = "private-send-3",
            threadId = "private:chris",
            userText = "hello failed send",
            createdAtMillis = 1_715_260_222L,
            status = MessageStatus.QUEUED
        )
        val failed = BleTransportSendResult.Failed("local transport write failed")
        val sender = RecordingTransportSender(failed)

        val result = runSuspending {
            OutgoingMessageSendUseCase.send(
                message = message,
                senderId = "sender-chris",
                encryptionMaterial = OutgoingMessageSendEncryptionMaterial(
                    senderPublicKey = senderPublicKeyBytes(),
                    keyBytes = deterministicKey(121)
                ),
                transportSender = sender
            )
        }

        assertSame(failed, result)
        assertEquals(message.messageId, requireNotNull(sender.capturedPlan).messageId)
    }

    @Test
    fun globalOutgoingMessageUsesNullableTargetExplicitly() {
        val message = OutgoingChatMessage(
            messageId = "global-send-4",
            threadId = "global",
            userText = "hello global send",
            createdAtMillis = 1_715_260_333L,
            status = MessageStatus.QUEUED
        )
        val sender = RecordingTransportSender(BleTransportSendResult.NotAvailable)

        val result = runSuspending {
            OutgoingMessageSendUseCase.send(
                message = message,
                senderId = "sender-global",
                encryptionMaterial = OutgoingMessageSendEncryptionMaterial(
                    senderPublicKey = senderPublicKeyBytes(),
                    keyBytes = deterministicKey(131)
                ),
                transportSender = sender
            )
        }

        assertEquals(BleTransportSendResult.NotAvailable, result)
        assertNull(requireNotNull(sender.capturedPlan).targetPeerId)
    }

    @Test
    fun encryptionMaterialUsesDefensiveCopies() {
        val senderPublicKey = senderPublicKeyBytes()
        val keyBytes = deterministicKey(141)
        val authenticatedData = byteArrayOf(0x01, 0x02)
        val originalSenderPublicKey = senderPublicKey.copyOf()
        val originalKeyBytes = keyBytes.copyOf()
        val originalAuthenticatedData = authenticatedData.copyOf()
        val material = OutgoingMessageSendEncryptionMaterial(
            senderPublicKey = senderPublicKey,
            keyBytes = keyBytes,
            authenticatedData = authenticatedData
        )

        senderPublicKey[0] = (senderPublicKey[0].toInt() xor 0x01).toByte()
        keyBytes[0] = (keyBytes[0].toInt() xor 0x01).toByte()
        authenticatedData[0] = (authenticatedData[0].toInt() xor 0x01).toByte()
        val firstPublicKeyRead = material.senderPublicKey
        val firstKeyRead = material.keyBytes
        val firstAuthenticatedDataRead = requireNotNull(material.authenticatedData)
        firstPublicKeyRead[1] = (firstPublicKeyRead[1].toInt() xor 0x01).toByte()
        firstKeyRead[1] = (firstKeyRead[1].toInt() xor 0x01).toByte()
        firstAuthenticatedDataRead[1] = (firstAuthenticatedDataRead[1].toInt() xor 0x01).toByte()

        assertArrayEquals(originalSenderPublicKey, material.senderPublicKey)
        assertArrayEquals(originalKeyBytes, material.keyBytes)
        assertArrayEquals(originalAuthenticatedData, requireNotNull(material.authenticatedData))
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

    private fun senderPublicKeyBytes(): ByteArray {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val publicKey = generator.generateKeyPair().public as ECPublicKey
        return Sec1PublicKeyEncoding.encodeUncompressed(publicKey)
    }

    private fun deterministicKey(offset: Int): ByteArray {
        return ByteArray(32) { index -> (index + offset).toByte() }
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
