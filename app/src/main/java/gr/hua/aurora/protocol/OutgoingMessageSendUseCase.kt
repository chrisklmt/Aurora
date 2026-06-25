package gr.hua.aurora.protocol

import gr.hua.aurora.ble.transport.BleTransportSendResult
import gr.hua.aurora.ble.transport.BleTransportSender
import gr.hua.aurora.model.OutgoingChatMessage

class OutgoingMessageSendEncryptionMaterial(
    senderPublicKey: ByteArray,
    keyBytes: ByteArray,
    authenticatedData: ByteArray? = null
) {
    private val storedSenderPublicKey = senderPublicKey.copyOf()
    private val storedKeyBytes = keyBytes.copyOf()
    private val storedAuthenticatedData = authenticatedData?.copyOf()

    val senderPublicKey: ByteArray
        get() = storedSenderPublicKey.copyOf()

    val keyBytes: ByteArray
        get() = storedKeyBytes.copyOf()

    val authenticatedData: ByteArray?
        get() = storedAuthenticatedData?.copyOf()
}

object OutgoingMessageSendUseCase {
    suspend fun send(
        message: OutgoingChatMessage,
        senderId: String,
        encryptionMaterial: OutgoingMessageSendEncryptionMaterial,
        transportSender: BleTransportSender,
        targetPeerId: String? = null
    ): BleTransportSendResult {
        val draft = OutgoingMessageFrameBuilder.build(message)
        val resolvedFrame = OutgoingMessageFrameResolver.resolve(
            draft = draft,
            senderId = senderId
        )
        return MessageFrameTransportSendUseCase.send(
            frame = resolvedFrame,
            encryptionMaterial = encryptionMaterial,
            transportSender = transportSender,
            targetPeerId = targetPeerId
        )
    }
}
