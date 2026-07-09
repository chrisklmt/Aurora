package gr.hua.aurora.transport.hybrid

data class HybridBootstrapCommandExecutorConfig(
    val mode: HybridBootstrapCommandExecutorMode = HybridBootstrapCommandExecutorMode.NO_OP,
    val noOpRejectionReason: String = "Hybrid bootstrap execution is disabled."
)
