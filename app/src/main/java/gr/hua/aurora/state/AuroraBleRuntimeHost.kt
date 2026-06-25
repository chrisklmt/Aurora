package gr.hua.aurora.state

import android.bluetooth.BluetoothManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import gr.hua.aurora.ble.advertise.AndroidBleAdvertiser
import gr.hua.aurora.ble.advertise.BleAdvertiseRequest
import gr.hua.aurora.ble.advertise.BleAdvertiseStatus
import gr.hua.aurora.ble.connection.AndroidBleConnector
import gr.hua.aurora.ble.connection.BleConnectionStatus
import gr.hua.aurora.ble.connection.BleConnector
import gr.hua.aurora.ble.advertise.BleAdvertiser
import gr.hua.aurora.ble.discovery.AndroidBleScanner
import gr.hua.aurora.ble.discovery.BleDiscoveredDevice
import gr.hua.aurora.ble.discovery.BleDiscoveryPayload
import gr.hua.aurora.ble.discovery.BleDiscoveryService
import gr.hua.aurora.ble.discovery.BleScanAggregator
import gr.hua.aurora.ble.discovery.BleScanDiagnostics
import gr.hua.aurora.ble.discovery.BleScanStatus
import gr.hua.aurora.ble.discovery.BleScanner
import gr.hua.aurora.ble.discovery.BleStablePeerId
import gr.hua.aurora.ble.gatt.AndroidBleGattServer
import gr.hua.aurora.ble.gatt.BleGattServer
import gr.hua.aurora.ble.gatt.BleGattServerStatus
import gr.hua.aurora.ble.noop.NoOpBleTransportSender
import gr.hua.aurora.ble.permissions.BluetoothPermissionStatus
import gr.hua.aurora.ble.permissions.rememberBluetoothPermissionStatusState
import gr.hua.aurora.ble.transport.AndroidBleTransportSender
import gr.hua.aurora.ble.transport.BleGattTransportFrameWriter
import gr.hua.aurora.ble.transport.BleTransportFrameBridge
import gr.hua.aurora.ble.transport.BleTransportFrameReceiver
import gr.hua.aurora.ble.transport.BleTransportReceiveResult
import gr.hua.aurora.ble.transport.BleTransportSender
import gr.hua.aurora.identity.AndroidKeystoreLocalAgreementKey
import gr.hua.aurora.identity.AndroidKeystoreLocalAgreementKey.PrivateKeyLoadResult
import gr.hua.aurora.identity.AndroidKeystoreLocalAgreementPublicKey
import gr.hua.aurora.model.OutgoingChatMessage
import gr.hua.aurora.protocol.IncomingSessionMaterialProvider
import gr.hua.aurora.protocol.IncomingTransportMessage
import gr.hua.aurora.protocol.IncomingTransportReceiveResult
import gr.hua.aurora.protocol.GlobalMeshDeliveryCoordinator
import gr.hua.aurora.protocol.GlobalMeshDeliveryResult
import gr.hua.aurora.protocol.GlobalMeshDiagnostics
import gr.hua.aurora.protocol.LocalPeerSessionIdentityMaterial
import gr.hua.aurora.protocol.MessageFrameType
import gr.hua.aurora.protocol.NoOpIncomingSessionMaterialProvider
import gr.hua.aurora.protocol.PeerIdentityExchangeHandler
import gr.hua.aurora.protocol.PeerIdentityExchangeHandlingResult
import gr.hua.aurora.protocol.PeerSessionRegistry
import gr.hua.aurora.protocol.PeerSessionRegistryDiagnostics
import gr.hua.aurora.state.IncomingMessageIngestionResult.Appended
import gr.hua.aurora.state.IncomingMessageIngestionResult.Duplicate
import gr.hua.aurora.state.IncomingMessageIngestionResult.UnsupportedThread
import gr.hua.aurora.state.IncomingMessageIngestionResult.UnsupportedType
import gr.hua.aurora.ui.components.buildAuroraAvailabilityUiState
import java.security.PrivateKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val auroraBleRuntimeLogTag = "AuroraBleRuntime"

