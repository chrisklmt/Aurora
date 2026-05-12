package gr.hua.aurora.model

// Το ChatThread κρατά ελάχιστη περιγραφή συνομιλίας για λίστες και previews χωρίς business orchestration.
data class ChatThread(
    val id: String,
    val title: String,
    val peerId: String? = null,
    val lastMessagePreview: String? = null,
    val updatedAtMillis: Long = 0L,
    val unreadCount: Int = 0
)
