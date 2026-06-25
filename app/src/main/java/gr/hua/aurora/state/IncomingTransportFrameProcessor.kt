package gr.hua.aurora.state

import gr.hua.aurora.ble.transport.BleGattTransportFrame
import gr.hua.aurora.protocol.IncomingSessionMaterialProvider
import gr.hua.aurora.protocol.IncomingMessageReceiveUseCase
import gr.hua.aurora.protocol.IncomingTransportMessage
import gr.hua.aurora.protocol.IncomingTransportReceiveResult
import gr.hua.aurora.protocol.MessageFrameType
import gr.hua.aurora.protocol.PeerIdentityExchangeHandlingResult

object IncomingTransportFrameProcessor {
    fun process(
        frames: Collection<BleGattTransportFrame>,
        sessionMaterialProvider: IncomingSessionMaterialProvider,
        stateHolder: AuroraStateHolder,
        handleIdentity: ((IncomingTransportMessage) -> PeerIdentityExchangeHandlingResult)? = null,
        identityHandlingUnavailableReason: String =
            "Local agreement identity material unavailable for incoming identity exchange.",
        receive: (
            Collection<BleGattTransportFrame>,
            IncomingSessionMaterialProvider
        ) -> IncomingTransportReceiveResult = IncomingMessageReceiveUseCase::receive
    ): IncomingTransportFrameProcessingResult {
        return process(
            frames = frames,
            sessionMaterialProvider = sessionMaterialProvider,
            ingest = stateHolder::ingestIncomingTransportMessage,
            handleIdentity = handleIdentity,
            identityHandlingUnavailableReason = identityHandlingUnavailableReason,
            receive = receive
        )
    }

    fun process(
        frames: Collection<BleGattTransportFrame>,
        sessionMaterialProvider: IncomingSessionMaterialProvider,
        ingest: (IncomingTransportMessage) -> IncomingMessageIngestionResult,
        handleIdentity: ((IncomingTransportMessage) -> PeerIdentityExchangeHandlingResult)? = null,
        identityHandlingUnavailableReason: String =
            "Local agreement identity material unavailable for incoming identity exchange.",
        receive: (
            Collection<BleGattTransportFrame>,
            IncomingSessionMaterialProvider
        ) -> IncomingTransportReceiveResult = IncomingMessageReceiveUseCase::receive
    ): IncomingTransportFrameProcessingResult {
        return when (
            val receiveResult = receive(
                frames,
                sessionMaterialProvider
            )
        ) {
            is IncomingTransportReceiveResult.Received -> {
                if (receiveResult.message.frame.type == MessageFrameType.IDENTITY_EXCHANGE) {
                    val identityHandler = handleIdentity
                    if (identityHandler == null) {
                        IncomingTransportFrameProcessingResult.IdentityHandlingUnavailable(
                            message = receiveResult.message,
                            reason = identityHandlingUnavailableReason
                        )
                    } else {
                        IncomingTransportFrameProcessingResult.IdentityHandled(
                            message = receiveResult.message,
                            handlingResult = identityHandler(receiveResult.message)
                        )
                    }
                } else {
                    IncomingTransportFrameProcessingResult.Received(
                        message = receiveResult.message,
                        ingestionResult = ingest(receiveResult.message)
                    )
                }
            }
            is IncomingTransportReceiveResult.IncompleteChunks,
            is IncomingTransportReceiveResult.InvalidEnvelope,
            is IncomingTransportReceiveResult.SessionMaterialUnavailable,
            is IncomingTransportReceiveResult.UnsupportedSender,
            is IncomingTransportReceiveResult.InvalidSenderIdentity,
            is IncomingTransportReceiveResult.DecryptFailed,
            is IncomingTransportReceiveResult.InvalidFrame -> {
                IncomingTransportFrameProcessingResult.ReceiveFailed(
                    receiveResult = receiveResult
                )
            }
        }
    }
}
