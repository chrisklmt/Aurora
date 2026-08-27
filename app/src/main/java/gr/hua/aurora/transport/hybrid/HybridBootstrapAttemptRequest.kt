package gr.hua.aurora.transport.hybrid

data class HybridBootstrapAttemptRequest(
    val peerId: String,
    val sessionId: String,
    val bootstrapIdentifier: String,
    val groupOwnerAddress: String,
    val socketPort: Int,
    val latestCreatedAtMillis: Long,
    val requestedAtMillis: Long
) {
    init {
        require(peerId.isNotBlank()) {
            "Hybrid bootstrap attempt request peerId must not be blank."
        }
        require(sessionId.isNotBlank()) {
            "Hybrid bootstrap attempt request sessionId must not be blank."
        }
        require(bootstrapIdentifier.isNotBlank()) {
            "Hybrid bootstrap attempt request bootstrapIdentifier must not be blank."
        }
        require(groupOwnerAddress.isNotBlank()) {
            "Hybrid bootstrap attempt request groupOwnerAddress must not be blank."
        }
        require(socketPort in 1..65535) {
            "Hybrid bootstrap attempt request socketPort must be in 1..65535."
        }
        require(latestCreatedAtMillis >= 0L) {
            "Hybrid bootstrap attempt request latestCreatedAtMillis must be non-negative."
        }
        require(requestedAtMillis >= 0L) {
            "Hybrid bootstrap attempt request requestedAtMillis must be non-negative."
        }
    }
}
