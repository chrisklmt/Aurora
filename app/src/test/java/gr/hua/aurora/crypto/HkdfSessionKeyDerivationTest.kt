package gr.hua.aurora.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class HkdfSessionKeyDerivationTest {
    @Test
    fun derivedSessionKeyHasExpectedLength() {
        val derivedKey = HkdfSessionKeyDerivation.deriveSessionKey(
            sharedSecret = byteArrayOf(1, 2, 3, 4)
        )

        assertEquals(32, derivedKey.size)
    }

    @Test
    fun sameSharedSecretDerivesSameSessionKey() {
        val sharedSecret = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2)

        val firstKey = HkdfSessionKeyDerivation.deriveSessionKey(sharedSecret)
        val secondKey = HkdfSessionKeyDerivation.deriveSessionKey(sharedSecret)

        assertArrayEquals(firstKey, secondKey)
    }

    @Test
    fun differentSharedSecretsDeriveDifferentSessionKeys() {
        val firstKey = HkdfSessionKeyDerivation.deriveSessionKey(
            sharedSecret = byteArrayOf(1, 3, 3, 7)
        )
        val secondKey = HkdfSessionKeyDerivation.deriveSessionKey(
            sharedSecret = byteArrayOf(1, 3, 3, 8)
        )

        assertFalse(firstKey.contentEquals(secondKey))
    }

    @Test
    fun emptySharedSecretFails() {
        try {
            HkdfSessionKeyDerivation.deriveSessionKey(ByteArray(0))
            fail("Deriving a session key from an empty shared secret should fail.")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun fixedInputDerivesExpectedKey() {
        val sharedSecret = byteArrayOf(
            0x00, 0x01, 0x02, 0x03,
            0x04, 0x05, 0x06, 0x07,
            0x08, 0x09, 0x0A, 0x0B,
            0x0C, 0x0D, 0x0E, 0x0F
        )

        val derivedKey = HkdfSessionKeyDerivation.deriveSessionKey(sharedSecret)

        assertArrayEquals(
            byteArrayOf(
                157.toByte(), 58, 118, 61, 177.toByte(), 0, 130.toByte(), 10,
                175.toByte(), 144.toByte(), 237.toByte(), 63, 38, 111, 49, 162.toByte(),
                156.toByte(), 94, 177.toByte(), 112, 76, 102, 27, 92,
                34, 213.toByte(), 29, 4, 138.toByte(), 189.toByte(), 8, 61
            ),
            derivedKey
        )
    }
}
