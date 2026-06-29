package gr.hua.aurora.wifidirect

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

        assertEquals("supported", wifiDirectSupportSummary(status))
        assertEquals("granted", wifiDirectPermissionsSummary(status.permissionStatus))
        assertEquals("enabled", wifiDirectEnabledSummary(status.enabledState))
        assertEquals("inactive", wifiDirectDiscoverySummary(status.discoveryState))
        assertEquals("not wired", wifiDirectTransportSummary(status.transportState))
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

        assertEquals("unsupported", wifiDirectSupportSummary(status))
        assertEquals("missing", wifiDirectPermissionsSummary(status.permissionStatus))
        assertEquals("unknown", wifiDirectEnabledSummary(status.enabledState))
        assertEquals("Wi-Fi Direct status unavailable: RuntimeException", status.lastError)
    }
}
