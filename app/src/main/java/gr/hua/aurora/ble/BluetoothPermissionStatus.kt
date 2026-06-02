package gr.hua.aurora.ble

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

data class BluetoothPermissionStatus(
    val requiredPermissions: Set<String>,
    val missingPermissions: Set<String>,
    val isBluetoothEnabled: Boolean?
) {
    val allRequiredGranted: Boolean
        get() = missingPermissions.isEmpty()
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
            isBluetoothEnabled = readBluetoothEnabled(appContext)
        )
    }

    fun requiredPermissionsForSdkInt(sdkInt: Int): Set<String> {
        return if (sdkInt >= Build.VERSION_CODES.S) {
            linkedSetOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
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
}
