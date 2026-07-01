package gr.hua.aurora.protocol

import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.model.OutgoingChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class PrivateChatTransportFrameFactoryTest {
    @Test
    fun factoryBuildsEncryptedPrivateTextFrameForPrivateThread() {
        val material = testEncryptionMaterial()

        val preparedFrame = PrivateChatTransportFrameFactory.build(
            message = OutgoingChatMessage(
                messageId = "private-frame-1",
                threadId = "private:alex",
                userText = "hello private",
                createdAtMillis = 1_715_260_101L,
                status = MessageStatus.QUEUED
            ),
            privateChatId = "chat-alex",
            senderPeerId = "sender-canonical",
            senderUsername = "Alice",
            encryptionMaterial = material
        )

        val decodedFrame = decodePreparedFrame(preparedFrame, material)
        val decodedPayload = PrivateChatMessagePayloadCodec.decode(decodedFrame.payload)

        assertEquals("alex", preparedFrame.targetPeerId)
        assertEquals(MessageFrameType.PRIVATE_TEXT, preparedFrame.frame.type)
        assertEquals(MessageFrameType.PRIVATE_TEXT, decodedFrame.type)
        assertEquals(MessageFrameType.PRIVATE_TEXT, preparedFrame.encryptedEnvelope.relayMetadata?.messageType)
        assertEquals("sender-canonical", decodedFrame.senderId)
        assertEquals("alex", decodedFrame.recipientId)
        assertEquals("chat-alex", decodedPayload.privateChatId)
        assertEquals("Alice", decodedPayload.senderUsername)
        assertEquals("hello private", decodedPayload.body)
        assertTrue(decodedFrame.type != MessageFrameType.GLOBAL_TEXT)
    }

    @Test
    fun factoryRejectsMissingPrivateChatIdCleanly() {
        val error = runCatching {
            PrivateChatTransportFrameFactory.build(
                message = privateOutgoingMessage(),
                privateChatId = " ",
                senderPeerId = "sender-canonical",
                senderUsername = "Alice",
                encryptionMaterial = testEncryptionMaterial()
            )
        }.exceptionOrNull()

        requireNotNull(error)
        assertTrue(error is IllegalArgumentException)
        assertTrue(
            requireNotNull(error.message).contains("privateChatId", ignoreCase = true)
        )
    }

    @Test
    fun factoryRejectsMissingEncryptionMaterialCleanly() {
        val error = runCatching {
            PrivateChatTransportFrameFactory.build(
                message = privateOutgoingMessage(),
                privateChatId = "chat-alex",
                senderPeerId = "sender-canonical",
                senderUsername = "Alice",
                encryptionMaterial = null
            )
        }.exceptionOrNull()

        requireNotNull(error)
        assertTrue(error is IllegalArgumentException)
        assertEquals(
            "Private chat encryption material is required.",
            error.message
        )
    }

    @Test
    fun factoryRejectsNonPrivateThreadInsteadOfProducingGlobalText() {
        val error = runCatching {
            PrivateChatTransportFrameFactory.build(
                message = OutgoingChatMessage(
                    messageId = "private-frame-2",
                    threadId = "global",
                    userText = "hello global",
                    createdAtMillis = 1_715_260_102L,
                    status = MessageStatus.QUEUED
                ),
                privateChatId = "chat-alex",
                senderPeerId = "sender-canonical",
                senderUsername = "Alice",
                encryptionMaterial = testEncryptionMaterial()
            )
        }.exceptionOrNull()

        requireNotNull(error)
        assertTrue(error is IllegalArgumentException)
        assertEquals(
            "Private chat transport requires PRIVATE_TEXT messages.",
            error.message
        )
    }

    @Test
    fun factoryRejectsInvalidEncryptionMaterialCleanly() {
        val error = runCatching {
            PrivateChatTransportFrameFactory.build(
                message = privateOutgoingMessage(),
                privateChatId = "chat-alex",
                senderPeerId = "sender-canonical",
                senderUsername = "Alice",
                encryptionMaterial = OutgoingMessageSendEncryptionMaterial(
                    senderPublicKey = senderPublicKeyBytes(),
                    keyBytes = ByteArray(16),
                    authenticatedData = "private-chat-aad".toByteArray(UTF_8)
                )
            )
        }.exceptionOrNull()

        requireNotNull(error)
        assertTrue(error is IllegalArgumentException)
        assertTrue(
            requireNotNull(error.message).contains("32-byte keys")
        )
    }

    private fun privateOutgoingMessage(): OutgoingChatMessage {
        return OutgoingChatMessage(
            messageId = "private-frame-3",
            threadId = "private:alex",
            userText = "hello private",
            createdAtMillis = 1_715_260_103L,
            status = MessageStatus.QUEUED
        )
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

    private fun decodePreparedFrame(
        preparedFrame: PreparedPrivateChatTransportFrame,
        material: OutgoingMessageSendEncryptionMaterial
    ): MessageFrame {
        val frameBytes = EncryptedMessageEnvelopeDecryptor.decrypt(
            envelope = preparedFrame.encryptedEnvelope,
            keyBytes = material.keyBytes,
            authenticatedData = material.authenticatedData
        )
        return MessageFrameCodec.decode(String(frameBytes, UTF_8))
    }
}
