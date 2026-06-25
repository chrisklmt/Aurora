package gr.hua.aurora.state

import gr.hua.aurora.data.LocalProfileSettings
import gr.hua.aurora.data.LocalProfileSettingsStore
import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.model.OutgoingChatMessage
import gr.hua.aurora.protocol.IncomingTransportMessage
import gr.hua.aurora.protocol.MessageFrame
import gr.hua.aurora.protocol.MessageFrameType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingChatMessageIngestorTest {
    @Test
    fun ingestGlobalIncomingMessageAppendsVisibleGlobalChatMessage() {
        val pendingQueue = listOf(
            OutgoingChatMessage(
                messageId = "queued-1",
                threadId = "global",
                userText = "queued outgoing",
                createdAtMillis = 111L,
                status = MessageStatus.QUEUED
            )
        )
        val state = SampleAuroraState.create(
            generatedUsername = "PIAIUFN1"
        ).copy(
            pendingOutgoingMessages = pendingQueue
        )
        val frame = MessageFrame(
            id = "incoming-global-1",
            type = MessageFrameType.GLOBAL_TEXT,
            senderId = "peer-alex",
            createdAtMillis = 2_345L,
            payload = "hello from remote"
        )

        val outcome = IncomingChatMessageIngestor.ingest(
            state = state,
            frame = frame
        )

        assertTrue(outcome.result is IncomingMessageIngestionResult.Appended)
        val appendedMessage = (outcome.result as IncomingMessageIngestionResult.Appended).message
        assertEquals(state.globalMessages.size + 1, outcome.updatedState.globalMessages.size)
        assertEquals(frame.id, appendedMessage.id)
        assertEquals("global", appendedMessage.threadId)
        assertEquals(frame.senderId, appendedMessage.senderId)
        assertEquals(frame.senderId, appendedMessage.senderName)
        assertEquals(frame.payload, appendedMessage.text)
        assertEquals(frame.createdAtMillis, appendedMessage.createdAtMillis)
        assertEquals(MessageStatus.RECEIVED, appendedMessage.status)
        assertEquals(false, appendedMessage.isOutgoing)
        assertNotEquals(MessageStatus.SENT, appendedMessage.status)
        assertNotEquals(MessageStatus.DELIVERED, appendedMessage.status)
        assertEquals(pendingQueue, outcome.updatedState.pendingOutgoingMessages)
    }

    @Test
    fun duplicateIncomingMessageDoesNotAppendAgain() {
        val state = SampleAuroraState.create(
            generatedUsername = "PIAIUFN1"
        )
        val frame = MessageFrame(
            id = "incoming-global-duplicate",
            type = MessageFrameType.GLOBAL_TEXT,
            senderId = "peer-alex",
            createdAtMillis = 9_999L,
            payload = "duplicate payload"
        )
        val firstOutcome = IncomingChatMessageIngestor.ingest(
            state = state,
            frame = frame
        )

        val duplicateOutcome = IncomingChatMessageIngestor.ingest(
            state = firstOutcome.updatedState,
            frame = frame
        )

        assertTrue(firstOutcome.result is IncomingMessageIngestionResult.Appended)
        assertTrue(duplicateOutcome.result is IncomingMessageIngestionResult.Duplicate)
        assertEquals(
            frame.id,
            (duplicateOutcome.result as IncomingMessageIngestionResult.Duplicate).messageId
        )
        assertEquals(
            firstOutcome.updatedState.globalMessages.size,
            duplicateOutcome.updatedState.globalMessages.size
        )
    }

    @Test
    fun privateIncomingFrameIsExplicitlyUnsupported() {
        val state = SampleAuroraState.create(
            generatedUsername = "PIAIUFN1"
        )
        val frame = MessageFrame(
            id = "incoming-private-1",
            type = MessageFrameType.PRIVATE_TEXT,
            senderId = "peer-private",
            recipientId = "self",
            createdAtMillis = 4_567L,
            payload = "private hello"
        )

        val outcome = IncomingChatMessageIngestor.ingest(
            state = state,
            frame = frame
        )

        assertTrue(outcome.result is IncomingMessageIngestionResult.UnsupportedThread)
        assertSame(state, outcome.updatedState)
    }

    @Test
    fun unsupportedFrameTypeDoesNotCrash() {
        val state = SampleAuroraState.create(
            generatedUsername = "PIAIUFN1"
        )
        val frame = MessageFrame(
            id = "incoming-control-1",
            type = MessageFrameType.CONTROL,
            senderId = "peer-control",
            createdAtMillis = 7_654L,
            payload = "control data"
        )

        val outcome = IncomingChatMessageIngestor.ingest(
            state = state,
            frame = frame
        )

        assertTrue(outcome.result is IncomingMessageIngestionResult.UnsupportedType)
        assertSame(state, outcome.updatedState)
    }

    @Test
    fun identityExchangeFrameIsExplicitlyUnsupportedForChatIngestion() {
        val state = SampleAuroraState.create(
            generatedUsername = "PIAIUFN1"
        )
        val frame = gr.hua.aurora.protocol.PeerIdentityExchangeMessage(
            peerId = "peer-identity",
            publicAgreementKeyBytes = validPublicKeyBytes(),
            createdAtMillis = 7_655L
        ).toMessageFrame(frameId = "identity-frame-1")

        val outcome = IncomingChatMessageIngestor.ingest(
            state = state,
            frame = frame
        )

        assertTrue(outcome.result is IncomingMessageIngestionResult.UnsupportedType)
        assertSame(state, outcome.updatedState)
    }

    @Test
    fun stateHolderIngestsTransportMessageWithoutChangingOutgoingQueue() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val queuedMessage = requireNotNull(holder.sendGlobalPreviewMessage("queued outgoing"))
        val queueBeforeIngest = holder.uiState.pendingOutgoingMessages.toList()
        val message = IncomingTransportMessage(
            frame = MessageFrame(
                id = "incoming-global-transport",
                type = MessageFrameType.GLOBAL_TEXT,
                senderId = "peer-transport",
                createdAtMillis = 8_888L,
                payload = "transport hello"
            ),
            senderPublicKey = byteArrayOf(1, 2, 3, 4)
        )

        val result = holder.ingestIncomingTransportMessage(message)

        assertTrue(result is IncomingMessageIngestionResult.Appended)
        assertEquals(queueBeforeIngest, holder.uiState.pendingOutgoingMessages)
        assertEquals(queuedMessage.messageId, holder.uiState.pendingOutgoingMessages.single().messageId)
        val appendedMessage = holder.uiState.globalMessages.last()
        assertEquals("incoming-global-transport", appendedMessage.id)
        assertEquals("peer-transport", appendedMessage.senderId)
        assertEquals("peer-transport", appendedMessage.senderName)
        assertEquals("transport hello", appendedMessage.text)
        assertEquals(8_888L, appendedMessage.createdAtMillis)
        assertEquals(MessageStatus.RECEIVED, appendedMessage.status)
        assertEquals(false, appendedMessage.isOutgoing)
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

    private fun validPublicKeyBytes(): ByteArray {
        val generator = java.security.KeyPairGenerator.getInstance("EC")
        generator.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        val publicKey = generator.generateKeyPair().public as java.security.interfaces.ECPublicKey
        return gr.hua.aurora.crypto.Sec1PublicKeyEncoding.encodeUncompressed(publicKey)
    }
}
