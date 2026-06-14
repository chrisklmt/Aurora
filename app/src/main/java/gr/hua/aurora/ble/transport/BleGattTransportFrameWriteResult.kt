package gr.hua.aurora.ble.transport

sealed interface BleGattTransportFrameWriteResult {
    data object Accepted : BleGattTransportFrameWriteResult
    data object NotAvailable : BleGattTransportFrameWriteResult
}
