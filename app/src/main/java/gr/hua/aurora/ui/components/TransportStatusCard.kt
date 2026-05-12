package gr.hua.aurora.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class TransportStatusTone {
    NEUTRAL,
    HEALTHY,
    WARNING
}

@Composable
fun TransportStatusCard(
    summary: String,
    detail: String,
    modifier: Modifier = Modifier,
    title: String = "Transport Status",
    tone: TransportStatusTone = TransportStatusTone.NEUTRAL,
    note: String? = null,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    // Το card αποτυπώνει μόνο οπτική κατάσταση που δίνεται απ' έξω και δεν συνδέεται με πραγματικό nearby state.
    val headlineColor = when (tone) {
        TransportStatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
        TransportStatusTone.HEALTHY -> MaterialTheme.colorScheme.primary
        TransportStatusTone.WARNING -> MaterialTheme.colorScheme.error
    }

    OutlinedCard(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = headlineColor
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.labelLarge,
                color = headlineColor
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium
            )
            if (!note.isNullOrBlank()) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!actionLabel.isNullOrBlank() && onActionClick != null) {
                Button(onClick = onActionClick) {
                    Text(actionLabel)
                }
            }
        }
    }
}
