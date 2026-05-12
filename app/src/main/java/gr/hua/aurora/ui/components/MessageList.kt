package gr.hua.aurora.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class MessageListItem(
    val sender: String,
    val text: String,
    val timestampLabel: String? = null,
    val supportingLabel: String? = null
)

@Composable
fun MessageList(
    messages: List<MessageListItem>,
    modifier: Modifier = Modifier,
    localUsername: String? = null
) {
    // Σε αυτό το βήμα το list δουλεύει με απλό UI data class ώστε να μείνει ανεξάρτητο από domain models.
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(messages) { message ->
            MessageBubble(
                sender = message.sender,
                text = message.text,
                isOwnMessage = localUsername != null && message.sender == localUsername,
                timestampLabel = message.timestampLabel,
                supportingLabel = message.supportingLabel
            )
        }
    }
}
