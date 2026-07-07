package gr.hua.aurora.protocol

import gr.hua.aurora.ble.transport.BleGattTransportChunk
import gr.hua.aurora.ble.transport.BleGattTransportFrame
import gr.hua.aurora.ble.transport.BleGattTransportFrameChunker
import gr.hua.aurora.crypto.Sec1PublicKeyEncoding
import gr.hua.aurora.transport.hybrid.HybridTransportControlFrameFactory
import gr.hua.aurora.transport.hybrid.HybridTransportControlMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class IncomingMessageReceiveUseCaseTest {
    @Test
    fun validPublicGlobalMessageReceiveFlowPreservesFieldsWithoutDecryption() {
        val frame = MessageFrame(
            id = "incoming-global-1",
            type = MessageFrameType.GLOBAL_TEXT,
            senderId = "peer-global",
            createdAtMillis = 1_715_400_001L,
            payload = "hello incoming global"
        )
        val frames = BleGattTransportFrameChunker.chunk(
            encodedEnvelopeBytes = MessageFrameCodec.encode(frame).toByteArray(UTF_8),
            groupId = 0x3101
        )

        val result = IncomingMessageReceiveUseCase.receive(
            frames = frames,
            sessionMaterialProvider = NoOpIncomingSessionMaterialProvider
        )

        assertTrue(result is IncomingTransportReceiveResult.Received)
        val message = (result as IncomingTransportReceiveResult.Received).message
        assertEquals(frame.id, message.frame.id)
        assertEquals(frame.type, message.frame.type)
        assertEquals(frame.senderId, message.frame.senderId)
        assertEquals(frame.recipientId, message.frame.recipientId)
        assertEquals(frame.payload, message.frame.payload)
        assertEquals(frame.createdAtMillis, message.frame.createdAtMillis)
        assertEquals(null, message.senderPublicKey)
    }

    @Test
    fun validPrivateMessageReceiveFlowPreservesFields() {
        val senderPublicKey = senderPublicKeyBytes()
        val frame = MessageFrame(
            id = "incoming-private-2",
            type = MessageFrameType.PRIVATE_TEXT,
            senderId = "peer-private",
            recipientId = "self",
            createdAtMillis = 1_715_400_321L,
            payload = "hello incoming private"
        )
        val keyBytes = deterministicKey(21)
        val authenticatedData = "incoming-private-aad".toByteArray(UTF_8)
        val frames = transportFramesFor(
            frame = frame,
            senderPublicKey = senderPublicKey,
            keyBytes = keyBytes,
            authenticatedData = authenticatedData,
            groupId = 0x3102
        )

        val result = IncomingMessageReceiveUseCase.receive(
            frames = frames,
            sessionMaterialProvider = matchingSenderProvider(
                expectedSenderPublicKey = senderPublicKey,
                decryptionMaterial = IncomingMessageReceiveDecryptionMaterial(
                keyBytes = keyBytes,
                authenticatedData = authenticatedData
                )
            )
        )

        assertTrue(result is IncomingTransportReceiveResult.Received)
        val message = (result as IncomingTransportReceiveResult.Received).message
        assertEquals(frame.id, message.frame.id)
        assertEquals(frame.type, message.frame.type)
        assertEquals(frame.senderId, message.frame.senderId)
        assertEquals(frame.recipientId, message.frame.recipientId)
        assertEquals(frame.payload, message.frame.payload)
        assertEquals(frame.createdAtMillis, message.frame.createdAtMillis)
        assertArrayEquals(senderPublicKey, message.senderPublicKey)
    }

    @Test
    fun outOfOrderChunkArrivalStillReceivesMessage() {
        val payload = "incoming ".repeat(BleGattTransportChunk.MAX_PAYLOAD_SIZE)
        val frame = MessageFrame(
            id = "incoming-global-3",
            type = MessageFrameType.GLOBAL_TEXT,
            senderId = "peer-order",
            createdAtMillis = 1_715_400_777L,
            payload = payload
        )
        val senderPublicKey = senderPublicKeyBytes()
        val keyBytes = deterministicKey(31)
        val authenticatedData = "incoming-order-aad".toByteArray(UTF_8)
        val frames = transportFramesFor(
            frame = frame,
            senderPublicKey = senderPublicKey,
            keyBytes = keyBytes,
            authenticatedData = authenticatedData,
            groupId = 0x3103
        )

        val result = IncomingMessageReceiveUseCase.receive(
            frames = frames.reversed(),
            sessionMaterialProvider = matchingSenderProvider(
                expectedSenderPublicKey = senderPublicKey,
                decryptionMaterial = IncomingMessageReceiveDecryptionMaterial(
                keyBytes = keyBytes,
                authenticatedData = authenticatedData
                )
            )
        )

        assertTrue(result is IncomingTransportReceiveResult.Received)
        assertEquals(
            frame,
            (result as IncomingTransportReceiveResult.Received).message.frame
        )
    }

    @Test
    fun incompleteChunkFailureIsReported() {
        val frame = MessageFrame(
            id = "incoming-global-4",
            type = MessageFrameType.GLOBAL_TEXT,
            senderId = "peer-missing",
            createdAtMillis = 1_715_400_999L,
            payload = "chunk ".repeat(BleGattTransportChunk.MAX_PAYLOAD_SIZE)
        )
        val senderPublicKey = senderPublicKeyBytes()
        val frames = transportFramesFor(
            frame = frame,
            senderPublicKey = senderPublicKey,
            keyBytes = deterministicKey(41),
            authenticatedData = "incoming-missing-aad".toByteArray(UTF_8),
            groupId = 0x3104
        )

        val result = IncomingMessageReceiveUseCase.receive(
            frames = frames.dropLast(1),
            sessionMaterialProvider = matchingSenderProvider(
                expectedSenderPublicKey = senderPublicKey,
                decryptionMaterial = IncomingMessageReceiveDecryptionMaterial(
                keyBytes = deterministicKey(41),
                authenticatedData = "incoming-missing-aad".toByteArray(UTF_8)
                )
            )
        )

        assertTrue(result is IncomingTransportReceiveResult.IncompleteChunks)
    }

    @Test
    fun envelopeCorruptionFailureIsReported() {
        val frame = MessageFrame(
            id = "incoming-global-5",
            type = MessageFrameType.GLOBAL_TEXT,
            senderId = "peer-envelope",
            createdAtMillis = 1_715_401_111L,
            payload = "hello envelope corruption"
        )
        val keyBytes = deterministicKey(51)
        val authenticatedData = "incoming-envelope-aad".toByteArray(UTF_8)
        val senderPublicKey = senderPublicKeyBytes()
        val encodedEnvelopeBytes = validEncodedEnvelopeBytes(
            frame = frame,
            senderPublicKey = senderPublicKey,
            keyBytes = keyBytes,
            authenticatedData = authenticatedData
        ).copyOf().also { bytes ->
            bytes[0] = '!'.code.toByte()
        }
        val frames = BleGattTransportFrameChunker.chunk(
            encodedEnvelopeBytes = encodedEnvelopeBytes,
            groupId = 0x3105
        )

        val result = IncomingMessageReceiveUseCase.receive(
            frames = frames,
            sessionMaterialProvider = matchingSenderProvider(
                expectedSenderPublicKey = senderPublicKey,
                decryptionMaterial = IncomingMessageReceiveDecryptionMaterial(
                keyBytes = keyBytes,
                authenticatedData = authenticatedData
                )
            )
        )

        assertTrue(result is IncomingTransportReceiveResult.InvalidEnvelope)
    }

    @Test
    fun decryptFailureIsReported() {
        val frame = MessageFrame(
            id = "incoming-global-6",
            type = MessageFrameType.GLOBAL_TEXT,
            senderId = "peer-decrypt",
            createdAtMillis = 1_715_401_222L,
            payload = "hello decrypt failure"
        )
        val authenticatedData = "incoming-decrypt-aad".toByteArray(UTF_8)
        val senderPublicKey = senderPublicKeyBytes()
        val frames = transportFramesFor(
            frame = frame,
            senderPublicKey = senderPublicKey,
            keyBytes = deterministicKey(61),
            authenticatedData = authenticatedData,
            groupId = 0x3106
        )

        val result = IncomingMessageReceiveUseCase.receive(
            frames = frames,
            sessionMaterialProvider = matchingSenderProvider(
                expectedSenderPublicKey = senderPublicKey,
                decryptionMaterial = IncomingMessageReceiveDecryptionMaterial(
                keyBytes = deterministicKey(62),
                authenticatedData = authenticatedData
                )
            )
        )

        assertTrue(result is IncomingTransportReceiveResult.DecryptFailed)
    }

    @Test
    fun frameCorruptionFailureIsReported() {
        val senderPublicKey = senderPublicKeyBytes()
        val keyBytes = deterministicKey(71)
        val authenticatedData = "incoming-frame-aad".toByteArray(UTF_8)
        val envelope = EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = senderPublicKey,
            keyBytes = keyBytes,
            plaintext = "not-a-valid-frame".toByteArray(UTF_8),
            authenticatedData = authenticatedData
        )
        val frames = BleGattTransportFrameChunker.chunk(
            encodedEnvelopeBytes = EncryptedMessageEnvelopeCodec.encode(envelope).toByteArray(UTF_8),
            groupId = 0x3107
        )

        val result = IncomingMessageReceiveUseCase.receive(
            frames = frames,
            sessionMaterialProvider = matchingSenderProvider(
                expectedSenderPublicKey = senderPublicKey,
                decryptionMaterial = IncomingMessageReceiveDecryptionMaterial(
                keyBytes = keyBytes,
                authenticatedData = authenticatedData
                )
            )
        )

        assertTrue(result is IncomingTransportReceiveResult.InvalidFrame)
    }

    @Test
    fun noOpProviderPathIsReportedAsSessionMaterialUnavailable() {
        val senderPublicKey = senderPublicKeyBytes()
        val frames = transportFramesFor(
            frame = MessageFrame(
                id = "incoming-global-noop",
                type = MessageFrameType.PRIVATE_TEXT,
                senderId = "peer-noop",
                recipientId = "self",
                createdAtMillis = 1_715_401_333L,
                payload = "hello no-op"
            ),
            senderPublicKey = senderPublicKey,
            keyBytes = deterministicKey(91),
            authenticatedData = "incoming-noop-aad".toByteArray(UTF_8),
            groupId = 0x3108
        )

        val result = IncomingMessageReceiveUseCase.receive(
            frames = frames,
            sessionMaterialProvider = NoOpIncomingSessionMaterialProvider
        )

        assertTrue(result is IncomingTransportReceiveResult.SessionMaterialUnavailable)
    }

    @Test
    fun materialUnavailablePathIsReported() {
        val senderPublicKey = senderPublicKeyBytes()
        val frames = transportFramesFor(
            frame = MessageFrame(
                id = "incoming-global-unavailable",
                type = MessageFrameType.PRIVATE_TEXT,
                senderId = "peer-unavailable",
                recipientId = "self",
                createdAtMillis = 1_715_401_444L,
                payload = "hello unavailable"
            ),
            senderPublicKey = senderPublicKey,
            keyBytes = deterministicKey(92),
            authenticatedData = "incoming-unavailable-aad".toByteArray(UTF_8),
            groupId = 0x3109
        )

        val result = IncomingMessageReceiveUseCase.receive(
            frames = frames,
            sessionMaterialProvider = fixedProvider(
                IncomingSessionMaterialLookupResult.MaterialUnavailable(
                    reason = "Incoming session material is not available for this peer."
                )
            )
        )

        assertTrue(result is IncomingTransportReceiveResult.SessionMaterialUnavailable)
    }

    @Test
    fun unsupportedSenderPathIsReported() {
        val senderPublicKey = senderPublicKeyBytes()
        val frames = transportFramesFor(
            frame = MessageFrame(
                id = "incoming-global-unsupported",
                type = MessageFrameType.PRIVATE_TEXT,
                senderId = "peer-unsupported",
                recipientId = "self",
                createdAtMillis = 1_715_401_555L,
                payload = "hello unsupported"
            ),
            senderPublicKey = senderPublicKey,
            keyBytes = deterministicKey(93),
            authenticatedData = "incoming-unsupported-aad".toByteArray(UTF_8),
            groupId = 0x3110
        )

        val result = IncomingMessageReceiveUseCase.receive(
            frames = frames,
            sessionMaterialProvider = fixedProvider(
                IncomingSessionMaterialLookupResult.UnsupportedSender(
                    reason = "Incoming sender is not supported."
                )
            )
        )

        assertTrue(result is IncomingTransportReceiveResult.UnsupportedSender)
    }

    @Test
    fun invalidIdentityPathIsReported() {
        val senderPublicKey = senderPublicKeyBytes()
        val frames = transportFramesFor(
            frame = MessageFrame(
                id = "incoming-global-invalid-identity",
                type = MessageFrameType.PRIVATE_TEXT,
                senderId = "peer-invalid",
                recipientId = "self",
                createdAtMillis = 1_715_401_666L,
                payload = "hello invalid identity"
            ),
            senderPublicKey = senderPublicKey,
            keyBytes = deterministicKey(94),
            authenticatedData = "incoming-invalid-identity-aad".toByteArray(UTF_8),
            groupId = 0x3111
        )

        val result = IncomingMessageReceiveUseCase.receive(
            frames = frames,
            sessionMaterialProvider = fixedProvider(
                IncomingSessionMaterialLookupResult.InvalidIdentity(
                    reason = "Incoming sender identity is invalid."
                )
            )
        )

        assertTrue(result is IncomingTransportReceiveResult.InvalidSenderIdentity)
    }

    @Test
    fun plaintextIdentityExchangeBootstrapFrameIsReceivedWithoutSessionMaterial() {
        val senderPublicKey = senderPublicKeyBytes()
        val frame = PeerIdentityExchangeMessage(
            peerId = "peer-bootstrap",
            publicAgreementKeyBytes = senderPublicKey,
            createdAtMillis = 1_715_401_777L
        ).toMessageFrame(frameId = "identity-bootstrap")
        val frames = BleGattTransportFrameChunker.chunk(
            encodedEnvelopeBytes = MessageFrameCodec.encode(frame).toByteArray(UTF_8),
            groupId = 0x3112
        )

        val result = IncomingMessageReceiveUseCase.receive(
            frames = frames,
            sessionMaterialProvider = NoOpIncomingSessionMaterialProvider
        )

        assertTrue(result is IncomingTransportReceiveResult.Received)
        val message = (result as IncomingTransportReceiveResult.Received).message
        assertEquals(frame, message.frame)
        assertArrayEquals(senderPublicKey, message.senderPublicKey)
    }

    @Test
    fun plaintextHybridTransportControlFrameIsReceivedWithoutSessionMaterial() {
        val message = HybridTransportControlMessage(
            messageType = HybridTransportControlMessage.MessageType.WIFI_DIRECT_OFFER,
            sessionId = "hybrid-session-001",
            publicPeerIdHint = "peer-hybrid",
            createdAtMillis = 1_715_401_810L,
            capabilityFlags = setOf(
                HybridTransportControlMessage.CapabilityFlag.WIFI_DIRECT_BOOTSTRAP,
                HybridTransportControlMessage.CapabilityFlag.BLE_FALLBACK
            )
        )
        val frame = HybridTransportControlFrameFactory.create(
            message = message,
            frameId = "hybrid-frame-001",
            senderId = "peer-hybrid"
        )
        val frames = BleGattTransportFrameChunker.chunk(
            encodedEnvelopeBytes = MessageFrameCodec.encode(frame).toByteArray(UTF_8),
            groupId = 0x3114
        )

        val result = IncomingMessageReceiveUseCase.receive(
            frames = frames,
            sessionMaterialProvider = NoOpIncomingSessionMaterialProvider
        )

        assertTrue(result is IncomingTransportReceiveResult.Received)
        assertEquals(frame, (result as IncomingTransportReceiveResult.Received).message.frame)
    }

    @Test
    fun privateRelayEnvelopeWithoutSessionMaterialIsSurfacedForRelayOnly() {
        val senderPublicKey = senderPublicKeyBytes()
        val frame = MessageFrame(
            id = "incoming-private-relay-only",
            type = MessageFrameType.PRIVATE_TEXT,
            senderId = "peer-private",
            recipientId = "peer-target",
            createdAtMillis = 1_715_401_888L,
            payload = "encrypted private payload"
        )
        val envelope = EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = senderPublicKey,
            keyBytes = deterministicKey(95),
            plaintext = MessageFrameCodec.encode(frame).toByteArray(UTF_8),
            authenticatedData = "incoming-relay-only-aad".toByteArray(UTF_8),
            relayMetadata = EncryptedMessageRelayMetadata(
                messageId = frame.id,
                messageType = MessageFrameType.PRIVATE_TEXT,
                ttl = 4
            )
        )
        val frames = BleGattTransportFrameChunker.chunk(
            encodedEnvelopeBytes = EncryptedMessageEnvelopeCodec.encode(envelope).toByteArray(UTF_8),
            groupId = 0x3113
        )

        val result = IncomingMessageReceiveUseCase.receive(
            frames = frames,
            sessionMaterialProvider = fixedProvider(
                IncomingSessionMaterialLookupResult.MaterialUnavailable(
                    reason = "Incoming session material is unavailable for the sender."
                )
            )
        )

        assertTrue(result is IncomingTransportReceiveResult.RelayOnlyEncrypted)
        val relayOnly = result as IncomingTransportReceiveResult.RelayOnlyEncrypted
        assertEquals(frame.id, requireNotNull(relayOnly.envelope.relayMetadata).messageId)
        assertEquals(MessageFrameType.PRIVATE_TEXT, relayOnly.envelope.relayMetadata?.messageType)
        assertEquals(4, relayOnly.envelope.relayMetadata?.ttl)
    }

    private fun transportFramesFor(
        frame: MessageFrame,
        senderPublicKey: ByteArray,
        keyBytes: ByteArray,
        authenticatedData: ByteArray?,
        groupId: Int
    ): List<BleGattTransportFrame> {
        return BleGattTransportFrameChunker.chunk(
            encodedEnvelopeBytes = validEncodedEnvelopeBytes(
                frame = frame,
                senderPublicKey = senderPublicKey,
                keyBytes = keyBytes,
                authenticatedData = authenticatedData
            ),
            groupId = groupId
        )
    }

    private fun validEncodedEnvelopeBytes(
        frame: MessageFrame,
        senderPublicKey: ByteArray,
        keyBytes: ByteArray,
        authenticatedData: ByteArray?
    ): ByteArray {
        val envelope = EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = senderPublicKey,
            keyBytes = keyBytes,
            plaintext = MessageFrameCodec.encode(frame).toByteArray(UTF_8),
            authenticatedData = authenticatedData
        )

        return EncryptedMessageEnvelopeCodec.encode(envelope).toByteArray(UTF_8)
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

    private fun matchingSenderProvider(
        expectedSenderPublicKey: ByteArray,
        decryptionMaterial: IncomingMessageReceiveDecryptionMaterial
    ): IncomingSessionMaterialProvider {
        return IncomingSessionMaterialProvider { envelope ->
            if (envelope.senderPublicKey.contentEquals(expectedSenderPublicKey)) {
                IncomingSessionMaterialLookupResult.Found(
                    material = decryptionMaterial
                )
            } else {
                IncomingSessionMaterialLookupResult.UnsupportedSender(
                    reason = "Incoming sender is not supported."
                )
            }
        }
    }

    private fun fixedProvider(
        result: IncomingSessionMaterialLookupResult
    ): IncomingSessionMaterialProvider {
        return IncomingSessionMaterialProvider { result }
    }
}
