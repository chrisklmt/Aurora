package gr.hua.aurora.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun CompactDiagnosticsCard(
    summaryText: String,
    onOpenAutomatedDiagnostics: () -> Unit,
    rawDiagnosticsExpanded: Boolean,
    onToggleRawDiagnostics: (() -> Unit)?,
    supportingText: String? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Diagnostics",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = summaryText,
                style = MaterialTheme.typography.bodySmall
            )
            supportingText?.let { details ->
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(
                    onClick = onOpenAutomatedDiagnostics
                ) {
                    Text("Automated diagnostics")
                }
                if (onToggleRawDiagnostics != null) {
                    TextButton(
                        onClick = onToggleRawDiagnostics
                    ) {
                        Text(
                            if (rawDiagnosticsExpanded) {
                                "Hide raw diagnostics"
                            } else {
                                "Advanced raw diagnostics"
                            }
                        )
                    }
                }
            }
        }
    }
}
