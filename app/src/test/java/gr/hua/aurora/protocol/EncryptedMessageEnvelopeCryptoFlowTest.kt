package gr.hua.aurora.protocol

import gr.hua.aurora.crypto.AesGcmCipher
import gr.hua.aurora.crypto.EncryptedPayload
import gr.hua.aurora.crypto.Sec1PublicKeyEncoding
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class EncryptedMessageEnvelopeCryptoFlowTest {
    @Test
    fun encryptedPayloadRoundtripSurvivesEncryptedMessageEnvelopeCodec() {
        val senderPublicKey = senderPublicKeyBytes()
        val key = deterministicKey()
        val plaintext = "Aurora envelope payload".toByteArray()
        val authenticatedData = "envelope-aad".toByteArray()

        val encryptedPayload = AesGcmCipher.encrypt(key, plaintext, authenticatedData)
        val payloadFrame = EncryptedPayloadFrame(
            nonce = encryptedPayload.nonce,
            ciphertext = encryptedPayload.ciphertext
        )
        val envelope = EncryptedMessageEnvelope(
            senderPublicKey = senderPublicKey,
            payload = payloadFrame
        )
        val encoded = EncryptedMessageEnvelopeCodec.encode(envelope)
        val decoded = EncryptedMessageEnvelopeCodec.decode(encoded)
        val decodedPayload = EncryptedPayload(
            nonce = decoded.payload.nonce,
            ciphertext = decoded.payload.ciphertext
        )

        assertArrayEquals(senderPublicKey, decoded.senderPublicKey)

        val decryptedPayload = AesGcmCipher.decrypt(key, decodedPayload, authenticatedData)

        assertArrayEquals(plaintext, decryptedPayload)
    }

    @Test
    fun differentAuthenticatedDataFailsAfterEncryptedMessageEnvelopeCodecRoundtrip() {
        val senderPublicKey = senderPublicKeyBytes()
        val key = deterministicKey()
        val plaintext = "Aurora envelope payload".toByteArray()
        val authenticatedData = "envelope-aad".toByteArray()
        val differentAuthenticatedData = "wrong-aad".toByteArray()

        val encryptedPayload = AesGcmCipher.encrypt(key, plaintext, authenticatedData)
        val payloadFrame = EncryptedPayloadFrame(
            nonce = encryptedPayload.nonce,
            ciphertext = encryptedPayload.ciphertext
        )
        val envelope = EncryptedMessageEnvelope(
            senderPublicKey = senderPublicKey,
            payload = payloadFrame
        )
        val encoded = EncryptedMessageEnvelopeCodec.encode(envelope)
        val decoded = EncryptedMessageEnvelopeCodec.decode(encoded)
        val decodedPayload = EncryptedPayload(
            nonce = decoded.payload.nonce,
            ciphertext = decoded.payload.ciphertext
        )

        assertThrows(GeneralSecurityException::class.java) {
            AesGcmCipher.decrypt(key, decodedPayload, differentAuthenticatedData)
        }
    }

    private fun senderPublicKeyBytes(): ByteArray {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val publicKey = generator.generateKeyPair().public as ECPublicKey
        return Sec1PublicKeyEncoding.encodeUncompressed(publicKey)
    }

    private fun deterministicKey(): ByteArray {
        return ByteArray(32) { index -> (index + 21).toByte() }
    }
}
