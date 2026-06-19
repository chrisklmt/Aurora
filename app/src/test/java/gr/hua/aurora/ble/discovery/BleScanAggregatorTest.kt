package gr.hua.aurora.ble.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleScanAggregatorTest {
    @Test
    fun updateAddsDeviceWithNewAddress() {
        val aggregator = BleScanAggregator()

        val snapshot = aggregator.update(device(address = "AA:BB", name = "Aurora", rssi = -40))

        assertEquals(listOf(device(address = "AA:BB", name = "Aurora", rssi = -40)), snapshot)
    }

    @Test
    fun repeatedSameAddressUpdatesExistingDeviceWithoutDuplicate() {
        val aggregator = BleScanAggregator()

        aggregator.update(device(address = "AA:BB", name = "Aurora", rssi = -60))
        val snapshot = aggregator.update(device(address = "AA:BB", name = "Updated", rssi = -50))

        assertEquals(
            listOf(device(address = "AA:BB", name = "Updated", rssi = -50)),
            snapshot
        )
    }

    @Test
    fun newestNonBlankNameReplacesMissingName() {
        val aggregator = BleScanAggregator()

        aggregator.update(device(address = "AA:BB", name = null, rssi = -60))
        val snapshot = aggregator.update(device(address = "AA:BB", name = "Aurora", rssi = -55))

        assertEquals("Aurora", snapshot.single().name)
    }

    @Test
    fun nullOrBlankNewestNameKeepsPreviousName() {
        val aggregator = BleScanAggregator()

        aggregator.update(device(address = "AA:BB", name = "Aurora", rssi = -60))
        aggregator.update(device(address = "AA:BB", name = null, rssi = -55))
        val snapshot = aggregator.update(device(address = "AA:BB", name = "  ", rssi = -50))

        assertEquals("Aurora", snapshot.single().name)
    }

    @Test
    fun newestRssiReplacesPreviousRssi() {
        val aggregator = BleScanAggregator()

        aggregator.update(device(address = "AA:BB", name = "Aurora", rssi = -70))
        val snapshot = aggregator.update(device(address = "AA:BB", name = "Aurora", rssi = -45))

        assertEquals(-45, snapshot.single().rssi)
    }

    @Test
    fun newestNonNullConnectableReplacesPreviousValue() {
        val aggregator = BleScanAggregator()

        aggregator.update(device(address = "AA:BB", isConnectable = false))
        val snapshot = aggregator.update(device(address = "AA:BB", isConnectable = true))

        assertEquals(true, snapshot.single().isConnectable)
    }

    @Test
    fun nullNewestConnectableKeepsPreviousValue() {
        val aggregator = BleScanAggregator()

        aggregator.update(device(address = "AA:BB", isConnectable = true))
        val snapshot = aggregator.update(device(address = "AA:BB", isConnectable = null))

        assertEquals(true, snapshot.single().isConnectable)
    }

    @Test
    fun discoveryPayloadStaysTrueAfterLaterFalseUpdate() {
        val aggregator = BleScanAggregator()

        aggregator.update(device(address = "AA:BB", hasAuroraDiscoveryPayload = true))
        val snapshot = aggregator.update(
            device(address = "AA:BB", hasAuroraDiscoveryPayload = false)
        )

        assertEquals(true, snapshot.single().hasAuroraDiscoveryPayload)
    }

    @Test
    fun discoveryPayloadStaysFalseWhenNoTrueWasObserved() {
        val aggregator = BleScanAggregator()

        aggregator.update(device(address = "AA:BB", hasAuroraDiscoveryPayload = false))
        val snapshot = aggregator.update(
            device(address = "AA:BB", hasAuroraDiscoveryPayload = false)
        )

        assertEquals(false, snapshot.single().hasAuroraDiscoveryPayload)
    }

    @Test
    fun sameStablePeerIdAcrossDifferentAddressesUpdatesExistingDevice() {
        val aggregator = BleScanAggregator()
        val stablePeerId = stablePeerId(
            byteArrayOf(0x01, 0x23, 0x45, 0x67, 0x09, 0x11, 0x22, 0x33)
        )

        aggregator.update(
            device(
                address = "AA:01",
                name = "Aurora",
                rssi = -65,
                isConnectable = false,
                hasAuroraDiscoveryPayload = true,
                stablePeerId = stablePeerId
            )
        )
        val snapshot = aggregator.update(
            device(
                address = "AA:02",
                name = null,
                rssi = -40,
                isConnectable = true,
                hasAuroraDiscoveryPayload = true,
                stablePeerId = stablePeerId
            )
        )

        assertEquals(1, snapshot.size)
        assertEquals("AA:02", snapshot.single().address)
        assertEquals("Aurora", snapshot.single().name)
        assertEquals(-40, snapshot.single().rssi)
        assertEquals(true, snapshot.single().isConnectable)
        assertEquals(true, snapshot.single().hasAuroraDiscoveryPayload)
        assertEquals(stablePeerId, snapshot.single().stablePeerId)
    }

    @Test
    fun stablePeerIdReplacesLegacyAddressEntryForSameAddress() {
        val aggregator = BleScanAggregator()
        val stablePeerId = stablePeerId(
            byteArrayOf(0x55, 0x44, 0x33, 0x22, 0x11, 0x10, 0x20, 0x30)
        )

        aggregator.update(
            device(
                address = "AA:BB",
                name = "Aurora",
                rssi = -60,
                hasAuroraDiscoveryPayload = true
            )
        )
        val snapshot = aggregator.update(
            device(
                address = "AA:BB",
                name = null,
                rssi = -45,
                stablePeerId = stablePeerId
            )
        )

        assertEquals(1, snapshot.size)
        assertEquals(stablePeerId, snapshot.single().stablePeerId)
        assertEquals("AA:BB", snapshot.single().address)
        assertEquals("Aurora", snapshot.single().name)
        assertEquals(-45, snapshot.single().rssi)
    }

    @Test
    fun blankAddressIsIgnored() {
        val aggregator = BleScanAggregator()

        aggregator.update(device(address = "", name = "Ignored", rssi = -30))
        val snapshot = aggregator.update(device(address = "   ", name = "Ignored too", rssi = -20))

        assertTrue(snapshot.isEmpty())
    }

    @Test
    fun snapshotKeepsStableInsertionOrderAcrossRssiUpdates() {
        val aggregator = BleScanAggregator()

        aggregator.update(device(address = "AA:01", rssi = -80))
        aggregator.update(device(address = "AA:02", rssi = null))
        aggregator.update(device(address = "AA:03", rssi = -35))
        aggregator.update(device(address = "AA:04", rssi = -60))
        aggregator.update(device(address = "AA:02", rssi = -20))

        assertEquals(
            listOf("AA:01", "AA:02", "AA:03", "AA:04"),
            aggregator.snapshot().map { it.address }
        )
    }

    @Test
    fun clearRemovesAllDevices() {
        val aggregator = BleScanAggregator()

        aggregator.update(device(address = "AA:BB"))
        aggregator.clear()

        assertTrue(aggregator.snapshot().isEmpty())
    }

    @Test
    fun updateReturnsSnapshotAfterApplyingDevice() {
        val aggregator = BleScanAggregator()

        aggregator.update(device(address = "AA:01", rssi = -80))
        val snapshot = aggregator.update(device(address = "AA:02", rssi = -30))

        assertEquals(snapshot, aggregator.snapshot())
    }

    @Test
    fun deviceRemainsBeforeExpiry() {
        val aggregator = BleScanAggregator()

        aggregator.update(
            device(address = "AA:BB", rssi = -50),
            nowMs = 1_000L
        )
        val snapshot = aggregator.prune(
            nowMs = 1_000L + BleScanAggregator.STALE_PEER_TIMEOUT_MS - 1L
        )

        assertEquals(1, snapshot.size)
        assertEquals("AA:BB", snapshot.single().address)
    }

    @Test
    fun deviceExpiresAfterTimeout() {
        val aggregator = BleScanAggregator()

        aggregator.update(
            device(address = "AA:BB", rssi = -50),
            nowMs = 1_000L
        )
        val snapshot = aggregator.prune(
            nowMs = 1_000L + BleScanAggregator.STALE_PEER_TIMEOUT_MS
        )

        assertTrue(snapshot.isEmpty())
    }

    @Test
    fun rotatedAddressForSameStablePeerIdRefreshesLastSeen() {
        val aggregator = BleScanAggregator()
        val stablePeerId = stablePeerId(
            byteArrayOf(0x09, 0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02)
        )

        aggregator.update(
            device(
                address = "AA:01",
                rssi = -60,
                stablePeerId = stablePeerId
            ),
            nowMs = 1_000L
        )
        aggregator.update(
            device(
                address = "AA:02",
                rssi = -45,
                stablePeerId = stablePeerId
            ),
            nowMs = 1_000L + BleScanAggregator.STALE_PEER_TIMEOUT_MS - 500L
        )
        val snapshot = aggregator.prune(
            nowMs = 1_000L + BleScanAggregator.STALE_PEER_TIMEOUT_MS
        )

        assertEquals(1, snapshot.size)
        assertEquals("AA:02", snapshot.single().address)
        assertEquals(stablePeerId, snapshot.single().stablePeerId)
    }

    @Test
    fun legacyAddressKeyedPeerExpiresByLastSeen() {
        val aggregator = BleScanAggregator()

        aggregator.update(
            device(address = "AA:BB", hasAuroraDiscoveryPayload = true),
            nowMs = 2_000L
        )
        aggregator.update(
            device(address = "CC:DD", hasAuroraDiscoveryPayload = true),
            nowMs = 2_000L + BleScanAggregator.STALE_PEER_TIMEOUT_MS - 100L
        )
        val snapshot = aggregator.prune(
            nowMs = 2_000L + BleScanAggregator.STALE_PEER_TIMEOUT_MS + 1L
        )

        assertEquals(1, snapshot.size)
        assertEquals("CC:DD", snapshot.single().address)
    }

    private fun device(
        address: String,
        name: String? = null,
        rssi: Int? = null,
        isConnectable: Boolean? = null,
        hasAuroraDiscoveryPayload: Boolean = false,
        stablePeerId: BleStablePeerId? = null
    ): BleDiscoveredDevice {
        return BleDiscoveredDevice(
            address = address,
            name = name,
            rssi = rssi,
            isConnectable = isConnectable,
            hasAuroraDiscoveryPayload = hasAuroraDiscoveryPayload,
            stablePeerId = stablePeerId
        )
    }

    private fun stablePeerId(bytes: ByteArray): BleStablePeerId {
        return BleStablePeerId.fromBytes(bytes)
    }
}
