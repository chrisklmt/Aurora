package gr.hua.aurora.protocol

// Το frame είναι envelope σε επίπεδο εφαρμογής και δεν προσφέρει από μόνο του κρυπτογράφηση ή εγγυήσεις παράδοσης.
data class MessageFrame(
    val id: String,
    val type: MessageFrameType,
    val senderId: String,
    val recipientId: String? = null,
    val createdAtMillis: Long,
    // Το TTL μένει μόνο ως μελλοντικό πεδίο δρομολόγησης σε αυτό το στάδιο.
    val ttl: Int = 1,
    val payload: String
)
