package gr.hua.aurora.wifidirect

import android.content.Context
import android.os.Build

class AndroidWifiDirectController(
    context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT
) : WifiDirectController {
    private val appContext = context.applicationContext

    override fun currentRuntimeStatus(): WifiDirectRuntimeStatus {
        return runCatching {
            WifiDirectRuntimeStatus(
                permissionStatus = WifiDirectPermissionStatusReader.read(
                    context = appContext,
                    sdkInt = sdkInt
                )
            )
        }.getOrElse { error ->
            WifiDirectRuntimeStatus(
                permissionStatus = WifiDirectPermissionStatus(
                    requiredPermissions = WifiDirectPermissionStatusReader.requiredPermissionsForSdkInt(sdkInt),
                    missingPermissions = emptySet(),
                    isWifiDirectSupported = false,
                    isWifiEnabled = null
                ),
                lastError = "Wi-Fi Direct status unavailable: ${error::class.java.simpleName}"
            )
        }
    }
}
