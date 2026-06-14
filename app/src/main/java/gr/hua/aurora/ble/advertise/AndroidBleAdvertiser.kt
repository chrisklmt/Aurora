package gr.hua.aurora.ble.advertise

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.ParcelUuid
import android.util.Log

private const val tag = "AuroraBleAdvertiser"

class AndroidBleAdvertiser(
    private val bluetoothAdapter: BluetoothAdapter?
) : BleAdvertiser {
    private var activeAdvertiser: BluetoothLeAdvertiser? = null
    private var activeCallback: AdvertiseCallback? = null
    private var activeListener: BleAdvertiser.Listener? = null
    private var isAdvertising = false

    override fun start(
        request: BleAdvertiseRequest,
        listener: BleAdvertiser.Listener
    ) {
        clearActiveAdvertising(notifyStopped = false)

        val adapter = bluetoothAdapter ?: run {
            listener.onStatusChanged(BleAdvertiseStatus.STOPPED)
            return
        }
        val advertiser = adapter.bluetoothLeAdvertiser ?: run {
            listener.onStatusChanged(BleAdvertiseStatus.STOPPED)
            return
        }

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                if (activeCallback !== this || activeListener == null) {
                    return
                }

                isAdvertising = true
                activeListener?.onStatusChanged(BleAdvertiseStatus.ADVERTISING)
            }

            override fun onStartFailure(errorCode: Int) {
                if (activeCallback !== this) {
                    return
                }

                val failureListener = activeListener
                clearActiveAdvertising(notifyStopped = false)
                Log.w(tag, "BLE advertising start failed: errorCode=$errorCode")
                failureListener?.onStatusChanged(BleAdvertiseStatus.STOPPED)
            }
        }

        try {
            activeAdvertiser = advertiser
            activeCallback = callback
            activeListener = listener
            advertiser.startAdvertising(
                createAdvertiseSettings(),
                createAdvertiseData(request),
                callback
            )
        } catch (securityException: SecurityException) {
            clearActiveAdvertising(notifyStopped = false)
            Log.w(tag, "BLE advertising start failed with security exception", securityException)
            listener.onStatusChanged(BleAdvertiseStatus.STOPPED)
        } catch (runtimeException: RuntimeException) {
            clearActiveAdvertising(notifyStopped = false)
            Log.w(tag, "BLE advertising start failed with runtime exception", runtimeException)
            listener.onStatusChanged(BleAdvertiseStatus.STOPPED)
        }
    }

    override fun stop() {
        clearActiveAdvertising(notifyStopped = true)
    }

    private fun clearActiveAdvertising(notifyStopped: Boolean) {
        val advertiser = activeAdvertiser
        val callback = activeCallback
        val listener = activeListener
        val shouldNotifyStopped = notifyStopped && isAdvertising

        if (advertiser != null && callback != null) {
            try {
                advertiser.stopAdvertising(callback)
            } catch (_: SecurityException) {
            } catch (_: RuntimeException) {
            }
        }

        activeAdvertiser = null
        activeCallback = null
        activeListener = null
        isAdvertising = false

        if (shouldNotifyStopped) {
            listener?.onStatusChanged(BleAdvertiseStatus.STOPPED)
        }
    }
}

private fun createAdvertiseSettings(): AdvertiseSettings {
    return AdvertiseSettings.Builder()
        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
        .setConnectable(true)
        .build()
}

private fun createAdvertiseData(request: BleAdvertiseRequest): AdvertiseData {
    val serviceDataUuid = ParcelUuid(request.serviceUuid)
    return AdvertiseData.Builder()
        // Το service data μένει στο primary packet γιατί ένα 128-bit UUID μαζί με
        // 128-bit service data μπορεί να ξεπεράσει το legacy όριο των 31 bytes.
        .addServiceData(serviceDataUuid, request.payload)
        .setIncludeDeviceName(false)
        .setIncludeTxPowerLevel(false)
        .build()
}
