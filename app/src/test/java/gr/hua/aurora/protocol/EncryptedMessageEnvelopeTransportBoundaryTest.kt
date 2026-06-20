package gr.hua.aurora.protocol

import gr.hua.aurora.ble.transport.BleGattTransportFrame
import gr.hua.aurora.crypto.Sec1PublicKeyEncoding
import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.model.OutgoingChatMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class EncryptedMessageEnvelopeTransportBoundaryTest {
    @Test
    fun queuedGlobalMessageRoundTripsThroughEncodedEnvelopeBytesWithoutChangingQueuedStatus() {
        val queuedMessage = OutgoingChatMessage(
            messageId = "queued-global-transport-1",
            threadId = "global",
            userText = "hello transport global",
            createdAtMillis = 1_715_210_001L,
            status = MessageStatus.QUEUED
        )
        val originalQueuedMessage = queuedMessage.copy()
        val draft = OutgoingMessageFrameBuilder.build(queuedMessage)
        val resolvedFrame = OutgoingMessageFrameResolver.resolve(
            draft = draft,
            senderId = "sender-global"
        )
        val authenticatedData = "transport-boundary-global-aad".toByteArray(UTF_8)
        val senderPublicKey = senderPublicKeyBytes()
        val envelope = EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = senderPublicKey,
            keyBytes = deterministicKey(31),
            plaintext = MessageFrameCodec.encode(resolvedFrame).toByteArray(UTF_8),
            authenticatedData = authenticatedData
        )

        val encodedEnvelopeBytes = EncryptedMessageEnvelopeCodec.encode(envelope).toByteArray(UTF_8)
        val decodedEnvelope = EncryptedMessageEnvelopeCodec.decode(String(encodedEnvelopeBytes, UTF_8))
        val reEncodedEnvelopeBytes = EncryptedMessageEnvelopeCodec.encode(decodedEnvelope).toByteArray(UTF_8)
        val decryptedFrameBytes = EncryptedMessageEnvelopeDecryptor.decrypt(
            envelope = decodedEnvelope,
            keyBytes = deterministicKey(31),
            authenticatedData = authenticatedData
        )
        val decodedFrame = MessageFrameCodec.decode(String(decryptedFrameBytes, UTF_8))

        assertEquals(originalQueuedMessage, queuedMessage)
        assertEquals(MessageStatus.QUEUED, queuedMessage.status)
        assertArrayEquals(encodedEnvelopeBytes, reEncodedEnvelopeBytes)
        assertArrayEquals(senderPublicKey, decodedEnvelope.senderPublicKey)
        assertArrayEquals(envelope.payload.nonce, decodedEnvelope.payload.nonce)
        assertArrayEquals(envelope.payload.ciphertext, decodedEnvelope.payload.ciphertext)
        assertEquals(resolvedFrame, decodedFrame)
    }

    @Test
    fun queuedPrivateMessageRoundTripsThroughEncodedEnvelopeBytesWithoutChangingQueuedStatus() {
        val queuedMessage = OutgoingChatMessage(
            messageId = "queued-private-transport-2",
            threadId = "private:alex",
            userText = "hello transport private",
            createdAtMillis = 1_715_210_321L,
            status = MessageStatus.QUEUED
        )
        val originalQueuedMessage = queuedMessage.copy()
        val draft = OutgoingMessageFrameBuilder.build(queuedMessage)
        val resolvedFrame = OutgoingMessageFrameResolver.resolve(
            draft = draft,
            senderId = "sender-private"
        )
        val authenticatedData = "transport-boundary-private-aad".toByteArray(UTF_8)
        val senderPublicKey = senderPublicKeyBytes()
        val envelope = EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = senderPublicKey,
            keyBytes = deterministicKey(41),
            plaintext = MessageFrameCodec.encode(resolvedFrame).toByteArray(UTF_8),
            authenticatedData = authenticatedData
        )

        val encodedEnvelopeBytes = EncryptedMessageEnvelopeCodec.encode(envelope).toByteArray(UTF_8)
        val decodedEnvelope = EncryptedMessageEnvelopeCodec.decode(String(encodedEnvelopeBytes, UTF_8))
        val reEncodedEnvelopeBytes = EncryptedMessageEnvelopeCodec.encode(decodedEnvelope).toByteArray(UTF_8)
        val decryptedFrameBytes = EncryptedMessageEnvelopeDecryptor.decrypt(
            envelope = decodedEnvelope,
            keyBytes = deterministicKey(41),
            authenticatedData = authenticatedData
        )
        val decodedFrame = MessageFrameCodec.decode(String(decryptedFrameBytes, UTF_8))

        assertEquals(originalQueuedMessage, queuedMessage)
        assertEquals(MessageStatus.QUEUED, queuedMessage.status)
        assertArrayEquals(encodedEnvelopeBytes, reEncodedEnvelopeBytes)
        assertArrayEquals(senderPublicKey, decodedEnvelope.senderPublicKey)
        assertArrayEquals(envelope.payload.nonce, decodedEnvelope.payload.nonce)
        assertArrayEquals(envelope.payload.ciphertext, decodedEnvelope.payload.ciphertext)
        assertEquals(resolvedFrame, decodedFrame)
        assertEquals("alex", decodedFrame.recipientId)
    }

    @Test
    fun encodedEnvelopeBytesDoNotAssumeSingleBleGattTransportFrame() {
        val resolvedFrame = OutgoingMessageFrameResolver.resolve(
            draft = OutgoingMessageFrameDraft(
                id = "frame-too-large-1",
                threadId = "global",
                type = MessageFrameType.GLOBAL_TEXT,
                createdAtMillis = 1_715_210_777L,
                payload = "hello envelope bytes"
            ),
            senderId = "sender-global"
        )
        val envelope = EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = senderPublicKeyBytes(),
            keyBytes = deterministicKey(51),
            plaintext = MessageFrameCodec.encode(resolvedFrame).toByteArray(UTF_8)
        )
        val encodedEnvelopeBytes = EncryptedMessageEnvelopeCodec.encode(envelope).toByteArray(UTF_8)

        assertTrue(encodedEnvelopeBytes.size > BleGattTransportFrame.MAX_BODY_SIZE)
        assertNull(BleGattTransportFrame.create(body = encodedEnvelopeBytes))
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
