package gr.hua.aurora.protocol

import gr.hua.aurora.crypto.Sec1PublicKeyEncoding
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

class EncryptedMessageEnvelopeCodecTest {
    @Test
    fun encodeDecodeRoundtripPreservesFields() {
        val senderPublicKey = validSenderPublicKey()
        val envelope = EncryptedMessageEnvelope(
            protocolVersion = 1,
            senderPublicKey = senderPublicKey,
            payload = validPayload()
        )

        val encoded = EncryptedMessageEnvelopeCodec.encode(envelope)
        val decoded = EncryptedMessageEnvelopeCodec.decode(encoded)

        assertEquals(envelope.protocolVersion, decoded.protocolVersion)
        assertArrayEquals(senderPublicKey, decoded.senderPublicKey)
        assertEquals(envelope.payload.protocolVersion, decoded.payload.protocolVersion)
        assertArrayEquals(envelope.payload.nonce, decoded.payload.nonce)
        assertArrayEquals(envelope.payload.ciphertext, decoded.payload.ciphertext)
    }

    @Test
    fun envelopeDefensivelyCopiesSenderPublicKey() {
        val senderPublicKey = validSenderPublicKey()
        val envelope = EncryptedMessageEnvelope(
            senderPublicKey = senderPublicKey,
            payload = validPayload()
        )

        val originalCopy = senderPublicKey.copyOf()
        senderPublicKey[0] = (senderPublicKey[0].toInt() xor 0x01).toByte()

        assertArrayEquals(originalCopy, envelope.senderPublicKey)

        val senderPublicKeyFromGetter = envelope.senderPublicKey
        senderPublicKeyFromGetter[1] = (senderPublicKeyFromGetter[1].toInt() xor 0x01).toByte()

        assertArrayEquals(originalCopy, envelope.senderPublicKey)
    }

    @Test
    fun invalidPartCountFails() {
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedMessageEnvelopeCodec.decode("bad|frame")
        }
    }

    @Test
    fun unsupportedKindFails() {
        val encoded = "WRONG_KIND|1|${validSenderPublicKeyToken()}|${validPayloadToken()}"

        assertThrows(IllegalArgumentException::class.java) {
            EncryptedMessageEnvelopeCodec.decode(encoded)
        }
    }

    @Test
    fun unsupportedProtocolVersionFails() {
        val encoded = "AURORA_ENCRYPTED_MESSAGE|2|${validSenderPublicKeyToken()}|${validPayloadToken()}"

        assertThrows(IllegalArgumentException::class.java) {
            EncryptedMessageEnvelopeCodec.decode(encoded)
        }
    }

    @Test
    fun invalidSenderPublicKeyBase64Fails() {
        val encoded = "AURORA_ENCRYPTED_MESSAGE|1|***|${validPayloadToken()}"

        assertThrows(IllegalArgumentException::class.java) {
            EncryptedMessageEnvelopeCodec.decode(encoded)
        }
    }

    @Test
    fun invalidSenderPublicKeyShapeFails() {
        val wrongPrefixSenderPublicKey = ByteArray(65)
        wrongPrefixSenderPublicKey[0] = 0x05
        val senderPublicKeyToken = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(wrongPrefixSenderPublicKey)
        val encoded = "AURORA_ENCRYPTED_MESSAGE|1|$senderPublicKeyToken|${validPayloadToken()}"

        assertThrows(IllegalArgumentException::class.java) {
            EncryptedMessageEnvelopeCodec.decode(encoded)
        }
    }

    @Test
    fun invalidNestedEncryptedPayloadFails() {
        val invalidNestedPayload = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("bad|frame".toByteArray(UTF_8))
        val encoded = "AURORA_ENCRYPTED_MESSAGE|1|${validSenderPublicKeyToken()}|$invalidNestedPayload"

        assertThrows(IllegalArgumentException::class.java) {
            EncryptedMessageEnvelopeCodec.decode(encoded)
        }
    }

    private fun validPayload(): EncryptedPayloadFrame {
        return EncryptedPayloadFrame(
            protocolVersion = 1,
            nonce = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
            ciphertext = byteArrayOf(9, 8, 7, 6)
        )
    }

    private fun validSenderPublicKey(): ByteArray {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val publicKey = generator.generateKeyPair().public as ECPublicKey
        return Sec1PublicKeyEncoding.encodeUncompressed(publicKey)
    }

    private fun validSenderPublicKeyToken(): String {
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(validSenderPublicKey())
    }

    private fun validPayloadToken(): String {
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(EncryptedPayloadFrameCodec.encode(validPayload()).toByteArray(UTF_8))
    }
}
