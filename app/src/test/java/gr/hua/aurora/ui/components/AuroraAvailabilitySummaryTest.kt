package gr.hua.aurora.ui.components

import android.Manifest
import gr.hua.aurora.ble.permissions.BluetoothPermissionStatus
import gr.hua.aurora.state.AuroraAvailabilityPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuroraAvailabilitySummaryTest {
    @Test
    fun onlineStateRequiresBluetoothLocationAndPermissions() {
        val uiState = buildAuroraAvailabilityUiState(
            desiredAvailability = AuroraAvailabilityPreference.ONLINE,
            bluetoothStatus = BluetoothPermissionStatus(
                requiredPermissions = setOf(Manifest.permission.ACCESS_FINE_LOCATION),
                missingPermissions = emptySet(),
                isBluetoothEnabled = true,
                isLocationEnabled = true
            )
        )

        assertTrue(uiState.isOnline)
        assertEquals("Online", uiState.statusLabel)
        assertNull(uiState.reasonText)
    }

    @Test
    fun locationServicesDisabledKeepsAvailabilityOffline() {
        val uiState = buildAuroraAvailabilityUiState(
            desiredAvailability = AuroraAvailabilityPreference.ONLINE,
            bluetoothStatus = BluetoothPermissionStatus(
                requiredPermissions = setOf(Manifest.permission.ACCESS_FINE_LOCATION),
                missingPermissions = emptySet(),
                isBluetoothEnabled = true,
                isLocationEnabled = false
            )
        )

        assertFalse(uiState.isOnline)
        assertEquals("Offline", uiState.statusLabel)
        assertEquals("Location/GPS disabled", uiState.reasonText)
    }

    @Test
    fun userPreferenceOfflineStaysOfflineWithoutReadinessReason() {
        val uiState = buildAuroraAvailabilityUiState(
            desiredAvailability = AuroraAvailabilityPreference.OFFLINE,
            bluetoothStatus = BluetoothPermissionStatus(
                requiredPermissions = setOf(Manifest.permission.ACCESS_FINE_LOCATION),
                missingPermissions = emptySet(),
                isBluetoothEnabled = true,
                isLocationEnabled = true
            )
        )

        assertFalse(uiState.isOnline)
        assertEquals("Offline", uiState.statusLabel)
        assertNull(uiState.reasonText)
    }
}
