package gr.hua.aurora.wifidirect.runtime

import gr.hua.aurora.wifidirect.model.WifiDirectPeer
import gr.hua.aurora.wifidirect.controller.WifiDirectEnabledState
import gr.hua.aurora.wifidirect.controller.WifiDirectPermissionStatus

private const val wifiDirectFoundationNote = "Wi-Fi Direct transport not wired yet."
private const val anonymizedWifiDirectDeviceAddress = "02:00:00:00:00:00"
private val wifiDirectDeviceAddressPattern = Regex("^[0-9a-f]{2}(?::[0-9a-f]{2}){5}$")

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

enum class WifiDirectRolePreference {
    AUTOMATIC,
    PREFER_GROUP_OWNER,
    PREFER_CLIENT
}

enum class WifiDirectLocalAddressClassification {
    AVAILABLE,
    ANONYMIZED,
    UNAVAILABLE
}

data class WifiDirectDnsSdServiceResponse(
    val serviceType: String,
    val instanceName: String? = null,
    val peer: WifiDirectPeer,
    val txtRecord: Map<String, String> = emptyMap(),
    val observedAtMillis: Long? = null
) {
    init {
        require(serviceType.isNotBlank()) {
            "Wi-Fi Direct DNS-SD serviceType must not be blank."
        }
        require(instanceName?.isBlank() != true) {
            "Wi-Fi Direct DNS-SD instanceName must not be blank when provided."
        }
        require(txtRecord.keys.none { it.isBlank() }) {
            "Wi-Fi Direct DNS-SD TXT keys must not be blank."
        }
        require(txtRecord.values.none { it.isBlank() }) {
            "Wi-Fi Direct DNS-SD TXT values must not be blank."
        }
        require(observedAtMillis == null || observedAtMillis >= 0L) {
            "Wi-Fi Direct DNS-SD observedAtMillis must be non-negative when provided."
        }
    }
}

data class WifiDirectDnsSdDiagnostics(
    val localServiceRegistered: Boolean = false,
    val localServiceInstanceName: String? = null,
    val serviceRequestRegistered: Boolean = false,
    val discoveryStarted: Boolean = false,
    val serviceType: String? = null,
    val discoveredServices: List<WifiDirectDnsSdServiceResponse> = emptyList(),
    val lastError: String? = null,
    val cleanupCompleted: Boolean = false
) {
    init {
        require(localServiceInstanceName?.isBlank() != true) {
            "Wi-Fi Direct DNS-SD localServiceInstanceName must not be blank when provided."
        }
        require(serviceType?.isBlank() != true) {
            "Wi-Fi Direct DNS-SD serviceType must not be blank when provided."
        }
        require(lastError?.isBlank() != true) {
            "Wi-Fi Direct DNS-SD lastError must not be blank when provided."
        }
    }
}

data class WifiDirectConnectionStatus(
    val state: WifiDirectConnectionState = WifiDirectConnectionState.DISCONNECTED,
    val targetPeer: WifiDirectPeer? = null,
    val groupFormed: WifiDirectGroupFormedState = WifiDirectGroupFormedState.UNKNOWN,
    val role: WifiDirectConnectionRole = WifiDirectConnectionRole.UNKNOWN,
    val groupOwnerAddress: String? = null,
    val lastError: String? = null
)

data class WifiDirectLocalDeviceInfo(
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val lastError: String? = null
) {
    init {
        require(deviceName?.isBlank() != true) {
            "Wi-Fi Direct local device name must not be blank when provided."
        }
        require(deviceAddress?.isBlank() != true) {
            "Wi-Fi Direct local device address must not be blank when provided."
        }
        require(lastError?.isBlank() != true) {
            "Wi-Fi Direct local device error must not be blank when provided."
        }
    }

    val addressClassification: WifiDirectLocalAddressClassification
        get() = classifyWifiDirectLocalDeviceAddress(deviceAddress)

    val normalizedDeviceAddress: String?
        get() = if (addressClassification == WifiDirectLocalAddressClassification.AVAILABLE) {
            normalizeWifiDirectDeviceAddress(deviceAddress)
        } else {
            null
        }

    val isAddressAvailable: Boolean
        get() = addressClassification == WifiDirectLocalAddressClassification.AVAILABLE
}

data class WifiDirectRuntimeStatus(
    val permissionStatus: WifiDirectPermissionStatus,
    val discoveryState: WifiDirectDiscoveryState = WifiDirectDiscoveryState.INACTIVE,
    val transportState: WifiDirectTransportState = WifiDirectTransportState.NOT_WIRED,
    val connectionStatus: WifiDirectConnectionStatus = WifiDirectConnectionStatus(),
    val localDeviceInfo: WifiDirectLocalDeviceInfo = WifiDirectLocalDeviceInfo(),
    val dnsSdDiagnostics: WifiDirectDnsSdDiagnostics = WifiDirectDnsSdDiagnostics(),
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

internal fun wifiDirectRolePreferenceSummary(
    preference: WifiDirectRolePreference
): String {
    return when (preference) {
        WifiDirectRolePreference.AUTOMATIC -> "Automatic"
        WifiDirectRolePreference.PREFER_GROUP_OWNER -> "Prefer this device as group owner"
        WifiDirectRolePreference.PREFER_CLIENT -> "Prefer this device as client"
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

fun normalizeWifiDirectDeviceAddress(
    deviceAddress: String?
): String? {
    val trimmedAddress = deviceAddress?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val normalizedAddress = trimmedAddress.lowercase()
    return normalizedAddress.takeIf { wifiDirectDeviceAddressPattern.matches(it) }
}

fun classifyWifiDirectLocalDeviceAddress(
    deviceAddress: String?
): WifiDirectLocalAddressClassification {
    val normalizedAddress = normalizeWifiDirectDeviceAddress(deviceAddress)
        ?: return WifiDirectLocalAddressClassification.UNAVAILABLE
    return if (normalizedAddress == anonymizedWifiDirectDeviceAddress) {
        WifiDirectLocalAddressClassification.ANONYMIZED
    } else {
        WifiDirectLocalAddressClassification.AVAILABLE
    }
}
