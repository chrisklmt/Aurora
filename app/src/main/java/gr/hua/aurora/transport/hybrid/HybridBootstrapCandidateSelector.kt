package gr.hua.aurora.transport.hybrid

object HybridBootstrapCandidateSelector {
    fun select(
        candidates: List<HybridBootstrapCandidate>
    ): HybridBootstrapCandidateSelection {
        if (candidates.isEmpty()) {
            return HybridBootstrapCandidateSelection.NoCandidates
        }

        val selectedCandidate = candidates
            .asSequence()
            .filter(HybridBootstrapCandidate::socketReady)
            .sortedWith(selectionPriorityComparator)
            .firstOrNull()
            ?: return HybridBootstrapCandidateSelection.NoSocketReadyCandidates

        return HybridBootstrapCandidateSelection.Selected(
            candidate = selectedCandidate
        )
    }

    private val selectionPriorityComparator =
        compareByDescending<HybridBootstrapCandidate> { it.latestCreatedAtMillis }
            .thenBy { it.peerId }
            .thenBy { it.sessionId }
}
