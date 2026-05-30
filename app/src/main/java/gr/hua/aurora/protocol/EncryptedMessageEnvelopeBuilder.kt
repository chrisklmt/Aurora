package gr.hua.aurora.protocol

import gr.hua.aurora.crypto.AesGcmCipher

object EncryptedMessageEnvelopeBuilder {
    fun build(
        senderPublicKey: ByteArray,
        keyBytes: ByteArray,
        plaintext: ByteArray,
        authenticatedData: ByteArray? = null
    ): EncryptedMessageEnvelope {
        val encryptedPayload = AesGcmCipher.encrypt(
            keyBytes = keyBytes,
            plaintext = plaintext,
            authenticatedData = authenticatedData
        )

        return EncryptedMessageEnvelope(
            senderPublicKey = senderPublicKey,
            payload = EncryptedPayloadFrame(
                nonce = encryptedPayload.nonce,
                ciphertext = encryptedPayload.ciphertext
            )
        )
    }
}
