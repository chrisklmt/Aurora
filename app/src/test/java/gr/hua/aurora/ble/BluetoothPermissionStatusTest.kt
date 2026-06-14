package gr.hua.aurora.ble

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothPermissionStatusTest {
    @Test
    fun api31AndAboveRequiresModernBluetoothPermissionsAndFineLocation() {
        assertEquals(
            linkedSetOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            ),
            BluetoothPermissionStatusReader.requiredPermissionsForSdkInt(31)
        )
    }

    @Test
    fun api30AndBelowRequiresFineLocation() {
        assertEquals(
            setOf(Manifest.permission.ACCESS_FINE_LOCATION),
            BluetoothPermissionStatusReader.requiredPermissionsForSdkInt(30)
        )
    }

    @Test
    fun allRequiredGrantedIsTrueWhenMissingPermissionsEmpty() {
        val status = BluetoothPermissionStatus(
            requiredPermissions = setOf(Manifest.permission.ACCESS_FINE_LOCATION),
            missingPermissions = emptySet(),
            isBluetoothEnabled = null
        )

        assertTrue(status.allRequiredGranted)
    }

    @Test
    fun allRequiredGrantedIsFalseWhenMissingPermissionsNotEmpty() {
        val status = BluetoothPermissionStatus(
            requiredPermissions = setOf(Manifest.permission.ACCESS_FINE_LOCATION),
            missingPermissions = setOf(Manifest.permission.ACCESS_FINE_LOCATION),
            isBluetoothEnabled = null
        )

        assertFalse(status.allRequiredGranted)
    }
}
