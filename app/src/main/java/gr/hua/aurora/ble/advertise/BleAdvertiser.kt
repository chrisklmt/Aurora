package gr.hua.aurora.ble.advertise

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
