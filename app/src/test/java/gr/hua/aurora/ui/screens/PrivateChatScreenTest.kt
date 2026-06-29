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
}
