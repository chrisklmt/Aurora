package gr.hua.aurora.ble.noop

import gr.hua.aurora.ble.transport.BleGattTransportFrameReadResult
import gr.hua.aurora.ble.transport.BleGattTransportFrameReader

class NoOpBleGattTransportFrameReader : BleGattTransportFrameReader {
    override fun read(listener: BleGattTransportFrameReader.Listener) {
        listener.onReadResult(BleGattTransportFrameReadResult.NotAvailable)
    }
}
