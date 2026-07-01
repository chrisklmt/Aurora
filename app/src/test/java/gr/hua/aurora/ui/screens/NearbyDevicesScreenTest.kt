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
import gr.hua.aurora.wifidirect.WifiDirectGlobalDebugSendDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectPeer
import gr.hua.aurora.wifidirect.WifiDirectPermissionStatus
import gr.hua.aurora.wifidirect.WifiDirectPrivateDebugSendDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectRuntimeStatus
import gr.hua.aurora.wifidirect.WifiDirectSocketDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectSocketEndpoint
import gr.hua.aurora.wifidirect.WifiDirectSocketRole
import gr.hua.aurora.wifidirect.WifiDirectSocketState
import gr.hua.aurora.wifidirect.WifiDirectReceiveBridgeDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectSendBridgeDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectSmokeTestDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectTransportAdapterDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectTransportAdapterState
import gr.hua.aurora.wifidirect.WifiDirectTransportState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        val card = requireNotNull(
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

        assertEquals("Debug", card.title)
        assertEquals(
            listOf(
                "Runtime",
                "Discovery",
                "Connection/group",
                "Socket/frame",
                "Bridges",
                "Global debug send",
                "Manual test readiness",
                "Manual test",
                "Identity"
            ),
            card.sections.map { it.title }
        )
        val globalSection = card.sections.first { it.title == "Global debug send" }
        assertTrue(globalSection.items.contains(DebugInfoItem("Overall", "Not ready", preferFullWidth = true)))
        assertTrue(globalSection.items.contains(DebugInfoItem("Guide", "For Wi-Fi Direct Global test: connect group, connect socket, enable send bridge on sender, enable receive bridge on receiver, enable Global send.", preferFullWidth = true)))
        assertTrue(globalSection.items.contains(DebugInfoItem("Note", "Normal chat still uses BLE. Private Chat still uses BLE.", preferFullWidth = true)))
        val readinessSection = card.sections.first { it.title == "Manual test readiness" }
        assertTrue(readinessSection.items.contains(DebugInfoItem("Overall", "Not ready", preferFullWidth = true)))
        assertTrue(readinessSection.items.contains(DebugInfoItem("Global send", "disabled")))
        assertTrue(readinessSection.items.contains(DebugInfoItem("Private send", "disabled")))
        val manualTestSection = card.sections.first { it.title == "Manual test" }
        assertTrue(
            manualTestSection.items.contains(
                DebugInfoItem(
                    "Step 9",
                    "For Global: enable Global Wi-Fi Direct debug send, then send Global Chat message.",
                    preferFullWidth = true
                )
            )
        )
        assertTrue(
            manualTestSection.items.contains(
                DebugInfoItem(
                    "Step 10",
                    "For Private: open Private Chat, enable Private Wi-Fi Direct debug send, then send Private Chat message.",
                    preferFullWidth = true
                )
            )
        )
        val bridgesSection = card.sections.first { it.title == "Bridges" }
        assertTrue(bridgesSection.items.contains(DebugInfoItem("Receive warn", "Receive bridge disabled.", preferFullWidth = true)))
    }

    @Test
    fun nearbyWifiDirectManualReadinessReportsGlobalAndPrivateDebugStates() {
        assertEquals(
            NearbyWifiDirectManualTestReadiness(
                overallStatus = "Ready for Private debug send",
                discoveryStatus = "ready",
                groupStatus = "connected",
                socketFrameStatus = "ready",
                adapterStatus = "ready",
                sendBridgeStatus = "enabled",
                receiveBridgeStatus = "enabled",
                globalDebugSendStatus = "enabled",
                privateDebugSendStatus = "enabled"
            ),
            nearbyWifiDirectManualTestReadiness(
                runtimeStatus = wifiDirectRuntimeStatus(
                    connectionStatus = WifiDirectConnectionStatus(
                        state = WifiDirectConnectionState.CONNECTED,
                        groupFormed = WifiDirectGroupFormedState.YES,
                        role = WifiDirectConnectionRole.CLIENT,
                        groupOwnerAddress = "192.168.49.1"
                    )
                ),
                socketDiagnostics = WifiDirectSocketDiagnostics(
                    state = WifiDirectSocketState.CONNECTED,
                    isConnected = true
                ),
                adapterDiagnostics = WifiDirectTransportAdapterDiagnostics(
                    state = WifiDirectTransportAdapterState.READY
                ),
                sendBridgeDiagnostics = WifiDirectSendBridgeDiagnostics(enabled = true),
                globalSendDiagnostics = WifiDirectGlobalDebugSendDiagnostics(enabled = true),
                privateDebugSendDiagnostics = WifiDirectPrivateDebugSendDiagnostics(enabled = true),
                receiveBridgeDiagnostics = WifiDirectReceiveBridgeDiagnostics(enabled = true)
            )
        )
    }

    @Test
    fun nearbyWifiDirectManualChecklistSectionListsGlobalAndPrivateSteps() {
        val section = buildNearbyWifiDirectManualChecklistSection()

        assertEquals("Manual test", section.title)
        assertTrue(section.items.any { it.value.contains("Global Wi-Fi Direct debug send") })
        assertTrue(section.items.any { it.value.contains("Private Wi-Fi Direct debug send") })
        assertTrue(section.items.any { it.value.contains("no Delivered/ACK", ignoreCase = true) })
    }

    @Test
    fun nearbyWifiDirectGlobalReadinessIsNotReadyByDefault() {
        assertEquals(
            NearbyWifiDirectGlobalDebugReadiness(
                overallStatus = "Not ready",
                discoveryStatus = "inactive",
                connectionStatus = "not ready",
                socketFrameStatus = "not ready",
                adapterStatus = "disabled",
                sendBridgeStatus = "disabled",
                receiveBridgeStatus = "disabled",
                globalSendStatus = "disabled",
                canEnableGlobalDebugSend = false,
                globalSendBlockedReason = "Connect a Wi-Fi Direct group first.",
                bridgeMismatchWarning = null,
                receiveBridgeWarning = "Receive bridge disabled."
            ),
            nearbyWifiDirectGlobalDebugReadiness(
                runtimeStatus = wifiDirectRuntimeStatus(),
                socketDiagnostics = WifiDirectSocketDiagnostics(),
                adapterDiagnostics = WifiDirectTransportAdapterDiagnostics(),
                sendBridgeDiagnostics = WifiDirectSendBridgeDiagnostics(),
                globalSendDiagnostics = WifiDirectGlobalDebugSendDiagnostics(),
                receiveBridgeDiagnostics = WifiDirectReceiveBridgeDiagnostics()
            )
        )
    }

    @Test
    fun nearbyWifiDirectGlobalReadinessRequiresReadyAdapterAndSendBridge() {
        assertEquals(
            NearbyWifiDirectGlobalDebugReadiness(
                overallStatus = "Ready for Global debug dual-send",
                discoveryStatus = "inactive",
                connectionStatus = "ready",
                socketFrameStatus = "ready",
                adapterStatus = "ready",
                sendBridgeStatus = "enabled",
                receiveBridgeStatus = "enabled",
                globalSendStatus = "enabled",
                canEnableGlobalDebugSend = true,
                globalSendBlockedReason = null,
                bridgeMismatchWarning = null,
                receiveBridgeWarning = null
            ),
            nearbyWifiDirectGlobalDebugReadiness(
                runtimeStatus = wifiDirectRuntimeStatus(
                    connectionStatus = WifiDirectConnectionStatus(
                        state = WifiDirectConnectionState.CONNECTED,
                        groupFormed = WifiDirectGroupFormedState.YES,
                        role = WifiDirectConnectionRole.CLIENT,
                        groupOwnerAddress = "192.168.49.1"
                    )
                ),
                socketDiagnostics = WifiDirectSocketDiagnostics(
                    state = WifiDirectSocketState.CONNECTED,
                    isConnected = true
                ),
                adapterDiagnostics = WifiDirectTransportAdapterDiagnostics(
                    state = WifiDirectTransportAdapterState.READY
                ),
                sendBridgeDiagnostics = WifiDirectSendBridgeDiagnostics(enabled = true),
                globalSendDiagnostics = WifiDirectGlobalDebugSendDiagnostics(enabled = true),
                receiveBridgeDiagnostics = WifiDirectReceiveBridgeDiagnostics(enabled = true)
            )
        )
    }

    @Test
    fun nearbyWifiDirectGlobalReadinessWaitsForReceiverBridgeWithoutBlockingSendReadiness() {
        val readiness = nearbyWifiDirectGlobalDebugReadiness(
            runtimeStatus = wifiDirectRuntimeStatus(
                connectionStatus = WifiDirectConnectionStatus(
                    state = WifiDirectConnectionState.CONNECTED,
                    groupFormed = WifiDirectGroupFormedState.YES,
                    role = WifiDirectConnectionRole.CLIENT,
                    groupOwnerAddress = "192.168.49.1"
                )
            ),
            socketDiagnostics = WifiDirectSocketDiagnostics(
                state = WifiDirectSocketState.CONNECTED,
                isConnected = true
            ),
            adapterDiagnostics = WifiDirectTransportAdapterDiagnostics(
                state = WifiDirectTransportAdapterState.READY
            ),
            sendBridgeDiagnostics = WifiDirectSendBridgeDiagnostics(enabled = true),
            globalSendDiagnostics = WifiDirectGlobalDebugSendDiagnostics(enabled = true),
            receiveBridgeDiagnostics = WifiDirectReceiveBridgeDiagnostics(enabled = false)
        )

        assertEquals("Waiting for receiver bridge", readiness.overallStatus)
        assertTrue(readiness.canEnableGlobalDebugSend)
        assertEquals("Receive bridge disabled.", readiness.receiveBridgeWarning)
    }

    @Test
    fun nearbyWifiDirectGlobalReadinessBlocksGlobalSendWhenSendBridgeMissing() {
        val readiness = nearbyWifiDirectGlobalDebugReadiness(
            runtimeStatus = wifiDirectRuntimeStatus(
                connectionStatus = WifiDirectConnectionStatus(
                    state = WifiDirectConnectionState.CONNECTED,
                    groupFormed = WifiDirectGroupFormedState.YES,
                    role = WifiDirectConnectionRole.CLIENT,
                    groupOwnerAddress = "192.168.49.1"
                )
            ),
            socketDiagnostics = WifiDirectSocketDiagnostics(
                state = WifiDirectSocketState.CONNECTED,
                isConnected = true
            ),
            adapterDiagnostics = WifiDirectTransportAdapterDiagnostics(
                state = WifiDirectTransportAdapterState.READY
            ),
            sendBridgeDiagnostics = WifiDirectSendBridgeDiagnostics(enabled = false),
            globalSendDiagnostics = WifiDirectGlobalDebugSendDiagnostics(enabled = false),
            receiveBridgeDiagnostics = WifiDirectReceiveBridgeDiagnostics(enabled = true)
        )

        assertFalse(readiness.canEnableGlobalDebugSend)
        assertEquals("Enable the send bridge first.", readiness.globalSendBlockedReason)
    }

    @Test
    fun nearbyWifiDirectReceiveBridgeDebugSectionShowsCompactDiagnostics() {
        assertEquals(
            DebugInfoSection(
                title = "Receive bridge",
                items = listOf(
                    DebugInfoItem("Bridge", "enabled"),
                    DebugInfoItem("Bridged", "2"),
                    DebugInfoItem("Failures", "1"),
                    DebugInfoItem("Last size", "18 B"),
                    DebugInfoItem(
                        "Last error",
                        "Invalid Aurora transport frame payload.",
                        preferFullWidth = true
                    ),
                    DebugInfoItem(
                        "Note",
                        "Debug bridge only; normal send path still uses BLE.",
                        preferFullWidth = true
                    )
                )
            ),
            buildNearbyWifiDirectReceiveBridgeDebugSection(
                diagnostics = WifiDirectReceiveBridgeDiagnostics(
                    enabled = true,
                    framesBridged = 2,
                    bridgeFailures = 1,
                    lastBridgedFrameSize = 18,
                    lastBridgeError = "Invalid Aurora transport frame payload."
                )
            )
        )
    }

    @Test
    fun nearbyWifiDirectSendBridgeDebugSectionShowsCompactDiagnostics() {
        assertEquals(
            DebugInfoSection(
                title = "Send bridge",
                items = listOf(
                    DebugInfoItem("Bridge", "enabled"),
                    DebugInfoItem("Submitted", "2"),
                    DebugInfoItem("Failures", "1"),
                    DebugInfoItem("Last size", "18 B"),
                    DebugInfoItem(
                        "Last error",
                        "Wi-Fi Direct send bridge disabled.",
                        preferFullWidth = true
                    ),
                    DebugInfoItem(
                        "Note",
                        "Debug send bridge only; normal chat sending still uses BLE.",
                        preferFullWidth = true
                    )
                )
            ),
            buildNearbyWifiDirectSendBridgeDebugSection(
                diagnostics = WifiDirectSendBridgeDiagnostics(
                    enabled = true,
                    framesSubmitted = 2,
                    submitFailures = 1,
                    lastSubmittedFrameSize = 18,
                    lastSendBridgeError = "Wi-Fi Direct send bridge disabled."
                )
            )
        )
    }

    @Test
    fun nearbyWifiDirectGlobalSendDebugSectionShowsCompactDiagnostics() {
        assertEquals(
            DebugInfoSection(
                title = "Global send",
                items = listOf(
                    DebugInfoItem("Global send", "enabled"),
                    DebugInfoItem("Mode", "BLE primary + Wi-Fi Direct debug copy"),
                    DebugInfoItem("Attempts", "4"),
                    DebugInfoItem("Success", "3"),
                    DebugInfoItem("Failures", "1"),
                    DebugInfoItem("Last msg", "global-msg-77"),
                    DebugInfoItem("Last result", "failed"),
                    DebugInfoItem("Last size", "96 B"),
                    DebugInfoItem(
                        "Last error",
                        "Wi-Fi Direct Global send requires the send bridge to be enabled.",
                        preferFullWidth = true
                    ),
                    DebugInfoItem(
                        "Note",
                        "Debug only. BLE remains the normal Global Chat path.",
                        preferFullWidth = true
                    )
                )
            ),
            buildNearbyWifiDirectGlobalSendDebugSection(
                diagnostics = WifiDirectGlobalDebugSendDiagnostics(
                    enabled = true,
                    globalSubmissionAttempts = 4,
                    globalSubmissionSuccesses = 3,
                    globalSubmitFailures = 1,
                    lastGlobalMessageId = "global-msg-77",
                    lastGlobalFrameSize = 96,
                    lastGlobalSendResult = "failed",
                    lastGlobalSendError =
                    "Wi-Fi Direct Global send requires the send bridge to be enabled."
                )
            )
        )
    }

    @Test
    fun nearbyWifiDirectSmokeTestDebugSectionShowsCompactDiagnostics() {
        assertEquals(
            DebugInfoSection(
                title = "Smoke",
                items = listOf(
                    DebugInfoItem("Smoke", "ready"),
                    DebugInfoItem("Bridge", "enabled"),
                    DebugInfoItem("Adapter", "ready"),
                    DebugInfoItem("Sent", "1"),
                    DebugInfoItem("Failures", "1"),
                    DebugInfoItem("Last result", "failed"),
                    DebugInfoItem("Last size", "96 B"),
                    DebugInfoItem(
                        "Last error",
                        "Wi-Fi Direct smoke test requires the send bridge to be enabled.",
                        preferFullWidth = true
                    ),
                    DebugInfoItem(
                        "Note",
                        "Debug-only Wi-Fi Direct smoke test. Normal chat sending still uses BLE.",
                        preferFullWidth = true
                    )
                )
            ),
            buildNearbyWifiDirectSmokeTestDebugSection(
                diagnostics = WifiDirectSmokeTestDiagnostics(
                    ready = true,
                    sendBridgeEnabled = true,
                    adapterState = gr.hua.aurora.wifidirect.WifiDirectTransportAdapterState.READY,
                    smokeFramesSent = 1,
                    smokeSendFailures = 1,
                    lastSmokeFrameSize = 96,
                    lastSmokeSendResult = "failed",
                    lastSmokeError =
                    "Wi-Fi Direct smoke test requires the send bridge to be enabled."
                )
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
    fun nearbyWifiDirectAdapterDebugSectionShowsCompactDiagnostics() {
        assertEquals(
            DebugInfoSection(
                title = "Adapter",
                items = listOf(
                    DebugInfoItem("Adapter", "ready"),
                    DebugInfoItem("Submitted", "2"),
                    DebugInfoItem("Received", "1"),
                    DebugInfoItem("Bytes", "24/12"),
                    DebugInfoItem("Last size", "12 B"),
                    DebugInfoItem(
                        "Note",
                        "Wi-Fi Direct chat routing not wired yet.",
                        preferFullWidth = true
                    )
                )
            ),
            buildNearbyWifiDirectAdapterDebugSection(
                diagnostics = gr.hua.aurora.wifidirect.WifiDirectTransportAdapterDiagnostics(
                    state = gr.hua.aurora.wifidirect.WifiDirectTransportAdapterState.READY,
                    framesSubmitted = 2,
                    framesReceived = 1,
                    bytesSubmitted = 24,
                    bytesReceived = 12,
                    lastFrameSize = 12
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
                canSendAdapterFrame = false,
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
                canSendAdapterFrame = false,
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
                canSendAdapterFrame = false,
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
                canSendAdapterFrame = false,
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
                canSendAdapterFrame = false,
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
    fun nearbyWifiDirectSocketControlsEnableAdapterFrameSendOnlyWhenAdapterReady() {
        assertEquals(
            NearbyWifiDirectSocketControlsState(
                canStartServer = false,
                canConnectClient = false,
                canSendFrame = true,
                canSendAdapterFrame = true,
                canSendBridgedFrame = false,
                canSendSmokeTestFrame = false,
                canCloseSocket = true
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
                diagnostics = WifiDirectSocketDiagnostics(
                    state = WifiDirectSocketState.CONNECTED,
                    role = WifiDirectSocketRole.CLIENT,
                    isConnected = true
                ),
                adapterDiagnostics = gr.hua.aurora.wifidirect.WifiDirectTransportAdapterDiagnostics(
                    state = gr.hua.aurora.wifidirect.WifiDirectTransportAdapterState.READY
                )
            )
        )
    }

    @Test
    fun nearbyWifiDirectSocketControlsEnableBridgedFrameOnlyWhenSendBridgeAndAdapterAreReady() {
        assertEquals(
            NearbyWifiDirectSocketControlsState(
                canStartServer = false,
                canConnectClient = false,
                canSendFrame = true,
                canSendAdapterFrame = true,
                canSendBridgedFrame = true,
                canSendSmokeTestFrame = true,
                canCloseSocket = true
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
                diagnostics = WifiDirectSocketDiagnostics(
                    state = WifiDirectSocketState.CONNECTED,
                    role = WifiDirectSocketRole.CLIENT,
                    isConnected = true
                ),
                adapterDiagnostics = gr.hua.aurora.wifidirect.WifiDirectTransportAdapterDiagnostics(
                    state = gr.hua.aurora.wifidirect.WifiDirectTransportAdapterState.READY
                ),
                sendBridgeDiagnostics = WifiDirectSendBridgeDiagnostics(
                    enabled = true
                ),
                smokeTestDiagnostics = WifiDirectSmokeTestDiagnostics(
                    ready = true,
                    sendBridgeEnabled = true,
                    adapterState = gr.hua.aurora.wifidirect.WifiDirectTransportAdapterState.READY
                )
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
