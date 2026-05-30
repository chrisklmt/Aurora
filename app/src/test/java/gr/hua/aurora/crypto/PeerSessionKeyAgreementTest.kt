package gr.hua.aurora.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class PeerSessionKeyAgreementTest {
    @Test
    fun aliceAndBobDeriveSameSessionKeyFromPeerPublicBytes() {
        val alice = generateEcKeyPair("secp256r1")
        val bob = generateEcKeyPair("secp256r1")
        val bobPublicKeyBytes = Sec1PublicKeyEncoding.encodeUncompressed(bob.publicKey())
        val alicePublicKeyBytes = Sec1PublicKeyEncoding.encodeUncompressed(alice.publicKey())

        val aliceSessionKey = PeerSessionKeyAgreement.deriveSessionKey(
            privateKey = alice.privateKey(),
            peerPublicKeyBytes = bobPublicKeyBytes
        )
        val bobSessionKey = PeerSessionKeyAgreement.deriveSessionKey(
            privateKey = bob.privateKey(),
            peerPublicKeyBytes = alicePublicKeyBytes
        )

        assertArrayEquals(aliceSessionKey, bobSessionKey)
    }

    @Test
    fun derivedSessionKeyHasExpectedLength() {
        val alice = generateEcKeyPair("secp256r1")
        val bob = generateEcKeyPair("secp256r1")

        val derivedKey = PeerSessionKeyAgreement.deriveSessionKey(
            privateKey = alice.privateKey(),
            peerPublicKeyBytes = Sec1PublicKeyEncoding.encodeUncompressed(bob.publicKey())
        )

        assertEquals(32, derivedKey.size)
    }

    @Test
    fun differentPeerPublicKeyProducesDifferentSessionKey() {
        val alice = generateEcKeyPair("secp256r1")
        val bob = generateEcKeyPair("secp256r1")
        val mallory = generateEcKeyPair("secp256r1")

        val bobSessionKey = PeerSessionKeyAgreement.deriveSessionKey(
            privateKey = alice.privateKey(),
            peerPublicKeyBytes = Sec1PublicKeyEncoding.encodeUncompressed(bob.publicKey())
        )
        val mallorySessionKey = PeerSessionKeyAgreement.deriveSessionKey(
            privateKey = alice.privateKey(),
            peerPublicKeyBytes = Sec1PublicKeyEncoding.encodeUncompressed(mallory.publicKey())
        )

        assertFalse(bobSessionKey.contentEquals(mallorySessionKey))
    }

    @Test
    fun invalidPeerPublicKeyBytesFail() {
        val alice = generateEcKeyPair("secp256r1")
        val invalidPeerPublicKeyBytes = ByteArray(65).apply {
            this[0] = 0x05
        }

        assertThrows(IllegalArgumentException::class.java) {
            PeerSessionKeyAgreement.deriveSessionKey(
                privateKey = alice.privateKey(),
                peerPublicKeyBytes = invalidPeerPublicKeyBytes
            )
        }
    }

    @Test
    fun nonP256PeerPublicKeyBytesFailWhenProviderSupportsIt() {
        val alice = generateEcKeyPair("secp256r1")
        val otherCurvePeer = try {
            generateEcKeyPair("secp384r1")
        } catch (exception: GeneralSecurityException) {
            assumeNoException(exception)
            return
        }
        val otherCurvePeerBytes = encodeUncompressedLikeSec1(otherCurvePeer.publicKey(), 48)

        assertThrows(IllegalArgumentException::class.java) {
            PeerSessionKeyAgreement.deriveSessionKey(
                privateKey = alice.privateKey(),
                peerPublicKeyBytes = otherCurvePeerBytes
            )
        }
    }

    private fun generateEcKeyPair(curveName: String): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(curveName))
        return generator.generateKeyPair()
    }

    private fun encodeUncompressedLikeSec1(
        publicKey: ECPublicKey,
        coordinateSize: Int
    ): ByteArray {
        return byteArrayOf(0x04) +
            toFixedLengthUnsigned(publicKey.w.affineX.toByteArray(), coordinateSize) +
            toFixedLengthUnsigned(publicKey.w.affineY.toByteArray(), coordinateSize)
    }

    private fun toFixedLengthUnsigned(bytes: ByteArray, size: Int): ByteArray {
        return when {
            bytes.size == size -> bytes
            bytes.size < size -> ByteArray(size - bytes.size) + bytes
            bytes.size == size + 1 && bytes[0] == 0.toByte() -> bytes.copyOfRange(1, bytes.size)
            else -> throw IllegalArgumentException("Coordinate does not fit in $size bytes.")
        }
    }

    private fun KeyPair.privateKey(): ECPrivateKey {
        return private as ECPrivateKey
    }

    private fun KeyPair.publicKey(): ECPublicKey {
        return public as ECPublicKey
    }
}
