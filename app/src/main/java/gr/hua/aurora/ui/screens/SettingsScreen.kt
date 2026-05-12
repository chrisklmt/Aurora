package gr.hua.aurora.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    currentUsername: String,
    onUsernameChange: (String) -> Unit,
    onBack: () -> Unit
) {
    var draftUsername by rememberSaveable(currentUsername) { mutableStateOf(currentUsername) }

    PlaceholderScreenScaffold(
        title = "Settings",
        subtitle = "UI placeholder"
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "This screen lets you edit local profile state without persistence yet.",
                style = MaterialTheme.typography.bodyLarge
            )
            // Η αλλαγή username μένει μόνο στη μνήμη του app σε αυτό το στάδιο και δεν γράφεται σε αποθήκευση.
            Text(
                text = "Local profile",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Updates apply only to local in-memory state for now.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = draftUsername,
                onValueChange = { draftUsername = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Username") },
                singleLine = true
            )
            Button(
                onClick = { onUsernameChange(draftUsername) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Apply Username")
            }
            Text(
                text = "Current username: $currentUsername",
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { draftUsername = currentUsername },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reset Draft")
            }
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Back")
            }
        }
    }
}
