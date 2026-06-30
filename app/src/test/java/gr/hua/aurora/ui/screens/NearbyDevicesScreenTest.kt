package gr.hua.aurora.ui.screens

import gr.hua.aurora.ble.advertise.BleAdvertiseStatus
import gr.hua.aurora.ble.connection.BleConnectionStatus
import gr.hua.aurora.ble.discovery.BleDiscoveredDevice
import gr.hua.aurora.ble.discovery.BleScanDiagnostics
import gr.hua.aurora.ble.discovery.BleScanStatus
import gr.hua.aurora.ble.discovery.BleStablePeerId
import gr.hua.aurora.ble.gatt.BleGattServerStatus
import gr.hua.aurora.ble.permissions.BluetoothPermissionStatus
import gr.hua.aurora.model.AuroraContact
import gr.hua.aurora.model.PrivateChatIdentity
import gr.hua.aurora.protocol.PeerIdentityExchangeSendResult
import gr.hua.aurora.protocol.PeerSessionRegistryDiagnostics
import gr.hua.aurora.ui.components.DebugInfoCardModel
import gr.hua.aurora.ui.components.DebugInfoItem
import gr.hua.aurora.ui.components.DebugInfoSection
import gr.hua.aurora.wifidirect.WifiDirectDiscoveryState
import gr.hua.aurora.wifidirect.WifiDirectConnectionRole
import gr.hua.aurora.wifidirect.WifiDirectConnectionState
import gr.hua.aurora.wifidirect.WifiDirectConnectionStatus
import gr.hua.aurora.wifidirect.WifiDirectGroupFormedState
import gr.hua.aurora.wifidirect.WifiDirectPeer
import gr.hua.aurora.wifidirect.WifiDirectPermissionStatus
import gr.hua.aurora.wifidirect.WifiDirectRuntimeStatus
import gr.hua.aurora.wifidirect.WifiDirectSocketDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectSocketEndpoint
import gr.hua.aurora.wifidirect.WifiDirectSocketRole
import gr.hua.aurora.wifidirect.WifiDirectSocketState
import gr.hua.aurora.wifidirect.WifiDirectTransportState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NearbyDevicesScreenTest {
    @Test
    fun nearbySelectedSecurePeerTextShowsSelectionState() {
        assertEquals(
            "Secure peer selected: peer-123",
            nearbySelectedSecurePeerText("peer-123")
        )
        assertEquals(
            "No secure peer selected for mesh delivery.",
            nearbySelectedSecurePeerText(null)
        )
    }

    @Test
    fun loadsLocalIdentityExchangeMaterialWithDerivedPeerIdAndDefensiveCopy() {
        val publicKeyBytes = byteArrayOf(
            0x04,
            0x01, 0x02, 0x03, 0x04,
            0x05, 0x06, 0x07, 0x08,
            0x09, 0x0A, 0x0B, 0x0C,
            0x0D, 0x0E, 0x0F, 0x10
        )

        val material = loadLocalPeerIdentityExchangePublicMaterialOrNull {
            publicKeyBytes.copyOf()
        }

        val expectedPeerId = BleStablePeerId.deriveFromPublicKeyBytes(publicKeyBytes)
            .toByteArray()
            .joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xFF)
            }

        assertEquals(expectedPeerId, requireNotNull(material).peerId)
        assertArrayEquals(publicKeyBytes, material.publicAgreementKeyBytes())

        val copiedBytes = material.publicAgreementKeyBytes()
        copiedBytes[0] = 0x00

        assertArrayEquals(publicKeyBytes, material.publicAgreementKeyBytes())
    }

    @Test
    fun missingLocalIdentityExchangeMaterialReturnsNull() {
        val material = loadLocalPeerIdentityExchangePublicMaterialOrNull {
            null
        }

        assertNull(material)
    }

    @Test
    fun localIdentityExchangePublicMaterialResultReportsUnavailable() {
        val result = loadLocalPeerIdentityExchangePublicMaterialResult {
            null
        }

        assertEquals(
            LocalPeerIdentityExchangePublicMaterialLoadResult.Unavailable(
                reason = "Local agreement public key unavailable."
            ),
            result
        )
    }

    @Test
    fun nearbyBleDeviceIdentityKeyPrefersStablePeerId() {
        val stablePeerId = BleStablePeerId.fromBytes(
            byteArrayOf(0x10, 0x32, 0x54, 0x76, 0x11, 0x22, 0x33, 0x44)
        )
        val device = BleDiscoveredDevice(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Aurora peer",
            rssi = -45,
            isConnectable = true,
            hasAuroraDiscoveryPayload = true,
            stablePeerId = stablePeerId
        )

        assertEquals("1032547611223344", nearbyBleDeviceIdentityKey(device))
    }

    @Test
    fun nearbyIdentityExchangeStatusTextUsesTruthfulTransportWording() {
        assertEquals(
            "Identity sent. Run on both devices.",
            nearbyIdentityExchangeStatusText(PeerIdentityExchangeSendResult.SubmittedLocally)
        )
        assertEquals(
            "Identity exchange unavailable.",
            nearbyIdentityExchangeStatusText(PeerIdentityExchangeSendResult.SenderUnavailable)
        )
        assertEquals(
            "Local identity material unavailable.",
            nearbyIdentityExchangeStatusText(
                PeerIdentityExchangeSendResult.InvalidLocalIdentity(
                    reason = "Local identity material unavailable."
                )
            )
        )
        assertEquals(
            "Identity exchange failed: writer unavailable",
            nearbyIdentityExchangeStatusText(
                PeerIdentityExchangeSendResult.Failed(
                    reason = "writer unavailable"
                )
            )
        )
    }

    @Test
    fun nearbyContactDisplayNamePrefersNameThenStablePeerFallback() {
        val namedDevice = BleDiscoveredDevice(
            address = "AA:BB:CC:DD:EE:FF",
            name = " Alex tablet ",
            rssi = -45,
            isConnectable = true,
            hasAuroraDiscoveryPayload = true,
            stablePeerId = BleStablePeerId.fromBytes(
                byteArrayOf(0x10, 0x32, 0x54, 0x76, 0x11, 0x22, 0x33, 0x44)
            )
        )
        val unnamedAuroraDevice = namedDevice.copy(name = null)
        val unknownDevice = namedDevice.copy(
            name = null,
            hasAuroraDiscoveryPayload = false,
            stablePeerId = null
        )

        assertEquals("Aurora device 10325476", nearbyContactDisplayName(namedDevice))
        assertEquals("Aurora device 10325476", nearbyContactDisplayName(unnamedAuroraDevice))
        assertEquals("Unknown BLE device", nearbyContactDisplayName(unknownDevice))
    }

    @Test
    fun nearbyContactStatusTextReflectsContactAndKeyReadiness() {
        assertNull(
            nearbyContactStatusText(
                isContact = false,
                hasReadyKeys = false,
                hasPrivateChatSetup = false
            )
        )
        assertEquals(
            "Setup needed",
            nearbyContactStatusText(
                isContact = true,
                hasReadyKeys = false,
                hasPrivateChatSetup = false
            )
        )
        assertEquals(
            "Retry setup",
            nearbyContactStatusText(
                isContact = true,
                hasReadyKeys = false,
                hasPrivateChatSetup = true
            )
        )
        assertEquals(
            "Private chat ready",
            nearbyContactStatusText(
                isContact = true,
                hasReadyKeys = true,
                hasPrivateChatSetup = true
            )
        )
    }

    @Test
    fun nearbyProductStatusTextKeepsNormalModeCompact() {
        assertNull(
            nearbyProductStatusText(
                isContact = false,
                hasReadyKeys = false,
                hasPrivateChatSetup = false,
                isAuroraDevice = true
            )
        )
        assertEquals(
            "Setup needed",
            nearbyProductStatusText(
                isContact = true,
                hasReadyKeys = false,
                hasPrivateChatSetup = false,
                isAuroraDevice = true
            )
        )
        assertEquals(
            "Retry setup",
            nearbyProductStatusText(
                isContact = true,
                hasReadyKeys = false,
                hasPrivateChatSetup = true,
                isAuroraDevice = true
            )
        )
        assertEquals(
            "Private chat ready",
            nearbyProductStatusText(
                isContact = true,
                hasReadyKeys = true,
                hasPrivateChatSetup = true,
                isAuroraDevice = true
            )
        )
    }

    @Test
    fun nearbyNormalModeHidesManualConnectDisconnectAndExchangeKeys() {
        val device = BleDiscoveredDevice(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Aurora",
            rssi = -45,
            isConnectable = true,
            hasAuroraDiscoveryPayload = true,
            stablePeerId = BleStablePeerId.fromBytes(
                byteArrayOf(0x10, 0x32, 0x54, 0x76, 0x11, 0x22, 0x33, 0x44)
            )
        )

        val visibility = nearbyRowActionVisibility(
            device = device,
            existingContact = null,
            isPrivateChatReady = false,
            connectionStatus = BleConnectionStatus.CONNECTED,
            activeConnectionDeviceAddress = device.address,
            showDebugActions = false
        )

        assertEquals(false, visibility.showConnect)
        assertEquals(false, visibility.showDisconnect)
        assertEquals(false, visibility.showExchangeIdentity)
        assertEquals(true, visibility.showAddContact)
        assertEquals(false, visibility.showOpenChat)
    }

    @Test
    fun nearbyDebugModeKeepsManualTransportAndIdentityControls() {
        val device = BleDiscoveredDevice(
            address = "AA:BB:CC:DD:EE:FF",
            name = "Aurora",
            rssi = -45,
            isConnectable = true,
            hasAuroraDiscoveryPayload = true,
            stablePeerId = BleStablePeerId.fromBytes(
                byteArrayOf(0x10, 0x32, 0x54, 0x76, 0x11, 0x22, 0x33, 0x44)
            )
        )

        val visibility = nearbyRowActionVisibility(
            device = device,
            existingContact = null,
            isPrivateChatReady = false,
            connectionStatus = BleConnectionStatus.CONNECTED,
            activeConnectionDeviceAddress = device.address,
            showDebugActions = true
        )

        assertEquals(false, visibility.showAddContact)
        assertEquals(false, visibility.showOpenChat)
        assertEquals(true, visibility.showDisconnect)
        assertEquals(true, visibility.showExchangeIdentity)
        assertEquals(true, visibility.showReadTransportMarker)
        assertEquals(true, visibility.showReadTransportFrame)
        assertEquals(true, visibility.showWriteTransportMarker)
    }

    @Test
    fun nearbyAddContactActionLabelUsesRetryForRestartRecovery() {
        val contact = AuroraContact(
            canonicalPeerId = "peer-123",
            displayName = "Alex",
            createdAtMillis = 1_000L,
            hasSession = false
        )
        val establishedIdentity = PrivateChatIdentity(
            canonicalPeerId = "peer-123",
            privateChatId = "chat-123",
            localProposalId = "local-123",
            remoteProposalId = "remote-123",
            createdAtMillis = 1_000L,
            lastUpdatedMillis = 2_000L
        )

        assertEquals("Add contact", nearbyAddContactActionLabel(existingContact = null, identity = null))
        assertEquals(
            "Finish setup",
            nearbyAddContactActionLabel(existingContact = contact, identity = null)
        )
        assertEquals(
            "Retry setup",
            nearbyAddContactActionLabel(existingContact = contact, identity = establishedIdentity)
        )
    }

    @Test
    fun nearbyPeerHasReadyKeysRecognizesDirectAndAliasedSessions() {
        val diagnostics = PeerSessionRegistryDiagnostics(
            establishedPeerIds = listOf("peer-canonical"),
            canonicalPeerIdByAlias = mapOf("peer-legacy" to "peer-canonical")
        )

        assertEquals(true, nearbyPeerHasReadyKeys("peer-canonical", diagnostics))
        assertEquals(true, nearbyPeerHasReadyKeys("peer-legacy", diagnostics))
        assertEquals(false, nearbyPeerHasReadyKeys("peer-missing", diagnostics))
        assertEquals(false, nearbyPeerHasReadyKeys(null, diagnostics))
    }

    @Test
    fun nearbyOpenChatPeerIdUsesExistingContactEntry() {
        val contact = AuroraContact(
            canonicalPeerId = "peer-123",
            displayName = "Alex",
            createdAtMillis = 1000L,
            hasSession = true
        )

        assertEquals("peer-123", nearbyOpenChatPeerId(contact))
        assertNull(nearbyOpenChatPeerId(null))
    }

    @Test
    fun nearbyPeerSessionDiagnosticsTextShowsDirectAndAliasBackedReadiness() {
        val directDiagnostics = PeerSessionRegistryDiagnostics(
            establishedPeerIds = listOf("peer-123"),
            canonicalPeerIdByAlias = emptyMap()
        )
        val aliasDiagnostics = PeerSessionRegistryDiagnostics(
            establishedPeerIds = listOf("peer-canonical"),
            canonicalPeerIdByAlias = mapOf("peer-legacy" to "peer-canonical")
        )

        assertEquals(
            "Selected peer session: ready",
            nearbyPeerSessionStatusText(
                label = "Selected peer session",
                peerId = "peer-123",
                diagnostics = directDiagnostics
            )
        )
        assertEquals(
            "Active peer session: ready (mapped to peer-canonical)",
            nearbyPeerSessionStatusText(
                label = "Active peer session",
                peerId = "peer-legacy",
                diagnostics = aliasDiagnostics
            )
        )
        assertEquals(
            "Established session peers (1): peer-canonical",
            nearbyEstablishedSessionPeersText(aliasDiagnostics)
        )
        assertEquals(
            "Session peer aliases: peer-legacy -> peer-canonical",
            nearbySessionAliasText(aliasDiagnostics)
        )
    }

    @Test
    fun nearbyExpandedDebugSectionsAreHiddenWhenDebugModeIsDisabled() {
        val sections = buildNearbyExpandedDebugSections(
            showDebugDiagnostics = false,
            advertiseStatus = BleAdvertiseStatus.ADVERTISING,
            gattServerStatus = BleGattServerStatus.HOSTING,
            scanStatus = BleScanStatus.SCANNING,
            transportSenderSourceLabel = "Android connector-backed",
            activeTransportPeerId = "peer-123",
            connectionStatus = BleConnectionStatus.CONNECTED,
            transportReadStatus = NearbyBleTransportReadStatus.IDLE,
            transportFrameReadStatus = NearbyBleTransportFrameReadStatus.IDLE,
            transportWriteStatus = NearbyBleTransportWriteStatus.IDLE,
            scanDiagnostics = BleScanDiagnostics(),
            peerSessionDiagnostics = PeerSessionRegistryDiagnostics(
                establishedPeerIds = listOf("peer-123"),
                canonicalPeerIdByAlias = emptyMap()
            ),
            selectedSecurePeerId = "peer-123",
            activeSessionPeerId = "peer-123",
            lastIdentityExchangeStatus = "Identity sent."
        )

        assertEquals(emptyList<DebugInfoSection>(), sections)
    }

    @Test
    fun nearbyReadinessHeadlineIsCompactWhenReadyInNormalMode() {
        val bluetoothStatus = BluetoothPermissionStatus(
            requiredPermissions = emptySet(),
            missingPermissions = emptySet(),
            isBluetoothEnabled = true,
            isLocationEnabled = true
        )

        assertEquals(
            "Device readiness: Ready",
            nearbyReadinessHeadline(
                bluetoothStatus = bluetoothStatus,
                showDebugDetails = false
            )
        )
        assertEquals(
            emptyList<String>(),
            nearbyReadinessDetailLines(
                bluetoothStatus = bluetoothStatus,
                showDebugDetails = false
            )
        )
    }

    @Test
    fun nearbyReadinessHeadlineShowsCompactProblemsWhenNotReadyInNormalMode() {
        val bluetoothStatus = BluetoothPermissionStatus(
            requiredPermissions = setOf("permission"),
            missingPermissions = setOf("permission"),
            isBluetoothEnabled = false,
            isLocationEnabled = false
        )

        assertEquals(
            "Device readiness: Permissions missing, Bluetooth off, Location/GPS off",
            nearbyReadinessHeadline(
                bluetoothStatus = bluetoothStatus,
                showDebugDetails = false
            )
        )
    }

    @Test
    fun nearbyDebugCardIsHiddenWhenDebugModeIsDisabled() {
        assertNull(
            buildNearbyDebugCard(
                showDebugDiagnostics = false,
                advertiseStatus = BleAdvertiseStatus.ADVERTISING,
                gattServerStatus = BleGattServerStatus.HOSTING,
                scanStatus = BleScanStatus.SCANNING,
                wifiDirectRuntimeStatus = wifiDirectRuntimeStatus(),
                wifiDirectSocketDiagnostics = WifiDirectSocketDiagnostics(),
                identityHandlerStatus = "Identity handler ready.",
                peerSessionDiagnostics = PeerSessionRegistryDiagnostics(
                    establishedPeerIds = emptyList(),
                    canonicalPeerIdByAlias = emptyMap()
                )
            )
        )
    }

    @Test
    fun nearbyDebugCardShowsSingleGroupedStructure() {
        val diagnostics = PeerSessionRegistryDiagnostics(
            establishedPeerIds = listOf("peer-canonical"),
            canonicalPeerIdByAlias = mapOf("peer-legacy" to "peer-canonical")
        )

        assertEquals(
            DebugInfoCardModel(
                title = "Debug",
                sections = listOf(
                    DebugInfoSection(
                        title = "Runtime",
                        items = listOf(
                            DebugInfoItem("Mode", "Full mesh"),
                            DebugInfoItem("Scan", "Scanning")
                        )
                    ),
                    DebugInfoSection(
                        title = "Wi-Fi Direct",
                        items = listOf(
                            DebugInfoItem("Supported", "yes"),
                            DebugInfoItem("Permissions", "granted"),
                            DebugInfoItem("Wi-Fi/P2P", "enabled"),
                            DebugInfoItem("Discovery", "inactive"),
                            DebugInfoItem("Transport", "not wired yet"),
                            DebugInfoItem("Connection", "disconnected"),
                            DebugInfoItem("Group", "unknown"),
                            DebugInfoItem("Role", "unknown"),
                            DebugInfoItem("Peers", "0")
                        )
                    ),
                    DebugInfoSection(
                        title = "Socket",
                        items = listOf(
                            DebugInfoItem("Socket", "idle"),
                            DebugInfoItem("Role", "unknown"),
                            DebugInfoItem("Connected", "no"),
                            DebugInfoItem("Endpoint", "port 8988"),
                            DebugInfoItem("Sent", "none"),
                            DebugInfoItem("Received", "none"),
                            DebugInfoItem("Bytes", "0/0"),
                            DebugInfoItem(
                                "Note",
                                "Wi-Fi Direct chat transport not wired yet.",
                                preferFullWidth = true
                            )
                        )
                    ),
                    DebugInfoSection(
                        title = "Frame",
                        items = listOf(
                            DebugInfoItem("Transport", "idle"),
                            DebugInfoItem("Frames", "0/0"),
                            DebugInfoItem("Bytes", "0/0"),
                            DebugInfoItem("Last size", "none"),
                            DebugInfoItem(
                                "Note",
                                "Wi-Fi Direct chat routing not wired yet.",
                                preferFullWidth = true
                            )
                        )
                    ),
                    DebugInfoSection(
                        title = "Identity",
                        items = listOf(
                            DebugInfoItem("Handler", "ready"),
                            DebugInfoItem("Sessions", "1")
                        )
                    )
                )
            ),
            buildNearbyDebugCard(
                showDebugDiagnostics = true,
                advertiseStatus = BleAdvertiseStatus.ADVERTISING,
                gattServerStatus = BleGattServerStatus.HOSTING,
                scanStatus = BleScanStatus.SCANNING,
                wifiDirectRuntimeStatus = wifiDirectRuntimeStatus(),
                wifiDirectSocketDiagnostics = WifiDirectSocketDiagnostics(),
                identityHandlerStatus = "Identity handler ready. Local agreement private key loaded.",
                peerSessionDiagnostics = diagnostics
            )
        )
    }

    @Test
    fun nearbyWifiDirectDebugSectionShowsSafePlaceholderState() {
        assertEquals(
            DebugInfoSection(
                title = "Wi-Fi Direct",
                items = listOf(
                    DebugInfoItem("Supported", "yes"),
                    DebugInfoItem("Permissions", "missing"),
                    DebugInfoItem("Missing", "NEARBY_WIFI_DEVICES", preferFullWidth = true),
                    DebugInfoItem("Wi-Fi/P2P", "unknown"),
                    DebugInfoItem("Discovery", "inactive"),
                    DebugInfoItem("Transport", "not wired yet"),
                    DebugInfoItem("Connection", "disconnected"),
                    DebugInfoItem("Group", "unknown"),
                    DebugInfoItem("Role", "unknown"),
                    DebugInfoItem("Peers", "0"),
                    DebugInfoItem(
                        "Last error",
                        "Wi-Fi Direct status unavailable: RuntimeException",
                        preferFullWidth = true
                    )
                )
            ),
            buildNearbyWifiDirectDebugSection(
                runtimeStatus = wifiDirectRuntimeStatus(
                    missingPermissions = setOf("android.permission.NEARBY_WIFI_DEVICES"),
                    isWifiEnabled = null,
                    lastError = "Wi-Fi Direct status unavailable: RuntimeException"
                )
            )
        )
    }

    @Test
    fun nearbyWifiDirectDebugSectionShowsSafePeerDetailsWhenDiscovered() {
        assertEquals(
            DebugInfoSection(
                title = "Wi-Fi Direct",
                items = listOf(
                    DebugInfoItem("Supported", "yes"),
                    DebugInfoItem("Permissions", "granted"),
                    DebugInfoItem("Wi-Fi/P2P", "enabled"),
                    DebugInfoItem("Discovery", "active"),
                    DebugInfoItem("Transport", "not wired yet"),
                    DebugInfoItem("Connection", "disconnected"),
                    DebugInfoItem("Group", "unknown"),
                    DebugInfoItem("Role", "unknown"),
                    DebugInfoItem("Peers", "2"),
                    DebugInfoItem(
                        "Devices",
                        "Aurora Alpha (AA:BB:CC:DD:EE:01), unnamed (AA:BB:CC:DD:EE:02)",
                        preferFullWidth = true
                    )
                )
            ),
            buildNearbyWifiDirectDebugSection(
                runtimeStatus = wifiDirectRuntimeStatus(
                    discoveryState = WifiDirectDiscoveryState.ACTIVE,
                    peers = listOf(
                        WifiDirectPeer(
                            deviceName = "Aurora Alpha",
                            deviceAddress = "AA:BB:CC:DD:EE:01"
                        ),
                        WifiDirectPeer(
                            deviceName = null,
                            deviceAddress = "AA:BB:CC:DD:EE:02"
                        )
                    )
                )
            )
        )
    }

    @Test
    fun nearbyWifiDirectDebugControlsFollowDiscoveryState() {
        assertEquals(
            NearbyWifiDirectDebugControlsState(
                canStartDiscovery = true,
                canStopDiscovery = true,
                canDisconnect = false,
                disconnectLabel = "Disconnect Wi-Fi Direct",
                startDisabledReason = null
            ),
            nearbyWifiDirectDebugControlsState(
                wifiDirectRuntimeStatus(
                    discoveryState = WifiDirectDiscoveryState.INACTIVE
                )
            )
        )
        assertEquals(
            NearbyWifiDirectDebugControlsState(
                canStartDiscovery = false,
                canStopDiscovery = true,
                canDisconnect = false,
                disconnectLabel = "Disconnect Wi-Fi Direct",
                startDisabledReason = "Wi-Fi Direct discovery already active."
            ),
            nearbyWifiDirectDebugControlsState(
                wifiDirectRuntimeStatus(
                    discoveryState = WifiDirectDiscoveryState.ACTIVE
                )
            )
        )
    }

    @Test
    fun nearbyWifiDirectDebugControlsDisableStartWhenPermissionMissing() {
        assertEquals(
            NearbyWifiDirectDebugControlsState(
                canStartDiscovery = false,
                canStopDiscovery = true,
                canDisconnect = false,
                disconnectLabel = "Disconnect Wi-Fi Direct",
                startDisabledReason = "Missing Nearby Wi-Fi permission."
            ),
            nearbyWifiDirectDebugControlsState(
                wifiDirectRuntimeStatus(
                    missingPermissions = setOf("android.permission.NEARBY_WIFI_DEVICES")
                )
            )
        )
    }

    @Test
    fun nearbyWifiDirectDebugSectionShowsConnectionDetailsWhenPresent() {
        val peer = WifiDirectPeer(
            deviceName = "Aurora Alpha",
            deviceAddress = "AA:BB:CC:DD:EE:01"
        )

        assertEquals(
            DebugInfoSection(
                title = "Wi-Fi Direct",
                items = listOf(
                    DebugInfoItem("Supported", "yes"),
                    DebugInfoItem("Permissions", "granted"),
                    DebugInfoItem("Wi-Fi/P2P", "enabled"),
                    DebugInfoItem("Discovery", "inactive"),
                    DebugInfoItem("Transport", "not wired yet"),
                    DebugInfoItem("Connection", "connected"),
                    DebugInfoItem("Target", "Aurora Alpha (AA:BB:CC:DD:EE:01)", preferFullWidth = true),
                    DebugInfoItem("Group", "yes"),
                    DebugInfoItem("Role", "group owner"),
                    DebugInfoItem("Owner", "192.168.49.1", preferFullWidth = true),
                    DebugInfoItem("Peers", "1"),
                    DebugInfoItem(
                        "Devices",
                        "Aurora Alpha (AA:BB:CC:DD:EE:01)",
                        preferFullWidth = true
                    ),
                    DebugInfoItem(
                        "Connect error",
                        "Wi-Fi Direct connect failed: busy",
                        preferFullWidth = true
                    )
                )
            ),
            buildNearbyWifiDirectDebugSection(
                runtimeStatus = wifiDirectRuntimeStatus(
                    peers = listOf(peer),
                    connectionStatus = WifiDirectConnectionStatus(
                        state = WifiDirectConnectionState.CONNECTED,
                        targetPeer = peer,
                        groupFormed = WifiDirectGroupFormedState.YES,
                        role = WifiDirectConnectionRole.GROUP_OWNER,
                        groupOwnerAddress = "192.168.49.1",
                        lastError = "Wi-Fi Direct connect failed: busy"
                    )
                )
            )
        )
    }

    @Test
    fun nearbyWifiDirectSocketDebugSectionShowsCompactDiagnostics() {
        assertEquals(
            DebugInfoSection(
                title = "Socket",
                items = listOf(
                    DebugInfoItem("Socket", "connected"),
                    DebugInfoItem("Role", "client"),
                    DebugInfoItem("Connected", "yes"),
                    DebugInfoItem("Endpoint", "192.168.49.1:8988"),
                    DebugInfoItem("Sent", "ping"),
                    DebugInfoItem("Received", "pong"),
                    DebugInfoItem("Bytes", "8/8"),
                    DebugInfoItem(
                        "Note",
                        "Wi-Fi Direct chat transport not wired yet.",
                        preferFullWidth = true
                    )
                )
            ),
            buildNearbyWifiDirectSocketDebugSection(
                diagnostics = WifiDirectSocketDiagnostics(
                    state = WifiDirectSocketState.CONNECTED,
                    role = WifiDirectSocketRole.CLIENT,
                    endpoint = WifiDirectSocketEndpoint(
                        host = "192.168.49.1",
                        port = 8988
                    ),
                    isConnected = true,
                    lastSentMessage = "ping",
                    lastReceivedMessage = "pong",
                    bytesSent = 8,
                    bytesReceived = 8
                )
            )
        )
    }

    @Test
    fun nearbyWifiDirectFrameDebugSectionShowsCompactDiagnostics() {
        assertEquals(
            DebugInfoSection(
                title = "Frame",
                items = listOf(
                    DebugInfoItem("Transport", "ready"),
                    DebugInfoItem("Frames", "1/1"),
                    DebugInfoItem("Bytes", "8/8"),
                    DebugInfoItem("Last size", "4 B"),
                    DebugInfoItem(
                        "Note",
                        "Wi-Fi Direct chat routing not wired yet.",
                        preferFullWidth = true
                    )
                )
            ),
            buildNearbyWifiDirectFrameDebugSection(
                diagnostics = WifiDirectSocketDiagnostics(
                    frameDiagnostics = gr.hua.aurora.wifidirect.WifiDirectFrameDiagnostics(
                        state = gr.hua.aurora.wifidirect.WifiDirectFrameTransportState.READY,
                        framesSent = 1,
                        framesReceived = 1,
                        bytesSent = 8,
                        bytesReceived = 8,
                        lastFrameSize = 4
                    )
                )
            )
        )
    }

    @Test
    fun nearbyWifiDirectPeerActionStateReflectsConnectAndDisconnectProgress() {
        val peer = WifiDirectPeer(
            deviceName = "Aurora Alpha",
            deviceAddress = "AA:BB:CC:DD:EE:01"
        )

        assertEquals(
            NearbyWifiDirectPeerActionState(
                connectLabel = "Connect",
                canConnect = true
            ),
            nearbyWifiDirectPeerActionState(
                runtimeStatus = wifiDirectRuntimeStatus(
                    peers = listOf(peer)
                ),
                peer = peer
            )
        )
        assertEquals(
            NearbyWifiDirectPeerActionState(
                connectLabel = "Connected",
                canConnect = false
            ),
            nearbyWifiDirectPeerActionState(
                runtimeStatus = wifiDirectRuntimeStatus(
                    peers = listOf(peer),
                    connectionStatus = WifiDirectConnectionStatus(
                        state = WifiDirectConnectionState.CONNECTED,
                        targetPeer = peer,
                        groupFormed = WifiDirectGroupFormedState.YES,
                        role = WifiDirectConnectionRole.CLIENT
                    )
                ),
                peer = peer
            )
        )
        assertEquals(
            NearbyWifiDirectPeerActionState(
                connectLabel = "Disconnecting",
                canConnect = false,
                disabledReason = "Wi-Fi Direct disconnect already in progress."
            ),
            nearbyWifiDirectPeerActionState(
                runtimeStatus = wifiDirectRuntimeStatus(
                    peers = listOf(peer),
                    connectionStatus = WifiDirectConnectionStatus(
                        state = WifiDirectConnectionState.DISCONNECTING,
                        targetPeer = peer
                    )
                ),
                peer = peer
            )
        )
    }

    @Test
    fun nearbyWifiDirectSocketControlsRequireFormedGroupBeforeEnablingSocketActions() {
        assertEquals(
            NearbyWifiDirectSocketControlsState(
                canStartServer = false,
                canConnectClient = false,
                canSendFrame = false,
                canCloseSocket = false,
                helpText = "Wi-Fi Direct group not formed."
            ),
            nearbyWifiDirectSocketControlsState(
                runtimeStatus = wifiDirectRuntimeStatus(),
                diagnostics = WifiDirectSocketDiagnostics()
            )
        )
    }

    @Test
    fun nearbyWifiDirectSocketControlsEnableServerForGroupOwner() {
        assertEquals(
            NearbyWifiDirectSocketControlsState(
                canStartServer = true,
                canConnectClient = false,
                canSendFrame = false,
                canCloseSocket = false
            ),
            nearbyWifiDirectSocketControlsState(
                runtimeStatus = wifiDirectRuntimeStatus(
                    connectionStatus = WifiDirectConnectionStatus(
                        state = WifiDirectConnectionState.CONNECTED,
                        groupFormed = WifiDirectGroupFormedState.YES,
                        role = WifiDirectConnectionRole.GROUP_OWNER
                    )
                ),
                diagnostics = WifiDirectSocketDiagnostics()
            )
        )
    }

    @Test
    fun nearbyWifiDirectSocketControlsEnableClientForKnownOwnerAddress() {
        assertEquals(
            NearbyWifiDirectSocketControlsState(
                canStartServer = false,
                canConnectClient = true,
                canSendFrame = false,
                canCloseSocket = false,
                connectHost = "192.168.49.1"
            ),
            nearbyWifiDirectSocketControlsState(
                runtimeStatus = wifiDirectRuntimeStatus(
                    connectionStatus = WifiDirectConnectionStatus(
                        state = WifiDirectConnectionState.CONNECTED,
                        groupFormed = WifiDirectGroupFormedState.YES,
                        role = WifiDirectConnectionRole.CLIENT,
                        groupOwnerAddress = "192.168.49.1"
                    )
                ),
                diagnostics = WifiDirectSocketDiagnostics()
            )
        )
    }

    @Test
    fun nearbyWifiDirectSocketControlsShowClearReasonWhenRoleOrAddressIsUnavailable() {
        assertEquals(
            NearbyWifiDirectSocketControlsState(
                canStartServer = false,
                canConnectClient = false,
                canSendFrame = false,
                canCloseSocket = false,
                helpText = "Wi-Fi Direct role unavailable."
            ),
            nearbyWifiDirectSocketControlsState(
                runtimeStatus = wifiDirectRuntimeStatus(
                    connectionStatus = WifiDirectConnectionStatus(
                        state = WifiDirectConnectionState.CONNECTED,
                        groupFormed = WifiDirectGroupFormedState.YES,
                        role = WifiDirectConnectionRole.UNKNOWN
                    )
                ),
                diagnostics = WifiDirectSocketDiagnostics()
            )
        )
        assertEquals(
            NearbyWifiDirectSocketControlsState(
                canStartServer = false,
                canConnectClient = false,
                canSendFrame = false,
                canCloseSocket = false,
                connectHost = null,
                helpText = "Group owner address unavailable."
            ),
            nearbyWifiDirectSocketControlsState(
                runtimeStatus = wifiDirectRuntimeStatus(
                    connectionStatus = WifiDirectConnectionStatus(
                        state = WifiDirectConnectionState.CONNECTED,
                        groupFormed = WifiDirectGroupFormedState.YES,
                        role = WifiDirectConnectionRole.CLIENT,
                        groupOwnerAddress = null
                    )
                ),
                diagnostics = WifiDirectSocketDiagnostics()
            )
        )
    }

    @Test
    fun nearbyExpandedDebugCardKeepsVerboseRuntimeTransportAndIdentityDetails() {
        val diagnostics = PeerSessionRegistryDiagnostics(
            establishedPeerIds = listOf("peer-canonical"),
            canonicalPeerIdByAlias = mapOf("peer-legacy" to "peer-canonical")
        )
        val card = buildNearbyExpandedDebugCard(
            showDebugDiagnostics = true,
            advertiseStatus = BleAdvertiseStatus.ADVERTISING,
            gattServerStatus = BleGattServerStatus.HOSTING,
            scanStatus = BleScanStatus.SCANNING,
            transportSenderSourceLabel = "Android connector-backed",
            activeTransportPeerId = "peer-legacy",
            connectionStatus = BleConnectionStatus.CONNECTED,
            transportReadStatus = NearbyBleTransportReadStatus.IDLE,
            transportFrameReadStatus = NearbyBleTransportFrameReadStatus.FRAME_AVAILABLE,
            transportWriteStatus = NearbyBleTransportWriteStatus.ACCEPTED,
            scanDiagnostics = BleScanDiagnostics(
                rawScanResultCount = 7,
                auroraDiscoveryMatchCount = 2,
                lastDeviceName = "Aurora peer",
                lastDeviceAddress = "AA:BB:CC:DD:EE:FF",
                lastRssi = -45,
                lastHadDiscoveryServiceData = true,
                lastHadAuroraDiscoveryPayload = true
            ),
            peerSessionDiagnostics = diagnostics,
            selectedSecurePeerId = "peer-legacy",
            activeSessionPeerId = "peer-legacy",
            lastIdentityExchangeStatus = "Identity sent. Run on both devices."
        )

        assertEquals(
            "BLE diagnostics",
            requireNotNull(card).title
        )
        assertEquals(
            listOf(
                "Runtime details",
                "Transport",
                "Scan details",
                "Identity details"
            ),
            card.sections.map { it.title }
        )
        assertEquals(
            listOf(
                DebugInfoItem("Advertise", "Active"),
                DebugInfoItem("GATT", "Hosting"),
                DebugInfoItem("Scan", "Scanning")
            ),
            card.sections[0].items
        )
        assertEquals(
            listOf(
                DebugInfoItem("Sender", "Android"),
                DebugInfoItem("Active peer", "peer-legacy"),
                DebugInfoItem("Connection", "Connected"),
                DebugInfoItem("Read", "Idle"),
                DebugInfoItem("Frame", "Frame available"),
                DebugInfoItem("Write", "Accepted")
            ),
            card.sections[1].items
        )
        assertEquals(
            listOf(
                DebugInfoItem("Selected peer", "peer-legacy"),
                DebugInfoItem("Active peer", "peer-legacy"),
                DebugInfoItem("Selected session", "ready"),
                DebugInfoItem("Active session", "ready"),
                DebugInfoItem("Established", "peer-canonical", preferFullWidth = true),
                DebugInfoItem("Aliases", "peer-legacy -> peer-canonical", preferFullWidth = true),
                DebugInfoItem(
                    "Last exchange",
                    "Identity sent. Run on both devices.",
                    preferFullWidth = true
                )
            ),
            card.sections[3].items
        )
    }

    private fun wifiDirectRuntimeStatus(
        missingPermissions: Set<String> = emptySet(),
        isWifiEnabled: Boolean? = true,
        discoveryState: WifiDirectDiscoveryState = WifiDirectDiscoveryState.INACTIVE,
        peers: List<WifiDirectPeer> = emptyList(),
        connectionStatus: WifiDirectConnectionStatus = WifiDirectConnectionStatus(),
        lastError: String? = null
    ): WifiDirectRuntimeStatus {
        return WifiDirectRuntimeStatus(
            permissionStatus = WifiDirectPermissionStatus(
                requiredPermissions = setOf("android.permission.NEARBY_WIFI_DEVICES"),
                missingPermissions = missingPermissions,
                isWifiDirectSupported = true,
                isWifiEnabled = isWifiEnabled
            ),
            discoveryState = discoveryState,
            transportState = WifiDirectTransportState.NOT_WIRED,
            connectionStatus = connectionStatus,
            peers = peers,
            lastError = lastError
        )
    }
}
