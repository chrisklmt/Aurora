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
import gr.hua.aurora.ble.transport.AndroidBleTransportSender
import gr.hua.aurora.ble.transport.BleGattTransportFrame
import gr.hua.aurora.ble.transport.BleGattTransportFrameWriter
import gr.hua.aurora.ble.transport.BleTransportFrameBridge
import gr.hua.aurora.ble.transport.BleTransportFrameReceiver
import gr.hua.aurora.ble.transport.BleTransportReceiveResult
import gr.hua.aurora.ble.transport.BleTransportSendResult
import gr.hua.aurora.ble.transport.BleTransportSender
import gr.hua.aurora.identity.AndroidKeystoreLocalAgreementKey
import gr.hua.aurora.identity.AndroidKeystoreLocalAgreementKey.LocalIdentityClearResult
import gr.hua.aurora.identity.AndroidKeystoreLocalAgreementKey.PrivateKeyLoadResult
import gr.hua.aurora.identity.AndroidKeystoreLocalAgreementPublicKey
import gr.hua.aurora.identity.LocalKeyIdentity
import gr.hua.aurora.model.OutgoingChatMessage
import gr.hua.aurora.protocol.EncryptedMessageEnvelope
import gr.hua.aurora.protocol.EncryptedMessageEnvelopeBuilder
import gr.hua.aurora.protocol.IncomingSessionMaterialProvider
import gr.hua.aurora.protocol.IncomingTransportMessage
import gr.hua.aurora.protocol.IncomingTransportReceiveResult
import gr.hua.aurora.protocol.EncryptedMessageRelayMetadata
import gr.hua.aurora.protocol.GlobalMeshDeliveryCoordinator
import gr.hua.aurora.protocol.GlobalMeshDeliveryResult
import gr.hua.aurora.protocol.GlobalMeshDiagnostics
import gr.hua.aurora.protocol.LocalPeerSessionIdentityMaterial
import gr.hua.aurora.protocol.MessageFrame
import gr.hua.aurora.protocol.MessageFrameCodec
import gr.hua.aurora.protocol.MessageFrameTransportSendUseCase
import gr.hua.aurora.protocol.MessageFrameType
import gr.hua.aurora.protocol.NoOpIncomingSessionMaterialProvider
import gr.hua.aurora.protocol.OutgoingMessageFrameBuilder
import gr.hua.aurora.protocol.OutgoingMessageFrameResolver
import gr.hua.aurora.protocol.PeerIdentityExchangeHandler
import gr.hua.aurora.protocol.PeerIdentityExchangeHandlingResult
import gr.hua.aurora.protocol.PeerIdentityExchangeMessage
import gr.hua.aurora.protocol.PeerIdentityExchangeSendResult
import gr.hua.aurora.protocol.PeerIdentityExchangeSendUseCase
import gr.hua.aurora.protocol.PeerSessionRegistry
import gr.hua.aurora.protocol.PeerSessionRegistryDiagnostics
import gr.hua.aurora.protocol.PeerSessionPeerId
import gr.hua.aurora.protocol.PrivateChatMessageSendResult
import gr.hua.aurora.protocol.PrivateChatMessagePayload
import gr.hua.aurora.protocol.PrivateChatMessagePayloadCodec
import gr.hua.aurora.protocol.PrivateChatMessageSendUseCase
import gr.hua.aurora.protocol.PreparedPrivateChatTransportFrame
import gr.hua.aurora.protocol.PrivateChatTransportFrameFactory
import gr.hua.aurora.protocol.SeenMessageIdCache
import gr.hua.aurora.transport.processing.IncomingTransportFrameProcessor
import gr.hua.aurora.transport.processing.IncomingTransportFrameProcessingResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapDecision
import gr.hua.aurora.transport.hybrid.HybridBootstrapDiagnostics
import gr.hua.aurora.transport.hybrid.HybridBootstrapDiagnosticsFormatter
import gr.hua.aurora.transport.hybrid.HybridBootstrapDecisionProvider
import gr.hua.aurora.transport.hybrid.HybridTransportControlStore
import gr.hua.aurora.transport.hybrid.InMemoryHybridTransportControlStore
import gr.hua.aurora.state.IncomingMessageIngestionResult.Appended
import gr.hua.aurora.state.IncomingMessageIngestionResult.Duplicate
import gr.hua.aurora.state.IncomingMessageIngestionResult.UnsupportedThread
import gr.hua.aurora.state.IncomingMessageIngestionResult.UnsupportedType
import gr.hua.aurora.wifidirect.model.WifiDirectPeer
import gr.hua.aurora.wifidirect.frame.WifiDirectTransportFrame
import gr.hua.aurora.wifidirect.runtime.RememberedWifiDirectRuntimeStatusState
import gr.hua.aurora.wifidirect.runtime.WifiDirectRolePreference
import gr.hua.aurora.wifidirect.runtime.WifiDirectRuntimeStatus
import gr.hua.aurora.wifidirect.transport.WifiDirectTransportSendResult
import gr.hua.aurora.wifidirect.transport.WifiDirectTransportSender
import java.security.PrivateKey
import java.nio.charset.StandardCharsets.UTF_8
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
    val wifiDirectRuntimeStatus: WifiDirectRuntimeStatus,
    val refreshWifiDirectStatus: () -> Unit,
    val startWifiDirectDiscovery: () -> Unit,
    val stopWifiDirectDiscovery: () -> Unit,
    val connectToWifiDirectPeer: (
        WifiDirectPeer,
        WifiDirectRolePreference
    ) -> Unit,
    val disconnectWifiDirectPeer: () -> Unit,
    val receiveWifiDirectDebugTransportFrame: (BleGattTransportFrame) -> BleTransportReceiveResult,
    val peerSessionDiagnostics: PeerSessionRegistryDiagnostics,
    val globalMeshDiagnostics: GlobalMeshDiagnostics,
    val identityHandlerStatus: String,
    val lastIdentityExchangeStatus: String?,
    val lastIncomingMessageStatus: String?,
    val lastConnectOnSendStatus: String?,
    val lastGlobalMeshStatus: String?,
    val submitGlobalMeshMessage: suspend (OutgoingChatMessage, String) -> GlobalMeshDeliveryResult,
    val submitPrivateChatMessage: suspend (OutgoingChatMessage, String, String) -> PrivateChatTransportSubmission,
    val exchangeIdentityWithPeer: suspend (BleDiscoveredDevice, String?) -> PeerIdentityExchangeSendResult,
    val connectToTransportPeer: (String, String?) -> Unit,
    val disconnectTransportPeer: () -> Unit,
    val clearSessionForPeer: (String) -> Unit,
    val resetLocalIdentityAndSessions: () -> Unit
) {
    internal var submitGlobalMeshMessageWithOptionalWifiDirect:
        suspend (OutgoingChatMessage, String, WifiDirectTransportSender?) -> GlobalMeshDeliveryResult =
        { message, senderId, _ ->
            submitGlobalMeshMessage(message, senderId)
        }
}

data class PrivateChatTransportSubmission(
    val result: PrivateChatMessageSendResult,
    val preparedTransportFrame: PreparedPrivateChatTransportFrame? = null
)

