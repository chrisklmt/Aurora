package gr.hua.aurora.ble.transport

import gr.hua.aurora.crypto.Sec1PublicKeyEncoding
import gr.hua.aurora.protocol.EncryptedMessageEnvelopeBuilder
import gr.hua.aurora.protocol.EncryptedMessageEnvelopeCodec
import gr.hua.aurora.protocol.EncryptedMessageEnvelopeDecryptor
import gr.hua.aurora.protocol.MessageFrameCodec
import gr.hua.aurora.protocol.MessageFrameType
import gr.hua.aurora.protocol.OutgoingMessageFrameDraft
import gr.hua.aurora.protocol.OutgoingMessageFrameResolver
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class OutgoingBleTransportSendPlanBuilderTest {
    @Test
    fun builderCreatesOneFramePlanForSmallEncryptedBytes() {
        val encryptedEnvelopeBytes = ByteArray(BleGattTransportChunk.MAX_PAYLOAD_SIZE) { index ->
            (index + 1).toByte()
        }

        val plan = OutgoingBleTransportSendPlanBuilder.build(
            messageId = "plan-small-1",
            targetPeerId = null,
            encryptedEnvelopeBytes = encryptedEnvelopeBytes
        )
        val reassembledBytes = BleGattTransportFrameReassembler.reassemble(plan.framesInSendOrder())

        assertEquals("plan-small-1", plan.messageId)
        assertEquals(null, plan.targetPeerId)
        assertEquals(1, plan.framesInSendOrder().size)
        assertArrayEquals(encryptedEnvelopeBytes, reassembledBytes)
    }

    @Test
    fun builderCreatesMultiFramePlanForLargerEncryptedBytes() {
        val encryptedEnvelopeBytes = ByteArray(BleGattTransportChunk.MAX_PAYLOAD_SIZE * 3 + 2) { index ->
            (index + 5).toByte()
        }

        val plan = OutgoingBleTransportSendPlanBuilder.build(
            messageId = "plan-large-2",
            targetPeerId = "peer-42",
            encryptedEnvelopeBytes = encryptedEnvelopeBytes,
            sourceCreatedAtMillis = 1_715_230_002L
        )
        val reassembledBytes = BleGattTransportFrameReassembler.reassemble(plan.framesInSendOrder())

        assertEquals("plan-large-2", plan.messageId)
        assertEquals("peer-42", plan.targetPeerId)
        assertEquals(1_715_230_002L, plan.sourceCreatedAtMillis)
        assertTrue(plan.framesInSendOrder().size > 1)
        assertArrayEquals(encryptedEnvelopeBytes, reassembledBytes)
    }

    @Test
    fun frameOrderIsStable() {
        val encryptedEnvelopeBytes = ByteArray(BleGattTransportChunk.MAX_PAYLOAD_SIZE * 2 + 1) { index ->
            (index + 7).toByte()
        }

        val plan = OutgoingBleTransportSendPlanBuilder.build(
            messageId = "plan-order-3",
            targetPeerId = "peer-order",
            encryptedEnvelopeBytes = encryptedEnvelopeBytes
        )
        val chunkIndexes = plan.framesInSendOrder().map { frame ->
            checkNotNull(BleGattTransportChunk.parse(frame)).chunkIndex
        }

        assertEquals(listOf(0, 1, 2), chunkIndexes)
    }

    @Test
    fun recipientTargetIdIsPreserved() {
        val plan = OutgoingBleTransportSendPlanBuilder.build(
            messageId = "plan-target-4",
            targetPeerId = "peer-alex",
            encryptedEnvelopeBytes = ByteArray(3) { index -> (index + 9).toByte() }
        )

        assertEquals("peer-alex", plan.targetPeerId)
    }

    @Test
    fun emptyEncryptedBytesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            OutgoingBleTransportSendPlanBuilder.build(
                messageId = "plan-empty-5",
                targetPeerId = null,
                encryptedEnvelopeBytes = byteArrayOf()
            )
        }
    }

    @Test
    fun encodedEnvelopeBytesRoundTripThroughSendPlanAndDecryptToSameFrame() {
        val resolvedFrame = OutgoingMessageFrameResolver.resolve(
            draft = OutgoingMessageFrameDraft(
                id = "plan-envelope-6",
                threadId = "private:alex",
                type = MessageFrameType.PRIVATE_TEXT,
                recipientId = "alex",
                createdAtMillis = 1_715_230_321L,
                payload = "hello planned private"
            ),
            senderId = "sender-private"
        )
        val authenticatedData = "planned-envelope-aad".toByteArray(UTF_8)
        val envelope = EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = senderPublicKeyBytes(),
            keyBytes = deterministicKey(91),
            plaintext = MessageFrameCodec.encode(resolvedFrame).toByteArray(UTF_8),
            authenticatedData = authenticatedData
        )
        val encodedEnvelopeBytes = EncryptedMessageEnvelopeCodec.encode(envelope).toByteArray(UTF_8)

        val plan = OutgoingBleTransportSendPlanBuilder.build(
            messageId = resolvedFrame.id,
            targetPeerId = resolvedFrame.recipientId,
            encryptedEnvelopeBytes = encodedEnvelopeBytes,
            sourceCreatedAtMillis = resolvedFrame.createdAtMillis
        )
        val reassembledBytes = BleGattTransportFrameReassembler.reassemble(plan.framesInSendOrder())
        val decodedEnvelope = EncryptedMessageEnvelopeCodec.decode(String(reassembledBytes, UTF_8))
        val decryptedFrameBytes = EncryptedMessageEnvelopeDecryptor.decrypt(
            envelope = decodedEnvelope,
            keyBytes = deterministicKey(91),
            authenticatedData = authenticatedData
        )
        val decodedFrame = MessageFrameCodec.decode(String(decryptedFrameBytes, UTF_8))

        assertEquals(resolvedFrame.id, plan.messageId)
        assertEquals(resolvedFrame.recipientId, plan.targetPeerId)
        assertEquals(resolvedFrame.createdAtMillis, plan.sourceCreatedAtMillis)
        assertArrayEquals(encodedEnvelopeBytes, reassembledBytes)
        assertEquals(resolvedFrame, decodedFrame)
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
}
