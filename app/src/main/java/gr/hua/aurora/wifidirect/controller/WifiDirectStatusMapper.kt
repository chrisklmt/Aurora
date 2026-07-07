package gr.hua.aurora.wifidirect.controller

import android.net.wifi.p2p.WifiP2pManager
import gr.hua.aurora.wifidirect.*

private const val wifiDirectStatusUnavailableMessage = "Wi-Fi Direct status unavailable"

internal fun fallbackWifiDirectPermissionStatus(
    sdkInt: Int
): WifiDirectPermissionStatus {
    return WifiDirectPermissionStatus(
        requiredPermissions = WifiDirectPermissionStatusReader.requiredPermissionsForSdkInt(sdkInt),
        missingPermissions = emptySet(),
        isWifiDirectSupported = false,
        isWifiEnabled = null
    )
}

internal fun wifiDirectStatusUnavailableReason(
    error: Throwable
): String {
    return "$wifiDirectStatusUnavailableMessage: ${error::class.java.simpleName}"
}

internal fun wifiDirectPermissionStatusWithP2pState(
    status: WifiDirectPermissionStatus,
    isWifiP2pEnabled: Boolean?
): WifiDirectPermissionStatus {
    return if (isWifiP2pEnabled != null) {
        status.copy(isWifiP2pEnabled = isWifiP2pEnabled)
    } else {
        status
    }
}

internal fun buildWifiDirectRuntimeStatus(
    permissionStatus: WifiDirectPermissionStatus,
    discoveryState: WifiDirectDiscoveryState,
    connectionStatus: WifiDirectConnectionStatus,
    peers: List<WifiDirectPeer>,
    lastError: String?,
    lastUpdatedAtMillis: Long?
): WifiDirectRuntimeStatus {
    return WifiDirectRuntimeStatus(
        permissionStatus = permissionStatus,
        discoveryState = discoveryState,
        transportState = WifiDirectTransportState.NOT_WIRED,
        connectionStatus = connectionStatus,
        peers = peers,
        lastError = lastError,
        lastUpdatedAtMillis = lastUpdatedAtMillis
    )
}

internal fun wifiDirectDiscoveryBlockedReason(
    permissionStatus: WifiDirectPermissionStatus
): String? {
    if (!permissionStatus.isWifiDirectSupported) {
        return "Wi-Fi Direct unsupported on this device."
    }
    if (permissionStatus.hasMissingNearbyWifiPermission) {
        return "Missing Nearby Wi-Fi permission."
    }
    if (permissionStatus.hasMissingLocationPermission) {
        return "Missing location permission."
    }
    if (!permissionStatus.allRequiredGranted) {
        return "Missing Wi-Fi Direct permission."
    }
    return when (permissionStatus.enabledState) {
        WifiDirectEnabledState.ENABLED -> null
        WifiDirectEnabledState.DISABLED -> "Wi-Fi Direct is disabled."
        WifiDirectEnabledState.UNKNOWN -> "Wi-Fi Direct state unavailable."
    }
}

internal fun wifiDirectFailureLabel(
    reason: Int
): String {
    return when (reason) {
        WifiP2pManager.BUSY -> "busy"
        WifiP2pManager.P2P_UNSUPPORTED -> "unsupported"
        WifiP2pManager.ERROR -> "error"
        else -> "code $reason"
    }
}
