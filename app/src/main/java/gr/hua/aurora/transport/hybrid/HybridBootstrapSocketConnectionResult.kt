package gr.hua.aurora.transport.hybrid

sealed interface HybridBootstrapSocketConnectionResult {
    data class Connected(
        val peerId: String,
        val sessionId: String,
        val bootstrapIdentifier: String,
        val groupOwnerAddress: String,
        val socketPort: Int,
        val connectedAtMillis: Long
    ) : HybridBootstrapSocketConnectionResult

    data class Failed(
        val reason: String
    ) : HybridBootstrapSocketConnectionResult
}
