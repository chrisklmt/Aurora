package gr.hua.aurora.transport.processing

import gr.hua.aurora.protocol.EncryptedMessageEnvelope
import gr.hua.aurora.protocol.IncomingTransportMessage
import gr.hua.aurora.protocol.IncomingTransportReceiveResult
import gr.hua.aurora.protocol.PeerIdentityExchangeHandlingResult
import gr.hua.aurora.state.IncomingMessageIngestionResult

sealed interface IncomingTransportFrameProcessingSuccessResult :
    IncomingTransportFrameProcessingResult

sealed interface IncomingTransportFrameProcessingResult {
    data class Received(
        val message: IncomingTransportMessage,
        val ingestionResult: IncomingMessageIngestionResult
    ) : IncomingTransportFrameProcessingSuccessResult

    data class IdentityHandled(
        val message: IncomingTransportMessage,
        val handlingResult: PeerIdentityExchangeHandlingResult
    ) : IncomingTransportFrameProcessingSuccessResult

    data class IdentityHandlingUnavailable(
        val message: IncomingTransportMessage,
        val reason: String
    ) : IncomingTransportFrameProcessingSuccessResult {
        init {
            require(reason.isNotBlank()) {
                "Incoming identity handling unavailable reason must not be blank."
            }
        }
    }

    data class RelayOnlyEncrypted(
        val envelope: EncryptedMessageEnvelope
    ) : IncomingTransportFrameProcessingSuccessResult

    data class ReceiveFailed(
        val receiveResult: IncomingTransportReceiveResult
    ) : IncomingTransportFrameProcessingResult
}
