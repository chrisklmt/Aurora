package gr.hua.aurora.transport.hybrid

class HybridBootstrapDecisionProvider(
    private val store: HybridTransportControlStore
) {
    fun currentDecision(): HybridBootstrapDecision {
        return HybridBootstrapDecisionEngine.decide(store.snapshot())
    }
}
