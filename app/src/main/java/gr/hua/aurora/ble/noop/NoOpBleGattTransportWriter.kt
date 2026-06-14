package gr.hua.aurora.ble.noop

import gr.hua.aurora.ble.transport.BleGattTransportPayload
import gr.hua.aurora.ble.transport.BleGattTransportWriteResult
import gr.hua.aurora.ble.transport.BleGattTransportWriter

class NoOpBleGattTransportWriter : BleGattTransportWriter {
    override fun write(
        payload: BleGattTransportPayload,
        listener: BleGattTransportWriter.Listener
    ) {
        listener.onWriteResult(BleGattTransportWriteResult.NotAvailable)
    }
}
