package gr.hua.aurora.ble.noop

import gr.hua.aurora.ble.gatt.BleGattServer
import gr.hua.aurora.ble.gatt.BleGattServerStatus

class NoOpBleGattServer : BleGattServer {
    override fun start(listener: BleGattServer.Listener) {
        listener.onStatusChanged(BleGattServerStatus.IDLE)
    }

    override fun stop() = Unit
}
