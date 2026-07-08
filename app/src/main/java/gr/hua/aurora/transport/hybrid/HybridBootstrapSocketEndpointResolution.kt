package gr.hua.aurora.transport.hybrid

sealed interface HybridBootstrapSocketEndpointResolution {
    data class Resolved(
        val endpoint: HybridBootstrapSocketEndpoint
    ) : HybridBootstrapSocketEndpointResolution

    data object NoCandidates : HybridBootstrapSocketEndpointResolution

    data object NoSocketReadyCandidate : HybridBootstrapSocketEndpointResolution

    data class InvalidSelectedCandidate(
        val reason: String
    ) : HybridBootstrapSocketEndpointResolution
}
