package gr.hua.aurora.ui.screens

import gr.hua.aurora.model.AuroraContact
import gr.hua.aurora.model.ChatMessage
import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.model.PrivateChatIdentity
import gr.hua.aurora.protocol.PeerSessionRegistryDiagnostics
import gr.hua.aurora.protocol.PrivateChatMessageSendResult
import gr.hua.aurora.ui.components.DebugInfoCardModel
import gr.hua.aurora.ui.components.DebugInfoItem
import gr.hua.aurora.ui.components.DebugInfoSection
import gr.hua.aurora.wifidirect.WifiDirectPermissionStatus
import gr.hua.aurora.wifidirect.WifiDirectReceiveBridgeDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectRuntimeStatus
import gr.hua.aurora.wifidirect.WifiDirectSendBridgeDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectTransportAdapterDiagnostics
import gr.hua.aurora.wifidirect.WifiDirectTransportAdapterState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateChatScreenTest {
    @Test
    fun privateChatShowsSelectedContactName() {
        val contact = AuroraContact(
            canonicalPeerId = "0d61e4a3c3441947",
            displayName = "Alex",
            createdAtMillis = 1_000L,
            hasSession = true
        )

        val content = buildPrivateChatScreenContent(
            requestedPeerId = contact.canonicalPeerId,
            contact = contact,
            privateChatIdentity = readyIdentity(contact.canonicalPeerId),
            hasRuntimeSession = true,
            isNearbyVisible = false
        )

        assertEquals("Alex", content.title)
        assertEquals("0d61e4a3c344...", content.shortPeerId)
        assertFalse(content.isMissingContact)
    }

    @Test
    fun privateChatReadyContentKeepsNormalModeClean() {
        val contact = AuroraContact(
            canonicalPeerId = "peer-123",
            displayName = "Alex",
            createdAtMillis = 1_000L,
            hasSession = true
        )

        val content = buildPrivateChatScreenContent(
            requestedPeerId = contact.canonicalPeerId,
            contact = contact,
            privateChatIdentity = readyIdentity(contact.canonicalPeerId),
            hasRuntimeSession = true,
            isNearbyVisible = false
        )

        assertNull(content.statusText)
        assertNull(content.helperText)
        assertTrue(content.isComposerEnabled)
        assertEquals("Private message", content.composerHint)
        assertEquals("No private messages yet.", content.emptyStateText)
    }

    @Test
    fun privateChatMissingSetupUsesProductFacingHint() {
        val contact = AuroraContact(
            canonicalPeerId = "peer-123",
            displayName = "Alex",
            createdAtMillis = 1_000L,
            hasSession = false
        )

        val content = buildPrivateChatScreenContent(
            requestedPeerId = contact.canonicalPeerId,
            contact = contact,
            privateChatIdentity = PrivateChatIdentity(
                canonicalPeerId = contact.canonicalPeerId,
                localProposalId = "local-peer-123",
                createdAtMillis = 1_000L,
                lastUpdatedMillis = 2_000L
            ),
            hasRuntimeSession = false,
            isNearbyVisible = false
        )

        assertEquals("Retry setup", content.statusText)
        assertEquals(
            "Not currently visible. Open Nearby to finish private chat setup.",
            content.helperText
        )
        assertFalse(content.isComposerEnabled)
        assertEquals("Setup needed before sending", content.composerHint)
        assertFalse(requireNotNull(content.helperText).contains("Transport", ignoreCase = true))
        assertFalse(requireNotNull(content.helperText).contains("Session", ignoreCase = true))
        assertFalse(requireNotNull(content.helperText).contains("Peer:", ignoreCase = true))
    }

    @Test
    fun privateChatRecoveredRuntimeSessionEnablesComposerAfterRestart() {
        val contact = AuroraContact(
            canonicalPeerId = "peer-123",
            displayName = "Alex",
            createdAtMillis = 1_000L,
            hasSession = false
        )

        val content = buildPrivateChatScreenContent(
            requestedPeerId = contact.canonicalPeerId,
            contact = contact,
            privateChatIdentity = readyIdentity(contact.canonicalPeerId),
            hasRuntimeSession = true,
            isNearbyVisible = false
        )

        assertNull(content.statusText)
        assertNull(content.helperText)
        assertTrue(content.isComposerEnabled)
        assertEquals("Private message", content.composerHint)
    }

    @Test
    fun privateChatHandlesMissingContactGracefully() {
        val content = buildPrivateChatScreenContent(
            requestedPeerId = "missing-peer-123456",
            contact = null,
            privateChatIdentity = null,
            hasRuntimeSession = false,
            isNearbyVisible = false
        )

        assertEquals("Contact not found", content.title)
        assertEquals("missing-peer...", content.shortPeerId)
        assertEquals("Contact not found", content.statusText)
        assertEquals(
            "Add this device from Nearby to start a private chat.",
            content.helperText
        )
        assertTrue(content.isMissingContact)
        assertFalse(content.isComposerEnabled)
    }

    @Test
    fun privateChatDebugCardShowsSingleGroupedStructureWhenEnabled() {
        val contact = AuroraContact(
            canonicalPeerId = "peer-123",
            displayName = "Alex",
            createdAtMillis = 1_000L,
            lastSeenMillis = 2_000L,
            hasSession = true
        )
        val messages = listOf(
            ChatMessage(
                id = "msg-1",
                threadId = "private:peer-123",
                senderId = "self",
                senderName = "Chris",
                text = "hello",
                createdAtMillis = 5_000L,
                status = MessageStatus.SENT,
                isOutgoing = true
            )
        )

        val card = buildPrivateChatDebugCard(
            showDebugDiagnostics = true,
            requestedPeerId = contact.canonicalPeerId,
            contact = contact,
            privateChatIdentity = readyIdentity(contact.canonicalPeerId),
            hasRuntimeSession = true,
            isNearbyVisible = true,
            messages = messages,
            lastDeliveryResult = PrivateChatMessageSendResult.SubmittedLocally,
            peerSessionDiagnostics = PeerSessionRegistryDiagnostics(
                establishedPeerIds = listOf("peer-123"),
                canonicalPeerIdByAlias = emptyMap()
            ),
            activeTransportPeerId = "peer-123",
            lastIdentityExchangeStatus = "Identity received from peer-123. Send yours back from this device.",
            wifiDirectRuntimeStatus = wifiDirectRuntimeStatus(),
            wifiDirectAdapterDiagnostics = WifiDirectTransportAdapterDiagnostics(
                state = WifiDirectTransportAdapterState.READY
            ),
            wifiDirectSendBridgeDiagnostics = WifiDirectSendBridgeDiagnostics(enabled = true),
            wifiDirectReceiveBridgeDiagnostics = WifiDirectReceiveBridgeDiagnostics(enabled = true),
            isComposerEnabled = true
        )

        assertEquals(
            DebugInfoCardModel(
                title = "Debug",
                sections = listOf(
                    DebugInfoSection(
                        title = "Target",
                        items = listOf(
                            DebugInfoItem("Peer", "peer-123"),
                            DebugInfoItem("Chat", "ready"),
                            DebugInfoItem("Local prop", "local-peer-1..."),
                            DebugInfoItem("Remote prop", "remote-peer-..."),
                            DebugInfoItem("Chat id", "chat-peer-12..."),
                            DebugInfoItem("Visible", "nearby"),
                            DebugInfoItem("Messages", "1"),
                        )
                    ),
                    DebugInfoSection(
                        title = "Runtime",
                        items = listOf(
                            DebugInfoItem("Session", "ready"),
                            DebugInfoItem("Active", "match"),
                            DebugInfoItem("Composer", "enabled"),
                            DebugInfoItem("Keys", "ready")
                        )
                    ),
                    DebugInfoSection(
                        title = "Wi-Fi Direct private",
                        items = listOf(
                            DebugInfoItem("Overall", "not wired yet", preferFullWidth = true),
                            DebugInfoItem("Path", "BLE only"),
                            DebugInfoItem("Adapter", "ready"),
                            DebugInfoItem("Send bridge", "enabled"),
                            DebugInfoItem("Receive bridge", "enabled"),
                            DebugInfoItem("Contact", "present"),
                            DebugInfoItem("Chat id", "ready"),
                            DebugInfoItem("Session", "ready"),
                            DebugInfoItem(
                                "Blocked",
                                "Private Wi-Fi Direct routing not enabled.",
                                preferFullWidth = true
                            ),
                            DebugInfoItem(
                                "Note",
                                "Private Chat still uses BLE. Wi-Fi Direct private routing not enabled.",
                                preferFullWidth = true
                            )
                        )
                    ),
                    DebugInfoSection(
                        title = "Events",
                        items = listOf(
                            DebugInfoItem("Last send", "queued"),
                            DebugInfoItem(
                                "Last identity",
                                "Identity received from peer-123. Send yours back from this device.",
                                preferFullWidth = true
                            )
                        )
                    )
                )
            ),
            card
        )
    }

    @Test
    fun privateChatDebugCardStaysHiddenWhenDebugModeIsDisabled() {
        assertNull(
            buildPrivateChatDebugCard(
                showDebugDiagnostics = false,
                requestedPeerId = "peer-123",
                contact = null,
                privateChatIdentity = null,
                hasRuntimeSession = false,
                isNearbyVisible = false,
                messages = emptyList(),
                lastDeliveryResult = null,
                peerSessionDiagnostics = PeerSessionRegistryDiagnostics(
                    establishedPeerIds = emptyList(),
                    canonicalPeerIdByAlias = emptyMap()
                ),
                activeTransportPeerId = null,
                lastIdentityExchangeStatus = null,
                wifiDirectRuntimeStatus = wifiDirectRuntimeStatus(),
                wifiDirectAdapterDiagnostics = WifiDirectTransportAdapterDiagnostics(),
                wifiDirectSendBridgeDiagnostics = WifiDirectSendBridgeDiagnostics(),
                wifiDirectReceiveBridgeDiagnostics = WifiDirectReceiveBridgeDiagnostics(),
                isComposerEnabled = false
            )
        )
    }

    @Test
    fun privateChatWifiDirectDiagnosticsDefaultToBleOnlyNotReadyState() {
        val diagnostics = privateChatWifiDirectDebugDiagnostics(
            contact = null,
            privateChatIdentity = null,
            hasRuntimeSession = false,
            runtimeStatus = wifiDirectRuntimeStatus(),
            adapterDiagnostics = WifiDirectTransportAdapterDiagnostics(),
            sendBridgeDiagnostics = WifiDirectSendBridgeDiagnostics(),
            receiveBridgeDiagnostics = WifiDirectReceiveBridgeDiagnostics()
        )

        assertEquals("not ready", diagnostics.readiness.overallStatus)
        assertEquals("BLE only", diagnostics.readiness.pathStatus)
        assertFalse(diagnostics.readiness.canAttemptWhenWired)
        assertFalse(diagnostics.readiness.isWired)
        assertEquals("Wi-Fi Direct adapter not ready.", diagnostics.readiness.blockedReason)
        assertFalse(diagnostics.guard.persistsRawSessionSecrets)
        assertFalse(diagnostics.guard.exposesPlaintextToRelays)
    }

    @Test
    fun privateChatWifiDirectDiagnosticsRequireReadyAdapter() {
        val diagnostics = privateChatWifiDirectDebugDiagnostics(
            contact = readyContact(),
            privateChatIdentity = readyIdentity("peer-123"),
            hasRuntimeSession = true,
            runtimeStatus = wifiDirectRuntimeStatus(),
            adapterDiagnostics = WifiDirectTransportAdapterDiagnostics(
                state = WifiDirectTransportAdapterState.NOT_READY
            ),
            sendBridgeDiagnostics = WifiDirectSendBridgeDiagnostics(enabled = true),
            receiveBridgeDiagnostics = WifiDirectReceiveBridgeDiagnostics(enabled = true)
        )

        assertEquals("Wi-Fi Direct adapter not ready.", diagnostics.readiness.blockedReason)
        assertFalse(diagnostics.readiness.canAttemptWhenWired)
    }

    @Test
    fun privateChatWifiDirectDiagnosticsRequireSendBridge() {
        val diagnostics = privateChatWifiDirectDebugDiagnostics(
            contact = readyContact(),
            privateChatIdentity = readyIdentity("peer-123"),
            hasRuntimeSession = true,
            runtimeStatus = wifiDirectRuntimeStatus(),
            adapterDiagnostics = WifiDirectTransportAdapterDiagnostics(
                state = WifiDirectTransportAdapterState.READY
            ),
            sendBridgeDiagnostics = WifiDirectSendBridgeDiagnostics(enabled = false),
            receiveBridgeDiagnostics = WifiDirectReceiveBridgeDiagnostics(enabled = true)
        )

        assertEquals(
            "Enable the Wi-Fi Direct send bridge first.",
            diagnostics.readiness.blockedReason
        )
        assertFalse(diagnostics.readiness.canAttemptWhenWired)
    }

    @Test
    fun privateChatWifiDirectDiagnosticsRequireReceiveBridge() {
        val diagnostics = privateChatWifiDirectDebugDiagnostics(
            contact = readyContact(),
            privateChatIdentity = readyIdentity("peer-123"),
            hasRuntimeSession = true,
            runtimeStatus = wifiDirectRuntimeStatus(),
            adapterDiagnostics = WifiDirectTransportAdapterDiagnostics(
                state = WifiDirectTransportAdapterState.READY
            ),
            sendBridgeDiagnostics = WifiDirectSendBridgeDiagnostics(enabled = true),
            receiveBridgeDiagnostics = WifiDirectReceiveBridgeDiagnostics(enabled = false)
        )

        assertEquals(
            "Enable the Wi-Fi Direct receive bridge on the receiver.",
            diagnostics.readiness.blockedReason
        )
        assertFalse(diagnostics.readiness.canAttemptWhenWired)
    }

    @Test
    fun privateChatWifiDirectDiagnosticsRequirePrivateChatId() {
        val diagnostics = privateChatWifiDirectDebugDiagnostics(
            contact = readyContact(),
            privateChatIdentity = PrivateChatIdentity(
                canonicalPeerId = "peer-123",
                localProposalId = "local-peer-123",
                createdAtMillis = 1_000L,
                lastUpdatedMillis = 2_000L
            ),
            hasRuntimeSession = true,
            runtimeStatus = wifiDirectRuntimeStatus(),
            adapterDiagnostics = WifiDirectTransportAdapterDiagnostics(
                state = WifiDirectTransportAdapterState.READY
            ),
            sendBridgeDiagnostics = WifiDirectSendBridgeDiagnostics(enabled = true),
            receiveBridgeDiagnostics = WifiDirectReceiveBridgeDiagnostics(enabled = true)
        )

        assertEquals("Private chat id required.", diagnostics.readiness.blockedReason)
        assertEquals("missing", diagnostics.privateChatIdStatus)
    }

    @Test
    fun privateChatWifiDirectDiagnosticsRequireRuntimeSession() {
        val diagnostics = privateChatWifiDirectDebugDiagnostics(
            contact = readyContact(),
            privateChatIdentity = readyIdentity("peer-123"),
            hasRuntimeSession = false,
            runtimeStatus = wifiDirectRuntimeStatus(),
            adapterDiagnostics = WifiDirectTransportAdapterDiagnostics(
                state = WifiDirectTransportAdapterState.READY
            ),
            sendBridgeDiagnostics = WifiDirectSendBridgeDiagnostics(enabled = true),
            receiveBridgeDiagnostics = WifiDirectReceiveBridgeDiagnostics(enabled = true)
        )

        assertEquals("Private session required.", diagnostics.readiness.blockedReason)
        assertEquals("missing", diagnostics.sessionStatus)
    }

    @Test
    fun privateChatWifiDirectDiagnosticsStayDisabledAfterResetStyleDefaults() {
        val diagnostics = privateChatWifiDirectDebugDiagnostics(
            contact = readyContact(),
            privateChatIdentity = readyIdentity("peer-123"),
            hasRuntimeSession = true,
            runtimeStatus = wifiDirectRuntimeStatus(),
            adapterDiagnostics = WifiDirectTransportAdapterDiagnostics(),
            sendBridgeDiagnostics = WifiDirectSendBridgeDiagnostics(),
            receiveBridgeDiagnostics = WifiDirectReceiveBridgeDiagnostics()
        )

        assertEquals("disabled", diagnostics.adapterStatus)
        assertEquals("disabled", diagnostics.sendBridgeStatus)
        assertEquals("disabled", diagnostics.receiveBridgeStatus)
        assertEquals("not ready", diagnostics.readiness.overallStatus)
        assertFalse(diagnostics.readiness.canAttemptWhenWired)
    }

    @Test
    fun privateChatCanonicalPeerIdResolvesAlias() {
        val diagnostics = PeerSessionRegistryDiagnostics(
            establishedPeerIds = listOf("canonical-peer"),
            canonicalPeerIdByAlias = mapOf("friendly-peer" to "canonical-peer")
        )

        assertEquals(
            "canonical-peer",
            privateChatCanonicalPeerId("friendly-peer", diagnostics)
        )
        assertEquals(
            "canonical-peer",
            privateChatCanonicalPeerId("canonical-peer", diagnostics)
        )
    }

    @Test
    fun privateChatDebugDeliveryValueStaysCompactAndSafe() {
        assertEquals("queued", privateChatDebugDeliveryValue(PrivateChatMessageSendResult.SubmittedLocally))
        assertEquals("setup needed", privateChatDebugDeliveryValue(PrivateChatMessageSendResult.KeysUnavailable))
        assertEquals("not reachable", privateChatDebugDeliveryValue(PrivateChatMessageSendResult.ContactNotReachable))
        assertEquals(
            "failed",
            privateChatDebugDeliveryValue(
                PrivateChatMessageSendResult.Failed("transport failed")
            )
        )
    }

    @Test
    fun privateChatDebugIdentifierValueStaysShortAndSafe() {
        assertEquals("missing", privateChatDebugIdentifierValue(null))
        assertEquals("chat-peer-12...", privateChatDebugIdentifierValue("chat-peer-123"))
        assertEquals("peer-123", privateChatDebugIdentifierValue("peer-123"))
    }

    @Test
    fun customChatNameOverridesContactTitleWhenPresent() {
        val contact = AuroraContact(
            canonicalPeerId = "peer-123",
            displayName = "Alex",
            createdAtMillis = 1_000L,
            hasSession = true
        )
        val content = buildPrivateChatScreenContent(
            requestedPeerId = contact.canonicalPeerId,
            contact = contact,
            privateChatIdentity = readyIdentity(contact.canonicalPeerId).copy(
                customChatName = "Family chat"
            ),
            hasRuntimeSession = true,
            isNearbyVisible = false
        )

        assertEquals("Family chat", content.title)
    }

    private fun readyIdentity(peerId: String): PrivateChatIdentity {
        return PrivateChatIdentity(
            canonicalPeerId = peerId,
            privateChatId = "chat-$peerId",
            localProposalId = "local-$peerId",
            remoteProposalId = "remote-$peerId",
            createdAtMillis = 1_000L,
            lastUpdatedMillis = 2_000L
        )
    }

    private fun readyContact(): AuroraContact {
        return AuroraContact(
            canonicalPeerId = "peer-123",
            displayName = "Alex",
            createdAtMillis = 1_000L,
            hasSession = true
        )
    }

    private fun wifiDirectRuntimeStatus(): WifiDirectRuntimeStatus {
        return WifiDirectRuntimeStatus(
            permissionStatus = WifiDirectPermissionStatus(
                requiredPermissions = emptySet(),
                missingPermissions = emptySet(),
                isWifiDirectSupported = true,
                isWifiEnabled = true,
                isWifiP2pEnabled = true
            )
        )
    }
}
