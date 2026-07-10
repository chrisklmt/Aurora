package gr.hua.aurora.transport.hybrid

fun interface HybridBootstrapSocketConnector {
    fun connect(
        plan: HybridBootstrapSocketExecutionPlan
    ): HybridBootstrapSocketConnectionResult
}
