package gr.hua.aurora.crypto

import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object HkdfSessionKeyDerivation {
    private const val hmacAlgorithm = "HmacSHA256"
    private const val hashLengthBytes = 32
    private const val outputKeyLengthBytes = 32
    private val emptySalt = ByteArray(hashLengthBytes)
    private val sessionKeyInfo = "aurora-session-key-v1".toByteArray(StandardCharsets.UTF_8)

    // Ο helper παράγει μόνο bytes κλειδιού από κοινό μυστικό και δεν ορίζει ταυτότητα peer, πρωτόκολλο ή πλήρες secure session.
    fun deriveSessionKey(sharedSecret: ByteArray): ByteArray {
        require(sharedSecret.isNotEmpty()) { "sharedSecret must not be empty." }

        val pseudoRandomKey = hmacSha256(
            keyBytes = emptySalt,
            data = sharedSecret
        )
        val firstBlock = hmacSha256(
            keyBytes = pseudoRandomKey,
            data = sessionKeyInfo + byteArrayOf(0x01.toByte())
        )

        return firstBlock.copyOf(outputKeyLengthBytes)
    }

    private fun hmacSha256(
        keyBytes: ByteArray,
        data: ByteArray
    ): ByteArray {
        val mac = Mac.getInstance(hmacAlgorithm)
        mac.init(SecretKeySpec(keyBytes.copyOf(), hmacAlgorithm))
        return mac.doFinal(data.copyOf())
    }
}
