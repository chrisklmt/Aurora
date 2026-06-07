package gr.hua.aurora.ble

sealed interface BleGattTransportFrameWriteResult {
    data object Accepted : BleGattTransportFrameWriteResult
    data object NotAvailable : BleGattTransportFrameWriteResult
}
