package gr.hua.aurora.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class NoOpBleConnectorTest {
    @Test
    fun connectReportsIdleStatus() {
        val statuses = mutableListOf<BleConnectionStatus>()
        val connector = NoOpBleConnector()

        connector.connect(
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            listener = object : BleConnector.Listener {
                override fun onStatusChanged(status: BleConnectionStatus) {
                    statuses += status
                }
            }
        )

        assertEquals(listOf(BleConnectionStatus.IDLE), statuses)
    }

    @Test
    fun disconnectCanBeCalledRepeatedly() {
        val connector = NoOpBleConnector()

        connector.disconnect()
        connector.disconnect()
        connector.connect(
            deviceAddress = "AA:BB:CC:DD:EE:FF",
            listener = object : BleConnector.Listener {
                override fun onStatusChanged(status: BleConnectionStatus) {
                }
            }
        )
        connector.disconnect()
        connector.disconnect()
    }

    @Test
    fun disconnectCanBeCalledBeforeConnectWithoutThrowing() {
        val connector = NoOpBleConnector()

        connector.disconnect()
        connector.disconnect()
    }
}
