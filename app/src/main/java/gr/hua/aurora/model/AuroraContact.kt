package gr.hua.aurora.model

data class AuroraContact(
    val canonicalPeerId: String,
    val displayName: String,
    val createdAtMillis: Long,
    val lastSeenMillis: Long? = null,
    val hasSession: Boolean = false
) {
    init {
        require(canonicalPeerId.isNotBlank()) {
            "Aurora contact canonicalPeerId must not be blank."
        }
        require(displayName.isNotBlank()) {
            "Aurora contact displayName must not be blank."
        }
        require(createdAtMillis >= 0L) {
            "Aurora contact createdAtMillis must be non-negative."
        }
    }
}
