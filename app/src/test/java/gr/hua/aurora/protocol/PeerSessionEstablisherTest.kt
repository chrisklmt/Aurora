package gr.hua.aurora.protocol

import gr.hua.aurora.ble.transport.BleGattTransportFrame
import gr.hua.aurora.ble.transport.BleGattTransportFrameChunker
import gr.hua.aurora.ble.transport.BleTransportSendResult
import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.model.OutgoingChatMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class PeerSessionEstablisherTest {
    @Test
    fun twoGeneratedIdentitiesDeriveCompatibleSessionMaterial() {
        val alice = generateEcKeyPair()
        val bob = generateEcKeyPair()

        val aliceResult = establish(
            local = alice,
            remotePeerId = "bob",
            remotePeerPublicKeyBytes = bob.publicKeyBytes()
        )
        val bobResult = establish(
            local = bob,
            remotePeerId = "alice",
            remotePeerPublicKeyBytes = alice.publicKeyBytes()
        )

        assertTrue(aliceResult is PeerSessionEstablishmentResult.Established)
        assertTrue(bobResult is PeerSessionEstablishmentResult.Established)
        assertArrayEquals(
            (aliceResult as PeerSessionEstablishmentResult.Established).session.outgoingMaterial.keyBytes,
            (bobResult as PeerSessionEstablishmentResult.Established).session.incomingMaterial.keyBytes
        )
        assertArrayEquals(
            (bobResult as PeerSessionEstablishmentResult.Established).session.outgoingMaterial.keyBytes,
            (aliceResult as PeerSessionEstablishmentResult.Established).session.incomingMaterial.keyBytes
        )
    }

    @Test
    fun outgoingFromAToBDecryptsThroughBIncomingProvider() {
        val alice = generateEcKeyPair()
        val bob = generateEcKeyPair()
        val aliceSession = requireEstablished(
            establish(
                local = alice,
                remotePeerId = "bob",
                remotePeerPublicKeyBytes = bob.publicKeyBytes()
            )
        )
        val bobSession = requireEstablished(
            establish(
                local = bob,
                remotePeerId = "alice",
                remotePeerPublicKeyBytes = alice.publicKeyBytes()
            )
        )
        val bobRegistry = PeerSessionRegistry().apply {
            putSession(bobSession)
        }
        val message = MessageFrame(
            id = "a-to-b",
            type = MessageFrameType.PRIVATE_TEXT,
            senderId = "alice",
            recipientId = "bob",
            createdAtMillis = 1_715_900_001L,
            payload = "hello bob"
        )

        val result = receiveWithSession(
            frame = message,
            outgoingMaterial = aliceSession.outgoingMaterial,
            incomingProvider = bobRegistry,
            groupId = 0x5101
        )

        assertTrue(result is IncomingTransportReceiveResult.Received)
        assertEquals(
            message,
            (result as IncomingTransportReceiveResult.Received).message.frame
        )
    }

    @Test
    fun outgoingFromBToADecryptsThroughAIncomingProvider() {
        val alice = generateEcKeyPair()
        val bob = generateEcKeyPair()
        val aliceSession = requireEstablished(
            establish(
                local = alice,
                remotePeerId = "bob",
                remotePeerPublicKeyBytes = bob.publicKeyBytes()
            )
        )
        val bobSession = requireEstablished(
            establish(
                local = bob,
                remotePeerId = "alice",
                remotePeerPublicKeyBytes = alice.publicKeyBytes()
            )
        )
        val aliceRegistry = PeerSessionRegistry().apply {
            putSession(aliceSession)
        }
        val message = MessageFrame(
            id = "b-to-a",
            type = MessageFrameType.PRIVATE_TEXT,
            senderId = "bob",
            recipientId = "alice",
            createdAtMillis = 1_715_900_002L,
            payload = "hello alice"
        )

        val result = receiveWithSession(
            frame = message,
            outgoingMaterial = bobSession.outgoingMaterial,
            incomingProvider = aliceRegistry,
            groupId = 0x5102
        )

        assertTrue(result is IncomingTransportReceiveResult.Received)
        assertEquals(
            message,
            (result as IncomingTransportReceiveResult.Received).message.frame
        )
    }

    @Test
    fun invalidRemotePublicKeyFails() {
        val alice = generateEcKeyPair()
        val invalidRemotePublicKey = ByteArray(65).apply {
            this[0] = 0x05
        }

        val result = establish(
            local = alice,
            remotePeerId = "peer-invalid",
            remotePeerPublicKeyBytes = invalidRemotePublicKey
        )

        assertTrue(result is PeerSessionEstablishmentResult.InvalidRemotePublicKey)
    }

    @Test
    fun selfLoopbackKeyFailsWhenDetectable() {
        val alice = generateEcKeyPair()

        val result = establish(
            local = alice,
            remotePeerId = "self",
            remotePeerPublicKeyBytes = alice.publicKeyBytes()
        )

        assertTrue(result is PeerSessionEstablishmentResult.SelfPeer)
    }

    @Test
    fun resultSessionUsesDefensiveCopies() {
        val alice = generateEcKeyPair()
        val bob = generateEcKeyPair()
        val result = requireEstablished(
            establish(
                local = alice,
                remotePeerId = "bob",
                remotePeerPublicKeyBytes = bob.publicKeyBytes()
            )
        )

        val firstPeerPublicKeyCopy = result.peerPublicKeyToByteArray()
        val secondPeerPublicKeyCopy = result.peerPublicKeyToByteArray()

        assertNotSame(firstPeerPublicKeyCopy, secondPeerPublicKeyCopy)
        firstPeerPublicKeyCopy[1] = (firstPeerPublicKeyCopy[1].toInt() xor 0x01).toByte()
        assertArrayEquals(
            bob.publicKeyBytes(),
            result.peerPublicKeyToByteArray()
        )
    }

    @Test
    fun establishAndStoreRegistersSessionInRegistry() {
        val alice = generateEcKeyPair()
        val bob = generateEcKeyPair()
        val registry = PeerSessionRegistry()

        val result = PeerSessionEstablisher.establishAndStore(
            localIdentity = alice.identity(),
            remotePeerId = "bob",
            remotePeerPublicKeyBytes = bob.publicKeyBytes(),
            registry = registry
        )

        assertTrue(result is PeerSessionEstablishmentResult.Established)
        val outgoingLookup = registry.lookupOutgoingMaterial(
            OutgoingChatMessage(
                messageId = "queued-bob",
                threadId = "private:bob",
                userText = "hello",
                createdAtMillis = 1_715_900_010L,
                status = MessageStatus.QUEUED
            )
        )
        assertTrue(outgoingLookup is OutgoingSessionMaterialLookupResult.Found)
    }

    @Test
    fun keyAgreementFailureIsExplicit() {
        val alice = generateEcKeyPair()
        val bob = generateEcKeyPair()

        val result = PeerSessionEstablisher.establish(
            localIdentity = alice.identity(),
            remotePeerId = "bob",
            remotePeerPublicKeyBytes = bob.publicKeyBytes(),
            deriveSharedSecret = { _, _ ->
                throw IllegalArgumentException("agreement failed")
            }
        )

        assertTrue(result is PeerSessionEstablishmentResult.KeyAgreementFailed)
    }

    @Test
    fun keyDerivationFailureIsExplicit() {
        val alice = generateEcKeyPair()
        val bob = generateEcKeyPair()

        val result = PeerSessionEstablisher.establish(
            localIdentity = alice.identity(),
            remotePeerId = "bob",
            remotePeerPublicKeyBytes = bob.publicKeyBytes(),
            deriveSessionKey = {
                throw IllegalArgumentException("derivation failed")
            }
        )

        assertTrue(result is PeerSessionEstablishmentResult.KeyDerivationFailed)
    }

    private fun establish(
        local: KeyPair,
        remotePeerId: String,
        remotePeerPublicKeyBytes: ByteArray
    ): PeerSessionEstablishmentResult {
        return PeerSessionEstablisher.establish(
            localIdentity = local.identity(),
            remotePeerId = remotePeerId,
            remotePeerPublicKeyBytes = remotePeerPublicKeyBytes
        )
    }

    private fun requireEstablished(
        result: PeerSessionEstablishmentResult
    ): EstablishedPeerSession {
        require(result is PeerSessionEstablishmentResult.Established) {
            "Expected established session but got $result"
        }
        return result.session
    }

    private fun receiveWithSession(
        frame: MessageFrame,
        outgoingMaterial: OutgoingMessageSendEncryptionMaterial,
        incomingProvider: IncomingSessionMaterialProvider,
        groupId: Int
    ): IncomingTransportReceiveResult {
        val envelope = EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = outgoingMaterial.senderPublicKey,
            keyBytes = outgoingMaterial.keyBytes,
            plaintext = MessageFrameCodec.encode(frame).toByteArray(UTF_8)
        )
        val frames = BleGattTransportFrameChunker.chunk(
            encodedEnvelopeBytes = EncryptedMessageEnvelopeCodec.encode(envelope).toByteArray(UTF_8),
            groupId = groupId
        )

        return IncomingMessageReceiveUseCase.receive(
            frames = frames,
            sessionMaterialProvider = incomingProvider
        )
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
        return gr.hua.aurora.crypto.Sec1PublicKeyEncoding.encodeUncompressed(publicKey())
    }
}
