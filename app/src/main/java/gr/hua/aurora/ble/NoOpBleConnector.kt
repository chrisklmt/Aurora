package gr.hua.aurora.ble

class NoOpBleConnector : BleConnector {
    override fun connect(
        deviceAddress: String,
        listener: BleConnector.Listener
    ) {
        listener.onStatusChanged(BleConnectionStatus.IDLE)
    }

    override fun disconnect() = Unit
}
