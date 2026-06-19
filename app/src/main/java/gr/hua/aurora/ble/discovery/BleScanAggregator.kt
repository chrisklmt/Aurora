package gr.hua.aurora.ble.discovery

class BleScanAggregator {
    companion object {
        const val STALE_PEER_TIMEOUT_MS = 12_000L
        const val STALE_PEER_PRUNE_INTERVAL_MS = 3_000L
    }

    private data class ObservedBleDevice(
        val device: BleDiscoveredDevice,
        val lastSeenAtMs: Long
    )

    private val devicesByIdentityKey = linkedMapOf<String, ObservedBleDevice>()

    fun update(
        device: BleDiscoveredDevice,
        nowMs: Long = System.currentTimeMillis()
    ): List<BleDiscoveredDevice> {
        removeStaleEntries(nowMs)
        val address = device.address.takeIf { it.isNotBlank() } ?: return snapshot()
        val normalizedDevice = if (address == device.address) {
            device
        } else {
            device.copy(address = address)
        }
        val identityKey = normalizedDevice.identityKey()
        val current = devicesByIdentityKey[identityKey]?.device
            ?: takeMigratedAddressMatchOrNull(normalizedDevice, identityKey)

        if (normalizedDevice.stablePeerId != null) {
            removeLegacyAddressMatches(normalizedDevice, identityKey)
        }

        devicesByIdentityKey[identityKey] = ObservedBleDevice(
            device = mergeDevice(
                current = current,
                device = normalizedDevice
            ),
            lastSeenAtMs = nowMs
        )

        return snapshot()
    }

    fun prune(nowMs: Long = System.currentTimeMillis()): List<BleDiscoveredDevice> {
        removeStaleEntries(nowMs)
        return snapshot()
    }

    fun snapshot(): List<BleDiscoveredDevice> {
        return devicesByIdentityKey.values
            .map(ObservedBleDevice::device)
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
            entry.key != identityKey && entry.value.device.address == device.address
        }?.value?.device
    }

    private fun removeLegacyAddressMatches(
        device: BleDiscoveredDevice,
        identityKey: String
    ) {
        val iterator = devicesByIdentityKey.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key != identityKey && entry.value.device.address == device.address) {
                iterator.remove()
            }
        }
    }

    private fun removeStaleEntries(nowMs: Long) {
        val iterator = devicesByIdentityKey.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs - entry.value.lastSeenAtMs >= STALE_PEER_TIMEOUT_MS) {
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
