package gr.hua.aurora.ble

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
    fun blankAddressIsIgnored() {
        val aggregator = BleScanAggregator()

        aggregator.update(device(address = "", name = "Ignored", rssi = -30))
        val snapshot = aggregator.update(device(address = "   ", name = "Ignored too", rssi = -20))

        assertTrue(snapshot.isEmpty())
    }

    @Test
    fun snapshotSortsStrongestRssiFirstAndNullLast() {
        val aggregator = BleScanAggregator()

        aggregator.update(device(address = "AA:01", rssi = -80))
        aggregator.update(device(address = "AA:02", rssi = null))
        aggregator.update(device(address = "AA:03", rssi = -35))
        aggregator.update(device(address = "AA:04", rssi = -60))

        assertEquals(
            listOf("AA:03", "AA:04", "AA:01", "AA:02"),
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

    private fun device(
        address: String,
        name: String? = null,
        rssi: Int? = null,
        isConnectable: Boolean? = null,
        hasAuroraDiscoveryPayload: Boolean = false
    ): BleDiscoveredDevice {
        return BleDiscoveredDevice(
            address = address,
            name = name,
            rssi = rssi,
            isConnectable = isConnectable,
            hasAuroraDiscoveryPayload = hasAuroraDiscoveryPayload
        )
    }
}
