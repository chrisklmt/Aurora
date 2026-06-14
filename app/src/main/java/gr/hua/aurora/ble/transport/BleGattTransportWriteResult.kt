package gr.hua.aurora.ble.transport

sealed interface BleGattTransportWriteResult {
    data object Accepted : BleGattTransportWriteResult
    data object NotAvailable : BleGattTransportWriteResult
}
