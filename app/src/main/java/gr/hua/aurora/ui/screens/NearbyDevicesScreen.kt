package gr.hua.aurora.ui.screens

import android.bluetooth.BluetoothManager
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import gr.hua.aurora.ble.connection.AndroidBleConnector
import gr.hua.aurora.ble.advertise.BleAdvertiseStatus
import gr.hua.aurora.ble.discovery.AndroidBleScanner
import gr.hua.aurora.ble.connection.BleConnectionStatus
import gr.hua.aurora.ble.connection.BleConnector
import gr.hua.aurora.ble.discovery.BleDiscoveredDevice
import gr.hua.aurora.ble.discovery.BleScanAggregator
import gr.hua.aurora.ble.gatt.BleGattServerStatus
import gr.hua.aurora.ble.discovery.BleScanDiagnostics
import gr.hua.aurora.ble.transport.BleGattTransportFrameReadResult
import gr.hua.aurora.ble.transport.BleGattTransportFrameReader
import gr.hua.aurora.ble.transport.BleGattTransportPayload
import gr.hua.aurora.ble.transport.BleGattTransportReadResult
import gr.hua.aurora.ble.transport.BleGattTransportReader
import gr.hua.aurora.ble.transport.BleGattTransportWriteResult
import gr.hua.aurora.ble.transport.BleGattTransportWriter
import gr.hua.aurora.ble.discovery.BleScanStatus
import gr.hua.aurora.ble.discovery.BleScanner
import gr.hua.aurora.ble.permissions.BluetoothPermissionStatus
import gr.hua.aurora.ble.permissions.BluetoothPermissionStatusReader
import gr.hua.aurora.model.NearbyDevicePreview
import gr.hua.aurora.model.TransportType
import gr.hua.aurora.state.AuroraAvailabilityPreference
import gr.hua.aurora.ui.components.AuroraAvailabilityIndicator
import gr.hua.aurora.ui.components.AuroraTopBarAction
import gr.hua.aurora.ui.components.buildAuroraAvailabilityUiState
import kotlinx.coroutines.delay

private const val nearbyDevicesLogTag = "NearbyDevicesScreen"

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
    val bleConnectionStatus: BleConnectionStatus,
    val bleTransportReadStatus: NearbyBleTransportReadStatus,
    val bleTransportFrameReadStatus: NearbyBleTransportFrameReadStatus,
    val bleTransportWriteStatus: NearbyBleTransportWriteStatus,
    val bleScanStatus: BleScanStatus,
    val bleScanDiagnostics: BleScanDiagnostics,
    val activeConnectionDeviceAddress: String?,
    val discoveredBleDevices: List<BleDiscoveredDevice>,
    val connectToDevice: (String) -> Unit,
    val disconnectDevice: () -> Unit,
    val readTransportMarker: () -> Unit,
    val readTransportFrame: () -> Unit,
    val writeTransportMarker: () -> Unit,
    val requestMissingPermissions: () -> Unit,
    val openBluetoothSettings: () -> Unit,
    val openLocationSettings: () -> Unit
)

@Composable
fun NearbyDevicesScreen(
    nearbyDevices: List<NearbyDevicePreview>,
    currentUsername: String,
    desiredAvailability: AuroraAvailabilityPreference,
    bleAdvertiseStatus: BleAdvertiseStatus,
    bleGattServerStatus: BleGattServerStatus,
    onResetLocalData: () -> Unit,
    onBack: () -> Unit
) {
    val bleSessionState = rememberNearbyBleSessionState(
        desiredAvailability = desiredAvailability
    )
    val availabilityState = remember(desiredAvailability, bleSessionState.bluetoothStatus) {
        buildAuroraAvailabilityUiState(
            desiredAvailability = desiredAvailability,
            bluetoothStatus = bleSessionState.bluetoothStatus
        )
    }
    var showPreviewRows by remember {
        mutableStateOf(false)
    }

    PlaceholderScreenScaffold(
        title = "Nearby Devices",
        subtitle = null,
        subtitleContent = {
            AuroraAvailabilityIndicator(uiState = availabilityState)
        },
        username = currentUsername,
        onUsernameTripleTap = onResetLocalData,
        rightAction = AuroraTopBarAction.BACK,
        onRightActionClick = onBack
    ) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ReadinessStatusCard(
                    bluetoothStatus = bleSessionState.bluetoothStatus,
                    onGrantBluetoothAccess = bleSessionState.requestMissingPermissions,
                    onOpenBluetoothSettings = bleSessionState.openBluetoothSettings,
                    onOpenLocationSettings = bleSessionState.openLocationSettings
                )
            }
            item {
                DiscoveredBleDevicesCard(
                    connectionStatus = bleSessionState.bleConnectionStatus,
                    transportReadStatus = bleSessionState.bleTransportReadStatus,
                    transportFrameReadStatus = bleSessionState.bleTransportFrameReadStatus,
                    transportWriteStatus = bleSessionState.bleTransportWriteStatus,
                    scanStatus = bleSessionState.bleScanStatus,
                    scanDiagnostics = bleSessionState.bleScanDiagnostics,
                    isDiscoveryPausedByAvailability =
                        desiredAvailability == AuroraAvailabilityPreference.OFFLINE,
                    activeConnectionDeviceAddress = bleSessionState.activeConnectionDeviceAddress,
                    devices = bleSessionState.discoveredBleDevices,
                    onConnect = bleSessionState.connectToDevice,
                    onDisconnect = bleSessionState.disconnectDevice,
                    onReadTransportMarker = bleSessionState.readTransportMarker,
                    onReadTransportFrame = bleSessionState.readTransportFrame,
                    onWriteTransportMarker = bleSessionState.writeTransportMarker
                )
            }
            item {
                BleRuntimeStatusCard(
                    advertiseStatus = bleAdvertiseStatus,
                    gattServerStatus = bleGattServerStatus
                )
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Sample nearby rows",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "These placeholder rows are separate from the live BLE scanner results above.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = { showPreviewRows = !showPreviewRows }
                        ) {
                            Text(
                                if (showPreviewRows) {
                                    "Hide sample rows"
                                } else {
                                    "Show sample rows"
                                }
                            )
                        }
                        if (showPreviewRows) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                nearbyDevices.forEach { device ->
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
            }
        }
    }
}

