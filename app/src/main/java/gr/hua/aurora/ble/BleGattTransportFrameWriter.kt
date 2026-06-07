package gr.hua.aurora.ble

interface BleGattTransportFrameWriter {
    fun write(
        frame: BleGattTransportFrame,
        listener: Listener
    )

    interface Listener {
        fun onWriteResult(result: BleGattTransportFrameWriteResult)
    }
}
