package gr.hua.aurora.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build

class AndroidBleConnector(
    context: Context,
    private val bluetoothAdapter: BluetoothAdapter?
) : BleConnector,
    BleGattTransportReader,
    BleGattTransportWriter,
    BleGattTransportFrameWriter,
    BleGattTransportFrameReader {
    private val appContext = context.applicationContext
    private var activeGatt: BluetoothGatt? = null
    private var activeListener: BleConnector.Listener? = null
    private var pendingReadListener: BleGattTransportReader.Listener? = null
    private var pendingFrameReadListener: BleGattTransportFrameReader.Listener? = null
    private var pendingWriteListener: BleGattTransportWriter.Listener? = null
    private var pendingFrameWriteListener: BleGattTransportFrameWriter.Listener? = null
    private var retainedTransportCharacteristic: BluetoothGattCharacteristic? = null
    private var retainedFrameTransportCharacteristic: BluetoothGattCharacteristic? = null
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
                val frameCharacteristic = service?.getCharacteristic(
                    BleGattProfile.frameTransportCharacteristicUuid
                )
                if (
                    status == BluetoothGatt.GATT_SUCCESS &&
                    service != null &&
                    characteristic != null
                ) {
                    retainedTransportCharacteristic = characteristic
                    retainedFrameTransportCharacteristic = frameCharacteristic
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

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                handleCharacteristicRead(
                    gatt = gatt,
                    characteristic = characteristic,
                    status = status,
                    value = characteristic.value
                )
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                handleCharacteristicRead(
                    gatt = gatt,
                    characteristic = characteristic,
                    status = status,
                    value = value
                )
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                handleCharacteristicWrite(
                    gatt = gatt,
                    characteristic = characteristic,
                    status = status
                )
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

    override fun read(listener: BleGattTransportReader.Listener) {
        val gatt = activeGatt ?: run {
            listener.onReadResult(BleGattTransportReadResult.NotAvailable)
            return
        }
        val characteristic = retainedTransportCharacteristic ?: run {
            listener.onReadResult(BleGattTransportReadResult.NotAvailable)
            return
        }
        if (hasPendingTransportRead()) {
            listener.onReadResult(BleGattTransportReadResult.NotAvailable)
            return
        }

        pendingReadListener = listener
        val didStartRead = startTransportCharacteristicRead(
            gatt = gatt,
            characteristic = characteristic
        )

        if (!didStartRead) {
            val readListener = takePendingReadListener()
            readListener?.onReadResult(BleGattTransportReadResult.NotAvailable)
        }
    }

    override fun read(listener: BleGattTransportFrameReader.Listener) {
        val gatt = activeGatt ?: run {
            listener.onReadResult(BleGattTransportFrameReadResult.NotAvailable)
            return
        }
        val characteristic = retainedFrameTransportCharacteristic ?: run {
            listener.onReadResult(BleGattTransportFrameReadResult.NotAvailable)
            return
        }
        if (hasPendingTransportRead()) {
            listener.onReadResult(BleGattTransportFrameReadResult.NotAvailable)
            return
        }

        pendingFrameReadListener = listener
        val didStartRead = startTransportCharacteristicRead(
            gatt = gatt,
            characteristic = characteristic
        )

        if (!didStartRead) {
            val frameReadListener = takePendingFrameReadListener()
            frameReadListener?.onReadResult(BleGattTransportFrameReadResult.NotAvailable)
        }
    }

    override fun write(
        payload: BleGattTransportPayload,
        listener: BleGattTransportWriter.Listener
    ) {
        val gatt = activeGatt ?: run {
            listener.onWriteResult(BleGattTransportWriteResult.NotAvailable)
            return
        }
        val characteristic = retainedTransportCharacteristic ?: run {
            listener.onWriteResult(BleGattTransportWriteResult.NotAvailable)
            return
        }
        if (hasPendingTransportWrite()) {
            listener.onWriteResult(BleGattTransportWriteResult.NotAvailable)
            return
        }

        pendingWriteListener = listener
        val payloadValue = payload.toByteArray()
        val didStartWrite = startTransportCharacteristicWrite(
            gatt = gatt,
            characteristic = characteristic,
            value = payloadValue
        )

        if (!didStartWrite) {
            val writeListener = takePendingWriteListener()
            writeListener?.onWriteResult(BleGattTransportWriteResult.NotAvailable)
        }
    }

    override fun write(
        frame: BleGattTransportFrame,
        listener: BleGattTransportFrameWriter.Listener
    ) {
        val gatt = activeGatt ?: run {
            listener.onWriteResult(BleGattTransportFrameWriteResult.NotAvailable)
            return
        }
        val characteristic = retainedFrameTransportCharacteristic ?: run {
            listener.onWriteResult(BleGattTransportFrameWriteResult.NotAvailable)
            return
        }
        if (hasPendingTransportWrite()) {
            listener.onWriteResult(BleGattTransportFrameWriteResult.NotAvailable)
            return
        }

        pendingFrameWriteListener = listener
        val frameValue = frame.toByteArray()
        val didStartWrite = startTransportCharacteristicWrite(
            gatt = gatt,
            characteristic = characteristic,
            value = frameValue
        )

        if (!didStartWrite) {
            val frameWriteListener = takePendingFrameWriteListener()
            frameWriteListener?.onWriteResult(BleGattTransportFrameWriteResult.NotAvailable)
        }
    }

    override fun disconnect() {
        cleanupActiveConnection(notifyDisconnected = true, requestDisconnect = true)
    }

    private fun handleCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
        value: ByteArray?
    ) {
        if (activeGatt !== gatt) {
            return
        }

        val hasPendingMarkerRead = pendingReadListener != null
        val hasPendingFrameRead = pendingFrameReadListener != null
        if (!hasPendingMarkerRead && !hasPendingFrameRead) {
            return
        }

        val markerReadDidSucceed =
            characteristic.uuid == BleGattProfile.transportCharacteristicUuid &&
                status == BluetoothGatt.GATT_SUCCESS
        val frameReadDidSucceed =
            characteristic.uuid == BleGattProfile.frameTransportCharacteristicUuid &&
                status == BluetoothGatt.GATT_SUCCESS

        if (hasPendingMarkerRead) {
            val readListener = takePendingReadListener() ?: return
            if (markerReadDidSucceed && BleGattTransportPayload.matchesCurrent(value)) {
                readListener.onReadResult(BleGattTransportReadResult.MarkerSeen)
            } else {
                readListener.onReadResult(BleGattTransportReadResult.NotAvailable)
            }
            return
        }

        val frameReadListener = takePendingFrameReadListener() ?: return
        val frame = if (frameReadDidSucceed) BleGattTransportFrame.parse(value) else null
        if (frame != null) {
            frameReadListener.onReadResult(BleGattTransportFrameReadResult.FrameAvailable(frame))
        } else {
            frameReadListener.onReadResult(BleGattTransportFrameReadResult.NotAvailable)
        }
    }

    private fun handleCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int
    ) {
        if (activeGatt !== gatt) {
            return
        }

        val hasPendingMarkerWrite = pendingWriteListener != null
        val hasPendingFrameWrite = pendingFrameWriteListener != null
        if (!hasPendingMarkerWrite && !hasPendingFrameWrite) {
            return
        }

        val markerWriteDidSucceed =
            characteristic.uuid == BleGattProfile.transportCharacteristicUuid &&
                status == BluetoothGatt.GATT_SUCCESS
        val frameWriteDidSucceed =
            characteristic.uuid == BleGattProfile.frameTransportCharacteristicUuid &&
                status == BluetoothGatt.GATT_SUCCESS

        if (hasPendingMarkerWrite) {
            val writeListener = takePendingWriteListener() ?: return
            if (markerWriteDidSucceed) {
                writeListener.onWriteResult(BleGattTransportWriteResult.Accepted)
            } else {
                writeListener.onWriteResult(BleGattTransportWriteResult.NotAvailable)
            }
            return
        }

        val frameWriteListener = takePendingFrameWriteListener() ?: return
        if (frameWriteDidSucceed) {
            frameWriteListener.onWriteResult(BleGattTransportFrameWriteResult.Accepted)
        } else {
            frameWriteListener.onWriteResult(BleGattTransportFrameWriteResult.NotAvailable)
        }
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
        pendingReadListener = null
        pendingFrameReadListener = null
        pendingWriteListener = null
        pendingFrameWriteListener = null
        retainedTransportCharacteristic = null
        retainedFrameTransportCharacteristic = null
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

    private fun takePendingReadListener(): BleGattTransportReader.Listener? {
        val listener = pendingReadListener
        pendingReadListener = null
        return listener
    }

    private fun takePendingFrameReadListener(): BleGattTransportFrameReader.Listener? {
        val listener = pendingFrameReadListener
        pendingFrameReadListener = null
        return listener
    }

    private fun takePendingWriteListener(): BleGattTransportWriter.Listener? {
        val listener = pendingWriteListener
        pendingWriteListener = null
        return listener
    }

    private fun takePendingFrameWriteListener(): BleGattTransportFrameWriter.Listener? {
        val listener = pendingFrameWriteListener
        pendingFrameWriteListener = null
        return listener
    }

    private fun hasPendingTransportWrite(): Boolean {
        return pendingWriteListener != null || pendingFrameWriteListener != null
    }

    private fun hasPendingTransportRead(): Boolean {
        return pendingReadListener != null || pendingFrameReadListener != null
    }

    private fun startTransportCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ): Boolean {
        return try {
            gatt.readCharacteristic(characteristic)
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun startTransportCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic,
                    value,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.value = value
                gatt.writeCharacteristic(characteristic)
            }
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }
}
