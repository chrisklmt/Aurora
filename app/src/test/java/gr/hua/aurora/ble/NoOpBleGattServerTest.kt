package gr.hua.aurora.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class NoOpBleGattServerTest {
    @Test
    fun startReportsIdleStatus() {
        val statuses = mutableListOf<BleGattServerStatus>()
        val server = NoOpBleGattServer()

        server.start(
            listener = object : BleGattServer.Listener {
                override fun onStatusChanged(status: BleGattServerStatus) {
                    statuses += status
                }
            }
        )

        assertEquals(listOf(BleGattServerStatus.IDLE), statuses)
    }

    @Test
    fun stopCanBeCalledRepeatedly() {
        val server = NoOpBleGattServer()

        server.stop()
        server.stop()
        server.start(
            listener = object : BleGattServer.Listener {
                override fun onStatusChanged(status: BleGattServerStatus) {
                }
            }
        )
        server.stop()
        server.stop()
    }

    @Test
    fun stopCanBeCalledBeforeStartWithoutThrowing() {
        val server = NoOpBleGattServer()

        server.stop()
        server.stop()
    }
}
