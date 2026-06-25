package gr.hua.aurora.protocol

import gr.hua.aurora.crypto.Sec1PublicKeyEncoding
import gr.hua.aurora.data.LocalProfileSettings
import gr.hua.aurora.data.LocalProfileSettingsStore
import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.model.OutgoingChatMessage
import gr.hua.aurora.state.AuroraStateHolder
import gr.hua.aurora.state.SampleAuroraState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class PeerIdentityExchangeHandlerTest {
    @Test
    fun validIdentityFrameEstablishesAndStoresSession() {
        val alice = generateEcKeyPair()
        val bob = generateEcKeyPair()
        val bobPublicKey = bob.publicKeyBytes()
        val canonicalBobPeerId = canonicalPeerId(bobPublicKey)
        val registry = PeerSessionRegistry()
        val frame = PeerIdentityExchangeMessage(
            peerId = canonicalBobPeerId,
            publicAgreementKeyBytes = bobPublicKey,
            createdAtMillis = 1_716_000_001L
        ).toMessageFrame(frameId = "identity-bob")

        val result = PeerIdentityExchangeHandler.handle(
            frame = frame,
            localIdentity = alice.identity(),
            registry = registry
        )

        assertEquals(
            PeerIdentityExchangeHandlingResult.Established(peerId = canonicalBobPeerId),
            result
        )
        val outgoingLookup = registry.lookupOutgoingMaterial(
            privateMessage(peerId = canonicalBobPeerId)
        )
        assertTrue(outgoingLookup is OutgoingSessionMaterialLookupResult.Found)
        val outgoingMaterial = (outgoingLookup as OutgoingSessionMaterialLookupResult.Found).material
        assertArrayEquals(
            alice.publicKeyBytes(),
            outgoingMaterial.senderPublicKey
        )
    }

    @Test
    fun establishedSessionSupportsIncomingLookupBySenderPublicKey() {
        val alice = generateEcKeyPair()
        val bob = generateEcKeyPair()
        val bobPublicKey = bob.publicKeyBytes()
        val registry = PeerSessionRegistry()
        val frame = PeerIdentityExchangeMessage(
            peerId = canonicalPeerId(bobPublicKey),
            publicAgreementKeyBytes = bobPublicKey,
            createdAtMillis = 1_716_000_002L
        ).toMessageFrame(frameId = "identity-bob-incoming")

        val result = PeerIdentityExchangeHandler.handle(
            frame = frame,
            localIdentity = alice.identity(),
            registry = registry
        )

        assertTrue(result is PeerIdentityExchangeHandlingResult.Established)
        val incomingLookup = registry.lookupIncomingMaterial(
            envelopeFor(
                senderPublicKey = bobPublicKey,
                keyBytes = deterministicKey(44)
            )
        )
        assertTrue(incomingLookup is IncomingSessionMaterialLookupResult.Found)
    }

    @Test
    fun mismatchedClaimedPeerIdStoresSessionUnderCanonicalStablePeerIdAndAlias() {
        val alice = generateEcKeyPair()
        val bob = generateEcKeyPair()
        val bobPublicKey = bob.publicKeyBytes()
        val canonicalBobPeerId = canonicalPeerId(bobPublicKey)
        val registry = PeerSessionRegistry()
        val frame = PeerIdentityExchangeMessage(
            peerId = "peer-legacy-bob",
            publicAgreementKeyBytes = bobPublicKey,
            createdAtMillis = 1_716_000_002L
        ).toMessageFrame(frameId = "identity-bob-alias")

        val result = PeerIdentityExchangeHandler.handle(
            frame = frame,
            localIdentity = alice.identity(),
            registry = registry
        )

        assertEquals(
            PeerIdentityExchangeHandlingResult.Established(peerId = canonicalBobPeerId),
            result
        )
        assertTrue(
            registry.lookupOutgoingMaterialForPeer(canonicalBobPeerId)
                is OutgoingSessionMaterialLookupResult.Found
        )
        assertTrue(
            registry.lookupOutgoingMaterialForPeer("peer-legacy-bob")
                is OutgoingSessionMaterialLookupResult.Found
        )
        assertEquals(
            canonicalBobPeerId,
            registry.diagnosticsSnapshot().canonicalPeerIdByAlias["peer-legacy-bob"]
        )
    }

    @Test
    fun nonIdentityFrameIsIgnored() {
        val result = PeerIdentityExchangeHandler.handle(
            frame = MessageFrame(
                id = "global-frame",
                type = MessageFrameType.GLOBAL_TEXT,
                senderId = "peer-global",
                createdAtMillis = 1_716_000_003L,
                payload = "hello"
            ),
            localIdentity = generateEcKeyPair().identity(),
            registry = PeerSessionRegistry()
        )

        assertEquals(
            PeerIdentityExchangeHandlingResult.IgnoredNonIdentityFrame,
            result
        )
    }

    @Test
    fun malformedPayloadReturnsInvalidIdentityMessage() {
        val frame = MessageFrame(
            id = "identity-malformed",
            type = MessageFrameType.IDENTITY_EXCHANGE,
            senderId = "peer-malformed",
            createdAtMillis = 1_716_000_004L,
            payload = "bad|payload"
        )

        val result = PeerIdentityExchangeHandler.handle(
            frame = frame,
            localIdentity = generateEcKeyPair().identity(),
            registry = PeerSessionRegistry()
        )

        assertTrue(result is PeerIdentityExchangeHandlingResult.InvalidIdentityMessage)
    }

    @Test
    fun invalidPublicKeyMapsToInvalidRemotePublicKey() {
        val invalidPublicKey = ByteArray(65).apply {
            this[0] = 0x04
        }
        val frame = MessageFrame(
            id = "identity-invalid-key",
            type = MessageFrameType.IDENTITY_EXCHANGE,
            senderId = "peer-invalid",
            createdAtMillis = 1_716_000_005L,
            payload = encodeIdentityPayload(
                peerId = "peer-invalid",
                publicKeyBytes = invalidPublicKey,
                createdAtMillis = 1_716_000_005L
            )
        )

        val result = PeerIdentityExchangeHandler.handle(
            frame = frame,
            localIdentity = generateEcKeyPair().identity(),
            registry = PeerSessionRegistry()
        )

        assertTrue(result is PeerIdentityExchangeHandlingResult.InvalidRemotePublicKey)
    }

    @Test
    fun selfPeerMapsToSelfPeer() {
        val local = generateEcKeyPair()
        val localPeerId = canonicalPeerId(local.publicKeyBytes())
        val frame = PeerIdentityExchangeMessage(
            peerId = localPeerId,
            publicAgreementKeyBytes = local.publicKeyBytes(),
            createdAtMillis = 1_716_000_006L
        ).toMessageFrame(frameId = "identity-self")

        val result = PeerIdentityExchangeHandler.handle(
            frame = frame,
            localIdentity = local.identity(),
            registry = PeerSessionRegistry()
        )

        assertTrue(result is PeerIdentityExchangeHandlingResult.SelfPeer)
    }

    @Test
    fun keyAgreementFailureMapsExplicitly() {
        val frame = PeerIdentityExchangeMessage(
            peerId = "peer-agreement-fail",
            publicAgreementKeyBytes = generateEcKeyPair().publicKeyBytes(),
            createdAtMillis = 1_716_000_007L
        ).toMessageFrame(frameId = "identity-agreement-fail")

        val result = PeerIdentityExchangeHandler.handle(
            frame = frame,
            localIdentity = generateEcKeyPair().identity(),
            registry = PeerSessionRegistry(),
            establishAndStore = { _, _, _, _ ->
                PeerSessionEstablishmentResult.KeyAgreementFailed(
                    reason = "agreement failed"
                )
            }
        )

        assertEquals(
            PeerIdentityExchangeHandlingResult.KeyAgreementFailed(
                reason = "agreement failed"
            ),
            result
        )
    }

    @Test
    fun keyDerivationFailureMapsExplicitly() {
        val frame = PeerIdentityExchangeMessage(
            peerId = "peer-derivation-fail",
            publicAgreementKeyBytes = generateEcKeyPair().publicKeyBytes(),
            createdAtMillis = 1_716_000_008L
        ).toMessageFrame(frameId = "identity-derivation-fail")

        val result = PeerIdentityExchangeHandler.handle(
            frame = frame,
            localIdentity = generateEcKeyPair().identity(),
            registry = PeerSessionRegistry(),
            establishAndStore = { _, _, _, _ ->
                PeerSessionEstablishmentResult.KeyDerivationFailed(
                    reason = "derivation failed"
                )
            }
        )

        assertEquals(
            PeerIdentityExchangeHandlingResult.KeyDerivationFailed(
                reason = "derivation failed"
            ),
            result
        )
    }

    @Test
    fun handlerDoesNotInsertChatMessagesOrMutateOutgoingQueue() {
        val holder = AuroraStateHolder(
            initialState = SampleAuroraState.create(
                generatedUsername = "PIAIUFN1"
            ),
            localProfileStore = FakeProfileStore()
        )
        val queuedMessage = requireNotNull(holder.sendGlobalPreviewMessage("queued outgoing"))
        val stateBefore = holder.uiState
        val remote = generateEcKeyPair()
        val remotePublicKey = remote.publicKeyBytes()

        val result = PeerIdentityExchangeHandler.handle(
            frame = PeerIdentityExchangeMessage(
                peerId = canonicalPeerId(remotePublicKey),
                publicAgreementKeyBytes = remotePublicKey,
                createdAtMillis = 1_716_000_009L
            ).toMessageFrame(frameId = "identity-pure"),
            localIdentity = generateEcKeyPair().identity(),
            registry = PeerSessionRegistry()
        )

        assertTrue(result is PeerIdentityExchangeHandlingResult.Established)
        assertEquals(stateBefore.globalMessages, holder.uiState.globalMessages)
        assertEquals(
            stateBefore.pendingOutgoingMessages,
            holder.uiState.pendingOutgoingMessages
        )
        assertEquals(
            queuedMessage.messageId,
            holder.uiState.pendingOutgoingMessages.single().messageId
        )
    }

    private fun privateMessage(
        peerId: String
    ): OutgoingChatMessage {
        return OutgoingChatMessage(
            messageId = "outgoing-$peerId",
            threadId = "private:$peerId",
            userText = "hello $peerId",
            createdAtMillis = 1_716_000_100L,
            status = MessageStatus.QUEUED
        )
    }

    private fun envelopeFor(
        senderPublicKey: ByteArray,
        keyBytes: ByteArray
    ): EncryptedMessageEnvelope {
        return EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = senderPublicKey,
            keyBytes = keyBytes,
            plaintext = "payload".toByteArray(UTF_8)
        )
    }

    private fun encodeIdentityPayload(
        peerId: String,
        publicKeyBytes: ByteArray,
        createdAtMillis: Long
    ): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return listOf(
            "AURORA_PEER_IDENTITY_V1",
            encoder.encodeToString(peerId.toByteArray(UTF_8)),
            createdAtMillis.toString(),
            encoder.encodeToString(publicKeyBytes)
        ).joinToString("|")
    }

    private fun deterministicKey(offset: Int): ByteArray {
        return ByteArray(32) { index -> (index + offset).toByte() }
    }

    private fun canonicalPeerId(
        publicKeyBytes: ByteArray
    ): String {
        return PeerSessionPeerId.deriveFromPublicKey(publicKeyBytes)
    }

    private fun generateEcKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair()
    }

    private fun KeyPair.identity(): LocalPeerSessionIdentityMaterial {
        return LocalPeerSessionIdentityMaterial(
            publicKeyBytes = publicKeyBytes(),
            privateKey = privateKey()
        )
    }

    private fun KeyPair.privateKey(): ECPrivateKey {
        return private as ECPrivateKey
    }

    private fun KeyPair.publicKey(): ECPublicKey {
        return public as ECPublicKey
    }

    private fun KeyPair.publicKeyBytes(): ByteArray {
        return Sec1PublicKeyEncoding.encodeUncompressed(publicKey())
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
