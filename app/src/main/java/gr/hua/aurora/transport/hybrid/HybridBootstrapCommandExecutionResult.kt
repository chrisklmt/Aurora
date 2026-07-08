package gr.hua.aurora.transport.hybrid

sealed interface HybridBootstrapCommandExecutionResult {
    data class Accepted(
        val peerId: String,
        val sessionId: String,
        val bootstrapIdentifier: String,
        val groupOwnerAddress: String,
        val socketPort: Int,
        val commandCreatedAtMillis: Long
    ) : HybridBootstrapCommandExecutionResult

    data class Rejected(
        val reason: String
    ) : HybridBootstrapCommandExecutionResult
}
