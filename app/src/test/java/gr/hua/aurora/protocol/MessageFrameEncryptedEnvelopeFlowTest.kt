package gr.hua.aurora.protocol

import gr.hua.aurora.crypto.Sec1PublicKeyEncoding
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class MessageFrameEncryptedEnvelopeFlowTest {
    @Test
    fun resolvedGlobalMessageFrameRoundTripsThroughEncryptedEnvelopeCodecAndDecryptor() {
        val resolvedFrame = OutgoingMessageFrameResolver.resolve(
            draft = OutgoingMessageFrameDraft(
                id = "global-envelope-1",
                threadId = "global",
                type = MessageFrameType.GLOBAL_TEXT,
                createdAtMillis = 1_715_200_001L,
                payload = "hello encrypted global"
            ),
            senderId = "sender-global"
        )
        val plaintextFrameBytes = MessageFrameCodec.encode(resolvedFrame).toByteArray(UTF_8)
        val authenticatedData = "frame-envelope-aad".toByteArray(UTF_8)
        val senderPublicKey = senderPublicKeyBytes()

        val envelope = EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = senderPublicKey,
            keyBytes = deterministicKey(11),
            plaintext = plaintextFrameBytes,
            authenticatedData = authenticatedData
        )
        val encodedEnvelopeBytes = EncryptedMessageEnvelopeCodec.encode(envelope).toByteArray(UTF_8)
        val decodedEnvelope = EncryptedMessageEnvelopeCodec.decode(String(encodedEnvelopeBytes, UTF_8))
        val decryptedFrameBytes = EncryptedMessageEnvelopeDecryptor.decrypt(
            envelope = decodedEnvelope,
            keyBytes = deterministicKey(11),
            authenticatedData = authenticatedData
        )
        val decodedFrame = MessageFrameCodec.decode(String(decryptedFrameBytes, UTF_8))

        assertArrayEquals(senderPublicKey, decodedEnvelope.senderPublicKey)
        assertEquals(resolvedFrame, decodedFrame)
        assertEquals("global-envelope-1", decodedFrame.id)
        assertEquals(MessageFrameType.GLOBAL_TEXT, decodedFrame.type)
        assertEquals("sender-global", decodedFrame.senderId)
        assertEquals("hello encrypted global", decodedFrame.payload)
        assertEquals(1_715_200_001L, decodedFrame.createdAtMillis)
    }

    @Test
    fun resolvedPrivateMessageFrameRoundTripsThroughEncryptedEnvelopeCodecAndDecryptor() {
        val resolvedFrame = OutgoingMessageFrameResolver.resolve(
            draft = OutgoingMessageFrameDraft(
                id = "private-envelope-2",
                threadId = "private:alex",
                type = MessageFrameType.PRIVATE_TEXT,
                recipientId = "alex",
                createdAtMillis = 1_715_200_321L,
                payload = "hello encrypted private"
            ),
            senderId = "sender-private"
        )
        val plaintextFrameBytes = MessageFrameCodec.encode(resolvedFrame).toByteArray(UTF_8)
        val authenticatedData = "frame-envelope-private-aad".toByteArray(UTF_8)
        val senderPublicKey = senderPublicKeyBytes()

        val envelope = EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = senderPublicKey,
            keyBytes = deterministicKey(21),
            plaintext = plaintextFrameBytes,
            authenticatedData = authenticatedData
        )
        val encodedEnvelopeBytes = EncryptedMessageEnvelopeCodec.encode(envelope).toByteArray(UTF_8)
        val decodedEnvelope = EncryptedMessageEnvelopeCodec.decode(String(encodedEnvelopeBytes, UTF_8))
        val decryptedFrameBytes = EncryptedMessageEnvelopeDecryptor.decrypt(
            envelope = decodedEnvelope,
            keyBytes = deterministicKey(21),
            authenticatedData = authenticatedData
        )
        val decodedFrame = MessageFrameCodec.decode(String(decryptedFrameBytes, UTF_8))

        assertArrayEquals(senderPublicKey, decodedEnvelope.senderPublicKey)
        assertEquals(resolvedFrame, decodedFrame)
        assertEquals("private-envelope-2", decodedFrame.id)
        assertEquals(MessageFrameType.PRIVATE_TEXT, decodedFrame.type)
        assertEquals("sender-private", decodedFrame.senderId)
        assertEquals("alex", decodedFrame.recipientId)
        assertEquals("hello encrypted private", decodedFrame.payload)
        assertEquals(1_715_200_321L, decodedFrame.createdAtMillis)
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
