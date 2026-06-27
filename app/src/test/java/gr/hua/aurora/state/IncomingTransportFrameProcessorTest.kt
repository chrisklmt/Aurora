package gr.hua.aurora.state

import gr.hua.aurora.ble.transport.BleGattTransportFrame
import gr.hua.aurora.ble.transport.BleGattTransportFrameChunker
import gr.hua.aurora.crypto.Sec1PublicKeyEncoding
import gr.hua.aurora.data.LocalProfileSettings
import gr.hua.aurora.data.LocalProfileSettingsStore
import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.protocol.EncryptedMessageEnvelopeBuilder
import gr.hua.aurora.protocol.EncryptedMessageEnvelopeCodec
import gr.hua.aurora.protocol.IncomingMessageReceiveDecryptionMaterial
import gr.hua.aurora.protocol.IncomingSessionMaterialLookupResult
import gr.hua.aurora.protocol.IncomingSessionMaterialProvider
import gr.hua.aurora.protocol.IncomingTransportMessage
import gr.hua.aurora.protocol.IncomingTransportReceiveResult
import gr.hua.aurora.protocol.MessageFrame
import gr.hua.aurora.protocol.MessageFrameCodec
import gr.hua.aurora.protocol.MessageFrameType
import gr.hua.aurora.protocol.PeerIdentityExchangeHandlingResult
import gr.hua.aurora.protocol.PrivateChatMessagePayload
import gr.hua.aurora.protocol.PrivateChatMessagePayloadCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class IncomingTransportFrameProcessorTest {
    @Test
    fun validGlobalFramesAreReceivedAndIngestedIntoState() {
        val holder = createHolder()
        val queuedMessage = requireNotNull(holder.sendGlobalPreviewMessage("queued outgoing"))
        val queueBefore = holder.uiState.pendingOutgoingMessages.toList()
        val senderPublicKey = senderPublicKeyBytes()
        val keyBytes = deterministicKey(81)
        val authenticatedData = "processor-global-aad".toByteArray(UTF_8)
        val frame = MessageFrame(
            id = "processor-global-1",
            type = MessageFrameType.GLOBAL_TEXT,
            senderId = "peer-global",
            createdAtMillis = 1_715_500_001L,
            payload = "hello processor"
        )
        val frames = transportFramesFor(
            frame = frame,
            senderPublicKey = senderPublicKey,
            keyBytes = keyBytes,
            authenticatedData = authenticatedData,
            groupId = 0x3201
        )

        val result = IncomingTransportFrameProcessor.process(
            frames = frames,
            sessionMaterialProvider = sessionMaterialProvider(
                expectedSenderPublicKey = senderPublicKey,
                keyBytes = keyBytes,
                authenticatedData = authenticatedData
            ),
            stateHolder = holder
        )

        assertTrue(result is IncomingTransportFrameProcessingResult.Received)
        val receivedResult = result as IncomingTransportFrameProcessingResult.Received
        assertTrue(receivedResult.ingestionResult is IncomingMessageIngestionResult.Appended)
        val appendedMessage = holder.uiState.globalMessages
            .single { it.id == frame.id }
        assertEquals(frame.id, receivedResult.message.frame.id)
        assertEquals(frame.id, appendedMessage.id)
        assertEquals(frame.senderId, appendedMessage.senderId)
        assertEquals(frame.payload, appendedMessage.text)
        assertEquals(frame.createdAtMillis, appendedMessage.createdAtMillis)
        assertEquals(MessageStatus.RECEIVED, appendedMessage.status)
        assertEquals(false, appendedMessage.isOutgoing)
        assertNotEquals(MessageStatus.SENT, appendedMessage.status)
        assertNotEquals(MessageStatus.DELIVERED, appendedMessage.status)
        assertEquals(queueBefore, holder.uiState.pendingOutgoingMessages)
        assertEquals(queuedMessage.messageId, holder.uiState.pendingOutgoingMessages.single().messageId)
    }

    @Test
    fun duplicateFramesDoNotAppendTwice() {
        val holder = createHolder()
        val senderPublicKey = senderPublicKeyBytes()
        val keyBytes = deterministicKey(82)
        val authenticatedData = "processor-duplicate-aad".toByteArray(UTF_8)
        val frame = MessageFrame(
            id = "processor-duplicate-1",
            type = MessageFrameType.GLOBAL_TEXT,
            senderId = "peer-duplicate",
            createdAtMillis = 1_715_500_111L,
            payload = "duplicate me"
        )
        val frames = transportFramesFor(
            frame = frame,
            senderPublicKey = senderPublicKey,
            keyBytes = keyBytes,
            authenticatedData = authenticatedData,
            groupId = 0x3202
        )

        val firstResult = IncomingTransportFrameProcessor.process(
            frames = frames,
            sessionMaterialProvider = sessionMaterialProvider(
                expectedSenderPublicKey = senderPublicKey,
                keyBytes = keyBytes,
                authenticatedData = authenticatedData
            ),
            stateHolder = holder
        )
        val sizeAfterFirst = holder.uiState.globalMessages.size

        val duplicateResult = IncomingTransportFrameProcessor.process(
            frames = frames,
            sessionMaterialProvider = sessionMaterialProvider(
                expectedSenderPublicKey = senderPublicKey,
                keyBytes = keyBytes,
                authenticatedData = authenticatedData
            ),
            stateHolder = holder
        )

        assertTrue(firstResult is IncomingTransportFrameProcessingResult.Received)
        assertTrue(duplicateResult is IncomingTransportFrameProcessingResult.Received)
        val ingestionResult =
            (duplicateResult as IncomingTransportFrameProcessingResult.Received).ingestionResult
        assertTrue(ingestionResult is IncomingMessageIngestionResult.Duplicate)
        assertEquals(frame.id, (ingestionResult as IncomingMessageIngestionResult.Duplicate).messageId)
        assertEquals(sizeAfterFirst, holder.uiState.globalMessages.size)
    }

    @Test
    fun receiveFailureDoesNotCallIngestion() {
        var ingestionCalls = 0
        var identityCalls = 0

        val result = IncomingTransportFrameProcessor.process(
            frames = emptyList(),
            sessionMaterialProvider = sessionMaterialProvider(
                expectedSenderPublicKey = senderPublicKeyBytes(),
                keyBytes = deterministicKey(83)
            ),
            ingest = {
                ingestionCalls += 1
                IncomingMessageIngestionResult.Duplicate(it.frame.id)
            },
            handleIdentity = {
                identityCalls += 1
                PeerIdentityExchangeHandlingResult.Established(
                    peerId = it.frame.senderId
                )
            },
            receive = { _, _ ->
                IncomingTransportReceiveResult.InvalidEnvelope(
                    reason = "bad envelope"
                )
            }
        )

        assertTrue(result is IncomingTransportFrameProcessingResult.ReceiveFailed)
        assertEquals(0, ingestionCalls)
        assertEquals(0, identityCalls)
        assertTrue(
            (result as IncomingTransportFrameProcessingResult.ReceiveFailed).receiveResult
                is IncomingTransportReceiveResult.InvalidEnvelope
        )
    }

    @Test
    fun privateFrameIsReceivedIntoPrivateChatWithoutTouchingGlobalChat() {
        val holder = createHolder()
        holder.addOrUpdateContact(
            canonicalPeerId = "peer-private",
            displayName = "Alex",
            hasSession = true
        )
        val privateChatIdentity = requireNotNull(
            holder.recordReceivedPrivateChatProposal(
                peerId = "peer-private",
                remoteProposalId = "remote-peer-private"
            )
        )
        val queuedMessage = requireNotNull(holder.sendGlobalPreviewMessage("queued outgoing"))
        val queueBefore = holder.uiState.pendingOutgoingMessages.toList()
        val senderPublicKey = senderPublicKeyBytes()
        val keyBytes = deterministicKey(84)
        val authenticatedData = "processor-private-aad".toByteArray(UTF_8)
        val frame = MessageFrame(
            id = "processor-private-1",
            type = MessageFrameType.PRIVATE_TEXT,
            senderId = "peer-private",
            recipientId = "self",
            createdAtMillis = 1_715_500_222L,
            payload = PrivateChatMessagePayloadCodec.encode(
                PrivateChatMessagePayload(
                    privateChatId = requireNotNull(privateChatIdentity.privateChatId),
                    senderUsername = "Alex",
                    body = "private payload"
                )
            )
        )
        val frames = transportFramesFor(
            frame = frame,
            senderPublicKey = senderPublicKey,
            keyBytes = keyBytes,
            authenticatedData = authenticatedData,
            groupId = 0x3203
        )
        val globalCountBefore = holder.uiState.globalMessages.size

        val result = IncomingTransportFrameProcessor.process(
            frames = frames,
            sessionMaterialProvider = sessionMaterialProvider(
                expectedSenderPublicKey = senderPublicKey,
                keyBytes = keyBytes,
                authenticatedData = authenticatedData
            ),
            stateHolder = holder
        )

        assertTrue(result is IncomingTransportFrameProcessingResult.Received)
        val ingestionResult =
            (result as IncomingTransportFrameProcessingResult.Received).ingestionResult
        assertTrue(ingestionResult is IncomingMessageIngestionResult.Appended)
        assertEquals(globalCountBefore, holder.uiState.globalMessages.size)
        assertEquals(queueBefore, holder.uiState.pendingOutgoingMessages)
        assertEquals(queuedMessage.messageId, holder.uiState.pendingOutgoingMessages.single().messageId)
        val privateMessage = holder.privateMessagesForPeerId("peer-private").single()
        assertEquals(frame.id, privateMessage.id)
        assertEquals("private:peer-private", privateMessage.threadId)
        assertEquals("private payload", privateMessage.text)
        assertEquals(MessageStatus.RECEIVED, privateMessage.status)
    }

    @Test
    fun identityExchangeFrameCallsIdentityHandlerAndSkipsChatIngestor() {
        var identityCalls = 0
        var ingestionCalls = 0
        val message = IncomingTransportMessage(
            frame = MessageFrame(
                id = "identity-frame-1",
                type = MessageFrameType.IDENTITY_EXCHANGE,
                senderId = "peer-identity",
                createdAtMillis = 1_715_500_333L,
                payload = "identity-payload"
            ),
            senderPublicKey = senderPublicKeyBytes()
        )

        val result = IncomingTransportFrameProcessor.process(
            frames = emptyList(),
            sessionMaterialProvider = sessionMaterialProvider(
                expectedSenderPublicKey = requireNotNull(message.senderPublicKey),
                keyBytes = deterministicKey(85)
            ),
            ingest = {
                ingestionCalls += 1
                IncomingMessageIngestionResult.Duplicate(it.frame.id)
            },
            handleIdentity = {
                identityCalls += 1
                PeerIdentityExchangeHandlingResult.Established(
                    peerId = it.frame.senderId
                )
            },
            receive = { _, _ ->
                IncomingTransportReceiveResult.Received(message)
            }
        )

        assertTrue(result is IncomingTransportFrameProcessingResult.IdentityHandled)
        val handled = result as IncomingTransportFrameProcessingResult.IdentityHandled
        assertEquals(1, identityCalls)
        assertEquals(0, ingestionCalls)
        assertEquals(message, handled.message)
        assertEquals(
            PeerIdentityExchangeHandlingResult.Established(peerId = "peer-identity"),
            handled.handlingResult
        )
    }

    @Test
    fun successfulIdentityEstablishmentResultIsSurfacedWithoutChatInsertion() {
        val holder = createHolder()
        val queuedMessage = requireNotNull(holder.sendGlobalPreviewMessage("queued outgoing"))
        val queueBefore = holder.uiState.pendingOutgoingMessages.toList()
        val globalCountBefore = holder.uiState.globalMessages.size
        val message = IncomingTransportMessage(
            frame = MessageFrame(
                id = "identity-frame-2",
                type = MessageFrameType.IDENTITY_EXCHANGE,
                senderId = "peer-established",
                createdAtMillis = 1_715_500_444L,
                payload = "identity-payload"
            ),
            senderPublicKey = senderPublicKeyBytes()
        )

        val result = IncomingTransportFrameProcessor.process(
            frames = emptyList(),
            sessionMaterialProvider = sessionMaterialProvider(
                expectedSenderPublicKey = requireNotNull(message.senderPublicKey),
                keyBytes = deterministicKey(86)
            ),
            stateHolder = holder,
            handleIdentity = {
                PeerIdentityExchangeHandlingResult.Established(
                    peerId = "peer-established"
                )
            },
            receive = { _, _ ->
                IncomingTransportReceiveResult.Received(message)
            }
        )

        assertTrue(result is IncomingTransportFrameProcessingResult.IdentityHandled)
        assertEquals(globalCountBefore, holder.uiState.globalMessages.size)
        assertEquals(queueBefore, holder.uiState.pendingOutgoingMessages)
        assertEquals(queuedMessage.messageId, holder.uiState.pendingOutgoingMessages.single().messageId)
    }

    @Test
    fun identityFailureResultIsSurfaced() {
        val message = IncomingTransportMessage(
            frame = MessageFrame(
                id = "identity-frame-3",
                type = MessageFrameType.IDENTITY_EXCHANGE,
                senderId = "peer-invalid",
                createdAtMillis = 1_715_500_555L,
                payload = "identity-payload"
            ),
            senderPublicKey = senderPublicKeyBytes()
        )

        val result = IncomingTransportFrameProcessor.process(
            frames = emptyList(),
            sessionMaterialProvider = sessionMaterialProvider(
                expectedSenderPublicKey = requireNotNull(message.senderPublicKey),
                keyBytes = deterministicKey(87)
            ),
            ingest = {
                IncomingMessageIngestionResult.Duplicate(it.frame.id)
            },
            handleIdentity = {
                PeerIdentityExchangeHandlingResult.InvalidRemotePublicKey(
                    reason = "invalid remote key"
                )
            },
            receive = { _, _ ->
                IncomingTransportReceiveResult.Received(message)
            }
        )

        assertTrue(result is IncomingTransportFrameProcessingResult.IdentityHandled)
        val handled = result as IncomingTransportFrameProcessingResult.IdentityHandled
        assertEquals(
            PeerIdentityExchangeHandlingResult.InvalidRemotePublicKey(
                reason = "invalid remote key"
            ),
            handled.handlingResult
        )
    }

    @Test
    fun identityFrameWithoutHandlerSurfacesUnavailableAndSkipsChatInsertion() {
        val holder = createHolder()
        val queuedMessage = requireNotNull(holder.sendGlobalPreviewMessage("queued outgoing"))
        val queueBefore = holder.uiState.pendingOutgoingMessages.toList()
        val globalCountBefore = holder.uiState.globalMessages.size
        val message = IncomingTransportMessage(
            frame = MessageFrame(
                id = "identity-frame-4",
                type = MessageFrameType.IDENTITY_EXCHANGE,
                senderId = "peer-unavailable",
                createdAtMillis = 1_715_500_666L,
                payload = "identity-payload"
            ),
            senderPublicKey = senderPublicKeyBytes()
        )

        val result = IncomingTransportFrameProcessor.process(
            frames = emptyList(),
            sessionMaterialProvider = sessionMaterialProvider(
                expectedSenderPublicKey = requireNotNull(message.senderPublicKey),
                keyBytes = deterministicKey(88)
            ),
            stateHolder = holder,
            receive = { _, _ ->
                IncomingTransportReceiveResult.Received(message)
            }
        )

        assertTrue(result is IncomingTransportFrameProcessingResult.IdentityHandlingUnavailable)
        val unavailable = result as IncomingTransportFrameProcessingResult.IdentityHandlingUnavailable
        assertTrue(unavailable.reason.contains("identity material", ignoreCase = true))
        assertEquals(globalCountBefore, holder.uiState.globalMessages.size)
        assertEquals(queueBefore, holder.uiState.pendingOutgoingMessages)
        assertEquals(queuedMessage.messageId, holder.uiState.pendingOutgoingMessages.single().messageId)
    }

    private fun createHolder(): AuroraStateHolder {
        return AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
    }

    private fun transportFramesFor(
        frame: MessageFrame,
        senderPublicKey: ByteArray,
        keyBytes: ByteArray,
        authenticatedData: ByteArray?,
        groupId: Int
    ): List<BleGattTransportFrame> {
        val envelope = EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = senderPublicKey,
            keyBytes = keyBytes,
            plaintext = MessageFrameCodec.encode(frame).toByteArray(UTF_8),
            authenticatedData = authenticatedData
        )

        return BleGattTransportFrameChunker.chunk(
            encodedEnvelopeBytes = EncryptedMessageEnvelopeCodec.encode(envelope).toByteArray(UTF_8),
            groupId = groupId
        )
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

    private fun sessionMaterialProvider(
        expectedSenderPublicKey: ByteArray,
        keyBytes: ByteArray,
        authenticatedData: ByteArray? = null
    ): IncomingSessionMaterialProvider {
        return IncomingSessionMaterialProvider { envelope ->
            if (envelope.senderPublicKey.contentEquals(expectedSenderPublicKey)) {
                IncomingSessionMaterialLookupResult.Found(
                    material = IncomingMessageReceiveDecryptionMaterial(
                        keyBytes = keyBytes,
                        authenticatedData = authenticatedData
                    )
                )
            } else {
                IncomingSessionMaterialLookupResult.UnsupportedSender(
                    reason = "Incoming sender is not supported."
                )
            }
        }
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
