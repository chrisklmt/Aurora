package gr.hua.aurora.protocol

import gr.hua.aurora.crypto.Sec1PublicKeyEncoding

private const val legacyEncryptedMessageProtocolVersion = 1
private const val relayAwareEncryptedMessageProtocolVersion = 2

class EncryptedMessageEnvelope(
    val protocolVersion: Int = LEGACY_PROTOCOL_VERSION,
    senderPublicKey: ByteArray,
    val relayMetadata: EncryptedMessageRelayMetadata? = null,
    val payload: EncryptedPayloadFrame
) {
    private val storedSenderPublicKey = senderPublicKey.copyOf()

    init {
        require(
            protocolVersion == legacyEncryptedMessageProtocolVersion ||
                protocolVersion == relayAwareEncryptedMessageProtocolVersion
        ) {
            "Encrypted message protocolVersion must be $legacyEncryptedMessageProtocolVersion or $relayAwareEncryptedMessageProtocolVersion."
        }
        require(
            (protocolVersion == relayAwareEncryptedMessageProtocolVersion) == (relayMetadata != null)
        ) {
            "Encrypted message relay metadata presence must match the protocolVersion."
        }
        Sec1PublicKeyEncoding.decodeUncompressed(storedSenderPublicKey)
    }

    val senderPublicKey: ByteArray
        get() = storedSenderPublicKey.copyOf()

    companion object {
        const val LEGACY_PROTOCOL_VERSION: Int = legacyEncryptedMessageProtocolVersion
        const val RELAY_AWARE_PROTOCOL_VERSION: Int = relayAwareEncryptedMessageProtocolVersion
    }
}
