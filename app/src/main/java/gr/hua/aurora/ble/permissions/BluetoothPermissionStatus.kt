package gr.hua.aurora.ble.permissions

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

data class BluetoothPermissionStatus(
    val requiredPermissions: Set<String>,
    val missingPermissions: Set<String>,
    val isBluetoothEnabled: Boolean?,
    val isLocationEnabled: Boolean?
) {
    val allRequiredGranted: Boolean
        get() = missingPermissions.isEmpty()

    val hasMissingBluetoothPermission: Boolean
        get() = missingPermissions.any { permission ->
            permission == Manifest.permission.BLUETOOTH_SCAN ||
                permission == Manifest.permission.BLUETOOTH_ADVERTISE ||
                permission == Manifest.permission.BLUETOOTH_CONNECT
        }

    val hasMissingLocationPermission: Boolean
        get() = missingPermissions.any { permission ->
            permission == Manifest.permission.ACCESS_FINE_LOCATION ||
                permission == Manifest.permission.ACCESS_COARSE_LOCATION
        }

    val isBluetoothReady: Boolean
        get() = isBluetoothEnabled == true

    val isLocationReady: Boolean
        get() = isLocationEnabled == true

    val isReadinessComplete: Boolean
        get() = allRequiredGranted && isBluetoothReady && isLocationReady
}

object BluetoothPermissionStatusReader {
    fun read(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): BluetoothPermissionStatus {
        val appContext = context.applicationContext
        val requiredPermissions = requiredPermissionsForSdkInt(sdkInt)
        val missingPermissions = requiredPermissions.filterTo(linkedSetOf()) { permission ->
            ContextCompat.checkSelfPermission(
                appContext,
                permission
            ) != PackageManager.PERMISSION_GRANTED
        }

        return BluetoothPermissionStatus(
            requiredPermissions = requiredPermissions,
            missingPermissions = missingPermissions,
            isBluetoothEnabled = readBluetoothEnabled(appContext),
            isLocationEnabled = readLocationEnabled(appContext, sdkInt)
        )
    }

    fun requiredPermissionsForSdkInt(sdkInt: Int): Set<String> {
        return if (sdkInt >= Build.VERSION_CODES.S) {
            linkedSetOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            setOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun readBluetoothEnabled(context: Context): Boolean? {
        return try {
            val manager = context.getSystemService(BluetoothManager::class.java) ?: return null
            manager.adapter?.isEnabled
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun readLocationEnabled(
        context: Context,
        sdkInt: Int
    ): Boolean? {
        return try {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return null
            if (sdkInt >= Build.VERSION_CODES.P) {
                manager.isLocationEnabled
            } else {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }
}
