package gr.hua.aurora.transport.hybrid

data class HybridBootstrapSocketHintObservation(
    val peerId: String,
    val sessionId: String,
    val groupOwnerAddress: String,
    val socketPort: Int,
    val createdAtMillis: Long,
    val observedAtMonotonicMillis: Long
) {
    init {
        require(peerId.isNotBlank()) {
            "Hybrid bootstrap socket hint observation peerId must not be blank."
        }
        require(sessionId.isNotBlank()) {
            "Hybrid bootstrap socket hint observation sessionId must not be blank."
        }
        require(groupOwnerAddress.isNotBlank()) {
            "Hybrid bootstrap socket hint observation groupOwnerAddress must not be blank."
        }
        require(socketPort in 1..65535) {
            "Hybrid bootstrap socket hint observation socketPort must be in 1..65535."
        }
        require(createdAtMillis >= 0L) {
            "Hybrid bootstrap socket hint observation createdAtMillis must be non-negative."
        }
        require(observedAtMonotonicMillis >= 0L) {
            "Hybrid bootstrap socket hint observation observedAtMonotonicMillis must be non-negative."
        }
    }
}
