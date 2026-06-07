package gr.hua.aurora.ble

sealed interface BleGattTransportWriteResult {
    data object Accepted : BleGattTransportWriteResult
    data object NotAvailable : BleGattTransportWriteResult
}
