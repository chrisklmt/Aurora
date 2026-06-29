package gr.hua.aurora.wifidirect

private const val wifiDirectFoundationNote = "Wi-Fi Direct transport not wired yet."

enum class WifiDirectDiscoveryState {
    INACTIVE,
    ACTIVE
}

enum class WifiDirectTransportState {
    NOT_WIRED,
    IDLE,
    CONNECTING,
    CONNECTED
}

data class WifiDirectRuntimeStatus(
    val permissionStatus: WifiDirectPermissionStatus,
    val discoveryState: WifiDirectDiscoveryState = WifiDirectDiscoveryState.INACTIVE,
    val transportState: WifiDirectTransportState = WifiDirectTransportState.NOT_WIRED,
    val peers: List<WifiDirectPeer> = emptyList(),
    val lastError: String? = null,
    val note: String = wifiDirectFoundationNote
) {
    val isSupported: Boolean
        get() = permissionStatus.isWifiDirectSupported

    val enabledState: WifiDirectEnabledState
        get() = permissionStatus.enabledState

    val peerCount: Int
        get() = peers.size
}

fun wifiDirectSupportSummary(
    status: WifiDirectRuntimeStatus
): String {
    return if (status.isSupported) {
        "supported"
    } else {
        "unsupported"
    }
}

fun wifiDirectPermissionsSummary(
    status: WifiDirectPermissionStatus
): String {
    return if (status.allRequiredGranted) {
        "granted"
    } else {
        "missing"
    }
}

fun wifiDirectEnabledSummary(
    state: WifiDirectEnabledState
): String {
    return when (state) {
        WifiDirectEnabledState.ENABLED -> "enabled"
        WifiDirectEnabledState.DISABLED -> "disabled"
        WifiDirectEnabledState.UNKNOWN -> "unknown"
    }
}

fun wifiDirectDiscoverySummary(
    state: WifiDirectDiscoveryState
): String {
    return when (state) {
        WifiDirectDiscoveryState.ACTIVE -> "active"
        WifiDirectDiscoveryState.INACTIVE -> "inactive"
    }
}

fun wifiDirectTransportSummary(
    state: WifiDirectTransportState
): String {
    return when (state) {
        WifiDirectTransportState.NOT_WIRED -> "not wired"
        WifiDirectTransportState.IDLE -> "idle"
        WifiDirectTransportState.CONNECTING -> "connecting"
        WifiDirectTransportState.CONNECTED -> "connected"
    }
}
