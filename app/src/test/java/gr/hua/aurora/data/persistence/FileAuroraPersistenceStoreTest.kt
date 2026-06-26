package gr.hua.aurora.data.persistence

import gr.hua.aurora.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class FileAuroraPersistenceStoreTest {
    @Test
    fun fileStoreSavesAndLoadsContactsAndMessages() {
        val tempDirectory = Files.createTempDirectory("aurora-persistence-test").toFile()
        val stateFile = tempDirectory.resolve("aurora_state_store_v1.txt")
        val store = FileAuroraPersistenceStore(stateFile)

        store.replaceAll(
            PersistedAuroraState(
                contacts = listOf(
                    PersistedContact(
                        canonicalPeerId = "peer-123",
                        displayName = "Alex",
                        createdAtMillis = 100L,
                        lastSeenMillis = 200L
                    )
                ),
                messages = listOf(
                    PersistedChatMessage(
                        messageId = "msg-1",
                        threadType = PersistedChatThreadType.PRIVATE,
                        peerId = "peer-123",
                        text = "hello",
                        createdAtMillis = 300L,
                        direction = PersistedMessageDirection.OUTGOING,
                        status = MessageStatus.QUEUED,
                        senderId = "self",
                        senderName = "Me"
                    )
                )
            )
        )

        val restored = FileAuroraPersistenceStore(stateFile).load()

        assertEquals(1, restored.contacts.size)
        assertEquals(1, restored.messages.size)
        assertEquals("peer-123", restored.contacts.single().canonicalPeerId)
        assertEquals("Alex", restored.contacts.single().displayName)
        assertEquals("msg-1", restored.messages.single().messageId)
        assertEquals("peer-123", restored.messages.single().peerId)
        assertEquals(MessageStatus.QUEUED, restored.messages.single().status)
    }

    @Test
    fun fileStoreClearRemovesPersistedState() {
        val tempDirectory = Files.createTempDirectory("aurora-persistence-clear").toFile()
        val stateFile = tempDirectory.resolve("aurora_state_store_v1.txt")
        val store = FileAuroraPersistenceStore(stateFile)

        store.saveContact(
            PersistedContact(
                canonicalPeerId = "peer-123",
                displayName = "Alex",
                createdAtMillis = 100L
            )
        )
        assertTrue(stateFile.exists())

        store.clear()

        assertFalse(stateFile.exists())
        val restored = store.load()
        assertTrue(restored.contacts.isEmpty())
        assertTrue(restored.messages.isEmpty())
    }

    @Test
    fun fileStoreRoundTripsTabsNewlinesAndUnicodeWithoutPlatformBase64() {
        val tempDirectory = Files.createTempDirectory("aurora-persistence-escaped").toFile()
        val stateFile = tempDirectory.resolve("aurora_state_store_v1.txt")
        val store = FileAuroraPersistenceStore(stateFile)

        store.replaceAll(
            PersistedAuroraState(
                contacts = listOf(
                    PersistedContact(
                        canonicalPeerId = "peer-\t123",
                        displayName = "Alex\nΚαλημερα",
                        createdAtMillis = 100L,
                        lastSeenMillis = 200L
                    )
                ),
                messages = listOf(
                    PersistedChatMessage(
                        messageId = "msg-\t1",
                        threadType = PersistedChatThreadType.PRIVATE,
                        peerId = "peer-\t123",
                        text = "hello\nworld\t!",
                        createdAtMillis = 300L,
                        direction = PersistedMessageDirection.OUTGOING,
                        status = MessageStatus.QUEUED,
                        senderId = "self",
                        senderName = "Me\nUser"
                    )
                )
            )
        )

        val restored = store.load()

        assertEquals("peer-\t123", restored.contacts.single().canonicalPeerId)
        assertEquals("Alex\nΚαλημερα", restored.contacts.single().displayName)
        assertEquals("msg-\t1", restored.messages.single().messageId)
        assertEquals("hello\nworld\t!", restored.messages.single().text)
        assertEquals("Me\nUser", restored.messages.single().senderName)
    }
}
