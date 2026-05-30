package gr.hua.aurora.protocol

import gr.hua.aurora.crypto.Sec1PublicKeyEncoding

private const val supportedEncryptedMessageProtocolVersion = 1

class EncryptedMessageEnvelope(
    val protocolVersion: Int = supportedEncryptedMessageProtocolVersion,
    senderPublicKey: ByteArray,
    val payload: EncryptedPayloadFrame
) {
    private val storedSenderPublicKey = senderPublicKey.copyOf()

    init {
        require(protocolVersion == supportedEncryptedMessageProtocolVersion) {
            "Encrypted message protocolVersion must be $supportedEncryptedMessageProtocolVersion."
        }
        Sec1PublicKeyEncoding.decodeUncompressed(storedSenderPublicKey)
    }

    val senderPublicKey: ByteArray
        get() = storedSenderPublicKey.copyOf()
}
