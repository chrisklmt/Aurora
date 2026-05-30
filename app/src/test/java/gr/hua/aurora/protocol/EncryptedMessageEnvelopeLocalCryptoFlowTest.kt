package gr.hua.aurora.protocol

import gr.hua.aurora.crypto.PeerSessionKeyAgreement
import gr.hua.aurora.crypto.Sec1PublicKeyEncoding
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class EncryptedMessageEnvelopeLocalCryptoFlowTest {
    @Test
    fun aliceAndBobCompleteLocalEncryptedEnvelopeFlowWithAuthenticatedData() {
        val alice = generateEcKeyPair("secp256r1")
        val bob = generateEcKeyPair("secp256r1")
        val alicePublicKeyBytes = Sec1PublicKeyEncoding.encodeUncompressed(alice.publicKey())
        val bobPublicKeyBytes = Sec1PublicKeyEncoding.encodeUncompressed(bob.publicKey())
        val plaintext = "Aurora local encrypted envelope".toByteArray()
        val authenticatedData = "local-flow-aad".toByteArray()

        val aliceDerivedKey = PeerSessionKeyAgreement.deriveSessionKey(
            privateKey = alice.privateKey(),
            peerPublicKeyBytes = bobPublicKeyBytes
        )
        val envelope = EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = alicePublicKeyBytes,
            keyBytes = aliceDerivedKey,
            plaintext = plaintext,
            authenticatedData = authenticatedData
        )
        val decodedEnvelope = EncryptedMessageEnvelopeCodec.decode(
            EncryptedMessageEnvelopeCodec.encode(envelope)
        )

        assertArrayEquals(alicePublicKeyBytes, decodedEnvelope.senderPublicKey)

        val bobDerivedKey = PeerSessionKeyAgreement.deriveSessionKey(
            privateKey = bob.privateKey(),
            peerPublicKeyBytes = decodedEnvelope.senderPublicKey
        )
        val decryptedPayload = EncryptedMessageEnvelopeDecryptor.decrypt(
            envelope = decodedEnvelope,
            keyBytes = bobDerivedKey,
            authenticatedData = authenticatedData
        )

        assertArrayEquals(plaintext, decryptedPayload)
    }

    @Test
    fun aliceAndBobCompleteLocalEncryptedEnvelopeFlowWithoutAuthenticatedData() {
        val alice = generateEcKeyPair("secp256r1")
        val bob = generateEcKeyPair("secp256r1")
        val alicePublicKeyBytes = Sec1PublicKeyEncoding.encodeUncompressed(alice.publicKey())
        val bobPublicKeyBytes = Sec1PublicKeyEncoding.encodeUncompressed(bob.publicKey())
        val plaintext = "Aurora local encrypted envelope".toByteArray()

        val aliceDerivedKey = PeerSessionKeyAgreement.deriveSessionKey(
            privateKey = alice.privateKey(),
            peerPublicKeyBytes = bobPublicKeyBytes
        )
        val envelope = EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = alicePublicKeyBytes,
            keyBytes = aliceDerivedKey,
            plaintext = plaintext
        )
        val decodedEnvelope = EncryptedMessageEnvelopeCodec.decode(
            EncryptedMessageEnvelopeCodec.encode(envelope)
        )
        val bobDerivedKey = PeerSessionKeyAgreement.deriveSessionKey(
            privateKey = bob.privateKey(),
            peerPublicKeyBytes = decodedEnvelope.senderPublicKey
        )
        val decryptedPayload = EncryptedMessageEnvelopeDecryptor.decrypt(
            envelope = decodedEnvelope,
            keyBytes = bobDerivedKey
        )

        assertArrayEquals(plaintext, decryptedPayload)
    }

    @Test
    fun wrongAuthenticatedDataFailsAfterLocalKeyAgreement() {
        val alice = generateEcKeyPair("secp256r1")
        val bob = generateEcKeyPair("secp256r1")
        val alicePublicKeyBytes = Sec1PublicKeyEncoding.encodeUncompressed(alice.publicKey())
        val bobPublicKeyBytes = Sec1PublicKeyEncoding.encodeUncompressed(bob.publicKey())
        val authenticatedData = "local-flow-aad".toByteArray()

        val aliceDerivedKey = PeerSessionKeyAgreement.deriveSessionKey(
            privateKey = alice.privateKey(),
            peerPublicKeyBytes = bobPublicKeyBytes
        )
        val envelope = EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = alicePublicKeyBytes,
            keyBytes = aliceDerivedKey,
            plaintext = "Aurora local encrypted envelope".toByteArray(),
            authenticatedData = authenticatedData
        )
        val decodedEnvelope = EncryptedMessageEnvelopeCodec.decode(
            EncryptedMessageEnvelopeCodec.encode(envelope)
        )
        val bobDerivedKey = PeerSessionKeyAgreement.deriveSessionKey(
            privateKey = bob.privateKey(),
            peerPublicKeyBytes = decodedEnvelope.senderPublicKey
        )

        assertThrows(GeneralSecurityException::class.java) {
            EncryptedMessageEnvelopeDecryptor.decrypt(
                envelope = decodedEnvelope,
                keyBytes = bobDerivedKey,
                authenticatedData = "wrong-aad".toByteArray()
            )
        }
    }

    @Test
    fun wrongPeerKeyMaterialFailsToDecrypt() {
        val alice = generateEcKeyPair("secp256r1")
        val bob = generateEcKeyPair("secp256r1")
        val mallory = generateEcKeyPair("secp256r1")
        val alicePublicKeyBytes = Sec1PublicKeyEncoding.encodeUncompressed(alice.publicKey())
        val bobPublicKeyBytes = Sec1PublicKeyEncoding.encodeUncompressed(bob.publicKey())

        val aliceDerivedKey = PeerSessionKeyAgreement.deriveSessionKey(
            privateKey = alice.privateKey(),
            peerPublicKeyBytes = bobPublicKeyBytes
        )
        val envelope = EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = alicePublicKeyBytes,
            keyBytes = aliceDerivedKey,
            plaintext = "Aurora local encrypted envelope".toByteArray()
        )
        val decodedEnvelope = EncryptedMessageEnvelopeCodec.decode(
            EncryptedMessageEnvelopeCodec.encode(envelope)
        )
        val malloryDerivedKey = PeerSessionKeyAgreement.deriveSessionKey(
            privateKey = mallory.privateKey(),
            peerPublicKeyBytes = decodedEnvelope.senderPublicKey
        )

        assertThrows(GeneralSecurityException::class.java) {
            EncryptedMessageEnvelopeDecryptor.decrypt(
                envelope = decodedEnvelope,
                keyBytes = malloryDerivedKey
            )
        }
    }

    private fun generateEcKeyPair(curveName: String): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(curveName))
        return generator.generateKeyPair()
    }

    private fun KeyPair.privateKey(): ECPrivateKey {
        return private as ECPrivateKey
    }

    private fun KeyPair.publicKey(): ECPublicKey {
        return public as ECPublicKey
    }
}
