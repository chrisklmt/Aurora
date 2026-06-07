package gr.hua.aurora.ble

interface BleGattTransportWriter {
    fun write(
        payload: BleGattTransportPayload,
        listener: Listener
    )

    interface Listener {
        fun onWriteResult(result: BleGattTransportWriteResult)
    }
}
