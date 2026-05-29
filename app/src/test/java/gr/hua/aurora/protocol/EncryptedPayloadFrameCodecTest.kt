package gr.hua.aurora.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class EncryptedPayloadFrameCodecTest {
    @Test
    fun encodeDecodeRoundtripPreservesFields() {
        val frame = EncryptedPayloadFrame(
            protocolVersion = 1,
            nonce = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
            ciphertext = byteArrayOf(9, 8, 7, 6, 5)
        )

        val encoded = EncryptedPayloadFrameCodec.encode(frame)
        val decoded = EncryptedPayloadFrameCodec.decode(encoded)

        assertEquals(frame.protocolVersion, decoded.protocolVersion)
        assertArrayEquals(frame.nonce, decoded.nonce)
        assertArrayEquals(frame.ciphertext, decoded.ciphertext)
    }

    @Test
    fun frameDefensivelyCopiesArrays() {
        val nonce = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
        val ciphertext = byteArrayOf(3, 4, 5, 6)
        val frame = EncryptedPayloadFrame(
            nonce = nonce,
            ciphertext = ciphertext
        )

        nonce[0] = 99.toByte()
        ciphertext[0] = 88.toByte()

        assertArrayEquals(
            byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
            frame.nonce
        )
        assertArrayEquals(
            byteArrayOf(3, 4, 5, 6),
            frame.ciphertext
        )

        val nonceFromGetter = frame.nonce
        val ciphertextFromGetter = frame.ciphertext
        nonceFromGetter[1] = 77.toByte()
        ciphertextFromGetter[1] = 66.toByte()

        assertArrayEquals(
            byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
            frame.nonce
        )
        assertArrayEquals(
            byteArrayOf(3, 4, 5, 6),
            frame.ciphertext
        )
    }

    @Test
    fun invalidPartCountFails() {
        assertThrows(IllegalArgumentException::class.java) {
            EncryptedPayloadFrameCodec.decode("bad|frame")
        }
    }

    @Test
    fun unsupportedKindFails() {
        val encoded = "WRONG_KIND|1|${validNonceToken()}|${validCiphertextToken()}"

        assertThrows(IllegalArgumentException::class.java) {
            EncryptedPayloadFrameCodec.decode(encoded)
        }
    }

    @Test
    fun unsupportedVersionFails() {
        val encoded = "AURORA_ENCRYPTED_PAYLOAD|2|${validNonceToken()}|${validCiphertextToken()}"

        assertThrows(IllegalArgumentException::class.java) {
            EncryptedPayloadFrameCodec.decode(encoded)
        }
    }

    @Test
    fun invalidNonceLengthFails() {
        val shortNonce = Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(1, 2, 3))
        val encoded = "AURORA_ENCRYPTED_PAYLOAD|1|$shortNonce|${validCiphertextToken()}"

        assertThrows(IllegalArgumentException::class.java) {
            EncryptedPayloadFrameCodec.decode(encoded)
        }
    }

    @Test
    fun emptyCiphertextFails() {
        val encoded = "AURORA_ENCRYPTED_PAYLOAD|1|${validNonceToken()}|"

        assertThrows(IllegalArgumentException::class.java) {
            EncryptedPayloadFrameCodec.decode(encoded)
        }
    }

    @Test
    fun invalidBase64FieldFails() {
        val encoded = "AURORA_ENCRYPTED_PAYLOAD|1|***|${validCiphertextToken()}"

        assertThrows(IllegalArgumentException::class.java) {
            EncryptedPayloadFrameCodec.decode(encoded)
        }
    }

    private fun validNonceToken(): String {
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11))
    }

    private fun validCiphertextToken(): String {
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(byteArrayOf(9, 8, 7, 6))
    }
}
