package gr.hua.aurora.ui.components

import gr.hua.aurora.model.ChatMessage
import gr.hua.aurora.model.MessageStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Ο mapper ανήκει στο UI layer γιατί μετατρέπει app-level models σε έτοιμα στοιχεία παρουσίασης.
fun ChatMessage.toMessageListItem(
    showRetryAction: Boolean = false
): MessageListItem {
    val retryActionMessageId = if (
        showRetryAction &&
        isOutgoing &&
        status == MessageStatus.FAILED
    ) {
        id
    } else {
        null
    }

    return MessageListItem(
        sender = senderName,
        text = text,
        isOutgoing = isOutgoing,
        timestampLabel = createdAtMillis.toPreviewTimeLabel(),
        supportingLabel = status.toPreviewStatusLabel(isOutgoing = isOutgoing),
        actionLabel = if (retryActionMessageId != null) "Retry" else null,
        actionMessageId = retryActionMessageId
    )
}

private fun Long.toPreviewTimeLabel(): String {
    return SimpleDateFormat("HH:mm", Locale.US).format(Date(this))
}

private fun MessageStatus.toPreviewStatusLabel(
    isOutgoing: Boolean
): String? {
    if (!isOutgoing) {
        return null
    }

    return when (this) {
        MessageStatus.DRAFT -> "Draft"
        MessageStatus.RECEIVED -> null
        MessageStatus.QUEUED,
        MessageStatus.LOCAL_ONLY -> "Pending"
        MessageStatus.SENT -> "Sent"
        MessageStatus.DELIVERED -> "Sent"
        MessageStatus.FAILED -> "Failed"
    }
}
