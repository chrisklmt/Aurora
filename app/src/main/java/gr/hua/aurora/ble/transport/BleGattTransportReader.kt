package gr.hua.aurora.ble.transport

interface BleGattTransportReader {
    fun read(listener: Listener)

    interface Listener {
        fun onReadResult(result: BleGattTransportReadResult)
    }
}
