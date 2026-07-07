package gr.hua.aurora.wifidirect

import gr.hua.aurora.wifidirect.controller.WifiDirectPermissionStatus

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiDirectRuntimeStatusTest {
    @Test
    fun runtimeStatusMapsToSafeSummaryText() {
        val status = WifiDirectRuntimeStatus(
            permissionStatus = WifiDirectPermissionStatus(
                requiredPermissions = setOf("android.permission.NEARBY_WIFI_DEVICES"),
                missingPermissions = emptySet(),
                isWifiDirectSupported = true,
                isWifiEnabled = true
            ),
            discoveryState = WifiDirectDiscoveryState.INACTIVE,
            transportState = WifiDirectTransportState.NOT_WIRED
        )

        assertEquals("yes", wifiDirectSupportSummary(status))
        assertEquals("granted", wifiDirectPermissionsSummary(status.permissionStatus))
        assertEquals(null, wifiDirectMissingPermissionsSummary(status.permissionStatus))
        assertEquals("enabled", wifiDirectEnabledSummary(status.enabledState))
        assertEquals("inactive", wifiDirectDiscoverySummary(status.discoveryState))
        assertEquals("not wired yet", wifiDirectTransportSummary(status.transportState))
        assertEquals("disconnected", wifiDirectConnectionSummary(status.connectionStatus.state))
        assertEquals("unknown", wifiDirectGroupFormedSummary(status.connectionStatus.groupFormed))
        assertEquals("unknown", wifiDirectConnectionRoleSummary(status.connectionStatus.role))
        assertEquals(0, status.peerCount)
        assertEquals("Wi-Fi Direct transport not wired yet.", status.note)
    }

    @Test
    fun runtimeStatusCanRepresentUnsupportedOrUnknownStatesSafely() {
        val status = WifiDirectRuntimeStatus(
            permissionStatus = WifiDirectPermissionStatus(
                requiredPermissions = setOf("android.permission.ACCESS_FINE_LOCATION"),
                missingPermissions = setOf("android.permission.ACCESS_FINE_LOCATION"),
                isWifiDirectSupported = false,
                isWifiEnabled = null
            ),
            lastError = "Wi-Fi Direct status unavailable: RuntimeException"
        )

        assertEquals("no", wifiDirectSupportSummary(status))
        assertEquals("missing", wifiDirectPermissionsSummary(status.permissionStatus))
        assertEquals(
            "ACCESS_FINE_LOCATION",
            wifiDirectMissingPermissionsSummary(status.permissionStatus)
        )
        assertEquals("unknown", wifiDirectEnabledSummary(status.enabledState))
        assertEquals("Wi-Fi Direct status unavailable: RuntimeException", status.lastError)
    }
}
