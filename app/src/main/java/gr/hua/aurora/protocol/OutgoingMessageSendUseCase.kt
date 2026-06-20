package gr.hua.aurora.protocol

import gr.hua.aurora.ble.transport.BleTransportSendResult
import gr.hua.aurora.ble.transport.BleTransportSender
import gr.hua.aurora.ble.transport.OutgoingBleTransportSendPlanBuilder
import gr.hua.aurora.model.OutgoingChatMessage
import java.nio.charset.StandardCharsets.UTF_8
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

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
        transportSender: BleTransportSender
    ): BleTransportSendResult {
        val draft = OutgoingMessageFrameBuilder.build(message)
        val resolvedFrame = OutgoingMessageFrameResolver.resolve(
            draft = draft,
            senderId = senderId
        )
        val encodedFrameBytes = MessageFrameCodec.encode(resolvedFrame).toByteArray(UTF_8)
        val envelope = EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = encryptionMaterial.senderPublicKey,
            keyBytes = encryptionMaterial.keyBytes,
            plaintext = encodedFrameBytes,
            authenticatedData = encryptionMaterial.authenticatedData
        )
        val encodedEnvelopeBytes = EncryptedMessageEnvelopeCodec.encode(envelope).toByteArray(UTF_8)
        val sendPlan = OutgoingBleTransportSendPlanBuilder.build(
            messageId = resolvedFrame.id,
            targetPeerId = resolvedFrame.recipientId,
            encryptedEnvelopeBytes = encodedEnvelopeBytes,
            sourceCreatedAtMillis = resolvedFrame.createdAtMillis
        )

        return suspendCoroutine { continuation ->
            var hasCompleted = false
            transportSender.send(
                plan = sendPlan,
                listener = object : BleTransportSender.Listener {
                    override fun onSendResult(result: BleTransportSendResult) {
                        if (hasCompleted) {
                            return
                        }
                        hasCompleted = true
                        continuation.resume(result)
                    }
                }
            )
        }
    }
}
