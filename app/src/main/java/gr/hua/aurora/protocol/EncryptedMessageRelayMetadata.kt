package gr.hua.aurora.protocol

data class EncryptedMessageRelayMetadata(
    val messageId: String,
    val messageType: MessageFrameType,
    val ttl: Int
) {
    init {
        require(messageId.isNotBlank()) {
            "Encrypted relay metadata messageId must not be blank."
        }
        require(ttl >= 1) {
            "Encrypted relay metadata ttl must be at least 1."
        }
    }

    fun decrementTtlOrNull(): EncryptedMessageRelayMetadata? {
        if (ttl <= 1) {
            return null
        }
        return copy(ttl = ttl - 1)
    }
}
