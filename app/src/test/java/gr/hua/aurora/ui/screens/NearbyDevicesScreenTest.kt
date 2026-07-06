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
import gr.hua.aurora.ui.debug.wifidirect.*
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
import gr.hua.aurora.wifidirect.WifiDirectRolePreference
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
                identityHandlerStatus = "Identity handler ready. Local agreement private key loaded.",
                peerSessionDiagnostics = diagnostics
            )
        )

        assertEquals("Debug", card.title)
        assertEquals(
            listOf(
                "Runtime",
                "Identity"
            ),
            card.sections.map { it.title }
        )
        assertEquals(
            listOf(
                DebugInfoItem("Mode", "Full mesh"),
                DebugInfoItem("Scan", "Scanning")
            ),
            card.sections.first { it.title == "Runtime" }.items
        )
        assertEquals(
            listOf(
                DebugInfoItem("Handler", "ready"),
                DebugInfoItem("Sessions", "1")
            ),
            card.sections.first { it.title == "Identity" }.items
        )
    }

    @Test
    fun nearbyAdvancedSectionToggleLabelMatchesExpandedState() {
        assertEquals(
            "Show Wi-Fi Direct details",
            nearbyAdvancedSectionToggleLabel(
                title = "Wi-Fi Direct details",
                expanded = false
            )
        )
        assertEquals(
            "Hide socket diagnostics",
            nearbyAdvancedSectionToggleLabel(
                title = "socket diagnostics",
                expanded = true
            )
        )
    }

    @Test
    fun nearbyWifiDirectCompactSummaryKeepsPrimaryStateShort() {
        val summary = nearbyWifiDirectCompactSummary(
            runtimeStatus = wifiDirectRuntimeStatus(
                discoveryState = WifiDirectDiscoveryState.ACTIVE,
                connectionStatus = WifiDirectConnectionStatus(
                    state = WifiDirectConnectionState.CONNECTED,
                    groupFormed = WifiDirectGroupFormedState.YES,
                    role = WifiDirectConnectionRole.GROUP_OWNER
                )
            ),
            socketDiagnostics = WifiDirectSocketDiagnostics(
                state = WifiDirectSocketState.CONNECTED,
                role = WifiDirectSocketRole.SERVER,
                isConnected = true
            ),
            adapterDiagnostics = WifiDirectTransportAdapterDiagnostics(
                state = WifiDirectTransportAdapterState.READY
            ),
            sendBridgeDiagnostics = WifiDirectSendBridgeDiagnostics(enabled = true),
            globalSendDiagnostics = WifiDirectGlobalDebugSendDiagnostics(enabled = true),
            privateDebugSendDiagnostics = WifiDirectPrivateDebugSendDiagnostics(),
            receiveBridgeDiagnostics = WifiDirectReceiveBridgeDiagnostics(enabled = true)
        )

        assertEquals("Ready", summary.status)
        assertEquals("ready", summary.discovery)
        assertEquals("connected", summary.group)
        assertEquals("group owner", summary.role)
        assertEquals("ready", summary.socket)
        assertEquals("ready", summary.adapter)
        assertEquals("enabled", summary.sendBridge)
        assertEquals("enabled", summary.receiveBridge)
        assertEquals("enabled", summary.globalDebugSend)
    }

    @Test
    fun nearbyWifiDirectAdvancedCardsKeepRawDiagnosticsCollapsedIntoSeparateBuilders() {
        val runtimeStatus = wifiDirectRuntimeStatus(
            discoveryState = WifiDirectDiscoveryState.ACTIVE,
            connectionStatus = WifiDirectConnectionStatus(
                state = WifiDirectConnectionState.CONNECTED,
                groupFormed = WifiDirectGroupFormedState.YES,
                role = WifiDirectConnectionRole.CLIENT,
                groupOwnerAddress = "192.168.49.1"
            )
        )

        assertEquals(
            listOf("Discovery", "Connection/group"),
            buildNearbyWifiDirectDetailsAdvancedCard(runtimeStatus).sections.map { it.title }
        )
        assertEquals(
            listOf("Socket", "Frame", "Adapter"),
            buildNearbyWifiDirectSocketDiagnosticsCard(
                socketDiagnostics = WifiDirectSocketDiagnostics(),
                adapterDiagnostics = WifiDirectTransportAdapterDiagnostics()
            ).sections.map { it.title }
        )
        assertEquals(
            listOf("Send bridge", "Receive bridge", "Smoke"),
            buildNearbyWifiDirectBridgeDiagnosticsCard(
                sendBridgeDiagnostics = WifiDirectSendBridgeDiagnostics(),
                smokeTestDiagnostics = WifiDirectSmokeTestDiagnostics(),
                receiveBridgeDiagnostics = WifiDirectReceiveBridgeDiagnostics()
            ).sections.map { it.title }
        )
        assertEquals(
            listOf("Global send"),
            buildNearbyWifiDirectGlobalDiagnosticsCard(
                diagnostics = WifiDirectGlobalDebugSendDiagnostics()
            ).sections.map { it.title }
        )
        assertEquals(
            listOf("Manual test"),
            buildNearbyWifiDirectManualGuideCard().sections.map { it.title }
        )
    }

    @Test
    fun nearbyRolePreferenceHelpCardKeepsGuidanceAvailableWithoutAlwaysShowingIt() {
        val card = buildNearbyWifiDirectRolePreferenceHelpCard(
            requestedPreference = WifiDirectRolePreference.PREFER_GROUP_OWNER,
            runtimeStatus = wifiDirectRuntimeStatus(
                connectionStatus = WifiDirectConnectionStatus(
                    state = WifiDirectConnectionState.CONNECTED,
                    groupFormed = WifiDirectGroupFormedState.YES,
                    role = WifiDirectConnectionRole.CLIENT
                )
            )
        )

        assertEquals("Role preference help", card.title)
        assertEquals("Role preference", card.sections.single().title)
        assertTrue(
            card.sections.single().items.any {
                it.value == "Role preference applies only when this device starts the Connect action."
            }
        )
        assertTrue(
            card.sections.single().items.any {
                it.value == "Requested role preference: Prefer this device as group owner"
            }
        )
    }

    @Test
    fun nearbyWifiDirectDetailsCardKeepsChecklistAndRawDiagnosticsBelowControls() {
        val card = buildNearbyWifiDirectDetailsCard(
            runtimeStatus = wifiDirectRuntimeStatus(),
            socketDiagnostics = WifiDirectSocketDiagnostics(),
            adapterDiagnostics = WifiDirectTransportAdapterDiagnostics(),
            sendBridgeDiagnostics = WifiDirectSendBridgeDiagnostics(),
            globalSendDiagnostics = WifiDirectGlobalDebugSendDiagnostics(),
            smokeTestDiagnostics = WifiDirectSmokeTestDiagnostics(),
            receiveBridgeDiagnostics = WifiDirectReceiveBridgeDiagnostics()
        )

        assertEquals("Wi-Fi Direct details", card.title)
        assertEquals(
            listOf(
                "Discovery",
                "Connection/group",
                "Socket/frame",
                "Bridges",
                "Global debug send",
                "Manual test"
            ),
            card.sections.map { it.title }
        )
        assertEquals(
            1,
            card.sections.count { it.title == "Manual test" }
        )
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
    fun nearbyWifiDirectPermissionBlockerExplainsMissingNearbyDevicesPermission() {
        assertEquals(
            NearbyWifiDirectPermissionBlocker(
                title = "Wi-Fi Direct permission required",
                message = "Grant Nearby devices permission to start Wi-Fi Direct discovery.",
                missingPermissionName = "NEARBY_WIFI_DEVICES",
                settingsInstruction =
                    "Open Android Settings > Apps > Aurora > Permissions > Nearby devices > Allow."
            ),
            nearbyWifiDirectPermissionBlocker(
                wifiDirectRuntimeStatus(
                    missingPermissions = setOf("android.permission.NEARBY_WIFI_DEVICES")
                )
            )
        )
    }

    @Test
    fun nearbyWifiDirectDisabledBlockerExplainsWifiOffState() {
        assertEquals(
            NearbyWifiDirectDisabledBlocker(
                title = "Wi-Fi Direct is disabled",
                message = "Turn on Wi-Fi to use Wi-Fi Direct discovery.",
                settingsActionLabel = "Open Wi-Fi settings",
                refreshActionLabel = "Refresh status",
                nextStep = "Turn on Wi-Fi, then return to Aurora."
            ),
            nearbyWifiDirectDisabledBlocker(
                wifiDirectRuntimeStatus(
                    isWifiEnabled = false
                )
            )
        )
    }

    @Test
    fun nearbyWifiDirectPermissionBlockerStaysNullWhenPermissionGrantedButWifiDisabled() {
        assertNull(
            nearbyWifiDirectPermissionBlocker(
                wifiDirectRuntimeStatus(
                    isWifiEnabled = false
                )
            )
        )
    }

    @Test
    fun nearbyWifiDirectManualNextStepPrioritizesMissingNearbyDevicesPermission() {
        assertEquals(
            NearbyWifiDirectManualNextStep(
                title = "Grant Nearby devices permission to start Wi-Fi Direct discovery.",
                detail = "Missing: NEARBY_WIFI_DEVICES"
            ),
            nearbyWifiDirectManualNextStep(
                runtimeStatus = wifiDirectRuntimeStatus(
                    missingPermissions = setOf("android.permission.NEARBY_WIFI_DEVICES")
                ),
                socketDiagnostics = WifiDirectSocketDiagnostics(),
                adapterDiagnostics = WifiDirectTransportAdapterDiagnostics(),
                sendBridgeDiagnostics = WifiDirectSendBridgeDiagnostics(),
                globalSendDiagnostics = WifiDirectGlobalDebugSendDiagnostics(),
                privateDebugSendDiagnostics = WifiDirectPrivateDebugSendDiagnostics(),
                receiveBridgeDiagnostics = WifiDirectReceiveBridgeDiagnostics()
            )
        )
    }

    @Test
    fun nearbyWifiDirectManualNextStepGuidesWifiEnableWhenDisabled() {
        assertEquals(
            NearbyWifiDirectManualNextStep(
                title = "Turn on Wi-Fi, then return to Aurora."
            ),
            nearbyWifiDirectManualNextStep(
                runtimeStatus = wifiDirectRuntimeStatus(
                    isWifiEnabled = false
                ),
                socketDiagnostics = WifiDirectSocketDiagnostics(),
                adapterDiagnostics = WifiDirectTransportAdapterDiagnostics(),
                sendBridgeDiagnostics = WifiDirectSendBridgeDiagnostics(),
                globalSendDiagnostics = WifiDirectGlobalDebugSendDiagnostics(),
                privateDebugSendDiagnostics = WifiDirectPrivateDebugSendDiagnostics(),
                receiveBridgeDiagnostics = WifiDirectReceiveBridgeDiagnostics()
            )
        )
    }

    @Test
    fun nearbyWifiDirectManualNextStepPointsToPrivateDebugToggleWhenOnlyThatIsMissing() {
        assertEquals(
            NearbyWifiDirectManualNextStep(
                title = "Enable Private Wi-Fi Direct debug send in Private Chat for the Private test."
            ),
            nearbyWifiDirectManualNextStep(
                runtimeStatus = wifiDirectRuntimeStatus(
                    discoveryState = WifiDirectDiscoveryState.ACTIVE,
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
                privateDebugSendDiagnostics = WifiDirectPrivateDebugSendDiagnostics(enabled = false),
                receiveBridgeDiagnostics = WifiDirectReceiveBridgeDiagnostics(enabled = true)
            )
        )
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
                    DebugInfoItem("Transport", "15"),
                    DebugInfoItem("Duplicates", "1"),
                    DebugInfoItem("Failures", "1"),
                    DebugInfoItem(
                        "Action",
                        "Enable receive bridge",
                        preferFullWidth = true
                    ),
                    DebugInfoItem("Toggle", "enabled"),
                    DebugInfoItem(
                        "Blocked",
                        "Cannot enable receive bridge: adapter not ready (Waiting for receive adapter.).",
                        preferFullWidth = true
                    ),
                    DebugInfoItem("Last result", "processed", preferFullWidth = true),
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
                    transportFramesObserved = 15,
                    framesBridged = 2,
                    duplicateFramesDropped = 1,
                    bridgeFailures = 1,
                    lastTransportFrameSize = 18,
                    lastToggleAction = "Enable receive bridge",
                    lastToggleResult = "enabled",
                    lastToggleBlockedReason =
                    "Cannot enable receive bridge: adapter not ready (Waiting for receive adapter.).",
                    lastBridgeResult = "processed",
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
                        "Peer type",
                        "Generic Wi-Fi Direct peers",
                        preferFullWidth = true
                    ),
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
                canStopDiscovery = false,
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
                canStopDiscovery = false,
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
    fun nearbyWifiDirectDebugControlsDisableStartWhenWifiDirectIsDisabled() {
        assertEquals(
            NearbyWifiDirectDebugControlsState(
                canStartDiscovery = false,
                canStopDiscovery = false,
                canDisconnect = false,
                disconnectLabel = "Disconnect Wi-Fi Direct",
                startDisabledReason = "Wi-Fi Direct is disabled."
            ),
            nearbyWifiDirectDebugControlsState(
                wifiDirectRuntimeStatus(
                    isWifiEnabled = false
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
                        "Peer type",
                        "Generic Wi-Fi Direct peers",
                        preferFullWidth = true
                    ),
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
                    DebugInfoItem("Action", "none"),
                    DebugInfoItem("Result", "none"),
                    DebugInfoItem("Attempts", "S0 C0 X0"),
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
    fun nearbyWifiDirectSocketActionTextShowsImmediateDebugFeedback() {
        val diagnostics = WifiDirectSocketDiagnostics(
            lastCommand = gr.hua.aurora.wifidirect.WifiDirectSocketCommand.START_SERVER,
            lastCommandResult = gr.hua.aurora.wifidirect.WifiDirectSocketCommandResult.STARTING,
            lastCommandSequence = 1,
            serverStartAttempts = 1,
            clientConnectAttempts = 0,
            closeAttempts = 0
        )
        val controlsState = NearbyWifiDirectSocketControlsState(
            canStartServer = true,
            canConnectClient = false,
            canSendFrame = false,
            canSendAdapterFrame = false,
            canCloseSocket = false,
            connectHost = "192.168.49.1"
        )

        assertEquals(
            "Socket action: #1 startServer | Starting socket server...",
            nearbyWifiDirectSocketActionResultText(diagnostics)
        )
        assertEquals(
            "Attempts: server 1 | client 0 | close 0",
            nearbyWifiDirectSocketAttemptSummaryText(diagnostics)
        )
        assertEquals(
            "Client host: 192.168.49.1",
            nearbyWifiDirectSocketHostText(controlsState)
        )
    }

    @Test
    fun nearbyHandleConnectSocketClientTapInvokesCallbackWithGroupOwnerIp() {
        val runtimeStatus = wifiDirectRuntimeStatus(
            connectionStatus = WifiDirectConnectionStatus(
                state = WifiDirectConnectionState.CONNECTED,
                groupFormed = WifiDirectGroupFormedState.YES,
                role = WifiDirectConnectionRole.CLIENT,
                groupOwnerAddress = "192.168.49.1"
            )
        )
        val controlsState = NearbyWifiDirectSocketControlsState(
            canStartServer = false,
            canConnectClient = true,
            canSendFrame = false,
            canSendAdapterFrame = false,
            canCloseSocket = false,
            connectHost = "192.168.49.1",
            startServerBlockedReason = "Start server only on group owner."
        )
        var invokedHost: String? = null

        val accepted = nearbyHandleConnectSocketClientTap(
            runtimeStatus = runtimeStatus,
            controlsState = controlsState,
            onConnectSocketClient = { host ->
                invokedHost = host
            }
        )

        assertTrue(accepted)
        assertEquals("192.168.49.1", invokedHost)
    }

    @Test
    fun nearbyHandleStartSocketServerTapInvokesCallback() {
        val runtimeStatus = wifiDirectRuntimeStatus(
            connectionStatus = WifiDirectConnectionStatus(
                state = WifiDirectConnectionState.CONNECTED,
                groupFormed = WifiDirectGroupFormedState.YES,
                role = WifiDirectConnectionRole.GROUP_OWNER,
                groupOwnerAddress = "192.168.49.1"
            )
        )
        val controlsState = NearbyWifiDirectSocketControlsState(
            canStartServer = true,
            canConnectClient = false,
            canSendFrame = false,
            canSendAdapterFrame = false,
            canCloseSocket = false,
            connectClientBlockedReason = "Connect client only on Wi-Fi Direct client."
        )
        var invokedHostHint: String? = null

        val accepted = nearbyHandleStartSocketServerTap(
            runtimeStatus = runtimeStatus,
            controlsState = controlsState,
            onStartSocketServer = { hostHint ->
                invokedHostHint = hostHint
            }
        )

        assertTrue(accepted)
        assertEquals("192.168.49.1", invokedHostHint)
    }

    @Test
    fun nearbyHandleConnectSocketClientTapDoesNotInvokeCallbackWhenBlocked() {
        val runtimeStatus = wifiDirectRuntimeStatus(
            connectionStatus = WifiDirectConnectionStatus(
                state = WifiDirectConnectionState.CONNECTED,
                groupFormed = WifiDirectGroupFormedState.YES,
                role = WifiDirectConnectionRole.CLIENT,
                groupOwnerAddress = null
            )
        )
        val controlsState = NearbyWifiDirectSocketControlsState(
            canStartServer = false,
            canConnectClient = false,
            canSendFrame = false,
            canSendAdapterFrame = false,
            canCloseSocket = false,
            startServerBlockedReason = "Start server only on group owner.",
            connectClientBlockedReason = "Group owner address missing.",
            helpText = "Group owner address missing."
        )
        var invocationCount = 0

        val accepted = nearbyHandleConnectSocketClientTap(
            runtimeStatus = runtimeStatus,
            controlsState = controlsState,
            onConnectSocketClient = {
                invocationCount += 1
            }
        )

        assertFalse(accepted)
        assertEquals(0, invocationCount)
    }

    @Test
    fun groupOwnerWithConnectedSocketCanEnableReceiveBridge() {
        val toggleState = nearbyWifiDirectReceiveBridgeToggleState(
            runtimeStatus = wifiDirectRuntimeStatus(
                connectionStatus = WifiDirectConnectionStatus(
                    state = WifiDirectConnectionState.CONNECTED,
                    groupFormed = WifiDirectGroupFormedState.YES,
                    role = WifiDirectConnectionRole.GROUP_OWNER,
                    groupOwnerAddress = "192.168.49.1"
                )
            ),
            socketDiagnostics = WifiDirectSocketDiagnostics(
                state = WifiDirectSocketState.CONNECTED,
                role = WifiDirectSocketRole.SERVER,
                isConnected = true
            ),
            adapterDiagnostics = WifiDirectTransportAdapterDiagnostics(
                state = WifiDirectTransportAdapterState.READY
            ),
            receiveBridgeDiagnostics = WifiDirectReceiveBridgeDiagnostics()
        )

        assertTrue(toggleState.showControls)
        assertTrue(toggleState.frameReady)
        assertTrue(toggleState.adapterReady)
        assertTrue(toggleState.effectiveReady)
        assertTrue(toggleState.canToggle)
        assertNull(toggleState.blockedReason)
    }

    @Test
    fun clientWithConnectedSocketCanEnableReceiveBridge() {
        val toggleState = nearbyWifiDirectReceiveBridgeToggleState(
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
                role = WifiDirectSocketRole.CLIENT,
                isConnected = true
            ),
            adapterDiagnostics = WifiDirectTransportAdapterDiagnostics(
                state = WifiDirectTransportAdapterState.READY
            ),
            receiveBridgeDiagnostics = WifiDirectReceiveBridgeDiagnostics()
        )

        assertTrue(toggleState.showControls)
        assertTrue(toggleState.canToggle)
        assertTrue(toggleState.effectiveReady)
    }

    @Test
    fun receiveBridgeToggleShowsVisibleAdapterBlockedReason() {
        val toggleState = nearbyWifiDirectReceiveBridgeToggleState(
            runtimeStatus = wifiDirectRuntimeStatus(
                connectionStatus = WifiDirectConnectionStatus(
                    state = WifiDirectConnectionState.CONNECTED,
                    groupFormed = WifiDirectGroupFormedState.YES,
                    role = WifiDirectConnectionRole.GROUP_OWNER,
                    groupOwnerAddress = "192.168.49.1"
                )
            ),
            socketDiagnostics = WifiDirectSocketDiagnostics(
                state = WifiDirectSocketState.CONNECTED,
                role = WifiDirectSocketRole.SERVER,
                isConnected = true
            ),
            adapterDiagnostics = WifiDirectTransportAdapterDiagnostics(
                state = WifiDirectTransportAdapterState.NOT_READY,
                notReadyReason = "Waiting for receive adapter."
            ),
            receiveBridgeDiagnostics = WifiDirectReceiveBridgeDiagnostics()
        )

        assertTrue(toggleState.showControls)
        assertFalse(toggleState.canToggle)
        assertEquals(
            "Cannot enable receive bridge: adapter not ready (Waiting for receive adapter.).",
            toggleState.blockedReason
        )
    }

    @Test
    fun receiveBridgeToggleTapInvokesCallbackWhenEffectiveReadinessIsTrue() {
        var enabledValue: Boolean? = null

        val accepted = nearbyHandleReceiveBridgeToggleTap(
            toggleState = NearbyWifiDirectReceiveBridgeToggleState(
                showControls = true,
                canToggle = true,
                blockedReason = null,
                socketConnected = true,
                frameReady = true,
                adapterReady = true,
                effectiveReady = true
            ),
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
                role = WifiDirectSocketRole.CLIENT,
                isConnected = true,
                isReadLoopActive = true
            ),
            sendBridgeEnabled = false,
            globalSendEnabled = false,
            receiveBridgeEnabled = false,
            onReportReceiveBridgeToggleBlocked = { error("toggle should not be blocked") },
            onSetReceiveBridgeEnabled = { enabled ->
                enabledValue = enabled
            }
        )

        assertTrue(accepted)
        assertEquals(true, enabledValue)
    }

    @Test
    fun receiveBridgeToggleTapDoesNotRequireSendBridgeOrGlobalSendAndBlocksCleanly() {
        var invocationCount = 0
        var blockedReason: String? = null

        val accepted = nearbyHandleReceiveBridgeToggleTap(
            toggleState = NearbyWifiDirectReceiveBridgeToggleState(
                showControls = true,
                canToggle = false,
                blockedReason = "Cannot enable receive bridge: adapter not ready (Waiting for receive adapter.).",
                socketConnected = true,
                frameReady = true,
                adapterReady = false,
                effectiveReady = false
            ),
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
                role = WifiDirectSocketRole.CLIENT,
                isConnected = true,
                isReadLoopActive = true
            ),
            sendBridgeEnabled = false,
            globalSendEnabled = false,
            receiveBridgeEnabled = false,
            onReportReceiveBridgeToggleBlocked = { reason ->
                blockedReason = reason
            },
            onSetReceiveBridgeEnabled = {
                invocationCount += 1
            }
        )

        assertFalse(accepted)
        assertEquals(0, invocationCount)
        assertEquals(
            "Cannot enable receive bridge: adapter not ready (Waiting for receive adapter.).",
            blockedReason
        )
    }

    @Test
    fun resetWifiDirectGroupClosesSocketDisablesBridgesAndRequestsCleanup() {
        var closeSocketCalls = 0
        var sendBridgeEnabled: Boolean? = null
        var receiveBridgeEnabled: Boolean? = null
        var resetDiagnosticsCalls = 0
        var stopDiscoveryCalls = 0
        var disconnectCalls = 0
        var refreshCalls = 0

        val result = nearbyHandleResetWifiDirectGroupTap(
            runtimeStatus = wifiDirectRuntimeStatus(
                discoveryState = WifiDirectDiscoveryState.ACTIVE,
                connectionStatus = WifiDirectConnectionStatus(
                    state = WifiDirectConnectionState.CONNECTED,
                    groupFormed = WifiDirectGroupFormedState.YES,
                    role = WifiDirectConnectionRole.GROUP_OWNER,
                    groupOwnerAddress = "192.168.49.1"
                )
            ),
            socketDiagnostics = WifiDirectSocketDiagnostics(
                state = WifiDirectSocketState.CONNECTED,
                role = WifiDirectSocketRole.SERVER,
                isConnected = true
            ),
            onCloseSocket = { closeSocketCalls += 1 },
            onSetSendBridgeEnabled = { enabled -> sendBridgeEnabled = enabled },
            onSetReceiveBridgeEnabled = { enabled -> receiveBridgeEnabled = enabled },
            onResetDiagnostics = { resetDiagnosticsCalls += 1 },
            onStopDiscovery = { stopDiscoveryCalls += 1 },
            onDisconnect = { disconnectCalls += 1 },
            onRefreshStatus = { refreshCalls += 1 }
        )

        assertEquals("Wi-Fi Direct group reset requested.", result)
        assertEquals(1, closeSocketCalls)
        assertEquals(false, sendBridgeEnabled)
        assertEquals(false, receiveBridgeEnabled)
        assertEquals(1, resetDiagnosticsCalls)
        assertEquals(1, stopDiscoveryCalls)
        assertEquals(1, disconnectCalls)
        assertEquals(1, refreshCalls)
    }

    @Test
    fun wifiDirectRolePreferenceHelpLinesExplainStickyGroupOwnerSelection() {
        assertEquals(
            listOf(
                "Role preference applies only when this device starts the Connect action.",
                "The other device's preference is not used unless that device initiates Connect.",
                "Android may still choose the final group owner.",
                "If Android keeps choosing the same host, continue testing with that device as group owner.",
                "Uninstalling the app may not reset Android Wi-Fi Direct group-owner selection.",
                "Use Reset Wi-Fi Direct group, then reconnect."
            ),
            nearbyWifiDirectRolePreferenceHelpLines()
        )
        assertEquals("Automatic", gr.hua.aurora.wifidirect.wifiDirectRolePreferenceSummary(WifiDirectRolePreference.AUTOMATIC))
    }

    @Test
    fun wifiDirectRolePreferenceOutcomeLinesShowRequestedAndActualRole() {
        assertEquals(
            listOf(
                "Requested role preference: Prefer this device as group owner",
                "Actual role: client. Android selected final role."
            ),
            nearbyWifiDirectRolePreferenceOutcomeLines(
                requestedPreference = WifiDirectRolePreference.PREFER_GROUP_OWNER,
                runtimeStatus = wifiDirectRuntimeStatus(
                    connectionStatus = WifiDirectConnectionStatus(
                        state = WifiDirectConnectionState.CONNECTED,
                        groupFormed = WifiDirectGroupFormedState.YES,
                        role = WifiDirectConnectionRole.CLIENT
                    )
                )
            )
        )
    }

    @Test
    fun wifiDirectRolePreferenceOutcomeLinesStayEmptyWithoutRequest() {
        assertTrue(
            nearbyWifiDirectRolePreferenceOutcomeLines(
                requestedPreference = null,
                runtimeStatus = wifiDirectRuntimeStatus()
            ).isEmpty()
        )
    }

    @Test
    fun nearbyWifiDirectManualNextStepUsesGroupOwnerSocketStepAfterConnection() {
        val nextStep = nearbyWifiDirectManualNextStep(
            runtimeStatus = wifiDirectRuntimeStatus(
                peers = listOf(
                    WifiDirectPeer(
                        deviceName = "Aurora Alpha",
                        deviceAddress = "AA:BB:CC:DD:EE:01"
                    )
                ),
                discoveryState = WifiDirectDiscoveryState.INACTIVE,
                connectionStatus = WifiDirectConnectionStatus(
                    state = WifiDirectConnectionState.CONNECTED,
                    groupFormed = WifiDirectGroupFormedState.YES,
                    role = WifiDirectConnectionRole.GROUP_OWNER,
                    groupOwnerAddress = "192.168.49.1"
                )
            ),
            socketDiagnostics = WifiDirectSocketDiagnostics(),
            adapterDiagnostics = WifiDirectTransportAdapterDiagnostics(),
            sendBridgeDiagnostics = WifiDirectSendBridgeDiagnostics(),
            globalSendDiagnostics = WifiDirectGlobalDebugSendDiagnostics(),
            privateDebugSendDiagnostics = WifiDirectPrivateDebugSendDiagnostics(),
            receiveBridgeDiagnostics = WifiDirectReceiveBridgeDiagnostics()
        )

        assertEquals("Start socket server.", nextStep.title)
        assertEquals(
            "This device is the Wi-Fi Direct group owner.",
            nextStep.detail
        )
    }

    @Test
    fun nearbyWifiDirectManualNextStepUsesClientSocketStepAfterConnection() {
        val nextStep = nearbyWifiDirectManualNextStep(
            runtimeStatus = wifiDirectRuntimeStatus(
                discoveryState = WifiDirectDiscoveryState.INACTIVE,
                connectionStatus = WifiDirectConnectionStatus(
                    state = WifiDirectConnectionState.CONNECTED,
                    groupFormed = WifiDirectGroupFormedState.YES,
                    role = WifiDirectConnectionRole.CLIENT,
                    groupOwnerAddress = "192.168.49.1"
                )
            ),
            socketDiagnostics = WifiDirectSocketDiagnostics(),
            adapterDiagnostics = WifiDirectTransportAdapterDiagnostics(),
            sendBridgeDiagnostics = WifiDirectSendBridgeDiagnostics(),
            globalSendDiagnostics = WifiDirectGlobalDebugSendDiagnostics(),
            privateDebugSendDiagnostics = WifiDirectPrivateDebugSendDiagnostics(),
            receiveBridgeDiagnostics = WifiDirectReceiveBridgeDiagnostics()
        )

        assertEquals("Connect socket client.", nextStep.title)
        assertEquals("Use group owner host 192.168.49.1.", nextStep.detail)
    }

    @Test
    fun nearbyWifiDirectSocketSetupUiStateShowsGroupOwnerPrimaryActionOnly() {
        val uiState = nearbyWifiDirectSocketSetupUiState(
            runtimeStatus = wifiDirectRuntimeStatus(
                connectionStatus = WifiDirectConnectionStatus(
                    state = WifiDirectConnectionState.CONNECTED,
                    groupFormed = WifiDirectGroupFormedState.YES,
                    role = WifiDirectConnectionRole.GROUP_OWNER,
                    groupOwnerAddress = "192.168.49.1"
                )
            ),
            socketControlsState = NearbyWifiDirectSocketControlsState(
                canStartServer = true,
                canConnectClient = false,
                canSendFrame = false,
                canSendAdapterFrame = false,
                canCloseSocket = false,
                connectClientBlockedReason = "Connect client only on Wi-Fi Direct client."
            ),
            socketDiagnostics = WifiDirectSocketDiagnostics(),
            adapterDiagnostics = WifiDirectTransportAdapterDiagnostics()
        )

        assertEquals("Role: group owner", uiState.roleText)
        assertEquals("Next step: Start socket server.", uiState.nextStepText)
        assertTrue(uiState.showPrimaryStartServer)
        assertFalse(uiState.showPrimaryConnectClient)
        assertFalse(uiState.showCloseSocket)
        assertFalse(uiState.showFrameActions)
    }

    @Test
    fun nearbyWifiDirectSocketSetupUiStateShowsClientPrimaryActionOnly() {
        val uiState = nearbyWifiDirectSocketSetupUiState(
            runtimeStatus = wifiDirectRuntimeStatus(
                connectionStatus = WifiDirectConnectionStatus(
                    state = WifiDirectConnectionState.CONNECTED,
                    groupFormed = WifiDirectGroupFormedState.YES,
                    role = WifiDirectConnectionRole.CLIENT,
                    groupOwnerAddress = "192.168.49.1"
                )
            ),
            socketControlsState = NearbyWifiDirectSocketControlsState(
                canStartServer = false,
                canConnectClient = true,
                canSendFrame = false,
                canSendAdapterFrame = false,
                canCloseSocket = false,
                connectHost = "192.168.49.1",
                startServerBlockedReason = "Start server only on group owner."
            ),
            socketDiagnostics = WifiDirectSocketDiagnostics(),
            adapterDiagnostics = WifiDirectTransportAdapterDiagnostics()
        )

        assertEquals("Role: client", uiState.roleText)
        assertEquals("Group owner host: 192.168.49.1", uiState.hostText)
        assertEquals("Next step: Connect socket client.", uiState.nextStepText)
        assertFalse(uiState.showPrimaryStartServer)
        assertTrue(uiState.showPrimaryConnectClient)
        assertFalse(uiState.showCloseSocket)
        assertFalse(uiState.showFrameActions)
    }

    @Test
    fun nearbyWifiDirectSocketSetupUiStateExplainsMissingOrInvalidClientHost() {
        val missingHostUiState = nearbyWifiDirectSocketSetupUiState(
            runtimeStatus = wifiDirectRuntimeStatus(
                connectionStatus = WifiDirectConnectionStatus(
                    state = WifiDirectConnectionState.CONNECTED,
                    groupFormed = WifiDirectGroupFormedState.YES,
                    role = WifiDirectConnectionRole.CLIENT,
                    groupOwnerAddress = null
                )
            ),
            socketControlsState = NearbyWifiDirectSocketControlsState(
                canStartServer = false,
                canConnectClient = false,
                canSendFrame = false,
                canSendAdapterFrame = false,
                canCloseSocket = false,
                startServerBlockedReason = "Start server only on group owner.",
                connectClientBlockedReason = "Group owner address missing."
            ),
            socketDiagnostics = WifiDirectSocketDiagnostics(),
            adapterDiagnostics = WifiDirectTransportAdapterDiagnostics()
        )
        val macHostUiState = nearbyWifiDirectSocketSetupUiState(
            runtimeStatus = wifiDirectRuntimeStatus(
                connectionStatus = WifiDirectConnectionStatus(
                    state = WifiDirectConnectionState.CONNECTED,
                    groupFormed = WifiDirectGroupFormedState.YES,
                    role = WifiDirectConnectionRole.CLIENT,
                    groupOwnerAddress = "AA:BB:CC:DD:EE:01"
                )
            ),
            socketControlsState = NearbyWifiDirectSocketControlsState(
                canStartServer = false,
                canConnectClient = false,
                canSendFrame = false,
                canSendAdapterFrame = false,
                canCloseSocket = false,
                startServerBlockedReason = "Start server only on group owner.",
                connectClientBlockedReason = "Socket client needs the group owner IP address."
            ),
            socketDiagnostics = WifiDirectSocketDiagnostics(),
            adapterDiagnostics = WifiDirectTransportAdapterDiagnostics()
        )

        assertEquals(
            "Cannot connect: group owner IP missing.",
            missingHostUiState.supportingText
        )
        assertEquals(
            "Cannot connect: group owner host is not an IP.",
            macHostUiState.supportingText
        )
    }

    @Test
    fun nearbyWifiDirectSocketSetupUiStateRevealsFrameAndBridgeControlsWhenReady() {
        val uiState = nearbyWifiDirectSocketSetupUiState(
            runtimeStatus = wifiDirectRuntimeStatus(
                connectionStatus = WifiDirectConnectionStatus(
                    state = WifiDirectConnectionState.CONNECTED,
                    groupFormed = WifiDirectGroupFormedState.YES,
                    role = WifiDirectConnectionRole.CLIENT,
                    groupOwnerAddress = "192.168.49.1"
                )
            ),
            socketControlsState = NearbyWifiDirectSocketControlsState(
                canStartServer = false,
                canConnectClient = false,
                canSendFrame = true,
                canSendAdapterFrame = true,
                canCloseSocket = true,
                connectHost = "192.168.49.1"
            ),
            socketDiagnostics = WifiDirectSocketDiagnostics(
                state = WifiDirectSocketState.CONNECTED,
                role = WifiDirectSocketRole.CLIENT,
                isConnected = true
            ),
            adapterDiagnostics = WifiDirectTransportAdapterDiagnostics(
                state = WifiDirectTransportAdapterState.READY
            )
        )

        assertEquals("Socket/frame: ready", uiState.headline)
        assertTrue(uiState.showFrameActions)
        assertTrue(uiState.showBridgeControls)
        assertTrue(uiState.showGlobalControls)
        assertTrue(uiState.showCloseSocket)
        assertEquals("Next step: Send debug frame or enable bridges.", uiState.nextStepText)
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
    fun nearbyWifiDirectSocketFrameDebugSectionTreatsConnectedSocketAsReadyWhenRawFrameStateIsIdle() {
        val section = buildNearbyWifiDirectSocketFrameDebugSection(
            diagnostics = WifiDirectSocketDiagnostics(
                state = WifiDirectSocketState.CONNECTED,
                role = WifiDirectSocketRole.SERVER,
                endpoint = WifiDirectSocketEndpoint(host = "192.168.49.1", port = 8988),
                isConnected = true,
                frameDiagnostics = gr.hua.aurora.wifidirect.WifiDirectFrameDiagnostics(
                    state = gr.hua.aurora.wifidirect.WifiDirectFrameTransportState.IDLE
                )
            ),
            adapterDiagnostics = WifiDirectTransportAdapterDiagnostics(
                state = WifiDirectTransportAdapterState.READY
            )
        )

        assertTrue(section.items.contains(DebugInfoItem("Frame", "ready")))
        assertFalse(section.items.any { it.label == "Frame reason" })
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
    fun nearbyWifiDirectAdapterDebugSectionShowsBlockedReasonWhenNotReady() {
        val section = buildNearbyWifiDirectAdapterDebugSection(
            diagnostics = WifiDirectTransportAdapterDiagnostics(
                state = WifiDirectTransportAdapterState.NOT_READY,
                notReadyReason = "Waiting for a socket client."
            )
        )

        assertTrue(
            section.items.contains(
                DebugInfoItem(
                    "Blocked",
                    "Waiting for a socket client.",
                    preferFullWidth = true
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
                startServerBlockedReason = "Wi-Fi Direct group not formed.",
                connectClientBlockedReason = "Wi-Fi Direct group not formed.",
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
                canCloseSocket = false,
                connectClientBlockedReason = "Connect client only on Wi-Fi Direct client."
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
                connectHost = "192.168.49.1",
                startServerBlockedReason = "Start server only on group owner."
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
                startServerBlockedReason = "Wi-Fi Direct role unavailable.",
                connectClientBlockedReason = "Wi-Fi Direct role unavailable.",
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
                startServerBlockedReason = "Start server only on group owner.",
                connectClientBlockedReason = "Group owner address missing.",
                helpText = "Group owner address missing."
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
        assertEquals(
            NearbyWifiDirectSocketControlsState(
                canStartServer = false,
                canConnectClient = false,
                canSendFrame = false,
                canSendAdapterFrame = false,
                canCloseSocket = false,
                connectHost = null,
                startServerBlockedReason = "Start server only on group owner.",
                connectClientBlockedReason = "Socket client needs the group owner IP address.",
                helpText = "Socket client needs the group owner IP address."
            ),
            nearbyWifiDirectSocketControlsState(
                runtimeStatus = wifiDirectRuntimeStatus(
                    connectionStatus = WifiDirectConnectionStatus(
                        state = WifiDirectConnectionState.CONNECTED,
                        groupFormed = WifiDirectGroupFormedState.YES,
                        role = WifiDirectConnectionRole.CLIENT,
                        groupOwnerAddress = "AA:BB:CC:DD:EE:01"
                    )
                ),
                diagnostics = WifiDirectSocketDiagnostics()
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
