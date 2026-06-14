package gr.hua.aurora.ble.discovery

data class BleDiscoveredDevice(
    val address: String,
    val name: String?,
    val rssi: Int?,
    val isConnectable: Boolean?,
    val hasAuroraDiscoveryPayload: Boolean,
    val stablePeerId: BleStablePeerId? = null
)

internal fun BleDiscoveredDevice.identityKey(): String {
    return stablePeerId?.toHexKey() ?: address.trim()
}
