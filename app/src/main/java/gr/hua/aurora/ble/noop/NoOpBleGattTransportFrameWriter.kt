package gr.hua.aurora.ble.noop

import gr.hua.aurora.ble.transport.BleGattTransportFrame
import gr.hua.aurora.ble.transport.BleGattTransportFrameWriteResult
import gr.hua.aurora.ble.transport.BleGattTransportFrameWriter

class NoOpBleGattTransportFrameWriter : BleGattTransportFrameWriter {
    override fun write(
        frame: BleGattTransportFrame,
        listener: BleGattTransportFrameWriter.Listener
    ) {
        listener.onWriteResult(BleGattTransportFrameWriteResult.NotAvailable)
    }
}
