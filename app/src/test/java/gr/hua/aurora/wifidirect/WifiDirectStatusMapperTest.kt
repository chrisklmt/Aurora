package gr.hua.aurora.wifidirect

import android.Manifest
import android.net.wifi.p2p.WifiP2pManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiDirectStatusMapperTest {
    @Test
    fun blockedReasonStaysStableForMissingNearbyWifiPermission() {
        val permissionStatus = WifiDirectPermissionStatus(
            requiredPermissions = setOf(Manifest.permission.NEARBY_WIFI_DEVICES),
            missingPermissions = setOf(Manifest.permission.NEARBY_WIFI_DEVICES),
            isWifiDirectSupported = true,
            isWifiEnabled = true,
            isWifiP2pEnabled = true
        )

        assertEquals(
            "Missing Nearby Wi-Fi permission.",
            wifiDirectDiscoveryBlockedReason(permissionStatus)
        )
    }

    @Test
    fun blockedReasonStaysStableForUnsupportedAndDisabledStates() {
        val unsupported = WifiDirectPermissionStatus(
            requiredPermissions = emptySet(),
            missingPermissions = emptySet(),
            isWifiDirectSupported = false,
            isWifiEnabled = true,
            isWifiP2pEnabled = true
        )
        val disabled = WifiDirectPermissionStatus(
            requiredPermissions = emptySet(),
            missingPermissions = emptySet(),
            isWifiDirectSupported = true,
            isWifiEnabled = false,
            isWifiP2pEnabled = false
        )
        val unknown = WifiDirectPermissionStatus(
            requiredPermissions = emptySet(),
            missingPermissions = emptySet(),
            isWifiDirectSupported = true,
            isWifiEnabled = null,
            isWifiP2pEnabled = null
        )

        assertEquals("Wi-Fi Direct unsupported on this device.", wifiDirectDiscoveryBlockedReason(unsupported))
        assertEquals("Wi-Fi Direct is disabled.", wifiDirectDiscoveryBlockedReason(disabled))
        assertEquals("Wi-Fi Direct state unavailable.", wifiDirectDiscoveryBlockedReason(unknown))
    }

    @Test
    fun statusMappingKeepsDiscoveryFieldsStable() {
        val permissionStatus = WifiDirectPermissionStatus(
            requiredPermissions = emptySet(),
            missingPermissions = emptySet(),
            isWifiDirectSupported = true,
            isWifiEnabled = true,
            isWifiP2pEnabled = true
        )
        val peers = listOf(
            WifiDirectPeer(
                deviceName = "Aurora Alpha",
                deviceAddress = "AA:BB:CC:DD:EE:01"
            )
        )

        assertEquals(
            WifiDirectRuntimeStatus(
                permissionStatus = permissionStatus,
                discoveryState = WifiDirectDiscoveryState.ACTIVE,
                transportState = WifiDirectTransportState.NOT_WIRED,
                peers = peers,
                lastError = "Wi-Fi Direct peers unavailable: RuntimeException",
                lastUpdatedAtMillis = 1234L
            ),
            buildWifiDirectRuntimeStatus(
                permissionStatus = permissionStatus,
                discoveryState = WifiDirectDiscoveryState.ACTIVE,
                peers = peers,
                lastError = "Wi-Fi Direct peers unavailable: RuntimeException",
                lastUpdatedAtMillis = 1234L
            )
        )
    }

    @Test
    fun statusUnavailableAndFailureLabelsStayStable() {
        assertEquals(
            "Wi-Fi Direct status unavailable: RuntimeException",
            wifiDirectStatusUnavailableReason(RuntimeException())
        )
        assertEquals("busy", wifiDirectFailureLabel(WifiP2pManager.BUSY))
        assertEquals("unsupported", wifiDirectFailureLabel(WifiP2pManager.P2P_UNSUPPORTED))
        assertEquals("error", wifiDirectFailureLabel(WifiP2pManager.ERROR))
        assertEquals("code 7", wifiDirectFailureLabel(7))
    }

    @Test
    fun p2pStateOverlayStaysSafe() {
        val permissionStatus = WifiDirectPermissionStatus(
            requiredPermissions = emptySet(),
            missingPermissions = emptySet(),
            isWifiDirectSupported = true,
            isWifiEnabled = true,
            isWifiP2pEnabled = null
        )

        assertEquals(
            false,
            wifiDirectPermissionStatusWithP2pState(permissionStatus, false).isWifiP2pEnabled
        )
        assertNull(
            wifiDirectPermissionStatusWithP2pState(permissionStatus, null).isWifiP2pEnabled
        )
    }
}
