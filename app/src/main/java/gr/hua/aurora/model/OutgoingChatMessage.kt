package gr.hua.aurora.model

data class OutgoingChatMessage(
    val messageId: String,
    val threadId: String,
    val userText: String,
    val createdAtMillis: Long,
    val status: MessageStatus
)
