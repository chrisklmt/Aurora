package gr.hua.aurora.ble.transport

interface BleGattTransportFrameWriter {
    fun write(
        frame: BleGattTransportFrame,
        listener: Listener
    )

    interface Listener {
        fun onWriteResult(result: BleGattTransportFrameWriteResult)
    }
}
