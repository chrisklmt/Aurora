package gr.hua.aurora.transport.hybrid

object HybridBootstrapDecisionEngine {
    fun decide(
        state: HybridTransportControlState
    ): HybridBootstrapDecision {
        val plannedCandidates = HybridBootstrapCandidatePlanner.plan(state)
        val selection = HybridBootstrapCandidateSelector.select(plannedCandidates)
        return HybridBootstrapDecision.create(
            candidates = plannedCandidates,
            selection = selection
        )
    }
}
