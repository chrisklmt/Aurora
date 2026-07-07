package gr.hua.aurora.transport.hybrid

sealed interface HybridBootstrapCandidateSelection {
    data class Selected(
        val candidate: HybridBootstrapCandidate
    ) : HybridBootstrapCandidateSelection

    data object NoCandidates : HybridBootstrapCandidateSelection

    data object NoSocketReadyCandidates : HybridBootstrapCandidateSelection
}
