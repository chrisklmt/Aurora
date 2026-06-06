package gr.hua.aurora.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.content.Context

class AndroidBleConnector(
    context: Context,
    private val bluetoothAdapter: BluetoothAdapter?
) : BleConnector {
    private val appContext = context.applicationContext
    private var activeGatt: BluetoothGatt? = null
    private var activeListener: BleConnector.Listener? = null
    private var hasActiveConnection = false

    override fun connect(
        deviceAddress: String,
        listener: BleConnector.Listener
    ) {
        cleanupActiveConnection(notifyDisconnected = false, requestDisconnect = true)

        val adapter = bluetoothAdapter ?: run {
            listener.onStatusChanged(BleConnectionStatus.DISCONNECTED)
            return
        }
        val address = deviceAddress.takeIf { it.isNotBlank() } ?: run {
            listener.onStatusChanged(BleConnectionStatus.DISCONNECTED)
            return
        }
        val remoteDevice = try {
            adapter.getRemoteDevice(address)
        } catch (_: SecurityException) {
            listener.onStatusChanged(BleConnectionStatus.DISCONNECTED)
            return
        } catch (_: RuntimeException) {
            listener.onStatusChanged(BleConnectionStatus.DISCONNECTED)
            return
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
            ) {
                if (activeGatt !== gatt || activeListener == null) {
                    return
                }

                if (
                    status == BluetoothGatt.GATT_SUCCESS &&
                    newState == BluetoothProfile.STATE_CONNECTED
                ) {
                    val discoverListener = activeListener
                    val didStartDiscovery = try {
                        gatt.discoverServices()
                    } catch (_: SecurityException) {
                        false
                    } catch (_: RuntimeException) {
                        false
                    }

                    if (!didStartDiscovery) {
                        cleanupActiveConnection(
                            notifyDisconnected = false,
                            requestDisconnect = true
                        )
                        discoverListener?.onStatusChanged(BleConnectionStatus.DISCONNECTED)
                    }
                    return
                }

                val disconnectListener = activeListener
                cleanupActiveConnection(
                    notifyDisconnected = false,
                    requestDisconnect = false
                )
                disconnectListener?.onStatusChanged(BleConnectionStatus.DISCONNECTED)
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int
            ) {
                if (activeGatt !== gatt || activeListener == null) {
                    return
                }

                val service = gatt.getService(BleGattProfile.serviceUuid)
                val characteristic = service?.getCharacteristic(
                    BleGattProfile.transportCharacteristicUuid
                )
                if (
                    status == BluetoothGatt.GATT_SUCCESS &&
                    service != null &&
                    characteristic != null
                ) {
                    activeListener?.onStatusChanged(BleConnectionStatus.CONNECTED)
                    return
                }

                val disconnectListener = activeListener
                cleanupActiveConnection(
                    notifyDisconnected = false,
                    requestDisconnect = true
                )
                disconnectListener?.onStatusChanged(BleConnectionStatus.DISCONNECTED)
            }
        }

        listener.onStatusChanged(BleConnectionStatus.CONNECTING)
        activeListener = listener
        hasActiveConnection = true

        try {
            val gatt = remoteDevice.connectGatt(appContext, false, callback)
            if (gatt == null) {
                cleanupActiveConnection(
                    notifyDisconnected = false,
                    requestDisconnect = false
                )
                listener.onStatusChanged(BleConnectionStatus.DISCONNECTED)
                return
            }

            activeGatt = gatt
        } catch (_: SecurityException) {
            cleanupActiveConnection(notifyDisconnected = false, requestDisconnect = false)
            listener.onStatusChanged(BleConnectionStatus.DISCONNECTED)
        } catch (_: RuntimeException) {
            cleanupActiveConnection(notifyDisconnected = false, requestDisconnect = false)
            listener.onStatusChanged(BleConnectionStatus.DISCONNECTED)
        }
    }

    override fun disconnect() {
        cleanupActiveConnection(notifyDisconnected = true, requestDisconnect = true)
    }

    private fun cleanupActiveConnection(
        notifyDisconnected: Boolean,
        requestDisconnect: Boolean
    ) {
        val gatt = activeGatt
        val listener = activeListener
        val shouldNotifyDisconnected = notifyDisconnected && hasActiveConnection

        activeGatt = null
        activeListener = null
        hasActiveConnection = false

        if (gatt != null) {
            if (requestDisconnect) {
                try {
                    gatt.disconnect()
                } catch (_: SecurityException) {
                } catch (_: RuntimeException) {
                }
            }

            try {
                gatt.close()
            } catch (_: SecurityException) {
            } catch (_: RuntimeException) {
            }
        }

        if (shouldNotifyDisconnected) {
            listener?.onStatusChanged(BleConnectionStatus.DISCONNECTED)
        }
    }
}
