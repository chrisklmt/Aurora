package gr.hua.aurora.wifidirect

import android.net.wifi.p2p.WifiP2pManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidWifiDirectControllerTest {
    @Test
    fun startDiscoveryMarksActiveAndUpdatesPeers() {
        var permissionStatus = readyPermissionStatus()
        val client = FakeWifiDirectPlatformClient().apply {
            requestPeersResult = listOf(
                WifiDirectPeer(
                    deviceName = "Aurora Alpha",
                    deviceAddress = "AA:BB:CC:DD:EE:01"
                )
            )
        }
        val controller = AndroidWifiDirectController(
            permissionStatusReader = { permissionStatus },
            fallbackPermissionStatus = { permissionStatus },
            platformClient = client,
            nowMillis = { 1234L }
        )
        val observedStatuses = mutableListOf<WifiDirectRuntimeStatus>()
        val listener = recordingListener(observedStatuses)
        controller.addListener(listener)

        controller.startDiscovery()

        val status = controller.currentRuntimeStatus()
        assertEquals(WifiDirectDiscoveryState.ACTIVE, status.discoveryState)
        assertEquals(1, status.peerCount)
        assertNull(status.lastError)
        assertEquals(1234L, status.lastUpdatedAtMillis)
        assertEquals(1, client.discoverPeersCallCount)
        assertEquals(1, client.requestPeersCallCount)
        assertTrue(observedStatuses.any { it.discoveryState == WifiDirectDiscoveryState.ACTIVE })
    }

    @Test
    fun startDiscoveryFailsClearlyWhenPermissionIsMissing() {
        val permissionStatus = readyPermissionStatus(
            missingPermissions = setOf("android.permission.NEARBY_WIFI_DEVICES")
        )
        val client = FakeWifiDirectPlatformClient()
        val controller = AndroidWifiDirectController(
            permissionStatusReader = { permissionStatus },
            fallbackPermissionStatus = { permissionStatus },
            platformClient = client,
            nowMillis = { 55L }
        )

        controller.startDiscovery()

        assertEquals(0, client.discoverPeersCallCount)
        assertEquals(
            "Missing Nearby Wi-Fi permission.",
            controller.currentRuntimeStatus().lastError
        )
        assertEquals(WifiDirectDiscoveryState.INACTIVE, controller.currentRuntimeStatus().discoveryState)
    }

    @Test
    fun startDiscoveryFailsClearlyWhenDeviceIsUnsupported() {
        val permissionStatus = WifiDirectPermissionStatus(
            requiredPermissions = setOf("android.permission.NEARBY_WIFI_DEVICES"),
            missingPermissions = emptySet(),
            isWifiDirectSupported = false,
            isWifiEnabled = true,
            isWifiP2pEnabled = true
        )
        val client = FakeWifiDirectPlatformClient()
        val controller = AndroidWifiDirectController(
            permissionStatusReader = { permissionStatus },
            fallbackPermissionStatus = { permissionStatus },
            platformClient = null,
            nowMillis = { 56L }
        )

        controller.startDiscovery()

        assertEquals(0, client.discoverPeersCallCount)
        assertEquals(
            "Wi-Fi Direct unsupported on this device.",
            controller.currentRuntimeStatus().lastError
        )
        assertEquals(WifiDirectDiscoveryState.INACTIVE, controller.currentRuntimeStatus().discoveryState)
    }

    @Test
    fun peerChangeBroadcastRefreshesPeerList() {
        val permissionStatus = readyPermissionStatus()
        val client = FakeWifiDirectPlatformClient().apply {
            requestPeersResult = listOf(
                WifiDirectPeer(
                    deviceName = "Aurora Beta",
                    deviceAddress = "AA:BB:CC:DD:EE:02"
                )
            )
        }
        val controller = AndroidWifiDirectController(
            permissionStatusReader = { permissionStatus },
            fallbackPermissionStatus = { permissionStatus },
            platformClient = client,
            nowMillis = { 77L }
        )

        controller.handleBroadcast(
            WifiDirectBroadcastEvent(
                action = WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION
            )
        )

        assertEquals(1, controller.currentRuntimeStatus().peerCount)
        assertEquals(1, client.requestPeersCallCount)
    }

    @Test
    fun discoveryStateStopsAndClearsPeersWhenWifiDirectTurnsOff() {
        var permissionStatus = readyPermissionStatus()
        val client = FakeWifiDirectPlatformClient().apply {
            requestPeersResult = listOf(
                WifiDirectPeer(
                    deviceName = "Aurora Gamma",
                    deviceAddress = "AA:BB:CC:DD:EE:03"
                )
            )
        }
        val controller = AndroidWifiDirectController(
            permissionStatusReader = { permissionStatus },
            fallbackPermissionStatus = { permissionStatus },
            platformClient = client,
            nowMillis = { 91L }
        )

        controller.startDiscovery()
        permissionStatus = readyPermissionStatus(
            isWifiEnabled = false,
            isWifiP2pEnabled = false
        )
        controller.handleBroadcast(
            WifiDirectBroadcastEvent(
                action = WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION,
                isWifiP2pEnabled = false
            )
        )

        val status = controller.currentRuntimeStatus()
        assertEquals(WifiDirectDiscoveryState.INACTIVE, status.discoveryState)
        assertEquals(0, status.peerCount)
        assertEquals(WifiDirectEnabledState.DISABLED, status.enabledState)
        assertEquals("Wi-Fi Direct is disabled.", status.lastError)
    }

    @Test
    fun startDiscoveryFailsClearlyWhenWifiDirectStateIsDisabled() {
        val permissionStatus = readyPermissionStatus(
            isWifiEnabled = false,
            isWifiP2pEnabled = false
        )
        val client = FakeWifiDirectPlatformClient()
        val controller = AndroidWifiDirectController(
            permissionStatusReader = { permissionStatus },
            fallbackPermissionStatus = { permissionStatus },
            platformClient = client,
            nowMillis = { 101L }
        )

        controller.startDiscovery()

        assertEquals(0, client.discoverPeersCallCount)
        assertEquals("Wi-Fi Direct is disabled.", controller.currentRuntimeStatus().lastError)
        assertEquals(WifiDirectDiscoveryState.INACTIVE, controller.currentRuntimeStatus().discoveryState)
    }

    @Test
    fun stopDiscoveryClearsPeersAndKeepsStateInactive() {
        val permissionStatus = readyPermissionStatus()
        val client = FakeWifiDirectPlatformClient().apply {
            requestPeersResult = listOf(
                WifiDirectPeer(
                    deviceName = "Aurora Delta",
                    deviceAddress = "AA:BB:CC:DD:EE:04"
                )
            )
        }
        val controller = AndroidWifiDirectController(
            permissionStatusReader = { permissionStatus },
            fallbackPermissionStatus = { permissionStatus },
            platformClient = client,
            nowMillis = { 120L }
        )

        controller.startDiscovery()
        controller.stopDiscovery()

        val status = controller.currentRuntimeStatus()
        assertEquals(WifiDirectDiscoveryState.INACTIVE, status.discoveryState)
        assertEquals(0, status.peerCount)
        assertNull(status.lastError)
        assertEquals(1, client.stopDiscoveryCallCount)
    }

    @Test
    fun stopDiscoveryIsSafeWhenAlreadyInactive() {
        val permissionStatus = readyPermissionStatus()
        val client = FakeWifiDirectPlatformClient()
        val controller = AndroidWifiDirectController(
            permissionStatusReader = { permissionStatus },
            fallbackPermissionStatus = { permissionStatus },
            platformClient = client,
            nowMillis = { 121L }
        )

        controller.stopDiscovery()

        assertEquals(WifiDirectDiscoveryState.INACTIVE, controller.currentRuntimeStatus().discoveryState)
        assertNull(controller.currentRuntimeStatus().lastError)
        assertEquals(1, client.stopDiscoveryCallCount)
    }

    @Test
    fun discoveryFailureSurfacesSafeErrorText() {
        val permissionStatus = readyPermissionStatus()
        val client = FakeWifiDirectPlatformClient().apply {
            discoverFailureReason = WifiP2pManager.BUSY
        }
        val controller = AndroidWifiDirectController(
            permissionStatusReader = { permissionStatus },
            fallbackPermissionStatus = { permissionStatus },
            platformClient = client,
            nowMillis = { 300L }
        )

        controller.startDiscovery()

        val status = controller.currentRuntimeStatus()
        assertEquals(WifiDirectDiscoveryState.INACTIVE, status.discoveryState)
        assertEquals("Wi-Fi Direct discovery failed: busy", status.lastError)
        assertEquals(300L, status.lastUpdatedAtMillis)
    }

    private fun readyPermissionStatus(
        missingPermissions: Set<String> = emptySet(),
        isWifiEnabled: Boolean? = true,
        isWifiP2pEnabled: Boolean? = true
    ): WifiDirectPermissionStatus {
        return WifiDirectPermissionStatus(
            requiredPermissions = setOf("android.permission.NEARBY_WIFI_DEVICES"),
            missingPermissions = missingPermissions,
            isWifiDirectSupported = true,
            isWifiEnabled = isWifiEnabled,
            isWifiP2pEnabled = isWifiP2pEnabled
        )
    }

    private fun recordingListener(
        statuses: MutableList<WifiDirectRuntimeStatus>
    ): WifiDirectController.Listener {
        return object : WifiDirectController.Listener {
            override fun onRuntimeStatusChanged(status: WifiDirectRuntimeStatus) {
                statuses += status
            }
        }
    }
}

private class FakeWifiDirectPlatformClient : WifiDirectPlatformClient {
    var discoverPeersCallCount: Int = 0
    var stopDiscoveryCallCount: Int = 0
    var requestPeersCallCount: Int = 0
    var discoverFailureReason: Int? = null
    var stopFailureReason: Int? = null
    var requestPeersFailureReason: String? = null
    var requestPeersResult: List<WifiDirectPeer> = emptyList()

    override fun discoverPeers(
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    ) {
        discoverPeersCallCount += 1
        discoverFailureReason?.let(onFailure) ?: onSuccess()
    }

    override fun stopPeerDiscovery(
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    ) {
        stopDiscoveryCallCount += 1
        stopFailureReason?.let(onFailure) ?: onSuccess()
    }

    override fun requestPeers(
        onSuccess: (List<WifiDirectPeer>) -> Unit,
        onFailure: (String) -> Unit
    ) {
        requestPeersCallCount += 1
        requestPeersFailureReason?.let(onFailure) ?: onSuccess(requestPeersResult)
    }
}
