package gr.hua.aurora.ble.transport

sealed interface BleGattTransportFrameReadResult {
    data class FrameAvailable(
        val frame: BleGattTransportFrame
    ) : BleGattTransportFrameReadResult

    data object NotAvailable : BleGattTransportFrameReadResult
}
