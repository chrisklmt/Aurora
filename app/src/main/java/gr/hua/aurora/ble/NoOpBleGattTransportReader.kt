package gr.hua.aurora.ble

class NoOpBleGattTransportReader : BleGattTransportReader {
    override fun read(listener: BleGattTransportReader.Listener) {
        listener.onReadResult(BleGattTransportReadResult.NotAvailable)
    }
}
