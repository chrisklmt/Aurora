package gr.hua.aurora.ble.transport

fun interface BleTransportFrameListener {
    fun onFrameReceived(frame: BleTransportIncomingFrame)
}
