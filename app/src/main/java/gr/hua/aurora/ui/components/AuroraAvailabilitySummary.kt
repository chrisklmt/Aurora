package gr.hua.aurora.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import gr.hua.aurora.ble.permissions.BluetoothPermissionStatus
import gr.hua.aurora.ble.permissions.rememberBluetoothPermissionStatusState
import gr.hua.aurora.state.AuroraAvailabilityPreference

private val availabilityOnlineColor = Color(0xFF2E7D32)
private val availabilityOfflineColor = Color(0xFFC62828)

data class AuroraAvailabilityUiState(
    val statusLabel: String,
    val reasonText: String?,
    val isOnline: Boolean,
    val canRetry: Boolean
)

data class RememberedAuroraAvailabilityUiState(
    val uiState: AuroraAvailabilityUiState,
    val refresh: () -> Unit
)

@Composable
fun rememberAuroraAvailabilityUiState(
    desiredAvailability: AuroraAvailabilityPreference
): RememberedAuroraAvailabilityUiState {
    val bluetoothStatusState = rememberBluetoothPermissionStatusState()

    return RememberedAuroraAvailabilityUiState(
        uiState = buildAuroraAvailabilityUiState(
            desiredAvailability = desiredAvailability,
            bluetoothStatus = bluetoothStatusState.status
        ),
        refresh = bluetoothStatusState.refresh
    )
}

fun buildAuroraAvailabilityUiState(
    desiredAvailability: AuroraAvailabilityPreference,
    bluetoothStatus: BluetoothPermissionStatus
): AuroraAvailabilityUiState {
    if (desiredAvailability == AuroraAvailabilityPreference.OFFLINE) {
        return AuroraAvailabilityUiState(
            statusLabel = "Offline",
            reasonText = null,
            isOnline = false,
            canRetry = false
        )
    }

    val reasonText = availabilityReasonText(bluetoothStatus)
    if (reasonText != null) {
        return AuroraAvailabilityUiState(
            statusLabel = "Offline",
            reasonText = reasonText,
            isOnline = false,
            canRetry = true
        )
    }

    return AuroraAvailabilityUiState(
        statusLabel = "Online",
        reasonText = null,
        isOnline = true,
        canRetry = false
    )
}

@Composable
fun AuroraAvailabilitySummary(
    uiState: AuroraAvailabilityUiState,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null
) {
    val displayText = if (!uiState.reasonText.isNullOrBlank()) {
        "${uiState.statusLabel} - ${uiState.reasonText}"
    } else {
        uiState.statusLabel
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AuroraAvailabilityIndicator(
                uiState = uiState,
                labelText = displayText,
                modifier = Modifier.weight(1f),
                onClick = onClick
            )
            trailingContent?.invoke(this)
        }
    }
}

@Composable
fun AuroraAvailabilityIndicator(
    uiState: AuroraAvailabilityUiState,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    AuroraAvailabilityIndicator(
        uiState = uiState,
        labelText = uiState.statusLabel,
        modifier = modifier,
        onClick = onClick
    )
}

@Composable
private fun AuroraAvailabilityIndicator(
    uiState: AuroraAvailabilityUiState,
    labelText: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val clickableModifier = if (uiState.canRetry && onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }
    val indicatorColor = if (uiState.isOnline) {
        availabilityOnlineColor
    } else {
        availabilityOfflineColor
    }

    Row(
        modifier = modifier.then(clickableModifier),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvailabilityDot(color = indicatorColor)
        Text(
            text = labelText,
            style = MaterialTheme.typography.labelMedium,
            color = indicatorColor
        )
    }
}

@Composable
private fun AvailabilityDot(
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(color)
            .width(10.dp)
            .height(10.dp)
    )
}

private fun availabilityReasonText(
    bluetoothStatus: BluetoothPermissionStatus
): String? {
    val reasonParts = buildList {
        if (bluetoothStatus.isBluetoothEnabled == false) {
            add("Bluetooth disabled")
        }
        if (bluetoothStatus.hasMissingBluetoothPermission) {
            add("Bluetooth permission missing")
        }
        if (bluetoothStatus.hasMissingLocationPermission) {
            add("Location/GPS permission missing")
        }
        if (bluetoothStatus.isLocationEnabled == false) {
            add("Location/GPS disabled")
        }
        if (
            bluetoothStatus.isBluetoothEnabled == null &&
            bluetoothStatus.isLocationEnabled == null &&
            bluetoothStatus.missingPermissions.isEmpty()
        ) {
            add("Readiness unavailable")
        }
    }

    return reasonParts.takeIf { it.isNotEmpty() }?.joinToString(", ")
}