data class AuroraBleRuntimeState(
    val bleAdvertiseStatus: BleAdvertiseStatus,
    val bleGattServerStatus: BleGattServerStatus,
    val bleScanStatus: BleScanStatus,
    val bleScanDiagnostics: BleScanDiagnostics,
    val discoveredAuroraPeers: List<BleDiscoveredDevice>,
    val bleConnector: AndroidBleConnector,
    val bleConnectionStatus: BleConnectionStatus,
    val activeTransportDeviceAddress: String?,
    val activeTransportPeerId: String?,
    val bleTransportSender: BleTransportSender,
    val transportSenderSourceLabel: String,
    val peerSessionDiagnostics: PeerSessionRegistryDiagnostics,
    val globalMeshDiagnostics: GlobalMeshDiagnostics,
    val identityHandlerStatus: String,
    val lastIdentityExchangeStatus: String?,
    val lastIncomingMessageStatus: String?,
    val lastConnectOnSendStatus: String?,
    val lastGlobalMeshStatus: String?,
    val submitGlobalMeshMessage: suspend (OutgoingChatMessage, String) -> GlobalMeshDeliveryResult,
    val connectToTransportPeer: (String, String?) -> Unit,
    val disconnectTransportPeer: () -> Unit
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
    stateHolder: AuroraStateHolder,
    transportFrameWriter: BleGattTransportFrameWriter? = null
): AuroraBleRuntimeState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val runtimeScope = rememberCoroutineScope()
    val mainHandler = remember {
        Handler(Looper.getMainLooper())
    }
    val bluetoothManager = remember(context) {
        context.getSystemService(BluetoothManager::class.java)
    }
    val bluetoothAdapter = remember(bluetoothManager) {
        bluetoothManager?.adapter
    }
    val bleConnector = remember(context, bluetoothAdapter) {
        AndroidBleConnector(context, bluetoothAdapter)
    }
    val bleScanner = remember(bluetoothAdapter) {
        AndroidBleScanner(bluetoothAdapter)
    }
    val discoveredAuroraPeersAggregator = remember {
        BleScanAggregator()
    }
    val resolvedTransportFrameWriter = transportFrameWriter ?: bleConnector
    val bleTransportSender = remember(resolvedTransportFrameWriter) {
        createAuroraBleTransportSender(resolvedTransportFrameWriter)
    }
    val transportSenderSourceLabel = remember(bleTransportSender) {
        auroraTransportSenderSourceLabel(bleTransportSender)
    }
    var lastIdentityExchangeStatus by remember {
        mutableStateOf<String?>(null)
    }
    var lastIncomingMessageStatus by remember {
        mutableStateOf<String?>(null)
    }
    var lastConnectOnSendStatus by remember {
        mutableStateOf<String?>(null)
    }
    var lastGlobalMeshStatus by remember {
        mutableStateOf<String?>(null)
    }
    var bleConnectionStatus by remember {
        mutableStateOf(BleConnectionStatus.IDLE)
    }
    var activeTransportDeviceAddress by remember {
        mutableStateOf<String?>(null)
    }
    var activeTransportPeerId by remember {
        mutableStateOf<String?>(null)
    }
    var bleScanStatus by remember {
        mutableStateOf(BleScanStatus.IDLE)
    }
    var bleScanDiagnostics by remember {
        mutableStateOf(BleScanDiagnostics())
    }
    var discoveredAuroraPeers by remember {
        mutableStateOf(emptyList<BleDiscoveredDevice>())
    }
    val peerSessionRegistry = remember {
        PeerSessionRegistry()
    }
    var peerSessionDiagnostics by remember {
        mutableStateOf(peerSessionRegistry.diagnosticsSnapshot())
    }
    val globalMeshDeliveryCoordinator = remember {
        GlobalMeshDeliveryCoordinator()
    }
    var globalMeshDiagnostics by remember {
        mutableStateOf(
            globalMeshDeliveryCoordinator.diagnosticsSnapshot(
                reachablePeerIds = emptyList(),
                activeTransportPeerId = null
            )
        )
    }
    fun refreshGlobalMeshDiagnostics() {
        globalMeshDiagnostics = globalMeshDeliveryCoordinator.diagnosticsSnapshot(
            reachablePeerIds = discoveredAuroraPeerIds(discoveredAuroraPeers),
            activeTransportPeerId = activeTransportPeerId
        )
    }
    val localIdentityMaterialLoadResult = remember {
        loadLocalPeerSessionIdentityMaterialResult()
    }
    val localIdentityMaterial = (
        localIdentityMaterialLoadResult as? LocalPeerSessionIdentityMaterialLoadResult.Ready
    )?.material
    val incomingSessionMaterialProvider = remember(peerSessionRegistry) {
        peerSessionRegistry as IncomingSessionMaterialProvider
    }
    val handleIdentity = remember(localIdentityMaterial, peerSessionRegistry) {
        createAuroraIdentityHandlerOrNull(
            localIdentity = localIdentityMaterial,
            registry = peerSessionRegistry
        )
    }
    val identityHandlerStatus = remember(localIdentityMaterialLoadResult, handleIdentity) {
        auroraIdentityHandlerStatusText(
            loadResult = localIdentityMaterialLoadResult,
            isHandlerReady = handleIdentity != null
        )
    }
    val transportFrameReceiver = remember(
        stateHolder,
        incomingSessionMaterialProvider,
        handleIdentity,
        identityHandlerStatus
    ) {
        createAuroraBleTransportFrameReceiver(
            stateHolder = stateHolder,
            sessionMaterialProvider = incomingSessionMaterialProvider,
            handleIdentity = handleIdentity,
            identityHandlingUnavailableReason = identityHandlingUnavailableReason(
                loadResult = localIdentityMaterialLoadResult,
                isHandlerReady = handleIdentity != null
            )
        )
    }
    val transportFrameBridge = remember(transportFrameReceiver, mainHandler) {
        BleTransportFrameBridge(
            receiver = transportFrameReceiver,
            dispatch = { runnable ->
                mainHandler.post(runnable)
            },
            onReceiveResult = { result ->
                Log.d(auroraBleRuntimeLogTag, "BLE transport receive result: $result")
                peerSessionDiagnostics = peerSessionRegistry.diagnosticsSnapshot()
                identityExchangeRuntimeStatusText(result)?.let { statusText ->
                    lastIdentityExchangeStatus = statusText
                }
                incomingMessageRuntimeStatusText(result)?.let { statusText ->
                    lastIncomingMessageStatus = statusText
                }
                if (
                    result is BleTransportReceiveResult.Processed &&
                    result.processingResult is IncomingTransportFrameProcessingResult.Received
                ) {
                    val received =
                        result.processingResult as IncomingTransportFrameProcessingResult.Received
                    if (received.message.frame.type == MessageFrameType.GLOBAL_TEXT) {
                        runtimeScope.launch {
                            val meshResult = globalMeshDeliveryCoordinator.relayReceivedMessage(
                                message = received.message,
                                ingestionResult = received.ingestionResult,
                                transportSender = bleTransportSender,
                                activeTransportPeerId = activeTransportPeerId,
                                immediateSourcePeerId = activeTransportPeerId
                            )
                            refreshGlobalMeshDiagnostics()
                            lastGlobalMeshStatus = globalMeshStatusText(meshResult)
                        }
                    }
                }
                refreshGlobalMeshDiagnostics()
                logIdentityExchangeReceiveResult(result)
            }
        )
    }
    val bleAdvertiser = remember(bluetoothAdapter) {
        AndroidBleAdvertiser(bluetoothAdapter)
    }
    val bleGattServer = remember(context, bluetoothManager, transportFrameBridge) {
        AndroidBleGattServer(
            context = context,
            bluetoothManager = bluetoothManager,
            transportFrameListener = transportFrameBridge
        )
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

    fun clearRuntimeDiscoveryState(
        stopScanner: Boolean
    ) {
        if (stopScanner) {
            bleScanner.stop()
        }
        discoveredAuroraPeersAggregator.clear()
        bleScanStatus = BleScanStatus.STOPPED
        bleScanDiagnostics = BleScanDiagnostics()
        discoveredAuroraPeers = emptyList()
        refreshGlobalMeshDiagnostics()
    }

    fun clearTransportConnectionState() {
        bleConnectionStatus = BleConnectionStatus.DISCONNECTED
        activeTransportDeviceAddress = null
        activeTransportPeerId = null
        refreshGlobalMeshDiagnostics()
    }

    fun startRuntimeTransportConnection(
        deviceAddress: String,
        peerId: String?
    ) {
        val sanitizedPeerId = peerId?.trim()?.takeIf { it.isNotEmpty() }
        Log.d(
            auroraBleRuntimeLogTag,
            "BLE transport connect requested: address=$deviceAddress peerId=${sanitizedPeerId ?: "none"}"
        )
        bleConnector.connect(
            deviceAddress = deviceAddress,
            listener = object : BleConnector.Listener {
                override fun onStatusChanged(status: BleConnectionStatus) {
                    bleConnectionStatus = status
                    when (status) {
                        BleConnectionStatus.CONNECTING -> {
                            activeTransportDeviceAddress = deviceAddress
                            activeTransportPeerId = sanitizedPeerId
                            refreshGlobalMeshDiagnostics()
                        }
                        BleConnectionStatus.CONNECTED -> {
                            activeTransportDeviceAddress = deviceAddress
                            activeTransportPeerId = sanitizedPeerId
                            refreshGlobalMeshDiagnostics()
                            Log.d(
                                auroraBleRuntimeLogTag,
                                "BLE transport connected: address=$deviceAddress peerId=${sanitizedPeerId ?: "none"}"
                            )
                        }
                        BleConnectionStatus.DISCONNECTED -> {
                            Log.d(
                                auroraBleRuntimeLogTag,
                                "BLE transport disconnected: address=$deviceAddress peerId=${sanitizedPeerId ?: "none"}"
                            )
                            clearTransportConnectionState()
                        }
                        BleConnectionStatus.IDLE -> Unit
                    }
                }
            }
        )
    }

    suspend fun connectToReachablePeerAndAwait(
        device: BleDiscoveredDevice
    ): PublicMeshConnectOnSendResult {
        val peerId = runtimeReachablePeerId(device)
        val address = device.address.trim()
        if (
            bleConnectionStatus == BleConnectionStatus.CONNECTED &&
            activeTransportPeerId == peerId &&
            activeTransportDeviceAddress == address
        ) {
            return PublicMeshConnectOnSendResult.Connected(peerId = peerId)
        }

        if (
            !(
                bleConnectionStatus == BleConnectionStatus.CONNECTING &&
                    activeTransportPeerId == peerId &&
                    activeTransportDeviceAddress == address
                )
        ) {
            startRuntimeTransportConnection(
                deviceAddress = address,
                peerId = peerId
            )
        }

        val connected: Boolean = withTimeoutOrNull<Boolean>(publicMeshConnectOnSendTimeoutMs) {
            var isConnected = false
            while (!isConnected) {
                if (
                    bleConnectionStatus == BleConnectionStatus.CONNECTED &&
                    activeTransportPeerId == peerId &&
                    activeTransportDeviceAddress == address
                ) {
                    isConnected = true
                    continue
                }
                if (
                    bleConnectionStatus == BleConnectionStatus.DISCONNECTED ||
                    bleConnectionStatus == BleConnectionStatus.IDLE
                ) {
                    return@withTimeoutOrNull false
                }
                delay(publicMeshConnectOnSendPollIntervalMs)
            }
            true
        } ?: false

        if (!connected) {
            if (bleConnectionStatus == BleConnectionStatus.CONNECTING) {
                bleConnector.disconnect()
                clearTransportConnectionState()
            }
            return PublicMeshConnectOnSendResult.Failed(
                peerId = peerId,
                reason = "connection did not reach ready state"
            )
        }

        return PublicMeshConnectOnSendResult.Connected(peerId = peerId)
    }

    val submitGlobalMeshMessage: suspend (OutgoingChatMessage, String) -> GlobalMeshDeliveryResult =
        { queuedMessage, senderId ->
            val result = submitPublicGlobalMeshMessage(
                message = queuedMessage,
                senderId = senderId,
                coordinator = globalMeshDeliveryCoordinator,
                transportSender = bleTransportSender,
                activeTransportPeerId = activeTransportPeerId,
                isActiveTransportConnected = bleConnectionStatus == BleConnectionStatus.CONNECTED,
                reachablePeers = discoveredAuroraPeers,
                connectToReachablePeer = ::connectToReachablePeerAndAwait,
                onConnectOnSendStatusChanged = { statusText ->
                    lastConnectOnSendStatus = statusText
                }
            )
            refreshGlobalMeshDiagnostics()
            lastGlobalMeshStatus = globalMeshStatusText(result)
            result
        }

    val connectToTransportPeer: (String, String?) -> Unit = { deviceAddress, peerId ->
        startRuntimeTransportConnection(
            deviceAddress = deviceAddress,
            peerId = peerId
        )
    }

    val disconnectTransportPeer: () -> Unit = {
        Log.d(
            auroraBleRuntimeLogTag,
            "BLE transport disconnect requested: address=${activeTransportDeviceAddress ?: "none"} peerId=${activeTransportPeerId ?: "none"}"
        )
        bleConnector.disconnect()
        clearTransportConnectionState()
    }

    DisposableEffect(lifecycleOwner, bleAdvertiser, bleGattServer, bleConnector, bleScanner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAppVisible = true
            } else if (event == Lifecycle.Event.ON_STOP) {
                isAppVisible = false
                bleConnector.disconnect()
                clearTransportConnectionState()
                clearRuntimeDiscoveryState(stopScanner = true)
                bleAdvertiser.stop()
                bleAdvertiseStatus = BleAdvertiseStatus.STOPPED
                bleGattServer.stop()
                bleGattServerStatus = BleGattServerStatus.STOPPED
                transportFrameBridge.clear()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            bleConnector.disconnect()
            clearTransportConnectionState()
            clearRuntimeDiscoveryState(stopScanner = true)
            bleAdvertiser.stop()
            bleGattServer.stop()
            transportFrameBridge.clear()
        }
    }

    LaunchedEffect(
        desiredAvailability,
        bluetoothStatus,
        isAppVisible,
        shouldHostRuntime,
        advertiseRequest,
        identityHandlerStatus,
        localIdentityMaterial
    ) {
        Log.d(
            auroraBleRuntimeLogTag,
            "BLE runtime: desired=$desiredAvailability shouldHostRuntime=$shouldHostRuntime appVisible=$isAppVisible bluetoothEnabled=${bluetoothStatus.isBluetoothEnabled} locationEnabled=${bluetoothStatus.isLocationEnabled} missingPermissions=${bluetoothStatus.missingPermissions.size} payloadSize=${advertiseRequest.payload.size} stablePeerId=${advertisedStablePeerId != null} identityHandlerAvailable=${handleIdentity != null} identityHandlerStatus=$identityHandlerStatus senderSource=$transportSenderSourceLabel activeTransportAddress=${activeTransportDeviceAddress ?: "none"} activeTransportPeer=${activeTransportPeerId ?: "none"} connectionStatus=$bleConnectionStatus establishedSessionCount=${peerSessionDiagnostics.establishedPeerIds.size} establishedSessionPeers=${peerSessionDiagnostics.establishedPeerIds.joinToString(separator = ",").ifEmpty { "none" }} sessionAliases=${peerSessionDiagnostics.canonicalPeerIdByAlias.entries.joinToString(separator = ",") { (aliasPeerId, canonicalPeerId) -> "$aliasPeerId->$canonicalPeerId" }.ifEmpty { "none" }} reachablePeerCount=${globalMeshDiagnostics.reachablePeerCount} seenGlobalMessageCount=${globalMeshDiagnostics.seenMessageCount} lastGlobalMeshResult=${globalMeshDiagnostics.lastResult}"
        )
    }

    DisposableEffect(bleScanner, shouldHostRuntime) {
        if (shouldHostRuntime) {
            discoveredAuroraPeersAggregator.clear()
            bleScanStatus = BleScanStatus.IDLE
            bleScanDiagnostics = BleScanDiagnostics()
            discoveredAuroraPeers = emptyList()
            refreshGlobalMeshDiagnostics()
            bleScanner.start(
                listener = object : BleScanner.Listener {
                    override fun onStatusChanged(status: BleScanStatus) {
                        bleScanStatus = status
                        bleScanDiagnostics = bleScanner.currentDiagnostics()
                        Log.d(
                            auroraBleRuntimeLogTag,
                            "BLE runtime scan status: $status raw=${bleScanDiagnostics.rawScanResultCount} matches=${bleScanDiagnostics.auroraDiscoveryMatchCount}"
                        )
                    }

                    override fun onDeviceDiscovered(device: BleDiscoveredDevice) {
                        bleScanDiagnostics = bleScanner.currentDiagnostics()
                        if (device.address.isBlank() || !device.hasAuroraDiscoveryPayload) {
                            return
                        }

                        discoveredAuroraPeers = discoveredAuroraPeersAggregator.update(device)
                        refreshGlobalMeshDiagnostics()
                        Log.d(
                            auroraBleRuntimeLogTag,
                            "BLE runtime peer discovered: visible=${discoveredAuroraPeers.size} peerId=${runtimeReachablePeerId(device)} address=${device.address}"
                        )
                    }
                }
            )
        } else {
            clearRuntimeDiscoveryState(stopScanner = true)
            Log.d(auroraBleRuntimeLogTag, "BLE runtime scanner stopped by app-level gating")
        }

        onDispose {
            clearRuntimeDiscoveryState(stopScanner = true)
        }
    }

    LaunchedEffect(shouldHostRuntime) {
        if (!shouldHostRuntime) {
            return@LaunchedEffect
        }

        while (true) {
            delay(BleScanAggregator.STALE_PEER_PRUNE_INTERVAL_MS)
            discoveredAuroraPeers = discoveredAuroraPeersAggregator.prune()
            refreshGlobalMeshDiagnostics()
        }
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
            transportFrameBridge.clear()
            Log.d(auroraBleRuntimeLogTag, "BLE GATT server stopped by app-level gating")
        }

        onDispose {
            bleGattServer.stop()
            bleGattServerStatus = BleGattServerStatus.STOPPED
            transportFrameBridge.clear()
        }
    }

    DisposableEffect(bleConnector, shouldHostRuntime) {
        if (!shouldHostRuntime) {
            bleConnector.disconnect()
            clearTransportConnectionState()
            Log.d(auroraBleRuntimeLogTag, "BLE transport connector stopped by app-level gating")
        }

        onDispose {
        }
    }

    return AuroraBleRuntimeState(
        bleAdvertiseStatus = bleAdvertiseStatus,
        bleGattServerStatus = bleGattServerStatus,
        bleScanStatus = bleScanStatus,
        bleScanDiagnostics = bleScanDiagnostics,
        discoveredAuroraPeers = discoveredAuroraPeers,
        bleConnector = bleConnector,
        bleConnectionStatus = bleConnectionStatus,
        activeTransportDeviceAddress = activeTransportDeviceAddress,
        activeTransportPeerId = activeTransportPeerId,
        bleTransportSender = bleTransportSender,
        transportSenderSourceLabel = transportSenderSourceLabel,
        peerSessionDiagnostics = peerSessionDiagnostics,
        globalMeshDiagnostics = globalMeshDiagnostics,
        identityHandlerStatus = identityHandlerStatus,
        lastIdentityExchangeStatus = lastIdentityExchangeStatus,
        lastIncomingMessageStatus = lastIncomingMessageStatus,
        lastConnectOnSendStatus = lastConnectOnSendStatus,
        lastGlobalMeshStatus = lastGlobalMeshStatus,
        submitGlobalMeshMessage = submitGlobalMeshMessage,
        connectToTransportPeer = connectToTransportPeer,
        disconnectTransportPeer = disconnectTransportPeer
    )
}

