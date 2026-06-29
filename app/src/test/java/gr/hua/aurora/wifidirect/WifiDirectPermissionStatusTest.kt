package gr.hua.aurora.wifidirect

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDirectPermissionStatusTest {
    @Test
    fun api32AndBelowRequiresFineLocation() {
        assertEquals(
            linkedSetOf(Manifest.permission.ACCESS_FINE_LOCATION),
            WifiDirectPermissionStatusReader.requiredPermissionsForSdkInt(32)
        )
    }

    @Test
    fun api33AndAboveRequiresNearbyWifiDevices() {
        assertEquals(
            linkedSetOf(Manifest.permission.NEARBY_WIFI_DEVICES),
            WifiDirectPermissionStatusReader.requiredPermissionsForSdkInt(33)
        )
    }

    @Test
    fun missingPermissionsProduceMissingStatus() {
        val status = WifiDirectPermissionStatus(
            requiredPermissions = setOf(Manifest.permission.NEARBY_WIFI_DEVICES),
            missingPermissions = setOf(Manifest.permission.NEARBY_WIFI_DEVICES),
            isWifiDirectSupported = true,
            isWifiEnabled = true
        )

        assertFalse(status.allRequiredGranted)
        assertTrue(status.hasMissingNearbyWifiPermission)
        assertEquals(WifiDirectEnabledState.ENABLED, status.enabledState)
    }

    @Test
    fun grantedPermissionsProduceGrantedStatus() {
        val status = WifiDirectPermissionStatus(
            requiredPermissions = setOf(Manifest.permission.ACCESS_FINE_LOCATION),
            missingPermissions = emptySet(),
            isWifiDirectSupported = true,
            isWifiEnabled = false
        )

        assertTrue(status.allRequiredGranted)
        assertFalse(status.hasMissingLocationPermission)
        assertEquals(WifiDirectEnabledState.DISABLED, status.enabledState)
    }

    @Test
    fun enabledStateFallsBackToUnknownWhenWifiStateIsUnavailable() {
        val status = WifiDirectPermissionStatus(
            requiredPermissions = emptySet(),
            missingPermissions = emptySet(),
            isWifiDirectSupported = true,
            isWifiEnabled = null,
            isWifiP2pEnabled = null
        )

        assertEquals(WifiDirectEnabledState.UNKNOWN, status.enabledState)
    }
}
