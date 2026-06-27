package gr.hua.aurora.protocol

class SeenMessageIdCache(
    private val maxSize: Int = DEFAULT_MAX_SIZE
) {
    private val seenIds = LinkedHashSet<String>()

    init {
        require(maxSize > 0) {
            "Seen message id cache maxSize must be positive."
        }
    }

    val size: Int
        get() = seenIds.size

    fun contains(
        messageId: String
    ): Boolean {
        return seenIds.contains(sanitize(messageId))
    }

    fun markSeen(
        messageId: String
    ): Boolean {
        val sanitizedMessageId = sanitize(messageId)
        val wasAdded = seenIds.add(sanitizedMessageId)
        if (wasAdded && seenIds.size > maxSize) {
            val iterator = seenIds.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        return wasAdded
    }

    fun clear() {
        seenIds.clear()
    }

    private fun sanitize(
        messageId: String
    ): String {
        return messageId.trim().also { sanitizedMessageId ->
            require(sanitizedMessageId.isNotEmpty()) {
                "Seen message id must not be blank."
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_SIZE: Int = 10_000
    }
}