internal fun auroraTransportSenderSourceLabel(
    sender: BleTransportSender
): String {
    return if (sender is NoOpBleTransportSender) {
        "NoOp"
    } else {
        "Android connector-backed"
    }
}

private const val publicMeshConnectOnSendTimeoutMs = 12_000L
private const val publicMeshConnectOnSendPollIntervalMs = 100L

internal sealed interface PublicMeshConnectOnSendResult {
    data class Connected(
        val peerId: String
    ) : PublicMeshConnectOnSendResult

    data class Failed(
        val peerId: String,
        val reason: String
    ) : PublicMeshConnectOnSendResult {
        init {
            require(peerId.isNotBlank()) {
                "Public mesh connect-on-send peer id must not be blank."
            }
            require(reason.isNotBlank()) {
                "Public mesh connect-on-send failure reason must not be blank."
            }
        }
    }
}

internal fun runtimeReachablePeerId(
    device: BleDiscoveredDevice
): String {
    val stablePeerId = device.stablePeerId
    if (stablePeerId != null) {
        return stablePeerId.toByteArray().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }
    }

    return device.address.trim()
}

internal fun discoveredAuroraPeerIds(
    devices: List<BleDiscoveredDevice>
): List<String> {
    return devices
        .filter { device ->
            device.hasAuroraDiscoveryPayload &&
                device.address.isNotBlank()
        }
        .map(::runtimeReachablePeerId)
        .distinct()
        .sorted()
}

