package gr.hua.aurora.ble.transport

class BleTransportFrameBridge(
    private val receiver: BleTransportFrameReceiver,
    private val dispatch: ((() -> Unit) -> Unit) = { runnable -> runnable() },
    private val onReceiveResult: (BleTransportReceiveResult) -> Unit = {}
) : BleTransportFrameListener {
    override fun onFrameReceived(frame: BleGattTransportFrame) {
        dispatch {
            onReceiveResult(receiver.receive(frame))
        }
    }

    fun clear() {
        receiver.clear()
    }
}
