package gr.hua.aurora.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class CryptoHandshakeFlowTest {
    @Test
    fun aliceAndBobCanExchangePublicKeysDeriveSessionKeyAndDecryptPayload() {
        val alice = generateEcKeyPair("secp256r1")
        val bob = generateEcKeyPair("secp256r1")

        val encodedAlicePublicKey = Sec1PublicKeyEncoding.encodeUncompressed(alice.publicKey())
        val encodedBobPublicKey = Sec1PublicKeyEncoding.encodeUncompressed(bob.publicKey())

        val decodedAlicePublicKey = Sec1PublicKeyEncoding.decodeUncompressed(encodedAlicePublicKey)
        val decodedBobPublicKey = Sec1PublicKeyEncoding.decodeUncompressed(encodedBobPublicKey)

        val aliceSharedSecret = EcdhKeyAgreement.deriveSharedSecret(
            privateKey = alice.privateKey(),
            publicKey = decodedBobPublicKey
        )
        val bobSharedSecret = EcdhKeyAgreement.deriveSharedSecret(
            privateKey = bob.privateKey(),
            publicKey = decodedAlicePublicKey
        )

        assertArrayEquals(aliceSharedSecret, bobSharedSecret)

        val aliceSessionKey = HkdfSessionKeyDerivation.deriveSessionKey(aliceSharedSecret)
        val bobSessionKey = HkdfSessionKeyDerivation.deriveSessionKey(bobSharedSecret)

        assertArrayEquals(aliceSessionKey, bobSessionKey)

        val plaintext = "Aurora handshake payload".toByteArray()
        val encryptedPayload = AesGcmCipher.encrypt(aliceSessionKey, plaintext)
        val decryptedPayload = AesGcmCipher.decrypt(bobSessionKey, encryptedPayload)

        assertArrayEquals(plaintext, decryptedPayload)
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
