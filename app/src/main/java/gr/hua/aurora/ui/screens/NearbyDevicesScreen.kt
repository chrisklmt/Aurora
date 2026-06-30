package gr.hua.aurora.ui.screens

import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import gr.hua.aurora.ble.connection.AndroidBleConnector
import gr.hua.aurora.ble.advertise.BleAdvertiseStatus
import gr.hua.aurora.ble.connection.BleConnectionStatus
import gr.hua.aurora.ble.discovery.BleDiscoveredDevice
import gr.hua.aurora.ble.discovery.BleStablePeerId
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
import gr.hua.aurora.ble.permissions.BluetoothPermissionStatus
import gr.hua.aurora.ble.permissions.BluetoothPermissionStatusReader
import gr.hua.aurora.ble.permissions.rememberBluetoothPermissionStatusState
import gr.hua.aurora.identity.AndroidKeystoreLocalAgreementPublicKey
import gr.hua.aurora.model.AuroraContact
import gr.hua.aurora.model.NearbyDevicePreview
import gr.hua.aurora.model.PrivateChatIdentity
import gr.hua.aurora.protocol.PeerIdentityExchangeSendResult
import gr.hua.aurora.protocol.PeerSessionRegistryDiagnostics
import gr.hua.aurora.protocol.PeerSessionPeerId
import gr.hua.aurora.protocol.hasSessionForPeer
import gr.hua.aurora.state.AuroraAvailabilityPreference
import gr.hua.aurora.ui.components.AuroraAvailabilityIndicator
import gr.hua.aurora.ui.components.AuroraTopBarAction
import gr.hua.aurora.ui.components.DebugInfoCard
import gr.hua.aurora.ui.components.DebugInfoCardModel
import gr.hua.aurora.ui.components.DebugInfoItem
import gr.hua.aurora.ui.components.DebugInfoSection
import gr.hua.aurora.ui.components.buildAuroraAvailabilityUiState
import gr.hua.aurora.wifidirect.WifiDirectPeer
import gr.hua.aurora.wifidirect.WifiDirectRuntimeStatus
import gr.hua.aurora.wifidirect.rememberWifiDirectSocketState
import kotlinx.coroutines.launch

private const val nearbyDevicesLogTag = "NearbyDevicesScreen"

internal enum class NearbyBleTransportReadStatus {
    IDLE,
    READING,
    MARKER_SEEN,
    NOT_AVAILABLE
}

internal enum class NearbyBleTransportFrameReadStatus {
    IDLE,
    READING,
    FRAME_AVAILABLE,
    NOT_AVAILABLE
}

