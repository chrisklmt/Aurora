package gr.hua.aurora.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class Sec1PublicKeyEncodingTest {
    @Test
    fun encodeDecodeRoundtrip() {
        val originalPublicKey = generateEcKeyPair("secp256r1").publicKey()

        val encoded = Sec1PublicKeyEncoding.encodeUncompressed(originalPublicKey)
        val decoded = Sec1PublicKeyEncoding.decodeUncompressed(encoded)
        val reencoded = Sec1PublicKeyEncoding.encodeUncompressed(decoded)

        assertArrayEquals(encoded, reencoded)
        assertEquals(originalPublicKey.w.affineX, decoded.w.affineX)
        assertEquals(originalPublicKey.w.affineY, decoded.w.affineY)
    }

    @Test
    fun encodedPublicKeyHasExpectedSec1Shape() {
        val encoded = Sec1PublicKeyEncoding.encodeUncompressed(
            generateEcKeyPair("secp256r1").publicKey()
        )

        assertEquals(65, encoded.size)
        assertEquals(0x04, encoded[0].toInt() and 0xFF)
    }

    @Test
    fun decodeRejectsWrongLength() {
        try {
            Sec1PublicKeyEncoding.decodeUncompressed(ByteArray(64))
            fail("Decoding a 64-byte SEC1 public key should fail.")
        } catch (_: IllegalArgumentException) {
        }

        try {
            Sec1PublicKeyEncoding.decodeUncompressed(ByteArray(66))
            fail("Decoding a 66-byte SEC1 public key should fail.")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun decodeRejectsWrongPrefix() {
        val encoded = ByteArray(65)
        encoded[0] = 0x05

        try {
            Sec1PublicKeyEncoding.decodeUncompressed(encoded)
            fail("Decoding a SEC1 public key with the wrong prefix should fail.")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun decodeRejectsInvalidPoint() {
        val invalidPoint = ByteArray(65)
        invalidPoint[0] = 0x04

        try {
            Sec1PublicKeyEncoding.decodeUncompressed(invalidPoint)
            fail("Decoding an invalid SEC1 point should fail.")
        } catch (_: GeneralSecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun nonP256PublicKeyIsRejectedWhenProviderSupportsIt() {
        val otherCurvePublicKey = try {
            generateEcKeyPair("secp384r1").publicKey()
        } catch (exception: GeneralSecurityException) {
            assumeNoException(exception)
            return
        }

        try {
            Sec1PublicKeyEncoding.encodeUncompressed(otherCurvePublicKey)
            fail("Encoding a non-P-256 public key should fail.")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun generateEcKeyPair(curveName: String): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(curveName))
        return generator.generateKeyPair()
    }

    private fun KeyPair.publicKey(): ECPublicKey {
        return public as ECPublicKey
    }
}
