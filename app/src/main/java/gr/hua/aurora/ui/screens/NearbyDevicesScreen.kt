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
import gr.hua.aurora.model.NearbyDevicePreview
import gr.hua.aurora.model.TransportType
import gr.hua.aurora.ui.components.AuroraTopBarAction

@Composable
fun NearbyDevicesScreen(
    nearbyDevices: List<NearbyDevicePreview>,
    currentUsername: String,
    onResetLocalData: () -> Unit,
    onBack: () -> Unit
) {
    PlaceholderScreenScaffold(
        title = "Nearby Devices",
        subtitle = "Discovery placeholder",
        username = currentUsername,
        onUsernameTripleTap = onResetLocalData,
        rightAction = AuroraTopBarAction.BACK,
        onRightActionClick = onBack
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Οι nearby εγγραφές εδώ είναι μόνο sample/in-memory UI state και δεν ξεκινούν πραγματική ανακάλυψη ή σύνδεση.
            Text(
                text = "Nearby preview devices are shown from local state only. Real discovery is not active yet.",
                style = MaterialTheme.typography.bodyLarge
            )
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(nearbyDevices) { device ->
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = device.displayName,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = device.detail,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Transport: ${device.transportType.toUiLabel()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!device.signalLabel.isNullOrBlank()) {
                                Text(
                                    text = "Signal: ${device.signalLabel}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = if (device.isConnectable) {
                                    "Status: Connectable"
                                } else {
                                    "Status: Preview only"
                                },
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

private fun TransportType.toUiLabel(): String {
    return when (this) {
        TransportType.LOCAL -> "Local"
        TransportType.BLE -> "BLE"
        TransportType.WIFI_DIRECT -> "Wi-Fi Direct"
        TransportType.UNKNOWN -> "Unknown"
    }
}
