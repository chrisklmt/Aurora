package gr.hua.aurora.protocol

import gr.hua.aurora.crypto.AesGcmCipher
import gr.hua.aurora.crypto.EncryptedPayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException

class EncryptedPayloadFrameCryptoFlowTest {
    @Test
    fun aesGcmPayloadRoundtripSurvivesEncryptedPayloadFrameCodec() {
        val key = deterministicKey()
        val plaintext = "Aurora encrypted payload".toByteArray()
        val authenticatedData = "frame-aad".toByteArray()

        val encryptedPayload = AesGcmCipher.encrypt(key, plaintext, authenticatedData)
        val frame = EncryptedPayloadFrame(
            nonce = encryptedPayload.nonce,
            ciphertext = encryptedPayload.ciphertext
        )
        val encoded = EncryptedPayloadFrameCodec.encode(frame)
        val decoded = EncryptedPayloadFrameCodec.decode(encoded)
        val decodedPayload = EncryptedPayload(
            nonce = decoded.nonce,
            ciphertext = decoded.ciphertext
        )

        val decryptedPayload = AesGcmCipher.decrypt(key, decodedPayload, authenticatedData)

        assertArrayEquals(plaintext, decryptedPayload)
    }

    @Test
    fun differentAuthenticatedDataFailsAfterEncryptedPayloadFrameCodecRoundtrip() {
        val key = deterministicKey()
        val plaintext = "Aurora encrypted payload".toByteArray()
        val authenticatedData = "frame-aad".toByteArray()
        val differentAuthenticatedData = "wrong-aad".toByteArray()

        val encryptedPayload = AesGcmCipher.encrypt(key, plaintext, authenticatedData)
        val frame = EncryptedPayloadFrame(
            nonce = encryptedPayload.nonce,
            ciphertext = encryptedPayload.ciphertext
        )
        val encoded = EncryptedPayloadFrameCodec.encode(frame)
        val decoded = EncryptedPayloadFrameCodec.decode(encoded)
        val decodedPayload = EncryptedPayload(
            nonce = decoded.nonce,
            ciphertext = decoded.ciphertext
        )

        assertThrows(GeneralSecurityException::class.java) {
            AesGcmCipher.decrypt(key, decodedPayload, differentAuthenticatedData)
        }
    }

    private fun deterministicKey(): ByteArray {
        return ByteArray(32) { index -> (index + 11).toByte() }
    }
}
