package gr.hua.aurora.wifidirect.controller

import gr.hua.aurora.wifidirect.*

internal sealed interface WifiDirectConnectCommandDecision {
    data class Allowed(
        val targetPeer: WifiDirectPeer
    ) : WifiDirectConnectCommandDecision

    data class Blocked(
        val targetPeer: WifiDirectPeer,
        val reason: String
    ) : WifiDirectConnectCommandDecision
}

internal fun wifiDirectConnectCommandDecision(
    permissionStatus: WifiDirectPermissionStatus,
    platformClientAvailable: Boolean,
    currentConnectionStatus: WifiDirectConnectionStatus,
    visiblePeers: List<WifiDirectPeer>,
    requestedPeer: WifiDirectPeer
): WifiDirectConnectCommandDecision {
    val normalizedPeer = WifiDirectPeerMapper.normalizePeer(requestedPeer)
    val blockedReason = wifiDirectDiscoveryBlockedReason(permissionStatus)
    if (blockedReason != null) {
        return WifiDirectConnectCommandDecision.Blocked(
            targetPeer = normalizedPeer,
            reason = blockedReason
        )
    }
    if (!platformClientAvailable) {
        return WifiDirectConnectCommandDecision.Blocked(
            targetPeer = normalizedPeer,
            reason = "Wi-Fi Direct unsupported on this device."
        )
    }

    val targetPeer = visiblePeers.firstOrNull { peer ->
        wifiDirectPeerMatches(peer, normalizedPeer)
    } ?: return WifiDirectConnectCommandDecision.Blocked(
        targetPeer = normalizedPeer,
        reason = "Selected Wi-Fi Direct peer is no longer visible."
    )

    if (targetPeer.deviceAddress.isNullOrBlank()) {
        return WifiDirectConnectCommandDecision.Blocked(
            targetPeer = targetPeer,
            reason = "Selected Wi-Fi Direct peer address unavailable."
        )
    }

    return when (currentConnectionStatus.state) {
        WifiDirectConnectionState.CONNECTING -> {
            if (wifiDirectPeerMatches(currentConnectionStatus.targetPeer, targetPeer)) {
                WifiDirectConnectCommandDecision.Allowed(targetPeer)
            } else {
                WifiDirectConnectCommandDecision.Blocked(
                    targetPeer = targetPeer,
                    reason = "Wi-Fi Direct connection already in progress."
                )
            }
        }
        WifiDirectConnectionState.CONNECTED -> {
            if (wifiDirectPeerMatches(currentConnectionStatus.targetPeer, targetPeer)) {
                WifiDirectConnectCommandDecision.Allowed(targetPeer)
            } else {
                WifiDirectConnectCommandDecision.Blocked(
                    targetPeer = targetPeer,
                    reason = "Disconnect current Wi-Fi Direct peer first."
                )
            }
        }
        WifiDirectConnectionState.DISCONNECTING -> WifiDirectConnectCommandDecision.Blocked(
            targetPeer = targetPeer,
            reason = "Wi-Fi Direct disconnect already in progress."
        )
        WifiDirectConnectionState.DISCONNECTED,
        WifiDirectConnectionState.FAILED -> WifiDirectConnectCommandDecision.Allowed(targetPeer)
    }
}
