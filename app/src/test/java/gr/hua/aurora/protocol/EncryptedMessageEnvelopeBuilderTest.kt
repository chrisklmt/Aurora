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

class EncryptedMessageEnvelopeBuilderTest {
    @Test
    fun builderPreservesSenderPublicKey() {
        val senderPublicKey = senderPublicKeyBytes()

        val envelope = EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = senderPublicKey,
            keyBytes = deterministicKey(),
            plaintext = "Aurora builder payload".toByteArray()
        )

        assertArrayEquals(senderPublicKey, envelope.senderPublicKey)
    }

    @Test
    fun builderEnvelopeCanBeEncodedDecodedAndDecrypted() {
        val senderPublicKey = senderPublicKeyBytes()
        val key = deterministicKey()
        val plaintext = "Aurora builder payload".toByteArray()
        val authenticatedData = "builder-aad".toByteArray()

        val envelope = EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = senderPublicKey,
            keyBytes = key,
            plaintext = plaintext,
            authenticatedData = authenticatedData
        )
        val encoded = EncryptedMessageEnvelopeCodec.encode(envelope)
        val decoded = EncryptedMessageEnvelopeCodec.decode(encoded)
        val decodedPayload = EncryptedPayload(
            nonce = decoded.payload.nonce,
            ciphertext = decoded.payload.ciphertext
        )

        val decryptedPayload = AesGcmCipher.decrypt(key, decodedPayload, authenticatedData)

        assertArrayEquals(plaintext, decryptedPayload)
    }

    @Test
    fun differentAuthenticatedDataFailsAfterBuilderAndCodecRoundtrip() {
        val key = deterministicKey()
        val envelope = EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = senderPublicKeyBytes(),
            keyBytes = key,
            plaintext = "Aurora builder payload".toByteArray(),
            authenticatedData = "builder-aad".toByteArray()
        )
        val decoded = EncryptedMessageEnvelopeCodec.decode(
            EncryptedMessageEnvelopeCodec.encode(envelope)
        )
        val decodedPayload = EncryptedPayload(
            nonce = decoded.payload.nonce,
            ciphertext = decoded.payload.ciphertext
        )

        assertThrows(GeneralSecurityException::class.java) {
            AesGcmCipher.decrypt(key, decodedPayload, "wrong-aad".toByteArray())
        }
    }

    @Test
    fun emptyAuthenticatedDataBehavesLikeAbsentAuthenticatedData() {
        val senderPublicKey = senderPublicKeyBytes()
        val key = deterministicKey()
        val plaintext = "Aurora builder payload".toByteArray()

        val builtWithEmptyAuthenticatedData = EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = senderPublicKey,
            keyBytes = key,
            plaintext = plaintext,
            authenticatedData = ByteArray(0)
        )
        val decodedWithoutAuthenticatedData = decodePayload(
            EncryptedMessageEnvelopeCodec.decode(
                EncryptedMessageEnvelopeCodec.encode(builtWithEmptyAuthenticatedData)
            )
        )

        assertArrayEquals(
            plaintext,
            AesGcmCipher.decrypt(key, decodedWithoutAuthenticatedData)
        )

        val builtWithoutAuthenticatedData = EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = senderPublicKey,
            keyBytes = key,
            plaintext = plaintext
        )
        val decodedWithEmptyAuthenticatedData = decodePayload(
            EncryptedMessageEnvelopeCodec.decode(
                EncryptedMessageEnvelopeCodec.encode(builtWithoutAuthenticatedData)
            )
        )

        assertArrayEquals(
            plaintext,
            AesGcmCipher.decrypt(key, decodedWithEmptyAuthenticatedData, ByteArray(0))
        )
    }

    private fun decodePayload(envelope: EncryptedMessageEnvelope): EncryptedPayload {
        return EncryptedPayload(
            nonce = envelope.payload.nonce,
            ciphertext = envelope.payload.ciphertext
        )
    }

    private fun senderPublicKeyBytes(): ByteArray {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val publicKey = generator.generateKeyPair().public as ECPublicKey
        return Sec1PublicKeyEncoding.encodeUncompressed(publicKey)
    }

    private fun deterministicKey(): ByteArray {
        return ByteArray(32) { index -> (index + 31).toByte() }
    }
}
