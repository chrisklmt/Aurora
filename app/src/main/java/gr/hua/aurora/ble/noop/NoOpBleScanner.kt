package gr.hua.aurora.ble.noop

import gr.hua.aurora.ble.discovery.BleScanStatus
import gr.hua.aurora.ble.discovery.BleScanner

class NoOpBleScanner : BleScanner {
    override fun start(listener: BleScanner.Listener) {
        listener.onStatusChanged(BleScanStatus.IDLE)
    }

    override fun stop() {
    }
}
