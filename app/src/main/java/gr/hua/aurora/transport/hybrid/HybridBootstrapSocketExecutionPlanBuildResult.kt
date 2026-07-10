package gr.hua.aurora.transport.hybrid

sealed interface HybridBootstrapSocketExecutionPlanBuildResult {
    data class Built(
        val plan: HybridBootstrapSocketExecutionPlan
    ) : HybridBootstrapSocketExecutionPlanBuildResult

    data class InvalidCommand(
        val reason: String
    ) : HybridBootstrapSocketExecutionPlanBuildResult

    data class InvalidTimeout(
        val reason: String
    ) : HybridBootstrapSocketExecutionPlanBuildResult
}
