package gr.hua.aurora.transport.hybrid

class NoOpHybridBootstrapCommandExecutor(
    private val rejectionReason: String = "Hybrid bootstrap execution is disabled."
) : HybridBootstrapCommandExecutor {
    override fun execute(
        command: HybridBootstrapAttemptCommand
    ): HybridBootstrapCommandExecutionResult {
        return HybridBootstrapCommandExecutionResult.Rejected(
            reason = rejectionReason
        )
    }
}
