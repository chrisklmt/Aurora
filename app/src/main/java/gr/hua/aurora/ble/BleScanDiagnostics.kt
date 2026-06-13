package gr.hua.aurora.ble

data class BleScanDiagnostics(
    val rawScanResultCount: Int = 0,
    val auroraDiscoveryMatchCount: Int = 0,
    val lastDeviceName: String? = null,
    val lastDeviceAddress: String? = null,
    val lastRssi: Int? = null,
    val lastHadDiscoveryServiceData: Boolean = false,
    val lastHadAuroraDiscoveryPayload: Boolean = false
) {
    fun record(
        deviceName: String?,
        deviceAddress: String,
        rssi: Int?,
        hadDiscoveryServiceData: Boolean,
        hadAuroraDiscoveryPayload: Boolean
    ): BleScanDiagnostics {
        return copy(
            rawScanResultCount = rawScanResultCount + 1,
            auroraDiscoveryMatchCount = auroraDiscoveryMatchCount +
                if (hadAuroraDiscoveryPayload) {
                    1
                } else {
                    0
                },
            lastDeviceName = deviceName,
            lastDeviceAddress = deviceAddress,
            lastRssi = rssi,
            lastHadDiscoveryServiceData = hadDiscoveryServiceData,
            lastHadAuroraDiscoveryPayload = hadAuroraDiscoveryPayload
        )
    }
}
