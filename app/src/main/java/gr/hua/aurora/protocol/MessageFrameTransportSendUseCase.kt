package gr.hua.aurora.protocol

import gr.hua.aurora.ble.transport.BleTransportSendResult
import gr.hua.aurora.ble.transport.BleTransportSender
import gr.hua.aurora.ble.transport.OutgoingBleTransportSendPlanBuilder
import java.nio.charset.StandardCharsets.UTF_8
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

object MessageFrameTransportSendUseCase {
    suspend fun send(
        frame: MessageFrame,
        encryptionMaterial: OutgoingMessageSendEncryptionMaterial,
        transportSender: BleTransportSender,
        targetPeerId: String? = null
    ): BleTransportSendResult {
        val encodedFrameBytes = MessageFrameCodec.encode(frame).toByteArray(UTF_8)
        val envelope = EncryptedMessageEnvelopeBuilder.build(
            senderPublicKey = encryptionMaterial.senderPublicKey,
            keyBytes = encryptionMaterial.keyBytes,
            plaintext = encodedFrameBytes,
            authenticatedData = encryptionMaterial.authenticatedData,
            relayMetadata = EncryptedMessageRelayMetadata(
                messageId = frame.id,
                messageType = frame.type,
                ttl = frame.ttl
            )
        )
        return sendEncryptedEnvelope(
            envelope = envelope,
            transportSender = transportSender,
            targetPeerId = targetPeerId ?: frame.recipientId,
            sourceCreatedAtMillis = frame.createdAtMillis
        )
    }

    suspend fun sendPublic(
        frame: MessageFrame,
        transportSender: BleTransportSender,
        targetPeerId: String? = null
    ): BleTransportSendResult {
        val encodedFrameBytes = MessageFrameCodec.encode(frame).toByteArray(UTF_8)
        return submitEncodedPayload(
            messageId = frame.id,
            targetPeerId = targetPeerId ?: frame.recipientId,
            sourceCreatedAtMillis = frame.createdAtMillis,
            encodedPayloadBytes = encodedFrameBytes,
            transportSender = transportSender
        )
    }

    suspend fun sendEncryptedEnvelope(
        envelope: EncryptedMessageEnvelope,
        transportSender: BleTransportSender,
        targetPeerId: String? = null,
        sourceCreatedAtMillis: Long? = null
    ): BleTransportSendResult {
        val relayMessageId = envelope.relayMetadata?.messageId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return BleTransportSendResult.Failed(
                reason = "Encrypted relay metadata is missing the message id."
            )
        val encodedEnvelopeBytes = EncryptedMessageEnvelopeCodec.encode(envelope).toByteArray(UTF_8)
        return submitEncodedPayload(
            messageId = relayMessageId,
            targetPeerId = targetPeerId,
            sourceCreatedAtMillis = sourceCreatedAtMillis,
            encodedPayloadBytes = encodedEnvelopeBytes,
            transportSender = transportSender
        )
    }

    private suspend fun submitEncodedPayload(
        messageId: String,
        targetPeerId: String?,
        sourceCreatedAtMillis: Long?,
        encodedPayloadBytes: ByteArray,
        transportSender: BleTransportSender
    ): BleTransportSendResult {
        val sendPlan = runCatching {
            OutgoingBleTransportSendPlanBuilder.build(
                messageId = messageId,
                targetPeerId = targetPeerId,
                encryptedEnvelopeBytes = encodedPayloadBytes,
                sourceCreatedAtMillis = sourceCreatedAtMillis
            )
        }.getOrElse { error ->
            return BleTransportSendResult.Failed(
                reason = error.message ?: "Encoded transport payload could not be chunked safely."
            )
        }
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
