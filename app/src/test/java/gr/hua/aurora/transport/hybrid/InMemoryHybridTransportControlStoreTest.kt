package gr.hua.aurora.transport.hybrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryHybridTransportControlStoreTest {
    @Test
    fun storesWifiDirectOffer() {
        val store = InMemoryHybridTransportControlStore()
        val message = offerMessage(
            sessionId = "offer-session-1",
            createdAtMillis = 1_722_000_001L
        )

        val result = store.record(
            peerId = "peer-offer-1",
            message = message
        )

        assertEquals(HybridTransportControlStore.RecordResult.Stored, result)
        assertEquals(
            message,
            requireNotNull(
                store.snapshot()
                    .sessionStateFor("peer-offer-1", "offer-session-1")
            ).latestOffer
        )
    }

    @Test
    fun storesWifiDirectAccept() {
        val store = InMemoryHybridTransportControlStore()
        val message = acceptMessage(
            sessionId = "accept-session-1",
            createdAtMillis = 1_722_000_002L
        )

        store.record(
            peerId = "peer-accept-1",
            message = message
        )

        assertEquals(
            message,
            requireNotNull(
                store.snapshot()
                    .sessionStateFor("peer-accept-1", "accept-session-1")
            ).latestAccept
        )
    }

    @Test
    fun storesWifiDirectSocketHint() {
        val store = InMemoryHybridTransportControlStore()
        val message = socketHintMessage(
            sessionId = "socket-session-1",
            createdAtMillis = 1_722_000_003L,
            groupOwnerAddress = "192.168.49.1",
            socketPort = 8988
        )

        store.record(
            peerId = "peer-socket-1",
            message = message
        )

        assertEquals(
            message,
            requireNotNull(
                store.snapshot()
                    .sessionStateFor("peer-socket-1", "socket-session-1")
            ).latestSocketHint
        )
    }

    @Test
    fun latestIssuedAtWinsForDuplicatePeerSessionType() {
        val store = InMemoryHybridTransportControlStore()
        val older = offerMessage(
            sessionId = "dup-session-1",
            createdAtMillis = 1_722_000_010L,
            publicPeerIdHint = "peer-old"
        )
        val newer = offerMessage(
            sessionId = "dup-session-1",
            createdAtMillis = 1_722_000_011L,
            publicPeerIdHint = "peer-new"
        )

        store.record("peer-dup-1", older)
        val result = store.record("peer-dup-1", newer)

        assertEquals(HybridTransportControlStore.RecordResult.Stored, result)
        assertEquals(
            newer,
            requireNotNull(
                store.snapshot()
                    .sessionStateFor("peer-dup-1", "dup-session-1")
            ).latestOffer
        )
    }

    @Test
    fun olderDuplicateDoesNotOverwriteNewerState() {
        val store = InMemoryHybridTransportControlStore()
        val newer = acceptMessage(
            sessionId = "dup-session-2",
            createdAtMillis = 1_722_000_020L,
            publicPeerIdHint = "peer-newer"
        )
        val older = acceptMessage(
            sessionId = "dup-session-2",
            createdAtMillis = 1_722_000_019L,
            publicPeerIdHint = "peer-older"
        )

        store.record("peer-dup-2", newer)
        val result = store.record("peer-dup-2", older)

        assertEquals(HybridTransportControlStore.RecordResult.IgnoredOlderMessage, result)
        assertEquals(
            newer,
            requireNotNull(
                store.snapshot()
                    .sessionStateFor("peer-dup-2", "dup-session-2")
            ).latestAccept
        )
    }

    @Test
    fun sessionIdIsPreservedExactly() {
        val store = InMemoryHybridTransportControlStore()
        val message = offerMessage(
            sessionId = "session:Alpha|Beta/0123",
            createdAtMillis = 1_722_000_030L
        )

        store.record("peer-session-1", message)

        assertEquals(
            "session:Alpha|Beta/0123",
            requireNotNull(
                store.snapshot()
                    .sessionStateFor("peer-session-1", "session:Alpha|Beta/0123")
            ).latestOffer?.sessionId
        )
    }

    @Test
    fun peerHintIsPreservedExactly() {
        val store = InMemoryHybridTransportControlStore()
        val message = acceptMessage(
            sessionId = "peer-hint-session-1",
            createdAtMillis = 1_722_000_040L,
            publicPeerIdHint = "peer:with/slash+plus=="
        )

        store.record("peer-hint-1", message)

        assertEquals(
            "peer:with/slash+plus==",
            requireNotNull(
                store.snapshot()
                    .sessionStateFor("peer-hint-1", "peer-hint-session-1")
            ).latestAccept?.publicPeerIdHint
        )
    }

    @Test
    fun optionalSocketFieldsArePreservedExactly() {
        val store = InMemoryHybridTransportControlStore()
        val message = socketHintMessage(
            sessionId = "socket-session-2",
            createdAtMillis = 1_722_000_050L,
            groupOwnerAddress = "fe80::1234",
            socketPort = 65535
        )

        store.record("peer-socket-2", message)

        val storedMessage = requireNotNull(
            store.snapshot()
                .sessionStateFor("peer-socket-2", "socket-session-2")
        ).latestSocketHint

        assertEquals("fe80::1234", storedMessage?.groupOwnerAddress)
        assertEquals(65535, storedMessage?.socketPort)
    }

    @Test
    fun snapshotCannotMutateInternalStoreState() {
        val store = InMemoryHybridTransportControlStore()
        val message = offerMessage(
            sessionId = "snapshot-session-1",
            createdAtMillis = 1_722_000_060L
        )
        store.record("peer-snapshot-1", message)

        val snapshot = store.snapshot()
        val mutablePeerMap = snapshot.sessionsByPeerId.toMutableMap()
        mutablePeerMap.clear()

        assertNotNull(
            store.snapshot()
                .sessionStateFor("peer-snapshot-1", "snapshot-session-1")
        )
    }

    @Test
    fun recordingControlMessagesIsPassiveAndOnlyUpdatesMemory() {
        val store = InMemoryHybridTransportControlStore()
        val message = socketHintMessage(
            sessionId = "passive-session-1",
            createdAtMillis = 1_722_000_070L,
            groupOwnerAddress = "192.168.49.70",
            socketPort = 9070
        )

        val result = store.record("peer-passive-1", message)
        val state = store.snapshot()

        assertEquals(HybridTransportControlStore.RecordResult.Stored, result)
        assertTrue(state.sessionsByPeerId.containsKey("peer-passive-1"))
        assertNull(
            state.sessionStateFor("peer-passive-1", "passive-session-1")?.latestAccept
        )
    }

    private fun offerMessage(
        sessionId: String,
        createdAtMillis: Long,
        publicPeerIdHint: String = "peer-offer-hint"
    ): HybridTransportControlMessage {
        return HybridTransportControlMessage(
            messageType = HybridTransportControlMessage.MessageType.WIFI_DIRECT_OFFER,
            sessionId = sessionId,
            publicPeerIdHint = publicPeerIdHint,
            createdAtMillis = createdAtMillis,
            capabilityFlags = setOf(
                HybridTransportControlMessage.CapabilityFlag.WIFI_DIRECT_BOOTSTRAP,
                HybridTransportControlMessage.CapabilityFlag.BLE_FALLBACK
            )
        )
    }

    private fun acceptMessage(
        sessionId: String,
        createdAtMillis: Long,
        publicPeerIdHint: String = "peer-accept-hint"
    ): HybridTransportControlMessage {
        return HybridTransportControlMessage(
            messageType = HybridTransportControlMessage.MessageType.WIFI_DIRECT_ACCEPT,
            sessionId = sessionId,
            publicPeerIdHint = publicPeerIdHint,
            createdAtMillis = createdAtMillis,
            capabilityFlags = setOf(
                HybridTransportControlMessage.CapabilityFlag.WIFI_DIRECT_BOOTSTRAP
            )
        )
    }

    private fun socketHintMessage(
        sessionId: String,
        createdAtMillis: Long,
        groupOwnerAddress: String,
        socketPort: Int
    ): HybridTransportControlMessage {
        return HybridTransportControlMessage(
            messageType = HybridTransportControlMessage.MessageType.WIFI_DIRECT_SOCKET_HINT,
            sessionId = sessionId,
            publicPeerIdHint = "peer-socket-hint",
            groupOwnerAddress = groupOwnerAddress,
            socketPort = socketPort,
            createdAtMillis = createdAtMillis,
            capabilityFlags = setOf(
                HybridTransportControlMessage.CapabilityFlag.WIFI_DIRECT_SOCKET_HINT,
                HybridTransportControlMessage.CapabilityFlag.BLE_FALLBACK
            )
        )
    }
}
