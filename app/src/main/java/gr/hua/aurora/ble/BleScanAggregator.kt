package gr.hua.aurora.ble

class BleScanAggregator {
    private val devicesByAddress = linkedMapOf<String, BleDiscoveredDevice>()

    fun update(device: BleDiscoveredDevice): List<BleDiscoveredDevice> {
        val address = device.address.takeIf { it.isNotBlank() } ?: return snapshot()
        val current = devicesByAddress[address]

        devicesByAddress[address] = if (current == null) {
            device
        } else {
            BleDiscoveredDevice(
                address = current.address,
                name = device.name?.takeIf { it.isNotBlank() } ?: current.name,
                rssi = device.rssi,
                isConnectable = device.isConnectable ?: current.isConnectable
            )
        }

        return snapshot()
    }

    fun snapshot(): List<BleDiscoveredDevice> {
        return devicesByAddress.values
            .sortedWith(
                compareByDescending<BleDiscoveredDevice> { it.rssi != null }
                    .thenByDescending { it.rssi }
            )
            .toList()
    }

    fun clear() {
        devicesByAddress.clear()
    }
}
