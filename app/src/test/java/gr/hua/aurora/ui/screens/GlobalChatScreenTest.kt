package gr.hua.aurora.ui.screens

import gr.hua.aurora.protocol.GlobalMeshDeliveryResult
import gr.hua.aurora.protocol.GlobalMeshDiagnostics
import gr.hua.aurora.state.AuroraAvailabilityPreference
import gr.hua.aurora.ui.components.DebugInfoCardModel
import gr.hua.aurora.ui.components.DebugInfoItem
import gr.hua.aurora.ui.components.DebugInfoSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlobalChatScreenTest {
    @Test
    fun meshReachabilityTextShowsReachableCount() {
        assertEquals(
            "Reachable Aurora peers: 1",
            globalChatMeshReachabilityText(
                GlobalMeshDiagnostics(
                    reachablePeerCount = 1,
                    reachablePeerIds = listOf("peer-123"),
                    activeTransportPeerId = "peer-123",
                    seenMessageCount = 2,
                    onlyOneActiveTransportPeerSupported = true,
                    lastResult = null
                )
            )
        )
    }

    @Test
    fun transportRoutingTextShowsSenderSourceAndActivePeer() {
        assertEquals(
            "Transport sender: Android connector-backed | Active transport peer: peer-123",
            globalChatTransportRoutingText(
                transportSenderSourceLabel = "Android connector-backed",
                activeTransportPeerId = "peer-123"
            )
        )
        assertEquals(
            "Transport sender: NoOp | Active transport peer: None",
            globalChatTransportRoutingText(
                transportSenderSourceLabel = "NoOp",
                activeTransportPeerId = null
            )
        )
    }

    @Test
    fun meshLimitTextReportsSingleActivePeerLimitation() {
        assertEquals(
            "Only one active transport peer currently supported.",
            globalChatMeshLimitText(
                GlobalMeshDiagnostics(
                    reachablePeerCount = 1,
                    reachablePeerIds = listOf("peer-123"),
                    activeTransportPeerId = "peer-123",
                    seenMessageCount = 3,
                    onlyOneActiveTransportPeerSupported = true,
                    lastResult = null
                )
            )
        )
    }

    @Test
    fun meshStatusTextShowsExplicitMeshDeliveryState() {
        assertEquals(
            "Global mesh queued to active peer peer-123.",
            globalChatMeshStatusText(
                GlobalMeshDeliveryResult.QueuedToActivePeer("peer-123")
            )
        )
        assertEquals(
            "No reachable Aurora peers.",
            globalChatMeshStatusText(GlobalMeshDeliveryResult.NoReachablePeers)
        )
        assertEquals(
            "Mesh transport sender unavailable.",
            globalChatMeshStatusText(GlobalMeshDeliveryResult.SenderUnavailable)
        )
        assertEquals(
            "Public mesh connect-on-send failed for peer-321: connection did not reach ready state",
            globalChatMeshStatusText(
                GlobalMeshDeliveryResult.ConnectOnSendFailed(
                    peerId = "peer-321",
                    reason = "connection did not reach ready state"
                )
            )
        )
        assertEquals(
            "Global mesh relay skipped duplicate msg-1.",
            globalChatMeshStatusText(GlobalMeshDeliveryResult.SkippedDuplicate("msg-1"))
        )
        assertEquals(
            "Global mesh relay skipped source peer peer-123.",
            globalChatMeshStatusText(GlobalMeshDeliveryResult.SkippedSourcePeer("peer-123"))
        )
        assertEquals(
            "Global mesh relay stopped at TTL for msg-2.",
            globalChatMeshStatusText(GlobalMeshDeliveryResult.SkippedTtlExpired("msg-2"))
        )
        assertEquals(
            "Global mesh failed: writer unavailable",
            globalChatMeshStatusText(
                GlobalMeshDeliveryResult.Failed("writer unavailable")
            )
        )
    }

    @Test
    fun onlineWithoutQueuedMessagesShowsNoTransportNote() {
        assertNull(
            globalChatTransportNote(
                desiredAvailability = AuroraAvailabilityPreference.ONLINE,
                queuedOutgoingCount = 0,
                meshDeliveryResult = null
            )
        )
    }

    @Test
    fun connectOnSendTextShowsPendingOrDefaultState() {
        assertEquals(
            "Public mesh connect-on-send: pending for peer-123.",
            globalChatConnectOnSendText(
                diagnostics = GlobalMeshDiagnostics(
                    reachablePeerCount = 1,
                    reachablePeerIds = listOf("peer-123"),
                    activeTransportPeerId = null,
                    seenMessageCount = 0,
                    onlyOneActiveTransportPeerSupported = true,
                    lastResult = null
                ),
                lastConnectOnSendStatus = "Public mesh connect-on-send: pending for peer-123."
            )
        )
        assertEquals(
            "Public mesh connect-on-send: active peer already connected.",
            globalChatConnectOnSendText(
                diagnostics = GlobalMeshDiagnostics(
                    reachablePeerCount = 1,
                    reachablePeerIds = listOf("peer-123"),
                    activeTransportPeerId = "peer-123",
                    seenMessageCount = 0,
                    onlyOneActiveTransportPeerSupported = true,
                    lastResult = null
                ),
                lastConnectOnSendStatus = null
            )
        )
    }

    @Test
    fun onlineWithoutQueuedMessagesAndNoReachablePeerShowsReachabilityNote() {
        assertEquals(
            "Saved locally. No reachable Aurora peers.",
            globalChatTransportNote(
                desiredAvailability = AuroraAvailabilityPreference.ONLINE,
                queuedOutgoingCount = 0,
                meshDeliveryResult = GlobalMeshDeliveryResult.NoReachablePeers
            )
        )
    }

    @Test
    fun onlineWithQueuedMessagesAndNoReachablePeerShowsReachabilityNote() {
        assertEquals(
            "Queued locally until an Aurora peer is reachable.",
            globalChatTransportNote(
                desiredAvailability = AuroraAvailabilityPreference.ONLINE,
                queuedOutgoingCount = 2,
                meshDeliveryResult = GlobalMeshDeliveryResult.NoReachablePeers
            )
        )
    }

    @Test
    fun onlineWithQueuedMessagesAndQueuedMeshSendShowsNoExtraDeliveryNote() {
        assertNull(
            globalChatTransportNote(
                desiredAvailability = AuroraAvailabilityPreference.ONLINE,
                queuedOutgoingCount = 2,
                meshDeliveryResult = GlobalMeshDeliveryResult.QueuedToActivePeer("peer-123")
            )
        )
    }

    @Test
    fun offlineWithQueuedMessagesShowsOfflineQueuedNote() {
        assertEquals(
            "Offline: messages stay on this device until mesh delivery is available.",
            globalChatTransportNote(
                desiredAvailability = AuroraAvailabilityPreference.OFFLINE,
                queuedOutgoingCount = 3,
                meshDeliveryResult = GlobalMeshDeliveryResult.SenderUnavailable
            )
        )
    }

    @Test
    fun connectOnSendFailureKeepsQueuedLocalWordingTruthful() {
        assertEquals(
            "Queued locally until public mesh connect-on-send succeeds.",
            globalChatTransportNote(
                desiredAvailability = AuroraAvailabilityPreference.ONLINE,
                queuedOutgoingCount = 1,
                meshDeliveryResult = GlobalMeshDeliveryResult.ConnectOnSendFailed(
                    peerId = "peer-123",
                    reason = "connection did not reach ready state"
                )
            )
        )
    }

    @Test
    fun debugSectionsAreHiddenWhenDebugModeIsDisabled() {
        assertNull(
            buildGlobalChatDebugCard(
                showDebugDiagnostics = false,
                transportSenderSourceLabel = "Android connector-backed",
                globalMeshDiagnostics = GlobalMeshDiagnostics(
                    reachablePeerCount = 1,
                    reachablePeerIds = listOf("peer-123"),
                    activeTransportPeerId = "peer-123",
                    seenMessageCount = 0,
                    onlyOneActiveTransportPeerSupported = true,
                    lastResult = null
                ),
                lastIncomingMessageStatus = "Received public global message from peer-123.",
                lastConnectOnSendStatus = "Public mesh connect-on-send: active peer already connected.",
                lastGlobalMeshStatus = "Global mesh queued to active peer peer-123.",
                meshDeliveryResult = GlobalMeshDeliveryResult.QueuedToActivePeer("peer-123"),
                queuedOutgoingCount = 2
            )
        )
    }

    @Test
    fun debugCardShowsSingleGroupedStructureWhenDebugModeIsEnabled() {
        val card = buildGlobalChatDebugCard(
            showDebugDiagnostics = true,
            transportSenderSourceLabel = "Android connector-backed",
            globalMeshDiagnostics = GlobalMeshDiagnostics(
                reachablePeerCount = 1,
                reachablePeerIds = listOf("peer-123"),
                activeTransportPeerId = "peer-123",
                seenMessageCount = 0,
                onlyOneActiveTransportPeerSupported = true,
                lastResult = null
            ),
            lastIncomingMessageStatus = "Received public global message from peer-123.",
            lastConnectOnSendStatus = "Public mesh connect-on-send: active peer already connected.",
            lastGlobalMeshStatus = "Global mesh queued to active peer peer-123.",
            meshDeliveryResult = GlobalMeshDeliveryResult.QueuedToActivePeer("peer-123"),
            queuedOutgoingCount = 2
        )

        assertEquals(
            DebugInfoCardModel(
                title = "Debug",
                sections = listOf(
                    DebugInfoSection(
                        title = "Mesh",
                        items = listOf(
                            DebugInfoItem("Reachable", "1"),
                            DebugInfoItem("Active", "peer-123")
                        )
                    ),
                    DebugInfoSection(
                        title = "Events",
                        items = listOf(
                            DebugInfoItem("Send", "peer-123"),
                            DebugInfoItem("Incoming", "peer-123"),
                            DebugInfoItem("Connect", "ok")
                        )
                    ),
                    DebugInfoSection(
                        title = "Queue",
                        items = listOf(
                            DebugInfoItem("Pending", "2")
                        )
                    )
                )
            ),
            card
        )
    }

    @Test
    fun debugCardUsesTransportLineOnlyWhenNotOnDefaultAndroidPath() {
        val card = buildGlobalChatDebugCard(
            showDebugDiagnostics = true,
            transportSenderSourceLabel = "NoOp",
            globalMeshDiagnostics = GlobalMeshDiagnostics(
                reachablePeerCount = 0,
                reachablePeerIds = emptyList(),
                activeTransportPeerId = null,
                seenMessageCount = 0,
                onlyOneActiveTransportPeerSupported = true,
                lastResult = null
            ),
            lastIncomingMessageStatus = null,
            lastConnectOnSendStatus = null,
            lastGlobalMeshStatus = null,
            meshDeliveryResult = null,
            queuedOutgoingCount = 0
        )

        assertEquals(
            listOf(
                DebugInfoSection(
                    title = "Mesh",
                    items = listOf(
                        DebugInfoItem("Reachable", "0"),
                        DebugInfoItem("Active", "none"),
                        DebugInfoItem("Transport", "NoOp")
                    )
                ),
                DebugInfoSection(
                    title = "Events",
                    items = listOf(
                        DebugInfoItem("Send", "none"),
                        DebugInfoItem("Incoming", "none")
                    )
                ),
                DebugInfoSection(
                    title = "Queue",
                    items = listOf(
                        DebugInfoItem("Pending", "0")
                    )
                )
            ),
            requireNotNull(card).sections
        )
    }
}
