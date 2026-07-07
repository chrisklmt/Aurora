package gr.hua.aurora.transport.processing

import gr.hua.aurora.ble.transport.BleGattTransportFrame
import gr.hua.aurora.protocol.IncomingSessionMaterialProvider
import gr.hua.aurora.protocol.IncomingMessageReceiveUseCase
import gr.hua.aurora.protocol.IncomingTransportMessage
import gr.hua.aurora.protocol.IncomingTransportReceiveResult
import gr.hua.aurora.protocol.MessageFrameType
import gr.hua.aurora.protocol.PeerIdentityExchangeHandlingResult
import gr.hua.aurora.state.AuroraStateHolder
import gr.hua.aurora.state.IncomingMessageIngestionResult
import gr.hua.aurora.transport.hybrid.HybridTransportControlFrameFactory
import gr.hua.aurora.transport.hybrid.HybridTransportControlMessage
import gr.hua.aurora.transport.hybrid.HybridTransportControlStore

object IncomingTransportFrameProcessor {
    fun process(
        frames: Collection<BleGattTransportFrame>,
        sessionMaterialProvider: IncomingSessionMaterialProvider,
        stateHolder: AuroraStateHolder,
        hybridControlStore: HybridTransportControlStore? = null,
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
            hybridControlStore = hybridControlStore,
            handleIdentity = handleIdentity,
            identityHandlingUnavailableReason = identityHandlingUnavailableReason,
            receive = receive
        )
    }

    fun process(
        frames: Collection<BleGattTransportFrame>,
        sessionMaterialProvider: IncomingSessionMaterialProvider,
        ingest: (IncomingTransportMessage) -> IncomingMessageIngestionResult,
        hybridControlStore: HybridTransportControlStore? = null,
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
                } else if (receiveResult.message.frame.type == MessageFrameType.HYBRID_TRANSPORT_CONTROL) {
                    recordHybridTransportControl(
                        message = receiveResult.message,
                        hybridControlStore = hybridControlStore
                    )
                } else {
                    IncomingTransportFrameProcessingResult.Received(
                        message = receiveResult.message,
                        ingestionResult = ingest(receiveResult.message)
                    )
                }
            }
            is IncomingTransportReceiveResult.RelayOnlyEncrypted -> {
                IncomingTransportFrameProcessingResult.RelayOnlyEncrypted(
                    envelope = receiveResult.envelope
                )
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

    private fun recordHybridTransportControl(
        message: IncomingTransportMessage,
        hybridControlStore: HybridTransportControlStore?
    ): IncomingTransportFrameProcessingResult {
        val controlMessage = HybridTransportControlFrameFactory.parseOrNull(message.frame)
            ?: return IncomingTransportFrameProcessingResult.HybridControlIgnored(
                message = message,
                reason = "Incoming hybrid transport control payload is invalid."
            )
        val store = hybridControlStore
            ?: return IncomingTransportFrameProcessingResult.HybridControlIgnored(
                message = message,
                reason = "Hybrid transport control store is not configured."
            )
        val peerId = resolvedHybridControlPeerId(
            message = message,
            controlMessage = controlMessage
        )

        return IncomingTransportFrameProcessingResult.HybridControlHandled(
            message = message,
            peerId = peerId,
            controlMessage = controlMessage,
            storeResult = store.record(peerId, controlMessage)
        )
    }

    private fun resolvedHybridControlPeerId(
        message: IncomingTransportMessage,
        controlMessage: HybridTransportControlMessage
    ): String {
        val senderId = message.frame.senderId.trim()
        if (senderId.isNotEmpty()) {
            return senderId
        }

        return controlMessage.publicPeerIdHint?.trim().orEmpty()
    }
}