internal enum class NearbyBleTransportWriteStatus {
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
    val connectToDevice: (BleDiscoveredDevice) -> Unit,
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
    contacts: List<AuroraContact>,
    privateChatIdentitiesByPeerId: Map<String, PrivateChatIdentity>,
    currentUsername: String,
    desiredAvailability: AuroraAvailabilityPreference,
    bleAdvertiseStatus: BleAdvertiseStatus,
    bleGattServerStatus: BleGattServerStatus,
    bleScanStatus: BleScanStatus,
    bleScanDiagnostics: BleScanDiagnostics,
    discoveredBleDevices: List<BleDiscoveredDevice>,
    showDebugDiagnostics: Boolean,
    bleConnectionStatus: BleConnectionStatus,
    bleConnector: AndroidBleConnector,
    transportSenderSourceLabel: String,
    wifiDirectRuntimeStatus: WifiDirectRuntimeStatus,
    onStartWifiDirectDiscovery: () -> Unit,
    onStopWifiDirectDiscovery: () -> Unit,
    onConnectWifiDirectPeer: (WifiDirectPeer) -> Unit,
    onDisconnectWifiDirectPeer: () -> Unit,
    onReceiveWifiDirectDebugTransportFrame:
    (gr.hua.aurora.ble.transport.BleGattTransportFrame)
    -> gr.hua.aurora.ble.transport.BleTransportReceiveResult,
    identityHandlerStatus: String,
    peerSessionDiagnostics: PeerSessionRegistryDiagnostics,
    activeTransportPeerId: String?,
    activeTransportDeviceAddress: String?,
    selectedSecurePeerId: String?,
    lastIdentityExchangeStatus: String?,
    onExchangeIdentityWithPeer: suspend (BleDiscoveredDevice, String?) -> PeerIdentityExchangeSendResult,
    onConnectTransportPeer: (String, String?) -> Unit,
    onDisconnectTransportPeer: () -> Unit,
    onAddOrUpdateContact: (String, String, Long?, Boolean) -> String?,
    onPromoteContactSession: (String, String, Long?) -> Unit,
    onRefreshContactLastSeen: (String, Long) -> Unit,
    onSelectSecurePeer: (String) -> Unit,
    onClearSelectedSecurePeer: () -> Unit,
    onOpenPrivateChat: (String) -> Unit,
    onResetLocalData: () -> Unit,
    onBack: () -> Unit
) {
    val bleSessionState = rememberNearbyBleSessionState(
        desiredAvailability = desiredAvailability,
        runtimeBleScanStatus = bleScanStatus,
        runtimeBleScanDiagnostics = bleScanDiagnostics,
        runtimeDiscoveredBleDevices = discoveredBleDevices,
        bleConnectionStatus = bleConnectionStatus,
        bleConnector = bleConnector,
        activeConnectionDeviceAddress = activeTransportDeviceAddress,
        onConnectTransportPeer = onConnectTransportPeer,
        onDisconnectTransportPeer = onDisconnectTransportPeer
    )
    val coroutineScope = rememberCoroutineScope()
    val availabilityState = remember(desiredAvailability, bleSessionState.bluetoothStatus) {
        buildAuroraAvailabilityUiState(
            desiredAvailability = desiredAvailability,
            bluetoothStatus = bleSessionState.bluetoothStatus
        )
    }
    val identityExchangeStatusTexts = remember {
        mutableStateMapOf<String, String>()
    }
    var activeIdentityExchangeDeviceKey by remember {
        mutableStateOf<String?>(null)
    }
    val wifiDirectSocketState = rememberWifiDirectSocketState(
        runtimeStatus = wifiDirectRuntimeStatus,
        processReceiveBridgeFrame = onReceiveWifiDirectDebugTransportFrame
    )
    val debugCard = buildNearbyDebugCard(
        showDebugDiagnostics = showDebugDiagnostics,
        advertiseStatus = bleAdvertiseStatus,
        gattServerStatus = bleGattServerStatus,
        scanStatus = bleScanStatus,
        wifiDirectRuntimeStatus = wifiDirectRuntimeStatus,
        wifiDirectSocketDiagnostics = wifiDirectSocketState.diagnostics,
        wifiDirectAdapterDiagnostics = wifiDirectSocketState.adapterDiagnostics,
        wifiDirectSendBridgeDiagnostics = wifiDirectSocketState.sendBridgeDiagnostics,
        wifiDirectReceiveBridgeDiagnostics = wifiDirectSocketState.receiveBridgeDiagnostics,
        identityHandlerStatus = identityHandlerStatus,
        peerSessionDiagnostics = peerSessionDiagnostics
    )
    val handleResetLocalData = remember(wifiDirectSocketState, onResetLocalData) {
        {
            wifiDirectSocketState.disableSendBridge()
            wifiDirectSocketState.disableReceiveBridge()
            onResetLocalData()
        }
    }

    LaunchedEffect(
        discoveredBleDevices,
        contacts,
        peerSessionDiagnostics.establishedPeerIds,
        peerSessionDiagnostics.canonicalPeerIdByAlias
    ) {
        discoveredBleDevices.forEach { device ->
            val contactPeerId = nearbyContactPeerId(device) ?: return@forEach
            if (contacts.any { it.canonicalPeerId == contactPeerId }) {
                onRefreshContactLastSeen(
                    contactPeerId,
                    System.currentTimeMillis()
                )
            }
            if (nearbyPeerHasReadyKeys(contactPeerId, peerSessionDiagnostics)) {
                onPromoteContactSession(
                    contactPeerId,
                    nearbyContactDisplayName(device),
                    System.currentTimeMillis()
                )
            }
        }
    }

    PlaceholderScreenScaffold(
        title = "Nearby Devices",
        subtitle = null,
        subtitleContent = {
            AuroraAvailabilityIndicator(uiState = availabilityState)
        },
        username = currentUsername,
        onUsernameTripleTap = handleResetLocalData,
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
                    showDebugDetails = showDebugDiagnostics,
                    onGrantBluetoothAccess = bleSessionState.requestMissingPermissions,
                    onOpenBluetoothSettings = bleSessionState.openBluetoothSettings,
                    onOpenLocationSettings = bleSessionState.openLocationSettings
                )
            }
            item {
                DiscoveredBleDevicesCard(
                    contacts = contacts,
                    advertiseStatus = bleAdvertiseStatus,
                    gattServerStatus = bleGattServerStatus,
                    connectionStatus = bleSessionState.bleConnectionStatus,
                    transportSenderSourceLabel = transportSenderSourceLabel,
                    activeTransportPeerId = activeTransportPeerId,
                    transportReadStatus = bleSessionState.bleTransportReadStatus,
                    transportFrameReadStatus = bleSessionState.bleTransportFrameReadStatus,
                    transportWriteStatus = bleSessionState.bleTransportWriteStatus,
                    scanStatus = bleSessionState.bleScanStatus,
                    scanDiagnostics = bleSessionState.bleScanDiagnostics,
                    showDebugDiagnostics = showDebugDiagnostics,
                    peerSessionDiagnostics = peerSessionDiagnostics,
                    isDiscoveryPausedByAvailability = !availabilityState.isOnline,
                    activeConnectionDeviceAddress = bleSessionState.activeConnectionDeviceAddress,
                    selectedSecurePeerId = selectedSecurePeerId,
                    lastIdentityExchangeStatus = lastIdentityExchangeStatus,
                    devices = bleSessionState.discoveredBleDevices,
                    identityExchangeStatusTexts = identityExchangeStatusTexts,
                    activeIdentityExchangeDeviceKey = activeIdentityExchangeDeviceKey,
                    privateChatIdentitiesByPeerId = privateChatIdentitiesByPeerId,
                    onAddOrUpdateContact = onAddOrUpdateContact,
                    onSelectSecurePeer = onSelectSecurePeer,
                    onClearSelectedSecurePeer = onClearSelectedSecurePeer,
                    onOpenPrivateChat = onOpenPrivateChat,
                    onConnect = bleSessionState.connectToDevice,
                    onDisconnect = bleSessionState.disconnectDevice,
                    onReadTransportMarker = bleSessionState.readTransportMarker,
                    onReadTransportFrame = bleSessionState.readTransportFrame,
                    onWriteTransportMarker = bleSessionState.writeTransportMarker,
                    onExchangeIdentity = { device, privateChatProposalId ->
                        val deviceKey = nearbyBleDeviceIdentityKey(device)
                        Log.d(
                            nearbyDevicesLogTag,
                            "Manual identity exchange clicked: address=${device.address} stablePeerId=${device.stablePeerId?.toNearbyPeerId() ?: "none"}"
                        )
                        activeIdentityExchangeDeviceKey = deviceKey
                        identityExchangeStatusTexts[deviceKey] = "Exchanging identity..."
                        coroutineScope.launch {
                            val result = runCatching {
                                onExchangeIdentityWithPeer(device, privateChatProposalId)
                            }
                            identityExchangeStatusTexts[deviceKey] = result.fold(
                                onSuccess = { sendResult ->
                                    Log.d(
                                        nearbyDevicesLogTag,
                                        "BLE identity exchange: device=${device.address} result=$sendResult"
                                    )
                                    nearbyIdentityExchangeStatusText(sendResult)
                                },
                                onFailure = { error ->
                                    Log.w(
                                        nearbyDevicesLogTag,
                                        "BLE identity exchange: device=${device.address} failed before submission",
                                        error
                                    )
                                    "Identity exchange failed: ${error::class.java.simpleName}"
                                }
                            )
                            activeIdentityExchangeDeviceKey = null
                        }
                    }
                )
            }
            debugCard?.let { card ->
                item {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        DebugInfoCard(card = card)
                        NearbyWifiDirectDebugControls(
                            runtimeStatus = wifiDirectRuntimeStatus,
                            socketDiagnostics = wifiDirectSocketState.diagnostics,
                            adapterDiagnostics = wifiDirectSocketState.adapterDiagnostics,
                            sendBridgeDiagnostics = wifiDirectSocketState.sendBridgeDiagnostics,
                            receiveBridgeDiagnostics = wifiDirectSocketState.receiveBridgeDiagnostics,
                            onStartDiscovery = onStartWifiDirectDiscovery,
                            onStopDiscovery = onStopWifiDirectDiscovery,
                            onConnectToPeer = onConnectWifiDirectPeer,
                            onDisconnect = onDisconnectWifiDirectPeer,
                            onStartSocketServer = wifiDirectSocketState.startServer,
                            onConnectSocketClient = wifiDirectSocketState.connectClient,
                            onSendSocketFrame = wifiDirectSocketState.sendFrame,
                            onSendAdapterFrame = wifiDirectSocketState.sendAdapterFrame,
                            onSendBridgedFrame = wifiDirectSocketState.sendBridgedFrame,
                            onSetSendBridgeEnabled = wifiDirectSocketState.setSendBridgeEnabled,
                            onSetReceiveBridgeEnabled = wifiDirectSocketState.setReceiveBridgeEnabled,
                            onCloseSocket = wifiDirectSocketState.closeSocket
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberNearbyBleSessionState(
    desiredAvailability: AuroraAvailabilityPreference,
    runtimeBleScanStatus: BleScanStatus,
    runtimeBleScanDiagnostics: BleScanDiagnostics,
    runtimeDiscoveredBleDevices: List<BleDiscoveredDevice>,
    bleConnectionStatus: BleConnectionStatus,
    bleConnector: AndroidBleConnector,
    activeConnectionDeviceAddress: String?,
    onConnectTransportPeer: (String, String?) -> Unit,
    onDisconnectTransportPeer: () -> Unit
): NearbyBleSessionState {
    val context = LocalContext.current
    val bleTransportReader: BleGattTransportReader = bleConnector
    val bleTransportFrameReader: BleGattTransportFrameReader = bleConnector
    val bleTransportWriter: BleGattTransportWriter = bleConnector
    val bluetoothStatusState = rememberBluetoothPermissionStatusState()
    val bluetoothStatus = bluetoothStatusState.status
    var bleTransportReadStatus by remember {
        mutableStateOf(NearbyBleTransportReadStatus.IDLE)
    }
    var bleTransportFrameReadStatus by remember {
        mutableStateOf(NearbyBleTransportFrameReadStatus.IDLE)
    }
    var bleTransportWriteStatus by remember {
        mutableStateOf(NearbyBleTransportWriteStatus.IDLE)
    }
    val availabilityUiState = buildAuroraAvailabilityUiState(
        desiredAvailability = desiredAvailability,
        bluetoothStatus = bluetoothStatus
    )
    val isAvailabilityOnline = availabilityUiState.isOnline

    val refreshBluetoothStatus = bluetoothStatusState.refresh

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshBluetoothStatus()
    }

    val requestMissingPermissions: () -> Unit = {
        val currentStatus = BluetoothPermissionStatusReader.read(context)
        if (currentStatus.missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(
                currentStatus.missingPermissions.toTypedArray()
            )
        } else {
            refreshBluetoothStatus()
        }
    }

    val openBluetoothSettings: () -> Unit = {
        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    val openLocationSettings: () -> Unit = {
        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }

    DisposableEffect(isAvailabilityOnline) {
        if (!isAvailabilityOnline) {
            bleTransportReadStatus = NearbyBleTransportReadStatus.IDLE
            bleTransportFrameReadStatus = NearbyBleTransportFrameReadStatus.IDLE
            bleTransportWriteStatus = NearbyBleTransportWriteStatus.IDLE
        }

        onDispose {
        }
    }

    val connectToDevice: (BleDiscoveredDevice) -> Unit = { device ->
        bleTransportReadStatus = NearbyBleTransportReadStatus.IDLE
        bleTransportFrameReadStatus = NearbyBleTransportFrameReadStatus.IDLE
        bleTransportWriteStatus = NearbyBleTransportWriteStatus.IDLE
        onConnectTransportPeer(
            device.address,
            device.stablePeerId?.toNearbyPeerId()
        )
    }

    val disconnectDevice: () -> Unit = {
        onDisconnectTransportPeer()
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
        bleScanStatus = runtimeBleScanStatus,
        bleScanDiagnostics = runtimeBleScanDiagnostics,
        activeConnectionDeviceAddress = activeConnectionDeviceAddress,
        discoveredBleDevices = runtimeDiscoveredBleDevices,
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
private fun DiscoveredBleDevicesCard(
    contacts: List<AuroraContact>,
    advertiseStatus: BleAdvertiseStatus,
    gattServerStatus: BleGattServerStatus,
    connectionStatus: BleConnectionStatus,
    transportSenderSourceLabel: String,
    activeTransportPeerId: String?,
    transportReadStatus: NearbyBleTransportReadStatus,
    transportFrameReadStatus: NearbyBleTransportFrameReadStatus,
    transportWriteStatus: NearbyBleTransportWriteStatus,
    scanStatus: BleScanStatus,
    scanDiagnostics: BleScanDiagnostics,
    showDebugDiagnostics: Boolean,
    peerSessionDiagnostics: PeerSessionRegistryDiagnostics,
    isDiscoveryPausedByAvailability: Boolean,
    activeConnectionDeviceAddress: String?,
    selectedSecurePeerId: String?,
    lastIdentityExchangeStatus: String?,
    devices: List<BleDiscoveredDevice>,
    identityExchangeStatusTexts: Map<String, String>,
    activeIdentityExchangeDeviceKey: String?,
    privateChatIdentitiesByPeerId: Map<String, PrivateChatIdentity>,
    onAddOrUpdateContact: (String, String, Long?, Boolean) -> String?,
    onSelectSecurePeer: (String) -> Unit,
    onClearSelectedSecurePeer: () -> Unit,
    onOpenPrivateChat: (String) -> Unit,
    onConnect: (BleDiscoveredDevice) -> Unit,
    onDisconnect: () -> Unit,
    onReadTransportMarker: () -> Unit,
    onReadTransportFrame: () -> Unit,
    onWriteTransportMarker: () -> Unit,
    onExchangeIdentity: (BleDiscoveredDevice, String?) -> Unit
) {
    var showDiagnostics by remember(showDebugDiagnostics) {
        mutableStateOf(false)
    }
    val expandedDebugCard = buildNearbyExpandedDebugCard(
        showDebugDiagnostics = showDebugDiagnostics,
        advertiseStatus = advertiseStatus,
        gattServerStatus = gattServerStatus,
        scanStatus = scanStatus,
        transportSenderSourceLabel = transportSenderSourceLabel,
        activeTransportPeerId = activeTransportPeerId,
        connectionStatus = connectionStatus,
        transportReadStatus = transportReadStatus,
        transportFrameReadStatus = transportFrameReadStatus,
        transportWriteStatus = transportWriteStatus,
        scanDiagnostics = scanDiagnostics,
        peerSessionDiagnostics = peerSessionDiagnostics,
        selectedSecurePeerId = selectedSecurePeerId,
        activeSessionPeerId = activeTransportPeerId,
        lastIdentityExchangeStatus = lastIdentityExchangeStatus
    )

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Nearby Aurora devices",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Visible: ${devices.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            when {
                devices.isNotEmpty() -> {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        devices.forEach { device ->
                            val contactPeerId = nearbyContactPeerId(device)
                            val existingContact = contactPeerId?.let { peerId ->
                                contacts.firstOrNull { it.canonicalPeerId == peerId }
                            }
                            val hasPrivateChatSetup = contactPeerId?.let { peerId ->
                                privateChatIdentitiesByPeerId[peerId] != null
                            } == true
                            val hasRuntimeSession = peerSessionDiagnostics.hasSessionForPeer(
                                contactPeerId
                            )
                            val isPrivateChatReady = contactPeerId?.let { peerId ->
                                hasRuntimeSession &&
                                    privateChatIdentitiesByPeerId[peerId]?.isEstablished == true
                            } == true
                            BleDiscoveredDeviceRow(
                                device = device,
                                existingContact = existingContact,
                                privateChatIdentitiesByPeerId = privateChatIdentitiesByPeerId,
                                hasRuntimeSession = hasRuntimeSession,
                                hasPrivateChatSetup = hasPrivateChatSetup,
                                isPrivateChatReady = isPrivateChatReady,
                                connectionStatus = connectionStatus,
                                transportReadStatus = transportReadStatus,
                                transportFrameReadStatus = transportFrameReadStatus,
                                transportWriteStatus = transportWriteStatus,
                                activeConnectionDeviceAddress = activeConnectionDeviceAddress,
                                showDebugActions = showDebugDiagnostics,
                                selectedSecurePeerId = selectedSecurePeerId,
                                identityExchangeStatusText = identityExchangeStatusTexts[
                                    nearbyBleDeviceIdentityKey(device)
                                ],
                                isIdentityExchangeActive =
                                    activeIdentityExchangeDeviceKey == nearbyBleDeviceIdentityKey(device),
                                onAddOrUpdateContact = onAddOrUpdateContact,
                                onSelectSecurePeer = onSelectSecurePeer,
                                onClearSelectedSecurePeer = onClearSelectedSecurePeer,
                                onOpenPrivateChat = onOpenPrivateChat,
                                onConnect = onConnect,
                                onDisconnect = onDisconnect,
                                onReadTransportMarker = onReadTransportMarker,
                                onReadTransportFrame = onReadTransportFrame,
                                onWriteTransportMarker = onWriteTransportMarker,
                                onExchangeIdentity = onExchangeIdentity
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
                        } else if (contacts.isNotEmpty()) {
                            "Scanning is active. Waiting for nearby Aurora peers to appear. Saved contacts stay available in Contacts."
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
                        } else if (contacts.isNotEmpty()) {
                            "No Aurora peers are visible right now. Saved contacts stay available in Contacts."
                        } else {
                            "Live Aurora peers will appear here when Bluetooth and Location/GPS readiness is complete and this screen is active."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (showDebugDiagnostics) {
                TextButton(
                    onClick = { showDiagnostics = !showDiagnostics }
                ) {
                    Text(
                        if (showDiagnostics) {
                            "Hide debug details"
                        } else {
                            "Show BLE diagnostics"
                        }
                    )
                }
            }

            if (showDebugDiagnostics && showDiagnostics) {
                expandedDebugCard?.let { card ->
                    DebugInfoCard(card = card)
                }
            }
        }
    }
}

@Composable
private fun BleDiscoveredDeviceRow(
    device: BleDiscoveredDevice,
    existingContact: AuroraContact?,
    privateChatIdentitiesByPeerId: Map<String, PrivateChatIdentity>,
    hasRuntimeSession: Boolean,
    hasPrivateChatSetup: Boolean,
    isPrivateChatReady: Boolean,
    connectionStatus: BleConnectionStatus,
    transportReadStatus: NearbyBleTransportReadStatus,
    transportFrameReadStatus: NearbyBleTransportFrameReadStatus,
    transportWriteStatus: NearbyBleTransportWriteStatus,
    activeConnectionDeviceAddress: String?,
    showDebugActions: Boolean,
    selectedSecurePeerId: String?,
    identityExchangeStatusText: String?,
    isIdentityExchangeActive: Boolean,
    onAddOrUpdateContact: (String, String, Long?, Boolean) -> String?,
    onSelectSecurePeer: (String) -> Unit,
    onClearSelectedSecurePeer: () -> Unit,
    onOpenPrivateChat: (String) -> Unit,
    onConnect: (BleDiscoveredDevice) -> Unit,
    onDisconnect: () -> Unit,
    onReadTransportMarker: () -> Unit,
    onReadTransportFrame: () -> Unit,
    onWriteTransportMarker: () -> Unit,
    onExchangeIdentity: (BleDiscoveredDevice, String?) -> Unit
) {
    val actionVisibility = nearbyRowActionVisibility(
        device = device,
        existingContact = existingContact,
        isPrivateChatReady = isPrivateChatReady,
        connectionStatus = connectionStatus,
        activeConnectionDeviceAddress = activeConnectionDeviceAddress,
        showDebugActions = showDebugActions
    )
    val securePeerId = nearbyContactPeerId(device)
    val canSelectSecurePeer = showDebugActions && !securePeerId.isNullOrBlank()
    val isSelectedSecurePeer = securePeerId != null && securePeerId == selectedSecurePeerId
    val isContact = existingContact != null
    val isTransportActionActive =
        transportReadStatus == NearbyBleTransportReadStatus.READING ||
            transportFrameReadStatus == NearbyBleTransportFrameReadStatus.READING ||
            transportWriteStatus == NearbyBleTransportWriteStatus.WRITING ||
            isIdentityExchangeActive
    val isReadTransportMarkerEnabled = !isTransportActionActive
    val isReadTransportFrameEnabled = !isTransportActionActive
    val isWriteTransportMarkerEnabled = !isTransportActionActive
    val productDisplayName =
        existingContact?.let { contact ->
            nearbyContactDisplayName(
                contact = contact,
                identity = nearbyContactPeerId(device)?.let(privateChatIdentitiesByPeerId::get)
            )
        } ?: nearbyContactDisplayName(device)
    val titleText = if (showDebugActions) {
        device.name?.trim()?.takeIf { it.isNotEmpty() } ?: productDisplayName
    } else {
        productDisplayName
    }
    val statusText = if (showDebugActions) {
        nearbyContactStatusText(
            isContact = isContact,
            hasReadyKeys = isPrivateChatReady,
            hasPrivateChatSetup = hasPrivateChatSetup
        )
    } else {
        nearbyProductStatusText(
            isContact = isContact,
            hasReadyKeys = isPrivateChatReady,
            hasPrivateChatSetup = hasPrivateChatSetup,
            isAuroraDevice = device.hasAuroraDiscoveryPayload
        )
    }
    val visibilityText = if (showDebugActions || !device.hasAuroraDiscoveryPayload) {
        null
    } else {
        "Seen nearby"
    }
    val openChatPeerId = nearbyOpenChatPeerId(existingContact)

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = titleText,
            style = MaterialTheme.typography.titleSmall
        )
        if (showDebugActions) {
            Text(
                text = "${device.address} | RSSI ${device.rssi.toBleScanRssiText()} dBm",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (showDebugActions) {
            Text(
                text = buildString {
                    append("RSSI ")
                    append(device.rssi.toBleScanRssiText())
                    append(" dBm | Marker ")
                    append(
                        if (device.hasAuroraDiscoveryPayload) {
                            "seen"
                        } else {
                            "not seen"
                        }
                    )
                    append(" | ")
                    append(
                        if (device.isConnectable == true) {
                            "connectable"
                        } else {
                            "seen only"
                        }
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        visibilityText?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (statusText != null) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (actionVisibility.showConnect) {
            Button(
                onClick = { onConnect(device) }
            ) {
                Text("Connect")
            }
        }
        if (actionVisibility.showDisconnect) {
            Button(
                onClick = onDisconnect
            ) {
                Text("Disconnect")
            }
        }
        if (actionVisibility.showAddContact && securePeerId != null) {
            Button(
                onClick = {
                    val privateChatProposalId = onAddOrUpdateContact(
                        securePeerId,
                        nearbyContactDisplayName(device),
                        System.currentTimeMillis(),
                        hasRuntimeSession
                    )
                    onExchangeIdentity(device, privateChatProposalId)
                }
            ) {
                Text(
                    nearbyAddContactActionLabel(
                        existingContact = existingContact,
                        identity = privateChatIdentitiesByPeerId[securePeerId]
                    )
                )
            }
        }
        if (actionVisibility.showOpenChat) {
            Button(
                onClick = { onOpenPrivateChat(openChatPeerId ?: requireNotNull(securePeerId)) }
            ) {
                Text("Open chat")
            }
        }
        if (showDebugActions && actionVisibility.showReadTransportMarker) {
            Button(
                enabled = isReadTransportMarkerEnabled,
                onClick = onReadTransportMarker
            ) {
                Text("Read marker")
            }
        }
        if (showDebugActions && actionVisibility.showReadTransportFrame) {
            Button(
                enabled = isReadTransportFrameEnabled,
                onClick = onReadTransportFrame
            ) {
                Text("Read frame")
            }
        }
        if (showDebugActions && actionVisibility.showWriteTransportMarker) {
            Button(
                enabled = isWriteTransportMarkerEnabled,
                onClick = onWriteTransportMarker
            ) {
                Text("Write marker")
            }
        }
        if (canSelectSecurePeer) {
            if (isSelectedSecurePeer) {
                Text(
                    text = "Secure chat target selected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onClearSelectedSecurePeer
                ) {
                    Text("Clear secure chat target")
                }
            } else {
                Button(
                    onClick = { onSelectSecurePeer(requireNotNull(securePeerId)) }
                ) {
                    Text("Use for secure chat")
                }
            }
        }
        if (showDebugActions && actionVisibility.showExchangeIdentity) {
            Button(
                enabled = !isTransportActionActive,
                onClick = {
                    val privateChatProposalId = securePeerId?.let { peerId ->
                        privateChatIdentitiesByPeerId[peerId]?.localProposalId
                    }
                    onExchangeIdentity(device, privateChatProposalId)
                }
            ) {
                Text("Exchange keys")
            }
            Text(
                text = "Run on both devices.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!identityExchangeStatusText.isNullOrBlank()) {
            Text(
                text = identityExchangeStatusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

internal data class NearbyRowActionVisibility(
    val showConnect: Boolean,
    val showDisconnect: Boolean,
    val showReadTransportMarker: Boolean,
    val showReadTransportFrame: Boolean,
    val showWriteTransportMarker: Boolean,
    val showExchangeIdentity: Boolean,
    val showAddContact: Boolean,
    val showOpenChat: Boolean
)

internal fun nearbyRowActionVisibility(
    device: BleDiscoveredDevice,
    existingContact: AuroraContact?,
    isPrivateChatReady: Boolean,
    connectionStatus: BleConnectionStatus,
    activeConnectionDeviceAddress: String?,
    showDebugActions: Boolean
): NearbyRowActionVisibility {
    val isActiveDevice = activeConnectionDeviceAddress == device.address
    val hasActiveConnection = connectionStatus == BleConnectionStatus.CONNECTING ||
        connectionStatus == BleConnectionStatus.CONNECTED
    val showDisconnect = showDebugActions && isActiveDevice && hasActiveConnection
    val showConnect = showDebugActions &&
        device.hasAuroraDiscoveryPayload &&
        !showDisconnect &&
        (!hasActiveConnection || isActiveDevice)
    val showConnectedTransportActions =
        showDebugActions &&
            isActiveDevice &&
            connectionStatus == BleConnectionStatus.CONNECTED
    val hasContactPeerId = nearbyContactPeerId(device) != null

    return NearbyRowActionVisibility(
        showConnect = showConnect,
        showDisconnect = showDisconnect,
        showReadTransportMarker = showConnectedTransportActions,
        showReadTransportFrame = showConnectedTransportActions,
        showWriteTransportMarker = showConnectedTransportActions,
        showExchangeIdentity = showConnectedTransportActions && device.hasAuroraDiscoveryPayload,
        showAddContact = !showDebugActions &&
            device.hasAuroraDiscoveryPayload &&
            hasContactPeerId &&
            (existingContact == null || !isPrivateChatReady),
        showOpenChat = !showDebugActions && existingContact != null
    )
}

@Composable
private fun ReadinessStatusCard(
    bluetoothStatus: BluetoothPermissionStatus,
    showDebugDetails: Boolean,
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
                text = nearbyReadinessHeadline(
                    bluetoothStatus = bluetoothStatus,
                    showDebugDetails = showDebugDetails
                ),
                style = MaterialTheme.typography.titleMedium
            )
            nearbyReadinessDetailLines(
                bluetoothStatus = bluetoothStatus,
                showDebugDetails = showDebugDetails
            ).forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!bluetoothStatus.allRequiredGranted) {
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

internal fun nearbyReadinessHeadline(
    bluetoothStatus: BluetoothPermissionStatus,
    showDebugDetails: Boolean
): String {
    val statusText = if (bluetoothStatus.isReadinessComplete) {
        "Ready"
    } else if (showDebugDetails) {
        "Incomplete"
    } else {
        nearbyReadinessProblems(bluetoothStatus).joinToString(separator = ", ")
    }
    return "Device readiness: $statusText"
}

internal fun nearbyReadinessDetailLines(
    bluetoothStatus: BluetoothPermissionStatus,
    showDebugDetails: Boolean
): List<String> {
    if (!showDebugDetails) {
        return emptyList()
    }

    return listOf(
        if (bluetoothStatus.allRequiredGranted) {
            "Permissions: Ready"
        } else {
            "Permissions: Missing"
        },
        when (bluetoothStatus.isBluetoothEnabled) {
            true -> "Bluetooth: Enabled"
            false -> "Bluetooth: Disabled"
            null -> "Bluetooth: Status unknown"
        },
        when (bluetoothStatus.isLocationEnabled) {
            true -> "Location/GPS: Enabled"
            false -> "Location/GPS: Disabled"
            null -> "Location/GPS: Status unknown"
        }
    )
}

internal fun nearbyReadinessProblems(
    bluetoothStatus: BluetoothPermissionStatus
): List<String> {
    return buildList {
        if (!bluetoothStatus.allRequiredGranted) {
            add("Permissions missing")
        }
        when (bluetoothStatus.isBluetoothEnabled) {
            false -> add("Bluetooth off")
            null -> add("Bluetooth unknown")
            else -> Unit
        }
        when (bluetoothStatus.isLocationEnabled) {
            false -> add("Location/GPS off")
            null -> add("Location/GPS unknown")
            else -> Unit
        }
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

internal data class LocalPeerIdentityExchangePublicMaterial(
    val peerId: String,
    private val publicAgreementKeyBytes: ByteArray
) {
    init {
        require(peerId.isNotBlank()) {
            "Local peer id must not be blank."
        }
        require(publicAgreementKeyBytes.isNotEmpty()) {
            "Local public agreement key bytes must not be empty."
        }
    }

    fun publicAgreementKeyBytes(): ByteArray {
        return publicAgreementKeyBytes.copyOf()
    }
}

internal sealed interface LocalPeerIdentityExchangePublicMaterialLoadResult {
    data class Ready(
        val material: LocalPeerIdentityExchangePublicMaterial
    ) : LocalPeerIdentityExchangePublicMaterialLoadResult

    data class Unavailable(
        val reason: String
    ) : LocalPeerIdentityExchangePublicMaterialLoadResult {
        init {
            require(reason.isNotBlank()) {
                "Local peer identity exchange unavailable reason must not be blank."
            }
        }
    }
}

internal fun loadLocalPeerIdentityExchangePublicMaterialResult(
    loadPublicKeyBytes: () -> ByteArray? = {
        runCatching {
            AndroidKeystoreLocalAgreementPublicKey.ensureAgreementPublicKeyBytes()
        }.getOrNull()
    }
): LocalPeerIdentityExchangePublicMaterialLoadResult {
    val publicKeyBytes = runCatching(loadPublicKeyBytes).getOrNull()
        ?.takeIf { it.isNotEmpty() }
        ?: return LocalPeerIdentityExchangePublicMaterialLoadResult.Unavailable(
            reason = "Local agreement public key unavailable."
        )

    return runCatching {
        LocalPeerIdentityExchangePublicMaterialLoadResult.Ready(
            LocalPeerIdentityExchangePublicMaterial(
                peerId = PeerSessionPeerId.deriveFromPublicKey(publicKeyBytes),
                publicAgreementKeyBytes = publicKeyBytes
            )
        )
    }.getOrElse {
        LocalPeerIdentityExchangePublicMaterialLoadResult.Unavailable(
            reason = "Local agreement public key unavailable."
        )
    }
}

internal fun loadLocalPeerIdentityExchangePublicMaterialOrNull(
    loadPublicKeyBytes: () -> ByteArray? = {
        AndroidKeystoreLocalAgreementPublicKey.ensureAgreementPublicKeyBytes()
    }
): LocalPeerIdentityExchangePublicMaterial? {
    return when (
        val result = loadLocalPeerIdentityExchangePublicMaterialResult(
            loadPublicKeyBytes = loadPublicKeyBytes
        )
    ) {
        is LocalPeerIdentityExchangePublicMaterialLoadResult.Ready -> result.material
        is LocalPeerIdentityExchangePublicMaterialLoadResult.Unavailable -> null
    }
}

internal fun nearbyBleDeviceIdentityKey(device: BleDiscoveredDevice): String {
    return device.stablePeerId?.toNearbyPeerId() ?: device.address.trim()
}

internal fun nearbyIdentityExchangeStatusText(
    result: PeerIdentityExchangeSendResult
): String {
    return when (result) {
        PeerIdentityExchangeSendResult.SubmittedLocally ->
            "Identity sent. Run on both devices."
        PeerIdentityExchangeSendResult.SenderUnavailable ->
            "Identity exchange unavailable."
        is PeerIdentityExchangeSendResult.InvalidLocalIdentity ->
            result.reason
        is PeerIdentityExchangeSendResult.Failed ->
            "Identity exchange failed: ${result.reason}"
    }
}

internal fun nearbyContactPeerId(
    device: BleDiscoveredDevice
): String? {
    return device.stablePeerId?.toNearbyPeerId()?.takeIf { it.isNotBlank() }
}

internal fun nearbyContactDisplayName(
    device: BleDiscoveredDevice
): String {
    val shortPeerId = nearbyContactPeerId(device)?.take(8)
    return if (shortPeerId != null) {
        "Aurora device $shortPeerId"
    } else if (device.hasAuroraDiscoveryPayload) {
        "Aurora device"
    } else {
        "Unknown BLE device"
    }
}

internal fun nearbyContactDisplayName(
    contact: AuroraContact,
    identity: PrivateChatIdentity?
): String {
    return identity?.displayNameOrNull()
        ?: contact.displayName
}

internal fun nearbyPeerHasReadyKeys(
    peerId: String?,
    diagnostics: PeerSessionRegistryDiagnostics
): Boolean {
    return diagnostics.hasSessionForPeer(peerId)
}

internal fun nearbyAddContactActionLabel(
    existingContact: AuroraContact?,
    identity: PrivateChatIdentity?
): String {
    return when {
        existingContact == null -> "Add contact"
        identity?.isEstablished == true -> "Retry setup"
        else -> "Finish setup"
    }
}

internal fun nearbyContactStatusText(
    isContact: Boolean,
    hasReadyKeys: Boolean,
    hasPrivateChatSetup: Boolean
): String? {
    if (!isContact) return null
    return when {
        hasReadyKeys -> "Private chat ready"
        hasPrivateChatSetup -> "Retry setup"
        else -> "Setup needed"
    }
}

internal fun nearbyProductStatusText(
    isContact: Boolean,
    hasReadyKeys: Boolean,
    hasPrivateChatSetup: Boolean,
    isAuroraDevice: Boolean
): String? {
    if (!isAuroraDevice || !isContact) {
        return null
    }

    return nearbyContactStatusText(
        isContact = true,
        hasReadyKeys = hasReadyKeys,
        hasPrivateChatSetup = hasPrivateChatSetup
    )
}

internal fun nearbyOpenChatPeerId(
    contact: AuroraContact?
): String? {
    return contact?.canonicalPeerId?.trim()?.takeIf { it.isNotEmpty() }
}

internal fun nearbySelectedSecurePeerText(
    selectedSecurePeerId: String?
): String {
    val peerId = selectedSecurePeerId?.trim()?.takeIf { it.isNotEmpty() }
    return if (peerId == null) {
        "No secure peer selected for mesh delivery."
    } else {
        "Secure peer selected: $peerId"
    }
}

internal fun buildNearbyRuntimeDebugSection(
    advertiseStatus: BleAdvertiseStatus,
    gattServerStatus: BleGattServerStatus,
    scanStatus: BleScanStatus
): DebugInfoSection {
    val items = buildList {
        add(DebugInfoItem("Mode", "Full mesh"))
        add(DebugInfoItem("Scan", scanStatus.toUiLabel()))
        if (advertiseStatus != BleAdvertiseStatus.ADVERTISING) {
            add(DebugInfoItem("Advertise", advertiseStatus.toUiLabel()))
        }
        if (gattServerStatus != BleGattServerStatus.HOSTING) {
            add(DebugInfoItem("GATT", gattServerStatus.toUiLabel()))
        }
    }

    return DebugInfoSection(
        title = "Runtime",
        items = items
    )
}

internal fun buildNearbyDebugCard(
    showDebugDiagnostics: Boolean,
    advertiseStatus: BleAdvertiseStatus,
    gattServerStatus: BleGattServerStatus,
    scanStatus: BleScanStatus,
    wifiDirectRuntimeStatus: WifiDirectRuntimeStatus,
    wifiDirectSocketDiagnostics: gr.hua.aurora.wifidirect.WifiDirectSocketDiagnostics,
    wifiDirectAdapterDiagnostics: gr.hua.aurora.wifidirect.WifiDirectTransportAdapterDiagnostics =
        gr.hua.aurora.wifidirect.WifiDirectTransportAdapterDiagnostics(),
    wifiDirectSendBridgeDiagnostics: gr.hua.aurora.wifidirect.WifiDirectSendBridgeDiagnostics =
        gr.hua.aurora.wifidirect.WifiDirectSendBridgeDiagnostics(),
    wifiDirectReceiveBridgeDiagnostics: gr.hua.aurora.wifidirect.WifiDirectReceiveBridgeDiagnostics =
        gr.hua.aurora.wifidirect.WifiDirectReceiveBridgeDiagnostics(),
    identityHandlerStatus: String,
    peerSessionDiagnostics: PeerSessionRegistryDiagnostics
): DebugInfoCardModel? {
    if (!showDebugDiagnostics) {
        return null
    }

    return DebugInfoCardModel(
        title = "Debug",
        sections = listOf(
            buildNearbyRuntimeDebugSection(
                advertiseStatus = advertiseStatus,
                gattServerStatus = gattServerStatus,
                scanStatus = scanStatus
            ),
            buildNearbyWifiDirectDebugSection(
                runtimeStatus = wifiDirectRuntimeStatus
            ),
            buildNearbyWifiDirectSocketDebugSection(
                diagnostics = wifiDirectSocketDiagnostics
            ),
            buildNearbyWifiDirectFrameDebugSection(
                diagnostics = wifiDirectSocketDiagnostics
            ),
            buildNearbyWifiDirectAdapterDebugSection(
                diagnostics = wifiDirectAdapterDiagnostics
            ),
            buildNearbyWifiDirectSendBridgeDebugSection(
                diagnostics = wifiDirectSendBridgeDiagnostics
            ),
            buildNearbyWifiDirectReceiveBridgeDebugSection(
                diagnostics = wifiDirectReceiveBridgeDiagnostics
            ),
            buildNearbyIdentityDebugSection(
                identityHandlerStatus = identityHandlerStatus,
                peerSessionDiagnostics = peerSessionDiagnostics
            )
        )
    )
}

internal fun buildNearbyTransportDebugSection(
    transportSenderSourceLabel: String,
    activeTransportPeerId: String?,
    connectionStatus: BleConnectionStatus
): DebugInfoSection {
    return DebugInfoSection(
        title = "Transport",
        items = listOf(
            DebugInfoItem("Sender", nearbyTransportSourceValue(transportSenderSourceLabel)),
            DebugInfoItem(
                "Active peer",
                activeTransportPeerId?.trim()?.takeIf { it.isNotEmpty() } ?: "none"
            ),
            DebugInfoItem("Connection", connectionStatus.toUiLabel())
        )
    )
}

internal fun buildNearbyIdentityDebugSection(
    identityHandlerStatus: String,
    peerSessionDiagnostics: PeerSessionRegistryDiagnostics
): DebugInfoSection {
    return DebugInfoSection(
        title = "Identity",
        items = listOf(
            DebugInfoItem("Handler", nearbyIdentityHandlerValue(identityHandlerStatus)),
            DebugInfoItem("Sessions", peerSessionDiagnostics.establishedPeerIds.size.toString())
        )
    )
}

internal fun buildNearbyExpandedDebugSections(
    showDebugDiagnostics: Boolean,
    advertiseStatus: BleAdvertiseStatus,
    gattServerStatus: BleGattServerStatus,
    scanStatus: BleScanStatus,
    transportSenderSourceLabel: String,
    activeTransportPeerId: String?,
    connectionStatus: BleConnectionStatus,
    transportReadStatus: NearbyBleTransportReadStatus,
    transportFrameReadStatus: NearbyBleTransportFrameReadStatus,
    transportWriteStatus: NearbyBleTransportWriteStatus,
    scanDiagnostics: BleScanDiagnostics,
    peerSessionDiagnostics: PeerSessionRegistryDiagnostics,
    selectedSecurePeerId: String?,
    activeSessionPeerId: String?,
    lastIdentityExchangeStatus: String?
): List<DebugInfoSection> {
    if (!showDebugDiagnostics) {
        return emptyList()
    }

    return buildList {
        add(
            DebugInfoSection(
                title = "Runtime details",
                items = listOf(
                    DebugInfoItem("Advertise", advertiseStatus.toUiLabel()),
                    DebugInfoItem("GATT", gattServerStatus.toUiLabel()),
                    DebugInfoItem("Scan", scanStatus.toUiLabel())
                )
            )
        )
        add(
            DebugInfoSection(
                title = "Transport",
                items = buildNearbyTransportDebugSection(
                    transportSenderSourceLabel = transportSenderSourceLabel,
                    activeTransportPeerId = activeTransportPeerId,
                    connectionStatus = connectionStatus
                ).items + listOf(
                    DebugInfoItem("Read", transportReadStatus.toUiLabel()),
                    DebugInfoItem("Frame", transportFrameReadStatus.toUiLabel()),
                    DebugInfoItem("Write", transportWriteStatus.toUiLabel())
                )
            )
        )
        add(
            DebugInfoSection(
                title = "Scan details",
                items = listOf(
                    DebugInfoItem("Raw", scanDiagnostics.rawScanResultCount.toString()),
                    DebugInfoItem("Matches", scanDiagnostics.auroraDiscoveryMatchCount.toString()),
                    DebugInfoItem(
                        "Last scan",
                        "${scanDiagnostics.lastDeviceName.toBleScanText()} / " +
                            "${scanDiagnostics.lastDeviceAddress.toBleScanText()} / " +
                            scanDiagnostics.lastRssi.toBleScanRssiText(),
                        preferFullWidth = true
                    ),
                    DebugInfoItem("Service data", scanDiagnostics.lastHadDiscoveryServiceData.toSeenText()),
                    DebugInfoItem("Marker", scanDiagnostics.lastHadAuroraDiscoveryPayload.toSeenText())
                )
            )
        )
        val identityDetails = buildList {
            add(DebugInfoItem("Selected peer", selectedSecurePeerId?.trim()?.takeIf { it.isNotEmpty() } ?: "none"))
            add(DebugInfoItem("Active peer", activeSessionPeerId?.trim()?.takeIf { it.isNotEmpty() } ?: "none"))
            add(
                DebugInfoItem(
                    "Selected session",
                    nearbyPeerSessionCompactValue(
                        peerId = selectedSecurePeerId,
                        diagnostics = peerSessionDiagnostics
                    )
                )
            )
            add(
                DebugInfoItem(
                    "Active session",
                    nearbyPeerSessionCompactValue(
                        peerId = activeSessionPeerId,
                        diagnostics = peerSessionDiagnostics
                    )
                )
            )
            add(
                DebugInfoItem(
                    "Established",
                    peerSessionDiagnostics.establishedPeerIds.joinToString().ifBlank { "none" },
                    preferFullWidth = true
                )
            )
            peerSessionDiagnostics.canonicalPeerIdByAlias.takeIf { it.isNotEmpty() }?.let { aliases ->
                add(
                    DebugInfoItem(
                        "Aliases",
                        aliases.entries.joinToString(separator = ", ") { (aliasPeerId, canonicalPeerId) ->
                            "$aliasPeerId -> $canonicalPeerId"
                        },
                        preferFullWidth = true
                    )
                )
            }
            if (!lastIdentityExchangeStatus.isNullOrBlank()) {
                add(
                    DebugInfoItem(
                        "Last exchange",
                        lastIdentityExchangeStatus,
                        preferFullWidth = true
                    )
                )
            }
        }
        add(
            DebugInfoSection(
                title = "Identity details",
                items = identityDetails
            )
        )
    }
}

internal fun buildNearbyExpandedDebugCard(
    showDebugDiagnostics: Boolean,
    advertiseStatus: BleAdvertiseStatus,
    gattServerStatus: BleGattServerStatus,
    scanStatus: BleScanStatus,
    transportSenderSourceLabel: String,
    activeTransportPeerId: String?,
    connectionStatus: BleConnectionStatus,
    transportReadStatus: NearbyBleTransportReadStatus,
    transportFrameReadStatus: NearbyBleTransportFrameReadStatus,
    transportWriteStatus: NearbyBleTransportWriteStatus,
    scanDiagnostics: BleScanDiagnostics,
    peerSessionDiagnostics: PeerSessionRegistryDiagnostics,
    selectedSecurePeerId: String?,
    activeSessionPeerId: String?,
    lastIdentityExchangeStatus: String?
): DebugInfoCardModel? {
    val sections = buildNearbyExpandedDebugSections(
        showDebugDiagnostics = showDebugDiagnostics,
        advertiseStatus = advertiseStatus,
        gattServerStatus = gattServerStatus,
        scanStatus = scanStatus,
        transportSenderSourceLabel = transportSenderSourceLabel,
        activeTransportPeerId = activeTransportPeerId,
        connectionStatus = connectionStatus,
        transportReadStatus = transportReadStatus,
        transportFrameReadStatus = transportFrameReadStatus,
        transportWriteStatus = transportWriteStatus,
        scanDiagnostics = scanDiagnostics,
        peerSessionDiagnostics = peerSessionDiagnostics,
        selectedSecurePeerId = selectedSecurePeerId,
        activeSessionPeerId = activeSessionPeerId,
        lastIdentityExchangeStatus = lastIdentityExchangeStatus
    )
    if (sections.isEmpty()) {
        return null
    }

    return DebugInfoCardModel(
        title = "BLE diagnostics",
        sections = sections
    )
}

internal fun nearbyTransportSourceValue(
    transportSenderSourceLabel: String
): String {
    val source = transportSenderSourceLabel.trim()
    return when {
        source.contains("android", ignoreCase = true) -> "Android"
        source.contains("noop", ignoreCase = true) -> "NoOp"
        source.isEmpty() -> "unavailable"
        else -> source
    }
}

internal fun nearbyIdentityHandlerValue(
    identityHandlerStatus: String
): String {
    val status = identityHandlerStatus.trim()
    return when {
        status.contains("ready", ignoreCase = true) -> "ready"
        status.contains("missing", ignoreCase = true) -> "missing"
        status.contains("unavailable", ignoreCase = true) -> "missing"
        status.isEmpty() -> "missing"
        else -> status.removeSuffix(".")
    }
}

internal fun nearbyPeerSessionCompactValue(
    peerId: String?,
    diagnostics: PeerSessionRegistryDiagnostics
): String {
    val sanitizedPeerId = peerId?.trim()?.takeIf { it.isNotEmpty() } ?: return "none"
    return when {
        diagnostics.establishedPeerIds.contains(sanitizedPeerId) -> "ready"
        diagnostics.canonicalPeerIdByAlias.containsKey(sanitizedPeerId) -> "ready"
        else -> "missing"
    }
}

internal fun nearbyIdentityExchangeCompactValue(
    lastIdentityExchangeStatus: String
): String {
    val status = lastIdentityExchangeStatus.trim()
    return when {
        status.startsWith("Identity received from ", ignoreCase = true) ->
            status.removePrefix("Identity received from ").substringBefore(".").trim()
        status.startsWith("Identity sent", ignoreCase = true) -> "sent"
        status.contains("handler unavailable", ignoreCase = true) -> "handler unavailable"
        else -> status.removeSuffix(".")
    }
}

internal fun nearbyPeerSessionStatusText(
    label: String,
    peerId: String?,
    diagnostics: PeerSessionRegistryDiagnostics
): String {
    require(label.isNotBlank()) {
        "Nearby peer session label must not be blank."
    }

    val sanitizedPeerId = peerId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return "$label: none"
    if (diagnostics.establishedPeerIds.contains(sanitizedPeerId)) {
        return "$label: ready"
    }
    val canonicalPeerId = diagnostics.canonicalPeerIdByAlias[sanitizedPeerId]
    return if (canonicalPeerId != null) {
        "$label: ready (mapped to $canonicalPeerId)"
    } else {
        "$label: missing"
    }
}

internal fun nearbyEstablishedSessionPeersText(
    diagnostics: PeerSessionRegistryDiagnostics
): String {
    return if (diagnostics.establishedPeerIds.isEmpty()) {
        "Established session peers (0): none"
    } else {
        "Established session peers (${diagnostics.establishedPeerIds.size}): ${diagnostics.establishedPeerIds.joinToString(separator = ", ")}"
    }
}

internal fun nearbySessionAliasText(
    diagnostics: PeerSessionRegistryDiagnostics
): String? {
    if (diagnostics.canonicalPeerIdByAlias.isEmpty()) {
        return null
    }

    return "Session peer aliases: ${diagnostics.canonicalPeerIdByAlias.entries.joinToString(separator = ", ") { (aliasPeerId, canonicalPeerId) -> "$aliasPeerId -> $canonicalPeerId" }}"
}

private fun BleStablePeerId.toNearbyPeerId(): String {
    return toByteArray().joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xFF)
    }
}
