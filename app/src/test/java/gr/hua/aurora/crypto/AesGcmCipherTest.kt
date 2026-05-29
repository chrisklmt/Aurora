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
    fun encryptDecryptEmptyPlaintextRoundtrip() {
        val plaintext = ByteArray(0)

        val encryptedPayload = AesGcmCipher.encrypt(validKey(), plaintext)

        val decryptedPayload = AesGcmCipher.decrypt(validKey(), encryptedPayload)

        assertArrayEquals(plaintext, decryptedPayload)
    }

    @Test
    fun encryptDecryptRoundtripWithAuthenticatedData() {
        val plaintext = "Aurora secret payload".toByteArray()
        val authenticatedData = "aad".toByteArray()

        val encryptedPayload = AesGcmCipher.encrypt(validKey(), plaintext, authenticatedData)

        val decryptedPayload = AesGcmCipher.decrypt(validKey(), encryptedPayload, authenticatedData)

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
    fun tamperedNonceFails() {
        val encryptedPayload = AesGcmCipher.encrypt(validKey(), "secret".toByteArray())
        val tamperedNonce = encryptedPayload.nonce.also { bytes ->
            bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
        }

        try {
            AesGcmCipher.decrypt(
                validKey(),
                EncryptedPayload(
                    nonce = tamperedNonce,
                    ciphertext = encryptedPayload.ciphertext
                )
            )
            fail("Decrypting with a tampered nonce should fail.")
        } catch (_: GeneralSecurityException) {
        }
    }

    @Test
    fun decryptWithDifferentAuthenticatedDataFails() {
        val encryptedPayload = AesGcmCipher.encrypt(
            validKey(),
            "secret".toByteArray(),
            "aad-one".toByteArray()
        )

        try {
            AesGcmCipher.decrypt(
                validKey(),
                encryptedPayload,
                "aad-two".toByteArray()
            )
            fail("Decrypting with different authenticated data should fail.")
        } catch (_: GeneralSecurityException) {
        }
    }

    @Test
    fun decryptWithoutAuthenticatedDataFailsWhenEncryptionUsedAuthenticatedData() {
        val encryptedPayload = AesGcmCipher.encrypt(
            validKey(),
            "secret".toByteArray(),
            "aad".toByteArray()
        )

        try {
            AesGcmCipher.decrypt(validKey(), encryptedPayload)
            fail("Decrypting without authenticated data should fail when encryption used it.")
        } catch (_: GeneralSecurityException) {
        }
    }

    @Test
    fun emptyAuthenticatedDataMatchesAbsentAuthenticatedData() {
        val plaintext = "secret".toByteArray()
        val encryptedWithEmptyAuthenticatedData = AesGcmCipher.encrypt(
            validKey(),
            plaintext,
            ByteArray(0)
        )
        val decryptedWithoutAuthenticatedData = AesGcmCipher.decrypt(
            validKey(),
            encryptedWithEmptyAuthenticatedData
        )

        assertArrayEquals(plaintext, decryptedWithoutAuthenticatedData)

        val encryptedWithoutAuthenticatedData = AesGcmCipher.encrypt(validKey(), plaintext)
        val decryptedWithEmptyAuthenticatedData = AesGcmCipher.decrypt(
            validKey(),
            encryptedWithoutAuthenticatedData,
            ByteArray(0)
        )

        assertArrayEquals(plaintext, decryptedWithEmptyAuthenticatedData)
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
