package gr.hua.aurora.ui.screens

import gr.hua.aurora.model.AuroraContact
import gr.hua.aurora.model.PrivateChatIdentity
import gr.hua.aurora.protocol.PeerSessionRegistryDiagnostics
import gr.hua.aurora.ui.components.DebugInfoCardModel
import gr.hua.aurora.ui.components.DebugInfoItem
import gr.hua.aurora.ui.components.DebugInfoSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ContactsScreenTest {
    @Test
    fun contactsNormalModeUsesProductFacingReadinessText() {
        assertEquals(
            "Private chat ready",
            contactsProductStatusText(
                isPrivateChatReady = true,
                hasPrivateChatSetup = true
            )
        )
        assertEquals(
            "Retry setup",
            contactsProductStatusText(
                isPrivateChatReady = false,
                hasPrivateChatSetup = true
            )
        )
        assertEquals(
            "Setup needed",
            contactsProductStatusText(
                isPrivateChatReady = false,
                hasPrivateChatSetup = false
            )
        )
        assertFalse(
            contactsProductStatusText(
                isPrivateChatReady = true,
                hasPrivateChatSetup = true
            ).contains("Keys", ignoreCase = true)
        )
        assertFalse(
            contactsProductStatusText(
                isPrivateChatReady = false,
                hasPrivateChatSetup = true
            ).contains("Session", ignoreCase = true)
        )
        assertFalse(
            contactsProductStatusText(
                isPrivateChatReady = false,
                hasPrivateChatSetup = false
            ).contains("Transport", ignoreCase = true)
        )
        assertFalse(
            contactsProductStatusText(
                isPrivateChatReady = false,
                hasPrivateChatSetup = false
            ).contains("Peer", ignoreCase = true)
        )
    }

    @Test
    fun contactsVisibilityTextSeparatesLivePresenceFromStoredContactHistory() {
        val seenContact = AuroraContact(
            canonicalPeerId = "peer-123",
            displayName = "Alex",
            createdAtMillis = 1_000L,
            lastSeenMillis = 2_000L
        )
        val unseenContact = seenContact.copy(
            canonicalPeerId = "peer-456",
            lastSeenMillis = null
        )

        assertEquals(
            "Seen nearby",
            contactsVisibilityText(
                contact = seenContact,
                isNearbyVisible = true
            )
        )
        assertEquals(
            "Not currently visible",
            contactsVisibilityText(
                contact = seenContact,
                isNearbyVisible = false
            )
        )
        assertNull(
            contactsVisibilityText(
                contact = unseenContact,
                isNearbyVisible = false
            )
        )
    }

    @Test
    fun contactSummaryUsesRuntimeSessionInsteadOfPersistedContactFlag() {
        val contact = AuroraContact(
            canonicalPeerId = "peer-123",
            displayName = "Alex",
            createdAtMillis = 1_000L,
            hasSession = false
        )
        val identity = PrivateChatIdentity(
            canonicalPeerId = "peer-123",
            privateChatId = "chat-123",
            localProposalId = "local-123",
            remoteProposalId = "remote-123",
            createdAtMillis = 1_000L,
            lastUpdatedMillis = 2_000L
        )

        val restoringSummary = buildContactChatSummary(
            contact = contact,
            identity = identity,
            hasRuntimeSession = false,
            isNearbyVisible = false
        )
        val recoveredSummary = buildContactChatSummary(
            contact = contact,
            identity = identity,
            hasRuntimeSession = true,
            isNearbyVisible = true
        )

        assertEquals(false, restoringSummary.isPrivateChatReady)
        assertEquals(true, restoringSummary.hasPrivateChatSetup)
        assertEquals(true, recoveredSummary.isPrivateChatReady)
        assertEquals("Seen nearby", recoveredSummary.visibilityText)
    }

    @Test
    fun contactsDebugCardIsHiddenWhenDebugModeIsDisabled() {
        assertNull(
            buildContactsDebugCard(
                showDebugDiagnostics = false,
                contacts = emptyList(),
                privateChatIdentitiesByPeerId = emptyMap(),
                nearbyVisiblePeerIds = emptySet(),
                peerSessionDiagnostics = PeerSessionRegistryDiagnostics(
                    establishedPeerIds = emptyList(),
                    canonicalPeerIdByAlias = emptyMap()
                ),
                lastIdentityExchangeStatus = null
            )
        )
    }

    @Test
    fun contactsDebugCardShowsSingleGroupedStructureWhenEnabled() {
        val contacts = listOf(
            AuroraContact(
                canonicalPeerId = "peer-123456789abc",
                displayName = "Alex",
                createdAtMillis = 1_000L,
                lastSeenMillis = 2_000L,
                hasSession = true
            ),
            AuroraContact(
                canonicalPeerId = "peer-456",
                displayName = "Nina",
                createdAtMillis = 3_000L,
                hasSession = false
            )
        )

        val card = buildContactsDebugCard(
            showDebugDiagnostics = true,
            contacts = contacts,
            privateChatIdentitiesByPeerId = mapOf(
                "peer-123456789abc" to PrivateChatIdentity(
                    canonicalPeerId = "peer-123456789abc",
                    privateChatId = "chat-123",
                    localProposalId = "local-123",
                    remoteProposalId = "remote-123",
                    createdAtMillis = 1_000L,
                    lastUpdatedMillis = 2_000L
                )
            ),
            peerSessionDiagnostics = PeerSessionRegistryDiagnostics(
                establishedPeerIds = listOf("peer-123456789abc"),
                canonicalPeerIdByAlias = mapOf("alex" to "peer-123456789abc")
            ),
            nearbyVisiblePeerIds = setOf("peer-123456789abc"),
            lastIdentityExchangeStatus = "Identity sent. Run on both devices."
        )

        assertEquals(
            DebugInfoCardModel(
                title = "Debug",
                sections = listOf(
                    DebugInfoSection(
                        title = "Contacts",
                        items = listOf(
                            DebugInfoItem("Count", "2"),
                            DebugInfoItem("Ready", "1"),
                            DebugInfoItem("Chats", "1"),
                            DebugInfoItem("Visible", "1"),
                            DebugInfoItem("Seen", "1"),
                            DebugInfoItem("Sessions", "1"),
                            DebugInfoItem("Peers", "peer-1234567..., peer-456", preferFullWidth = true),
                            DebugInfoItem(
                                "Last exchange",
                                "Identity sent. Run on both devices.",
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
    fun contactChatPeerIdUsesCanonicalPeerId() {
        val contact = AuroraContact(
            canonicalPeerId = "peer-123",
            displayName = "Alex",
            createdAtMillis = 1_000L,
            hasSession = true
        )

        assertEquals("peer-123", contactChatPeerId(contact))
    }
}
