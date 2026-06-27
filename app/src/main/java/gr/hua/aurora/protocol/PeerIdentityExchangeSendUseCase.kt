package gr.hua.aurora.protocol

import gr.hua.aurora.ble.transport.BleTransportSendResult
import gr.hua.aurora.ble.transport.BleTransportSender
import gr.hua.aurora.ble.transport.OutgoingBleTransportSendPlanBuilder
import java.nio.charset.StandardCharsets.UTF_8
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

sealed interface PeerIdentityExchangeSendResult {
    data object SubmittedLocally : PeerIdentityExchangeSendResult
    data object SenderUnavailable : PeerIdentityExchangeSendResult

    data class Failed(
        val reason: String
    ) : PeerIdentityExchangeSendResult {
        init {
            require(reason.isNotBlank()) {
                "Peer identity exchange send failure reason must not be blank."
            }
        }
    }

    data class InvalidLocalIdentity(
        val reason: String
    ) : PeerIdentityExchangeSendResult {
        init {
            require(reason.isNotBlank()) {
                "Peer identity exchange invalid local identity reason must not be blank."
            }
        }
    }
}

object PeerIdentityExchangeSendUseCase {
    suspend fun send(
        localPeerId: String,
        localPublicAgreementKeyBytes: ByteArray,
        privateChatProposalId: String?,
        targetPeerId: String?,
        transportSender: BleTransportSender,
        createdAtMillis: Long
    ): PeerIdentityExchangeSendResult {
        val message = try {
            PeerIdentityExchangeMessage(
                peerId = localPeerId,
                publicAgreementKeyBytes = localPublicAgreementKeyBytes,
                createdAtMillis = createdAtMillis,
                privateChatProposalId = privateChatProposalId
            )
        } catch (error: IllegalArgumentException) {
            return PeerIdentityExchangeSendResult.InvalidLocalIdentity(
                reason = error.message ?: "Local peer identity is invalid."
            )
        }

        val frame = message.toMessageFrame().copy(
            recipientId = targetPeerId
        )
        val encodedFrameBytes = MessageFrameCodec.encode(frame).toByteArray(UTF_8)
        val sendPlan = try {
            OutgoingBleTransportSendPlanBuilder.build(
                messageId = frame.id,
                targetPeerId = targetPeerId,
                encryptedEnvelopeBytes = encodedFrameBytes,
                sourceCreatedAtMillis = frame.createdAtMillis
            )
        } catch (error: IllegalArgumentException) {
            return PeerIdentityExchangeSendResult.Failed(
                reason = error.message ?: "Peer identity exchange transport plan could not be built."
            )
        }

        val transportResult = suspendCoroutine { continuation ->
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

        return when (transportResult) {
            BleTransportSendResult.QueuedLocally -> PeerIdentityExchangeSendResult.SubmittedLocally
            BleTransportSendResult.NotAvailable -> PeerIdentityExchangeSendResult.SenderUnavailable
            is BleTransportSendResult.Failed -> PeerIdentityExchangeSendResult.Failed(
                reason = transportResult.reason
            )
        }
    }
}
