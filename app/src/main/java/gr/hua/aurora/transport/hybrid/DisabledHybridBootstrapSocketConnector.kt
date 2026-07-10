package gr.hua.aurora.transport.hybrid

class DisabledHybridBootstrapSocketConnector(
    private val failureReason: String = "Hybrid bootstrap socket connector is disabled."
) : HybridBootstrapSocketConnector {
    override fun connect(
        plan: HybridBootstrapSocketExecutionPlan
    ): HybridBootstrapSocketConnectionResult {
        return HybridBootstrapSocketConnectionResult.Failed(
            reason = failureReason
        )
    }
}
