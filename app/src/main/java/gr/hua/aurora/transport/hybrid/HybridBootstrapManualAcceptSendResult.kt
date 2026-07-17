package gr.hua.aurora.transport.hybrid

sealed interface HybridBootstrapManualAcceptSendResult {
    data class Sent(
        val peerId: String,
        val sessionId: String
    ) : HybridBootstrapManualAcceptSendResult {
        init {
            require(peerId.isNotBlank()) {
                "Hybrid bootstrap manual accept sent peerId must not be blank."
            }
            require(sessionId.isNotBlank()) {
                "Hybrid bootstrap manual accept sent sessionId must not be blank."
            }
        }
    }

    data object NoOfferCandidate : HybridBootstrapManualAcceptSendResult

    data object NoActivePeer : HybridBootstrapManualAcceptSendResult

    data object NoActiveSession : HybridBootstrapManualAcceptSendResult

    data object WriterUnavailable : HybridBootstrapManualAcceptSendResult

    data class InvalidAccept(
        val reason: String
    ) : HybridBootstrapManualAcceptSendResult {
        init {
            require(reason.isNotBlank()) {
                "Hybrid bootstrap manual accept invalid reason must not be blank."
            }
        }
    }

    data class SendFailed(
        val reason: String
    ) : HybridBootstrapManualAcceptSendResult {
        init {
            require(reason.isNotBlank()) {
                "Hybrid bootstrap manual accept send failure reason must not be blank."
            }
        }
    }
}
