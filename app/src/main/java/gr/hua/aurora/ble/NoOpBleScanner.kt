package gr.hua.aurora.ble

class NoOpBleScanner : BleScanner {
    override fun start(listener: BleScanner.Listener) {
        listener.onStatusChanged(BleScanStatus.IDLE)
    }

    override fun stop() {
    }
}