internal fun choosePublicMeshConnectOnSendPeer(
    reachablePeers: List<BleDiscoveredDevice>,
    preferredPeerId: String? = null
): BleDiscoveredDevice? {
    val sanitizedPreferredPeerId = preferredPeerId?.trim()?.takeIf { it.isNotEmpty() }
    val candidates = reachablePeers
        .filter { device ->
            device.hasAuroraDiscoveryPayload &&
                device.address.isNotBlank() &&
                device.isConnectable != false
        }
        .sortedWith(
            compareBy<BleDiscoveredDevice>(
                { runtimeReachablePeerId(it) },
                { it.address.trim() }
            )
        )
    if (candidates.isEmpty()) {
        return null
    }

    if (sanitizedPreferredPeerId == null) {
        return candidates.first()
    }

    return candidates.firstOrNull { device ->
        runtimeReachablePeerId(device) == sanitizedPreferredPeerId
    } ?: candidates.first()
}

internal suspend fun submitPublicGlobalMeshMessage(
    message: OutgoingChatMessage,
    senderId: String,
    coordinator: GlobalMeshDeliveryCoordinator,
    transportSender: BleTransportSender?,
    activeTransportPeerId: String?,
    isActiveTransportConnected: Boolean,
    reachablePeers: List<BleDiscoveredDevice>,
    connectToReachablePeer: suspend (BleDiscoveredDevice) -> PublicMeshConnectOnSendResult,
    onConnectOnSendStatusChanged: (String) -> Unit = {}
): GlobalMeshDeliveryResult {
    val sanitizedActiveTransportPeerId = activeTransportPeerId?.trim()?.takeIf { it.isNotEmpty() }
    if (isActiveTransportConnected && sanitizedActiveTransportPeerId != null) {
        onConnectOnSendStatusChanged(
            "Public mesh connect-on-send: not needed. Active peer $sanitizedActiveTransportPeerId is already connected."
        )
        return coordinator.submitLocalMessage(
            message = message,
            senderId = senderId,
            transportSender = transportSender,
            activeTransportPeerId = sanitizedActiveTransportPeerId
        )
    }

    val targetPeer = choosePublicMeshConnectOnSendPeer(
        reachablePeers = reachablePeers,
        preferredPeerId = sanitizedActiveTransportPeerId
    ) ?: run {
        onConnectOnSendStatusChanged(
            "Public mesh connect-on-send: no reachable Aurora peer."
        )
        return GlobalMeshDeliveryResult.NoReachablePeers
    }
    val targetPeerId = runtimeReachablePeerId(targetPeer)
    onConnectOnSendStatusChanged(
        "Public mesh connect-on-send: pending for $targetPeerId."
    )

    return when (val connectResult = connectToReachablePeer(targetPeer)) {
        is PublicMeshConnectOnSendResult.Connected -> {
            onConnectOnSendStatusChanged(
                "Public mesh connect-on-send: succeeded for ${connectResult.peerId}."
            )
            coordinator.submitLocalMessage(
                message = message,
                senderId = senderId,
                transportSender = transportSender,
                activeTransportPeerId = connectResult.peerId
            )
        }
        is PublicMeshConnectOnSendResult.Failed -> {
            onConnectOnSendStatusChanged(
                "Public mesh connect-on-send: failed for ${connectResult.peerId}."
            )
            GlobalMeshDeliveryResult.ConnectOnSendFailed(
                peerId = connectResult.peerId,
                reason = connectResult.reason
            )
        }
    }
}

