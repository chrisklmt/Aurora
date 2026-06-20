package gr.hua.aurora.state

import android.bluetooth.BluetoothManager
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import gr.hua.aurora.ble.advertise.AndroidBleAdvertiser
import gr.hua.aurora.ble.advertise.BleAdvertiseRequest
import gr.hua.aurora.ble.advertise.BleAdvertiseStatus
import gr.hua.aurora.ble.advertise.BleAdvertiser
import gr.hua.aurora.ble.discovery.BleDiscoveryPayload
import gr.hua.aurora.ble.discovery.BleDiscoveryService
import gr.hua.aurora.ble.discovery.BleStablePeerId
import gr.hua.aurora.ble.gatt.AndroidBleGattServer
import gr.hua.aurora.ble.gatt.BleGattServer
import gr.hua.aurora.ble.gatt.BleGattServerStatus
import gr.hua.aurora.ble.noop.NoOpBleTransportSender
import gr.hua.aurora.ble.permissions.BluetoothPermissionStatus
import gr.hua.aurora.ble.permissions.rememberBluetoothPermissionStatusState
import gr.hua.aurora.ble.transport.AndroidBleTransportSender
import gr.hua.aurora.ble.transport.BleGattTransportFrameWriter
import gr.hua.aurora.ble.transport.BleTransportSender
import gr.hua.aurora.identity.AndroidKeystoreLocalAgreementPublicKey
import gr.hua.aurora.ui.components.buildAuroraAvailabilityUiState

private const val auroraBleRuntimeLogTag = "AuroraBleRuntime"

data class AuroraBleRuntimeState(
    val bleAdvertiseStatus: BleAdvertiseStatus,
    val bleGattServerStatus: BleGattServerStatus,
    val bleTransportSender: BleTransportSender
)

internal fun shouldRunAuroraBleRuntime(
    desiredAvailability: AuroraAvailabilityPreference,
    bluetoothStatus: BluetoothPermissionStatus,
    isAppVisible: Boolean
): Boolean {
    return isAppVisible && buildAuroraAvailabilityUiState(
        desiredAvailability = desiredAvailability,
        bluetoothStatus = bluetoothStatus
    ).isOnline
}

@Composable
fun rememberAuroraBleRuntimeState(
    desiredAvailability: AuroraAvailabilityPreference,
    transportFrameWriter: BleGattTransportFrameWriter? = null
): AuroraBleRuntimeState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val bluetoothManager = remember(context) {
        context.getSystemService(BluetoothManager::class.java)
    }
    val bluetoothAdapter = remember(bluetoothManager) {
        bluetoothManager?.adapter
    }
    val bleAdvertiser = remember(bluetoothAdapter) {
        AndroidBleAdvertiser(bluetoothAdapter)
    }
    val bleGattServer = remember(context, bluetoothManager) {
        AndroidBleGattServer(context, bluetoothManager)
    }
    val advertisedStablePeerId = remember {
        runCatching {
            BleStablePeerId.deriveFromPublicKeyBytes(
                AndroidKeystoreLocalAgreementPublicKey.ensureAgreementPublicKeyBytes()
            )
        }.getOrNull()
    }
    val advertiseRequest = remember(advertisedStablePeerId) {
        BleAdvertiseRequest(
            serviceUuid = BleDiscoveryService.serviceUuid,
            payload = BleDiscoveryPayload.current(advertisedStablePeerId).toByteArray()
        )
    }
    val bluetoothStatusState = rememberBluetoothPermissionStatusState()
    val bluetoothStatus = bluetoothStatusState.status
    val bleTransportSender = remember(transportFrameWriter) {
        createAuroraBleTransportSender(transportFrameWriter)
    }
    var isAppVisible by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var bleAdvertiseStatus by remember {
        mutableStateOf(BleAdvertiseStatus.IDLE)
    }
    var bleGattServerStatus by remember {
        mutableStateOf(BleGattServerStatus.IDLE)
    }
    val shouldHostRuntime = shouldRunAuroraBleRuntime(
        desiredAvailability = desiredAvailability,
        bluetoothStatus = bluetoothStatus,
        isAppVisible = isAppVisible
    )
    DisposableEffect(lifecycleOwner, bleAdvertiser, bleGattServer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAppVisible = true
            } else if (event == Lifecycle.Event.ON_STOP) {
                isAppVisible = false
                bleAdvertiser.stop()
                bleAdvertiseStatus = BleAdvertiseStatus.STOPPED
                bleGattServer.stop()
                bleGattServerStatus = BleGattServerStatus.STOPPED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            bleAdvertiser.stop()
            bleGattServer.stop()
        }
    }

    LaunchedEffect(
        desiredAvailability,
        bluetoothStatus,
        isAppVisible,
        shouldHostRuntime,
        advertiseRequest
    ) {
        Log.d(
            auroraBleRuntimeLogTag,
            "BLE runtime: desired=$desiredAvailability shouldHostRuntime=$shouldHostRuntime appVisible=$isAppVisible bluetoothEnabled=${bluetoothStatus.isBluetoothEnabled} locationEnabled=${bluetoothStatus.isLocationEnabled} missingPermissions=${bluetoothStatus.missingPermissions.size} payloadSize=${advertiseRequest.payload.size} stablePeerId=${advertisedStablePeerId != null}"
        )
    }

    DisposableEffect(bleAdvertiser, shouldHostRuntime, advertiseRequest) {
        if (shouldHostRuntime) {
            bleAdvertiseStatus = BleAdvertiseStatus.IDLE
            bleAdvertiser.start(
                request = advertiseRequest,
                listener = object : BleAdvertiser.Listener {
                    override fun onStatusChanged(status: BleAdvertiseStatus) {
                        bleAdvertiseStatus = status
                        Log.d(auroraBleRuntimeLogTag, "BLE advertiser status: $status")
                    }
                }
            )
        } else {
            bleAdvertiser.stop()
            bleAdvertiseStatus = BleAdvertiseStatus.STOPPED
            Log.d(auroraBleRuntimeLogTag, "BLE advertiser stopped by app-level gating")
        }

        onDispose {
            bleAdvertiser.stop()
            bleAdvertiseStatus = BleAdvertiseStatus.STOPPED
        }
    }

    DisposableEffect(bleGattServer, shouldHostRuntime) {
        if (shouldHostRuntime) {
            bleGattServer.start(
                listener = object : BleGattServer.Listener {
                    override fun onStatusChanged(status: BleGattServerStatus) {
                        bleGattServerStatus = status
                        Log.d(auroraBleRuntimeLogTag, "BLE GATT server status: $status")
                    }
                }
            )
        } else {
            bleGattServer.stop()
            bleGattServerStatus = BleGattServerStatus.STOPPED
            Log.d(auroraBleRuntimeLogTag, "BLE GATT server stopped by app-level gating")
        }

        onDispose {
            bleGattServer.stop()
            bleGattServerStatus = BleGattServerStatus.STOPPED
        }
    }

    return AuroraBleRuntimeState(
        bleAdvertiseStatus = bleAdvertiseStatus,
        bleGattServerStatus = bleGattServerStatus,
        bleTransportSender = bleTransportSender
    )
}

internal fun createAuroraBleTransportSender(
    transportFrameWriter: BleGattTransportFrameWriter?
): BleTransportSender {
    return if (transportFrameWriter == null) {
        NoOpBleTransportSender()
    } else {
        AndroidBleTransportSender(transportFrameWriter)
    }
}
