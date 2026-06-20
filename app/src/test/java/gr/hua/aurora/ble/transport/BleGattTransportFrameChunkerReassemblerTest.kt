package gr.hua.aurora.ble.transport

import gr.hua.aurora.crypto.Sec1PublicKeyEncoding
import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.model.OutgoingChatMessage
import gr.hua.aurora.protocol.EncryptedMessageEnvelopeBuilder
import gr.hua.aurora.protocol.EncryptedMessageEnvelopeCodec
import gr.hua.aurora.protocol.EncryptedMessageEnvelopeDecryptor
import gr.hua.aurora.protocol.MessageFrameCodec
import gr.hua.aurora.protocol.MessageFrameType
import gr.hua.aurora.protocol.OutgoingMessageFrameBuilder
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

class BleGattTransportFrameChunkerReassemblerTest {
    @Test
    fun oneFramePayloadRoundTrips() {
        val envelopeBytes = ByteArray(BleGattTransportChunk.MAX_PAYLOAD_SIZE) { index ->
            (index + 1).toByte()
        }

        val frames = BleGattTransportFrameChunker.chunk(
            encodedEnvelopeBytes = envelopeBytes,
            groupId = 0x1201
        )
        val reassembled = BleGattTransportFrameReassembler.reassemble(frames)

        assertEquals(1, frames.size)
        assertArrayEquals(envelopeBytes, reassembled)
    }

    @Test
    fun multiFramePayloadRoundTrips() {
        val envelopeBytes = ByteArray(BleGattTransportChunk.MAX_PAYLOAD_SIZE * 3 + 4) { index ->
            (index + 7).toByte()
        }

        val frames = BleGattTransportFrameChunker.chunk(
            encodedEnvelopeBytes = envelopeBytes,
            groupId = 0x1202
        )
        val reassembled = BleGattTransportFrameReassembler.reassemble(frames)

        assertTrue(frames.size > 1)
        assertArrayEquals(envelopeBytes, reassembled)
    }

    @Test
    fun outOfOrderChunksStillReassembleFromMetadata() {
        val envelopeBytes = ByteArray(BleGattTransportChunk.MAX_PAYLOAD_SIZE * 2 + 3) { index ->
            (index + 9).toByte()
        }
        val frames = BleGattTransportFrameChunker.chunk(
            encodedEnvelopeBytes = envelopeBytes,
            groupId = 0x1203
        )

        val reassembled = BleGattTransportFrameReassembler.reassemble(frames.reversed())

        assertArrayEquals(envelopeBytes, reassembled)
    }

    @Test
    fun missingChunkFailsReassembly() {
        val envelopeBytes = ByteArray(BleGattTransportChunk.MAX_PAYLOAD_SIZE * 2 + 2) { index ->
            (index + 11).toByte()
        }
        val frames = BleGattTransportFrameChunker.chunk(
            encodedEnvelopeBytes = envelopeBytes,
            groupId = 0x1204
        )

        assertThrows(IllegalArgumentException::class.java) {
            BleGattTransportFrameReassembler.reassemble(listOf(frames.first(), frames.last()))
        }
    }

    @Test
    fun duplicateChunkFailsReassembly() {
        val envelopeBytes = ByteArray(BleGattTransportChunk.MAX_PAYLOAD_SIZE * 2 + 1) { index ->
            (index + 13).toByte()
        }
        val frames = BleGattTransportFrameChunker.chunk(
            encodedEnvelopeBytes = envelopeBytes,
            groupId = 0x1205
        )
        val duplicated = listOf(frames[0], frames[0], frames[1])

        assertThrows(IllegalArgumentException::class.java) {
            BleGattTransportFrameReassembler.reassemble(duplicated)
        }
    }

    @Test
    fun emptyPayloadIsRejectedClearly() {
        assertThrows(IllegalArgumentException::class.java) {
            BleGattTransportFrameChunker.chunk(
                encodedEnvelopeBytes = byteArrayOf(),
                groupId = 0x1206
            )
        }
    }

    @Test
    fun encodedGlobalEnvelopeBytesRoundTripThroughChunkingAndDecryptToSameFrame() {
        val queuedMessage = OutgoingChatMessage(
            messageId = "queued-global-chunk-1",
            threadId = "global",
            userText = "hello chunked global",
            createdAtMillis = 1_715_220_001L,
            status = MessageStatus.QUEUED
        )
        val originalQueuedMessage = queuedMessage.copy()
        val draft = OutgoingMessageFrameBuilder.build(queuedMessage)
        val resolvedFrame = OutgoingMessageFrameResolver.resolve(
            draft = draft,
            senderId = "sender-global"
        )
        val authenticatedData = "chunked-global-aad".toByteArray(UTF_8)
        val envelope = EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = senderPublicKeyBytes(),
            keyBytes = deterministicKey(61),
            plaintext = MessageFrameCodec.encode(resolvedFrame).toByteArray(UTF_8),
            authenticatedData = authenticatedData
        )
        val encodedEnvelopeBytes = EncryptedMessageEnvelopeCodec.encode(envelope).toByteArray(UTF_8)
        val frames = BleGattTransportFrameChunker.chunk(
            encodedEnvelopeBytes = encodedEnvelopeBytes,
            groupId = 0x1207
        )

