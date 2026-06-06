package gr.hua.aurora.ble

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
