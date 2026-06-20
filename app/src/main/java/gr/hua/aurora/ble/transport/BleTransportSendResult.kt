package gr.hua.aurora.ble.transport

sealed interface BleTransportSendResult {
    data object QueuedLocally : BleTransportSendResult
    data object NotAvailable : BleTransportSendResult
    data class Failed(
        val reason: String
    ) : BleTransportSendResult {
        init {
            require(reason.isNotBlank()) {
                "BLE transport send failure reason must not be blank."
            }
        }
    }
}
