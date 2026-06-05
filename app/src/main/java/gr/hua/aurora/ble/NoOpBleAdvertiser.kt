package gr.hua.aurora.ble

class NoOpBleAdvertiser : BleAdvertiser {
    override fun start(listener: BleAdvertiser.Listener) {
        listener.onStatusChanged(BleAdvertiseStatus.IDLE)
    }

    override fun stop() {
    }
}
