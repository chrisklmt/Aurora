package gr.hua.aurora.protocol

import gr.hua.aurora.crypto.Sec1PublicKeyEncoding
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class PeerIdentityExchangeCodecTest {
    @Test
    fun encodeDecodePreservesPeerId() {
        val message = PeerIdentityExchangeMessage(
            peerId = "peer-alpha",
            publicAgreementKeyBytes = validPublicKeyBytes(),
            createdAtMillis = 1_715_902_001L
        )

        val decoded = PeerIdentityExchangeCodec.decode(
            PeerIdentityExchangeCodec.encode(message)
        )

        assertEquals(message.peerId, decoded.peerId)
    }

    @Test
    fun encodeDecodePreservesPublicKeyBytes() {
        val publicKeyBytes = validPublicKeyBytes()
        val message = PeerIdentityExchangeMessage(
            peerId = "peer-beta",
            publicAgreementKeyBytes = publicKeyBytes,
            createdAtMillis = 1_715_902_002L
        )

        val decoded = PeerIdentityExchangeCodec.decode(
            PeerIdentityExchangeCodec.encode(message)
        )

        assertArrayEquals(publicKeyBytes, decoded.publicAgreementKeyBytes())
    }

    @Test
    fun invalidOrEmptyPeerIdIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PeerIdentityExchangeMessage(
                peerId = "",
                publicAgreementKeyBytes = validPublicKeyBytes(),
                createdAtMillis = 1L
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            PeerIdentityExchangeCodec.decode(
                buildEncodedMessage(
                    peerIdBytes = ByteArray(0),
                    createdAtMillis = 1_715_902_003L,
                    publicKeyBytes = validPublicKeyBytes()
                )
            )
        }
    }

    @Test
    fun invalidOrEmptyKeyIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PeerIdentityExchangeMessage(
                peerId = "peer-invalid-key",
                publicAgreementKeyBytes = ByteArray(0),
                createdAtMillis = 1L
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            PeerIdentityExchangeCodec.decode(
                buildEncodedMessage(
                    peerIdBytes = "peer-invalid-key".toByteArray(UTF_8),
                    createdAtMillis = 1_715_902_004L,
                    publicKeyBytes = ByteArray(0)
                )
            )
        }
    }

    @Test
    fun publicKeyBytesUseDefensiveCopies() {
        val originalPublicKeyBytes = validPublicKeyBytes()
        val message = PeerIdentityExchangeMessage(
            peerId = "peer-copy",
            publicAgreementKeyBytes = originalPublicKeyBytes,
            createdAtMillis = 1_715_902_005L
        )

        originalPublicKeyBytes[1] = (originalPublicKeyBytes[1].toInt() xor 0x01).toByte()
        val firstCopy = message.publicAgreementKeyBytes()
        val secondCopy = message.publicAgreementKeyBytes()

        assertNotSame(firstCopy, secondCopy)
        firstCopy[2] = (firstCopy[2].toInt() xor 0x01).toByte()
        assertArrayEquals(secondCopy, message.publicAgreementKeyBytes())
    }

    @Test
    fun messageFrameBoundaryPreservesIdentityPayload() {
        val message = PeerIdentityExchangeMessage(
            peerId = "peer-frame",
            publicAgreementKeyBytes = validPublicKeyBytes(),
            createdAtMillis = 1_715_902_006L
        )

        val decoded = PeerIdentityExchangeMessage.fromMessageFrame(
            message.toMessageFrame()
        )

        assertEquals(message.peerId, decoded.peerId)
        assertEquals(message.createdAtMillis, decoded.createdAtMillis)
        assertArrayEquals(message.publicAgreementKeyBytes(), decoded.publicAgreementKeyBytes())
    }

    private fun buildEncodedMessage(
        peerIdBytes: ByteArray,
        createdAtMillis: Long,
        publicKeyBytes: ByteArray
    ): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return listOf(
            "AURORA_PEER_IDENTITY_V1",
            encoder.encodeToString(peerIdBytes),
            createdAtMillis.toString(),
            encoder.encodeToString(publicKeyBytes)
        ).joinToString("|")
    }

    private fun validPublicKeyBytes(): ByteArray {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val publicKey = generator.generateKeyPair().public as ECPublicKey
        return Sec1PublicKeyEncoding.encodeUncompressed(publicKey)
    }
}
