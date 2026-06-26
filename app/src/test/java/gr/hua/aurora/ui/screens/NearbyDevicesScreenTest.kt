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
import gr.hua.aurora.protocol.PeerIdentityExchangeSendResult
import gr.hua.aurora.protocol.PeerSessionRegistryDiagnostics
import gr.hua.aurora.ui.components.DebugInfoCardModel
import gr.hua.aurora.ui.components.DebugInfoItem
import gr.hua.aurora.ui.components.DebugInfoSection
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
            "Keys sent. Run on both devices.",
            nearbyIdentityExchangeStatusText(PeerIdentityExchangeSendResult.SubmittedLocally)
        )
        assertEquals(
            "Key exchange unavailable.",
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
            "Key exchange failed: writer unavailable",
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

        assertEquals("Alex tablet", nearbyContactDisplayName(namedDevice))
        assertEquals("Peer 10325476", nearbyContactDisplayName(unnamedAuroraDevice))
        assertEquals("Unknown BLE device", nearbyContactDisplayName(unknownDevice))
    }

    @Test
    fun nearbyContactStatusTextReflectsContactAndKeyReadiness() {
        assertNull(
            nearbyContactStatusText(
                isContact = false,
                hasReadyKeys = false
            )
        )
        assertEquals(
            "Contact | Keys missing",
            nearbyContactStatusText(
                isContact = true,
                hasReadyKeys = false
            )
        )
        assertEquals(
            "Contact | Keys ready",
            nearbyContactStatusText(
                isContact = true,
                hasReadyKeys = true
            )
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
                identityHandlerStatus = "Identity handler ready. Local agreement private key loaded.",
                peerSessionDiagnostics = diagnostics
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
}
