package gr.hua.aurora.ble

interface BleAdvertiser {
    fun start(
        request: BleAdvertiseRequest,
        listener: Listener
    )
    fun stop()

    interface Listener {
        fun onStatusChanged(status: BleAdvertiseStatus)
    }
}
