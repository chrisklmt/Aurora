package gr.hua.aurora.protocol

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

class PeerSessionRegistryTest {
    @Test
    fun noSessionReturnsMaterialUnavailable() {
        val registry = PeerSessionRegistry()

        val outgoingResult = registry.lookupOutgoingMaterial(
            privateMessage(peerId = "peer-a")
        )
        val incomingResult = registry.lookupIncomingMaterial(
            envelopeFor(
                senderPublicKey = senderPublicKeyBytes(),
                keyBytes = deterministicKey(11)
            )
        )

        assertTrue(outgoingResult is OutgoingSessionMaterialLookupResult.MaterialUnavailable)
        assertTrue(incomingResult is IncomingSessionMaterialLookupResult.MaterialUnavailable)
        assertNull(registry.encryptionMaterialFor(privateMessage(peerId = "peer-a")))
    }

    @Test
    fun storedOutgoingPrivateSessionReturnsMaterial() {
        val registry = PeerSessionRegistry()
        val peerPublicKey = senderPublicKeyBytes()
        val session = session(
            peerId = "peer-outgoing",
            peerPublicKey = peerPublicKey,
            outgoingOffset = 21,
            incomingOffset = 22
        )
        registry.putSession(session)

        val result = registry.lookupOutgoingMaterial(
            privateMessage(peerId = "peer-outgoing")
        )

        assertTrue(result is OutgoingSessionMaterialLookupResult.Found)
        val material = (result as OutgoingSessionMaterialLookupResult.Found).material
        assertArrayEquals(
            session.outgoingMaterial.keyBytes,
            material.keyBytes
        )
        assertArrayEquals(
            session.outgoingMaterial.senderPublicKey,
            material.senderPublicKey
        )
    }

    @Test
    fun aliasMappedPeerResolvesOutgoingMaterialAndDiagnostics() {
        val registry = PeerSessionRegistry()
        val peerPublicKey = senderPublicKeyBytes()
        val canonicalPeerId = PeerSessionPeerId.deriveFromPublicKey(peerPublicKey)
        val aliasPeerId = "peer-legacy"
        val session = session(
            peerId = canonicalPeerId,
            peerPublicKey = peerPublicKey,
            outgoingOffset = 23,
            incomingOffset = 24
        )
        registry.putSession(session)

        assertTrue(registry.putPeerIdAlias(aliasPeerId, canonicalPeerId))

        val lookupResult = registry.lookupOutgoingMaterialForPeer(aliasPeerId)
        val messageLookupResult = registry.lookupOutgoingMaterial(
            privateMessage(peerId = aliasPeerId)
        )
        val diagnostics = registry.diagnosticsSnapshot()

        assertTrue(lookupResult is OutgoingSessionMaterialLookupResult.Found)
        assertTrue(messageLookupResult is OutgoingSessionMaterialLookupResult.Found)
        assertTrue(registry.hasSessionForPeer(aliasPeerId))
        assertEquals(canonicalPeerId, registry.canonicalPeerIdFor(aliasPeerId))
        assertEquals(listOf(canonicalPeerId), diagnostics.establishedPeerIds)
        assertEquals(canonicalPeerId, diagnostics.canonicalPeerIdByAlias[aliasPeerId])
    }

    @Test
    fun storedIncomingSenderPublicKeyReturnsMaterial() {
        val registry = PeerSessionRegistry()
        val peerPublicKey = senderPublicKeyBytes()
        val session = session(
            peerId = "peer-incoming",
            peerPublicKey = peerPublicKey,
            outgoingOffset = 31,
            incomingOffset = 32
        )
        registry.putSession(session)

        val result = registry.lookupIncomingMaterial(
            envelopeFor(
                senderPublicKey = peerPublicKey,
                keyBytes = deterministicKey(33)
            )
        )

        assertTrue(result is IncomingSessionMaterialLookupResult.Found)
        val material = (result as IncomingSessionMaterialLookupResult.Found).material
        assertArrayEquals(
            session.incomingMaterial.keyBytes,
            material.keyBytes
        )
    }

    @Test
    fun updatingSessionReplacesOldMaterial() {
        val registry = PeerSessionRegistry()
        val peerPublicKey = senderPublicKeyBytes()
        registry.putSession(
            session(
                peerId = "peer-update",
                peerPublicKey = peerPublicKey,
                outgoingOffset = 41,
                incomingOffset = 42
            )
        )
        val updatedSession = session(
            peerId = "peer-update",
            peerPublicKey = peerPublicKey,
            outgoingOffset = 51,
            incomingOffset = 52
        )

        registry.putSession(updatedSession)

        val outgoingResult = registry.lookupOutgoingMaterial(
            privateMessage(peerId = "peer-update")
        )
        val incomingResult = registry.lookupIncomingMaterial(
            envelopeFor(
                senderPublicKey = peerPublicKey,
                keyBytes = deterministicKey(53)
            )
        )

        assertTrue(outgoingResult is OutgoingSessionMaterialLookupResult.Found)
        assertTrue(incomingResult is IncomingSessionMaterialLookupResult.Found)
        assertArrayEquals(
            updatedSession.outgoingMaterial.keyBytes,
            (outgoingResult as OutgoingSessionMaterialLookupResult.Found).material.keyBytes
        )
        assertArrayEquals(
            updatedSession.incomingMaterial.keyBytes,
            (incomingResult as IncomingSessionMaterialLookupResult.Found).material.keyBytes
        )
    }

