package gr.hua.aurora.ble.transport

sealed interface BleGattTransportReadResult {
    data object MarkerSeen : BleGattTransportReadResult
    data object NotAvailable : BleGattTransportReadResult
}
