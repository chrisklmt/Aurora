package gr.hua.aurora.transport.hybrid

object HybridBootstrapCommandExecutorFactory {
    fun noOp(
        rejectionReason: String = "Hybrid bootstrap execution is disabled."
    ): HybridBootstrapCommandExecutor {
        return NoOpHybridBootstrapCommandExecutor(
            rejectionReason = rejectionReason
        )
    }

    fun defaultRuntimeExecutor(): HybridBootstrapCommandExecutor {
        return noOp()
    }
}
