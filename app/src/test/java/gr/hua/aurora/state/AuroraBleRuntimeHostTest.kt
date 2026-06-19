package gr.hua.aurora.state

import android.Manifest
import gr.hua.aurora.ble.permissions.BluetoothPermissionStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuroraBleRuntimeHostTest {
    @Test
    fun runtimeStartsWhenAvailabilityAndReadinessAreOnline() {
        assertTrue(
            shouldRunAuroraBleRuntime(
                desiredAvailability = AuroraAvailabilityPreference.ONLINE,
                bluetoothStatus = readyBluetoothStatus(),
                isAppVisible = true
            )
        )
    }

    @Test
    fun runtimeStaysStoppedWhenLocationIsDisabled() {
        assertFalse(
            shouldRunAuroraBleRuntime(
                desiredAvailability = AuroraAvailabilityPreference.ONLINE,
                bluetoothStatus = readyBluetoothStatus(
                    isLocationEnabled = false
                ),
                isAppVisible = true
            )
        )
    }

    @Test
    fun runtimeStaysStoppedWhenUserPreferenceIsOffline() {
        assertFalse(
            shouldRunAuroraBleRuntime(
                desiredAvailability = AuroraAvailabilityPreference.OFFLINE,
                bluetoothStatus = readyBluetoothStatus(),
                isAppVisible = true
            )
        )
    }

    @Test
    fun runtimeStaysStoppedWhenAppIsNotVisible() {
        assertFalse(
            shouldRunAuroraBleRuntime(
                desiredAvailability = AuroraAvailabilityPreference.ONLINE,
                bluetoothStatus = readyBluetoothStatus(),
                isAppVisible = false
            )
        )
    }

    private fun readyBluetoothStatus(
        isLocationEnabled: Boolean = true
    ): BluetoothPermissionStatus {
        return BluetoothPermissionStatus(
            requiredPermissions = setOf(Manifest.permission.ACCESS_FINE_LOCATION),
            missingPermissions = emptySet(),
            isBluetoothEnabled = true,
            isLocationEnabled = isLocationEnabled
        )
    }
}
