package gr.hua.aurora.ble

class NoOpBleGattTransportFrameWriter : BleGattTransportFrameWriter {
    override fun write(
        frame: BleGattTransportFrame,
        listener: BleGattTransportFrameWriter.Listener
    ) {
        listener.onWriteResult(BleGattTransportFrameWriteResult.NotAvailable)
    }
}
