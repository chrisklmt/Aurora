package gr.hua.aurora.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gr.hua.aurora.model.ContactPreview
import gr.hua.aurora.ui.components.AuroraTopBarAction

@Composable
fun ContactsScreen(
    contacts: List<ContactPreview>,
    currentUsername: String,
    onResetLocalData: () -> Unit,
    onBack: () -> Unit
) {
    PlaceholderScreenScaffold(
        title = "Contacts",
        subtitle = "UI placeholder",
        username = currentUsername,
        onUsernameTripleTap = onResetLocalData,
        rightAction = AuroraTopBarAction.BACK,
        onRightActionClick = onBack
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Η λίστα επαφών διαβάζει μόνο τοπική μνήμη ώστε να μείνει συνεκτικό το UI χωρίς discovery ή αποθήκευση.
            Text(
                text = "Sample contacts",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "This screen is visual-only and does not load saved or nearby contacts yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(contacts) { contact ->
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = contact.displayName,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = contact.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
