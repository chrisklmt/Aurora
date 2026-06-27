package gr.hua.aurora.protocol

import gr.hua.aurora.crypto.AesGcmCipher

object EncryptedMessageEnvelopeBuilder {
    fun build(
        senderPublicKey: ByteArray,
        keyBytes: ByteArray,
        plaintext: ByteArray,
        authenticatedData: ByteArray? = null,
        relayMetadata: EncryptedMessageRelayMetadata? = null
    ): EncryptedMessageEnvelope {
        val encryptedPayload = AesGcmCipher.encrypt(
            keyBytes = keyBytes,
            plaintext = plaintext,
            authenticatedData = authenticatedData
        )

        return EncryptedMessageEnvelope(
            protocolVersion = if (relayMetadata == null) {
                EncryptedMessageEnvelope.LEGACY_PROTOCOL_VERSION
            } else {
                EncryptedMessageEnvelope.RELAY_AWARE_PROTOCOL_VERSION
            },
            senderPublicKey = senderPublicKey,
            relayMetadata = relayMetadata,
            payload = EncryptedPayloadFrame(
                nonce = encryptedPayload.nonce,
                ciphertext = encryptedPayload.ciphertext
            )
        )
    }
}
