package gr.hua.aurora.ble.noop

import gr.hua.aurora.ble.advertise.BleAdvertiseRequest
import gr.hua.aurora.ble.advertise.BleAdvertiseStatus
import gr.hua.aurora.ble.advertise.BleAdvertiser

class NoOpBleAdvertiser : BleAdvertiser {
    override fun start(
        request: BleAdvertiseRequest,
        listener: BleAdvertiser.Listener
    ) {
        listener.onStatusChanged(BleAdvertiseStatus.IDLE)
    }

    override fun stop() {
    }
}
