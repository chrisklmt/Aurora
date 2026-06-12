package gr.hua.aurora.ui.screens

import android.bluetooth.BluetoothManager
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import gr.hua.aurora.ble.AndroidBleConnector
import gr.hua.aurora.ble.AndroidBleAdvertiser
import gr.hua.aurora.ble.AndroidBleGattServer
import gr.hua.aurora.ble.BleAdvertiseStatus
import gr.hua.aurora.ble.BleAdvertiser
import gr.hua.aurora.ble.BleAdvertiseRequest
import gr.hua.aurora.ble.AndroidBleScanner
import gr.hua.aurora.ble.BleConnectionStatus
import gr.hua.aurora.ble.BleConnector
import gr.hua.aurora.ble.BleDiscoveryPayload
import gr.hua.aurora.ble.BleDiscoveryService
import gr.hua.aurora.ble.BleDiscoveredDevice
import gr.hua.aurora.ble.BleGattServer
import gr.hua.aurora.ble.BleGattServerStatus
import gr.hua.aurora.ble.BleGattTransportFrameReadResult
import gr.hua.aurora.ble.BleGattTransportFrameReader
import gr.hua.aurora.ble.BleGattTransportPayload
import gr.hua.aurora.ble.BleGattTransportReadResult
import gr.hua.aurora.ble.BleGattTransportReader
import gr.hua.aurora.ble.BleGattTransportWriteResult
import gr.hua.aurora.ble.BleGattTransportWriter
import gr.hua.aurora.ble.BleScanStatus
import gr.hua.aurora.ble.BleScanner
import gr.hua.aurora.ble.BluetoothPermissionStatus
import gr.hua.aurora.ble.BluetoothPermissionStatusReader
import gr.hua.aurora.model.NearbyDevicePreview
import gr.hua.aurora.model.TransportType
import gr.hua.aurora.ui.components.AuroraTopBarAction

private val temporaryNearbyAdvertisePlaceholderPayload =
    BleDiscoveryPayload.current().toByteArray()

private enum class NearbyBleTransportReadStatus {
    IDLE,
    READING,
    MARKER_SEEN,
    NOT_AVAILABLE
}

private enum class NearbyBleTransportFrameReadStatus {
    IDLE,
    READING,
    FRAME_AVAILABLE,
    NOT_AVAILABLE
}

private enum class NearbyBleTransportWriteStatus {
    IDLE,
    WRITING,
    ACCEPTED,
    NOT_AVAILABLE
}

private data class NearbyBleSessionState(
    val bluetoothStatus: BluetoothPermissionStatus,
    val bleAdvertiseStatus: BleAdvertiseStatus,
    val bleConnectionStatus: BleConnectionStatus,
    val bleGattServerStatus: BleGattServerStatus,
    val bleTransportReadStatus: NearbyBleTransportReadStatus,
    val bleTransportFrameReadStatus: NearbyBleTransportFrameReadStatus,
    val bleTransportWriteStatus: NearbyBleTransportWriteStatus,
    val bleScanStatus: BleScanStatus,
    val activeConnectionDeviceAddress: String?,
    val discoveredBleDevices: List<BleDiscoveredDevice>,
    val connectToDevice: (String) -> Unit,
    val disconnectDevice: () -> Unit,
    val readTransportMarker: () -> Unit,
    val readTransportFrame: () -> Unit,
    val writeTransportMarker: () -> Unit,
    val requestMissingPermissions: () -> Unit,
    val openBluetoothSettings: () -> Unit
)

