package gr.hua.aurora.ui.screens

import gr.hua.aurora.model.AuroraContact
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactsScreenTest {
    @Test
    fun contactsKeyStatusTextReflectsSessionReadiness() {
        val readyContact = AuroraContact(
            canonicalPeerId = "peer-123",
            displayName = "Alex",
            createdAtMillis = 1000L,
            hasSession = true
        )
        val missingContact = readyContact.copy(
            canonicalPeerId = "peer-456",
            hasSession = false
        )

        assertEquals("Keys ready", contactsKeyStatusText(readyContact))
        assertEquals("Keys missing", contactsKeyStatusText(missingContact))
    }

    @Test
    fun contactChatPeerIdUsesCanonicalPeerId() {
        val contact = AuroraContact(
            canonicalPeerId = "peer-123",
            displayName = "Alex",
            createdAtMillis = 1000L,
            hasSession = true
        )

        assertEquals("peer-123", contactChatPeerId(contact))
    }
}
