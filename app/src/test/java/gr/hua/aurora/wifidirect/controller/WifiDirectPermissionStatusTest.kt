package gr.hua.aurora.wifidirect.controller

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
        assertEquals(
            listOf("NEARBY_WIFI_DEVICES"),
            status.missingPermissionLabels
        )
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
        assertEquals(emptyList<String>(), status.missingPermissionLabels)
        assertEquals(WifiDirectEnabledState.DISABLED, status.enabledState)
    }

    @Test
    fun api33MissingNearbyWifiDevicesReportsMissingPermission() {
        val status = WifiDirectPermissionStatus(
            requiredPermissions = WifiDirectPermissionStatusReader.requiredPermissionsForSdkInt(33),
            missingPermissions = setOf(Manifest.permission.NEARBY_WIFI_DEVICES),
            isWifiDirectSupported = true,
            isWifiEnabled = true
        )

        assertTrue(status.hasMissingNearbyWifiPermission)
        assertFalse(status.hasMissingLocationPermission)
        assertEquals(listOf("NEARBY_WIFI_DEVICES"), status.missingPermissionLabels)
    }

    @Test
    fun api33GrantedNearbyWifiDevicesReportsGrantedPermission() {
        val status = WifiDirectPermissionStatus(
            requiredPermissions = WifiDirectPermissionStatusReader.requiredPermissionsForSdkInt(33),
            missingPermissions = emptySet(),
            isWifiDirectSupported = true,
            isWifiEnabled = true
        )

        assertTrue(status.allRequiredGranted)
        assertEquals(emptyList<String>(), status.missingPermissionLabels)
    }

    @Test
    fun api32MissingFineLocationReportsMissingPermission() {
        val status = WifiDirectPermissionStatus(
            requiredPermissions = WifiDirectPermissionStatusReader.requiredPermissionsForSdkInt(32),
            missingPermissions = setOf(Manifest.permission.ACCESS_FINE_LOCATION),
            isWifiDirectSupported = true,
            isWifiEnabled = true
        )

        assertFalse(status.hasMissingNearbyWifiPermission)
        assertTrue(status.hasMissingLocationPermission)
        assertEquals(listOf("ACCESS_FINE_LOCATION"), status.missingPermissionLabels)
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
