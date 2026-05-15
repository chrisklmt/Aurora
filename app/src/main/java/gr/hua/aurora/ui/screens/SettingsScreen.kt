package gr.hua.aurora.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gr.hua.aurora.ui.components.AuroraTopBar
import gr.hua.aurora.ui.components.AuroraTopBarAction

@Composable
fun SettingsScreen(
    currentUsername: String,
    generatedUsername: String,
    useCustomUsernameInGlobalChat: Boolean,
    onUsernameChange: (String) -> Unit,
    onUseCustomUsernameInGlobalChatChange: (Boolean) -> Unit,
    onClearLocalData: () -> Unit,
    onBack: () -> Unit
) {
    var draftUsername by rememberSaveable(currentUsername) { mutableStateOf(currentUsername) }
    var showClearDialog by rememberSaveable { mutableStateOf(false) }
    val currentGlobalChatUsername = if (useCustomUsernameInGlobalChat) currentUsername else generatedUsername

    Scaffold(
        topBar = {
            AuroraTopBar(
                title = "Settings",
                rightAction = AuroraTopBarAction.BACK,
                onRightActionClick = onBack
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Το username αποθηκεύεται μόνο ως απλή τοπική ρύθμιση και όχι ως ασφαλές ή ευαίσθητο μυστικό.
            Text(
                text = "Local profile",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Username is stored locally with a lightweight settings shell.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = draftUsername,
                    onValueChange = { draftUsername = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Username") },
                    singleLine = true
                )
                Button(
                    onClick = { onUsernameChange(draftUsername) },
                    modifier = Modifier.widthIn(min = 84.dp)
                ) {
                    Text("Apply")
                }
            }
            Text(
                text = "Current username: $currentUsername",
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Global Chat",
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Use custom username in Global Chat",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Generated username: $generatedUsername",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Current Global Chat name: $currentGlobalChatUsername",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = useCustomUsernameInGlobalChat,
                    onCheckedChange = onUseCustomUsernameInGlobalChatChange
                )
            }
            // Το UI καλεί το κεντρικό local reset path ώστε το ίδιο flow να καλύπτει σταδιακά όσα τοπικά δεδομένα προστεθούν.
            Text(
                text = "Local data",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Reset local app data and return Aurora to a clean local state.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { showClearDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Clear Local Data")
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = {
                Text("Clear Local Data")
            },
            text = {
                Text("This will reset local app data on this device and return the app to a clean local state.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        onClearLocalData()
                    }
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
