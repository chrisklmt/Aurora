package gr.hua.aurora.ble.noop

import gr.hua.aurora.ble.transport.BleGattTransportReadResult
import gr.hua.aurora.ble.transport.BleGattTransportReader

class NoOpBleGattTransportReader : BleGattTransportReader {
    override fun read(listener: BleGattTransportReader.Listener) {
        listener.onReadResult(BleGattTransportReadResult.NotAvailable)
    }
}
