package gr.hua.aurora.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun GlobalChatScreen(
    onOpenNearby: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSamplePrivateChat: () -> Unit
) {
    PlaceholderScreenScaffold(
        title = "Global Chat",
        subtitle = "Base navigation structure"
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "The global chat screen is currently a placeholder and does not send messages yet.",
                style = MaterialTheme.typography.bodyLarge
            )
            Button(
                onClick = onOpenSamplePrivateChat,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open Sample Private Chat")
            }
            OutlinedButton(
                onClick = onOpenNearby,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open Nearby Devices")
            }
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open Settings")
            }
        }
    }
}
