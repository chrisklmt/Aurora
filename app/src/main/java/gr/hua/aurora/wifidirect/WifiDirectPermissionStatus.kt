package gr.hua.aurora.wifidirect

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat

enum class WifiDirectEnabledState {
    ENABLED,
    DISABLED,
    UNKNOWN
}

data class WifiDirectPermissionStatus(
    val requiredPermissions: Set<String>,
    val missingPermissions: Set<String>,
    val isWifiDirectSupported: Boolean,
    val isWifiEnabled: Boolean?,
    val isWifiP2pEnabled: Boolean? = null
) {
    val allRequiredGranted: Boolean
        get() = missingPermissions.isEmpty()

    val enabledState: WifiDirectEnabledState
        get() = when (isWifiP2pEnabled ?: isWifiEnabled) {
            true -> WifiDirectEnabledState.ENABLED
            false -> WifiDirectEnabledState.DISABLED
            null -> WifiDirectEnabledState.UNKNOWN
        }

    val hasMissingNearbyWifiPermission: Boolean
        get() = missingPermissions.contains(Manifest.permission.NEARBY_WIFI_DEVICES)

    val hasMissingLocationPermission: Boolean
        get() = missingPermissions.any { permission ->
            permission == Manifest.permission.ACCESS_FINE_LOCATION ||
                permission == Manifest.permission.ACCESS_COARSE_LOCATION
        }
}

object WifiDirectPermissionStatusReader {
    fun read(
        context: Context,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): WifiDirectPermissionStatus {
        val appContext = context.applicationContext
        val requiredPermissions = requiredPermissionsForSdkInt(sdkInt)
        val missingPermissions = requiredPermissions.filterTo(linkedSetOf()) { permission ->
            ContextCompat.checkSelfPermission(
                appContext,
                permission
            ) != PackageManager.PERMISSION_GRANTED
        }

        return WifiDirectPermissionStatus(
            requiredPermissions = requiredPermissions,
            missingPermissions = missingPermissions,
            isWifiDirectSupported = readWifiDirectSupported(appContext),
            isWifiEnabled = readWifiEnabled(appContext)
        )
    }

    fun requiredPermissionsForSdkInt(sdkInt: Int): Set<String> {
        return if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            linkedSetOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            linkedSetOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun readWifiDirectSupported(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_WIFI_DIRECT
        )
    }

    private fun readWifiEnabled(context: Context): Boolean? {
        return try {
            val manager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
            manager.isWifiEnabled
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }
}
