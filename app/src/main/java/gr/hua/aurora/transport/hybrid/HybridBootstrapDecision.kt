package gr.hua.aurora.transport.hybrid

class HybridBootstrapDecision private constructor(
    private val storedCandidates: List<HybridBootstrapCandidate>,
    val selection: HybridBootstrapCandidateSelection
) {
    val candidates: List<HybridBootstrapCandidate>
        get() = storedCandidates.toList()

    companion object {
        fun create(
            candidates: List<HybridBootstrapCandidate>,
            selection: HybridBootstrapCandidateSelection
        ): HybridBootstrapDecision {
            return HybridBootstrapDecision(
                storedCandidates = candidates.toList(),
                selection = selection
            )
        }
    }
}
