package gr.hua.aurora.transport.hybrid

sealed interface HybridBootstrapManualOfferSendResult {
    data class Sent(
        val peerId: String,
        val sessionId: String
    ) : HybridBootstrapManualOfferSendResult {
        init {
            require(peerId.isNotBlank()) {
                "Hybrid bootstrap manual offer sent peerId must not be blank."
            }
            require(sessionId.isNotBlank()) {
                "Hybrid bootstrap manual offer sent sessionId must not be blank."
            }
        }
    }

    data object NoActivePeer : HybridBootstrapManualOfferSendResult

    data object NoActiveSession : HybridBootstrapManualOfferSendResult

    data object WriterUnavailable : HybridBootstrapManualOfferSendResult

    data class InvalidOffer(
        val reason: String
    ) : HybridBootstrapManualOfferSendResult {
        init {
            require(reason.isNotBlank()) {
                "Hybrid bootstrap manual offer invalid reason must not be blank."
            }
        }
    }

    data class SendFailed(
        val reason: String
    ) : HybridBootstrapManualOfferSendResult {
        init {
            require(reason.isNotBlank()) {
                "Hybrid bootstrap manual offer send failure reason must not be blank."
            }
        }
    }
}
