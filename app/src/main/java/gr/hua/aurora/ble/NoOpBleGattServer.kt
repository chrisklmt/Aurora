package gr.hua.aurora.ble

class NoOpBleGattServer : BleGattServer {
    override fun start(listener: BleGattServer.Listener) {
        listener.onStatusChanged(BleGattServerStatus.IDLE)
    }

    override fun stop() = Unit
}
