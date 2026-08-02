package gr.hua.aurora.wifidirect.controller

import android.net.wifi.p2p.WifiP2pManager
import gr.hua.aurora.wifidirect.*
import gr.hua.aurora.wifidirect.model.WifiDirectPeer
import gr.hua.aurora.wifidirect.platform.WifiDirectPlatformClient
import gr.hua.aurora.wifidirect.platform.wifiDirectConnectRequestDebugText
import gr.hua.aurora.wifidirect.platform.wifiDirectGroupOwnerIntentOrNull
import gr.hua.aurora.wifidirect.runtime.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
            localDeviceInfoResult = WifiDirectLocalDeviceInfo(
                deviceName = "Aurora Local",
                deviceAddress = "AA:BB:CC:DD:EE:99"
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
        assertEquals(1, client.requestLocalDeviceInfoCallCount)
        assertEquals(1, client.requestPeersCallCount)
        assertEquals("Aurora Local", status.localDeviceInfo.deviceName)
        assertEquals("AA:BB:CC:DD:EE:99", status.localDeviceInfo.deviceAddress)
        assertTrue(status.localDeviceInfo.isAddressAvailable)
        assertTrue(observedStatuses.any { it.discoveryState == WifiDirectDiscoveryState.ACTIVE })
    }

    @Test
    fun localDeviceInfoFailureIsExposedSafely() {
        val permissionStatus = readyPermissionStatus()
        val client = FakeWifiDirectPlatformClient().apply {
            requestLocalDeviceInfoFailureReason = "SecurityException"
        }
        val controller = AndroidWifiDirectController(
            permissionStatusReader = { permissionStatus },
            fallbackPermissionStatus = { permissionStatus },
            platformClient = client,
            nowMillis = { 123L }
        )

        controller.refreshConnectionInfo()

        assertEquals(1, client.requestLocalDeviceInfoCallCount)
        assertEquals(
            "Wi-Fi Direct local device info unavailable: SecurityException",
            controller.currentRuntimeStatus().localDeviceInfo.lastError
        )
    }

    @Test
    fun anonymizedLocalDeviceAddressIsClassifiedAsUnavailableForCorrelation() {
        val permissionStatus = readyPermissionStatus()
        val client = FakeWifiDirectPlatformClient().apply {
            localDeviceInfoResult = WifiDirectLocalDeviceInfo(
                deviceName = "Aurora Local",
                deviceAddress = "02:00:00:00:00:00"
            )
        }
        val controller = AndroidWifiDirectController(
            permissionStatusReader = { permissionStatus },
            fallbackPermissionStatus = { permissionStatus },
            platformClient = client,
            nowMillis = { 321L }
        )

        controller.refreshConnectionInfo()

        val localInfo = controller.currentRuntimeStatus().localDeviceInfo
        assertEquals(
            WifiDirectLocalAddressClassification.ANONYMIZED,
            localInfo.addressClassification
        )
        assertEquals(false, localInfo.isAddressAvailable)
        assertNull(localInfo.lastError)
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
        assertEquals(1, client.requestLocalDeviceInfoCallCount)
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
        assertEquals(1, client.requestLocalDeviceInfoCallCount)
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
    fun registerAutomatedDiagnosticsServiceRegistersLocalDnsSdService() {
        val permissionStatus = readyPermissionStatus()
        val client = FakeWifiDirectPlatformClient()
        val controller = AndroidWifiDirectController(
            permissionStatusReader = { permissionStatus },
            fallbackPermissionStatus = { permissionStatus },
            platformClient = client,
            nowMillis = { 200L }
        )

        controller.registerAutomatedDiagnosticsService(
            correlationToken = "a1b2c3d4e5f60718293a4b5c6d7e8f90",
            deviceNameHint = "Participant Pixel"
        )

        val diagnostics = controller.currentRuntimeStatus().dnsSdDiagnostics
        assertEquals(1, client.clearLocalDnsSdServicesCallCount)
        assertEquals(1, client.addLocalDnsSdServiceCallCount)
        assertEquals(
            automatedDiagnosticsWifiDirectDnsSdInstanceName,
            client.lastLocalDnsSdInstanceName
        )
        assertEquals(
            automatedDiagnosticsWifiDirectDnsSdServiceType,
            client.lastLocalDnsSdServiceType
        )
        assertEquals(
            automatedDiagnosticsWifiDirectDnsSdProtocolVersion,
            client.lastLocalDnsSdTxtRecord[automatedDiagnosticsWifiDirectDnsSdProtocolTxtKey]
        )
        assertEquals(
            "a1b2c3d4e5f60718293a4b5c6d7e8f90",
            client.lastLocalDnsSdTxtRecord[automatedDiagnosticsWifiDirectDnsSdTokenTxtKey]
        )
        assertTrue(diagnostics.localServiceRegistered)
        assertFalse(diagnostics.cleanupCompleted)
        assertNull(diagnostics.lastError)
    }

    @Test
    fun startAutomatedDiagnosticsServiceDiscoveryRegistersRequestAndStartsDiscovery() {
        val permissionStatus = readyPermissionStatus()
        val client = FakeWifiDirectPlatformClient()
        val controller = AndroidWifiDirectController(
            permissionStatusReader = { permissionStatus },
            fallbackPermissionStatus = { permissionStatus },
            platformClient = client,
            nowMillis = { 201L }
        )

        controller.startAutomatedDiagnosticsServiceDiscovery()

        val diagnostics = controller.currentRuntimeStatus().dnsSdDiagnostics
        assertEquals(1, client.clearDnsSdServiceRequestsCallCount)
        assertEquals(1, client.addDnsSdServiceRequestCallCount)
        assertEquals(1, client.discoverDnsSdServicesCallCount)
        assertTrue(diagnostics.serviceRequestRegistered)
        assertTrue(diagnostics.discoveryStarted)
        assertFalse(diagnostics.cleanupCompleted)
        assertNotNull(client.dnsSdServiceListener)
        assertNotNull(client.dnsSdTxtListener)
    }

    @Test
    fun txtCallbackMapsToWifiDirectDnsSdServiceResponse() {
        val permissionStatus = readyPermissionStatus()
        val client = FakeWifiDirectPlatformClient()
        val controller = AndroidWifiDirectController(
            permissionStatusReader = { permissionStatus },
            fallbackPermissionStatus = { permissionStatus },
            platformClient = client,
            nowMillis = { 202L }
        )
        val peer = WifiDirectPeer(
            deviceName = "Participant Pixel",
            deviceAddress = "AA:BB:CC:DD:EE:20"
        )

        controller.startAutomatedDiagnosticsServiceDiscovery()
        client.dnsSdTxtListener?.invoke(
            "${automatedDiagnosticsWifiDirectDnsSdInstanceName}." +
                "${automatedDiagnosticsWifiDirectDnsSdServiceType}.local.",
            mapOf(
                automatedDiagnosticsWifiDirectDnsSdProtocolTxtKey to
                    automatedDiagnosticsWifiDirectDnsSdProtocolVersion,
                automatedDiagnosticsWifiDirectDnsSdTokenTxtKey to
                    "a1b2c3d4e5f60718293a4b5c6d7e8f90"
            ),
            peer
        )

        val service = controller.currentRuntimeStatus()
            .dnsSdDiagnostics
            .discoveredServices
            .single()
        assertEquals(automatedDiagnosticsWifiDirectDnsSdServiceType, service.serviceType)
        assertEquals(automatedDiagnosticsWifiDirectDnsSdInstanceName, service.instanceName)
        assertEquals(peer, service.peer)
        assertEquals(
            "a1b2c3d4e5f60718293a4b5c6d7e8f90",
            service.txtRecord[automatedDiagnosticsWifiDirectDnsSdTokenTxtKey]
        )
    }

    @Test
    fun duplicateTxtCallbackIsDeduplicated() {
        val permissionStatus = readyPermissionStatus()
        val client = FakeWifiDirectPlatformClient()
        val controller = AndroidWifiDirectController(
            permissionStatusReader = { permissionStatus },
            fallbackPermissionStatus = { permissionStatus },
            platformClient = client,
            nowMillis = { 203L }
        )
        val peer = WifiDirectPeer(
            deviceName = "Participant Pixel",
            deviceAddress = "AA:BB:CC:DD:EE:20"
        )
        val fullDomain =
            "${automatedDiagnosticsWifiDirectDnsSdInstanceName}." +
                "${automatedDiagnosticsWifiDirectDnsSdServiceType}.local."
        val txtRecord = mapOf(
            automatedDiagnosticsWifiDirectDnsSdProtocolTxtKey to
                automatedDiagnosticsWifiDirectDnsSdProtocolVersion,
            automatedDiagnosticsWifiDirectDnsSdTokenTxtKey to
                "a1b2c3d4e5f60718293a4b5c6d7e8f90"
        )

        controller.startAutomatedDiagnosticsServiceDiscovery()
        client.dnsSdTxtListener?.invoke(fullDomain, txtRecord, peer)
        client.dnsSdTxtListener?.invoke(fullDomain, txtRecord, peer)

        assertEquals(
            1,
            controller.currentRuntimeStatus().dnsSdDiagnostics.discoveredServices.size
        )
    }

    @Test
    fun clearAutomatedDiagnosticsServiceDiscoveryCompletesOnlyAfterBothCleanupsSucceed() {
        val permissionStatus = readyPermissionStatus()
        val client = FakeWifiDirectPlatformClient()
        val controller = AndroidWifiDirectController(
            permissionStatusReader = { permissionStatus },
            fallbackPermissionStatus = { permissionStatus },
            platformClient = client,
            nowMillis = { 204L }
        )

        controller.registerAutomatedDiagnosticsService(
            correlationToken = "a1b2c3d4e5f60718293a4b5c6d7e8f90",
            deviceNameHint = "Participant Pixel"
        )
        controller.startAutomatedDiagnosticsServiceDiscovery()
        client.autoCompleteClearLocalDnsSdServices = false
        client.autoCompleteClearDnsSdServiceRequests = false

        controller.clearAutomatedDiagnosticsServiceDiscovery()

        var diagnostics = controller.currentRuntimeStatus().dnsSdDiagnostics
        assertFalse(diagnostics.cleanupCompleted)
        assertFalse(diagnostics.localServiceRegistered)
        assertFalse(diagnostics.serviceRequestRegistered)
        assertFalse(diagnostics.discoveryStarted)

        client.completePendingClearLocalDnsSdServicesSuccess()
        diagnostics = controller.currentRuntimeStatus().dnsSdDiagnostics
        assertFalse(diagnostics.cleanupCompleted)

        client.completePendingClearDnsSdServiceRequestsSuccess()
        diagnostics = controller.currentRuntimeStatus().dnsSdDiagnostics
        assertTrue(diagnostics.cleanupCompleted)
        assertNull(diagnostics.lastError)
    }

    @Test
    fun clearAutomatedDiagnosticsServiceDiscoveryKeepsCleanupIncompleteWhenLocalServiceCleanupFails() {
        val permissionStatus = readyPermissionStatus()
        val client = FakeWifiDirectPlatformClient()
        val controller = AndroidWifiDirectController(
            permissionStatusReader = { permissionStatus },
            fallbackPermissionStatus = { permissionStatus },
            platformClient = client,
            nowMillis = { 205L }
        )

        controller.registerAutomatedDiagnosticsService(
            correlationToken = "a1b2c3d4e5f60718293a4b5c6d7e8f90",
            deviceNameHint = "Participant Pixel"
        )
        controller.startAutomatedDiagnosticsServiceDiscovery()
        client.autoCompleteClearLocalDnsSdServices = false
        client.autoCompleteClearDnsSdServiceRequests = false

        controller.clearAutomatedDiagnosticsServiceDiscovery()
        client.completePendingClearLocalDnsSdServicesFailure(WifiP2pManager.BUSY)
        client.completePendingClearDnsSdServiceRequestsSuccess()

        val diagnostics = controller.currentRuntimeStatus().dnsSdDiagnostics
        assertFalse(diagnostics.cleanupCompleted)
        assertEquals(
            "Wi-Fi Direct diagnostics local-service cleanup failed: busy",
            diagnostics.lastError
        )
    }

    @Test
    fun clearAutomatedDiagnosticsServiceDiscoveryKeepsCleanupIncompleteWhenServiceRequestCleanupFails() {
        val permissionStatus = readyPermissionStatus()
        val client = FakeWifiDirectPlatformClient()
        val controller = AndroidWifiDirectController(
            permissionStatusReader = { permissionStatus },
            fallbackPermissionStatus = { permissionStatus },
            platformClient = client,
            nowMillis = { 206L }
        )

        controller.registerAutomatedDiagnosticsService(
            correlationToken = "a1b2c3d4e5f60718293a4b5c6d7e8f90",
            deviceNameHint = "Participant Pixel"
        )
        controller.startAutomatedDiagnosticsServiceDiscovery()
        client.autoCompleteClearLocalDnsSdServices = false
        client.autoCompleteClearDnsSdServiceRequests = false

        controller.clearAutomatedDiagnosticsServiceDiscovery()
        client.completePendingClearLocalDnsSdServicesSuccess()
        client.completePendingClearDnsSdServiceRequestsFailure(WifiP2pManager.ERROR)

        val diagnostics = controller.currentRuntimeStatus().dnsSdDiagnostics
        assertFalse(diagnostics.cleanupCompleted)
        assertEquals(
            "Wi-Fi Direct diagnostics request cleanup failed: error",
            diagnostics.lastError
        )
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
    var requestLocalDeviceInfoCallCount: Int = 0
    var addLocalDnsSdServiceCallCount: Int = 0
    var clearLocalDnsSdServicesCallCount: Int = 0
    var addDnsSdServiceRequestCallCount: Int = 0
    var clearDnsSdServiceRequestsCallCount: Int = 0
    var discoverDnsSdServicesCallCount: Int = 0
    var discoverFailureReason: Int? = null
    var stopFailureReason: Int? = null
    var requestPeersFailureReason: String? = null
    var connectFailureReason: Int? = null
    var cancelFailureReason: Int? = null
    var disconnectFailureReason: Int? = null
    var requestConnectionSnapshotFailureReason: String? = null
    var requestLocalDeviceInfoFailureReason: String? = null
    var addLocalDnsSdServiceFailureReason: Int? = null
    var clearLocalDnsSdServicesFailureReason: Int? = null
    var addDnsSdServiceRequestFailureReason: Int? = null
    var clearDnsSdServiceRequestsFailureReason: Int? = null
    var discoverDnsSdServicesFailureReason: Int? = null
    var requestPeersResult: List<WifiDirectPeer> = emptyList()
    var connectionSnapshot: WifiDirectConnectionSnapshot = WifiDirectConnectionSnapshot()
    var localDeviceInfoResult: WifiDirectLocalDeviceInfo = WifiDirectLocalDeviceInfo()
    var lastRolePreference: WifiDirectRolePreference? = null
    var lastLocalDnsSdInstanceName: String? = null
    var lastLocalDnsSdServiceType: String? = null
    var lastLocalDnsSdTxtRecord: Map<String, String> = emptyMap()
    var dnsSdServiceListener: ((String?, String?, WifiDirectPeer) -> Unit)? = null
    var dnsSdTxtListener: ((String?, Map<String, String>, WifiDirectPeer) -> Unit)? = null
    var autoCompleteClearLocalDnsSdServices: Boolean = true
    var autoCompleteClearDnsSdServiceRequests: Boolean = true
    private var pendingClearLocalDnsSdServicesSuccess: (() -> Unit)? = null
    private var pendingClearLocalDnsSdServicesFailure: ((Int) -> Unit)? = null
    private var pendingClearDnsSdServiceRequestsSuccess: (() -> Unit)? = null
    private var pendingClearDnsSdServiceRequestsFailure: ((Int) -> Unit)? = null

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

    override fun requestLocalDeviceInfo(
        onSuccess: (WifiDirectLocalDeviceInfo) -> Unit,
        onFailure: (String) -> Unit
    ) {
        requestLocalDeviceInfoCallCount += 1
        requestLocalDeviceInfoFailureReason?.let(onFailure) ?: onSuccess(localDeviceInfoResult)
    }

    override fun setDnsSdResponseListeners(
        onServiceAvailable: (String?, String?, WifiDirectPeer) -> Unit,
        onTxtRecordAvailable: (String?, Map<String, String>, WifiDirectPeer) -> Unit,
        onFailure: (String) -> Unit
    ) {
        dnsSdServiceListener = onServiceAvailable
        dnsSdTxtListener = onTxtRecordAvailable
    }

    override fun addLocalDnsSdService(
        instanceName: String,
        serviceType: String,
        txtRecord: Map<String, String>,
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    ) {
        addLocalDnsSdServiceCallCount += 1
        lastLocalDnsSdInstanceName = instanceName
        lastLocalDnsSdServiceType = serviceType
        lastLocalDnsSdTxtRecord = txtRecord
        addLocalDnsSdServiceFailureReason?.let(onFailure) ?: onSuccess()
    }

    override fun clearLocalDnsSdServices(
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    ) {
        clearLocalDnsSdServicesCallCount += 1
        if (autoCompleteClearLocalDnsSdServices) {
            clearLocalDnsSdServicesFailureReason?.let(onFailure) ?: onSuccess()
            return
        }
        pendingClearLocalDnsSdServicesSuccess = onSuccess
        pendingClearLocalDnsSdServicesFailure = onFailure
    }

    override fun addDnsSdServiceRequest(
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    ) {
        addDnsSdServiceRequestCallCount += 1
        addDnsSdServiceRequestFailureReason?.let(onFailure) ?: onSuccess()
    }

    override fun clearDnsSdServiceRequests(
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    ) {
        clearDnsSdServiceRequestsCallCount += 1
        if (autoCompleteClearDnsSdServiceRequests) {
            clearDnsSdServiceRequestsFailureReason?.let(onFailure) ?: onSuccess()
            return
        }
        pendingClearDnsSdServiceRequestsSuccess = onSuccess
        pendingClearDnsSdServiceRequestsFailure = onFailure
    }

    override fun discoverDnsSdServices(
        onSuccess: () -> Unit,
        onFailure: (Int) -> Unit
    ) {
        discoverDnsSdServicesCallCount += 1
        discoverDnsSdServicesFailureReason?.let(onFailure) ?: onSuccess()
    }

    fun completePendingClearLocalDnsSdServicesSuccess() {
        val callback = pendingClearLocalDnsSdServicesSuccess
        pendingClearLocalDnsSdServicesSuccess = null
        pendingClearLocalDnsSdServicesFailure = null
        callback?.invoke()
    }

    fun completePendingClearLocalDnsSdServicesFailure(
        reason: Int
    ) {
        val callback = pendingClearLocalDnsSdServicesFailure
        pendingClearLocalDnsSdServicesSuccess = null
        pendingClearLocalDnsSdServicesFailure = null
        callback?.invoke(reason)
    }

    fun completePendingClearDnsSdServiceRequestsSuccess() {
        val callback = pendingClearDnsSdServiceRequestsSuccess
        pendingClearDnsSdServiceRequestsSuccess = null
        pendingClearDnsSdServiceRequestsFailure = null
        callback?.invoke()
    }

    fun completePendingClearDnsSdServiceRequestsFailure(
        reason: Int
    ) {
        val callback = pendingClearDnsSdServiceRequestsFailure
        pendingClearDnsSdServiceRequestsSuccess = null
        pendingClearDnsSdServiceRequestsFailure = null
        callback?.invoke(reason)
    }
}
