package gr.hua.aurora.ble.transport

data class BleTransportIncomingFrame(
    val frame: BleGattTransportFrame,
    val sourceDeviceAddress: String? = null
) {
    val sanitizedSourceDeviceAddress: String?
        get() = sourceDeviceAddress?.trim()?.takeIf { it.isNotEmpty() }
}
