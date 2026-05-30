package gr.hua.aurora.protocol

import gr.hua.aurora.crypto.Sec1PublicKeyEncoding
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

class EncryptedMessageEnvelopeDecryptorTest {
    @Test
    fun decryptsBuiltEnvelopeToOriginalPlaintext() {
        val key = deterministicKey(41)
        val plaintext = "Aurora decryptor payload".toByteArray()
        val envelope = EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = senderPublicKeyBytes(),
            keyBytes = key,
            plaintext = plaintext,
            authenticatedData = "decryptor-aad".toByteArray()
        )

        val decryptedPayload = EncryptedMessageEnvelopeDecryptor.decrypt(
            envelope = envelope,
            keyBytes = key,
            authenticatedData = "decryptor-aad".toByteArray()
        )

        assertArrayEquals(plaintext, decryptedPayload)
    }

    @Test
    fun decryptsEncodedDecodedEnvelopeToOriginalPlaintext() {
        val key = deterministicKey(51)
        val plaintext = "Aurora decryptor payload".toByteArray()
        val envelope = EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = senderPublicKeyBytes(),
            keyBytes = key,
            plaintext = plaintext,
            authenticatedData = "decryptor-aad".toByteArray()
        )
        val decodedEnvelope = EncryptedMessageEnvelopeCodec.decode(
            EncryptedMessageEnvelopeCodec.encode(envelope)
        )

        val decryptedPayload = EncryptedMessageEnvelopeDecryptor.decrypt(
            envelope = decodedEnvelope,
            keyBytes = key,
            authenticatedData = "decryptor-aad".toByteArray()
        )

        assertArrayEquals(plaintext, decryptedPayload)
    }

    @Test
    fun differentAuthenticatedDataFails() {
        val key = deterministicKey(61)
        val envelope = EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = senderPublicKeyBytes(),
            keyBytes = key,
            plaintext = "Aurora decryptor payload".toByteArray(),
            authenticatedData = "decryptor-aad".toByteArray()
        )

        assertThrows(GeneralSecurityException::class.java) {
            EncryptedMessageEnvelopeDecryptor.decrypt(
                envelope = envelope,
                keyBytes = key,
                authenticatedData = "wrong-aad".toByteArray()
            )
        }
    }

    @Test
    fun emptyAuthenticatedDataBehavesLikeAbsentAuthenticatedData() {
        val senderPublicKey = senderPublicKeyBytes()
        val key = deterministicKey(71)
        val plaintext = "Aurora decryptor payload".toByteArray()

        val builtWithEmptyAuthenticatedData = EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = senderPublicKey,
            keyBytes = key,
            plaintext = plaintext,
            authenticatedData = ByteArray(0)
        )

        assertArrayEquals(
            plaintext,
            EncryptedMessageEnvelopeDecryptor.decrypt(
                envelope = builtWithEmptyAuthenticatedData,
                keyBytes = key
            )
        )

        val builtWithoutAuthenticatedData = EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = senderPublicKey,
            keyBytes = key,
            plaintext = plaintext
        )

        assertArrayEquals(
            plaintext,
            EncryptedMessageEnvelopeDecryptor.decrypt(
                envelope = builtWithoutAuthenticatedData,
                keyBytes = key,
                authenticatedData = ByteArray(0)
            )
        )
    }

    @Test
    fun wrongKeyFails() {
        val envelope = EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = senderPublicKeyBytes(),
            keyBytes = deterministicKey(81),
            plaintext = "Aurora decryptor payload".toByteArray(),
            authenticatedData = "decryptor-aad".toByteArray()
        )

        assertThrows(GeneralSecurityException::class.java) {
            EncryptedMessageEnvelopeDecryptor.decrypt(
                envelope = envelope,
                keyBytes = deterministicKey(91),
                authenticatedData = "decryptor-aad".toByteArray()
            )
        }
    }

    private fun senderPublicKeyBytes(): ByteArray {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val publicKey = generator.generateKeyPair().public as ECPublicKey
        return Sec1PublicKeyEncoding.encodeUncompressed(publicKey)
    }

    private fun deterministicKey(offset: Int): ByteArray {
        return ByteArray(32) { index -> (index + offset).toByte() }
    }
}
