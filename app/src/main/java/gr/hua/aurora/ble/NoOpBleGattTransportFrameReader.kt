package gr.hua.aurora.ble

class NoOpBleGattTransportFrameReader : BleGattTransportFrameReader {
    override fun read(listener: BleGattTransportFrameReader.Listener) {
        listener.onReadResult(BleGattTransportFrameReadResult.NotAvailable)
    }
}
