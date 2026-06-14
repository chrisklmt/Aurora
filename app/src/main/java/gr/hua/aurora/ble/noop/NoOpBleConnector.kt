package gr.hua.aurora.ble.noop

import gr.hua.aurora.ble.connection.BleConnectionStatus
import gr.hua.aurora.ble.connection.BleConnector

class NoOpBleConnector : BleConnector {
    override fun connect(
        deviceAddress: String,
        listener: BleConnector.Listener
    ) {
        listener.onStatusChanged(BleConnectionStatus.IDLE)
    }

    override fun disconnect() = Unit
}
