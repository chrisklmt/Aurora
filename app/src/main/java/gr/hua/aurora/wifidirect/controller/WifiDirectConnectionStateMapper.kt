package gr.hua.aurora.wifidirect.controller

import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import gr.hua.aurora.wifidirect.*
import gr.hua.aurora.wifidirect.runtime.*

internal data class WifiDirectConnectionSnapshot(
    val groupFormed: WifiDirectGroupFormedState = WifiDirectGroupFormedState.UNKNOWN,
    val role: WifiDirectConnectionRole = WifiDirectConnectionRole.UNKNOWN,
    val groupOwnerAddress: String? = null
)

internal fun wifiDirectConnectionSnapshot(
    groupFormed: Boolean?,
    isGroupOwner: Boolean?,
    groupOwnerAddress: String?
): WifiDirectConnectionSnapshot {
    val normalizedGroupOwnerAddress = groupOwnerAddress?.trim()?.takeIf { it.isNotEmpty() }
    val groupFormedState = when (groupFormed) {
        true -> WifiDirectGroupFormedState.YES
        false -> WifiDirectGroupFormedState.NO
        null -> WifiDirectGroupFormedState.UNKNOWN
    }
    val role = when {
        groupFormed != true -> WifiDirectConnectionRole.UNKNOWN
        isGroupOwner == true -> WifiDirectConnectionRole.GROUP_OWNER
        isGroupOwner == false -> WifiDirectConnectionRole.CLIENT
        else -> WifiDirectConnectionRole.UNKNOWN
    }
    return WifiDirectConnectionSnapshot(
        groupFormed = groupFormedState,
        role = role,
        groupOwnerAddress = normalizedGroupOwnerAddress
    )
}

internal fun wifiDirectConnectionSnapshot(
    info: WifiP2pInfo
): WifiDirectConnectionSnapshot {
    return wifiDirectConnectionSnapshot(
        groupFormed = info.groupFormed,
        isGroupOwner = if (info.groupFormed) info.isGroupOwner else null,
        groupOwnerAddress = info.groupOwnerAddress?.hostAddress
    )
}

internal fun wifiDirectGroupOwnerAddress(
    group: WifiP2pGroup?
): String? {
    return group?.owner?.deviceAddress?.trim()?.takeIf { it.isNotEmpty() }
}

internal fun wifiDirectConnectionStatusFromSnapshot(
    current: WifiDirectConnectionStatus,
    snapshot: WifiDirectConnectionSnapshot
): WifiDirectConnectionStatus {
    val resolvedState = when (snapshot.groupFormed) {
        WifiDirectGroupFormedState.YES -> WifiDirectConnectionState.CONNECTED
        WifiDirectGroupFormedState.NO -> when (current.state) {
            WifiDirectConnectionState.CONNECTING -> WifiDirectConnectionState.CONNECTING
            WifiDirectConnectionState.DISCONNECTING -> WifiDirectConnectionState.DISCONNECTING
            else -> WifiDirectConnectionState.DISCONNECTED
        }
        WifiDirectGroupFormedState.UNKNOWN -> when (current.state) {
            WifiDirectConnectionState.CONNECTING,
            WifiDirectConnectionState.DISCONNECTING -> current.state
            WifiDirectConnectionState.CONNECTED -> WifiDirectConnectionState.CONNECTED
            else -> WifiDirectConnectionState.DISCONNECTED
        }
    }
    return current.copy(
        state = resolvedState,
        groupFormed = snapshot.groupFormed,
        role = snapshot.role,
        groupOwnerAddress = snapshot.groupOwnerAddress,
        lastError = if (resolvedState == WifiDirectConnectionState.CONNECTED) {
            null
        } else {
            current.lastError
        }
    )
}

internal fun wifiDirectConnectionFailureStatus(
    current: WifiDirectConnectionStatus,
    targetPeer: WifiDirectPeer?,
    reason: String
): WifiDirectConnectionStatus {
    return current.copy(
        state = WifiDirectConnectionState.FAILED,
        targetPeer = targetPeer ?: current.targetPeer,
        groupFormed = WifiDirectGroupFormedState.UNKNOWN,
        role = WifiDirectConnectionRole.UNKNOWN,
        groupOwnerAddress = null,
        lastError = reason
    )
}

internal fun wifiDirectDisconnectedStatus(
    current: WifiDirectConnectionStatus,
    keepLastError: Boolean = false,
    lastError: String? = current.lastError
): WifiDirectConnectionStatus {
    return current.copy(
        state = WifiDirectConnectionState.DISCONNECTED,
        targetPeer = null,
        groupFormed = WifiDirectGroupFormedState.UNKNOWN,
        role = WifiDirectConnectionRole.UNKNOWN,
        groupOwnerAddress = null,
        lastError = if (keepLastError) lastError else null
    )
}

internal fun wifiDirectPeerMatches(
    left: WifiDirectPeer?,
    right: WifiDirectPeer?
): Boolean {
    if (left == null || right == null) return false
    val leftAddress = left.deviceAddress?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
    val rightAddress = right.deviceAddress?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
    if (leftAddress != null && rightAddress != null) {
        return leftAddress == rightAddress
    }
    val leftName = left.deviceName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val rightName = right.deviceName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    return leftName != null && leftName == rightName
}
