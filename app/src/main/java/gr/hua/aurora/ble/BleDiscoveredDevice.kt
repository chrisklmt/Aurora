package gr.hua.aurora.ble

data class BleDiscoveredDevice(
    val address: String,
    val name: String?,
    val rssi: Int?,
    val isConnectable: Boolean?,
    val hasAuroraDiscoveryPayload: Boolean
)
