package gr.hua.aurora

import android.Manifest
import gr.hua.aurora.ble.permissions.BluetoothPermissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashActivityGateTest {
    @Test
    fun missingPermissionsRequirePermissionGate() {
        val gate = resolveSplashGate(
            bluetoothStatus = BluetoothPermissionStatus(
                requiredPermissions = setOf(Manifest.permission.ACCESS_FINE_LOCATION),
                missingPermissions = setOf(Manifest.permission.ACCESS_FINE_LOCATION),
                isBluetoothEnabled = true,
                isLocationEnabled = true
            )
        )

        assertEquals(SplashGate.NeedsPermissions, gate)
    }

    @Test
    fun fullStartupPermissionGapStillRequiresPermissionGate() {
        val gate = resolveSplashGate(
            bluetoothStatus = BluetoothPermissionStatus(
                requiredPermissions = setOf(Manifest.permission.ACCESS_FINE_LOCATION),
                missingPermissions = emptySet(),
                isBluetoothEnabled = true,
                isLocationEnabled = true
            ),
            hasMissingStartupPermissions = true
        )

        assertEquals(SplashGate.NeedsPermissions, gate)
    }

    @Test
    fun bluetoothDisabledRequiresBluetoothGate() {
        val gate = resolveSplashGate(
            bluetoothStatus = BluetoothPermissionStatus(
                requiredPermissions = setOf(Manifest.permission.ACCESS_FINE_LOCATION),
                missingPermissions = emptySet(),
                isBluetoothEnabled = false,
                isLocationEnabled = true
            )
        )

        assertEquals(SplashGate.NeedsBluetooth, gate)
        assertTrue(shouldOfferContinueAnyway(gate))
        assertNotEquals(SplashGate.Ready, gate)
    }

    @Test
    fun locationDisabledRequiresLocationGate() {
        val gate = resolveSplashGate(
            bluetoothStatus = BluetoothPermissionStatus(
                requiredPermissions = setOf(Manifest.permission.ACCESS_FINE_LOCATION),
                missingPermissions = emptySet(),
                isBluetoothEnabled = true,
                isLocationEnabled = false
            )
        )

        assertEquals(SplashGate.NeedsLocation, gate)
        assertTrue(shouldOfferContinueAnyway(gate))
        assertNotEquals(SplashGate.Ready, gate)
    }

    @Test
    fun completeReadinessAllowsReadyGate() {
        val gate = resolveSplashGate(
            bluetoothStatus = BluetoothPermissionStatus(
                requiredPermissions = setOf(Manifest.permission.ACCESS_FINE_LOCATION),
                missingPermissions = emptySet(),
                isBluetoothEnabled = true,
                isLocationEnabled = true
            )
        )

        assertEquals(SplashGate.Ready, gate)
        assertFalse(shouldOfferContinueAnyway(gate))
    }

    @Test
    fun permissionsGateDoesNotOfferContinueAnyway() {
        assertFalse(shouldOfferContinueAnyway(SplashGate.NeedsPermissions))
    }

    @Test
    fun permissionDenialDoesNotCountAsGranted() {
        assertFalse(
            wereAllRequestedPermissionsGranted(
                mapOf(
                    Manifest.permission.ACCESS_FINE_LOCATION to true,
                    Manifest.permission.POST_NOTIFICATIONS to false
                )
            )
        )
    }

    @Test
    fun permissionGrantRequiresAllRequestedPermissionsGranted() {
        assertTrue(
            wereAllRequestedPermissionsGranted(
                mapOf(
                    Manifest.permission.ACCESS_FINE_LOCATION to true,
                    Manifest.permission.POST_NOTIFICATIONS to true
                )
            )
        )
    }
}
