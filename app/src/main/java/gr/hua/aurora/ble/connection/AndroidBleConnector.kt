package gr.hua.aurora.ble.connection

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import gr.hua.aurora.ble.gatt.BleGattProfile
import gr.hua.aurora.ble.transport.BleGattTransportFrame
import gr.hua.aurora.ble.transport.BleGattTransportFrameReadResult
import gr.hua.aurora.ble.transport.BleGattTransportFrameReader
import gr.hua.aurora.ble.transport.BleGattTransportFrameWriteResult
import gr.hua.aurora.ble.transport.BleGattTransportFrameWriter
import gr.hua.aurora.ble.transport.BleGattTransportPayload
import gr.hua.aurora.ble.transport.BleGattTransportReadResult
import gr.hua.aurora.ble.transport.BleGattTransportReader
import gr.hua.aurora.ble.transport.BleGattTransportWriteResult
import gr.hua.aurora.ble.transport.BleGattTransportWriter

class AndroidBleConnector(
    context: Context,
    private val bluetoothAdapter: BluetoothAdapter?
) : BleConnector,
    BleGattTransportReader,
    BleGattTransportWriter,
    BleGattTransportFrameWriter,
    BleGattTransportFrameReader {
    private companion object {
        private const val TAG = "AndroidBleConnector"
        private const val CONNECTION_TIMEOUT_MS = 10_000L
        private const val SERVICE_DISCOVERY_TIMEOUT_MS = 5_000L
        private const val MTU_TIMEOUT_MS = 2_000L
        private const val TRANSPORT_OPERATION_TIMEOUT_MS = 5_000L
        private const val REQUESTED_MTU = 517
    }

    private val appContext = context.applicationContext
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var activeGatt: BluetoothGatt? = null
    private var activeListener: BleConnector.Listener? = null
    private var pendingReadListener: BleGattTransportReader.Listener? = null
    private var pendingFrameReadListener: BleGattTransportFrameReader.Listener? = null
    private var pendingWriteListener: BleGattTransportWriter.Listener? = null
    private var pendingFrameWriteListener: BleGattTransportFrameWriter.Listener? = null
    private var retainedTransportCharacteristic: BluetoothGattCharacteristic? = null
    private var retainedFrameTransportCharacteristic: BluetoothGattCharacteristic? = null
    private var hasActiveConnection = false
    private var awaitingMtuCallback = false
    private var serviceDiscoveryStarted = false
    private var connectionTimeoutRunnable: Runnable? = null
    private var serviceDiscoveryTimeoutRunnable: Runnable? = null
    private var mtuTimeoutRunnable: Runnable? = null
    private var readTimeoutRunnable: Runnable? = null
    private var writeTimeoutRunnable: Runnable? = null

    override fun connect(
        deviceAddress: String,
        listener: BleConnector.Listener
    ) {
        cleanupActiveConnection(
            notifyDisconnected = false,
            requestDisconnect = true,
            cleanupReason = "new connect requested"
        )

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
            Log.w(TAG, "BLE connect failed: address=$address security exception")
            listener.onStatusChanged(BleConnectionStatus.DISCONNECTED)
            return
        } catch (_: RuntimeException) {
            Log.w(TAG, "BLE connect failed: address=$address runtime exception")
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

                Log.d(
                    TAG,
                    "BLE connection callback: address=${gatt.device.address} status=$status state=$newState"
                )

                if (
                    status == BluetoothGatt.GATT_SUCCESS &&
                    newState == BluetoothProfile.STATE_CONNECTED
                ) {
                    cancelConnectionTimeout()
                    startServiceDiscoveryHandshake(gatt)
                    return
                }

                val disconnectListener = activeListener
                cleanupActiveConnection(
                    notifyDisconnected = false,
                    requestDisconnect = false,
                    cleanupReason = "connection callback status=$status state=$newState"
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

                cancelServiceDiscoveryTimeout()
                Log.d(
                    TAG,
                    "BLE service discovery callback: address=${gatt.device.address} status=$status"
                )

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(
                        TAG,
                        "BLE service discovery failed: address=${gatt.device.address} status=$status"
                    )
                    val disconnectListener = activeListener
                    cleanupActiveConnection(
                        notifyDisconnected = false,
                        requestDisconnect = true,
                        cleanupReason = "service discovery failed status=$status"
                    )
                    disconnectListener?.onStatusChanged(BleConnectionStatus.DISCONNECTED)
                    return
                }

                val service = gatt.getService(BleGattProfile.serviceUuid)
                if (service == null) {
                    Log.w(
                        TAG,
                        "BLE service missing: address=${gatt.device.address} uuid=${BleGattProfile.serviceUuid}"
                    )
                }
                val characteristic = service?.getCharacteristic(
                    BleGattProfile.transportCharacteristicUuid
                )
                if (characteristic == null) {
                    Log.w(
                        TAG,
                        "BLE transport characteristic missing: address=${gatt.device.address} uuid=${BleGattProfile.transportCharacteristicUuid}"
                    )
                }
                val frameCharacteristic = service?.getCharacteristic(
                    BleGattProfile.frameTransportCharacteristicUuid
                )
                if (service != null && characteristic != null) {
                    if (frameCharacteristic == null) {
                        Log.d(
                            TAG,
                            "BLE frame characteristic unavailable: address=${gatt.device.address}"
                        )
                    }
                    retainedTransportCharacteristic = characteristic
                    retainedFrameTransportCharacteristic = frameCharacteristic
                    activeListener?.onStatusChanged(BleConnectionStatus.CONNECTED)
                    return
                }

                val disconnectListener = activeListener
                cleanupActiveConnection(
                    notifyDisconnected = false,
                    requestDisconnect = true,
                    cleanupReason = "service discovery incomplete"
                )
                disconnectListener?.onStatusChanged(BleConnectionStatus.DISCONNECTED)
            }

            override fun onMtuChanged(
                gatt: BluetoothGatt,
                mtu: Int,
                status: Int
            ) {
                if (activeGatt !== gatt || activeListener == null) {
                    return
                }

                cancelMtuTimeout()
                awaitingMtuCallback = false

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(
                        TAG,
                        "BLE mtu callback: address=${gatt.device.address} mtu=$mtu status=$status"
                    )
                } else {
                    Log.w(
                        TAG,
                        "BLE mtu callback failed: address=${gatt.device.address} mtu=$mtu status=$status"
                    )
                }

                startServiceDiscovery(gatt)
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
        Log.d(TAG, "BLE connect start: address=$address")

        try {
            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Log.d(TAG, "BLE connectGatt path: address=$address transport=LE")
                remoteDevice.connectGatt(
                    appContext,
                    false,
                    callback,
                    BluetoothDevice.TRANSPORT_LE
                )
            } else {
                Log.d(TAG, "BLE connectGatt path: address=$address transport=legacy")
                remoteDevice.connectGatt(appContext, false, callback)
            }
            if (gatt == null) {
                Log.w(TAG, "BLE connect failed: address=$address connectGatt returned null")
                cleanupActiveConnection(
                    notifyDisconnected = false,
                    requestDisconnect = false,
                    cleanupReason = "connectGatt returned null"
                )
                listener.onStatusChanged(BleConnectionStatus.DISCONNECTED)
                return
            }

            activeGatt = gatt
            scheduleConnectionTimeout(gatt)
        } catch (securityException: SecurityException) {
            Log.w(
                TAG,
                "BLE connect failed with security exception: address=$address",
                securityException
            )
            cleanupActiveConnection(
                notifyDisconnected = false,
                requestDisconnect = false,
                cleanupReason = "connect security exception"
            )
            listener.onStatusChanged(BleConnectionStatus.DISCONNECTED)
        } catch (runtimeException: RuntimeException) {
            Log.w(
                TAG,
                "BLE connect failed with runtime exception: address=$address",
                runtimeException
            )
            cleanupActiveConnection(
                notifyDisconnected = false,
                requestDisconnect = false,
                cleanupReason = "connect runtime exception"
            )
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
        } else {
            scheduleReadTimeout(gatt, characteristic.uuid.toString())
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
        } else {
            scheduleReadTimeout(gatt, characteristic.uuid.toString())
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
        } else {
            scheduleWriteTimeout(gatt, characteristic.uuid.toString())
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
        } else {
            scheduleWriteTimeout(gatt, characteristic.uuid.toString())
        }
    }

    override fun disconnect() {
        cleanupActiveConnection(
            notifyDisconnected = true,
            requestDisconnect = true,
            cleanupReason = "disconnect requested"
        )
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

        cancelReadTimeout()
        Log.d(
            TAG,
            "BLE read callback: address=${gatt.device.address} uuid=${characteristic.uuid} status=$status"
        )

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

        cancelWriteTimeout()
        Log.d(
            TAG,
            "BLE write callback: address=${gatt.device.address} uuid=${characteristic.uuid} status=$status"
        )

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
        requestDisconnect: Boolean,
        cleanupReason: String
    ) {
        val gatt = activeGatt
        val listener = activeListener
        val shouldNotifyDisconnected = notifyDisconnected && hasActiveConnection

        Log.d(
            TAG,
            "BLE cleanup: reason=$cleanupReason requestDisconnect=$requestDisconnect notifyDisconnected=$shouldNotifyDisconnected hasGatt=${gatt != null}"
        )
        cancelAllTimeouts()
        clearPendingTransportOperations()
        activeGatt = null
        activeListener = null
        retainedTransportCharacteristic = null
        retainedFrameTransportCharacteristic = null
        hasActiveConnection = false
        awaitingMtuCallback = false
        serviceDiscoveryStarted = false

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

    private fun startServiceDiscoveryHandshake(gatt: BluetoothGatt) {
        serviceDiscoveryStarted = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val didRequestMtu = try {
                gatt.requestMtu(REQUESTED_MTU)
            } catch (securityException: SecurityException) {
                Log.w(
                    TAG,
                    "BLE mtu request failed with security exception: address=${gatt.device.address}",
                    securityException
                )
                false
            } catch (runtimeException: RuntimeException) {
                Log.w(
                    TAG,
                    "BLE mtu request failed with runtime exception: address=${gatt.device.address}",
                    runtimeException
                )
                false
            }

            if (didRequestMtu) {
                awaitingMtuCallback = true
                Log.d(
                    TAG,
                    "BLE mtu request start: address=${gatt.device.address} mtu=$REQUESTED_MTU"
                )
                scheduleMtuTimeout(gatt)
                return
            }

            Log.d(
                TAG,
                "BLE mtu request unavailable: address=${gatt.device.address} continuing to discovery"
            )
        }

        startServiceDiscovery(gatt)
    }

    private fun startServiceDiscovery(gatt: BluetoothGatt) {
        if (activeGatt !== gatt || activeListener == null || serviceDiscoveryStarted) {
            return
        }

        awaitingMtuCallback = false
        cancelMtuTimeout()
        serviceDiscoveryStarted = true
        Log.d(TAG, "BLE service discovery start: address=${gatt.device.address}")

        val didStartDiscovery = try {
            gatt.discoverServices()
        } catch (securityException: SecurityException) {
            Log.w(
                TAG,
                "BLE service discovery start failed with security exception: address=${gatt.device.address}",
                securityException
            )
            false
        } catch (runtimeException: RuntimeException) {
            Log.w(
                TAG,
                "BLE service discovery start failed with runtime exception: address=${gatt.device.address}",
                runtimeException
            )
            false
        }

        if (!didStartDiscovery) {
            Log.w(
                TAG,
                "BLE service discovery failed to start: address=${gatt.device.address}"
            )
            val discoverListener = activeListener
            cleanupActiveConnection(
                notifyDisconnected = false,
                requestDisconnect = true,
                cleanupReason = "service discovery start failed"
            )
            discoverListener?.onStatusChanged(BleConnectionStatus.DISCONNECTED)
            return
        }

        scheduleServiceDiscoveryTimeout(gatt)
    }

    private fun scheduleConnectionTimeout(gatt: BluetoothGatt) {
        cancelConnectionTimeout()
        connectionTimeoutRunnable = Runnable {
            if (activeGatt !== gatt || activeListener == null) {
                return@Runnable
            }

            Log.w(TAG, "BLE connect timed out: address=${gatt.device.address}")
            val timeoutListener = activeListener
            cleanupActiveConnection(
                notifyDisconnected = false,
                requestDisconnect = true,
                cleanupReason = "connection timed out"
            )
            timeoutListener?.onStatusChanged(BleConnectionStatus.DISCONNECTED)
        }.also {
            timeoutHandler.postDelayed(it, CONNECTION_TIMEOUT_MS)
        }
    }

    private fun cancelConnectionTimeout() {
        connectionTimeoutRunnable?.let(timeoutHandler::removeCallbacks)
        connectionTimeoutRunnable = null
    }

    private fun scheduleServiceDiscoveryTimeout(gatt: BluetoothGatt) {
        cancelServiceDiscoveryTimeout()
        serviceDiscoveryTimeoutRunnable = Runnable {
            if (activeGatt !== gatt || activeListener == null || !serviceDiscoveryStarted) {
                return@Runnable
            }

            Log.w(TAG, "BLE service discovery timed out: address=${gatt.device.address}")
            val timeoutListener = activeListener
            cleanupActiveConnection(
                notifyDisconnected = false,
                requestDisconnect = true,
                cleanupReason = "service discovery timed out"
            )
            timeoutListener?.onStatusChanged(BleConnectionStatus.DISCONNECTED)
        }.also {
            timeoutHandler.postDelayed(it, SERVICE_DISCOVERY_TIMEOUT_MS)
        }
    }

    private fun cancelServiceDiscoveryTimeout() {
        serviceDiscoveryTimeoutRunnable?.let(timeoutHandler::removeCallbacks)
        serviceDiscoveryTimeoutRunnable = null
    }

    private fun scheduleMtuTimeout(gatt: BluetoothGatt) {
        cancelMtuTimeout()
        mtuTimeoutRunnable = Runnable {
            if (activeGatt !== gatt || activeListener == null || !awaitingMtuCallback) {
                return@Runnable
            }

            Log.w(
                TAG,
                "BLE mtu request timed out: address=${gatt.device.address} continuing to discovery"
            )
            awaitingMtuCallback = false
            startServiceDiscovery(gatt)
        }.also {
            timeoutHandler.postDelayed(it, MTU_TIMEOUT_MS)
        }
    }

    private fun cancelMtuTimeout() {
        mtuTimeoutRunnable?.let(timeoutHandler::removeCallbacks)
        mtuTimeoutRunnable = null
    }

    private fun scheduleReadTimeout(
        gatt: BluetoothGatt,
        characteristicId: String
    ) {
        cancelReadTimeout()
        readTimeoutRunnable = Runnable {
            val readListener = takePendingReadListener()
            val frameReadListener = takePendingFrameReadListener()
            if (readListener == null && frameReadListener == null) {
                return@Runnable
            }

            Log.w(
                TAG,
                "BLE read timed out: address=${gatt.device.address} uuid=$characteristicId"
            )
            readListener?.onReadResult(BleGattTransportReadResult.NotAvailable)
            frameReadListener?.onReadResult(BleGattTransportFrameReadResult.NotAvailable)
        }.also {
            timeoutHandler.postDelayed(it, TRANSPORT_OPERATION_TIMEOUT_MS)
        }
    }

    private fun cancelReadTimeout() {
        readTimeoutRunnable?.let(timeoutHandler::removeCallbacks)
        readTimeoutRunnable = null
    }

    private fun scheduleWriteTimeout(
        gatt: BluetoothGatt,
        characteristicId: String
    ) {
        cancelWriteTimeout()
        writeTimeoutRunnable = Runnable {
            val writeListener = takePendingWriteListener()
            val frameWriteListener = takePendingFrameWriteListener()
            if (writeListener == null && frameWriteListener == null) {
                return@Runnable
            }

            Log.w(
                TAG,
                "BLE write timed out: address=${gatt.device.address} uuid=$characteristicId"
            )
            writeListener?.onWriteResult(BleGattTransportWriteResult.NotAvailable)
            frameWriteListener?.onWriteResult(BleGattTransportFrameWriteResult.NotAvailable)
        }.also {
            timeoutHandler.postDelayed(it, TRANSPORT_OPERATION_TIMEOUT_MS)
        }
    }

    private fun cancelWriteTimeout() {
        writeTimeoutRunnable?.let(timeoutHandler::removeCallbacks)
        writeTimeoutRunnable = null
    }

    private fun cancelAllTimeouts() {
        cancelConnectionTimeout()
        cancelServiceDiscoveryTimeout()
        cancelMtuTimeout()
        cancelReadTimeout()
        cancelWriteTimeout()
    }

    private fun clearPendingTransportOperations() {
        takePendingReadListener()?.onReadResult(BleGattTransportReadResult.NotAvailable)
        takePendingFrameReadListener()?.onReadResult(BleGattTransportFrameReadResult.NotAvailable)
        takePendingWriteListener()?.onWriteResult(BleGattTransportWriteResult.NotAvailable)
        takePendingFrameWriteListener()?.onWriteResult(BleGattTransportFrameWriteResult.NotAvailable)
    }

    private fun startTransportCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ): Boolean {
        return try {
            val didStartRead = gatt.readCharacteristic(characteristic)
            if (!didStartRead) {
                Log.w(
                    TAG,
                    "BLE read failed to start: address=${gatt.device.address} uuid=${characteristic.uuid}"
                )
            }
            didStartRead
        } catch (securityException: SecurityException) {
            Log.w(
                TAG,
                "BLE read failed with security exception: address=${gatt.device.address} uuid=${characteristic.uuid}",
                securityException
            )
            false
        } catch (runtimeException: RuntimeException) {
            Log.w(
                TAG,
                "BLE read failed with runtime exception: address=${gatt.device.address} uuid=${characteristic.uuid}",
                runtimeException
            )
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
                val writeStatus = gatt.writeCharacteristic(
                    characteristic,
                    value,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
                if (writeStatus != BluetoothStatusCodes.SUCCESS) {
                    Log.w(
                        TAG,
                        "BLE write failed to start: address=${gatt.device.address} uuid=${characteristic.uuid} status=$writeStatus"
                    )
                }
                writeStatus == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.value = value
                val didStartWrite = gatt.writeCharacteristic(characteristic)
                if (!didStartWrite) {
                    Log.w(
                        TAG,
                        "BLE write failed to start: address=${gatt.device.address} uuid=${characteristic.uuid}"
                    )
                }
                didStartWrite
            }
        } catch (securityException: SecurityException) {
            Log.w(
                TAG,
                "BLE write failed with security exception: address=${gatt.device.address} uuid=${characteristic.uuid}",
                securityException
            )
            false
        } catch (runtimeException: RuntimeException) {
            Log.w(
                TAG,
                "BLE write failed with runtime exception: address=${gatt.device.address} uuid=${characteristic.uuid}",
                runtimeException
            )
            false
        }
    }
}
