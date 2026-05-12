package gr.hua.aurora.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gr.hua.aurora.navigation.Routes

@Composable
fun DrawerContentHost(
    currentRoute: String,
    onOpenGlobal: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenNearby: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Το drawer εκθέτει μόνο callbacks ώστε να μείνει ανεξάρτητο από συγκεκριμένο navigation implementation.
    OutlinedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Menu",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = "This placeholder menu keeps the app shell visible before real drawer state is added.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            DrawerActionButton(
                label = "Global Chat",
                selected = currentRoute == Routes.GLOBAL,
                onClick = onOpenGlobal
            )
            DrawerActionButton(
                label = "Contacts",
                selected = currentRoute == Routes.CONTACTS,
                onClick = onOpenContacts
            )
            DrawerActionButton(
                label = "Nearby Devices",
                selected = currentRoute == Routes.NEARBY,
                onClick = onOpenNearby
            )
            DrawerActionButton(
                label = "Settings",
                selected = currentRoute == Routes.SETTINGS,
                onClick = onOpenSettings
            )
        }
    }
}

@Composable
private fun DrawerActionButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(label)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(label)
        }
    }
}