internal fun shouldRunAuroraBleRuntime(
    desiredAvailability: AuroraAvailabilityPreference,
    bluetoothStatus: BluetoothPermissionStatus,
    isAppVisible: Boolean
): Boolean {
    return isAppVisible && gr.hua.aurora.ui.components.buildAuroraAvailabilityUiState(
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
    var runtimeGeneration by remember {
        mutableStateOf(0)
    }
    val bleScanner = remember(bluetoothAdapter) {
        AndroidBleScanner(bluetoothAdapter)
    }
    val discoveredAuroraPeersAggregator = remember(runtimeGeneration) {
        BleScanAggregator()
    }
    val resolvedTransportFrameWriter = transportFrameWriter ?: bleConnector
    val bleTransportSender = remember(resolvedTransportFrameWriter) {
        createAuroraBleTransportSender(resolvedTransportFrameWriter)
    }
    val transportSenderSourceLabel = remember(bleTransportSender) {
        auroraTransportSenderSourceLabel(bleTransportSender)
    }
    val wifiDirectRuntimeState =
        gr.hua.aurora.wifidirect.runtime.rememberWifiDirectRuntimeStatusState()
    val wifiDirectRuntimeStatus = wifiDirectRuntimeState.status
    var lastIdentityExchangeStatus by remember(runtimeGeneration) {
        mutableStateOf<String?>(null)
    }
    var lastIncomingMessageStatus by remember(runtimeGeneration) {
        mutableStateOf<String?>(null)
    }
    var lastConnectOnSendStatus by remember(runtimeGeneration) {
        mutableStateOf<String?>(null)
    }
    var lastGlobalMeshStatus by remember(runtimeGeneration) {
        mutableStateOf<String?>(null)
    }
    var bleConnectionStatus by remember(runtimeGeneration) {
        mutableStateOf(BleConnectionStatus.IDLE)
    }
    var activeTransportDeviceAddress by remember(runtimeGeneration) {
        mutableStateOf<String?>(null)
    }
    var activeTransportPeerId by remember(runtimeGeneration) {
        mutableStateOf<String?>(null)
    }
    var bleScanStatus by remember(runtimeGeneration) {
        mutableStateOf(BleScanStatus.IDLE)
    }
    var bleScanDiagnostics by remember(runtimeGeneration) {
        mutableStateOf(BleScanDiagnostics())
    }
    var discoveredAuroraPeers by remember(runtimeGeneration) {
        mutableStateOf(emptyList<BleDiscoveredDevice>())
    }
    val peerSessionRegistry = remember(runtimeGeneration) {
        PeerSessionRegistry()
    }
    var peerSessionDiagnostics by remember(runtimeGeneration) {
        mutableStateOf(peerSessionRegistry.diagnosticsSnapshot())
    }
    val globalMeshDeliveryCoordinator = remember(runtimeGeneration) {
        GlobalMeshDeliveryCoordinator()
    }
    val privateMeshSeenMessageIds = remember(runtimeGeneration) {
        SeenMessageIdCache()
    }
    var globalMeshDiagnostics by remember(runtimeGeneration) {
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
    val localIdentityMaterialLoadResult = remember(runtimeGeneration) {
        loadLocalPeerSessionIdentityMaterialResult()
    }
    val localIdentityMaterial = (
        localIdentityMaterialLoadResult as? LocalPeerSessionIdentityMaterialLoadResult.Ready
    )?.material
    val localPeerId = remember(localIdentityMaterial) {
        localIdentityMaterial?.let { identity ->
            PeerSessionPeerId.deriveFromPublicKey(identity.publicKeyBytes())
        }
    }
    var pendingIdentityRecoveryReplyPeerId by remember(runtimeGeneration) {
        mutableStateOf<String?>(null)
    }
    val incomingSessionMaterialProvider = remember(peerSessionRegistry) {
        peerSessionRegistry as IncomingSessionMaterialProvider
    }
    val handleIdentity = remember(localIdentityMaterial, peerSessionRegistry) {
        createAuroraIdentityHandlerOrNull(
            stateHolder = stateHolder,
            localIdentity = localIdentityMaterial,
            registry = peerSessionRegistry,
            onIdentityEstablished = { peerId, shouldReply ->
                if (shouldReply) {
                    pendingIdentityRecoveryReplyPeerId = peerId
                }
            }
        )
    }
    val identityHandlerStatus = remember(localIdentityMaterialLoadResult, handleIdentity) {
        auroraIdentityHandlerStatusText(
            loadResult = localIdentityMaterialLoadResult,
            isHandlerReady = handleIdentity != null
        )
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

    LaunchedEffect(
        pendingIdentityRecoveryReplyPeerId,
        discoveredAuroraPeers,
        bleConnectionStatus,
        activeTransportPeerId,
        activeTransportDeviceAddress
    ) {
        val targetPeerId = pendingIdentityRecoveryReplyPeerId ?: return@LaunchedEffect
        pendingIdentityRecoveryReplyPeerId = null
        val privateChatProposalId = stateHolder.privateChatIdentityForPeerId(targetPeerId)
            ?.localProposalId
        val replyResult = submitIdentityExchangeToTarget(
            targetPeerId = targetPeerId,
            transportSender = bleTransportSender,
            bleConnectionStatus = bleConnectionStatus,
            activeTransportPeerId = activeTransportPeerId,
            activeTransportDeviceAddress = activeTransportDeviceAddress,
            reachablePeers = discoveredAuroraPeers,
            connectToReachablePeer = ::connectToReachablePeerAndAwait,
            localIdentityMaterial = loadLocalPeerIdentityExchangePublicMaterialOrNull(),
            privateChatProposalId = privateChatProposalId
        )
        lastIdentityExchangeStatus = identityExchangeRecoveryStatusText(
            peerId = targetPeerId,
            result = replyResult
        )
    }

    val hybridTransportControlStore = remember(
        stateHolder
    ) {
        InMemoryHybridTransportControlStore()
    }
    val hybridBootstrapDecisionProvider = remember(hybridTransportControlStore) {
        HybridBootstrapDecisionProvider(hybridTransportControlStore)
    }
    val initialHybridBootstrapDecision = remember(
        runtimeGeneration,
        hybridBootstrapDecisionProvider
    ) {
        hybridBootstrapDecisionProvider.currentDecision()
    }
    var latestHybridBootstrapDecision by remember(
        runtimeGeneration
    ) {
        mutableStateOf(initialHybridBootstrapDecision)
    }
    var latestHybridBootstrapDiagnostics by remember(
        runtimeGeneration
    ) {
        mutableStateOf(
            HybridBootstrapDiagnosticsFormatter.format(initialHybridBootstrapDecision)
        )
    }
    val transportFrameReceiver = remember(
        stateHolder,
        incomingSessionMaterialProvider,
        hybridTransportControlStore,
        handleIdentity,
        identityHandlerStatus
    ) {
        createAuroraBleTransportFrameReceiver(
            stateHolder = stateHolder,
            sessionMaterialProvider = incomingSessionMaterialProvider,
            hybridControlStore = hybridTransportControlStore,
            handleIdentity = handleIdentity,
            identityHandlingUnavailableReason = identityHandlingUnavailableReason(
                loadResult = localIdentityMaterialLoadResult,
                isHandlerReady = handleIdentity != null
            )
        )
    }
    fun handleIncomingTransportReceiveResult(
        source: String,
        result: BleTransportReceiveResult
    ) {
        Log.d(auroraBleRuntimeLogTag, "$source transport receive result: $result")
        peerSessionDiagnostics = peerSessionRegistry.diagnosticsSnapshot()
        identityExchangeRuntimeStatusText(result)?.let { statusText ->
            lastIdentityExchangeStatus = statusText
        }
        hybridBootstrapDecisionAfterReceiveOrNull(
            result = result,
            provider = hybridBootstrapDecisionProvider
        )?.let { decision ->
            latestHybridBootstrapDecision = decision
            latestHybridBootstrapDiagnostics = HybridBootstrapDiagnosticsFormatter.format(decision)
        }
        incomingMessageRuntimeStatusText(result)?.let { statusText ->
            lastIncomingMessageStatus = statusText
        }
        if (
            result is BleTransportReceiveResult.Processed &&
            (
                result.processingResult is IncomingTransportFrameProcessingResult.Received ||
                    result.processingResult is IncomingTransportFrameProcessingResult.RelayOnlyEncrypted
                )
        ) {
            val immediateSourcePeerId = runtimeSourcePeerId(
                sourceDeviceAddress = result.sourceDeviceAddress,
                activeTransportPeerId = activeTransportPeerId,
                activeTransportDeviceAddress = activeTransportDeviceAddress,
                reachablePeers = discoveredAuroraPeers
            )
            when (val processingResult = result.processingResult) {
                is IncomingTransportFrameProcessingResult.Received -> {
                    when (processingResult.message.frame.type) {
                        MessageFrameType.GLOBAL_TEXT -> {
                            runtimeScope.launch {
                                val meshResult = relayReceivedPublicMeshMessage(
                                    message = processingResult.message,
                                    ingestionResult = processingResult.ingestionResult,
                                    coordinator = globalMeshDeliveryCoordinator,
                                    transportSender = bleTransportSender,
                                    activeTransportPeerId = activeTransportPeerId,
                                    activeTransportDeviceAddress = activeTransportDeviceAddress,
                                    isActiveTransportConnected = bleConnectionStatus == BleConnectionStatus.CONNECTED,
                                    localPeerId = localPeerId,
                                    reachablePeers = discoveredAuroraPeers,
                                    immediateSourcePeerId = immediateSourcePeerId,
                                    immediateSourceDeviceAddress = result.sourceDeviceAddress,
                                    connectToReachablePeer = ::connectToReachablePeerAndAwait,
                                    onConnectOnSendStatusChanged = { statusText ->
                                        lastConnectOnSendStatus = statusText
                                    }
                                )
                                refreshGlobalMeshDiagnostics()
                                lastGlobalMeshStatus = globalMeshStatusText(meshResult)
                            }
                        }
                        MessageFrameType.PRIVATE_TEXT -> {
                            val relayEnvelope = processingResult.message.relayEnvelope
                            if (relayEnvelope != null) {
                                runtimeScope.launch {
                                    relayPrivateEncryptedMessage(
                                        envelope = relayEnvelope,
                                        seenMessageIds = privateMeshSeenMessageIds,
                                        transportSender = bleTransportSender,
                                        activeTransportPeerId = activeTransportPeerId,
                                        activeTransportDeviceAddress = activeTransportDeviceAddress,
                                        isActiveTransportConnected = bleConnectionStatus == BleConnectionStatus.CONNECTED,
                                        localPeerId = localPeerId,
                                        reachablePeers = discoveredAuroraPeers,
                                        immediateSourcePeerId = immediateSourcePeerId,
                                        immediateSourceDeviceAddress = result.sourceDeviceAddress,
                                        connectToReachablePeer = ::connectToReachablePeerAndAwait
                                    )
                                }
                            }
                        }
                        MessageFrameType.IDENTITY_EXCHANGE,
                        MessageFrameType.HYBRID_TRANSPORT_CONTROL,
                        MessageFrameType.CONTROL -> Unit
                    }
                }
                is IncomingTransportFrameProcessingResult.RelayOnlyEncrypted -> {
                    runtimeScope.launch {
                        relayPrivateEncryptedMessage(
                            envelope = processingResult.envelope,
                            seenMessageIds = privateMeshSeenMessageIds,
                            transportSender = bleTransportSender,
                            activeTransportPeerId = activeTransportPeerId,
                            activeTransportDeviceAddress = activeTransportDeviceAddress,
                            isActiveTransportConnected = bleConnectionStatus == BleConnectionStatus.CONNECTED,
                            localPeerId = localPeerId,
                            reachablePeers = discoveredAuroraPeers,
                            immediateSourcePeerId = immediateSourcePeerId,
                            immediateSourceDeviceAddress = result.sourceDeviceAddress,
                            connectToReachablePeer = ::connectToReachablePeerAndAwait
                        )
                    }
                }
                is IncomingTransportFrameProcessingResult.IdentityHandled,
                is IncomingTransportFrameProcessingResult.IdentityHandlingUnavailable,
                is IncomingTransportFrameProcessingResult.HybridControlHandled,
                is IncomingTransportFrameProcessingResult.HybridControlIgnored -> Unit
            }
        }
        refreshGlobalMeshDiagnostics()
        logIdentityExchangeReceiveResult(result)
    }
    val receiveWifiDirectDebugTransportFrame = remember(
        transportFrameReceiver,
        runtimeGeneration,
        activeTransportPeerId,
        activeTransportDeviceAddress,
        bleConnectionStatus,
        discoveredAuroraPeers
    ) {
        { frame: BleGattTransportFrame ->
            val result = transportFrameReceiver.receive(frame)
            handleIncomingTransportReceiveResult("Wi-Fi Direct", result)
            result
        }
    }
    val transportFrameBridge = remember(transportFrameReceiver, mainHandler) {
        BleTransportFrameBridge(
            receiver = transportFrameReceiver,
            dispatch = { runnable ->
                mainHandler.post(runnable)
            },
            onReceiveResult = { result ->
                handleIncomingTransportReceiveResult("BLE", result)
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
    val advertisedStablePeerId = remember(runtimeGeneration) {
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
    val bluetoothStatusState =
        gr.hua.aurora.ble.permissions.rememberBluetoothPermissionStatusState()
    val bluetoothStatus = bluetoothStatusState.status
    var isAppVisible by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var bleAdvertiseStatus by remember(runtimeGeneration) {
        mutableStateOf(BleAdvertiseStatus.IDLE)
    }
    var bleGattServerStatus by remember(runtimeGeneration) {
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

    val submitGlobalMeshMessageWithOptionalWifiDirect: suspend (
        OutgoingChatMessage,
        String,
        WifiDirectTransportSender?
    ) -> GlobalMeshDeliveryResult =
        { queuedMessage, senderId, wifiDirectTransportSender ->
            val result = submitPublicGlobalMeshMessage(
                message = queuedMessage,
                senderId = senderId,
                coordinator = globalMeshDeliveryCoordinator,
                transportSender = bleTransportSender,
                wifiDirectTransportSender = wifiDirectTransportSender,
                activeTransportPeerId = activeTransportPeerId,
                activeTransportDeviceAddress = activeTransportDeviceAddress,
                isActiveTransportConnected = bleConnectionStatus == BleConnectionStatus.CONNECTED,
                localPeerId = localPeerId,
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
    val submitGlobalMeshMessage: suspend (OutgoingChatMessage, String) -> GlobalMeshDeliveryResult =
        { queuedMessage, senderId ->
            submitGlobalMeshMessageWithOptionalWifiDirect(
                queuedMessage,
                senderId,
                null
            )
        }
    val submitPrivateChatMessage: suspend (OutgoingChatMessage, String, String) -> PrivateChatTransportSubmission =
        { queuedMessage, senderUsername, privateChatId ->
            var preparedTransportFrame: PreparedPrivateChatTransportFrame? = null
            val result = submitPrivateEncryptedMessage(
                message = queuedMessage,
                privateChatId = privateChatId,
                senderPeerId = localPeerId,
                senderUsername = senderUsername,
                transportSender = bleTransportSender,
                sessionMaterialProvider = peerSessionRegistry,
                activeTransportPeerId = activeTransportPeerId,
                activeTransportDeviceAddress = activeTransportDeviceAddress,
                isActiveTransportConnected = bleConnectionStatus == BleConnectionStatus.CONNECTED,
                localPeerId = localPeerId,
                reachablePeers = discoveredAuroraPeers,
                connectToReachablePeer = ::connectToReachablePeerAndAwait,
                onPreparedTransportFrame = { preparedTransportFrame = it }
            )
            if (result == PrivateChatMessageSendResult.SubmittedLocally) {
                privateMeshSeenMessageIds.markSeen(queuedMessage.messageId)
            }
            PrivateChatTransportSubmission(
                result = result,
                preparedTransportFrame = preparedTransportFrame
            )
        }
    val exchangeIdentityWithPeer: suspend (BleDiscoveredDevice, String?) -> PeerIdentityExchangeSendResult =
        { device, privateChatProposalId ->
            val result = connectAndExchangeIdentityWithPeer(
                device = device,
                transportSender = bleTransportSender,
                bleConnectionStatus = bleConnectionStatus,
                activeTransportPeerId = activeTransportPeerId,
                activeTransportDeviceAddress = activeTransportDeviceAddress,
                connectToReachablePeer = ::connectToReachablePeerAndAwait,
                localIdentityMaterial = loadLocalPeerIdentityExchangePublicMaterialOrNull(),
                privateChatProposalId = privateChatProposalId
            )
            lastIdentityExchangeStatus = identityExchangeSendStatusText(result)
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
    val clearSessionForPeer: (String) -> Unit = { peerId ->
        val didClear = peerSessionRegistry.clearPeer(peerId)
        if (didClear) {
            peerSessionDiagnostics = peerSessionRegistry.diagnosticsSnapshot()
        }
    }
    val resetLocalIdentityAndSessions: () -> Unit = {
        Log.d(auroraBleRuntimeLogTag, "BLE runtime local identity reset requested")
        bleConnector.disconnect()
        clearTransportConnectionState()
        clearRuntimeDiscoveryState(stopScanner = true)
        bleAdvertiser.stop()
        bleAdvertiseStatus = BleAdvertiseStatus.STOPPED
        bleGattServer.stop()
        bleGattServerStatus = BleGattServerStatus.STOPPED
        transportFrameBridge.clear()
        val resetSummary = resetAuroraLocalIdentity(
            clearSessionRegistry = peerSessionRegistry::clearAll
        )
        Log.d(
            auroraBleRuntimeLogTag,
            "BLE runtime local identity reset: clearedAliases=${resetSummary.clearedAliases.joinToString(separator = ",").ifEmpty { "none" }} oldPeerId=${resetSummary.previousPeerId ?: "none"} newPeerId=${resetSummary.refreshedPeerId ?: "none"} oldStablePeerId=${resetSummary.previousStablePeerId ?: "none"} newStablePeerId=${resetSummary.refreshedStablePeerId ?: "none"}"
        )
        runtimeGeneration += 1
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

    DisposableEffect(bleScanner, shouldHostRuntime, runtimeGeneration) {
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

    LaunchedEffect(shouldHostRuntime, runtimeGeneration) {
        if (!shouldHostRuntime) {
            return@LaunchedEffect
        }

        while (true) {
            delay(BleScanAggregator.STALE_PEER_PRUNE_INTERVAL_MS)
            discoveredAuroraPeers = discoveredAuroraPeersAggregator.prune()
            refreshGlobalMeshDiagnostics()
        }
    }

    DisposableEffect(bleAdvertiser, shouldHostRuntime, advertiseRequest, runtimeGeneration) {
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

    DisposableEffect(bleGattServer, shouldHostRuntime, runtimeGeneration) {
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

    DisposableEffect(bleConnector, shouldHostRuntime, runtimeGeneration) {
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
        wifiDirectRuntimeStatus = wifiDirectRuntimeStatus,
        refreshWifiDirectStatus = wifiDirectRuntimeState.refresh,
        startWifiDirectDiscovery = wifiDirectRuntimeState.startDiscovery,
        stopWifiDirectDiscovery = wifiDirectRuntimeState.stopDiscovery,
        connectToWifiDirectPeer = wifiDirectRuntimeState.connectToPeer,
        disconnectWifiDirectPeer = wifiDirectRuntimeState.disconnect,
        receiveWifiDirectDebugTransportFrame = receiveWifiDirectDebugTransportFrame,
        peerSessionDiagnostics = peerSessionDiagnostics,
        globalMeshDiagnostics = globalMeshDiagnostics,
        identityHandlerStatus = identityHandlerStatus,
        lastIdentityExchangeStatus = lastIdentityExchangeStatus,
        lastIncomingMessageStatus = lastIncomingMessageStatus,
        lastConnectOnSendStatus = lastConnectOnSendStatus,
        lastGlobalMeshStatus = lastGlobalMeshStatus,
        submitGlobalMeshMessage = submitGlobalMeshMessage,
        submitPrivateChatMessage = submitPrivateChatMessage,
        exchangeIdentityWithPeer = exchangeIdentityWithPeer,
        connectToTransportPeer = connectToTransportPeer,
        disconnectTransportPeer = disconnectTransportPeer,
        clearSessionForPeer = clearSessionForPeer,
        resetLocalIdentityAndSessions = resetLocalIdentityAndSessions
    ).also { runtimeState ->
        runtimeState.submitGlobalMeshMessageWithOptionalWifiDirect =
            submitGlobalMeshMessageWithOptionalWifiDirect
    }
}

internal suspend fun AuroraBleRuntimeState.submitGlobalMeshMessage(
    message: OutgoingChatMessage,
    senderId: String,
    wifiDirectTransportSender: WifiDirectTransportSender?
): GlobalMeshDeliveryResult {
    return submitGlobalMeshMessageWithOptionalWifiDirect(
        message,
        senderId,
        wifiDirectTransportSender
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

internal data class RuntimeMeshForwardTarget(
    val peerId: String,
    val device: BleDiscoveredDevice?,
    val usesActiveConnection: Boolean
)

internal data class RuntimeMeshForwardTargetSelection(
    val targets: List<RuntimeMeshForwardTarget>,
    val excludedSourcePeerId: String?
)

internal sealed interface RuntimeMeshForwardFailure {
    data object SenderUnavailable : RuntimeMeshForwardFailure

    data class ConnectFailed(
        val peerId: String,
        val reason: String
    ) : RuntimeMeshForwardFailure

    data class SendFailed(
        val peerId: String,
        val reason: String
    ) : RuntimeMeshForwardFailure
}

internal data class RuntimeMeshFanoutResult(
    val queuedPeerIds: List<String>,
    val firstFailure: RuntimeMeshForwardFailure?
)

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

internal fun chooseExactReachablePeer(
    reachablePeers: List<BleDiscoveredDevice>,
    targetPeerId: String
): BleDiscoveredDevice? {
    val sanitizedTargetPeerId = targetPeerId.trim().takeIf { it.isNotEmpty() } ?: return null
    return reachablePeers
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
        .firstOrNull { device ->
            runtimeReachablePeerId(device) == sanitizedTargetPeerId
        }
}

internal fun selectRuntimeMeshForwardTargets(
    reachablePeers: List<BleDiscoveredDevice>,
    activeTransportPeerId: String?,
    activeTransportDeviceAddress: String?,
    isActiveTransportConnected: Boolean,
    localPeerId: String? = null,
    excludedSourcePeerId: String? = null,
    excludedSourceDeviceAddress: String? = null
): RuntimeMeshForwardTargetSelection {
    val sanitizedLocalPeerId = localPeerId?.trim()?.takeIf { it.isNotEmpty() }
    val sanitizedExcludedSourcePeerId = excludedSourcePeerId?.trim()?.takeIf { it.isNotEmpty() }
    val sanitizedExcludedSourceDeviceAddress =
        excludedSourceDeviceAddress?.trim()?.takeIf { it.isNotEmpty() }
    val targetsByPeerId = LinkedHashMap<String, RuntimeMeshForwardTarget>()
    var sourceExcluded = false

    fun shouldSkipTarget(
        peerId: String,
        deviceAddress: String?
    ): Boolean {
        if (sanitizedLocalPeerId != null && peerId == sanitizedLocalPeerId) {
            return true
        }
        if (
            sanitizedExcludedSourcePeerId != null &&
            peerId == sanitizedExcludedSourcePeerId
        ) {
            sourceExcluded = true
            return true
        }
        if (
            sanitizedExcludedSourceDeviceAddress != null &&
            deviceAddress != null &&
            deviceAddress == sanitizedExcludedSourceDeviceAddress
        ) {
            sourceExcluded = true
            return true
        }
        return false
    }

    val sanitizedActiveTransportPeerId = activeTransportPeerId?.trim()?.takeIf { it.isNotEmpty() }
    val sanitizedActiveTransportDeviceAddress =
        activeTransportDeviceAddress?.trim()?.takeIf { it.isNotEmpty() }
    if (isActiveTransportConnected && sanitizedActiveTransportPeerId != null) {
        if (!shouldSkipTarget(sanitizedActiveTransportPeerId, sanitizedActiveTransportDeviceAddress)) {
            targetsByPeerId[sanitizedActiveTransportPeerId] = RuntimeMeshForwardTarget(
                peerId = sanitizedActiveTransportPeerId,
                device = null,
                usesActiveConnection = true
            )
        }
    }

    reachablePeers
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
        .forEach { device ->
            val peerId = runtimeReachablePeerId(device)
            val deviceAddress = device.address.trim()
            if (shouldSkipTarget(peerId, deviceAddress)) {
                return@forEach
            }
            targetsByPeerId.putIfAbsent(
                peerId,
                RuntimeMeshForwardTarget(
                    peerId = peerId,
                    device = device,
                    usesActiveConnection = false
                )
            )
        }

    return RuntimeMeshForwardTargetSelection(
        targets = targetsByPeerId.values.toList(),
        excludedSourcePeerId = if (sourceExcluded) sanitizedExcludedSourcePeerId else null
    )
}

internal suspend fun sendAcrossRuntimeMeshTargets(
    targets: List<RuntimeMeshForwardTarget>,
    transportSender: BleTransportSender?,
    connectToReachablePeer: suspend (BleDiscoveredDevice) -> PublicMeshConnectOnSendResult,
    onConnectOnSendStatusChanged: (String) -> Unit = {},
    sendToPeer: suspend (peerId: String, sender: BleTransportSender) -> BleTransportSendResult
): RuntimeMeshFanoutResult {
    val sender = when (transportSender) {
        null -> return RuntimeMeshFanoutResult(
            queuedPeerIds = emptyList(),
            firstFailure = RuntimeMeshForwardFailure.SenderUnavailable
        )
        is NoOpBleTransportSender -> return RuntimeMeshFanoutResult(
            queuedPeerIds = emptyList(),
            firstFailure = RuntimeMeshForwardFailure.SenderUnavailable
        )
        else -> transportSender
    }

    val queuedPeerIds = mutableListOf<String>()
    var firstFailure: RuntimeMeshForwardFailure? = null
    targets.forEach { target ->
        val sendResult = if (target.usesActiveConnection) {
            sendToPeer(target.peerId, sender)
        } else {
            val targetDevice = target.device
                ?: return@forEach
            onConnectOnSendStatusChanged(
                "Mesh connect-on-send: pending for ${target.peerId}."
            )
            when (val connectResult = connectToReachablePeer(targetDevice)) {
                is PublicMeshConnectOnSendResult.Connected -> {
                    if (connectResult.peerId != target.peerId) {
                        if (firstFailure == null) {
                            firstFailure = RuntimeMeshForwardFailure.ConnectFailed(
                                peerId = target.peerId,
                                reason = "connected peer did not match the requested mesh target"
                            )
                        }
                        onConnectOnSendStatusChanged(
                            "Mesh connect-on-send: failed for ${target.peerId}."
                        )
                        return@forEach
                    }
                    onConnectOnSendStatusChanged(
                        "Mesh connect-on-send: succeeded for ${target.peerId}."
                    )
                    sendToPeer(target.peerId, sender)
                }
                is PublicMeshConnectOnSendResult.Failed -> {
                    if (firstFailure == null) {
                        firstFailure = RuntimeMeshForwardFailure.ConnectFailed(
                            peerId = connectResult.peerId,
                            reason = connectResult.reason
                        )
                    }
                    onConnectOnSendStatusChanged(
                        "Mesh connect-on-send: failed for ${connectResult.peerId}."
                    )
                    return@forEach
                }
            }
        }

        when (sendResult) {
            BleTransportSendResult.QueuedLocally -> queuedPeerIds += target.peerId
            BleTransportSendResult.NotAvailable -> {
                if (firstFailure == null) {
                    firstFailure = RuntimeMeshForwardFailure.SenderUnavailable
                }
            }
            is BleTransportSendResult.Failed -> {
                if (firstFailure == null) {
                    firstFailure = RuntimeMeshForwardFailure.SendFailed(
                        peerId = target.peerId,
                        reason = sendResult.reason
                    )
                }
            }
        }
    }

    return RuntimeMeshFanoutResult(
        queuedPeerIds = queuedPeerIds,
        firstFailure = firstFailure
    )
}

internal fun RuntimeMeshFanoutResult.toGlobalMeshDeliveryResult(
    excludedSourcePeerId: String? = null
): GlobalMeshDeliveryResult {
    if (queuedPeerIds.isNotEmpty()) {
        return if (queuedPeerIds.size == 1) {
            GlobalMeshDeliveryResult.QueuedToActivePeer(
                peerId = queuedPeerIds.single()
            )
        } else {
            GlobalMeshDeliveryResult.QueuedToPeers(
                peerIds = queuedPeerIds
            )
        }
    }

    if (excludedSourcePeerId != null) {
        return GlobalMeshDeliveryResult.SkippedSourcePeer(
            peerId = excludedSourcePeerId
        )
    }

    return when (val failure = firstFailure) {
        null -> GlobalMeshDeliveryResult.NoReachablePeers
        RuntimeMeshForwardFailure.SenderUnavailable -> GlobalMeshDeliveryResult.SenderUnavailable
        is RuntimeMeshForwardFailure.ConnectFailed -> GlobalMeshDeliveryResult.ConnectOnSendFailed(
            peerId = failure.peerId,
            reason = failure.reason
        )
        is RuntimeMeshForwardFailure.SendFailed -> GlobalMeshDeliveryResult.Failed(
            reason = failure.reason
        )
    }
}

internal fun RuntimeMeshFanoutResult.toPrivateChatSendResult(): PrivateChatMessageSendResult {
    if (queuedPeerIds.isNotEmpty()) {
        return PrivateChatMessageSendResult.SubmittedLocally
    }

    return when (val failure = firstFailure) {
        null -> PrivateChatMessageSendResult.ContactNotReachable
        RuntimeMeshForwardFailure.SenderUnavailable -> PrivateChatMessageSendResult.ContactNotReachable
        is RuntimeMeshForwardFailure.ConnectFailed -> PrivateChatMessageSendResult.Failed(
            reason = failure.reason
        )
        is RuntimeMeshForwardFailure.SendFailed -> PrivateChatMessageSendResult.Failed(
            reason = failure.reason
        )
    }
}

internal fun runtimeSourcePeerId(
    sourceDeviceAddress: String?,
    activeTransportPeerId: String?,
    activeTransportDeviceAddress: String?,
    reachablePeers: List<BleDiscoveredDevice>
): String? {
    val sanitizedSourceDeviceAddress = sourceDeviceAddress?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    val sanitizedActiveTransportDeviceAddress =
        activeTransportDeviceAddress?.trim()?.takeIf { it.isNotEmpty() }
    if (sanitizedActiveTransportDeviceAddress == sanitizedSourceDeviceAddress) {
        return activeTransportPeerId?.trim()?.takeIf { it.isNotEmpty() }
    }

    return reachablePeers.firstOrNull { device ->
        device.address.trim() == sanitizedSourceDeviceAddress
    }?.let(::runtimeReachablePeerId)
}

internal suspend fun submitPrivateEncryptedMessage(
    message: OutgoingChatMessage,
    privateChatId: String,
    senderPeerId: String?,
    senderUsername: String,
    transportSender: BleTransportSender?,
    sessionMaterialProvider: gr.hua.aurora.protocol.OutgoingSessionMaterialProvider,
    activeTransportPeerId: String?,
    activeTransportDeviceAddress: String? = null,
    isActiveTransportConnected: Boolean,
    localPeerId: String? = null,
    reachablePeers: List<BleDiscoveredDevice> = emptyList(),
    connectToReachablePeer: suspend (BleDiscoveredDevice) -> PublicMeshConnectOnSendResult = {
        PublicMeshConnectOnSendResult.Failed(
            peerId = runtimeReachablePeerId(it),
            reason = "connect-on-send unavailable"
        )
    },
    onPreparedTransportFrame: ((PreparedPrivateChatTransportFrame) -> Unit)? = null
): PrivateChatMessageSendResult {
    val targetPeerId = privateChatTargetPeerId(message)
        ?: return PrivateChatMessageSendResult.ContactUnavailable
    val sanitizedSenderPeerId = senderPeerId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return PrivateChatMessageSendResult.KeysUnavailable
    val encryptionMaterial = sessionMaterialProvider.encryptionMaterialForTarget(targetPeerId)
        ?: return PrivateChatMessageSendResult.KeysUnavailable

    val preparedTransportFrame = runCatching {
        PrivateChatTransportFrameFactory.build(
            message = message,
            privateChatId = privateChatId,
            senderPeerId = sanitizedSenderPeerId,
            senderUsername = senderUsername.trim(),
            encryptionMaterial = encryptionMaterial
        )
    }.getOrElse { error ->
        return PrivateChatMessageSendResult.Failed(
            reason = error.message ?: "Private chat payload is invalid."
        )
    }
    onPreparedTransportFrame?.invoke(preparedTransportFrame)

    val targetSelection = selectRuntimeMeshForwardTargets(
        reachablePeers = reachablePeers,
        activeTransportPeerId = activeTransportPeerId,
        activeTransportDeviceAddress = activeTransportDeviceAddress,
        isActiveTransportConnected = isActiveTransportConnected,
        localPeerId = localPeerId
    )
    val fanoutResult = sendAcrossRuntimeMeshTargets(
        targets = targetSelection.targets,
        transportSender = transportSender,
        connectToReachablePeer = connectToReachablePeer,
        sendToPeer = { transportPeerId, sender ->
            MessageFrameTransportSendUseCase.sendEncryptedEnvelope(
                envelope = preparedTransportFrame.encryptedEnvelope,
                transportSender = sender,
                targetPeerId = transportPeerId,
                sourceCreatedAtMillis = preparedTransportFrame.frame.createdAtMillis
            )
        }
    )

    return fanoutResult.toPrivateChatSendResult()
}

internal suspend fun relayPrivateEncryptedMessage(
    envelope: EncryptedMessageEnvelope,
    seenMessageIds: SeenMessageIdCache,
    transportSender: BleTransportSender?,
    activeTransportPeerId: String?,
    activeTransportDeviceAddress: String?,
    isActiveTransportConnected: Boolean,
    localPeerId: String?,
    reachablePeers: List<BleDiscoveredDevice>,
    immediateSourcePeerId: String?,
    immediateSourceDeviceAddress: String?,
    connectToReachablePeer: suspend (BleDiscoveredDevice) -> PublicMeshConnectOnSendResult
): RuntimeMeshFanoutResult? {
    val relayMetadata = envelope.relayMetadata ?: return null
    if (!seenMessageIds.markSeen(relayMetadata.messageId)) {
        return null
    }
    val decrementedRelayMetadata = relayMetadata.decrementTtlOrNull() ?: return RuntimeMeshFanoutResult(
        queuedPeerIds = emptyList(),
        firstFailure = null
    )
    val relayedEnvelope = EncryptedMessageEnvelope(
        protocolVersion = envelope.protocolVersion,
        senderPublicKey = envelope.senderPublicKey,
        relayMetadata = decrementedRelayMetadata,
        payload = envelope.payload
    )
    val targetSelection = selectRuntimeMeshForwardTargets(
        reachablePeers = reachablePeers,
        activeTransportPeerId = activeTransportPeerId,
        activeTransportDeviceAddress = activeTransportDeviceAddress,
        isActiveTransportConnected = isActiveTransportConnected,
        localPeerId = localPeerId,
        excludedSourcePeerId = immediateSourcePeerId,
        excludedSourceDeviceAddress = immediateSourceDeviceAddress
    )
    if (targetSelection.targets.isEmpty()) {
        return RuntimeMeshFanoutResult(
            queuedPeerIds = emptyList(),
            firstFailure = null
        )
    }

    return sendAcrossRuntimeMeshTargets(
        targets = targetSelection.targets,
        transportSender = transportSender,
        connectToReachablePeer = connectToReachablePeer,
        sendToPeer = { transportPeerId, sender ->
            MessageFrameTransportSendUseCase.sendEncryptedEnvelope(
                envelope = relayedEnvelope,
                transportSender = sender,
                targetPeerId = transportPeerId
            )
        }
    )
}

internal suspend fun relayReceivedPublicMeshMessage(
    message: IncomingTransportMessage,
    ingestionResult: IncomingMessageIngestionResult,
    coordinator: GlobalMeshDeliveryCoordinator,
    transportSender: BleTransportSender?,
    activeTransportPeerId: String?,
    activeTransportDeviceAddress: String?,
    isActiveTransportConnected: Boolean,
    localPeerId: String?,
    reachablePeers: List<BleDiscoveredDevice>,
    immediateSourcePeerId: String?,
    immediateSourceDeviceAddress: String?,
    connectToReachablePeer: suspend (BleDiscoveredDevice) -> PublicMeshConnectOnSendResult,
    onConnectOnSendStatusChanged: (String) -> Unit = {}
): GlobalMeshDeliveryResult {
    val relayEvaluation = coordinator.evaluateMeshRelay(
        messageId = message.frame.id,
        ttl = message.frame.ttl,
        ingestionResult = ingestionResult
    )
    relayEvaluation.terminalResult?.let { terminalResult ->
        return coordinator.recordResult(terminalResult)
    }

    val relayFrame = message.frame.copy(
        ttl = requireNotNull(relayEvaluation.remainingTtl)
    )
    val targetSelection = selectRuntimeMeshForwardTargets(
        reachablePeers = reachablePeers,
        activeTransportPeerId = activeTransportPeerId,
        activeTransportDeviceAddress = activeTransportDeviceAddress,
        isActiveTransportConnected = isActiveTransportConnected,
        localPeerId = localPeerId,
        excludedSourcePeerId = immediateSourcePeerId,
        excludedSourceDeviceAddress = immediateSourceDeviceAddress
    )
    if (targetSelection.targets.isEmpty()) {
        return coordinator.recordResult(
            RuntimeMeshFanoutResult(
                queuedPeerIds = emptyList(),
                firstFailure = null
            ).toGlobalMeshDeliveryResult(targetSelection.excludedSourcePeerId)
        )
    }

    val fanoutResult = sendAcrossRuntimeMeshTargets(
        targets = targetSelection.targets,
        transportSender = transportSender,
        connectToReachablePeer = connectToReachablePeer,
        onConnectOnSendStatusChanged = onConnectOnSendStatusChanged,
        sendToPeer = { transportPeerId, sender ->
            MessageFrameTransportSendUseCase.sendPublic(
                frame = relayFrame,
                transportSender = sender,
                targetPeerId = transportPeerId
            )
        }
    )
    return coordinator.recordResult(
        fanoutResult.toGlobalMeshDeliveryResult(targetSelection.excludedSourcePeerId)
    )
}

internal suspend fun submitIdentityExchangeToTarget(
    targetPeerId: String,
    transportSender: BleTransportSender,
    bleConnectionStatus: BleConnectionStatus,
    activeTransportPeerId: String?,
    activeTransportDeviceAddress: String?,
    reachablePeers: List<BleDiscoveredDevice>,
    connectToReachablePeer: suspend (BleDiscoveredDevice) -> PublicMeshConnectOnSendResult,
    localIdentityMaterial: RuntimePeerIdentityExchangePublicMaterial?,
    privateChatProposalId: String?
): PeerIdentityExchangeSendResult {
    val identityMaterial = localIdentityMaterial ?: return PeerIdentityExchangeSendResult.InvalidLocalIdentity(
        reason = "Local agreement public key unavailable."
    )
    if (identityMaterial.peerId.isBlank() || identityMaterial.publicAgreementKeyBytes().isEmpty()) {
        return PeerIdentityExchangeSendResult.InvalidLocalIdentity(
            reason = "Local agreement public key unavailable."
        )
    }
    val isActiveTargetConnection =
        bleConnectionStatus == BleConnectionStatus.CONNECTED &&
            activeTransportPeerId == targetPeerId
    if (!isActiveTargetConnection) {
        val targetDevice = reachablePeers.firstOrNull { reachablePeer ->
            runtimeReachablePeerId(reachablePeer) == targetPeerId
        } ?: return PeerIdentityExchangeSendResult.Failed(
            reason = "peer is not reachable for identity setup"
        )
        when (val connectResult = connectToReachablePeer(targetDevice)) {
            is PublicMeshConnectOnSendResult.Failed -> {
                return PeerIdentityExchangeSendResult.Failed(
                    reason = connectResult.reason
                )
            }
            is PublicMeshConnectOnSendResult.Connected -> {
                if (connectResult.peerId != targetPeerId) {
                    return PeerIdentityExchangeSendResult.Failed(
                        reason = "connected peer did not match the requested identity"
                    )
                }
            }
        }
    }

    return PeerIdentityExchangeSendUseCase.send(
        localPeerId = identityMaterial.peerId,
        localPublicAgreementKeyBytes = identityMaterial.publicAgreementKeyBytes(),
        privateChatProposalId = privateChatProposalId,
        targetPeerId = targetPeerId,
        transportSender = transportSender,
        createdAtMillis = System.currentTimeMillis()
    )
}

internal suspend fun connectAndExchangeIdentityWithPeer(
    device: BleDiscoveredDevice,
    transportSender: BleTransportSender,
    bleConnectionStatus: BleConnectionStatus,
    activeTransportPeerId: String?,
    activeTransportDeviceAddress: String?,
    connectToReachablePeer: suspend (BleDiscoveredDevice) -> PublicMeshConnectOnSendResult,
    localIdentityMaterial: RuntimePeerIdentityExchangePublicMaterial?,
    privateChatProposalId: String?
): PeerIdentityExchangeSendResult {
    return submitIdentityExchangeToTarget(
        targetPeerId = runtimeReachablePeerId(device),
        transportSender = transportSender,
        bleConnectionStatus = bleConnectionStatus,
        activeTransportPeerId = activeTransportPeerId,
        activeTransportDeviceAddress = activeTransportDeviceAddress,
        reachablePeers = listOf(device),
        connectToReachablePeer = connectToReachablePeer,
        localIdentityMaterial = localIdentityMaterial,
        privateChatProposalId = privateChatProposalId
    )
}

internal data class LocalIdentityResetSummary(
    val previousPeerId: String?,
    val refreshedPeerId: String?,
    val previousStablePeerId: String?,
    val refreshedStablePeerId: String?,
    val clearedAliases: Set<String>
)

internal data class RuntimePeerIdentityExchangePublicMaterial(
    val peerId: String,
    private val publicAgreementKeyBytes: ByteArray
) {
    init {
        require(peerId.isNotBlank()) {
            "Runtime peer id must not be blank."
        }
        require(publicAgreementKeyBytes.isNotEmpty()) {
            "Runtime public agreement key bytes must not be empty."
        }
    }

    fun publicAgreementKeyBytes(): ByteArray {
        return publicAgreementKeyBytes.copyOf()
    }
}

internal fun loadLocalPeerIdentityExchangePublicMaterialOrNull(
    loadPublicKeyBytes: () -> ByteArray? = {
        runCatching {
            AndroidKeystoreLocalAgreementPublicKey.ensureAgreementPublicKeyBytes()
        }.getOrNull()
    }
): RuntimePeerIdentityExchangePublicMaterial? {
    val publicKeyBytes = runCatching(loadPublicKeyBytes).getOrNull()
        ?.takeIf { it.isNotEmpty() }
        ?: return null

    return runCatching {
        RuntimePeerIdentityExchangePublicMaterial(
            peerId = PeerSessionPeerId.deriveFromPublicKey(publicKeyBytes),
            publicAgreementKeyBytes = publicKeyBytes
        )
    }.getOrNull()
}

internal fun resetAuroraLocalIdentity(
    identity: LocalKeyIdentity = LocalKeyIdentity.default(),
    clearSessionRegistry: () -> Unit = {},
    loadExistingPublicKeyBytes: () -> ByteArray? = {
        AndroidKeystoreLocalAgreementPublicKey.loadAgreementPublicKeyBytesOrNull(identity)
    },
    clearLocalIdentityEntries: () -> LocalIdentityClearResult = {
        AndroidKeystoreLocalAgreementKey.clearLocalIdentityEntries(identity)
    },
    ensureFreshPublicKeyBytes: () -> ByteArray = {
        AndroidKeystoreLocalAgreementPublicKey.ensureAgreementPublicKeyBytes(identity)
    }
): LocalIdentityResetSummary {
    val previousPublicKeyBytes = runCatching(loadExistingPublicKeyBytes).getOrNull()
        ?.takeIf { it.isNotEmpty() }
    clearSessionRegistry()
    val clearResult = clearLocalIdentityEntries()
    val refreshedPublicKeyBytes = runCatching(ensureFreshPublicKeyBytes).getOrNull()
        ?.takeIf { it.isNotEmpty() }

    return LocalIdentityResetSummary(
        previousPeerId = previousPublicKeyBytes?.let(::peerIdFromPublicKeyBytes),
        refreshedPeerId = refreshedPublicKeyBytes?.let(::peerIdFromPublicKeyBytes),
        previousStablePeerId = previousPublicKeyBytes?.let(::stablePeerIdHexFromPublicKeyBytes),
        refreshedStablePeerId = refreshedPublicKeyBytes?.let(::stablePeerIdHexFromPublicKeyBytes),
        clearedAliases = clearResult.clearedAliases
    )
}

internal fun privateChatTargetPeerId(
    message: OutgoingChatMessage
): String? {
    val draft = runCatching {
        gr.hua.aurora.protocol.OutgoingMessageFrameBuilder.build(message)
    }.getOrNull() ?: return null
    if (draft.type != MessageFrameType.PRIVATE_TEXT) {
        return null
    }

    return draft.recipientId?.trim()?.takeIf { it.isNotEmpty() }
}

internal suspend fun submitPublicGlobalMeshMessage(
    message: OutgoingChatMessage,
    senderId: String,
    coordinator: GlobalMeshDeliveryCoordinator,
    transportSender: BleTransportSender?,
    wifiDirectTransportSender: WifiDirectTransportSender? = null,
    activeTransportPeerId: String?,
    activeTransportDeviceAddress: String? = null,
    isActiveTransportConnected: Boolean,
    localPeerId: String? = null,
    reachablePeers: List<BleDiscoveredDevice>,
    connectToReachablePeer: suspend (BleDiscoveredDevice) -> PublicMeshConnectOnSendResult,
    onConnectOnSendStatusChanged: (String) -> Unit = {}
): GlobalMeshDeliveryResult {
    val relayFrame = coordinator.prepareLocalPublicFrame(
        message = message,
        senderId = senderId
    ) ?: return coordinator.recordResult(
        GlobalMeshDeliveryResult.SkippedDuplicate(message.messageId)
    )
    val targetSelection = selectRuntimeMeshForwardTargets(
        reachablePeers = reachablePeers,
        activeTransportPeerId = activeTransportPeerId,
        activeTransportDeviceAddress = activeTransportDeviceAddress,
        isActiveTransportConnected = isActiveTransportConnected,
        localPeerId = localPeerId
    )
    if (targetSelection.targets.isEmpty()) {
        onConnectOnSendStatusChanged(
            "Public mesh connect-on-send: no reachable Aurora peer."
        )
        return coordinator.recordResult(GlobalMeshDeliveryResult.NoReachablePeers)
    }
    if (targetSelection.targets.none { !it.usesActiveConnection }) {
        onConnectOnSendStatusChanged(
            "Public mesh connect-on-send: not needed. Active mesh peer is already connected."
        )
    }

    val fanoutResult = sendAcrossRuntimeMeshTargets(
        targets = targetSelection.targets,
        transportSender = transportSender,
        connectToReachablePeer = connectToReachablePeer,
        onConnectOnSendStatusChanged = onConnectOnSendStatusChanged,
        sendToPeer = { transportPeerId, sender ->
            MessageFrameTransportSendUseCase.sendPublic(
                frame = relayFrame,
                transportSender = sender,
                targetPeerId = transportPeerId
            )
        }
    )
    val deliveryResult = fanoutResult.toGlobalMeshDeliveryResult()
    if (
        deliveryResult is GlobalMeshDeliveryResult.QueuedToActivePeer ||
        deliveryResult is GlobalMeshDeliveryResult.QueuedToPeers
    ) {
        maybeSendWifiDirectGlobalCopy(
            frame = relayFrame,
            transportSender = wifiDirectTransportSender
        )
    }
    return coordinator.recordResult(deliveryResult)
}

internal suspend fun maybeSendWifiDirectGlobalCopy(
    frame: MessageFrame,
    transportSender: WifiDirectTransportSender?
) {
    if (transportSender == null) {
        return
    }

    val transportFrame = runCatching {
        WifiDirectTransportFrame.fromPayload(
            MessageFrameCodec.encode(frame).toByteArray(UTF_8)
        )
    }.getOrElse { error ->
        safeAuroraBleRuntimeLogDebug(
            "Wi-Fi Direct global copy skipped for ${frame.id}: ${runtimeSafeErrorDetail(error)}"
        )
        return
    }

    when (val result = transportSender.send(transportFrame)) {
        WifiDirectTransportSendResult.Success -> {
            safeAuroraBleRuntimeLogDebug(
                "Wi-Fi Direct global copy submitted for ${frame.id} payloadSize=${transportFrame.payloadSize}"
            )
        }

        is WifiDirectTransportSendResult.NotReady -> {
            safeAuroraBleRuntimeLogDebug(
                "Wi-Fi Direct global copy unavailable for ${frame.id}: ${result.reason}"
            )
        }

        is WifiDirectTransportSendResult.Failed -> {
            val message = "Wi-Fi Direct global copy failed for ${frame.id}: ${result.reason}"
            val cause = result.cause
            safeAuroraBleRuntimeLogWarning(message, cause)
        }
    }
}

private fun runtimeSafeErrorDetail(
    error: Throwable
): String {
    return error.message?.trim()?.takeIf { it.isNotEmpty() }
        ?: error::class.java.simpleName
}

private fun safeAuroraBleRuntimeLogDebug(
    message: String
) {
    runCatching {
        Log.d(auroraBleRuntimeLogTag, message)
    }
}

private fun safeAuroraBleRuntimeLogWarning(
    message: String,
    cause: Throwable?
) {
    runCatching {
        if (cause != null) {
            Log.w(auroraBleRuntimeLogTag, message, cause)
        } else {
            Log.w(auroraBleRuntimeLogTag, message)
        }
    }
}

internal fun globalMeshStatusText(
    result: GlobalMeshDeliveryResult
): String {
    return when (result) {
        is GlobalMeshDeliveryResult.QueuedToActivePeer ->
            "Global mesh queued to active peer ${result.peerId}."

        is GlobalMeshDeliveryResult.QueuedToPeers ->
            "Global mesh queued to ${result.peerIds.size} peers."

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

internal fun identityExchangeSendStatusText(
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

internal fun identityExchangeRecoveryStatusText(
    peerId: String,
    result: PeerIdentityExchangeSendResult
): String {
    return when (result) {
        PeerIdentityExchangeSendResult.SubmittedLocally ->
            "Identity received from $peerId. Setup reply queued."
        PeerIdentityExchangeSendResult.SenderUnavailable ->
            "Identity received from $peerId. Setup reply unavailable."
        is PeerIdentityExchangeSendResult.InvalidLocalIdentity ->
            result.reason
        is PeerIdentityExchangeSendResult.Failed ->
            "Identity received from $peerId. Setup reply failed: ${result.reason}"
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

private fun peerIdFromPublicKeyBytes(
    publicKeyBytes: ByteArray
): String {
    return PeerSessionPeerId.deriveFromPublicKey(publicKeyBytes)
}

private fun stablePeerIdHexFromPublicKeyBytes(
    publicKeyBytes: ByteArray
): String {
    return BleStablePeerId.deriveFromPublicKeyBytes(publicKeyBytes)
        .toByteArray()
        .joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
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
    stateHolder: AuroraStateHolder,
    localIdentity: LocalPeerSessionIdentityMaterial?,
    registry: PeerSessionRegistry,
    onIdentityEstablished: (peerId: String, shouldReply: Boolean) -> Unit = { _, _ -> }
): ((IncomingTransportMessage) -> PeerIdentityExchangeHandlingResult)? {
    val identity = localIdentity ?: return null

    return { message ->
        val hadSessionBeforeHandling = registry.hasSessionForPeer(message.frame.senderId)
        val handlingResult = PeerIdentityExchangeHandler.handle(
            frame = message.frame,
            localIdentity = identity,
            registry = registry
        )
        if (handlingResult is PeerIdentityExchangeHandlingResult.Established) {
            val privateChatProposalId = runCatching {
                PeerIdentityExchangeMessage.fromMessageFrame(message.frame).privateChatProposalId
            }.getOrNull()
            stateHolder.recordReceivedPrivateChatProposal(
                peerId = handlingResult.peerId,
                remoteProposalId = privateChatProposalId
            )
            stateHolder.findContactByPeerId(handlingResult.peerId)?.let { existingContact ->
                stateHolder.promoteContactSession(
                    canonicalPeerId = handlingResult.peerId,
                    displayName = existingContact.displayName,
                    lastSeenMillis = message.frame.createdAtMillis
                )
            }
            onIdentityEstablished(
                handlingResult.peerId,
                !hadSessionBeforeHandling
            )
        }
        handlingResult
    }
}

internal fun createAuroraBleTransportFrameReceiver(
    stateHolder: AuroraStateHolder,
    sessionMaterialProvider: IncomingSessionMaterialProvider = NoOpIncomingSessionMaterialProvider,
    hybridControlStore: HybridTransportControlStore? = null,
    handleIdentity: ((IncomingTransportMessage) -> PeerIdentityExchangeHandlingResult)? = null,
    identityHandlingUnavailableReason: String =
        "Local agreement identity material unavailable for incoming identity exchange."
): BleTransportFrameReceiver {
    return BleTransportFrameReceiver(processFrames = { frames ->
        IncomingTransportFrameProcessor.process(
            frames = frames,
            sessionMaterialProvider = sessionMaterialProvider,
            stateHolder = stateHolder,
            hybridControlStore = hybridControlStore,
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
                is IncomingTransportFrameProcessingResult.HybridControlHandled,
                is IncomingTransportFrameProcessingResult.HybridControlIgnored,
                is IncomingTransportFrameProcessingResult.Received,
                is IncomingTransportFrameProcessingResult.RelayOnlyEncrypted -> null
            }
        }
        is BleTransportReceiveResult.ProcessorFailed -> {
            when (val receiveResult = result.processingResult.receiveResult) {
                is IncomingTransportReceiveResult.Received -> null
                is IncomingTransportReceiveResult.RelayOnlyEncrypted -> null
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
                            MessageFrameType.HYBRID_TRANSPORT_CONTROL,
                            MessageFrameType.CONTROL ->
                                "Received message from ${ingestionResult.message.senderId}."
                        }
                        is Duplicate -> when (processingResult.message.frame.type) {
                            MessageFrameType.GLOBAL_TEXT ->
                                "Duplicate public global message ignored from ${processingResult.message.frame.senderId}."
                            MessageFrameType.PRIVATE_TEXT ->
                                "Duplicate encrypted private message ignored from ${processingResult.message.frame.senderId}."
                            MessageFrameType.IDENTITY_EXCHANGE,
                            MessageFrameType.HYBRID_TRANSPORT_CONTROL,
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
                is IncomingTransportFrameProcessingResult.IdentityHandlingUnavailable,
                is IncomingTransportFrameProcessingResult.HybridControlHandled,
                is IncomingTransportFrameProcessingResult.HybridControlIgnored,
                is IncomingTransportFrameProcessingResult.RelayOnlyEncrypted -> null
            }
        }
        is BleTransportReceiveResult.ProcessorFailed -> {
            when (val receiveResult = result.processingResult.receiveResult) {
                is IncomingTransportReceiveResult.Received -> null
                is IncomingTransportReceiveResult.RelayOnlyEncrypted ->
                    "Relayed encrypted private frame."
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

internal fun hybridBootstrapDecisionAfterReceiveOrNull(
    result: BleTransportReceiveResult,
    provider: HybridBootstrapDecisionProvider
): HybridBootstrapDecision? {
    return when (result) {
        is BleTransportReceiveResult.Processed -> {
            when (result.processingResult) {
                is IncomingTransportFrameProcessingResult.HybridControlHandled ->
                    provider.currentDecision()
                is IncomingTransportFrameProcessingResult.HybridControlIgnored,
                is IncomingTransportFrameProcessingResult.IdentityHandled,
                is IncomingTransportFrameProcessingResult.IdentityHandlingUnavailable,
                is IncomingTransportFrameProcessingResult.Received,
                is IncomingTransportFrameProcessingResult.RelayOnlyEncrypted -> null
            }
        }
        is BleTransportReceiveResult.Buffered,
        is BleTransportReceiveResult.BufferOverflow,
        is BleTransportReceiveResult.DuplicateChunk,
        is BleTransportReceiveResult.InvalidChunk,
        is BleTransportReceiveResult.ProcessorFailed -> null
    }
}

internal fun currentHybridBootstrapDiagnostics(
    provider: HybridBootstrapDecisionProvider
): HybridBootstrapDiagnostics {
    return HybridBootstrapDiagnosticsFormatter.format(provider.currentDecision())
}

internal fun hybridBootstrapDiagnosticsAfterReceiveOrNull(
    result: BleTransportReceiveResult,
    provider: HybridBootstrapDecisionProvider
): HybridBootstrapDiagnostics? {
    val decision = hybridBootstrapDecisionAfterReceiveOrNull(
        result = result,
        provider = provider
    ) ?: return null

    return HybridBootstrapDiagnosticsFormatter.format(decision)
}

internal fun hybridBootstrapDiagnosticsRuntimeStatusText(
    diagnostics: HybridBootstrapDiagnostics
): String? {
    return when (diagnostics.selectionStatus) {
        HybridBootstrapDiagnostics.SelectionStatus.NoCandidates ->
            "Hybrid bootstrap: no candidates"
        HybridBootstrapDiagnostics.SelectionStatus.NoSocketReadyCandidates ->
            "Hybrid bootstrap: candidates available, none socket-ready"
        HybridBootstrapDiagnostics.SelectionStatus.Selected ->
            "Hybrid bootstrap: socket-ready peer=${diagnostics.selectedPeerId} " +
                "session=${diagnostics.selectedSessionId} " +
                "address=${diagnostics.selectedGroupOwnerAddress} " +
                "port=${diagnostics.selectedSocketPort}"
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
                is IncomingTransportFrameProcessingResult.HybridControlHandled,
                is IncomingTransportFrameProcessingResult.HybridControlIgnored,
                is IncomingTransportFrameProcessingResult.Received,
                is IncomingTransportFrameProcessingResult.RelayOnlyEncrypted -> Unit
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
