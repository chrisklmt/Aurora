package gr.hua.aurora.wifidirect.controller

import gr.hua.aurora.wifidirect.*
import gr.hua.aurora.wifidirect.runtime.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDirectConnectionCommandGuardTest {
    @Test
    fun connectGuardBlocksUnsupportedDevice() {
        val decision = wifiDirectConnectCommandDecision(
            permissionStatus = readyPermissionStatus(isSupported = false),
            platformClientAvailable = false,
            currentConnectionStatus = WifiDirectConnectionStatus(),
            visiblePeers = listOf(visiblePeer()),
            requestedPeer = visiblePeer()
        )

        assertEquals(
            WifiDirectConnectCommandDecision.Blocked(
                targetPeer = visiblePeer(),
                reason = "Wi-Fi Direct unsupported on this device."
            ),
            decision
        )
    }

    @Test
    fun connectGuardBlocksMissingPermission() {
        val decision = wifiDirectConnectCommandDecision(
            permissionStatus = readyPermissionStatus(
                missingPermissions = setOf("android.permission.NEARBY_WIFI_DEVICES")
            ),
            platformClientAvailable = true,
            currentConnectionStatus = WifiDirectConnectionStatus(),
            visiblePeers = listOf(visiblePeer()),
            requestedPeer = visiblePeer()
        )

        assertEquals(
            WifiDirectConnectCommandDecision.Blocked(
                targetPeer = visiblePeer(),
                reason = "Missing Nearby Wi-Fi permission."
            ),
            decision
        )
    }

    @Test
    fun connectGuardBlocksDisabledOrUnknownState() {
        val disabledDecision = wifiDirectConnectCommandDecision(
            permissionStatus = readyPermissionStatus(
                isWifiEnabled = false,
                isWifiP2pEnabled = false
            ),
            platformClientAvailable = true,
            currentConnectionStatus = WifiDirectConnectionStatus(),
            visiblePeers = listOf(visiblePeer()),
            requestedPeer = visiblePeer()
        )
        val unknownDecision = wifiDirectConnectCommandDecision(
            permissionStatus = readyPermissionStatus(
                isWifiEnabled = null,
                isWifiP2pEnabled = null
            ),
            platformClientAvailable = true,
            currentConnectionStatus = WifiDirectConnectionStatus(),
            visiblePeers = listOf(visiblePeer()),
            requestedPeer = visiblePeer()
        )

        assertEquals(
            WifiDirectConnectCommandDecision.Blocked(
                targetPeer = visiblePeer(),
                reason = "Wi-Fi Direct is disabled."
            ),
            disabledDecision
        )
        assertEquals(
            WifiDirectConnectCommandDecision.Blocked(
                targetPeer = visiblePeer(),
                reason = "Wi-Fi Direct state unavailable."
            ),
            unknownDecision
        )
    }

    @Test
    fun connectGuardBlocksMissingPeer() {
        val decision = wifiDirectConnectCommandDecision(
            permissionStatus = readyPermissionStatus(),
            platformClientAvailable = true,
            currentConnectionStatus = WifiDirectConnectionStatus(),
            visiblePeers = emptyList(),
            requestedPeer = visiblePeer()
        )

        assertEquals(
            WifiDirectConnectCommandDecision.Blocked(
                targetPeer = visiblePeer(),
                reason = "Selected Wi-Fi Direct peer is no longer visible."
            ),
            decision
        )
    }

    @Test
    fun connectGuardAllowsVisiblePeerWhenIdleOrRetryingFailure() {
        val idleDecision = wifiDirectConnectCommandDecision(
            permissionStatus = readyPermissionStatus(),
            platformClientAvailable = true,
            currentConnectionStatus = WifiDirectConnectionStatus(),
            visiblePeers = listOf(visiblePeer()),
            requestedPeer = visiblePeer()
        )
        val failedDecision = wifiDirectConnectCommandDecision(
            permissionStatus = readyPermissionStatus(),
            platformClientAvailable = true,
            currentConnectionStatus = WifiDirectConnectionStatus(
                state = WifiDirectConnectionState.FAILED,
                targetPeer = visiblePeer(),
                lastError = "Wi-Fi Direct connect failed: busy"
            ),
            visiblePeers = listOf(visiblePeer()),
            requestedPeer = visiblePeer()
        )

        assertTrue(idleDecision is WifiDirectConnectCommandDecision.Allowed)
        assertTrue(failedDecision is WifiDirectConnectCommandDecision.Allowed)
    }

    private fun readyPermissionStatus(
        missingPermissions: Set<String> = emptySet(),
        isSupported: Boolean = true,
        isWifiEnabled: Boolean? = true,
        isWifiP2pEnabled: Boolean? = true
    ): WifiDirectPermissionStatus {
        return WifiDirectPermissionStatus(
            requiredPermissions = setOf("android.permission.NEARBY_WIFI_DEVICES"),
            missingPermissions = missingPermissions,
            isWifiDirectSupported = isSupported,
            isWifiEnabled = isWifiEnabled,
            isWifiP2pEnabled = isWifiP2pEnabled
        )
    }

    private fun visiblePeer(): WifiDirectPeer {
        return WifiDirectPeer(
            deviceName = "Aurora Alpha",
            deviceAddress = "AA:BB:CC:DD:EE:01"
        )
    }
}
