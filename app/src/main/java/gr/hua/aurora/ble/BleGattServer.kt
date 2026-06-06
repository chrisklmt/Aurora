package gr.hua.aurora.ble

interface BleGattServer {
    fun start(listener: Listener)

    fun stop()

    interface Listener {
        fun onStatusChanged(status: BleGattServerStatus)
    }
}
