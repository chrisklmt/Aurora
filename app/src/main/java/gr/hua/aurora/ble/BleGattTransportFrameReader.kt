package gr.hua.aurora.ble

interface BleGattTransportFrameReader {
    fun read(listener: Listener)

    interface Listener {
        fun onReadResult(result: BleGattTransportFrameReadResult)
    }
}
