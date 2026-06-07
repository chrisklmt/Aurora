package gr.hua.aurora.ble

class NoOpBleGattTransportWriter : BleGattTransportWriter {
    override fun write(
        payload: BleGattTransportPayload,
        listener: BleGattTransportWriter.Listener
    ) {
        listener.onWriteResult(BleGattTransportWriteResult.NotAvailable)
    }
}
