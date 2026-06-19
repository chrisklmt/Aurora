package gr.hua.aurora.ble.discovery

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Build
import android.os.ParcelUuid
import android.util.Log

class AndroidBleScanner(
    private val bluetoothAdapter: BluetoothAdapter?
) : BleScanner {
    private companion object {
        private const val TAG = "AndroidBleScanner"
    }

    private val aggregator = BleScanAggregator()
    private var activeScanner: BluetoothLeScanner? = null
    private var activeCallback: ScanCallback? = null
    private var activeListener: BleScanner.Listener? = null
    private var diagnostics = BleScanDiagnostics()
    private var isScanning = false

    fun currentDiagnostics(): BleScanDiagnostics {
        return diagnostics
    }

    override fun start(listener: BleScanner.Listener) {
        clearActiveScan(
            notifyStopped = false,
            stopReason = "new scan requested"
        )
        aggregator.clear()
        diagnostics = BleScanDiagnostics()

        val adapter = bluetoothAdapter ?: run {
            Log.w(TAG, "BLE scan start failed: Bluetooth adapter unavailable")
            listener.onStatusChanged(BleScanStatus.STOPPED)
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            Log.w(TAG, "BLE scan start failed: BluetoothLeScanner unavailable")
            listener.onStatusChanged(BleScanStatus.STOPPED)
            return
        }
        val settings = buildScanSettings()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                emitAggregatedDeviceUpdate(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { result ->
                    emitAggregatedDeviceUpdate(result)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "BLE scan failed: errorCode=$errorCode")
                clearActiveScan(
                    notifyStopped = true,
                    stopReason = "scan failed errorCode=$errorCode"
                )
            }
        }

        try {
            activeScanner = scanner
            activeCallback = callback
            activeListener = listener
            Log.d(
                TAG,
                "BLE scan start: mode=LOW_LATENCY callbackType=${describeCallbackType()} filters=none"
            )
            scanner.startScan(
                emptyList(),
                settings,
                callback
            )
            isScanning = true
            listener.onStatusChanged(BleScanStatus.SCANNING)
        } catch (securityException: SecurityException) {
            Log.w(TAG, "BLE scan start failed with security exception", securityException)
            clearActiveScan(
                notifyStopped = false,
                stopReason = "scan start security exception"
            )
            listener.onStatusChanged(BleScanStatus.STOPPED)
        } catch (runtimeException: RuntimeException) {
            Log.w(TAG, "BLE scan start failed with runtime exception", runtimeException)
            clearActiveScan(
                notifyStopped = false,
                stopReason = "scan start runtime exception"
            )
            listener.onStatusChanged(BleScanStatus.STOPPED)
        }
    }

    override fun stop() {
        clearActiveScan(
            notifyStopped = true,
            stopReason = "stop requested"
        )
    }

    private fun clearActiveScan(
        notifyStopped: Boolean,
        stopReason: String
    ) {
        val scanner = activeScanner
        val callback = activeCallback
        val listener = activeListener
        val shouldNotifyStopped = notifyStopped && isScanning

        Log.d(
            TAG,
            "BLE scan stop: reason=$stopReason notifyStopped=$shouldNotifyStopped hasScanner=${scanner != null} hasCallback=${callback != null}"
        )
        if (scanner != null && callback != null) {
            try {
                scanner.stopScan(callback)
            } catch (securityException: SecurityException) {
                Log.w(TAG, "BLE scan stop failed with security exception", securityException)
            } catch (runtimeException: RuntimeException) {
                Log.w(TAG, "BLE scan stop failed with runtime exception", runtimeException)
            }
        }

        activeScanner = null
        activeCallback = null
        activeListener = null
        isScanning = false
        aggregator.clear()
        diagnostics = BleScanDiagnostics()

        if (shouldNotifyStopped) {
            listener?.onStatusChanged(BleScanStatus.STOPPED)
        }
    }

    private fun buildScanSettings(): ScanSettings {
        return ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                }
                setReportDelay(0)
            }
            .build()
    }

    private fun describeCallbackType(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            "ALL_MATCHES"
        } else {
            "platform-default"
        }
    }

    private fun emitAggregatedDeviceUpdate(result: ScanResult) {
        val address = result.safeDeviceAddress()
        if (address.isBlank()) {
            return
        }
        val name = result.safeDeviceName() ?: result.safeScanRecordDeviceName()
        val discoveryServiceData = result.safeDiscoveryServiceData()
        val discoveryPayload = BleDiscoveryPayload.parse(discoveryServiceData)
        val hasAuroraDiscoveryPayload = discoveryPayload != null

        val updatedDiagnostics = diagnostics.record(
            deviceName = name,
            deviceAddress = address,
            rssi = result.rssi,
            hadDiscoveryServiceData = discoveryServiceData != null,
            hadAuroraDiscoveryPayload = hasAuroraDiscoveryPayload
        )
        diagnostics = updatedDiagnostics
        val parseOutcome = describeDiscoveryParseOutcome(
            discoveryServiceData = discoveryServiceData,
            discoveryPayload = discoveryPayload
        )
        Log.d(
            TAG,
            "BLE raw result: count=${updatedDiagnostics.rawScanResultCount} matches=${updatedDiagnostics.auroraDiscoveryMatchCount} address=$address rssi=${result.rssi} serviceDataBytes=${discoveryServiceData?.size ?: 0} parse=$parseOutcome"
        )

        val mappedDevice = BleDiscoveredDevice(
            address = address,
            name = name,
            rssi = result.rssi,
            isConnectable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                result.isConnectable
            } else {
                null
            },
            hasAuroraDiscoveryPayload = hasAuroraDiscoveryPayload,
            stablePeerId = discoveryPayload?.stablePeerId
        )

        val mergedDevice = aggregator
            .update(mappedDevice)
            .firstOrNull { device -> device.identityKey() == mappedDevice.identityKey() }
            ?: return

        activeListener?.onDeviceDiscovered(mergedDevice)
    }

    private fun describeDiscoveryParseOutcome(
        discoveryServiceData: ByteArray?,
        discoveryPayload: BleDiscoveryPayload?
    ): String {
        if (discoveryServiceData == null) {
            return "no_service_data"
        }
        if (discoveryPayload != null) {
            return if (discoveryPayload.stablePeerId != null) {
                "marker+stable_peer_id"
            } else {
                "legacy_marker"
            }
        }

        if (discoveryServiceData.size < 2) {
            return "too_short"
        }

        val version = discoveryServiceData[0].toInt() and 0xFF
        val kind = discoveryServiceData[1].toInt() and 0xFF
        return when (discoveryServiceData.size) {
            2, 10 -> "invalid_header_v${version}_k${kind}"
            else -> "invalid_size_${discoveryServiceData.size}"
        }
    }
}

private fun ScanResult.safeDeviceAddress(): String {
    return try {
        device.address
    } catch (_: SecurityException) {
        ""
    } catch (_: RuntimeException) {
        ""
    }
}

private fun ScanResult.safeDeviceName(): String? {
    return try {
        device.name
    } catch (_: SecurityException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}

private fun ScanResult.safeScanRecordDeviceName(): String? {
    return try {
        scanRecord?.deviceName
    } catch (_: SecurityException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}

private fun ScanResult.safeDiscoveryServiceData(): ByteArray? {
    return try {
        scanRecord?.getServiceData(ParcelUuid(BleDiscoveryService.serviceUuid))
    } catch (_: SecurityException) {
        null
    } catch (_: RuntimeException) {
        null
    }
}
