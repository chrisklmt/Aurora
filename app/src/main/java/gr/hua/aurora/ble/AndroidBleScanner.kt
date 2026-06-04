package gr.hua.aurora.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.os.Build

class AndroidBleScanner(
    private val bluetoothAdapter: BluetoothAdapter?
) : BleScanner {
    private val aggregator = BleScanAggregator()
    private var activeScanner: BluetoothLeScanner? = null
    private var activeCallback: ScanCallback? = null
    private var activeListener: BleScanner.Listener? = null
    private var isScanning = false

    override fun start(listener: BleScanner.Listener) {
        clearActiveScan(notifyStopped = false)
        aggregator.clear()

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

        if (shouldNotifyStopped) {
            listener?.onStatusChanged(BleScanStatus.STOPPED)
        }
    }

    private fun emitAggregatedDeviceUpdate(result: ScanResult) {
        val mappedDevice = result.toBleDiscoveredDevice()
        if (mappedDevice.address.isBlank()) {
            return
        }

        val mergedDevice = aggregator
            .update(mappedDevice)
            .firstOrNull { device -> device.address == mappedDevice.address }
            ?: return

        activeListener?.onDeviceDiscovered(mergedDevice)
    }
}

private fun ScanResult.toBleDiscoveredDevice(): BleDiscoveredDevice {
    return BleDiscoveredDevice(
        address = safeDeviceAddress(),
        name = safeDeviceName() ?: safeScanRecordDeviceName(),
        rssi = rssi,
        isConnectable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            isConnectable
        } else {
            null
        }
    )
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
