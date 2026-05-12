package gr.hua.aurora.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gr.hua.aurora.ui.components.AuroraTopBar
import gr.hua.aurora.ui.components.AuroraTopBarAction

private data class ContactPreviewItem(
    val name: String,
    val detail: String
)

private val sampleContacts = listOf(
    ContactPreviewItem(
        name = "Alex",
        detail = "Preview contact for upcoming conversation flow."
    ),
    ContactPreviewItem(
        name = "Maria",
        detail = "Static UI entry without nearby or saved-contact logic."
    ),
    ContactPreviewItem(
        name = "Nikos",
        detail = "Placeholder item for the contacts list layout."
    )
)

@Composable
fun ContactsScreen(
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            AuroraTopBar(
                title = "Contacts",
                subtitle = "UI placeholder",
                rightAction = AuroraTopBarAction.BACK,
                onRightActionClick = onBack
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Τα contacts εδώ είναι στατικά ώστε να κλειδώσει πρώτα το navigation και το βασικό list UI.
            Text(
                text = "Sample contacts",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "This screen is visual-only and does not load real contacts yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(sampleContacts) { contact ->
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = contact.name,
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
