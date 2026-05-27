package gr.hua.aurora.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class EcdhKeyAgreementTest {
    @Test
    fun aliceAndBobDeriveSameSharedSecret() {
        val alice = generateEcKeyPair("secp256r1")
        val bob = generateEcKeyPair("secp256r1")

        val aliceSecret = EcdhKeyAgreement.deriveSharedSecret(
            privateKey = alice.privateKey(),
            publicKey = bob.publicKey()
        )
        val bobSecret = EcdhKeyAgreement.deriveSharedSecret(
            privateKey = bob.privateKey(),
            publicKey = alice.publicKey()
        )

        assertArrayEquals(aliceSecret, bobSecret)
    }

    @Test
    fun derivedSharedSecretIsNonEmpty() {
        val alice = generateEcKeyPair("secp256r1")
        val bob = generateEcKeyPair("secp256r1")

        val sharedSecret = EcdhKeyAgreement.deriveSharedSecret(
            privateKey = alice.privateKey(),
            publicKey = bob.publicKey()
        )

        assertTrue(sharedSecret.isNotEmpty())
    }

    @Test
    fun differentPeerKeyProducesDifferentSharedSecret() {
        val alice = generateEcKeyPair("secp256r1")
        val bob = generateEcKeyPair("secp256r1")
        val mallory = generateEcKeyPair("secp256r1")

        val bobSecret = EcdhKeyAgreement.deriveSharedSecret(
            privateKey = alice.privateKey(),
            publicKey = bob.publicKey()
        )
        val mallorySecret = EcdhKeyAgreement.deriveSharedSecret(
            privateKey = alice.privateKey(),
            publicKey = mallory.publicKey()
        )

        assertFalse(bobSecret.contentEquals(mallorySecret))
    }

    @Test
    fun nonP256PublicKeyIsRejectedWhenProviderSupportsIt() {
        val alice = generateEcKeyPair("secp256r1")
        val otherCurvePeer = try {
            generateEcKeyPair("secp384r1")
        } catch (exception: GeneralSecurityException) {
            assumeNoException(exception)
            return
        }

        try {
            EcdhKeyAgreement.deriveSharedSecret(
                privateKey = alice.privateKey(),
                publicKey = otherCurvePeer.publicKey()
            )
            fail("Deriving a shared secret with a non-P-256 public key should fail.")
        } catch (_: IllegalArgumentException) {
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
