package gr.hua.aurora.ble.transport

class BleTransportFrameBridge(
    private val receiver: BleTransportFrameReceiver,
    private val dispatch: ((() -> Unit) -> Unit) = { runnable -> runnable() },
    private val onReceiveResult: (BleTransportReceiveResult) -> Unit = {}
) : BleTransportFrameListener {
    fun onFrameReceived(frame: BleGattTransportFrame) {
        onFrameReceived(
            BleTransportIncomingFrame(frame = frame)
        )
    }

    override fun onFrameReceived(frame: BleTransportIncomingFrame) {
        dispatch {
            onReceiveResult(receiver.receive(frame))
        }
    }

    fun clear() {
        receiver.clear()
    }
}
