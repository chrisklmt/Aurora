package gr.hua.aurora.protocol

import gr.hua.aurora.crypto.AesGcmCipher
import gr.hua.aurora.crypto.EncryptedPayload

object EncryptedMessageEnvelopeDecryptor {
    fun decrypt(
        envelope: EncryptedMessageEnvelope,
        keyBytes: ByteArray,
        authenticatedData: ByteArray? = null
    ): ByteArray {
        val encryptedPayload = EncryptedPayload(
            nonce = envelope.payload.nonce,
            ciphertext = envelope.payload.ciphertext
        )

        return AesGcmCipher.decrypt(
            keyBytes = keyBytes,
            encryptedPayload = encryptedPayload,
            authenticatedData = authenticatedData
        )
    }
}
