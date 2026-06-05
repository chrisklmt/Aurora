package gr.hua.aurora.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class NoOpBleAdvertiserTest {
    @Test
    fun startReportsIdleStatus() {
        val statuses = mutableListOf<BleAdvertiseStatus>()
        val advertiser = NoOpBleAdvertiser()

        advertiser.start(
            listener = object : BleAdvertiser.Listener {
                override fun onStatusChanged(status: BleAdvertiseStatus) {
                    statuses += status
                }
            }
        )

        assertEquals(listOf(BleAdvertiseStatus.IDLE), statuses)
    }

    @Test
    fun stopCanBeCalledRepeatedly() {
        val advertiser = NoOpBleAdvertiser()

        advertiser.stop()
        advertiser.stop()
        advertiser.start(
            listener = object : BleAdvertiser.Listener {
                override fun onStatusChanged(status: BleAdvertiseStatus) {
                }
            }
        )
        advertiser.stop()
        advertiser.stop()
    }
}