@Composable
private fun rememberNearbyBleSessionState(
    desiredAvailability: AuroraAvailabilityPreference
): NearbyBleSessionState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val bluetoothManager = remember(context) {
        context.getSystemService(BluetoothManager::class.java)
    }
    val bluetoothAdapter = remember(bluetoothManager) {
        bluetoothManager?.adapter
    }
    val bleConnector = remember(context, bluetoothAdapter) {
        AndroidBleConnector(context, bluetoothAdapter)
    }
    val bleTransportReader: BleGattTransportReader = bleConnector
    val bleTransportFrameReader: BleGattTransportFrameReader = bleConnector
    val bleTransportWriter: BleGattTransportWriter = bleConnector
    val bleScanner = remember(bluetoothAdapter) {
        AndroidBleScanner(bluetoothAdapter)
    }
    val discoveredBleDevicesAggregator = remember {
        BleScanAggregator()
    }
    var bluetoothStatus by remember(context) {
        mutableStateOf(BluetoothPermissionStatusReader.read(context))
    }
    var isScreenVisible by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var bleConnectionStatus by remember {
        mutableStateOf(BleConnectionStatus.IDLE)
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
    var bleScanDiagnostics by remember {
        mutableStateOf(BleScanDiagnostics())
    }
    var activeConnectionDeviceAddress by remember {
        mutableStateOf<String?>(null)
    }
    var discoveredBleDevices by remember {
        mutableStateOf(emptyList<BleDiscoveredDevice>())
    }
    val availabilityUiState = buildAuroraAvailabilityUiState(
        desiredAvailability = desiredAvailability,
        bluetoothStatus = bluetoothStatus
    )
    val isAvailabilityOnline = availabilityUiState.isOnline

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

    val openLocationSettings: () -> Unit = {
        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }

    DisposableEffect(lifecycleOwner, bleConnector, bleScanner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isScreenVisible = true
                refreshBluetoothStatus()
            } else if (event == Lifecycle.Event.ON_STOP) {
                isScreenVisible = false
                bleConnector.disconnect()
                bleConnectionStatus = BleConnectionStatus.DISCONNECTED
                activeConnectionDeviceAddress = null
                bleTransportReadStatus = NearbyBleTransportReadStatus.IDLE
                bleTransportFrameReadStatus = NearbyBleTransportFrameReadStatus.IDLE
                bleTransportWriteStatus = NearbyBleTransportWriteStatus.IDLE
                bleScanner.stop()
                discoveredBleDevicesAggregator.clear()
                bleScanStatus = BleScanStatus.STOPPED
                bleScanDiagnostics = BleScanDiagnostics()
                discoveredBleDevices = emptyList()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val shouldScan = isAvailabilityOnline && isScreenVisible

    LaunchedEffect(
        desiredAvailability,
        bluetoothStatus,
        isScreenVisible,
        availabilityUiState,
        shouldScan
    ) {
        Log.d(
            nearbyDevicesLogTag,
            "BLE gating: desired=$desiredAvailability effectiveOnline=${availabilityUiState.isOnline} reason=${availabilityUiState.reasonText ?: "none"} screenVisible=$isScreenVisible readinessComplete=${bluetoothStatus.isReadinessComplete} bluetoothEnabled=${bluetoothStatus.isBluetoothEnabled} locationEnabled=${bluetoothStatus.isLocationEnabled} missingPermissions=${bluetoothStatus.missingPermissions.size} shouldScan=$shouldScan"
        )
    }

    DisposableEffect(bleScanner, shouldScan) {
        if (shouldScan) {
            discoveredBleDevicesAggregator.clear()
            bleScanStatus = BleScanStatus.IDLE
            bleScanDiagnostics = BleScanDiagnostics()
            discoveredBleDevices = emptyList()
            bleScanner.start(
                listener = object : BleScanner.Listener {
                    override fun onStatusChanged(status: BleScanStatus) {
                        bleScanStatus = status
                        bleScanDiagnostics = bleScanner.currentDiagnostics()
                        Log.d(
                            nearbyDevicesLogTag,
                            "BLE scanner status: $status raw=${bleScanDiagnostics.rawScanResultCount} matches=${bleScanDiagnostics.auroraDiscoveryMatchCount}"
                        )
                    }

                    override fun onDeviceDiscovered(device: BleDiscoveredDevice) {
                        bleScanDiagnostics = bleScanner.currentDiagnostics()
                        if (device.address.isBlank()) {
                            return
                        }
                        if (!device.hasAuroraDiscoveryPayload) {
                            Log.d(
                                nearbyDevicesLogTag,
                                "BLE raw device kept in diagnostics only: address=${device.address}"
                            )
                            return
                        }

                        val updatedDevices = discoveredBleDevicesAggregator.update(device)
                        discoveredBleDevices = updatedDevices
                        Log.d(
                            nearbyDevicesLogTag,
                            "BLE device surfaced: visible=${updatedDevices.size} address=${device.address} aurora=${device.hasAuroraDiscoveryPayload}"
                        )
                    }
                }
            )
        } else {
            bleScanner.stop()
            discoveredBleDevicesAggregator.clear()
            bleScanStatus = BleScanStatus.STOPPED
            bleScanDiagnostics = BleScanDiagnostics()
            discoveredBleDevices = emptyList()
            Log.d(nearbyDevicesLogTag, "BLE scanner stopped by gating")
        }

        onDispose {
            bleScanner.stop()
            discoveredBleDevicesAggregator.clear()
            bleScanStatus = BleScanStatus.STOPPED
            bleScanDiagnostics = BleScanDiagnostics()
            discoveredBleDevices = emptyList()
        }
    }

    LaunchedEffect(shouldScan) {
        if (!shouldScan) {
            return@LaunchedEffect
        }

        while (true) {
            delay(BleScanAggregator.STALE_PEER_PRUNE_INTERVAL_MS)
            discoveredBleDevices = discoveredBleDevicesAggregator.prune()
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

    DisposableEffect(bleConnector, isAvailabilityOnline) {
        if (!isAvailabilityOnline) {
            bleConnector.disconnect()
            bleConnectionStatus = BleConnectionStatus.DISCONNECTED
            activeConnectionDeviceAddress = null
            bleTransportReadStatus = NearbyBleTransportReadStatus.IDLE
            bleTransportFrameReadStatus = NearbyBleTransportFrameReadStatus.IDLE
            bleTransportWriteStatus = NearbyBleTransportWriteStatus.IDLE
        }

        onDispose {
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
        bleConnectionStatus = bleConnectionStatus,
        bleTransportReadStatus = bleTransportReadStatus,
        bleTransportFrameReadStatus = bleTransportFrameReadStatus,
        bleTransportWriteStatus = bleTransportWriteStatus,
        bleScanStatus = bleScanStatus,
        bleScanDiagnostics = bleScanDiagnostics,
        activeConnectionDeviceAddress = activeConnectionDeviceAddress,
        discoveredBleDevices = discoveredBleDevices,
        connectToDevice = connectToDevice,
        disconnectDevice = disconnectDevice,
        readTransportMarker = readTransportMarker,
        readTransportFrame = readTransportFrame,
        writeTransportMarker = writeTransportMarker,
        requestMissingPermissions = requestMissingPermissions,
        openBluetoothSettings = openBluetoothSettings,
        openLocationSettings = openLocationSettings
    )
}

@Composable
private fun BleRuntimeStatusCard(
    advertiseStatus: BleAdvertiseStatus,
    gattServerStatus: BleGattServerStatus
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "BLE status",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Advertising: ${advertiseStatus.toUiLabel()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "GATT server: ${gattServerStatus.toUiLabel()}",
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
    scanDiagnostics: BleScanDiagnostics,
    isDiscoveryPausedByAvailability: Boolean,
    activeConnectionDeviceAddress: String?,
    devices: List<BleDiscoveredDevice>,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onReadTransportMarker: () -> Unit,
    onReadTransportFrame: () -> Unit,
    onWriteTransportMarker: () -> Unit
) {
    var showDiagnostics by remember {
        mutableStateOf(false)
    }

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
                text = "Visible devices: ${devices.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            when {
                devices.isNotEmpty() -> {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
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
                }

                scanStatus == BleScanStatus.SCANNING -> {
                    Text(
                        text = if (
                            scanDiagnostics.rawScanResultCount > 0 &&
                            scanDiagnostics.auroraDiscoveryMatchCount == 0
                        ) {
                            "Scanning is active. Raw BLE devices are visible in diagnostics, but no Aurora peers match yet."
                        } else {
                            "Scanning is active. Waiting for nearby Aurora peers to appear."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> {
                    Text(
                        text = if (isDiscoveryPausedByAvailability) {
                            "BLE discovery is paused while Aurora is Offline."
                        } else {
                            "Live Aurora peers will appear here when Bluetooth and Location/GPS readiness is complete and this screen is active."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            TextButton(
                onClick = { showDiagnostics = !showDiagnostics }
            ) {
                Text(
                    if (showDiagnostics) {
                        "Hide BLE diagnostics"
                    } else {
                        "Show BLE diagnostics"
                    }
                )
            }

            if (showDiagnostics) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Scan: ${scanStatus.toUiLabel()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Connection: ${connectionStatus.toUiLabel()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Transport read: ${transportReadStatus.toUiLabel()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Transport frame read: ${transportFrameReadStatus.toUiLabel()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Transport write: ${transportWriteStatus.toUiLabel()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "BLE scan raw results: ${scanDiagnostics.rawScanResultCount}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "BLE Aurora matches: ${scanDiagnostics.auroraDiscoveryMatchCount}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Last BLE scan: ${scanDiagnostics.lastDeviceName.toBleScanText()} / " +
                            "${scanDiagnostics.lastDeviceAddress.toBleScanText()} / " +
                            "RSSI ${scanDiagnostics.lastRssi.toBleScanRssiText()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Last BLE discovery service data: " +
                            scanDiagnostics.lastHadDiscoveryServiceData.toSeenText(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Last BLE Aurora marker: " +
                            scanDiagnostics.lastHadAuroraDiscoveryPayload.toSeenText(),
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
            text = device.name ?: "Unknown BLE device",
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = "${device.address} | RSSI ${device.rssi.toBleScanRssiText()} dBm",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = buildString {
                append(
                    if (device.hasAuroraDiscoveryPayload) {
                        "Aurora marker: Seen"
                    } else {
                        "Aurora marker: Not seen"
                    }
                )
                append(" | ")
                append(
                    if (device.isConnectable == true) {
                        "Connectable"
                    } else {
                        "Seen only"
                    }
                )
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
    onOpenBluetoothSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (bluetoothStatus.isReadinessComplete) {
                    "Bluetooth + Location/GPS: Ready"
                } else {
                    "Bluetooth + Location/GPS: Incomplete"
                },
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = if (bluetoothStatus.allRequiredGranted) {
                    "Permissions: Ready"
                } else {
                    "Permissions: Missing"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
            Text(
                text = when (bluetoothStatus.isLocationEnabled) {
                    true -> "Location/GPS: Enabled"
                    false -> "Location/GPS: Disabled"
                    null -> "Location/GPS: Status unknown"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!bluetoothStatus.allRequiredGranted) {
                Text(
                    text = "Aurora discovery needs Bluetooth and Location/GPS permissions before nearby peers can appear.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onGrantBluetoothAccess
                ) {
                    Text("Grant Bluetooth and Location access")
                }
            }
            if (bluetoothStatus.isBluetoothEnabled == false) {
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
            if (bluetoothStatus.isLocationEnabled == false) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Location/GPS is off right now. Discovery will stay inactive until Location/GPS is enabled.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = onOpenLocationSettings
                    ) {
                        Text("Open Location settings")
                    }
                }
            }
        }
    }
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

private fun BleAdvertiseStatus.toUiLabel(): String {
    return when (this) {
        BleAdvertiseStatus.ADVERTISING -> "Active"
        BleAdvertiseStatus.IDLE -> "Idle"
        BleAdvertiseStatus.STOPPED -> "Stopped"
    }
}

private fun BleScanStatus.toUiLabel(): String {
    return when (this) {
        BleScanStatus.IDLE -> "Idle"
        BleScanStatus.SCANNING -> "Scanning"
        BleScanStatus.STOPPED -> "Stopped"
    }
}

private fun String?.toBleScanText(): String {
    return this?.takeIf { it.isNotBlank() } ?: "Unknown"
}

private fun Int?.toBleScanRssiText(): String {
    return this?.toString() ?: "Unknown"
}

private fun Boolean.toSeenText(): String {
    return if (this) {
        "Seen"
    } else {
        "Not seen"
    }
}
