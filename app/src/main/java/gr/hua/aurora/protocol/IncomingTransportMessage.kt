package gr.hua.aurora.protocol

class IncomingTransportMessage(
    val frame: MessageFrame,
    senderPublicKey: ByteArray? = null
) {
    private val storedSenderPublicKey = senderPublicKey?.copyOf()

    val senderPublicKey: ByteArray?
        get() = storedSenderPublicKey?.copyOf()
}
