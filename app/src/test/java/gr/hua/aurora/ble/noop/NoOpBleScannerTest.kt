package gr.hua.aurora.ble.noop

import gr.hua.aurora.ble.discovery.BleDiscoveredDevice
import gr.hua.aurora.ble.discovery.BleScanStatus
import gr.hua.aurora.ble.discovery.BleScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoOpBleScannerTest {
    @Test
    fun startReportsIdleStatus() {
        val statuses = mutableListOf<BleScanStatus>()
        val scanner = NoOpBleScanner()

        scanner.start(
            listener = object : BleScanner.Listener {
                override fun onStatusChanged(status: BleScanStatus) {
                    statuses += status
                }

                override fun onDeviceDiscovered(device: BleDiscoveredDevice) {
                }
            }
        )

        assertEquals(listOf(BleScanStatus.IDLE), statuses)
    }

    @Test
    fun startDoesNotReportDevices() {
        val devices = mutableListOf<BleDiscoveredDevice>()
        val scanner = NoOpBleScanner()

        scanner.start(
            listener = object : BleScanner.Listener {
                override fun onStatusChanged(status: BleScanStatus) {
                }

                override fun onDeviceDiscovered(device: BleDiscoveredDevice) {
                    devices += device
                }
            }
        )

        assertTrue(devices.isEmpty())
    }

    @Test
    fun stopCanBeCalledRepeatedly() {
        val scanner = NoOpBleScanner()

        scanner.stop()
        scanner.stop()
        scanner.start(
            listener = object : BleScanner.Listener {
                override fun onStatusChanged(status: BleScanStatus) {
                }

                override fun onDeviceDiscovered(device: BleDiscoveredDevice) {
                }
            }
        )
        scanner.stop()
        scanner.stop()
    }
}
