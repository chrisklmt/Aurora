package gr.hua.aurora.ble.noop

import gr.hua.aurora.ble.transport.BleTransportSendResult
import gr.hua.aurora.ble.transport.BleTransportSender
import gr.hua.aurora.ble.transport.OutgoingBleTransportSendPlan

class NoOpBleTransportSender : BleTransportSender {
    override fun send(
        plan: OutgoingBleTransportSendPlan,
        listener: BleTransportSender.Listener
    ) {
        listener.onSendResult(BleTransportSendResult.NotAvailable)
    }
}
