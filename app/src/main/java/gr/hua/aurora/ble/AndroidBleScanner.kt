package gr.hua.aurora.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.os.Build
import android.os.ParcelUuid

class AndroidBleScanner(
    private val bluetoothAdapter: BluetoothAdapter?
) : BleScanner {
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
        clearActiveScan(notifyStopped = false)
        aggregator.clear()
        diagnostics = BleScanDiagnostics()

        val adapter = bluetoothAdapter ?: run {
            listener.onStatusChanged(BleScanStatus.STOPPED)
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            listener.onStatusChanged(BleScanStatus.STOPPED)
            return
        }

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
                clearActiveScan(notifyStopped = true)
            }
        }

        try {
            activeScanner = scanner
            activeCallback = callback
            activeListener = listener
            scanner.startScan(callback)
            isScanning = true
            listener.onStatusChanged(BleScanStatus.SCANNING)
        } catch (_: SecurityException) {
            clearActiveScan(notifyStopped = false)
            listener.onStatusChanged(BleScanStatus.STOPPED)
        } catch (_: RuntimeException) {
            clearActiveScan(notifyStopped = false)
            listener.onStatusChanged(BleScanStatus.STOPPED)
        }
    }

    override fun stop() {
        clearActiveScan(notifyStopped = true)
    }

    private fun clearActiveScan(notifyStopped: Boolean) {
        val scanner = activeScanner
        val callback = activeCallback
        val listener = activeListener
        val shouldNotifyStopped = notifyStopped && isScanning

        if (scanner != null && callback != null) {
            try {
                scanner.stopScan(callback)
            } catch (_: SecurityException) {
            } catch (_: RuntimeException) {
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

    private fun emitAggregatedDeviceUpdate(result: ScanResult) {
        val address = result.safeDeviceAddress()
        if (address.isBlank()) {
            return
        }
        val name = result.safeDeviceName() ?: result.safeScanRecordDeviceName()
        val discoveryServiceData = result.safeDiscoveryServiceData()
        val hasAuroraDiscoveryPayload = BleDiscoveryPayload.matchesCurrent(discoveryServiceData)

        diagnostics = diagnostics.record(
            deviceName = name,
            deviceAddress = address,
            rssi = result.rssi,
            hadDiscoveryServiceData = discoveryServiceData != null,
            hadAuroraDiscoveryPayload = hasAuroraDiscoveryPayload
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
            hasAuroraDiscoveryPayload = hasAuroraDiscoveryPayload
        )

        val mergedDevice = aggregator
            .update(mappedDevice)
            .firstOrNull { device -> device.address == mappedDevice.address }
            ?: return

        activeListener?.onDeviceDiscovered(mergedDevice)
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
