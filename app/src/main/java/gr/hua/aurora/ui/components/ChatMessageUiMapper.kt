package gr.hua.aurora.ui.components

import gr.hua.aurora.model.ChatMessage
import gr.hua.aurora.model.MessageStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Ο mapper ανήκει στο UI layer γιατί μετατρέπει app-level models σε έτοιμα στοιχεία παρουσίασης.
fun ChatMessage.toMessageListItem(): MessageListItem {
    return MessageListItem(
        sender = senderName,
        text = text,
        timestampLabel = createdAtMillis.toPreviewTimeLabel(),
        supportingLabel = status.toPreviewStatusLabel()
    )
}

private fun Long.toPreviewTimeLabel(): String {
    return SimpleDateFormat("HH:mm", Locale.US).format(Date(this))
}

private fun MessageStatus.toPreviewStatusLabel(): String {
    return when (this) {
        MessageStatus.DRAFT -> "Draft"
        MessageStatus.QUEUED -> "Queued"
        MessageStatus.SENT -> "Sent"
        MessageStatus.DELIVERED -> "Delivered"
        MessageStatus.FAILED -> "Failed"
    }
}
