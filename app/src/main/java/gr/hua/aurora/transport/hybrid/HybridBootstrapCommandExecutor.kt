package gr.hua.aurora.transport.hybrid

interface HybridBootstrapCommandExecutor {
    fun execute(
        command: HybridBootstrapAttemptCommand
    ): HybridBootstrapCommandExecutionResult
}
