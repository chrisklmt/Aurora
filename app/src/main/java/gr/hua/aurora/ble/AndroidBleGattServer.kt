package gr.hua.aurora.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import java.util.UUID

class AndroidBleGattServer(
    context: Context,
    private val bluetoothManager: BluetoothManager?
) : BleGattServer {
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
                val isTransportCharacteristic =
                    characteristic.uuid == BleGattProfile.transportCharacteristicUuid

                if (!isActiveSetup || !isTransportCharacteristic) {
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

                val payload = BleGattTransportPayload.current().toByteArray()
                val responseValue = when {
                    offset < 0 -> null
                    offset > payload.size -> null
                    offset == payload.size -> byteArrayOf()
                    offset == 0 -> payload
                    else -> payload.copyOfRange(offset, payload.size)
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
                val isTransportCharacteristic =
                    characteristic.uuid == BleGattProfile.transportCharacteristicUuid
                val writeStatus = if (
                    isActiveSetup &&
                    !preparedWrite &&
                    offset == 0 &&
                    isTransportCharacteristic &&
                    isSupportedTransportWriteValue(value)
                ) {
                    BluetoothGatt.GATT_SUCCESS
                } else {
                    BluetoothGatt.GATT_FAILURE
                }

                if (!responseNeeded) {
                    return
                }

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
    }
}

private fun isSupportedTransportWriteValue(value: ByteArray?): Boolean {
    return BleGattTransportPayload.matchesCurrent(value) ||
        BleGattTransportFrame.parse(value) != null
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
