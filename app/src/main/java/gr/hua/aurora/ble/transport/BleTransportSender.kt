package gr.hua.aurora.ble.transport

interface BleTransportSender {
    fun send(
        plan: OutgoingBleTransportSendPlan,
        listener: Listener
    )

    interface Listener {
        fun onSendResult(result: BleTransportSendResult)
    }
}
