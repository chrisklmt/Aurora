package gr.hua.aurora.transport.hybrid

sealed interface HybridBootstrapSocketDialResult {
    data class Connected(
        val address: String,
        val port: Int,
        val connectedAtMillis: Long
    ) : HybridBootstrapSocketDialResult

    data class Failed(
        val reason: String
    ) : HybridBootstrapSocketDialResult
}
