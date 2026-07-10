package gr.hua.aurora.transport.hybrid

object HybridBootstrapCommandExecutorFactory {
    fun create(
        config: HybridBootstrapCommandExecutorConfig = HybridBootstrapCommandExecutorConfig()
    ): HybridBootstrapCommandExecutor {
        return when (config.mode) {
            HybridBootstrapCommandExecutorMode.NO_OP -> noOp(
                rejectionReason = config.noOpRejectionReason
            )
            HybridBootstrapCommandExecutorMode.SOCKET_PLAN_DISABLED -> {
                HybridBootstrapSocketPlanCommandExecutor(
                    connector = HybridBootstrapSocketConnectorFactory.disabled(
                        failureReason = config.disabledSocketConnectorFailureReason
                    )
                )
            }
        }
    }

    fun noOp(
        rejectionReason: String = "Hybrid bootstrap execution is disabled."
    ): HybridBootstrapCommandExecutor {
        return NoOpHybridBootstrapCommandExecutor(
            rejectionReason = rejectionReason
        )
    }

    fun defaultRuntimeExecutor(): HybridBootstrapCommandExecutor {
        return create(HybridBootstrapCommandExecutorConfig())
    }
}
