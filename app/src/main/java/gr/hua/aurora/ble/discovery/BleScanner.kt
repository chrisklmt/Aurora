package gr.hua.aurora.ble.discovery

interface BleScanner {
    fun start(listener: Listener)
    fun stop()

    interface Listener {
        fun onStatusChanged(status: BleScanStatus)
        fun onDeviceDiscovered(device: BleDiscoveredDevice)
    }
}
