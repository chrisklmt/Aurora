package gr.hua.aurora.ble.transport

interface BleGattTransportFrameReader {
    fun read(listener: Listener)

    interface Listener {
        fun onReadResult(result: BleGattTransportFrameReadResult)
    }
}