internal fun globalMeshStatusText(
    result: GlobalMeshDeliveryResult
): String {
    return when (result) {
        is GlobalMeshDeliveryResult.QueuedToActivePeer ->
            "Global mesh queued to active peer ${result.peerId}."

        GlobalMeshDeliveryResult.NoReachablePeers ->
            "No reachable Aurora peers."

        GlobalMeshDeliveryResult.SenderUnavailable ->
            "Mesh transport sender unavailable."

        is GlobalMeshDeliveryResult.ConnectOnSendFailed ->
            "Public mesh connect-on-send failed for ${result.peerId}: ${result.reason}"

        is GlobalMeshDeliveryResult.SkippedDuplicate ->
            "Global mesh relay skipped duplicate ${result.messageId}."

        is GlobalMeshDeliveryResult.SkippedSourcePeer ->
            "Global mesh relay skipped source peer ${result.peerId}."

        is GlobalMeshDeliveryResult.SkippedTtlExpired ->
            "Global mesh relay stopped at TTL for ${result.messageId}."

        is GlobalMeshDeliveryResult.Failed ->
            "Global mesh failed: ${result.reason}"
    }
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

internal sealed interface LocalPeerSessionIdentityMaterialLoadResult {
    data class Ready(
        val material: LocalPeerSessionIdentityMaterial,
        val privateKeyStatus: PrivateKeyStatus
    ) : LocalPeerSessionIdentityMaterialLoadResult

    enum class PrivateKeyStatus {
        LOADED,
        GENERATED,
        REGENERATED_INVALID_EXISTING_KEY
    }

    sealed interface Unavailable : LocalPeerSessionIdentityMaterialLoadResult {
        val reason: String
    }

    data class PublicKeyUnavailable(
        override val reason: String = "Local agreement public key unavailable."
    ) : Unavailable

    data class PrivateKeyUnavailable(
        override val reason: String
    ) : Unavailable

    data class InvalidMaterial(
        override val reason: String = "Local agreement identity material invalid."
    ) : Unavailable
}

internal fun loadLocalPeerSessionIdentityMaterialResult(
    ensureAgreementKey: () -> Unit = {
        AndroidKeystoreLocalAgreementKey.ensureAgreementKey()
    },
    loadPublicKeyBytes: () -> ByteArray? = {
        AndroidKeystoreLocalAgreementPublicKey.loadAgreementPublicKeyBytesOrNull()
    },
    ensurePrivateKey: () -> PrivateKeyLoadResult = {
        AndroidKeystoreLocalAgreementKey.ensureAgreementPrivateKey()
    }
): LocalPeerSessionIdentityMaterialLoadResult {
    val ensuredPublicKey = runCatching {
        ensureAgreementKey()
    }.getOrNull()
    if (ensuredPublicKey == null) {
        return LocalPeerSessionIdentityMaterialLoadResult.PublicKeyUnavailable()
    }

    val publicKeyBytes = runCatching(loadPublicKeyBytes).getOrNull()
        ?.takeIf { it.isNotEmpty() }
        ?: return LocalPeerSessionIdentityMaterialLoadResult.PublicKeyUnavailable()
    val privateKeyResult = runCatching(ensurePrivateKey).getOrElse { error ->
        return LocalPeerSessionIdentityMaterialLoadResult.PrivateKeyUnavailable(
            reason = "Local agreement private key unavailable: ${error::class.java.simpleName}"
        )
    }
    val privateKey = when (privateKeyResult) {
        is PrivateKeyLoadResult.Ready -> privateKeyResult.privateKey
        is PrivateKeyLoadResult.RegeneratedAfterInvalidExistingKey -> privateKeyResult.privateKey
        is PrivateKeyLoadResult.KeystoreUnavailable -> {
            return LocalPeerSessionIdentityMaterialLoadResult.PrivateKeyUnavailable(
                reason = "Local agreement private key unavailable: ${privateKeyResult.reason}"
            )
        }
        is PrivateKeyLoadResult.GenerationFailed -> {
            return LocalPeerSessionIdentityMaterialLoadResult.PrivateKeyUnavailable(
                reason = "Local agreement private key unavailable: ${privateKeyResult.reason}"
            )
        }
        is PrivateKeyLoadResult.LoadFailed -> {
            return LocalPeerSessionIdentityMaterialLoadResult.PrivateKeyUnavailable(
                reason = "Local agreement private key unavailable: ${privateKeyResult.reason}"
            )
        }
        is PrivateKeyLoadResult.InvalidExistingKey -> {
            return LocalPeerSessionIdentityMaterialLoadResult.PrivateKeyUnavailable(
                reason = "Local agreement private key unavailable: ${privateKeyResult.reason}"
            )
        }
    }
    val privateKeyStatus = when (privateKeyResult) {
        is PrivateKeyLoadResult.Ready -> {
            if (privateKeyResult.wasGenerated) {
                LocalPeerSessionIdentityMaterialLoadResult.PrivateKeyStatus.GENERATED
            } else {
                LocalPeerSessionIdentityMaterialLoadResult.PrivateKeyStatus.LOADED
            }
        }
        is PrivateKeyLoadResult.RegeneratedAfterInvalidExistingKey -> {
            LocalPeerSessionIdentityMaterialLoadResult.PrivateKeyStatus.REGENERATED_INVALID_EXISTING_KEY
        }
        is PrivateKeyLoadResult.KeystoreUnavailable,
        is PrivateKeyLoadResult.GenerationFailed,
        is PrivateKeyLoadResult.LoadFailed,
        is PrivateKeyLoadResult.InvalidExistingKey -> {
            error("Unhandled private key status mapping.")
        }
    }

    return runCatching {
        LocalPeerSessionIdentityMaterialLoadResult.Ready(
            LocalPeerSessionIdentityMaterial(
                publicKeyBytes = publicKeyBytes,
                privateKey = privateKey
            ),
            privateKeyStatus = privateKeyStatus
        )
    }.getOrElse {
        LocalPeerSessionIdentityMaterialLoadResult.InvalidMaterial(
            reason = "Local agreement identity material invalid."
        )
    }
}

internal fun loadLocalPeerSessionIdentityMaterialOrNull(
    ensureAgreementKey: () -> Unit = {
        AndroidKeystoreLocalAgreementKey.ensureAgreementKey()
    },
    loadPublicKeyBytes: () -> ByteArray? = {
        AndroidKeystoreLocalAgreementPublicKey.loadAgreementPublicKeyBytesOrNull()
    },
    ensurePrivateKey: () -> PrivateKeyLoadResult = {
        AndroidKeystoreLocalAgreementKey.ensureAgreementPrivateKey()
    }
): LocalPeerSessionIdentityMaterial? {
    return when (
        val result = loadLocalPeerSessionIdentityMaterialResult(
            ensureAgreementKey = ensureAgreementKey,
            loadPublicKeyBytes = loadPublicKeyBytes,
            ensurePrivateKey = ensurePrivateKey
        )
    ) {
        is LocalPeerSessionIdentityMaterialLoadResult.Ready -> result.material
        is LocalPeerSessionIdentityMaterialLoadResult.Unavailable -> null
    }
}

internal fun createAuroraIdentityHandlerOrNull(
    localIdentity: LocalPeerSessionIdentityMaterial?,
    registry: PeerSessionRegistry
): ((IncomingTransportMessage) -> PeerIdentityExchangeHandlingResult)? {
    val identity = localIdentity ?: return null

    return { message ->
        PeerIdentityExchangeHandler.handle(
            frame = message.frame,
            localIdentity = identity,
            registry = registry
        )
    }
}

internal fun createAuroraBleTransportFrameReceiver(
    stateHolder: AuroraStateHolder,
    sessionMaterialProvider: IncomingSessionMaterialProvider = NoOpIncomingSessionMaterialProvider,
    handleIdentity: ((IncomingTransportMessage) -> PeerIdentityExchangeHandlingResult)? = null,
    identityHandlingUnavailableReason: String =
        "Local agreement identity material unavailable for incoming identity exchange."
): BleTransportFrameReceiver {
    return BleTransportFrameReceiver(processFrames = { frames ->
        IncomingTransportFrameProcessor.process(
            frames = frames,
            sessionMaterialProvider = sessionMaterialProvider,
            stateHolder = stateHolder,
            handleIdentity = handleIdentity,
            identityHandlingUnavailableReason = identityHandlingUnavailableReason
        )
    })
}

internal fun auroraIdentityHandlerStatusText(
    loadResult: LocalPeerSessionIdentityMaterialLoadResult,
    isHandlerReady: Boolean
): String {
    return when (loadResult) {
        is LocalPeerSessionIdentityMaterialLoadResult.Ready -> {
            if (isHandlerReady) {
                when (loadResult.privateKeyStatus) {
                    LocalPeerSessionIdentityMaterialLoadResult.PrivateKeyStatus.LOADED ->
                        "Identity handler ready. Local agreement private key loaded."
                    LocalPeerSessionIdentityMaterialLoadResult.PrivateKeyStatus.GENERATED ->
                        "Identity handler ready. Local agreement private key generated."
                    LocalPeerSessionIdentityMaterialLoadResult.PrivateKeyStatus.REGENERATED_INVALID_EXISTING_KEY ->
                        "Identity handler ready. Local agreement key was invalid and regenerated."
                }
            } else {
                "Identity session handler unavailable."
            }
        }
        is LocalPeerSessionIdentityMaterialLoadResult.Unavailable -> loadResult.reason
    }
}

internal fun identityHandlingUnavailableReason(
    loadResult: LocalPeerSessionIdentityMaterialLoadResult,
    isHandlerReady: Boolean
): String {
    return when (loadResult) {
        is LocalPeerSessionIdentityMaterialLoadResult.Ready -> {
            if (isHandlerReady) {
                "Identity session handler unavailable."
            } else {
                "Identity session handler unavailable."
            }
        }
        is LocalPeerSessionIdentityMaterialLoadResult.Unavailable -> loadResult.reason
    }
}

internal fun identityExchangeRuntimeStatusText(
    result: BleTransportReceiveResult
): String? {
    return when (result) {
        is BleTransportReceiveResult.Processed -> {
            when (val processingResult = result.processingResult) {
                is IncomingTransportFrameProcessingResult.IdentityHandled -> {
                    when (val handlingResult = processingResult.handlingResult) {
                        is PeerIdentityExchangeHandlingResult.Established ->
                            "Identity received from ${handlingResult.peerId}. Send yours back from this device."
                        is PeerIdentityExchangeHandlingResult.InvalidIdentityMessage ->
                            "Identity exchange invalid: ${handlingResult.reason}"
                        is PeerIdentityExchangeHandlingResult.InvalidRemotePublicKey ->
                            "Identity exchange remote key invalid: ${handlingResult.reason}"
                        is PeerIdentityExchangeHandlingResult.SelfPeer ->
                            "Identity exchange ignored: ${handlingResult.reason}"
                        is PeerIdentityExchangeHandlingResult.KeyAgreementFailed ->
                            "Identity exchange failed during key agreement: ${handlingResult.reason}"
                        is PeerIdentityExchangeHandlingResult.KeyDerivationFailed ->
                            "Identity exchange failed during key derivation: ${handlingResult.reason}"
                        PeerIdentityExchangeHandlingResult.IgnoredNonIdentityFrame -> null
                    }
                }
                is IncomingTransportFrameProcessingResult.IdentityHandlingUnavailable ->
                    processingResult.reason
                is IncomingTransportFrameProcessingResult.Received -> null
            }
        }
        is BleTransportReceiveResult.ProcessorFailed -> {
            when (val receiveResult = result.processingResult.receiveResult) {
                is IncomingTransportReceiveResult.Received -> null
                is IncomingTransportReceiveResult.IncompleteChunks ->
                    "Incoming transport incomplete: ${receiveResult.reason}"
                is IncomingTransportReceiveResult.InvalidEnvelope ->
                    "Incoming transport invalid envelope: ${receiveResult.reason}"
                is IncomingTransportReceiveResult.SessionMaterialUnavailable ->
                    "Incoming secure session unavailable: ${receiveResult.reason}"
                is IncomingTransportReceiveResult.UnsupportedSender ->
                    "Incoming sender unsupported: ${receiveResult.reason}"
                is IncomingTransportReceiveResult.InvalidSenderIdentity ->
                    "Incoming sender identity invalid: ${receiveResult.reason}"
                is IncomingTransportReceiveResult.DecryptFailed ->
                    "Incoming transport decryption failed: ${receiveResult.reason}"
                is IncomingTransportReceiveResult.InvalidFrame ->
                    "Incoming message frame invalid: ${receiveResult.reason}"
            }
        }
        is BleTransportReceiveResult.InvalidChunk ->
            "Incoming transport chunk invalid: ${result.reason}"
        is BleTransportReceiveResult.BufferOverflow ->
            "Incoming transport buffer overflow: ${result.reason}"
        is BleTransportReceiveResult.Buffered,
        is BleTransportReceiveResult.DuplicateChunk -> null
    }
}

internal fun incomingMessageRuntimeStatusText(
    result: BleTransportReceiveResult
): String? {
    return when (result) {
        is BleTransportReceiveResult.Processed -> {
            when (val processingResult = result.processingResult) {
                is IncomingTransportFrameProcessingResult.Received -> {
                    when (val ingestionResult = processingResult.ingestionResult) {
                        is Appended -> when (processingResult.message.frame.type) {
                            MessageFrameType.GLOBAL_TEXT ->
                                "Received public global message from ${ingestionResult.message.senderId}."
                            MessageFrameType.PRIVATE_TEXT ->
                                "Received encrypted private message from ${ingestionResult.message.senderId}."
                            MessageFrameType.IDENTITY_EXCHANGE,
                            MessageFrameType.CONTROL ->
                                "Received message from ${ingestionResult.message.senderId}."
                        }
                        is Duplicate -> when (processingResult.message.frame.type) {
                            MessageFrameType.GLOBAL_TEXT ->
                                "Duplicate public global message ignored from ${processingResult.message.frame.senderId}."
                            MessageFrameType.PRIVATE_TEXT ->
                                "Duplicate encrypted private message ignored from ${processingResult.message.frame.senderId}."
                            MessageFrameType.IDENTITY_EXCHANGE,
                            MessageFrameType.CONTROL ->
                                "Duplicate message ignored from ${processingResult.message.frame.senderId}."
                        }
                        is UnsupportedThread ->
                            "Incoming message uses an unsupported thread."
                        is UnsupportedType ->
                            "Incoming message uses an unsupported type."
                    }
                }
                is IncomingTransportFrameProcessingResult.IdentityHandled,
                is IncomingTransportFrameProcessingResult.IdentityHandlingUnavailable -> null
            }
        }
        is BleTransportReceiveResult.ProcessorFailed -> {
            when (val receiveResult = result.processingResult.receiveResult) {
                is IncomingTransportReceiveResult.Received -> null
                is IncomingTransportReceiveResult.IncompleteChunks ->
                    "Incoming message is incomplete: ${receiveResult.reason}"
                is IncomingTransportReceiveResult.InvalidEnvelope ->
                    "Incoming encrypted envelope is invalid: ${receiveResult.reason}"
                is IncomingTransportReceiveResult.SessionMaterialUnavailable ->
                    "Incoming secure session unavailable: ${receiveResult.reason}"
                is IncomingTransportReceiveResult.UnsupportedSender ->
                    "Incoming sender unsupported: ${receiveResult.reason}"
                is IncomingTransportReceiveResult.InvalidSenderIdentity ->
                    "Incoming sender identity invalid: ${receiveResult.reason}"
                is IncomingTransportReceiveResult.DecryptFailed ->
                    "Incoming encrypted message decryption failed: ${receiveResult.reason}"
                is IncomingTransportReceiveResult.InvalidFrame ->
                    "Incoming message frame is invalid: ${receiveResult.reason}"
            }
        }
        is BleTransportReceiveResult.InvalidChunk ->
            "Incoming transport chunk is invalid: ${result.reason}"
        is BleTransportReceiveResult.BufferOverflow ->
            "Incoming transport buffer overflow: ${result.reason}"
        is BleTransportReceiveResult.Buffered,
        is BleTransportReceiveResult.DuplicateChunk -> null
    }
}

private fun logIdentityExchangeReceiveResult(
    result: BleTransportReceiveResult
) {
    when (result) {
        is BleTransportReceiveResult.Processed -> {
            when (val processingResult = result.processingResult) {
                is IncomingTransportFrameProcessingResult.IdentityHandled -> {
                    Log.d(
                        auroraBleRuntimeLogTag,
                        "Incoming IDENTITY_EXCHANGE frame handled: sender=${processingResult.message.frame.senderId} result=${processingResult.handlingResult}"
                    )
                }
                is IncomingTransportFrameProcessingResult.IdentityHandlingUnavailable -> {
                    Log.w(
                        auroraBleRuntimeLogTag,
                        "Incoming IDENTITY_EXCHANGE frame detected but local handler unavailable: sender=${processingResult.message.frame.senderId} reason=${processingResult.reason}"
                    )
                }
                is IncomingTransportFrameProcessingResult.Received -> Unit
            }
        }
        is BleTransportReceiveResult.ProcessorFailed -> {
            Log.w(
                auroraBleRuntimeLogTag,
                "Incoming transport processing failed: ${result.processingResult.receiveResult}"
            )
        }
        is BleTransportReceiveResult.InvalidChunk -> {
            Log.w(
                auroraBleRuntimeLogTag,
                "Incoming transport chunk invalid: ${result.reason}"
            )
        }
        is BleTransportReceiveResult.BufferOverflow -> {
            Log.w(
                auroraBleRuntimeLogTag,
                "Incoming transport buffer overflow: ${result.reason}"
            )
        }
        is BleTransportReceiveResult.Buffered,
        is BleTransportReceiveResult.DuplicateChunk -> Unit
    }
}
