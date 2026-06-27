package gr.hua.aurora.protocol

class IncomingTransportMessage(
    val frame: MessageFrame,
    senderPublicKey: ByteArray? = null,
    val relayEnvelope: EncryptedMessageEnvelope? = null
) {
    private val storedSenderPublicKey = senderPublicKey?.copyOf()

    val senderPublicKey: ByteArray?
        get() = storedSenderPublicKey?.copyOf()

    val relayMetadata: EncryptedMessageRelayMetadata?
        get() = relayEnvelope?.relayMetadata
}
