package gr.hua.aurora.ble.discovery

class BleScanAggregator {
    private val devicesByIdentityKey = linkedMapOf<String, BleDiscoveredDevice>()

    fun update(device: BleDiscoveredDevice): List<BleDiscoveredDevice> {
        val address = device.address.takeIf { it.isNotBlank() } ?: return snapshot()
        val normalizedDevice = if (address == device.address) {
            device
        } else {
            device.copy(address = address)
        }
        val identityKey = normalizedDevice.identityKey()
        val current = devicesByIdentityKey[identityKey]
            ?: takeMigratedAddressMatchOrNull(normalizedDevice, identityKey)

        if (normalizedDevice.stablePeerId != null) {
            removeLegacyAddressMatches(normalizedDevice, identityKey)
        }

        devicesByIdentityKey[identityKey] = mergeDevice(
            current = current,
            device = normalizedDevice
        )

        return snapshot()
    }

    fun snapshot(): List<BleDiscoveredDevice> {
        return devicesByIdentityKey.values
            .sortedWith(
                compareByDescending<BleDiscoveredDevice> { it.rssi != null }
                    .thenByDescending { it.rssi }
            )
            .toList()
    }

    fun clear() {
        devicesByIdentityKey.clear()
    }

    private fun takeMigratedAddressMatchOrNull(
        device: BleDiscoveredDevice,
        identityKey: String
    ): BleDiscoveredDevice? {
        if (device.stablePeerId == null) {
            return null
        }

        return devicesByIdentityKey.entries.firstOrNull { entry ->
            entry.key != identityKey && entry.value.address == device.address
        }?.value
    }

    private fun removeLegacyAddressMatches(
        device: BleDiscoveredDevice,
        identityKey: String
    ) {
        val iterator = devicesByIdentityKey.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key != identityKey && entry.value.address == device.address) {
                iterator.remove()
            }
        }
    }

    private fun mergeDevice(
        current: BleDiscoveredDevice?,
        device: BleDiscoveredDevice
    ): BleDiscoveredDevice {
        if (current == null) {
            return device
        }

        return BleDiscoveredDevice(
            address = device.address,
            name = device.name?.takeIf { it.isNotBlank() } ?: current.name,
            rssi = device.rssi,
            isConnectable = device.isConnectable ?: current.isConnectable,
            hasAuroraDiscoveryPayload = current.hasAuroraDiscoveryPayload ||
                device.hasAuroraDiscoveryPayload,
            stablePeerId = device.stablePeerId ?: current.stablePeerId
        )
    }
}
