package gr.hua.aurora.ble

sealed interface BleGattTransportFrameReadResult {
    data class FrameAvailable(
        val frame: BleGattTransportFrame
    ) : BleGattTransportFrameReadResult

    data object NotAvailable : BleGattTransportFrameReadResult
}