@Composable
fun NearbyDevicesScreen(
    nearbyDevices: List<NearbyDevicePreview>,
    currentUsername: String,
    onResetLocalData: () -> Unit,
    onBack: () -> Unit
) {
    val bleSessionState = rememberNearbyBleSessionState()

    PlaceholderScreenScaffold(
        title = "Nearby Devices",
        subtitle = "Discovery placeholder",
        username = currentUsername,
        onUsernameTripleTap = onResetLocalData,
        rightAction = AuroraTopBarAction.BACK,
        onRightActionClick = onBack
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ReadinessStatusCard(
                bluetoothStatus = bleSessionState.bluetoothStatus,
                onGrantBluetoothAccess = bleSessionState.requestMissingPermissions,
                onOpenBluetoothSettings = bleSessionState.openBluetoothSettings
            )
            BleAdvertisingStatusCard(
                advertiseStatus = bleSessionState.bleAdvertiseStatus
            )
            BleGattServerStatusCard(
                gattServerStatus = bleSessionState.bleGattServerStatus
            )
            DiscoveredBleDevicesCard(
                connectionStatus = bleSessionState.bleConnectionStatus,
                transportReadStatus = bleSessionState.bleTransportReadStatus,
                transportFrameReadStatus = bleSessionState.bleTransportFrameReadStatus,
                transportWriteStatus = bleSessionState.bleTransportWriteStatus,
                scanStatus = bleSessionState.bleScanStatus,
                activeConnectionDeviceAddress = bleSessionState.activeConnectionDeviceAddress,
                devices = bleSessionState.discoveredBleDevices,
                onConnect = bleSessionState.connectToDevice,
                onDisconnect = bleSessionState.disconnectDevice,
                onReadTransportMarker = bleSessionState.readTransportMarker,
                onReadTransportFrame = bleSessionState.readTransportFrame,
                onWriteTransportMarker = bleSessionState.writeTransportMarker
            )
            Text(
                text = "The sample nearby rows below still come from local preview state. They are separate from the live BLE scanner results above.",
                style = MaterialTheme.typography.bodyLarge
            )
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(nearbyDevices) { device ->
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = device.displayName,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = device.detail,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Transport: ${device.transportType.toUiLabel()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!device.signalLabel.isNullOrBlank()) {
                                Text(
                                    text = "Signal: ${device.signalLabel}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = if (device.isConnectable) {
                                    "Status: Connectable"
                                } else {
                                    "Status: Preview only"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberNearbyBleSessionState(): NearbyBleSessionState {
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
    val bleConnector = remember(context, bluetoothAdapter) {
        AndroidBleConnector(context, bluetoothAdapter)
    }
    val bleTransportReader: BleGattTransportReader = bleConnector
    val bleTransportFrameReader: BleGattTransportFrameReader = bleConnector
    val bleTransportWriter: BleGattTransportWriter = bleConnector
    val bleGattServer = remember(context, bluetoothManager) {
        AndroidBleGattServer(context, bluetoothManager)
    }
    val bleScanner = remember(bluetoothAdapter) {
        AndroidBleScanner(bluetoothAdapter)
    }
    val temporaryNearbyAdvertisePlaceholderRequest = remember {
        BleAdvertiseRequest(
            serviceUuid = BleDiscoveryService.serviceUuid,
            payload = temporaryNearbyAdvertisePlaceholderPayload
        )
    }
    var bluetoothStatus by remember(context) {
        mutableStateOf(BluetoothPermissionStatusReader.read(context))
    }
    var isScreenVisible by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var bleAdvertiseStatus by remember {
        mutableStateOf(BleAdvertiseStatus.IDLE)
    }
    var bleConnectionStatus by remember {
        mutableStateOf(BleConnectionStatus.IDLE)
    }
    var bleGattServerStatus by remember {
        mutableStateOf(BleGattServerStatus.IDLE)
    }
    var bleTransportReadStatus by remember {
        mutableStateOf(NearbyBleTransportReadStatus.IDLE)
    }
    var bleTransportFrameReadStatus by remember {
        mutableStateOf(NearbyBleTransportFrameReadStatus.IDLE)
    }
    var bleTransportWriteStatus by remember {
        mutableStateOf(NearbyBleTransportWriteStatus.IDLE)
    }
    var bleScanStatus by remember {
        mutableStateOf(BleScanStatus.IDLE)
    }
    var activeConnectionDeviceAddress by remember {
        mutableStateOf<String?>(null)
    }
    var discoveredBleDevices by remember {
        mutableStateOf(emptyList<BleDiscoveredDevice>())
    }

    val refreshBluetoothStatus: () -> Unit = {
        bluetoothStatus = BluetoothPermissionStatusReader.read(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshBluetoothStatus()
    }

    val requestMissingPermissions: () -> Unit = {
        val currentStatus = BluetoothPermissionStatusReader.read(context)
        bluetoothStatus = currentStatus
        if (currentStatus.missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(
                currentStatus.missingPermissions.toTypedArray()
            )
        }
    }

    val openBluetoothSettings: () -> Unit = {
        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    DisposableEffect(lifecycleOwner, bleAdvertiser, bleConnector, bleGattServer, bleScanner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isScreenVisible = true
                refreshBluetoothStatus()
            } else if (event == Lifecycle.Event.ON_STOP) {
                isScreenVisible = false
                bleAdvertiser.stop()
                bleAdvertiseStatus = BleAdvertiseStatus.STOPPED
                bleConnector.disconnect()
                bleConnectionStatus = BleConnectionStatus.DISCONNECTED
                activeConnectionDeviceAddress = null
                bleTransportReadStatus = NearbyBleTransportReadStatus.IDLE
                bleTransportFrameReadStatus = NearbyBleTransportFrameReadStatus.IDLE
                bleTransportWriteStatus = NearbyBleTransportWriteStatus.IDLE
                bleGattServer.stop()
                bleGattServerStatus = BleGattServerStatus.STOPPED
                bleScanner.stop()
                bleScanStatus = BleScanStatus.STOPPED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val shouldScan = bluetoothStatus.allRequiredGranted &&
        bluetoothStatus.isBluetoothEnabled == true &&
        isScreenVisible

    val shouldAdvertise = bluetoothStatus.allRequiredGranted &&
        bluetoothStatus.isBluetoothEnabled == true &&
        isScreenVisible
    val shouldHostGattServer = bluetoothStatus.allRequiredGranted &&
        bluetoothStatus.isBluetoothEnabled == true &&
        isScreenVisible

    DisposableEffect(bleAdvertiser, shouldAdvertise) {
        if (shouldAdvertise) {
            bleAdvertiseStatus = BleAdvertiseStatus.IDLE
            bleAdvertiser.start(
                request = temporaryNearbyAdvertisePlaceholderRequest,
                listener = object : BleAdvertiser.Listener {
                    override fun onStatusChanged(status: BleAdvertiseStatus) {
                        bleAdvertiseStatus = status
                    }
                }
            )
        } else {
            bleAdvertiser.stop()
            bleAdvertiseStatus = BleAdvertiseStatus.STOPPED
        }

        onDispose {
            bleAdvertiser.stop()
            bleAdvertiseStatus = BleAdvertiseStatus.STOPPED
        }
    }

    DisposableEffect(bleGattServer, shouldHostGattServer) {
        if (shouldHostGattServer) {
            bleGattServer.start(
                listener = object : BleGattServer.Listener {
                    override fun onStatusChanged(status: BleGattServerStatus) {
                        bleGattServerStatus = status
                    }
                }
            )
        } else {
            bleGattServer.stop()
            bleGattServerStatus = BleGattServerStatus.STOPPED
        }

        onDispose {
            bleGattServer.stop()
            bleGattServerStatus = BleGattServerStatus.STOPPED
        }
    }

    DisposableEffect(bleScanner, shouldScan) {
        if (shouldScan) {
            bleScanStatus = BleScanStatus.IDLE
            discoveredBleDevices = emptyList()
            bleScanner.start(
                listener = object : BleScanner.Listener {
                    override fun onStatusChanged(status: BleScanStatus) {
                        bleScanStatus = status
                    }

                    override fun onDeviceDiscovered(device: BleDiscoveredDevice) {
                        if (device.address.isBlank()) {
                            return
                        }

                        discoveredBleDevices = discoveredBleDevices.upsertBleDevice(device)
                    }
                }
            )
        } else {
            bleScanner.stop()
            bleScanStatus = BleScanStatus.STOPPED
            discoveredBleDevices = emptyList()
        }

        onDispose {
            bleScanner.stop()
            bleScanStatus = BleScanStatus.STOPPED
        }
    }

    DisposableEffect(bleConnector) {
        onDispose {
            bleConnector.disconnect()
            bleConnectionStatus = BleConnectionStatus.DISCONNECTED
            activeConnectionDeviceAddress = null
            bleTransportReadStatus = NearbyBleTransportReadStatus.IDLE
            bleTransportFrameReadStatus = NearbyBleTransportFrameReadStatus.IDLE
            bleTransportWriteStatus = NearbyBleTransportWriteStatus.IDLE
        }
    }

    val connectToDevice: (String) -> Unit = { deviceAddress ->
        activeConnectionDeviceAddress = deviceAddress
        bleTransportReadStatus = NearbyBleTransportReadStatus.IDLE
        bleTransportFrameReadStatus = NearbyBleTransportFrameReadStatus.IDLE
        bleTransportWriteStatus = NearbyBleTransportWriteStatus.IDLE
        bleConnector.connect(
            deviceAddress = deviceAddress,
            listener = object : BleConnector.Listener {
                override fun onStatusChanged(status: BleConnectionStatus) {
                    bleConnectionStatus = status
                    if (status == BleConnectionStatus.DISCONNECTED) {
                        activeConnectionDeviceAddress = null
                        bleTransportReadStatus = NearbyBleTransportReadStatus.IDLE
                        bleTransportFrameReadStatus = NearbyBleTransportFrameReadStatus.IDLE
                        bleTransportWriteStatus = NearbyBleTransportWriteStatus.IDLE
                    }
                }
            }
        )
    }

    val disconnectDevice: () -> Unit = {
        bleConnector.disconnect()
        activeConnectionDeviceAddress = null
        bleTransportReadStatus = NearbyBleTransportReadStatus.IDLE
        bleTransportFrameReadStatus = NearbyBleTransportFrameReadStatus.IDLE
        bleTransportWriteStatus = NearbyBleTransportWriteStatus.IDLE
    }

    val readTransportMarker: () -> Unit = {
        bleTransportReadStatus = NearbyBleTransportReadStatus.READING
        bleTransportReader.read(
            listener = object : BleGattTransportReader.Listener {
                override fun onReadResult(result: BleGattTransportReadResult) {
                    bleTransportReadStatus = when (result) {
                        BleGattTransportReadResult.MarkerSeen ->
                            NearbyBleTransportReadStatus.MARKER_SEEN
                        BleGattTransportReadResult.NotAvailable ->
                            NearbyBleTransportReadStatus.NOT_AVAILABLE
                    }
                }
            }
        )
    }

    val readTransportFrame: () -> Unit = {
        bleTransportFrameReadStatus = NearbyBleTransportFrameReadStatus.READING
        bleTransportFrameReader.read(
            listener = object : BleGattTransportFrameReader.Listener {
                override fun onReadResult(result: BleGattTransportFrameReadResult) {
                    bleTransportFrameReadStatus = when (result) {
                        is BleGattTransportFrameReadResult.FrameAvailable ->
                            NearbyBleTransportFrameReadStatus.FRAME_AVAILABLE
                        BleGattTransportFrameReadResult.NotAvailable ->
                            NearbyBleTransportFrameReadStatus.NOT_AVAILABLE
                    }
                }
            }
        )
    }

    val writeTransportMarker: () -> Unit = {
        bleTransportWriteStatus = NearbyBleTransportWriteStatus.WRITING
        bleTransportWriter.write(
            payload = BleGattTransportPayload.current(),
            listener = object : BleGattTransportWriter.Listener {
                override fun onWriteResult(result: BleGattTransportWriteResult) {
                    bleTransportWriteStatus = when (result) {
                        BleGattTransportWriteResult.Accepted ->
                            NearbyBleTransportWriteStatus.ACCEPTED
                        BleGattTransportWriteResult.NotAvailable ->
                            NearbyBleTransportWriteStatus.NOT_AVAILABLE
                    }
                }
            }
        )
    }

    return NearbyBleSessionState(
        bluetoothStatus = bluetoothStatus,
        bleAdvertiseStatus = bleAdvertiseStatus,
        bleConnectionStatus = bleConnectionStatus,
        bleGattServerStatus = bleGattServerStatus,
        bleTransportReadStatus = bleTransportReadStatus,
        bleTransportFrameReadStatus = bleTransportFrameReadStatus,
        bleTransportWriteStatus = bleTransportWriteStatus,
        bleScanStatus = bleScanStatus,
        activeConnectionDeviceAddress = activeConnectionDeviceAddress,
        discoveredBleDevices = discoveredBleDevices,
        connectToDevice = connectToDevice,
        disconnectDevice = disconnectDevice,
        readTransportMarker = readTransportMarker,
        readTransportFrame = readTransportFrame,
        writeTransportMarker = writeTransportMarker,
        requestMissingPermissions = requestMissingPermissions,
        openBluetoothSettings = openBluetoothSettings
    )
}

@Composable
private fun BleAdvertisingStatusCard(advertiseStatus: BleAdvertiseStatus) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "BLE advertising",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = when (advertiseStatus) {
                    BleAdvertiseStatus.ADVERTISING -> "Advertising: Active"
                    BleAdvertiseStatus.IDLE -> "Advertising: Idle"
                    BleAdvertiseStatus.STOPPED -> "Advertising: Stopped"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BleGattServerStatusCard(gattServerStatus: BleGattServerStatus) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "BLE GATT server",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "BLE GATT server: ${gattServerStatus.toUiLabel()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DiscoveredBleDevicesCard(
    connectionStatus: BleConnectionStatus,
    transportReadStatus: NearbyBleTransportReadStatus,
    transportFrameReadStatus: NearbyBleTransportFrameReadStatus,
    transportWriteStatus: NearbyBleTransportWriteStatus,
    scanStatus: BleScanStatus,
    activeConnectionDeviceAddress: String?,
    devices: List<BleDiscoveredDevice>,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onReadTransportMarker: () -> Unit,
    onReadTransportFrame: () -> Unit,
    onWriteTransportMarker: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Discovered BLE devices",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "BLE connection: ${connectionStatus.toUiLabel()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "BLE transport read: ${transportReadStatus.toUiLabel()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "BLE transport frame read: ${transportFrameReadStatus.toUiLabel()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "BLE transport write: ${transportWriteStatus.toUiLabel()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            when {
                devices.isNotEmpty() -> {
                    devices.forEach { device ->
                        BleDiscoveredDeviceRow(
                            device = device,
                            connectionStatus = connectionStatus,
                            transportReadStatus = transportReadStatus,
                            transportFrameReadStatus = transportFrameReadStatus,
                            transportWriteStatus = transportWriteStatus,
                            activeConnectionDeviceAddress = activeConnectionDeviceAddress,
                            onConnect = onConnect,
                            onDisconnect = onDisconnect,
                            onReadTransportMarker = onReadTransportMarker,
                            onReadTransportFrame = onReadTransportFrame,
                            onWriteTransportMarker = onWriteTransportMarker
                        )
                    }
                }

                scanStatus == BleScanStatus.SCANNING -> {
                    Text(
                        text = "Scanning is active. Waiting for nearby BLE devices to appear.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> {
                    Text(
                        text = "Live BLE results will appear here when Bluetooth access is ready and this screen is active.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun BleDiscoveredDeviceRow(
    device: BleDiscoveredDevice,
    connectionStatus: BleConnectionStatus,
    transportReadStatus: NearbyBleTransportReadStatus,
    transportFrameReadStatus: NearbyBleTransportFrameReadStatus,
    transportWriteStatus: NearbyBleTransportWriteStatus,
    activeConnectionDeviceAddress: String?,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onReadTransportMarker: () -> Unit,
    onReadTransportFrame: () -> Unit,
    onWriteTransportMarker: () -> Unit
) {
    val isActiveDevice = activeConnectionDeviceAddress == device.address
    val hasActiveConnection = connectionStatus == BleConnectionStatus.CONNECTING ||
        connectionStatus == BleConnectionStatus.CONNECTED
    val showDisconnect = isActiveDevice && hasActiveConnection
    val showReadTransportMarker =
        isActiveDevice && connectionStatus == BleConnectionStatus.CONNECTED
    val showReadTransportFrame =
        isActiveDevice && connectionStatus == BleConnectionStatus.CONNECTED
    val showWriteTransportMarker =
        isActiveDevice && connectionStatus == BleConnectionStatus.CONNECTED
    val isTransportActionActive =
        transportReadStatus == NearbyBleTransportReadStatus.READING ||
            transportFrameReadStatus == NearbyBleTransportFrameReadStatus.READING ||
            transportWriteStatus == NearbyBleTransportWriteStatus.WRITING
    val isReadTransportMarkerEnabled = !isTransportActionActive
    val isReadTransportFrameEnabled = !isTransportActionActive
    val isWriteTransportMarkerEnabled = !isTransportActionActive
    val showConnect = device.hasAuroraDiscoveryPayload &&
        !showDisconnect &&
        (!hasActiveConnection || isActiveDevice)

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = device.name ?: device.address,
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = device.address,
            style = MaterialTheme.typography.bodyMedium
        )
        if (device.rssi != null) {
            Text(
                text = "Signal: ${device.rssi} dBm",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = if (device.isConnectable == true) {
                "Status: Connectable"
            } else {
                "Status: Seen only"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = if (device.hasAuroraDiscoveryPayload) {
                "Aurora discovery marker: Seen"
            } else {
                "Aurora discovery marker: Not seen"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (showConnect) {
            Button(
                onClick = { onConnect(device.address) }
            ) {
                Text("Connect")
            }
        }
        if (showDisconnect) {
            Button(
                onClick = onDisconnect
            ) {
                Text("Disconnect")
            }
        }
        if (showReadTransportMarker) {
            Button(
                enabled = isReadTransportMarkerEnabled,
                onClick = onReadTransportMarker
            ) {
                Text("Read transport marker")
            }
        }
        if (showReadTransportFrame) {
            Button(
                enabled = isReadTransportFrameEnabled,
                onClick = onReadTransportFrame
            ) {
                Text("Read transport frame")
            }
        }
        if (showWriteTransportMarker) {
            Button(
                enabled = isWriteTransportMarkerEnabled,
                onClick = onWriteTransportMarker
            ) {
                Text("Write transport marker")
            }
        }
    }
}

@Composable
private fun ReadinessStatusCard(
    bluetoothStatus: BluetoothPermissionStatus,
    onGrantBluetoothAccess: () -> Unit,
    onOpenBluetoothSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (bluetoothStatus.allRequiredGranted) {
                    "Permissions: Ready"
                } else {
                    "Permissions: Missing"
                },
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = when (bluetoothStatus.isBluetoothEnabled) {
                    true -> "Bluetooth: Enabled"
                    false -> "Bluetooth: Disabled"
                    null -> "Bluetooth: Status unknown"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!bluetoothStatus.allRequiredGranted) {
                Button(
                    onClick = onGrantBluetoothAccess
                ) {
                    Text("Grant Bluetooth access")
                }
            } else if (bluetoothStatus.isBluetoothEnabled == false) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Bluetooth is off right now. Discovery will stay inactive until Bluetooth is enabled.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = onOpenBluetoothSettings
                    ) {
                        Text("Open Bluetooth settings")
                    }
                }
            }
        }
    }
}

private fun List<BleDiscoveredDevice>.upsertBleDevice(
    device: BleDiscoveredDevice
): List<BleDiscoveredDevice> {
    val address = device.address.takeIf { it.isNotBlank() } ?: return this

    return (filterNot { existingDevice -> existingDevice.address == address } + device)
        .sortedWith(
            compareByDescending<BleDiscoveredDevice> { it.rssi != null }
                .thenByDescending { it.rssi }
        )
}

private fun TransportType.toUiLabel(): String {
    return when (this) {
        TransportType.LOCAL -> "Local"
        TransportType.BLE -> "BLE"
        TransportType.WIFI_DIRECT -> "Wi-Fi Direct"
        TransportType.UNKNOWN -> "Unknown"
    }
}

private fun BleConnectionStatus.toUiLabel(): String {
    return when (this) {
        BleConnectionStatus.IDLE -> "Idle"
        BleConnectionStatus.CONNECTING -> "Connecting"
        BleConnectionStatus.CONNECTED -> "Connected"
        BleConnectionStatus.DISCONNECTED -> "Disconnected"
    }
}

private fun BleGattServerStatus.toUiLabel(): String {
    return when (this) {
        BleGattServerStatus.IDLE -> "Idle"
        BleGattServerStatus.HOSTING -> "Hosting"
        BleGattServerStatus.STOPPED -> "Stopped"
    }
}

private fun NearbyBleTransportReadStatus.toUiLabel(): String {
    return when (this) {
        NearbyBleTransportReadStatus.IDLE -> "Idle"
        NearbyBleTransportReadStatus.READING -> "Reading"
        NearbyBleTransportReadStatus.MARKER_SEEN -> "Marker seen"
        NearbyBleTransportReadStatus.NOT_AVAILABLE -> "Not available"
    }
}

private fun NearbyBleTransportFrameReadStatus.toUiLabel(): String {
    return when (this) {
        NearbyBleTransportFrameReadStatus.IDLE -> "Idle"
        NearbyBleTransportFrameReadStatus.READING -> "Reading"
        NearbyBleTransportFrameReadStatus.FRAME_AVAILABLE -> "Frame available"
        NearbyBleTransportFrameReadStatus.NOT_AVAILABLE -> "Not available"
    }
}

private fun NearbyBleTransportWriteStatus.toUiLabel(): String {
    return when (this) {
        NearbyBleTransportWriteStatus.IDLE -> "Idle"
        NearbyBleTransportWriteStatus.WRITING -> "Writing"
        NearbyBleTransportWriteStatus.ACCEPTED -> "Accepted"
        NearbyBleTransportWriteStatus.NOT_AVAILABLE -> "Not available"
    }
}
