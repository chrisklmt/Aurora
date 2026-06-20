package gr.hua.aurora.protocol

import gr.hua.aurora.model.OutgoingChatMessage

data class OutgoingMessageFrameDraft(
    val id: String,
    val threadId: String,
    val type: MessageFrameType,
    val recipientId: String? = null,
    val createdAtMillis: Long,
    val payload: String
)

object OutgoingMessageFrameBuilder {
    private const val globalThreadId = "global"
    private const val privateThreadPrefix = "private:"

    fun build(message: OutgoingChatMessage): OutgoingMessageFrameDraft {
        require(message.messageId.isNotBlank()) { "Queued message id must not be blank." }
        require(message.threadId.isNotBlank()) { "Queued message threadId must not be blank." }
        require(message.createdAtMillis >= 0L) { "Queued message createdAtMillis must be non-negative." }

        val (type, recipientId) = scopeFor(message.threadId)

        return OutgoingMessageFrameDraft(
            id = message.messageId,
            threadId = message.threadId,
            type = type,
            recipientId = recipientId,
            createdAtMillis = message.createdAtMillis,
            payload = message.userText
        )
    }

    private fun scopeFor(threadId: String): Pair<MessageFrameType, String?> {
        return when {
            threadId == globalThreadId -> MessageFrameType.GLOBAL_TEXT to null
            threadId.startsWith(privateThreadPrefix) -> {
                val recipientId = threadId.removePrefix(privateThreadPrefix)
                require(recipientId.isNotBlank()) {
                    "Queued private threadId must include a recipient."
                }
                MessageFrameType.PRIVATE_TEXT to recipientId
            }

            else -> throw IllegalArgumentException("Unsupported queued threadId: $threadId.")
        }
    }
}
