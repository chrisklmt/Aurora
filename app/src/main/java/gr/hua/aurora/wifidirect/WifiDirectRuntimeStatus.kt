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

enum class WifiDirectConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    FAILED
}

enum class WifiDirectGroupFormedState {
    YES,
    NO,
    UNKNOWN
}

enum class WifiDirectConnectionRole {
    GROUP_OWNER,
    CLIENT,
    UNKNOWN
}

data class WifiDirectConnectionStatus(
    val state: WifiDirectConnectionState = WifiDirectConnectionState.DISCONNECTED,
    val targetPeer: WifiDirectPeer? = null,
    val groupFormed: WifiDirectGroupFormedState = WifiDirectGroupFormedState.UNKNOWN,
    val role: WifiDirectConnectionRole = WifiDirectConnectionRole.UNKNOWN,
    val groupOwnerAddress: String? = null,
    val lastError: String? = null
)

data class WifiDirectRuntimeStatus(
    val permissionStatus: WifiDirectPermissionStatus,
    val discoveryState: WifiDirectDiscoveryState = WifiDirectDiscoveryState.INACTIVE,
    val transportState: WifiDirectTransportState = WifiDirectTransportState.NOT_WIRED,
    val connectionStatus: WifiDirectConnectionStatus = WifiDirectConnectionStatus(),
    val peers: List<WifiDirectPeer> = emptyList(),
    val lastError: String? = null,
    val lastUpdatedAtMillis: Long? = null,
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
        "yes"
    } else {
        "no"
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
        WifiDirectTransportState.NOT_WIRED -> "not wired yet"
        WifiDirectTransportState.IDLE -> "idle"
        WifiDirectTransportState.CONNECTING -> "connecting"
        WifiDirectTransportState.CONNECTED -> "connected"
    }
}

fun wifiDirectConnectionSummary(
    state: WifiDirectConnectionState
): String {
    return when (state) {
        WifiDirectConnectionState.DISCONNECTED -> "disconnected"
        WifiDirectConnectionState.CONNECTING -> "connecting"
        WifiDirectConnectionState.CONNECTED -> "connected"
        WifiDirectConnectionState.DISCONNECTING -> "disconnecting"
        WifiDirectConnectionState.FAILED -> "failed"
    }
}

fun wifiDirectGroupFormedSummary(
    state: WifiDirectGroupFormedState
): String {
    return when (state) {
        WifiDirectGroupFormedState.YES -> "yes"
        WifiDirectGroupFormedState.NO -> "no"
        WifiDirectGroupFormedState.UNKNOWN -> "unknown"
    }
}

fun wifiDirectConnectionRoleSummary(
    role: WifiDirectConnectionRole
): String {
    return when (role) {
        WifiDirectConnectionRole.GROUP_OWNER -> "group owner"
        WifiDirectConnectionRole.CLIENT -> "client"
        WifiDirectConnectionRole.UNKNOWN -> "unknown"
    }
}

fun wifiDirectMissingPermissionsSummary(
    status: WifiDirectPermissionStatus
): String? {
    val labels = status.missingPermissionLabels
    return if (labels.isEmpty()) {
        null
    } else {
        labels.joinToString(separator = ", ")
    }
}
