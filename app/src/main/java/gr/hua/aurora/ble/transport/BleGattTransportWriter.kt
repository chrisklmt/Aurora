package gr.hua.aurora.ble.transport

interface BleGattTransportWriter {
    fun write(
        payload: BleGattTransportPayload,
        listener: Listener
    )

    interface Listener {
        fun onWriteResult(result: BleGattTransportWriteResult)
    }
}
