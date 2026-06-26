package gr.hua.aurora.data.persistence

import gr.hua.aurora.model.AuroraContact
import gr.hua.aurora.model.ChatMessage
import gr.hua.aurora.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuroraPersistenceStoreTest {
    @Test
    fun savingAndLoadingContactsPreservesContactFields() {
        val store = InMemoryAuroraPersistenceStore()

        store.saveContact(
            PersistedContact(
                canonicalPeerId = "peer-123",
                displayName = "Alex",
                createdAtMillis = 100L,
                lastSeenMillis = 200L
            )
        )

        val loaded = store.load().contacts.single()

        assertEquals("peer-123", loaded.canonicalPeerId)
        assertEquals("Alex", loaded.displayName)
        assertEquals(100L, loaded.createdAtMillis)
        assertEquals(200L, loaded.lastSeenMillis)
    }

    @Test
    fun restoredContactDoesNotImplyKeysReady() {
        val restored = PersistedContact(
            canonicalPeerId = "peer-123",
            displayName = "Alex",
            createdAtMillis = 100L,
            lastSeenMillis = 200L
        ).toRestoredContact()

        assertEquals("peer-123", restored.canonicalPeerId)
        assertEquals("Alex", restored.displayName)
        assertFalse(restored.hasSession)
    }

    @Test
    fun savingAndLoadingPrivateMessagesKeepsThemScopedToTheRightPeer() {
        val store = InMemoryAuroraPersistenceStore()

        val alexMessage = ChatMessage(
            id = "private-alex-1",
            threadId = "private:alex",
            senderId = "self",
            senderName = "Me",
            text = "hello alex",
            createdAtMillis = 100L,
            status = MessageStatus.SENT,
            isOutgoing = true
        ).toPersistedChatMessage()
        val mariaMessage = ChatMessage(
            id = "private-maria-1",
            threadId = "private:maria",
            senderId = "maria",
            senderName = "Maria",
            text = "hello back",
            createdAtMillis = 200L,
            status = MessageStatus.RECEIVED,
            isOutgoing = false
        ).toPersistedChatMessage()

        store.saveMessage(alexMessage)
        store.saveMessage(mariaMessage)

        val loaded = store.load().messages

        assertEquals(setOf("alex", "maria"), loaded.mapNotNull { it.peerId }.toSet())
        assertEquals(
            "private:alex",
            loaded.first { it.messageId == "private-alex-1" }.toRestoredChatMessage().threadId
        )
        assertEquals(
            "private:maria",
            loaded.first { it.messageId == "private-maria-1" }.toRestoredChatMessage().threadId
        )
    }

    @Test
    fun savingAndLoadingGlobalMessagesKeepsGlobalThreadMarker() {
        val store = InMemoryAuroraPersistenceStore()

        store.saveMessage(
            ChatMessage(
                id = "global-1",
                threadId = "global",
                senderId = "self",
                senderName = "Me",
                text = "public hello",
                createdAtMillis = 100L,
                status = MessageStatus.SENT,
                isOutgoing = true
            ).toPersistedChatMessage()
        )

        val loaded = store.load().messages.single()

        assertEquals(PersistedChatThreadType.GLOBAL, loaded.threadType)
        assertNull(loaded.peerId)
        assertEquals("global", loaded.toRestoredChatMessage().threadId)
    }

    @Test
    fun restoredQueuedAndDeliveredMessagesNeverComeBackAsDelivered() {
        val queued = PersistedChatMessage(
            messageId = "queued-1",
            threadType = PersistedChatThreadType.PRIVATE,
            peerId = "alex",
            text = "queued",
            createdAtMillis = 100L,
            direction = PersistedMessageDirection.OUTGOING,
            status = MessageStatus.QUEUED,
            senderId = "self",
            senderName = "Me"
        ).toRestoredChatMessage()
        val delivered = PersistedChatMessage(
            messageId = "delivered-1",
            threadType = PersistedChatThreadType.GLOBAL,
            text = "sent before",
            createdAtMillis = 200L,
            direction = PersistedMessageDirection.OUTGOING,
            status = MessageStatus.DELIVERED,
            senderId = "self",
            senderName = "Me"
        ).toRestoredChatMessage()

        assertEquals(MessageStatus.FAILED, queued.status)
        assertEquals(MessageStatus.SENT, delivered.status)
        assertTrue(queued.status != MessageStatus.DELIVERED)
        assertTrue(delivered.status != MessageStatus.DELIVERED)
    }

    @Test
    fun clearRemovesPersistedContactsAndMessages() {
        val store = InMemoryAuroraPersistenceStore()
        store.saveContact(
            PersistedContact(
                canonicalPeerId = "peer-123",
                displayName = "Alex",
                createdAtMillis = 100L
            )
        )
        store.saveMessage(
            PersistedChatMessage(
                messageId = "global-1",
                threadType = PersistedChatThreadType.GLOBAL,
                text = "public hello",
                createdAtMillis = 100L,
                direction = PersistedMessageDirection.OUTGOING,
                status = MessageStatus.SENT,
                senderId = "self",
                senderName = "Me"
            )
        )

        store.clear()

        val loaded = store.load()
        assertTrue(loaded.contacts.isEmpty())
        assertTrue(loaded.messages.isEmpty())
    }

    @Test
    fun persistedModelsDoNotContainPrivateKeyOrSessionMaterialFields() {
        val contactFields = PersistedContact::class.java.declaredFields
            .map { it.name }
            .filterNot { it == "\$stable" }
            .sorted()
        val messageFields = PersistedChatMessage::class.java.declaredFields
            .map { it.name }
            .filterNot { it == "\$stable" }
            .sorted()

        assertEquals(
            listOf("canonicalPeerId", "createdAtMillis", "displayName", "lastSeenMillis"),
            contactFields
        )
        assertEquals(
            listOf(
                "createdAtMillis",
                "direction",
                "messageId",
                "peerId",
                "senderId",
                "senderName",
                "status",
                "text",
                "threadType"
            ),
            messageFields
        )
    }

    @Test
    fun contactConversionDropsRuntimeSessionState() {
        val persisted = AuroraContact(
            canonicalPeerId = "peer-123",
            displayName = "Alex",
            createdAtMillis = 100L,
            lastSeenMillis = 200L,
            hasSession = true
        ).toPersistedContact()

        assertEquals("peer-123", persisted.canonicalPeerId)
        assertEquals("Alex", persisted.displayName)
        assertEquals(100L, persisted.createdAtMillis)
        assertEquals(200L, persisted.lastSeenMillis)
    }
}
