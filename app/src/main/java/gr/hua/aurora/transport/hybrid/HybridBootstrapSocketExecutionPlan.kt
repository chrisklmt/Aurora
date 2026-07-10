package gr.hua.aurora.transport.hybrid

data class HybridBootstrapSocketExecutionPlan(
    val peerId: String,
    val sessionId: String,
    val bootstrapIdentifier: String,
    val groupOwnerAddress: String,
    val socketPort: Int,
    val latestCreatedAtMillis: Long,
    val requestedAtMillis: Long,
    val commandCreatedAtMillis: Long,
    val connectTimeoutMillis: Long
)