    @Test
    fun clearPeerRemovesSession() {
        val registry = PeerSessionRegistry()
        val peerPublicKey = senderPublicKeyBytes()
        registry.putSession(
            session(
                peerId = "peer-clear",
                peerPublicKey = peerPublicKey,
                outgoingOffset = 61,
                incomingOffset = 62
            )
        )

        val removed = registry.clearPeer("peer-clear")
        val outgoingResult = registry.lookupOutgoingMaterial(
            privateMessage(peerId = "peer-clear")
        )
        val incomingResult = registry.lookupIncomingMaterial(
            envelopeFor(
                senderPublicKey = peerPublicKey,
                keyBytes = deterministicKey(63)
            )
        )

        assertTrue(removed)
        assertTrue(outgoingResult is OutgoingSessionMaterialLookupResult.MaterialUnavailable)
        assertTrue(incomingResult is IncomingSessionMaterialLookupResult.MaterialUnavailable)
    }

    @Test
    fun clearAllRemovesSessions() {
        val registry = PeerSessionRegistry()
        val firstPublicKey = senderPublicKeyBytes()
        val secondPublicKey = senderPublicKeyBytes()
        registry.putSession(
            session(
                peerId = "peer-one",
                peerPublicKey = firstPublicKey,
                outgoingOffset = 71,
                incomingOffset = 72
            )
        )
        registry.putSession(
            session(
                peerId = "peer-two",
                peerPublicKey = secondPublicKey,
                outgoingOffset = 73,
                incomingOffset = 74
            )
        )

        registry.clearAll()

        assertTrue(
            registry.lookupOutgoingMaterial(privateMessage(peerId = "peer-one"))
                is OutgoingSessionMaterialLookupResult.MaterialUnavailable
        )
        assertTrue(
            registry.lookupIncomingMaterial(
                envelopeFor(
                    senderPublicKey = secondPublicKey,
                    keyBytes = deterministicKey(75)
                )
            ) is IncomingSessionMaterialLookupResult.MaterialUnavailable
        )
    }

    @Test
    fun byteArraysAreDefensivelyCopied() {
        val peerPublicKey = senderPublicKeyBytes()
        val originalPeerPublicKey = peerPublicKey.copyOf()
        val registry = PeerSessionRegistry()
        val session = session(
            peerId = "peer-copy",
            peerPublicKey = peerPublicKey,
            outgoingOffset = 81,
            incomingOffset = 82
        )
        registry.putSession(session)

        peerPublicKey[0] = (peerPublicKey[0].toInt() xor 0x01).toByte()

        val returnedPublicKey = session.peerPublicKeyToByteArray()
        returnedPublicKey[1] = (returnedPublicKey[1].toInt() xor 0x01).toByte()

        val incomingResult = registry.lookupIncomingMaterial(
            envelopeFor(
                senderPublicKey = originalPeerPublicKey,
                keyBytes = deterministicKey(83)
            )
        )

        assertTrue(incomingResult is IncomingSessionMaterialLookupResult.Found)
        assertArrayEquals(
            originalPeerPublicKey,
            session.peerPublicKeyToByteArray()
        )
    }

    @Test
    fun globalMessageBehaviorIsExplicit() {
        val registry = PeerSessionRegistry()

        val result = registry.lookupOutgoingMaterial(
            OutgoingChatMessage(
                messageId = "global-message",
                threadId = "global",
                userText = "hello global",
                createdAtMillis = 1_715_800_001L,
                status = MessageStatus.LOCAL_ONLY
            )
        )

        assertTrue(result is OutgoingSessionMaterialLookupResult.MaterialUnavailable)
        val unavailable = result as OutgoingSessionMaterialLookupResult.MaterialUnavailable
        assertTrue(unavailable.reason.contains("global", ignoreCase = true))
    }

    private fun session(
        peerId: String,
        peerPublicKey: ByteArray,
        outgoingOffset: Int,
        incomingOffset: Int
    ): EstablishedPeerSession {
        return EstablishedPeerSession(
            peerId = peerId,
            peerPublicKey = peerPublicKey,
            outgoingMaterial = OutgoingMessageSendEncryptionMaterial(
                senderPublicKey = senderPublicKeyBytes(),
                keyBytes = deterministicKey(outgoingOffset),
                authenticatedData = "outgoing-$outgoingOffset".toByteArray(UTF_8)
            ),
            incomingMaterial = IncomingMessageReceiveDecryptionMaterial(
                keyBytes = deterministicKey(incomingOffset),
                authenticatedData = "incoming-$incomingOffset".toByteArray(UTF_8)
            )
        )
    }

    private fun privateMessage(
        peerId: String
    ): OutgoingChatMessage {
        return OutgoingChatMessage(
            messageId = "outgoing-$peerId",
            threadId = "private:$peerId",
            userText = "hello $peerId",
            createdAtMillis = 1_715_800_100L,
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
