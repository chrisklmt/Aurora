package gr.hua.aurora.transport.hybrid

sealed interface HybridBootstrapManualSocketHintSendResult {
    data class Sent(
        val peerId: String,
        val sessionId: String,
        val groupOwnerAddress: String,
        val socketPort: Int
    ) : HybridBootstrapManualSocketHintSendResult

    data object NoActivePeer : HybridBootstrapManualSocketHintSendResult

    data object NoActiveSession : HybridBootstrapManualSocketHintSendResult

    data object NoAcceptedCandidate : HybridBootstrapManualSocketHintSendResult

    data object NoSocketEndpoint : HybridBootstrapManualSocketHintSendResult

    data object NotGroupOwner : HybridBootstrapManualSocketHintSendResult

    data object WriterUnavailable : HybridBootstrapManualSocketHintSendResult

    data class InvalidSocketHint(
        val reason: String
    ) : HybridBootstrapManualSocketHintSendResult

    data class SendFailed(
        val reason: String
    ) : HybridBootstrapManualSocketHintSendResult
}