        val reassembledBytes = BleGattTransportFrameReassembler.reassemble(frames)
        val decodedEnvelope = EncryptedMessageEnvelopeCodec.decode(String(reassembledBytes, UTF_8))
        val decryptedFrameBytes = EncryptedMessageEnvelopeDecryptor.decrypt(
            envelope = decodedEnvelope,
            keyBytes = deterministicKey(61),
            authenticatedData = authenticatedData
        )
        val decodedFrame = MessageFrameCodec.decode(String(decryptedFrameBytes, UTF_8))

        assertEquals(originalQueuedMessage, queuedMessage)
        assertEquals(MessageStatus.QUEUED, queuedMessage.status)
        assertArrayEquals(encodedEnvelopeBytes, reassembledBytes)
        assertEquals(resolvedFrame, decodedFrame)
    }

    @Test
    fun encodedPrivateEnvelopeBytesRoundTripThroughChunkingAndDecryptToSameFrame() {
        val queuedMessage = OutgoingChatMessage(
            messageId = "queued-private-chunk-2",
            threadId = "private:alex",
            userText = "hello chunked private",
            createdAtMillis = 1_715_220_321L,
            status = MessageStatus.QUEUED
        )
        val originalQueuedMessage = queuedMessage.copy()
        val draft = OutgoingMessageFrameBuilder.build(queuedMessage)
        val resolvedFrame = OutgoingMessageFrameResolver.resolve(
            draft = draft,
            senderId = "sender-private"
        )
        val authenticatedData = "chunked-private-aad".toByteArray(UTF_8)
        val envelope = EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = senderPublicKeyBytes(),
            keyBytes = deterministicKey(71),
            plaintext = MessageFrameCodec.encode(resolvedFrame).toByteArray(UTF_8),
            authenticatedData = authenticatedData
        )
        val encodedEnvelopeBytes = EncryptedMessageEnvelopeCodec.encode(envelope).toByteArray(UTF_8)
        val frames = BleGattTransportFrameChunker.chunk(
            encodedEnvelopeBytes = encodedEnvelopeBytes,
            groupId = 0x1208
        )

        val reassembledBytes = BleGattTransportFrameReassembler.reassemble(frames.reversed())
        val decodedEnvelope = EncryptedMessageEnvelopeCodec.decode(String(reassembledBytes, UTF_8))
        val decryptedFrameBytes = EncryptedMessageEnvelopeDecryptor.decrypt(
            envelope = decodedEnvelope,
            keyBytes = deterministicKey(71),
            authenticatedData = authenticatedData
        )
        val decodedFrame = MessageFrameCodec.decode(String(decryptedFrameBytes, UTF_8))

        assertEquals(originalQueuedMessage, queuedMessage)
        assertEquals(MessageStatus.QUEUED, queuedMessage.status)
        assertArrayEquals(encodedEnvelopeBytes, reassembledBytes)
        assertEquals(resolvedFrame, decodedFrame)
        assertEquals(MessageFrameType.PRIVATE_TEXT, decodedFrame.type)
        assertEquals("alex", decodedFrame.recipientId)
    }

    @Test
    fun mismatchedGroupIdFailsReassembly() {
        val first = checkNotNull(
            BleGattTransportChunk.create(
                groupId = 0x2001,
                chunkIndex = 0,
                totalChunks = 2,
                payload = byteArrayOf(0x01)
            )
        ).toFrame()
        val second = checkNotNull(
            BleGattTransportChunk.create(
                groupId = 0x2002,
                chunkIndex = 1,
                totalChunks = 2,
                payload = byteArrayOf(0x02)
            )
        ).toFrame()

        assertThrows(IllegalArgumentException::class.java) {
            BleGattTransportFrameReassembler.reassemble(listOf(checkNotNull(first), checkNotNull(second)))
        }
    }

    @Test
    fun encodedEnvelopeBytesDoNotAssumeSingleBleWrite() {
        val resolvedFrame = OutgoingMessageFrameResolver.resolve(
            draft = OutgoingMessageFrameDraft(
                id = "chunk-size-proof-1",
                threadId = "global",
                type = MessageFrameType.GLOBAL_TEXT,
                createdAtMillis = 1_715_220_777L,
                payload = "hello chunk size proof"
            ),
            senderId = "sender-proof"
        )
        val envelope = EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = senderPublicKeyBytes(),
            keyBytes = deterministicKey(81),
            plaintext = MessageFrameCodec.encode(resolvedFrame).toByteArray(UTF_8)
        )
        val encodedEnvelopeBytes = EncryptedMessageEnvelopeCodec.encode(envelope).toByteArray(UTF_8)
        val frames = BleGattTransportFrameChunker.chunk(
            encodedEnvelopeBytes = encodedEnvelopeBytes,
            groupId = 0x1209
        )

        assertTrue(encodedEnvelopeBytes.size > BleGattTransportChunk.MAX_PAYLOAD_SIZE)
        assertTrue(frames.size > 1)
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
