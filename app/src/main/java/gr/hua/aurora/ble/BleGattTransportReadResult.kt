package gr.hua.aurora.ble

sealed interface BleGattTransportReadResult {
    data object MarkerSeen : BleGattTransportReadResult
    data object NotAvailable : BleGattTransportReadResult
}
