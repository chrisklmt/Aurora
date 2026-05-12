package gr.hua.aurora.model

// Το ChatMessage περιγράφει app/UI-level κατάσταση μηνύματος χωρίς να αποδεικνύει παράδοση, κρυπτογράφηση ή σύνδεση.
data class ChatMessage(
    val id: String,
    val threadId: String,
    val senderId: String,
    val senderName: String,
    val text: String,
    val createdAtMillis: Long,
    val status: MessageStatus,
    val isOutgoing: Boolean
)

// Το status δείχνει μόνο την τοπική πορεία που βλέπει ο χρήστης και όχι εγγυημένη αποστολή.
