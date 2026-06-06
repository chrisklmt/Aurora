package gr.hua.aurora.ble

interface BleGattTransportReader {
    fun read(listener: Listener)

    interface Listener {
        fun onReadResult(result: BleGattTransportReadResult)
    }
}
