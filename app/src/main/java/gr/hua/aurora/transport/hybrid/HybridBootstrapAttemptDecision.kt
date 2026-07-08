package gr.hua.aurora.transport.hybrid

sealed interface HybridBootstrapAttemptDecision {
    data class Allowed(
        val request: HybridBootstrapAttemptRequest
    ) : HybridBootstrapAttemptDecision

    data object NoCandidates : HybridBootstrapAttemptDecision

    data object NoSocketReadyCandidate : HybridBootstrapAttemptDecision

    data class InvalidEndpoint(
        val reason: String
    ) : HybridBootstrapAttemptDecision

    data class EndpointTooOld(
        val ageMillis: Long,
        val maxAgeMillis: Long
    ) : HybridBootstrapAttemptDecision
}
