package gr.hua.aurora.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleScanDiagnosticsTest {
    @Test
    fun recordTracksLastObservedValues() {
        val diagnostics = BleScanDiagnostics().record(
            deviceName = "Aurora",
            deviceAddress = "AA:BB",
            rssi = -48,
            hadDiscoveryServiceData = true,
            hadAuroraDiscoveryPayload = false
        )

        assertEquals(1, diagnostics.rawScanResultCount)
        assertEquals(0, diagnostics.auroraDiscoveryMatchCount)
        assertEquals("Aurora", diagnostics.lastDeviceName)
        assertEquals("AA:BB", diagnostics.lastDeviceAddress)
        assertEquals(-48, diagnostics.lastRssi)
        assertTrue(diagnostics.lastHadDiscoveryServiceData)
        assertFalse(diagnostics.lastHadAuroraDiscoveryPayload)
    }

    @Test
    fun recordIncrementsAuroraMatchCountOnlyForMatchingResults() {
        val diagnostics = BleScanDiagnostics()
            .record(
                deviceName = "First",
                deviceAddress = "AA:BB",
                rssi = -50,
                hadDiscoveryServiceData = false,
                hadAuroraDiscoveryPayload = false
            )
            .record(
                deviceName = "Second",
                deviceAddress = "CC:DD",
                rssi = -42,
                hadDiscoveryServiceData = true,
                hadAuroraDiscoveryPayload = true
            )

        assertEquals(2, diagnostics.rawScanResultCount)
        assertEquals(1, diagnostics.auroraDiscoveryMatchCount)
        assertEquals("Second", diagnostics.lastDeviceName)
        assertEquals("CC:DD", diagnostics.lastDeviceAddress)
        assertEquals(-42, diagnostics.lastRssi)
        assertTrue(diagnostics.lastHadDiscoveryServiceData)
        assertTrue(diagnostics.lastHadAuroraDiscoveryPayload)
    }
}
