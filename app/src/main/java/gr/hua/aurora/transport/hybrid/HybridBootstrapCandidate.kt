package gr.hua.aurora.transport.hybrid

data class HybridBootstrapCandidate(
    val peerId: String,
    val sessionId: String,
    val bootstrapIdentifier: String,
    val publicPeerIdHint: String?,
    val groupOwnerAddress: String?,
    val socketPort: Int?,
    val latestCreatedAtMillis: Long,
    val hasOffer: Boolean,
    val hasAccept: Boolean,
    val hasSocketHint: Boolean,
    val socketReady: Boolean
) {
    init {
        require(peerId.isNotBlank()) {
            "Hybrid bootstrap candidate peerId must not be blank."
        }
        require(sessionId.isNotBlank()) {
            "Hybrid bootstrap candidate sessionId must not be blank."
        }
        require(bootstrapIdentifier.isNotBlank()) {
            "Hybrid bootstrap candidate bootstrapIdentifier must not be blank."
        }
        require(latestCreatedAtMillis >= 0L) {
            "Hybrid bootstrap candidate latestCreatedAtMillis must be non-negative."
        }
    }
}
