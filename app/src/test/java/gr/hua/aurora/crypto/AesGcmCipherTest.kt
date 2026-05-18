package gr.hua.aurora.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.security.GeneralSecurityException

class AesGcmCipherTest {
    @Test
    fun encryptDecryptRoundtrip() {
        val plaintext = "Aurora secret payload".toByteArray()

        val encryptedPayload = AesGcmCipher.encrypt(validKey(), plaintext)

        val decryptedPayload = AesGcmCipher.decrypt(validKey(), encryptedPayload)

        assertArrayEquals(plaintext, decryptedPayload)
    }

    @Test
    fun wrongKeyFails() {
        val encryptedPayload = AesGcmCipher.encrypt(validKey(), "secret".toByteArray())

        try {
            AesGcmCipher.decrypt(otherValidKey(), encryptedPayload)
            fail("Decrypting with a wrong key should fail.")
        } catch (_: GeneralSecurityException) {
        }
    }

    @Test
    fun tamperedCiphertextFails() {
        val encryptedPayload = AesGcmCipher.encrypt(validKey(), "secret".toByteArray())
        val tamperedCiphertext = encryptedPayload.ciphertext.also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        }

        try {
            AesGcmCipher.decrypt(
                validKey(),
                EncryptedPayload(
                    nonce = encryptedPayload.nonce,
                    ciphertext = tamperedCiphertext
                )
            )
            fail("Decrypting tampered ciphertext should fail.")
        } catch (_: GeneralSecurityException) {
        }
    }

    @Test
    fun differentEncryptionsUseDifferentNonce() {
        val firstPayload = AesGcmCipher.encrypt(validKey(), "same payload".toByteArray())
        val secondPayload = AesGcmCipher.encrypt(validKey(), "same payload".toByteArray())

        assertFalse(firstPayload.nonce.contentEquals(secondPayload.nonce))
    }

    @Test
    fun invalidKeyLengthFails() {
        try {
            AesGcmCipher.encrypt(ByteArray(31), "secret".toByteArray())
            fail("Encrypting with an invalid key length should fail.")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun validKey(): ByteArray {
        return ByteArray(32) { index -> index.toByte() }
    }

    private fun otherValidKey(): ByteArray {
        return ByteArray(32) { index -> (index + 1).toByte() }
    }
}
