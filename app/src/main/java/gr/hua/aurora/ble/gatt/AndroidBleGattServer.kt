package gr.hua.aurora.ble.gatt

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import gr.hua.aurora.ble.transport.BleGattTransportFrame
import gr.hua.aurora.ble.transport.BleGattTransportPayload
import gr.hua.aurora.ble.transport.BleTransportFrameListener
import android.util.Log
import java.util.UUID

class AndroidBleGattServer(
    context: Context,
    private val bluetoothManager: BluetoothManager?,
    private val transportFrameListener: BleTransportFrameListener? = null
) : BleGattServer {
    private companion object {
        private const val TAG = "AndroidBleGattServer"
    }

    private val appContext = context.applicationContext
    private var activeServer: BluetoothGattServer? = null
    private var activeListener: BleGattServer.Listener? = null
    private var hasActiveServer = false

    override fun start(listener: BleGattServer.Listener) {
        clearActiveServer(notifyStopped = false)

        val manager = bluetoothManager ?: run {
            listener.onStatusChanged(BleGattServerStatus.STOPPED)
            return
        }

        var startedServer: BluetoothGattServer? = null
        val callback = object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                if (activeServer !== startedServer || activeListener == null) {
                    return
                }

                if (
                    status == BluetoothGatt.GATT_SUCCESS &&
                    service.uuid == BleGattProfile.serviceUuid
                ) {
                    activeListener?.onStatusChanged(BleGattServerStatus.HOSTING)
                    return
                }

                val failureListener = activeListener
                clearActiveServer(notifyStopped = false)
                failureListener?.onStatusChanged(BleGattServerStatus.STOPPED)
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic
            ) {
                val server = startedServer ?: return
                val isActiveSetup = activeServer === startedServer &&
                    activeListener != null &&
                    hasActiveServer

                if (!isActiveSetup) {
                    try {
                        server.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_FAILURE,
                            offset,
                            null
                        )
                    } catch (_: SecurityException) {
                    } catch (_: RuntimeException) {
                    }
                    return
                }

                val value = transportReadValueFor(characteristic)
                val responseValue = value?.let { readValue ->
                    responseValueForOffset(readValue, offset)
                }
                val responseStatus = if (responseValue != null) {
                    BluetoothGatt.GATT_SUCCESS
                } else {
                    BluetoothGatt.GATT_FAILURE
                }

                try {
                    server.sendResponse(
                        device,
                        requestId,
                        responseStatus,
                        offset,
                        responseValue
                    )
                } catch (_: SecurityException) {
                } catch (_: RuntimeException) {
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                val server = startedServer ?: return
                val isActiveSetup = activeServer === startedServer &&
                    activeListener != null &&
                    hasActiveServer
                val parsedFrame = transportFrameForCharacteristicWrite(
                    characteristic = characteristic,
                    value = value
                )
                logCharacteristicWrite(
                    device = device,
                    characteristic = characteristic,
                    value = value,
                    parsedFrame = parsedFrame
                )
                val writeStatus = if (
                    isActiveSetup &&
                    !preparedWrite &&
                    offset == 0 &&
                    isSupportedCharacteristicWrite(
                        characteristic = characteristic,
                        value = value
                    )
                ) {
                    BluetoothGatt.GATT_SUCCESS
                } else {
                    BluetoothGatt.GATT_FAILURE
                }

                if (responseNeeded) {
                    try {
                        server.sendResponse(
                            device,
                            requestId,
                            writeStatus,
                            offset,
                            null
                        )
                    } catch (_: SecurityException) {
                    } catch (_: RuntimeException) {
                    }
                }

                if (
                    writeStatus == BluetoothGatt.GATT_SUCCESS &&
                    parsedFrame != null
                ) {
                    transportFrameListener?.onFrameReceived(parsedFrame)
                }
            }
        }

        try {
            val server = manager.openGattServer(appContext, callback)
            if (server == null) {
                clearActiveServer(notifyStopped = false)
                listener.onStatusChanged(BleGattServerStatus.STOPPED)
                return
            }

            startedServer = server
            activeServer = server
            activeListener = listener
            hasActiveServer = true

            if (!server.addService(createTransportService())) {
                clearActiveServer(notifyStopped = false)
                listener.onStatusChanged(BleGattServerStatus.STOPPED)
            }
        } catch (_: SecurityException) {
            clearActiveServer(notifyStopped = false)
            listener.onStatusChanged(BleGattServerStatus.STOPPED)
        } catch (_: RuntimeException) {
            clearActiveServer(notifyStopped = false)
            listener.onStatusChanged(BleGattServerStatus.STOPPED)
        }
    }

    override fun stop() {
        clearActiveServer(notifyStopped = true)
    }

    private fun clearActiveServer(notifyStopped: Boolean) {
        val server = activeServer
        val listener = activeListener
        val shouldNotifyStopped = notifyStopped && hasActiveServer

        activeServer = null
        activeListener = null
        hasActiveServer = false

        if (server != null) {
            try {
                server.close()
            } catch (_: SecurityException) {
            } catch (_: RuntimeException) {
            }
        }

        if (shouldNotifyStopped) {
            listener?.onStatusChanged(BleGattServerStatus.STOPPED)
        }
    }
}

