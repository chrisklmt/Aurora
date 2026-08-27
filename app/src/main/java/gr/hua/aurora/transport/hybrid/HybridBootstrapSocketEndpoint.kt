package gr.hua.aurora.transport.hybrid

data class HybridBootstrapSocketEndpoint(
    val peerId: String,
    val sessionId: String,
    val bootstrapIdentifier: String,
    val groupOwnerAddress: String,
    val socketPort: Int,
    val latestCreatedAtMillis: Long,
    val localSocketHintObservedAtMonotonicMillis: Long? = null
) {
    init {
        require(peerId.isNotBlank()) {
            "Hybrid bootstrap socket endpoint peerId must not be blank."
        }
        require(sessionId.isNotBlank()) {
            "Hybrid bootstrap socket endpoint sessionId must not be blank."
        }
        require(bootstrapIdentifier.isNotBlank()) {
            "Hybrid bootstrap socket endpoint bootstrapIdentifier must not be blank."
        }
        require(groupOwnerAddress.isNotBlank()) {
            "Hybrid bootstrap socket endpoint groupOwnerAddress must not be blank."
        }
        require(socketPort in 1..65535) {
            "Hybrid bootstrap socket endpoint socketPort must be in 1..65535."
        }
        require(latestCreatedAtMillis >= 0L) {
            "Hybrid bootstrap socket endpoint latestCreatedAtMillis must be non-negative."
        }
        require(localSocketHintObservedAtMonotonicMillis == null || localSocketHintObservedAtMonotonicMillis >= 0L) {
            "Hybrid bootstrap socket endpoint localSocketHintObservedAtMonotonicMillis must be non-negative when present."
        }
    }
}
