package gr.hua.aurora.ui.screens

import gr.hua.aurora.model.AuroraContact
import gr.hua.aurora.protocol.PrivateChatMessageSendResult
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
            contact = contact
        )

        assertEquals("Alex", content.title)
        assertEquals("0d61e4a3c344...", content.shortPeerId)
        assertFalse(content.isMissingContact)
    }

    @Test
    fun privateChatComposerIsEnabledWhenKeysAreReady() {
        val contact = AuroraContact(
            canonicalPeerId = "peer-123",
            displayName = "Alex",
            createdAtMillis = 1_000L,
            hasSession = true
        )

        val content = buildPrivateChatScreenContent(
            requestedPeerId = contact.canonicalPeerId,
            contact = contact
        )

        assertEquals("Keys ready", content.keyStatusText)
        assertEquals("Private chat setup is ready.", content.setupText)
        assertTrue(content.isComposerEnabled)
        assertEquals("Private message", content.composerHint)
    }

    @Test
    fun privateChatComposerIsDisabledWhenKeysAreMissing() {
        val contact = AuroraContact(
            canonicalPeerId = "peer-123",
            displayName = "Alex",
            createdAtMillis = 1_000L,
            hasSession = false
        )

        val content = buildPrivateChatScreenContent(
            requestedPeerId = contact.canonicalPeerId,
            contact = contact
        )

        assertEquals("Keys missing", content.keyStatusText)
        assertEquals(
            "Exchange keys from Nearby before sending private messages.",
            content.setupText
        )
        assertFalse(content.isComposerEnabled)
        assertEquals(
            "Exchange keys from Nearby before sending private messages.",
            content.composerHint
        )
    }

    @Test
    fun privateChatHandlesMissingContactGracefully() {
        val content = buildPrivateChatScreenContent(
            requestedPeerId = "missing-peer-123456",
            contact = null
        )

        assertEquals("Contact not found", content.title)
        assertEquals("missing-peer...", content.shortPeerId)
        assertNull(content.keyStatusText)
        assertEquals(
            "Open Nearby or Contacts and select a saved contact first.",
            content.setupText
        )
        assertTrue(content.isMissingContact)
        assertFalse(content.shouldShowComposer)
        assertFalse(content.isComposerEnabled)
    }

    @Test
    fun privateChatDeliveryStringsStaySafeAndShort() {
        assertEquals(
            "Keys unavailable.",
            privateChatDeliveryStatusText(PrivateChatMessageSendResult.KeysUnavailable)
        )
        assertEquals(
            "Contact not reachable.",
            privateChatDeliveryStatusText(PrivateChatMessageSendResult.ContactNotReachable)
        )
        assertFalse(
            privateChatDeliveryStatusText(PrivateChatMessageSendResult.KeysUnavailable)
                .contains("private key", ignoreCase = true)
        )
        assertFalse(
            privateChatDeliveryStatusText(
                PrivateChatMessageSendResult.Failed("transport failed")
            ).contains("session material", ignoreCase = true)
        )
    }
}
