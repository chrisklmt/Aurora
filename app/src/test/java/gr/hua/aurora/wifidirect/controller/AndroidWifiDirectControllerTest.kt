package gr.hua.aurora.wifidirect.controller

import android.net.wifi.p2p.WifiP2pManager
import gr.hua.aurora.wifidirect.*
import gr.hua.aurora.wifidirect.runtime.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidWifiDirectControllerTest {
    @Test
    fun groupOwnerIntentMappingMatchesDebugRolePreference() {
        assertNull(wifiDirectGroupOwnerIntentOrNull(WifiDirectRolePreference.AUTOMATIC))
        assertEquals(15, wifiDirectGroupOwnerIntentOrNull(WifiDirectRolePreference.PREFER_GROUP_OWNER))
        assertEquals(0, wifiDirectGroupOwnerIntentOrNull(WifiDirectRolePreference.PREFER_CLIENT))
    }

    @Test
    fun connectRequestDebugTextIncludesPeerPreferenceAndIntent() {
        val peer = WifiDirectPeer(
            deviceName = "Aurora White",
            deviceAddress = "AA:BB:CC:DD:EE:01"
        )

        assertEquals(
            "peerName=Aurora White peerAddress=AA:BB:CC:DD:EE:01 preference=prefer_group_owner groupOwnerIntent=15",
            wifiDirectConnectRequestDebugText(peer, WifiDirectRolePreference.PREFER_GROUP_OWNER)
        )
        assertEquals(
            "peerName=Aurora White peerAddress=AA:BB:CC:DD:EE:01 preference=automatic groupOwnerIntent=default",
            wifiDirectConnectRequestDebugText(peer, WifiDirectRolePreference.AUTOMATIC)
        )
    }

    @Test
    fun connectionStateDefaultsToDisconnected() {
        val permissionStatus = readyPermissionStatus()
        val controller = AndroidWifiDirectController(
            permissionStatusReader = { permissionStatus },
            fallbackPermissionStatus = { permissionStatus },
            platformClient = FakeWifiDirectPlatformClient(),
            nowMillis = { 1L }
        )

        assertEquals(
            WifiDirectConnectionState.DISCONNECTED,
            controller.currentRuntimeStatus().connectionStatus.state
        )
        assertNull(controller.currentRuntimeStatus().connectionStatus.targetPeer)
    }

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
        controller.addListener(recordingListener(observedStatuses))

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
        val controller = AndroidWifiDirectController(
            permissionStatusReader = { permissionStatus },
            fallbackPermissionStatus = { permissionStatus },
            platformClient = null,
            nowMillis = { 56L }
        )

        controller.startDiscovery()

        assertEquals(
            "Wi-Fi Direct unsupported on this device.",
            controller.currentRuntimeStatus().lastError
        )
        assertEquals(WifiDirectDiscoveryState.INACTIVE, controller.currentRuntimeStatus().discoveryState)
    }

    @Test
    fun connectFailsClearlyWhenPermissionIsMissing() {
        val permissionStatus = readyPermissionStatus(
            missingPermissions = setOf("android.permission.NEARBY_WIFI_DEVICES")
        )
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
            nowMillis = { 60L }
        )

        controller.connectToPeer(
            peer = client.requestPeersResult.first(),
            rolePreference = WifiDirectRolePreference.AUTOMATIC
        )

        assertEquals(0, client.connectToPeerCallCount)
        assertEquals(
            WifiDirectConnectionState.FAILED,
            controller.currentRuntimeStatus().connectionStatus.state
        )
        assertEquals(
            "Missing Nearby Wi-Fi permission.",
            controller.currentRuntimeStatus().connectionStatus.lastError
        )
    }

    @Test
    fun connectFailsClearlyWhenWifiDirectStateIsDisabled() {
        val permissionStatus = readyPermissionStatus(
            isWifiEnabled = false,
            isWifiP2pEnabled = false
        )
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
            nowMillis = { 61L }
        )

        controller.connectToPeer(
            peer = client.requestPeersResult.first(),
            rolePreference = WifiDirectRolePreference.AUTOMATIC
        )

        assertEquals(0, client.connectToPeerCallCount)
        assertEquals(
            "Wi-Fi Direct is disabled.",
            controller.currentRuntimeStatus().connectionStatus.lastError
        )
    }

    @Test
    fun connectFailsClearlyWhenPeerIsNoLongerVisible() {
        val permissionStatus = readyPermissionStatus()
        val client = FakeWifiDirectPlatformClient()
        val controller = AndroidWifiDirectController(
            permissionStatusReader = { permissionStatus },
            fallbackPermissionStatus = { permissionStatus },
            platformClient = client,
            nowMillis = { 62L }
        )

        controller.connectToPeer(
            peer = WifiDirectPeer(
                deviceName = "Aurora Missing",
                deviceAddress = "AA:BB:CC:DD:EE:09"
            ),
            rolePreference = WifiDirectRolePreference.AUTOMATIC
        )

        assertEquals(0, client.connectToPeerCallCount)
        assertEquals(
            "Selected Wi-Fi Direct peer is no longer visible.",
            controller.currentRuntimeStatus().connectionStatus.lastError
        )
    }

    @Test
    fun connectSuccessMovesStateTowardConnectingAndConnected() {
        val permissionStatus = readyPermissionStatus()
        val peer = WifiDirectPeer(
            deviceName = "Aurora Beta",
            deviceAddress = "AA:BB:CC:DD:EE:02"
        )
        val client = FakeWifiDirectPlatformClient().apply {
            requestPeersResult = listOf(peer)
            connectionSnapshot = WifiDirectConnectionSnapshot(
                groupFormed = WifiDirectGroupFormedState.NO,
                role = WifiDirectConnectionRole.UNKNOWN,
                groupOwnerAddress = null
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
        controller.connectToPeer(
            peer = peer,
            rolePreference = WifiDirectRolePreference.PREFER_CLIENT
        )

        assertEquals(1, client.connectToPeerCallCount)
        assertEquals(1, client.requestConnectionSnapshotCallCount)
        assertEquals(WifiDirectRolePreference.PREFER_CLIENT, client.lastRolePreference)
        assertEquals(
            WifiDirectConnectionState.CONNECTING,
            controller.currentRuntimeStatus().connectionStatus.state
        )

        client.connectionSnapshot = WifiDirectConnectionSnapshot(
            groupFormed = WifiDirectGroupFormedState.YES,
            role = WifiDirectConnectionRole.CLIENT,
            groupOwnerAddress = "192.168.49.1"
        )
        controller.handleBroadcast(
            WifiDirectBroadcastEvent(
                action = WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION,
                isConnectionEstablished = true
            )
        )

        val status = controller.currentRuntimeStatus().connectionStatus
        assertEquals(WifiDirectConnectionState.CONNECTED, status.state)
        assertEquals(WifiDirectGroupFormedState.YES, status.groupFormed)
        assertEquals(WifiDirectConnectionRole.CLIENT, status.role)
        assertEquals("192.168.49.1", status.groupOwnerAddress)
    }

    @Test
    fun connectFailureRecordsLastConnectionError() {
        val permissionStatus = readyPermissionStatus()
        val peer = WifiDirectPeer(
            deviceName = "Aurora Busy",
            deviceAddress = "AA:BB:CC:DD:EE:03"
        )
        val client = FakeWifiDirectPlatformClient().apply {
            requestPeersResult = listOf(peer)
            connectFailureReason = WifiP2pManager.BUSY
        }
        val controller = AndroidWifiDirectController(
            permissionStatusReader = { permissionStatus },
            fallbackPermissionStatus = { permissionStatus },
            platformClient = client,
            nowMillis = { 91L }
        )

        controller.handleBroadcast(
            WifiDirectBroadcastEvent(
                action = WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION
            )
        )
        controller.connectToPeer(
            peer = peer,
            rolePreference = WifiDirectRolePreference.AUTOMATIC
        )

        val status = controller.currentRuntimeStatus().connectionStatus
        assertEquals(WifiDirectConnectionState.FAILED, status.state)
        assertEquals("Wi-Fi Direct connect failed: busy", status.lastError)
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
    fun connectionChangedBroadcastUpdatesRuntimeState() {
        val permissionStatus = readyPermissionStatus()
        val client = FakeWifiDirectPlatformClient().apply {
            connectionSnapshot = WifiDirectConnectionSnapshot(
                groupFormed = WifiDirectGroupFormedState.YES,
                role = WifiDirectConnectionRole.GROUP_OWNER,
                groupOwnerAddress = "AA:BB:CC:DD:EE:AA"
            )
        }
        val controller = AndroidWifiDirectController(
            permissionStatusReader = { permissionStatus },
            fallbackPermissionStatus = { permissionStatus },
            platformClient = client,
            nowMillis = { 88L }
        )

        controller.handleBroadcast(
            WifiDirectBroadcastEvent(
                action = WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION,
                isConnectionEstablished = true
            )
        )

        val status = controller.currentRuntimeStatus().connectionStatus
        assertEquals(WifiDirectConnectionState.CONNECTED, status.state)
        assertEquals(WifiDirectConnectionRole.GROUP_OWNER, status.role)
        assertEquals("AA:BB:CC:DD:EE:AA", status.groupOwnerAddress)
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
        assertEquals(WifiDirectConnectionState.DISCONNECTED, status.connectionStatus.state)
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
    fun disconnectIsSafeWhenInactive() {
        val permissionStatus = readyPermissionStatus()
        val client = FakeWifiDirectPlatformClient()
        val controller = AndroidWifiDirectController(
            permissionStatusReader = { permissionStatus },
            fallbackPermissionStatus = { permissionStatus },
            platformClient = client,
            nowMillis = { 121L }
        )

        controller.disconnect()

        assertEquals(0, client.cancelPendingConnectionCallCount)
        assertEquals(0, client.disconnectFromPeerCallCount)
        assertEquals(
            WifiDirectConnectionState.DISCONNECTED,
            controller.currentRuntimeStatus().connectionStatus.state
        )
    }

    @Test
    fun disconnectClearsConnectedState() {
        val permissionStatus = readyPermissionStatus()
        val peer = WifiDirectPeer(
            deviceName = "Aurora Echo",
            deviceAddress = "AA:BB:CC:DD:EE:05"
        )
        val client = FakeWifiDirectPlatformClient().apply {
            requestPeersResult = listOf(peer)
            connectionSnapshot = WifiDirectConnectionSnapshot(
                groupFormed = WifiDirectGroupFormedState.YES,
                role = WifiDirectConnectionRole.CLIENT,
                groupOwnerAddress = "192.168.49.1"
            )
        }
        val controller = AndroidWifiDirectController(
            permissionStatusReader = { permissionStatus },
            fallbackPermissionStatus = { permissionStatus },
            platformClient = client,
            nowMillis = { 122L }
        )

        controller.handleBroadcast(
            WifiDirectBroadcastEvent(
                action = WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION
            )
        )
        controller.connectToPeer(
            peer = peer,
            rolePreference = WifiDirectRolePreference.AUTOMATIC
        )
        controller.handleBroadcast(
            WifiDirectBroadcastEvent(
                action = WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION,
                isConnectionEstablished = true
            )
        )

        controller.disconnect()

        assertEquals(1, client.disconnectFromPeerCallCount)
        assertEquals(
            WifiDirectConnectionState.DISCONNECTED,
            controller.currentRuntimeStatus().connectionStatus.state
        )
        assertNull(controller.currentRuntimeStatus().connectionStatus.targetPeer)
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
    var connectToPeerCallCount: Int = 0
    var cancelPendingConnectionCallCount: Int = 0
    var disconnectFromPeerCallCount: Int = 0
    var requestConnectionSnapshotCallCount: Int = 0
    var discoverFailureReason: Int? = null
    var stopFailureReason: Int? = null
    var requestPeersFailureReason: String? = null
    var connectFailureReason: Int? = null
    var cancelFailureReason: Int? = null
    var disconnectFailureReason: Int? = null
    var requestConnectionSnapshotFailureReason: String? = null
    var requestPeersResult: List<WifiDirectPeer> = emptyList()
    var connectionSnapshot: WifiDirectConnectionSnapshot = WifiDirectConnectionSnapshot()
    var lastRolePreference: WifiDirectRolePreference? = null

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

    override fun connectToPeer(
        peer: WifiDirectPeer,
        rolePreference: WifiDirectRolePreference,
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    ) {
        connectToPeerCallCount += 1
        lastRolePreference = rolePreference
        connectFailureReason?.let(onFailure) ?: onSuccess()
    }

    override fun cancelPendingConnection(
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    ) {
        cancelPendingConnectionCallCount += 1
        cancelFailureReason?.let(onFailure) ?: onSuccess()
    }

    override fun disconnectFromPeer(
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    ) {
        disconnectFromPeerCallCount += 1
        disconnectFailureReason?.let(onFailure) ?: onSuccess()
    }

    override fun requestConnectionSnapshot(
        onSuccess: (WifiDirectConnectionSnapshot) -> Unit,
        onFailure: (String) -> Unit
    ) {
        requestConnectionSnapshotCallCount += 1
        requestConnectionSnapshotFailureReason?.let(onFailure) ?: onSuccess(connectionSnapshot)
    }
}
