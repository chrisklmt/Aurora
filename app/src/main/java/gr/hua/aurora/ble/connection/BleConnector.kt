package gr.hua.aurora.ble.connection

interface BleConnector {
    fun connect(
        deviceAddress: String,
        listener: Listener
    )

    fun disconnect()

    interface Listener {
        fun onStatusChanged(status: BleConnectionStatus)
    }
}
