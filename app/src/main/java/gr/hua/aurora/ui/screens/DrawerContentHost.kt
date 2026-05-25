package gr.hua.aurora.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val drawerWidth = 248.dp
private val drawerInnerEdgeShape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp)

@Composable
fun DrawerContentHost(
    onOpenContacts: () -> Unit,
    onOpenNearby: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Το drawer εκθέτει μόνο callbacks ώστε να μείνει ανεξάρτητο από συγκεκριμένο navigation implementation.
    ModalDrawerSheet(
        modifier = modifier.width(drawerWidth),
        drawerShape = drawerInnerEdgeShape
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Menu",
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(10.dp))
        Column(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DrawerActionPill(
                label = "Settings",
                onClick = onOpenSettings
            )
            DrawerActionPill(
                label = "Contacts",
                onClick = onOpenContacts
            )
            DrawerActionPill(
                label = "Nearby Devices",
                onClick = onOpenNearby
            )
        }
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
private fun DrawerActionPill(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 1.dp
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
