package gr.hua.aurora.transport.hybrid

data class HybridBootstrapAttemptCommand(
    val peerId: String,
    val sessionId: String,
    val bootstrapIdentifier: String,
    val groupOwnerAddress: String,
    val socketPort: Int,
    val latestCreatedAtMillis: Long,
    val requestedAtMillis: Long,
    val commandCreatedAtMillis: Long
) {
    init {
        require(peerId.isNotBlank()) {
            "Hybrid bootstrap attempt command peerId must not be blank."
        }
        require(sessionId.isNotBlank()) {
            "Hybrid bootstrap attempt command sessionId must not be blank."
        }
        require(bootstrapIdentifier.isNotBlank()) {
            "Hybrid bootstrap attempt command bootstrapIdentifier must not be blank."
        }
        require(groupOwnerAddress.isNotBlank()) {
            "Hybrid bootstrap attempt command groupOwnerAddress must not be blank."
        }
        require(socketPort in 1..65535) {
            "Hybrid bootstrap attempt command socketPort must be in 1..65535."
        }
        require(latestCreatedAtMillis >= 0L) {
            "Hybrid bootstrap attempt command latestCreatedAtMillis must be non-negative."
        }
        require(requestedAtMillis >= 0L) {
            "Hybrid bootstrap attempt command requestedAtMillis must be non-negative."
        }
        require(commandCreatedAtMillis >= 0L) {
            "Hybrid bootstrap attempt command commandCreatedAtMillis must be non-negative."
        }
        require(requestedAtMillis >= latestCreatedAtMillis) {
            "Hybrid bootstrap attempt command requestedAtMillis must be greater than or equal to latestCreatedAtMillis."
        }
        require(commandCreatedAtMillis >= requestedAtMillis) {
            "Hybrid bootstrap attempt command commandCreatedAtMillis must be greater than or equal to requestedAtMillis."
        }
    }
}
