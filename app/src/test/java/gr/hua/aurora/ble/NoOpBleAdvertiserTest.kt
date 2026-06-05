package gr.hua.aurora.ble

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class NoOpBleAdvertiserTest {
    private val request = BleAdvertiseRequest(
        serviceUuid = UUID.fromString("12345678-1234-1234-1234-1234567890ab"),
        payload = byteArrayOf(0x00)
    )

    @Test
    fun startReportsIdleStatus() {
        val statuses = mutableListOf<BleAdvertiseStatus>()
        val advertiser = NoOpBleAdvertiser()

        advertiser.start(
            request = request,
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
            request = request,
            listener = object : BleAdvertiser.Listener {
                override fun onStatusChanged(status: BleAdvertiseStatus) {
                }
            }
        )
        advertiser.stop()
        advertiser.stop()
    }
}