private fun createTransportService(): BluetoothGattService {
    return BluetoothGattService(
        BleGattProfile.serviceUuid,
        BluetoothGattService.SERVICE_TYPE_PRIMARY
    ).apply {
        addCharacteristic(createTransportCharacteristic())
        addCharacteristic(createFrameTransportCharacteristic())
    }
}

private fun transportReadValueFor(characteristic: BluetoothGattCharacteristic): ByteArray? {
    return when (characteristic.uuid) {
        BleGattProfile.transportCharacteristicUuid ->
            BleGattTransportPayload.current().toByteArray()

        BleGattProfile.frameTransportCharacteristicUuid ->
            BleGattTransportFrame.create(body = byteArrayOf())?.toByteArray()

        else -> null
    }
}

private fun responseValueForOffset(value: ByteArray, offset: Int): ByteArray? {
    return when {
        offset < 0 -> null
        offset > value.size -> null
        offset == value.size -> byteArrayOf()
        offset == 0 -> value
        else -> value.copyOfRange(offset, value.size)
    }
}

private fun isSupportedTransportWriteValue(value: ByteArray?): Boolean {
    return BleGattTransportPayload.matchesCurrent(value) ||
        BleGattTransportFrame.parse(value) != null
}

private fun transportFrameForCharacteristicWrite(
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray?
): BleGattTransportFrame? {
    return when (characteristic.uuid) {
        BleGattProfile.transportCharacteristicUuid,
        BleGattProfile.frameTransportCharacteristicUuid ->
            BleGattTransportFrame.parse(value)

        else -> null
    }
}

private fun logCharacteristicWrite(
    device: BluetoothDevice,
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray?,
    parsedFrame: BleGattTransportFrame?
) {
    if (characteristic.uuid == BleGattProfile.frameTransportCharacteristicUuid) {
        Log.d(
            "AndroidBleGattServer",
            "BLE raw frame write received: address=${device.address} bytes=${value?.size ?: 0}"
        )
        if (parsedFrame != null) {
            Log.d(
                "AndroidBleGattServer",
                "BLE frame decode success: address=${device.address} bodySize=${parsedFrame.bodyToByteArray().size}"
            )
        } else {
            Log.w(
                "AndroidBleGattServer",
                "BLE frame decode failed: address=${device.address} bytes=${value?.size ?: 0}"
            )
        }
    } else if (
        characteristic.uuid == BleGattProfile.transportCharacteristicUuid &&
        parsedFrame != null
    ) {
        Log.d(
            "AndroidBleGattServer",
            "BLE compatibility frame write received: address=${device.address} bytes=${value?.size ?: 0}"
        )
    }
}

private fun isSupportedCharacteristicWrite(
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray?
): Boolean {
    return when (characteristic.uuid) {
        BleGattProfile.transportCharacteristicUuid ->
            isSupportedTransportWriteValue(value)

        BleGattProfile.frameTransportCharacteristicUuid ->
            BleGattTransportFrame.parse(value) != null

        else -> false
    }
}

private fun createTransportCharacteristic(): BluetoothGattCharacteristic {
    return BluetoothGattCharacteristic(
        BleGattProfile.transportCharacteristicUuid,
        BluetoothGattCharacteristic.PROPERTY_READ or
            BluetoothGattCharacteristic.PROPERTY_WRITE,
        BluetoothGattCharacteristic.PERMISSION_READ or
            BluetoothGattCharacteristic.PERMISSION_WRITE
    )
}

private fun createFrameTransportCharacteristic(): BluetoothGattCharacteristic {
    return BluetoothGattCharacteristic(
        BleGattProfile.frameTransportCharacteristicUuid,
        BluetoothGattCharacteristic.PROPERTY_READ or
            BluetoothGattCharacteristic.PROPERTY_WRITE,
        BluetoothGattCharacteristic.PERMISSION_READ or
            BluetoothGattCharacteristic.PERMISSION_WRITE
    )
}
