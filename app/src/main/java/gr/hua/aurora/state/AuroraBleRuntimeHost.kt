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
import gr.hua.aurora.ble.transport.BleTransportLocalSendTrace
import gr.hua.aurora.ble.transport.BleTransportFrameBridge
import gr.hua.aurora.ble.transport.BleTransportFrameReceiver
import gr.hua.aurora.ble.transport.BleTransportReceiveResult
import gr.hua.aurora.ble.transport.BleTransportSendResult
import gr.hua.aurora.ble.transport.BleTransportSender
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsActivityLifecycleState
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsApplicationProbeObservation
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsApplicationProbeAssociationOutcome
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsApplicationProbeMarker
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsApplicationProbeTransportReceiveEvent
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsApplicationProbeReceiveDiagnostic
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsApplicationProbeSourceResolution
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsApplicationProbeSourceResolutionSource
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsApplicationProbeSelectedSecurePeerGate
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsPhaseApplicationProbeDescriptor
import gr.hua.aurora.diagnostics.automated.automatedDiagnosticsApplicationProbeExpectedMessageType
import gr.hua.aurora.diagnostics.automated.automatedDiagnosticsApplicationProbeMarkerOrNull
import gr.hua.aurora.diagnostics.automated.automatedDiagnosticsPhaseApplicationProbeDescriptors
import gr.hua.aurora.diagnostics.automated.automatedDiagnosticsPhaseApplicationProbePayloadOrNull
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticStepId
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsParticipantJoin
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsParticipantJoinSendResult
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsHybridAcceptObservation
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsHybridSocketHintObservation
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsPhaseSignal
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsPhaseState
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsPhaseStateSendResult
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsRunAnnouncement
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsRunAnnouncementSendResult
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsWifiDirectPeerReadySendResult
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsWifiDirectPeerReadySignal
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsServerReadySignal
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsServerReadySendResult
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsSharedRun
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsTimingPolicy
import gr.hua.aurora.diagnostics.automated.automatedDiagnosticsParticipantJoinSendStatusText
import gr.hua.aurora.diagnostics.automated.automatedDiagnosticsParticipantJoinStatusText
import gr.hua.aurora.diagnostics.automated.automatedDiagnosticsPhaseStateSendStatusText
import gr.hua.aurora.diagnostics.automated.automatedDiagnosticsPhaseStateStatusText
import gr.hua.aurora.diagnostics.automated.automatedDiagnosticsRunAnnouncementSendStatusText
import gr.hua.aurora.diagnostics.automated.automatedDiagnosticsRunAnnouncementStatusText
import gr.hua.aurora.diagnostics.automated.automatedDiagnosticsWifiDirectPeerReadySendStatusText
import gr.hua.aurora.diagnostics.automated.automatedDiagnosticsWifiDirectPeerReadyStatusText
import gr.hua.aurora.diagnostics.automated.automatedDiagnosticsServerReadySendStatusText
import gr.hua.aurora.diagnostics.automated.automatedDiagnosticsServerReadyStatusText
import gr.hua.aurora.diagnostics.automated.mergeAutomatedDiagnosticsPhaseSignal
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsRuntimeEvidence
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsRuntimeEvent
import gr.hua.aurora.diagnostics.automated.SystemMonotonicClock
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
import gr.hua.aurora.protocol.canonicalPeerIdFor
import gr.hua.aurora.protocol.hasSessionForPeer
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
import gr.hua.aurora.transport.hybrid.HybridBootstrapCandidate
import gr.hua.aurora.transport.hybrid.HybridBootstrapCandidateSelection
import gr.hua.aurora.transport.hybrid.HybridBootstrapAttemptCommandBuildResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapAttemptCommandBuilder
import gr.hua.aurora.transport.hybrid.HybridBootstrapAttemptDecision
import gr.hua.aurora.transport.hybrid.HybridBootstrapAttemptPolicy
import gr.hua.aurora.transport.hybrid.HybridBootstrapCommandExecutorConfig
import gr.hua.aurora.transport.hybrid.HybridBootstrapCommandExecutionResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapCommandExecutorMode
import gr.hua.aurora.transport.hybrid.HybridBootstrapSocketConnector
import gr.hua.aurora.transport.hybrid.HybridBootstrapManualAcceptSendResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapManualOfferSendResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapManualSocketHintSendResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapManualTriggerSnapshot
import gr.hua.aurora.transport.hybrid.HybridBootstrapManualTriggerSnapshotFormatter
import gr.hua.aurora.transport.hybrid.HybridBootstrapCommandTriggerController
import gr.hua.aurora.transport.hybrid.HybridBootstrapCommandTriggerResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapSocketEndpointResolution
import gr.hua.aurora.transport.hybrid.HybridBootstrapSocketEndpointResolver
import gr.hua.aurora.transport.hybrid.HybridBootstrapSocketHintObservation
import gr.hua.aurora.transport.hybrid.HybridTransportControlFrameFactory
import gr.hua.aurora.transport.hybrid.HybridTransportControlMessage
import gr.hua.aurora.transport.hybrid.HybridTransportControlStore
import gr.hua.aurora.transport.hybrid.HybridBootstrapCommandExecutorFactory
import gr.hua.aurora.transport.hybrid.InMemoryHybridTransportControlStore
import gr.hua.aurora.state.IncomingMessageIngestionResult.Appended
import gr.hua.aurora.state.IncomingMessageIngestionResult.Duplicate
import gr.hua.aurora.state.IncomingMessageIngestionResult.UnsupportedThread
import gr.hua.aurora.state.IncomingMessageIngestionResult.UnsupportedType
import gr.hua.aurora.wifidirect.model.WifiDirectPeer
import gr.hua.aurora.wifidirect.frame.WifiDirectTransportFrame
import gr.hua.aurora.wifidirect.runtime.WifiDirectConnectionRole
import gr.hua.aurora.wifidirect.runtime.WifiDirectConnectionState
import gr.hua.aurora.wifidirect.runtime.WifiDirectConnectionStatus
import gr.hua.aurora.wifidirect.runtime.WifiDirectGroupFormedState
import gr.hua.aurora.wifidirect.runtime.RememberedWifiDirectRuntimeStatusState
import gr.hua.aurora.wifidirect.runtime.WifiDirectRolePreference
import gr.hua.aurora.wifidirect.runtime.WifiDirectRuntimeStatus
import gr.hua.aurora.wifidirect.socket.wifiDirectDebugSocketPort
import gr.hua.aurora.wifidirect.transport.WifiDirectTransportSendResult
import gr.hua.aurora.wifidirect.transport.WifiDirectTransportSender
import java.security.PrivateKey
import java.nio.charset.StandardCharsets.UTF_8
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

private const val auroraBleRuntimeLogTag = "AuroraBleRuntime"
private const val automatedDiagnosticsRuntimeEventLimit = 32
private const val automatedDiagnosticsApplicationProbeObservationLimit = 64
private const val automatedDiagnosticsApplicationProbeReceiveDiagnosticLimit = 64
private const val automatedDiagnosticsApplicationProbeTransportReceiveEventLimit = 128
private const val automatedDiagnosticsBleTransportLocalSendTraceLimit = 64

data class AuroraBleRuntimeState(
    val bluetoothPermissionStatus: BluetoothPermissionStatus,
    val refreshBluetoothStatus: () -> Unit,
    val bleAdvertiseStatus: BleAdvertiseStatus,
    val bleGattServerStatus: BleGattServerStatus,
    val bleScanStatus: BleScanStatus,
    val bleScanDiagnostics: BleScanDiagnostics,
    val discoveredAuroraPeers: List<BleDiscoveredDevice>,
    val bleConnector: AndroidBleConnector,
    val bleConnectionStatus: BleConnectionStatus,
    val activeTransportDeviceAddress: String?,
    val activeTransportPeerId: String?,
    val localPeerId: String?,
    val bleTransportSender: BleTransportSender,
    val transportSenderSourceLabel: String,
    val wifiDirectRuntimeStatus: WifiDirectRuntimeStatus,
    val refreshWifiDirectStatus: () -> Unit,
    val startWifiDirectDiscovery: () -> Unit,
    val stopWifiDirectDiscovery: () -> Unit,
    val registerAutomatedDiagnosticsWifiDirectService: (String, String?) -> Unit,
    val startAutomatedDiagnosticsWifiDirectServiceDiscovery: () -> Unit,
    val clearAutomatedDiagnosticsWifiDirectServiceDiscovery: () -> Unit,
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
    val runtimeEvidence: AutomatedDiagnosticsRuntimeEvidence,
    val hybridBootstrapJavaNetRuntimeEnabled: Boolean,
    val hybridBootstrapCommandExecutorMode: HybridBootstrapCommandExecutorMode,
    val hybridBootstrapDecision: HybridBootstrapDecision,
    val hybridBootstrapDiagnostics: HybridBootstrapDiagnostics,
    val latestAutomatedDiagnosticsRunAnnouncement: AutomatedDiagnosticsRunAnnouncement?,
    val latestAutomatedDiagnosticsParticipantJoin: AutomatedDiagnosticsParticipantJoin?,
    val latestAutomatedDiagnosticsWifiDirectPeerReadySignal:
    AutomatedDiagnosticsWifiDirectPeerReadySignal?,
    val latestAutomatedDiagnosticsPhaseSignal: AutomatedDiagnosticsPhaseSignal?,
    val latestAutomatedDiagnosticsPhaseSignalsByStep:
    Map<AutomatedDiagnosticStepId, AutomatedDiagnosticsPhaseSignal>,
    val latestAutomatedDiagnosticsServerReadySignal: AutomatedDiagnosticsServerReadySignal?,
    val latestAutomatedDiagnosticsHybridAcceptObservation:
    AutomatedDiagnosticsHybridAcceptObservation?,
    val latestAutomatedDiagnosticsHybridSocketHintObservation:
    AutomatedDiagnosticsHybridSocketHintObservation?,
    val recentBleTransportLocalSendTraces: List<BleTransportLocalSendTrace>,
    val recentAutomatedDiagnosticsApplicationProbeObservations:
    List<AutomatedDiagnosticsApplicationProbeObservation>,
    val recentAutomatedDiagnosticsApplicationProbeTransportReceiveEvents:
    List<AutomatedDiagnosticsApplicationProbeTransportReceiveEvent>,
    val recentAutomatedDiagnosticsApplicationProbeReceiveDiagnostics:
    List<AutomatedDiagnosticsApplicationProbeReceiveDiagnostic>,
    val lastAutomatedDiagnosticsCoordinationStatus: String?,
    val lastAutomatedDiagnosticsWifiDirectPeerReadyStatus: String?,
    val lastAutomatedDiagnosticsPhaseStatus: String?,
    val lastAutomatedDiagnosticsServerReadyStatus: String?,
    val clearAutomatedDiagnosticsSharedRunCoordinationState: () -> Unit,
    val clearAutomatedDiagnosticsCoordinationState: () -> Unit,
    val hybridBootstrapManualTriggerSnapshot: HybridBootstrapManualTriggerSnapshot,
    val onHybridBootstrapManualTriggerRequested: suspend () -> HybridBootstrapCommandTriggerResult,
    val hybridBootstrapManualAcceptAvailable: Boolean,
    val hybridBootstrapManualAcceptBlockedReason: String?,
    val lastHybridBootstrapManualAcceptStatus: String?,
    val onHybridBootstrapManualAcceptRequested: suspend () -> HybridBootstrapManualAcceptSendResult,
    val hybridBootstrapManualOfferAvailable: Boolean,
    val hybridBootstrapManualOfferBlockedReason: String?,
    val lastHybridBootstrapManualOfferStatus: String?,
    val onHybridBootstrapManualOfferRequested: suspend () -> HybridBootstrapManualOfferSendResult,
    val lastHybridBootstrapManualSocketHintStatus: String?,
    val onAutomatedDiagnosticsRunAnnouncementRequested:
    suspend (AutomatedDiagnosticsSharedRun) -> AutomatedDiagnosticsRunAnnouncementSendResult,
    val onAutomatedDiagnosticsParticipantJoinRequested:
    suspend (AutomatedDiagnosticsSharedRun) -> AutomatedDiagnosticsParticipantJoinSendResult,
    val onAutomatedDiagnosticsWifiDirectPeerReadyRequested:
    suspend (
        AutomatedDiagnosticsSharedRun,
        String,
        String,
        String?
    ) -> AutomatedDiagnosticsWifiDirectPeerReadySendResult,
    val onAutomatedDiagnosticsPhaseStateRequested:
    suspend (
        AutomatedDiagnosticsSharedRun,
        String,
        AutomatedDiagnosticStepId,
        AutomatedDiagnosticsPhaseState,
        Int,
        List<AutomatedDiagnosticsPhaseApplicationProbeDescriptor>
    ) -> AutomatedDiagnosticsPhaseStateSendResult,
    val onAutomatedDiagnosticsServerReadyRequested:
    suspend (AutomatedDiagnosticsSharedRun, String, String, Int, Long) -> AutomatedDiagnosticsServerReadySendResult,
    val onHybridBootstrapManualSocketHintRequested:
    suspend () -> HybridBootstrapManualSocketHintSendResult,
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

    internal var setHybridBootstrapSocketConnectorOverride:
        (HybridBootstrapSocketConnector?) -> Unit = {}

    internal var recordAcceptedAutomatedDiagnosticsPhaseSignalSourceAssociation:
        (AutomatedDiagnosticsPhaseSignal) -> Unit = {}
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
    var recentBleTransportLocalSendTraces by remember(runtimeGeneration) {
        mutableStateOf<List<BleTransportLocalSendTrace>>(emptyList())
    }
    val bleTransportSender = remember(resolvedTransportFrameWriter) {
        createAuroraBleTransportSender(
            transportFrameWriter = resolvedTransportFrameWriter,
            onLocalSendTraceReady = { trace ->
                recentBleTransportLocalSendTraces =
                    appendBleTransportLocalSendTrace(
                        traces = recentBleTransportLocalSendTraces,
                        trace = trace,
                        limit = automatedDiagnosticsBleTransportLocalSendTraceLimit
                    )
            }
        )
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
    val hybridBootstrapCommandExecutorConfig = remember(runtimeGeneration) {
        currentHybridBootstrapCommandExecutorConfig()
    }
    var hybridBootstrapSocketConnectorOverride by remember(runtimeGeneration) {
        mutableStateOf<HybridBootstrapSocketConnector?>(null)
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
    var latestAutomatedDiagnosticsRunAnnouncement by remember(
        runtimeGeneration
    ) {
        mutableStateOf<AutomatedDiagnosticsRunAnnouncement?>(null)
    }
    var latestAutomatedDiagnosticsParticipantJoin by remember(
        runtimeGeneration
    ) {
        mutableStateOf<AutomatedDiagnosticsParticipantJoin?>(null)
    }
    var latestAutomatedDiagnosticsWifiDirectPeerReadySignal by remember(
        runtimeGeneration
    ) {
        mutableStateOf<AutomatedDiagnosticsWifiDirectPeerReadySignal?>(null)
    }
    var latestAutomatedDiagnosticsPhaseSignal by remember(
        runtimeGeneration
    ) {
        mutableStateOf<AutomatedDiagnosticsPhaseSignal?>(null)
    }
    var latestAutomatedDiagnosticsPhaseSignalsByStep by remember(
        runtimeGeneration
    ) {
        mutableStateOf<Map<AutomatedDiagnosticStepId, AutomatedDiagnosticsPhaseSignal>>(emptyMap())
    }
    var latestAutomatedDiagnosticsServerReadySignal by remember(
        runtimeGeneration
    ) {
        mutableStateOf<AutomatedDiagnosticsServerReadySignal?>(null)
    }
    var latestAutomatedDiagnosticsHybridAcceptObservation by remember(
        runtimeGeneration
    ) {
        mutableStateOf<AutomatedDiagnosticsHybridAcceptObservation?>(null)
    }
    var latestAutomatedDiagnosticsHybridSocketHintObservation by remember(
        runtimeGeneration
    ) {
        mutableStateOf<AutomatedDiagnosticsHybridSocketHintObservation?>(null)
    }
    var recentAutomatedDiagnosticsApplicationProbeObservations by remember(
        runtimeGeneration
    ) {
        mutableStateOf<List<AutomatedDiagnosticsApplicationProbeObservation>>(emptyList())
    }
    var recentAutomatedDiagnosticsApplicationProbeTransportReceiveEvents by remember(
        runtimeGeneration
    ) {
        mutableStateOf<List<AutomatedDiagnosticsApplicationProbeTransportReceiveEvent>>(emptyList())
    }
    var recentAutomatedDiagnosticsApplicationProbeReceiveDiagnostics by remember(
        runtimeGeneration
    ) {
        mutableStateOf<List<AutomatedDiagnosticsApplicationProbeReceiveDiagnostic>>(emptyList())
    }
    var automatedDiagnosticsAcceptedSourceAssociationsByAddress by remember(
        runtimeGeneration
    ) {
        mutableStateOf<Map<String, AutomatedDiagnosticsAcceptedSourceAssociation>>(emptyMap())
    }
    var lastAutomatedDiagnosticsCoordinationStatus by remember(
        runtimeGeneration
    ) {
        mutableStateOf<String?>(null)
    }
    var lastAutomatedDiagnosticsWifiDirectPeerReadyStatus by remember(
        runtimeGeneration
    ) {
        mutableStateOf<String?>(null)
    }
    var lastAutomatedDiagnosticsPhaseStatus by remember(
        runtimeGeneration
    ) {
        mutableStateOf<String?>(null)
    }
    var lastAutomatedDiagnosticsServerReadyStatus by remember(
        runtimeGeneration
    ) {
        mutableStateOf<String?>(null)
    }
    var latestHybridBootstrapSocketEndpointResolution by remember(
        runtimeGeneration
    ) {
        mutableStateOf(
            currentHybridBootstrapSocketEndpointResolution(
                provider = hybridBootstrapDecisionProvider,
                socketHintObservationsByKey = emptyMap()
            )
        )
    }
    val hybridBootstrapRequestedAtMillis = System::currentTimeMillis
    val hybridBootstrapCurrentMonotonicMillis = SystemMonotonicClock::nowMillis
    var hybridBootstrapSocketHintObservationsByKey by remember(
        runtimeGeneration
    ) {
        mutableStateOf<Map<String, HybridBootstrapSocketHintObservation>>(emptyMap())
    }
    var latestHybridBootstrapAttemptDecision by remember(
        runtimeGeneration
    ) {
        mutableStateOf(
            HybridBootstrapAttemptPolicy.decide(
                resolution = latestHybridBootstrapSocketEndpointResolution,
                requestedAtMillis = hybridBootstrapRequestedAtMillis(),
                currentMonotonicMillis = hybridBootstrapCurrentMonotonicMillis(),
                maxEndpointAgeMillis = HybridBootstrapAttemptPolicy.DEFAULT_MAX_ENDPOINT_AGE_MILLIS
            )
        )
    }
    val hybridBootstrapCommandCreatedAtMillis = System::currentTimeMillis
    var latestHybridBootstrapAttemptCommandBuildResult by remember(
        runtimeGeneration
    ) {
        mutableStateOf(
            HybridBootstrapAttemptCommandBuilder.build(
                decision = latestHybridBootstrapAttemptDecision,
                commandCreatedAtMillis = hybridBootstrapCommandCreatedAtMillis()
            )
        )
    }
    var latestHybridBootstrapCommandTriggerResult by remember(
        runtimeGeneration
    ) {
        mutableStateOf(initialHybridBootstrapCommandTriggerResult())
    }
    var latestHybridBootstrapManualTriggerSnapshot by remember(
        runtimeGeneration
    ) {
        mutableStateOf(
            currentHybridBootstrapManualTriggerSnapshot(
                commandBuildResult = latestHybridBootstrapAttemptCommandBuildResult,
                latestTriggerResult = latestHybridBootstrapCommandTriggerResult
            )
        )
    }
    val hybridBootstrapManualOfferCreatedAtMillis = System::currentTimeMillis
    val hybridBootstrapManualAcceptCreatedAtMillis = System::currentTimeMillis
    val hybridBootstrapManualSocketHintCreatedAtMillis = System::currentTimeMillis
    val automatedDiagnosticsServerReadyCreatedAtMillis = System::currentTimeMillis
    var lastHybridBootstrapManualAcceptStatus by remember(
        runtimeGeneration
    ) {
        mutableStateOf<String?>(null)
    }
    var lastHybridBootstrapManualOfferStatus by remember(
        runtimeGeneration
    ) {
        mutableStateOf<String?>(null)
    }
    var lastHybridBootstrapManualSocketHintStatus by remember(
        runtimeGeneration
    ) {
        mutableStateOf<String?>(null)
    }
    fun applyLatestHybridBootstrapDecision(
        decision: HybridBootstrapDecision
    ) {
        latestHybridBootstrapDecision = decision
        latestHybridBootstrapDiagnostics = HybridBootstrapDiagnosticsFormatter.format(decision)
        latestHybridBootstrapSocketEndpointResolution =
            HybridBootstrapSocketEndpointResolver.resolve(
                decision = decision,
                socketHintObservation = currentHybridBootstrapSocketHintObservationForDecision(
                    decision = decision,
                    socketHintObservationsByKey = hybridBootstrapSocketHintObservationsByKey
                )
            )
        latestHybridBootstrapAttemptDecision = HybridBootstrapAttemptPolicy.decide(
            resolution = latestHybridBootstrapSocketEndpointResolution,
            requestedAtMillis = hybridBootstrapRequestedAtMillis(),
            currentMonotonicMillis = hybridBootstrapCurrentMonotonicMillis(),
            maxEndpointAgeMillis = HybridBootstrapAttemptPolicy.DEFAULT_MAX_ENDPOINT_AGE_MILLIS
        )
        latestHybridBootstrapAttemptCommandBuildResult =
            HybridBootstrapAttemptCommandBuilder.build(
                decision = latestHybridBootstrapAttemptDecision,
                commandCreatedAtMillis = hybridBootstrapCommandCreatedAtMillis()
            )
        latestHybridBootstrapManualTriggerSnapshot =
            currentHybridBootstrapManualTriggerSnapshot(
                commandBuildResult = latestHybridBootstrapAttemptCommandBuildResult,
                latestTriggerResult = latestHybridBootstrapCommandTriggerResult
            )
    }
    val hybridBootstrapManualAcceptAvailable = remember(
        runtimeGeneration,
        latestHybridBootstrapDecision,
        bleConnectionStatus,
        activeTransportPeerId,
        bleTransportSender,
        peerSessionDiagnostics,
        localPeerId
    ) {
        currentHybridBootstrapManualAcceptAvailable(
            decision = latestHybridBootstrapDecision,
            bleConnectionStatus = bleConnectionStatus,
            activeTransportPeerId = activeTransportPeerId,
            peerSessionDiagnostics = peerSessionDiagnostics,
            transportSender = bleTransportSender,
            localPeerId = localPeerId
        )
    }
    val hybridBootstrapManualAcceptBlockedReason = remember(
        runtimeGeneration,
        latestHybridBootstrapDecision,
        bleConnectionStatus,
        activeTransportPeerId,
        bleTransportSender,
        peerSessionDiagnostics,
        localPeerId
    ) {
        currentHybridBootstrapManualAcceptBlockedReason(
            decision = latestHybridBootstrapDecision,
            bleConnectionStatus = bleConnectionStatus,
            activeTransportPeerId = activeTransportPeerId,
            peerSessionDiagnostics = peerSessionDiagnostics,
            transportSender = bleTransportSender,
            localPeerId = localPeerId
        )
    }
    val hybridBootstrapManualOfferAvailable = remember(
        runtimeGeneration,
        bleConnectionStatus,
        activeTransportPeerId,
        bleTransportSender,
        peerSessionDiagnostics,
        localPeerId
    ) {
        currentHybridBootstrapManualOfferAvailable(
            bleConnectionStatus = bleConnectionStatus,
            activeTransportPeerId = activeTransportPeerId,
            peerSessionDiagnostics = peerSessionDiagnostics,
            transportSender = bleTransportSender,
            localPeerId = localPeerId
        )
    }
    val hybridBootstrapManualOfferBlockedReason = remember(
        runtimeGeneration,
        bleConnectionStatus,
        activeTransportPeerId,
        bleTransportSender,
        peerSessionDiagnostics,
        localPeerId
    ) {
        currentHybridBootstrapManualOfferBlockedReason(
            bleConnectionStatus = bleConnectionStatus,
            activeTransportPeerId = activeTransportPeerId,
            peerSessionDiagnostics = peerSessionDiagnostics,
            transportSender = bleTransportSender,
            localPeerId = localPeerId
        )
    }
    @Suppress("UNUSED_VARIABLE")
    val hybridBootstrapManualTriggerAction = remember(
        runtimeGeneration,
        hybridBootstrapCommandExecutorConfig
    ) {
        createHybridBootstrapManualTriggerAction(
            buildResultProvider = {
                latestHybridBootstrapAttemptCommandBuildResult
            },
            controllerProvider = {
                currentHybridBootstrapCommandTriggerController(
                    config = hybridBootstrapCommandExecutorConfig,
                    socketConnectorOverride = hybridBootstrapSocketConnectorOverride
                )
            },
            recordResult = { result ->
                latestHybridBootstrapCommandTriggerResult = result
                latestHybridBootstrapManualTriggerSnapshot =
                    currentHybridBootstrapManualTriggerSnapshot(
                        commandBuildResult = latestHybridBootstrapAttemptCommandBuildResult,
                        latestTriggerResult = result
                    )
            }
        )
    }
    @Suppress("UNUSED_VARIABLE")
    val guardedHybridBootstrapManualTriggerAction = remember(
        runtimeGeneration,
        hybridBootstrapManualTriggerAction
    ) {
        {
            triggerHybridBootstrapManuallyIfAvailable(
                snapshot = latestHybridBootstrapManualTriggerSnapshot,
                manualTriggerAction = hybridBootstrapManualTriggerAction
            )
        }
    }
    val onHybridBootstrapManualTriggerRequested = remember(
        runtimeGeneration,
        guardedHybridBootstrapManualTriggerAction
    ) {
        createHybridBootstrapManualTriggerRequestCallback(
            guardedManualTriggerAction = guardedHybridBootstrapManualTriggerAction
        )
    }
    val onHybridBootstrapManualOfferRequested = remember(
        runtimeGeneration,
        bleConnectionStatus,
        activeTransportPeerId,
        bleTransportSender,
        peerSessionDiagnostics,
        localPeerId,
        hybridTransportControlStore,
        hybridBootstrapDecisionProvider
    ) {
        createHybridBootstrapManualOfferRequestCallback {
            val createdAtMillis = hybridBootstrapManualOfferCreatedAtMillis()
            val result = submitHybridBootstrapManualOffer(
                bleConnectionStatus = bleConnectionStatus,
                activeTransportPeerId = activeTransportPeerId,
                peerSessionDiagnostics = peerSessionDiagnostics,
                transportSender = bleTransportSender,
                localPeerId = localPeerId,
                createdAtMillis = createdAtMillis
            )
            if (result is HybridBootstrapManualOfferSendResult.Sent) {
                val localSenderPeerId = localPeerId?.trim()?.takeIf { it.isNotEmpty() }
                if (localSenderPeerId != null) {
                    applyLatestHybridBootstrapDecision(
                        recordLocallySentHybridBootstrapControlMessage(
                            targetPeerId = result.peerId,
                            message = createHybridBootstrapManualOfferMessage(
                                localPeerId = localSenderPeerId,
                                createdAtMillis = createdAtMillis
                            ),
                            hybridControlStore = hybridTransportControlStore,
                            provider = hybridBootstrapDecisionProvider
                        )
                    )
                }
            }
            lastHybridBootstrapManualOfferStatus =
                hybridBootstrapManualOfferRuntimeStatusText(result)
            result
        }
    }
    val onHybridBootstrapManualAcceptRequested = remember(
        runtimeGeneration,
        latestHybridBootstrapDecision,
        bleConnectionStatus,
        activeTransportPeerId,
        bleTransportSender,
        peerSessionDiagnostics,
        localPeerId,
        hybridTransportControlStore,
        hybridBootstrapDecisionProvider
    ) {
        createHybridBootstrapManualAcceptRequestCallback {
            val createdAtMillis = hybridBootstrapManualAcceptCreatedAtMillis()
            val result = submitHybridBootstrapManualAccept(
                decision = latestHybridBootstrapDecision,
                bleConnectionStatus = bleConnectionStatus,
                activeTransportPeerId = activeTransportPeerId,
                peerSessionDiagnostics = peerSessionDiagnostics,
                transportSender = bleTransportSender,
                localPeerId = localPeerId,
                createdAtMillis = createdAtMillis
            )
            if (result is HybridBootstrapManualAcceptSendResult.Sent) {
                val localSenderPeerId = localPeerId?.trim()?.takeIf { it.isNotEmpty() }
                if (localSenderPeerId != null) {
                    applyLatestHybridBootstrapDecision(
                        recordLocallySentHybridBootstrapControlMessage(
                            targetPeerId = result.peerId,
                            message = createHybridBootstrapManualAcceptMessage(
                                localPeerId = localSenderPeerId,
                                sessionId = result.sessionId,
                                createdAtMillis = createdAtMillis
                            ),
                            hybridControlStore = hybridTransportControlStore,
                            provider = hybridBootstrapDecisionProvider
                        )
                    )
                }
            }
            lastHybridBootstrapManualAcceptStatus =
                hybridBootstrapManualAcceptRuntimeStatusText(result)
            result
        }
    }
    val onHybridBootstrapManualSocketHintRequested = remember(
        runtimeGeneration,
        latestHybridBootstrapDecision,
        bleConnectionStatus,
        activeTransportPeerId,
        peerSessionDiagnostics,
        bleTransportSender,
        localPeerId,
        wifiDirectRuntimeStatus.connectionStatus,
        hybridTransportControlStore,
        hybridBootstrapDecisionProvider
    ) {
        createHybridBootstrapManualSocketHintRequestCallback {
            val createdAtMillis = hybridBootstrapManualSocketHintCreatedAtMillis()
            val result = submitHybridBootstrapManualSocketHint(
                decision = latestHybridBootstrapDecision,
                bleConnectionStatus = bleConnectionStatus,
                activeTransportPeerId = activeTransportPeerId,
                peerSessionDiagnostics = peerSessionDiagnostics,
                transportSender = bleTransportSender,
                localPeerId = localPeerId,
                wifiDirectConnectionStatus = wifiDirectRuntimeStatus.connectionStatus,
                socketPort = wifiDirectDebugSocketPort,
                createdAtMillis = createdAtMillis
            )
            if (result is HybridBootstrapManualSocketHintSendResult.Sent) {
                val localSenderPeerId = localPeerId?.trim()?.takeIf { it.isNotEmpty() }
                if (localSenderPeerId != null) {
                    applyLatestHybridBootstrapDecision(
                        recordLocallySentHybridBootstrapControlMessage(
                            targetPeerId = result.peerId,
                            message = createHybridBootstrapManualSocketHintMessage(
                                localPeerId = localSenderPeerId,
                                sessionId = result.sessionId,
                                groupOwnerAddress = result.groupOwnerAddress,
                                socketPort = result.socketPort,
                                createdAtMillis = createdAtMillis
                            ),
                            hybridControlStore = hybridTransportControlStore,
                            provider = hybridBootstrapDecisionProvider
                        )
                    )
                }
            }
            lastHybridBootstrapManualSocketHintStatus =
                hybridBootstrapManualSocketHintRuntimeStatusText(result)
            result
        }
    }
    val onAutomatedDiagnosticsRunAnnouncementRequested: suspend (
        AutomatedDiagnosticsSharedRun
    ) -> AutomatedDiagnosticsRunAnnouncementSendResult = remember(
        runtimeGeneration,
        bleConnectionStatus,
        activeTransportPeerId,
        bleTransportSender,
        localPeerId
    ) {
        val callback: suspend (
            AutomatedDiagnosticsSharedRun
        ) -> AutomatedDiagnosticsRunAnnouncementSendResult = { sharedRun ->
            val result = submitAutomatedDiagnosticsRunAnnouncement(
                bleConnectionStatus = bleConnectionStatus,
                activeTransportPeerId = activeTransportPeerId,
                transportSender = bleTransportSender,
                localPeerId = localPeerId,
                sharedRun = sharedRun
            )
            lastAutomatedDiagnosticsCoordinationStatus =
                automatedDiagnosticsRunAnnouncementSendStatusText(result)
            result
        }
        callback
    }
    val onAutomatedDiagnosticsParticipantJoinRequested: suspend (
        AutomatedDiagnosticsSharedRun
    ) -> AutomatedDiagnosticsParticipantJoinSendResult = remember(
        runtimeGeneration,
        bleConnectionStatus,
        activeTransportPeerId,
        bleTransportSender,
        localPeerId
    ) {
        val callback: suspend (
            AutomatedDiagnosticsSharedRun
        ) -> AutomatedDiagnosticsParticipantJoinSendResult = { sharedRun ->
            val createdAtMillis = automatedDiagnosticsServerReadyCreatedAtMillis()
            val result = submitAutomatedDiagnosticsParticipantJoin(
                bleConnectionStatus = bleConnectionStatus,
                activeTransportPeerId = activeTransportPeerId,
                transportSender = bleTransportSender,
                localPeerId = localPeerId,
                sharedRun = sharedRun,
                createdAtMillis = createdAtMillis
            )
            lastAutomatedDiagnosticsCoordinationStatus =
                automatedDiagnosticsParticipantJoinSendStatusText(result)
            result
        }
        callback
    }
    val clearAutomatedDiagnosticsSharedRunCoordinationState: () -> Unit = remember(runtimeGeneration) {
        {
            latestAutomatedDiagnosticsRunAnnouncement = null
            latestAutomatedDiagnosticsParticipantJoin = null
            lastAutomatedDiagnosticsCoordinationStatus = null
        }
    }
    val clearAutomatedDiagnosticsCoordinationState: () -> Unit = remember(
        runtimeGeneration,
        clearAutomatedDiagnosticsSharedRunCoordinationState
    ) {
        {
            clearAutomatedDiagnosticsSharedRunCoordinationState()
            latestAutomatedDiagnosticsWifiDirectPeerReadySignal = null
            lastAutomatedDiagnosticsWifiDirectPeerReadyStatus = null
            latestAutomatedDiagnosticsPhaseSignal = null
            latestAutomatedDiagnosticsPhaseSignalsByStep = emptyMap()
            lastAutomatedDiagnosticsPhaseStatus = null
            latestAutomatedDiagnosticsServerReadySignal = null
            latestAutomatedDiagnosticsHybridAcceptObservation = null
            latestAutomatedDiagnosticsHybridSocketHintObservation = null
            recentBleTransportLocalSendTraces = emptyList()
            recentAutomatedDiagnosticsApplicationProbeObservations = emptyList()
            recentAutomatedDiagnosticsApplicationProbeTransportReceiveEvents = emptyList()
            recentAutomatedDiagnosticsApplicationProbeReceiveDiagnostics = emptyList()
            automatedDiagnosticsAcceptedSourceAssociationsByAddress = emptyMap()
            lastAutomatedDiagnosticsServerReadyStatus = null
            wifiDirectRuntimeState.clearAutomatedDiagnosticsServiceDiscovery()
        }
    }
    val recordAcceptedAutomatedDiagnosticsPhaseSignalSourceAssociation: (
        AutomatedDiagnosticsPhaseSignal
    ) -> Unit = remember(runtimeGeneration) {
        { signal ->
            val sourceDeviceAddress = signal.sourceDeviceAddress
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return@remember
            automatedDiagnosticsAcceptedSourceAssociationsByAddress =
                automatedDiagnosticsAcceptedSourceAssociationsByAddress +
                    (
                        sourceDeviceAddress to
                            AutomatedDiagnosticsAcceptedSourceAssociation.from(signal)
                        )
        }
    }
    val onAutomatedDiagnosticsWifiDirectPeerReadyRequested: suspend (
        AutomatedDiagnosticsSharedRun,
        String,
        String,
        String?
    ) -> AutomatedDiagnosticsWifiDirectPeerReadySendResult = remember(
        runtimeGeneration,
        bleConnectionStatus,
        activeTransportPeerId,
        bleTransportSender,
        localPeerId
    ) {
        val callback: suspend (
            AutomatedDiagnosticsSharedRun,
            String,
            String,
            String?
        ) -> AutomatedDiagnosticsWifiDirectPeerReadySendResult =
            {
                    sharedRun,
                    expectedRemotePeerId,
                    wifiDirectCorrelationToken,
                    wifiDirectDeviceName ->
                val createdAtMillis = automatedDiagnosticsServerReadyCreatedAtMillis()
                val result = submitAutomatedDiagnosticsWifiDirectPeerReadySignal(
                    bleConnectionStatus = bleConnectionStatus,
                    activeTransportPeerId = activeTransportPeerId,
                    transportSender = bleTransportSender,
                    localPeerId = localPeerId,
                    sharedRun = sharedRun,
                    expectedRemotePeerId = expectedRemotePeerId,
                    wifiDirectCorrelationToken = wifiDirectCorrelationToken,
                    wifiDirectDeviceName = wifiDirectDeviceName,
                    createdAtMillis = createdAtMillis
                )
                lastAutomatedDiagnosticsWifiDirectPeerReadyStatus =
                    automatedDiagnosticsWifiDirectPeerReadySendStatusText(result)
                if (result is AutomatedDiagnosticsWifiDirectPeerReadySendResult.Sent) {
                    latestAutomatedDiagnosticsWifiDirectPeerReadySignal = result.signal.copy(
                        peerId = localPeerId ?: result.signal.peerId,
                        createdAtMillis = createdAtMillis
                    )
                }
                result
        }
        callback
    }
    val onAutomatedDiagnosticsPhaseStateRequested: suspend (
        AutomatedDiagnosticsSharedRun,
        String,
        AutomatedDiagnosticStepId,
        AutomatedDiagnosticsPhaseState,
        Int,
        List<AutomatedDiagnosticsPhaseApplicationProbeDescriptor>
    ) -> AutomatedDiagnosticsPhaseStateSendResult = remember(
        runtimeGeneration,
        bleConnectionStatus,
        activeTransportPeerId,
        bleTransportSender,
        localPeerId
    ) {
        val callback: suspend (
            AutomatedDiagnosticsSharedRun,
            String,
            AutomatedDiagnosticStepId,
            AutomatedDiagnosticsPhaseState,
            Int,
            List<AutomatedDiagnosticsPhaseApplicationProbeDescriptor>
        ) -> AutomatedDiagnosticsPhaseStateSendResult =
            { sharedRun, expectedRemotePeerId, stepId, phaseState, attemptNumber, applicationProbeDescriptors ->
                val createdAtMillis = automatedDiagnosticsServerReadyCreatedAtMillis()
                val result = submitAutomatedDiagnosticsPhaseStateSignal(
                    bleConnectionStatus = bleConnectionStatus,
                    activeTransportPeerId = activeTransportPeerId,
                    transportSender = bleTransportSender,
                    localPeerId = localPeerId,
                    sharedRun = sharedRun,
                    expectedRemotePeerId = expectedRemotePeerId,
                    stepId = stepId,
                    phaseState = phaseState,
                    attemptNumber = attemptNumber,
                    applicationProbeDescriptors = applicationProbeDescriptors,
                    createdAtMillis = createdAtMillis
                )
                lastAutomatedDiagnosticsPhaseStatus =
                    automatedDiagnosticsPhaseStateSendStatusText(result)
                result
            }
        callback
    }
    val onAutomatedDiagnosticsServerReadyRequested: suspend (
        AutomatedDiagnosticsSharedRun,
        String,
        String,
        Int,
        Long
    ) -> AutomatedDiagnosticsServerReadySendResult = remember(
        runtimeGeneration,
        bleConnectionStatus,
        activeTransportPeerId,
        bleTransportSender,
        localPeerId
    ) {
        val callback: suspend (
            AutomatedDiagnosticsSharedRun,
            String,
            String,
            Int,
            Long
        ) -> AutomatedDiagnosticsServerReadySendResult =
            { sharedRun, expectedClientPeerId, groupOwnerAddress, socketPort, serverToken ->
                val createdAtMillis = automatedDiagnosticsServerReadyCreatedAtMillis()
                val result = submitAutomatedDiagnosticsServerReadySignal(
                    bleConnectionStatus = bleConnectionStatus,
                    activeTransportPeerId = activeTransportPeerId,
                    transportSender = bleTransportSender,
                    localPeerId = localPeerId,
                    sharedRun = sharedRun,
                    expectedClientPeerId = expectedClientPeerId,
                    groupOwnerAddress = groupOwnerAddress,
                    socketPort = socketPort,
                    serverToken = serverToken,
                    createdAtMillis = createdAtMillis
                )
                lastAutomatedDiagnosticsServerReadyStatus =
                    automatedDiagnosticsServerReadySendStatusText(result)
                if (result is AutomatedDiagnosticsServerReadySendResult.Sent) {
                    latestAutomatedDiagnosticsServerReadySignal = result.signal.copy(
                        peerId = localPeerId ?: result.signal.peerId,
                        createdAtMillis = createdAtMillis
                    )
                }
                result
            }
        callback
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
        val currentHybridMonotonicMillis = hybridBootstrapCurrentMonotonicMillis()
        peerSessionDiagnostics = peerSessionRegistry.diagnosticsSnapshot()
        identityExchangeRuntimeStatusText(result)?.let { statusText ->
            lastIdentityExchangeStatus = statusText
        }
        hybridBootstrapSocketHintObservationAfterReceiveOrNull(
            result = result,
            observedAtMonotonicMillis = currentHybridMonotonicMillis
        )?.let { observation ->
            hybridBootstrapSocketHintObservationsByKey =
                hybridBootstrapSocketHintObservationsByKey +
                    (hybridBootstrapSocketHintObservationKey(observation) to observation)
        }
        hybridBootstrapDecisionAfterReceiveOrNull(
            result = result,
            provider = hybridBootstrapDecisionProvider
        )?.let { decision ->
            latestHybridBootstrapDecision = decision
            latestHybridBootstrapDiagnostics = HybridBootstrapDiagnosticsFormatter.format(decision)
            latestHybridBootstrapSocketEndpointResolution =
                HybridBootstrapSocketEndpointResolver.resolve(
                    decision = decision,
                    socketHintObservation = currentHybridBootstrapSocketHintObservationForDecision(
                        decision = decision,
                        socketHintObservationsByKey = hybridBootstrapSocketHintObservationsByKey
                    )
                )
            latestHybridBootstrapAttemptDecision = HybridBootstrapAttemptPolicy.decide(
                resolution = latestHybridBootstrapSocketEndpointResolution,
                requestedAtMillis = hybridBootstrapRequestedAtMillis(),
                currentMonotonicMillis = currentHybridMonotonicMillis,
                maxEndpointAgeMillis = HybridBootstrapAttemptPolicy.DEFAULT_MAX_ENDPOINT_AGE_MILLIS
            )
            latestHybridBootstrapAttemptCommandBuildResult =
                HybridBootstrapAttemptCommandBuilder.build(
                    decision = latestHybridBootstrapAttemptDecision,
                    commandCreatedAtMillis = hybridBootstrapCommandCreatedAtMillis()
                )
            latestHybridBootstrapManualTriggerSnapshot =
                currentHybridBootstrapManualTriggerSnapshot(
                    commandBuildResult = latestHybridBootstrapAttemptCommandBuildResult,
                    latestTriggerResult = latestHybridBootstrapCommandTriggerResult
                )
        }
        automatedDiagnosticsRunAnnouncementAfterReceiveOrNull(result)?.let { announcement ->
            latestAutomatedDiagnosticsRunAnnouncement = announcement
            lastAutomatedDiagnosticsCoordinationStatus =
                automatedDiagnosticsRunAnnouncementStatusText(announcement)
        }
        automatedDiagnosticsParticipantJoinAfterReceiveOrNull(result)?.let { join ->
            latestAutomatedDiagnosticsParticipantJoin = join
            lastAutomatedDiagnosticsCoordinationStatus =
                automatedDiagnosticsParticipantJoinStatusText(join)
        }
        automatedDiagnosticsWifiDirectPeerReadySignalAfterReceiveOrNull(result)?.let { signal ->
            latestAutomatedDiagnosticsWifiDirectPeerReadySignal = signal
            lastAutomatedDiagnosticsWifiDirectPeerReadyStatus =
                automatedDiagnosticsWifiDirectPeerReadyStatusText(signal)
        }
        automatedDiagnosticsPhaseStateAfterReceiveOrNull(result)?.let { signal ->
            val mergedSignal = mergeAutomatedDiagnosticsPhaseSignal(
                current = latestAutomatedDiagnosticsPhaseSignalsByStep[signal.stepId],
                incoming = signal
            )
            latestAutomatedDiagnosticsPhaseSignal = mergeAutomatedDiagnosticsPhaseSignal(
                current = latestAutomatedDiagnosticsPhaseSignal,
                incoming = mergedSignal
            )
            latestAutomatedDiagnosticsPhaseSignalsByStep =
                latestAutomatedDiagnosticsPhaseSignalsByStep +
                    (signal.stepId to mergedSignal)
            lastAutomatedDiagnosticsPhaseStatus =
                automatedDiagnosticsPhaseStateStatusText(mergedSignal)
        }
        automatedDiagnosticsServerReadySignalAfterReceiveOrNull(result)?.let { signal ->
            latestAutomatedDiagnosticsServerReadySignal = signal
            lastAutomatedDiagnosticsServerReadyStatus =
                automatedDiagnosticsServerReadyStatusText(signal)
        }
        automatedDiagnosticsHybridAcceptObservationAfterReceiveOrNull(
            result = result,
            observedAtMonotonicMillis = currentHybridMonotonicMillis
        )?.let { observation ->
            latestAutomatedDiagnosticsHybridAcceptObservation = observation
        }
        automatedDiagnosticsHybridSocketHintObservationAfterReceiveOrNull(
            result = result,
            observedAtMonotonicMillis = currentHybridMonotonicMillis
        )?.let { observation ->
            latestAutomatedDiagnosticsHybridSocketHintObservation = observation
        }
        automatedDiagnosticsApplicationProbeTransportReceiveEventAfterReceiveOrNull(
            result = result,
            observedAtMonotonicMillis = currentHybridMonotonicMillis,
            observedAtWallClockMillis = System.currentTimeMillis()
        )?.let { event ->
            recentAutomatedDiagnosticsApplicationProbeTransportReceiveEvents =
                appendAutomatedDiagnosticsApplicationProbeTransportReceiveEvent(
                    events = recentAutomatedDiagnosticsApplicationProbeTransportReceiveEvents,
                    event = event
                )
        }
        val applicationProbeReceiveDiagnostic =
            automatedDiagnosticsApplicationProbeReceiveDiagnosticAfterReceiveOrNull(
                result = result,
                sourceDeviceAddress = if (result is BleTransportReceiveResult.Processed) {
                    result.sourceDeviceAddress
                } else {
                    null
                },
                activeTransportPeerId = activeTransportPeerId,
                activeTransportDeviceAddress = activeTransportDeviceAddress,
                reachablePeers = discoveredAuroraPeers,
                selectedSecurePeerId = stateHolder.uiState.selectedSecurePeerId,
                diagnosticsSourceAssociationsByAddress =
                    automatedDiagnosticsAcceptedSourceAssociationsByAddress,
                receiverPeerId = localPeerId,
                observedAtMonotonicMillis = currentHybridMonotonicMillis,
                observedAtWallClockMillis = System.currentTimeMillis()
            )
        applicationProbeReceiveDiagnostic?.let { diagnostic ->
            recentAutomatedDiagnosticsApplicationProbeReceiveDiagnostics =
                appendAutomatedDiagnosticsApplicationProbeReceiveDiagnostic(
                    diagnostics = recentAutomatedDiagnosticsApplicationProbeReceiveDiagnostics,
                    diagnostic = diagnostic
                )
            automatedDiagnosticsApplicationProbeObservationFromReceiveDiagnosticOrNull(
                diagnostic
            )?.let { observation ->
                recentAutomatedDiagnosticsApplicationProbeObservations =
                    appendAutomatedDiagnosticsApplicationProbeObservation(
                        observations = recentAutomatedDiagnosticsApplicationProbeObservations,
                        observation = observation
                    )
            }
        }
        val resolvedImmediateSourcePeerId = if (
            result is BleTransportReceiveResult.Processed &&
                (
                    result.processingResult is IncomingTransportFrameProcessingResult.Received ||
                        result.processingResult is IncomingTransportFrameProcessingResult.RelayOnlyEncrypted
                    )
        ) {
            runtimeSourcePeerId(
                sourceDeviceAddress = result.sourceDeviceAddress,
                activeTransportPeerId = activeTransportPeerId,
                activeTransportDeviceAddress = activeTransportDeviceAddress,
                reachablePeers = discoveredAuroraPeers
            )
        } else {
            null
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
            val immediateSourcePeerId = resolvedImmediateSourcePeerId
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
    var automatedDiagnosticsActivityState by remember(lifecycleOwner) {
        mutableStateOf(
            automatedDiagnosticsActivityLifecycleStateFor(
                lifecycleOwner.lifecycle.currentState
            )
        )
    }
    var automatedDiagnosticsLastCleanupReason by remember {
        mutableStateOf<String?>(null)
    }
    var automatedDiagnosticsRecentEvents by remember {
        mutableStateOf(emptyList<AutomatedDiagnosticsRuntimeEvent>())
    }
    val appendAutomatedDiagnosticsEvent: (
        AutomatedDiagnosticsRuntimeEvent.Category,
        String
    ) -> Unit = remember {
        { category, detail ->
            val sanitizedDetail = detail.trim()
            if (sanitizedDetail.isEmpty()) {
                Unit
            } else {
                val nextEvent = AutomatedDiagnosticsRuntimeEvent(
                    atElapsedMillis = SystemMonotonicClock.nowMillis(),
                    category = category,
                    detail = sanitizedDetail
                )
                automatedDiagnosticsRecentEvents =
                    (automatedDiagnosticsRecentEvents + nextEvent)
                        .takeLast(automatedDiagnosticsRuntimeEventLimit)
            }
        }
    }
    val updateAutomatedDiagnosticsActivityState: (
        AutomatedDiagnosticsActivityLifecycleState
    ) -> Unit = remember(appendAutomatedDiagnosticsEvent) {
        { nextState ->
            if (automatedDiagnosticsActivityState != nextState) {
                automatedDiagnosticsActivityState = nextState
                appendAutomatedDiagnosticsEvent(
                    AutomatedDiagnosticsRuntimeEvent.Category.ACTIVITY,
                    "Activity lifecycle -> ${nextState.name}"
                )
            }
        }
    }
    val recordAutomatedDiagnosticsCleanup: (String) -> Unit = remember(
        appendAutomatedDiagnosticsEvent
    ) {
        { reason ->
            automatedDiagnosticsLastCleanupReason = reason
            appendAutomatedDiagnosticsEvent(
                AutomatedDiagnosticsRuntimeEvent.Category.CLEANUP,
                reason
            )
        }
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
    LaunchedEffect(shouldHostRuntime) {
        appendAutomatedDiagnosticsEvent(
            AutomatedDiagnosticsRuntimeEvent.Category.BLE_RUNTIME,
            "BLE runtime hosted=$shouldHostRuntime"
        )
    }
    LaunchedEffect(bleAdvertiseStatus) {
        appendAutomatedDiagnosticsEvent(
            AutomatedDiagnosticsRuntimeEvent.Category.ADVERTISER,
            "Advertiser -> ${bleAdvertiseStatus.name}"
        )
    }
    LaunchedEffect(bleScanStatus) {
        appendAutomatedDiagnosticsEvent(
            AutomatedDiagnosticsRuntimeEvent.Category.SCANNER,
            "Scanner -> ${bleScanStatus.name}"
        )
    }
    LaunchedEffect(bleGattServerStatus) {
        appendAutomatedDiagnosticsEvent(
            AutomatedDiagnosticsRuntimeEvent.Category.GATT,
            "GATT -> ${bleGattServerStatus.name}"
        )
    }
    LaunchedEffect(bleConnectionStatus, activeTransportPeerId) {
        appendAutomatedDiagnosticsEvent(
            AutomatedDiagnosticsRuntimeEvent.Category.CONNECTION,
            "Connection -> ${bleConnectionStatus.name} peer=${activeTransportPeerId ?: "none"}"
        )
    }

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

    fun stopHostedRuntime(
        cleanupReason: String
    ) {
        recordAutomatedDiagnosticsCleanup(cleanupReason)
        bleConnector.disconnect()
        clearTransportConnectionState()
        clearRuntimeDiscoveryState(stopScanner = true)
        bleAdvertiser.stop()
        bleAdvertiseStatus = BleAdvertiseStatus.STOPPED
        bleGattServer.stop()
        bleGattServerStatus = BleGattServerStatus.STOPPED
        transportFrameBridge.clear()
        automatedDiagnosticsAcceptedSourceAssociationsByAddress = emptyMap()
        recentBleTransportLocalSendTraces = emptyList()
        recentAutomatedDiagnosticsApplicationProbeTransportReceiveEvents = emptyList()
        recentAutomatedDiagnosticsApplicationProbeReceiveDiagnostics = emptyList()
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
        stopHostedRuntime("BLE runtime cleanup: local identity reset requested.")
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
                updateAutomatedDiagnosticsActivityState(
                    AutomatedDiagnosticsActivityLifecycleState.RESUMED
                )
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                updateAutomatedDiagnosticsActivityState(
                    AutomatedDiagnosticsActivityLifecycleState.PAUSED
                )
            } else if (event == Lifecycle.Event.ON_STOP) {
                isAppVisible = false
                updateAutomatedDiagnosticsActivityState(
                    AutomatedDiagnosticsActivityLifecycleState.STOPPED
                )
                stopHostedRuntime("BLE runtime cleanup: activity ON_STOP.")
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stopHostedRuntime("BLE runtime cleanup: runtime host disposed.")
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

    val automatedDiagnosticsRuntimeEvidence = AutomatedDiagnosticsRuntimeEvidence(
        activityLifecycleState = automatedDiagnosticsActivityState,
        bleRuntimeHosted = shouldHostRuntime,
        lastCleanupReason = automatedDiagnosticsLastCleanupReason,
        recentEvents = automatedDiagnosticsRecentEvents
    )

    return AuroraBleRuntimeState(
        bluetoothPermissionStatus = bluetoothStatus,
        refreshBluetoothStatus = bluetoothStatusState.refresh,
        bleAdvertiseStatus = bleAdvertiseStatus,
        bleGattServerStatus = bleGattServerStatus,
        bleScanStatus = bleScanStatus,
        bleScanDiagnostics = bleScanDiagnostics,
        discoveredAuroraPeers = discoveredAuroraPeers,
        bleConnector = bleConnector,
        bleConnectionStatus = bleConnectionStatus,
        activeTransportDeviceAddress = activeTransportDeviceAddress,
        activeTransportPeerId = activeTransportPeerId,
        localPeerId = localPeerId,
        bleTransportSender = bleTransportSender,
        transportSenderSourceLabel = transportSenderSourceLabel,
        wifiDirectRuntimeStatus = wifiDirectRuntimeStatus,
        refreshWifiDirectStatus = wifiDirectRuntimeState.refresh,
        startWifiDirectDiscovery = wifiDirectRuntimeState.startDiscovery,
        stopWifiDirectDiscovery = wifiDirectRuntimeState.stopDiscovery,
        registerAutomatedDiagnosticsWifiDirectService =
        wifiDirectRuntimeState.registerAutomatedDiagnosticsService,
        startAutomatedDiagnosticsWifiDirectServiceDiscovery =
        wifiDirectRuntimeState.startAutomatedDiagnosticsServiceDiscovery,
        clearAutomatedDiagnosticsWifiDirectServiceDiscovery =
        wifiDirectRuntimeState.clearAutomatedDiagnosticsServiceDiscovery,
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
        runtimeEvidence = automatedDiagnosticsRuntimeEvidence,
        hybridBootstrapJavaNetRuntimeEnabled = hybridBootstrapJavaNetRuntimeEnabled(),
        hybridBootstrapCommandExecutorMode = hybridBootstrapCommandExecutorConfig.mode,
        hybridBootstrapDecision = latestHybridBootstrapDecision,
        hybridBootstrapDiagnostics = latestHybridBootstrapDiagnostics,
        latestAutomatedDiagnosticsRunAnnouncement = latestAutomatedDiagnosticsRunAnnouncement,
        latestAutomatedDiagnosticsParticipantJoin = latestAutomatedDiagnosticsParticipantJoin,
        latestAutomatedDiagnosticsWifiDirectPeerReadySignal =
        latestAutomatedDiagnosticsWifiDirectPeerReadySignal,
        latestAutomatedDiagnosticsPhaseSignal = latestAutomatedDiagnosticsPhaseSignal,
        latestAutomatedDiagnosticsPhaseSignalsByStep =
        latestAutomatedDiagnosticsPhaseSignalsByStep,
        latestAutomatedDiagnosticsServerReadySignal = latestAutomatedDiagnosticsServerReadySignal,
        latestAutomatedDiagnosticsHybridAcceptObservation =
        latestAutomatedDiagnosticsHybridAcceptObservation,
        latestAutomatedDiagnosticsHybridSocketHintObservation =
        latestAutomatedDiagnosticsHybridSocketHintObservation,
        recentBleTransportLocalSendTraces = recentBleTransportLocalSendTraces,
        recentAutomatedDiagnosticsApplicationProbeObservations =
        recentAutomatedDiagnosticsApplicationProbeObservations,
        recentAutomatedDiagnosticsApplicationProbeTransportReceiveEvents =
        recentAutomatedDiagnosticsApplicationProbeTransportReceiveEvents,
        recentAutomatedDiagnosticsApplicationProbeReceiveDiagnostics =
        recentAutomatedDiagnosticsApplicationProbeReceiveDiagnostics,
        lastAutomatedDiagnosticsCoordinationStatus = lastAutomatedDiagnosticsCoordinationStatus,
        lastAutomatedDiagnosticsWifiDirectPeerReadyStatus =
        lastAutomatedDiagnosticsWifiDirectPeerReadyStatus,
        lastAutomatedDiagnosticsPhaseStatus = lastAutomatedDiagnosticsPhaseStatus,
        lastAutomatedDiagnosticsServerReadyStatus = lastAutomatedDiagnosticsServerReadyStatus,
        clearAutomatedDiagnosticsSharedRunCoordinationState =
        clearAutomatedDiagnosticsSharedRunCoordinationState,
        clearAutomatedDiagnosticsCoordinationState = clearAutomatedDiagnosticsCoordinationState,
        hybridBootstrapManualTriggerSnapshot = latestHybridBootstrapManualTriggerSnapshot,
        onHybridBootstrapManualTriggerRequested = onHybridBootstrapManualTriggerRequested,
        hybridBootstrapManualAcceptAvailable = hybridBootstrapManualAcceptAvailable,
        hybridBootstrapManualAcceptBlockedReason = hybridBootstrapManualAcceptBlockedReason,
        lastHybridBootstrapManualAcceptStatus = lastHybridBootstrapManualAcceptStatus,
        onHybridBootstrapManualAcceptRequested = onHybridBootstrapManualAcceptRequested,
        hybridBootstrapManualOfferAvailable = hybridBootstrapManualOfferAvailable,
        hybridBootstrapManualOfferBlockedReason = hybridBootstrapManualOfferBlockedReason,
        lastHybridBootstrapManualOfferStatus = lastHybridBootstrapManualOfferStatus,
        onHybridBootstrapManualOfferRequested = onHybridBootstrapManualOfferRequested,
        lastHybridBootstrapManualSocketHintStatus = lastHybridBootstrapManualSocketHintStatus,
        onAutomatedDiagnosticsRunAnnouncementRequested =
        onAutomatedDiagnosticsRunAnnouncementRequested,
        onAutomatedDiagnosticsParticipantJoinRequested =
        onAutomatedDiagnosticsParticipantJoinRequested,
        onAutomatedDiagnosticsWifiDirectPeerReadyRequested =
        onAutomatedDiagnosticsWifiDirectPeerReadyRequested,
        onAutomatedDiagnosticsPhaseStateRequested =
        onAutomatedDiagnosticsPhaseStateRequested,
        onAutomatedDiagnosticsServerReadyRequested = onAutomatedDiagnosticsServerReadyRequested,
        onHybridBootstrapManualSocketHintRequested = onHybridBootstrapManualSocketHintRequested,
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
        runtimeState.recordAcceptedAutomatedDiagnosticsPhaseSignalSourceAssociation =
            recordAcceptedAutomatedDiagnosticsPhaseSignalSourceAssociation
        runtimeState.setHybridBootstrapSocketConnectorOverride = { connector ->
            hybridBootstrapSocketConnectorOverride = connector
        }
    }
}

private fun automatedDiagnosticsActivityLifecycleStateFor(
    lifecycleState: Lifecycle.State
): AutomatedDiagnosticsActivityLifecycleState {
    return when {
        lifecycleState == Lifecycle.State.DESTROYED ->
            AutomatedDiagnosticsActivityLifecycleState.DISPOSED
        lifecycleState.isAtLeast(Lifecycle.State.RESUMED) ->
            AutomatedDiagnosticsActivityLifecycleState.RESUMED
        lifecycleState.isAtLeast(Lifecycle.State.STARTED) ->
            AutomatedDiagnosticsActivityLifecycleState.PAUSED
        else ->
            AutomatedDiagnosticsActivityLifecycleState.INITIALIZED
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
    transportFrameWriter: BleGattTransportFrameWriter?,
    onLocalSendTraceReady: ((BleTransportLocalSendTrace) -> Unit)? = null
): BleTransportSender {
    return if (transportFrameWriter == null) {
        NoOpBleTransportSender()
    } else {
        AndroidBleTransportSender(
            frameWriter = transportFrameWriter,
            onLocalSendTraceReady = onLocalSendTraceReady
        )
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

internal fun automatedDiagnosticsRunAnnouncementAfterReceiveOrNull(
    result: BleTransportReceiveResult
): AutomatedDiagnosticsRunAnnouncement? {
    return when (result) {
        is BleTransportReceiveResult.Processed -> {
            when (val processingResult = result.processingResult) {
                is IncomingTransportFrameProcessingResult.HybridControlHandled -> {
                    if (
                        processingResult.controlMessage.messageType ==
                        HybridTransportControlMessage.MessageType.AUTOMATED_DIAGNOSTICS_RUN_ANNOUNCE
                    ) {
                        hybridTransportControlMessageAsRunAnnouncementOrNull(
                            peerId = processingResult.peerId,
                            message = processingResult.controlMessage
                        )
                    } else {
                        null
                    }
                }
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

internal fun automatedDiagnosticsParticipantJoinAfterReceiveOrNull(
    result: BleTransportReceiveResult
): AutomatedDiagnosticsParticipantJoin? {
    return when (result) {
        is BleTransportReceiveResult.Processed -> {
            when (val processingResult = result.processingResult) {
                is IncomingTransportFrameProcessingResult.HybridControlHandled -> {
                    if (
                        processingResult.controlMessage.messageType ==
                        HybridTransportControlMessage.MessageType.AUTOMATED_DIAGNOSTICS_PARTICIPANT_JOIN
                    ) {
                        hybridTransportControlMessageAsParticipantJoinOrNull(
                            peerId = processingResult.peerId,
                            message = processingResult.controlMessage
                        )
                    } else {
                        null
                    }
                }
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

internal fun automatedDiagnosticsWifiDirectPeerReadySignalAfterReceiveOrNull(
    result: BleTransportReceiveResult
): AutomatedDiagnosticsWifiDirectPeerReadySignal? {
    return when (result) {
        is BleTransportReceiveResult.Processed -> {
            when (val processingResult = result.processingResult) {
                is IncomingTransportFrameProcessingResult.HybridControlHandled -> {
                    if (
                        processingResult.controlMessage.messageType ==
                        HybridTransportControlMessage.MessageType.AUTOMATED_DIAGNOSTICS_PHASE_READY
                    ) {
                        hybridTransportControlMessageAsWifiDirectPeerReadySignalOrNull(
                            peerId = processingResult.peerId,
                            message = processingResult.controlMessage
                        )
                    } else {
                        null
                    }
                }
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

internal fun automatedDiagnosticsPhaseStateAfterReceiveOrNull(
    result: BleTransportReceiveResult
): AutomatedDiagnosticsPhaseSignal? {
    return when (result) {
        is BleTransportReceiveResult.Processed -> {
            when (val processingResult = result.processingResult) {
                is IncomingTransportFrameProcessingResult.HybridControlHandled -> {
                    hybridTransportControlMessageAsAutomatedDiagnosticsPhaseSignalOrNull(
                        peerId = processingResult.peerId,
                        message = processingResult.controlMessage
                    )?.copy(
                        sourceDeviceAddress = result.sourceDeviceAddress
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                    )
                }
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

internal fun automatedDiagnosticsServerReadySignalAfterReceiveOrNull(
    result: BleTransportReceiveResult
): AutomatedDiagnosticsServerReadySignal? {
    return when (result) {
        is BleTransportReceiveResult.Processed -> {
            when (val processingResult = result.processingResult) {
                is IncomingTransportFrameProcessingResult.HybridControlHandled -> {
                    if (
                        processingResult.controlMessage.messageType ==
                        HybridTransportControlMessage.MessageType.AUTOMATED_DIAGNOSTICS_SERVER_READY
                    ) {
                        hybridTransportControlMessageAsServerReadySignalOrNull(
                            peerId = processingResult.peerId,
                            message = processingResult.controlMessage
                        )
                    } else {
                        null
                    }
                }
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

internal fun automatedDiagnosticsHybridAcceptObservationAfterReceiveOrNull(
    result: BleTransportReceiveResult,
    observedAtMonotonicMillis: Long
): AutomatedDiagnosticsHybridAcceptObservation? {
    return when (result) {
        is BleTransportReceiveResult.Processed -> {
            when (val processingResult = result.processingResult) {
                is IncomingTransportFrameProcessingResult.HybridControlHandled -> {
                    if (
                        processingResult.controlMessage.messageType ==
                        HybridTransportControlMessage.MessageType.WIFI_DIRECT_ACCEPT
                    ) {
                        AutomatedDiagnosticsHybridAcceptObservation(
                            peerId = processingResult.peerId,
                            sessionId = processingResult.controlMessage.sessionId,
                            publicPeerIdHint = processingResult.controlMessage.publicPeerIdHint,
                            createdAtMillis = processingResult.controlMessage.createdAtMillis,
                            observedAtMonotonicMillis = observedAtMonotonicMillis,
                            storeResult = processingResult.storeResult
                        )
                    } else {
                        null
                    }
                }
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

internal fun automatedDiagnosticsHybridSocketHintObservationAfterReceiveOrNull(
    result: BleTransportReceiveResult,
    observedAtMonotonicMillis: Long
): AutomatedDiagnosticsHybridSocketHintObservation? {
    return when (result) {
        is BleTransportReceiveResult.Processed -> {
            when (val processingResult = result.processingResult) {
                is IncomingTransportFrameProcessingResult.HybridControlHandled -> {
                    if (
                        processingResult.controlMessage.messageType ==
                        HybridTransportControlMessage.MessageType.WIFI_DIRECT_SOCKET_HINT
                    ) {
                        val groupOwnerAddress = processingResult.controlMessage.groupOwnerAddress
                            ?: return null
                        val socketPort = processingResult.controlMessage.socketPort
                            ?: return null
                        AutomatedDiagnosticsHybridSocketHintObservation(
                            peerId = processingResult.peerId,
                            sessionId = processingResult.controlMessage.sessionId,
                            publicPeerIdHint = processingResult.controlMessage.publicPeerIdHint,
                            groupOwnerAddress = groupOwnerAddress,
                            socketPort = socketPort,
                            createdAtMillis = processingResult.controlMessage.createdAtMillis,
                            observedAtMonotonicMillis = observedAtMonotonicMillis,
                            storeResult = processingResult.storeResult
                        )
                    } else {
                        null
                    }
                }
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

internal data class AutomatedDiagnosticsAcceptedSourceAssociation(
    val sharedRunId: String,
    val sessionAssociationId: String,
    val peerId: String,
    val expectedRemotePeerId: String,
    val stepId: AutomatedDiagnosticStepId,
    val attemptNumber: Int,
    val sourceDeviceAddress: String
) {
    companion object {
        fun from(
            signal: AutomatedDiagnosticsPhaseSignal
        ): AutomatedDiagnosticsAcceptedSourceAssociation {
            val sourceDeviceAddress = signal.sourceDeviceAddress
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: error(
                    "Accepted automated diagnostics phase signal sourceDeviceAddress is required."
                )
            return AutomatedDiagnosticsAcceptedSourceAssociation(
                sharedRunId = signal.sharedRun.runId,
                sessionAssociationId = signal.sharedRun.sessionAssociationId,
                peerId = signal.peerId,
                expectedRemotePeerId = signal.expectedRemotePeerId,
                stepId = signal.stepId,
                attemptNumber = signal.attemptNumber,
                sourceDeviceAddress = sourceDeviceAddress
            )
        }
    }
}

internal fun automatedDiagnosticsApplicationProbeTransportReceiveEventAfterReceiveOrNull(
    result: BleTransportReceiveResult,
    observedAtMonotonicMillis: Long,
    observedAtWallClockMillis: Long? = null
): AutomatedDiagnosticsApplicationProbeTransportReceiveEvent? {
    return when (result) {
        is BleTransportReceiveResult.Buffered ->
            AutomatedDiagnosticsApplicationProbeTransportReceiveEvent(
                groupId = result.groupId,
                sourceDeviceAddress = null,
                observedAtMonotonicMillis = observedAtMonotonicMillis,
                observedAtWallClockMillis = observedAtWallClockMillis,
                transportResultKind = "Buffered",
                receivedChunks = result.receivedChunks,
                expectedChunks = result.expectedChunks
            )

        is BleTransportReceiveResult.DuplicateChunk ->
            AutomatedDiagnosticsApplicationProbeTransportReceiveEvent(
                groupId = result.groupId,
                sourceDeviceAddress = null,
                observedAtMonotonicMillis = observedAtMonotonicMillis,
                observedAtWallClockMillis = observedAtWallClockMillis,
                transportResultKind = "DuplicateChunk",
                failureDetail = "chunkIndex=${result.chunkIndex}"
            )

        is BleTransportReceiveResult.InvalidChunk ->
            AutomatedDiagnosticsApplicationProbeTransportReceiveEvent(
                groupId = result.groupId,
                sourceDeviceAddress = null,
                observedAtMonotonicMillis = observedAtMonotonicMillis,
                observedAtWallClockMillis = observedAtWallClockMillis,
                transportResultKind = "InvalidChunk",
                receivedChunks = result.receivedChunks,
                expectedChunks = result.expectedChunks,
                failureDetail = result.reason
            )

        is BleTransportReceiveResult.BufferOverflow ->
            AutomatedDiagnosticsApplicationProbeTransportReceiveEvent(
                groupId = result.groupId,
                sourceDeviceAddress = null,
                observedAtMonotonicMillis = observedAtMonotonicMillis,
                observedAtWallClockMillis = observedAtWallClockMillis,
                transportResultKind = "BufferOverflow",
                receivedChunks = result.receivedChunks,
                expectedChunks = result.expectedChunks,
                failureDetail = result.reason
            )

        is BleTransportReceiveResult.ProcessorFailed -> {
            val receiveResult = result.processingResult.receiveResult
            AutomatedDiagnosticsApplicationProbeTransportReceiveEvent(
                groupId = result.groupId,
                sourceDeviceAddress = result.sourceDeviceAddress?.trim()?.takeIf { it.isNotEmpty() },
                observedAtMonotonicMillis = observedAtMonotonicMillis,
                observedAtWallClockMillis = observedAtWallClockMillis,
                transportResultKind = "ProcessorFailed",
                processingResultKind = "ReceiveFailed",
                receiveFailureKind = receiveResult::class.simpleName,
                failureDetail = automatedDiagnosticsIncomingTransportReceiveFailureDetail(
                    receiveResult
                )
            )
        }

        is BleTransportReceiveResult.Processed -> {
            when (val processingResult = result.processingResult) {
                is IncomingTransportFrameProcessingResult.Received -> {
                    val frame = processingResult.message.frame
                    val marker = automatedDiagnosticsApplicationProbeMarkerFromFrameOrNull(frame)
                    val expectedMessageType = marker?.let { probeMarker ->
                        automatedDiagnosticsApplicationProbeExpectedMessageType(
                            probeMarker.probeKind
                        )
                    }
                    val messageTypeMatchedExpectedProbe = if (expectedMessageType != null) {
                        frame.type == expectedMessageType
                    } else {
                        null
                    }
                    val ingestionResultKind = when (processingResult.ingestionResult) {
                        is Appended -> "Appended"
                        is Duplicate -> "Duplicate"
                        is UnsupportedThread -> "UnsupportedThread"
                        is UnsupportedType -> "UnsupportedType"
                    }
                    val failureDetail = when (val ingestionResult = processingResult.ingestionResult) {
                        is UnsupportedThread -> ingestionResult.reason
                        is UnsupportedType -> ingestionResult.reason
                        else -> null
                    }
                    AutomatedDiagnosticsApplicationProbeTransportReceiveEvent(
                        groupId = result.groupId,
                        sourceDeviceAddress = result.sourceDeviceAddress?.trim()?.takeIf { it.isNotEmpty() },
                        observedAtMonotonicMillis = observedAtMonotonicMillis,
                        observedAtWallClockMillis = observedAtWallClockMillis,
                        transportResultKind = "Processed",
                        processingResultKind = "Received",
                        ingestionResultKind = ingestionResultKind,
                        failureDetail = failureDetail,
                        messageId = frame.id,
                        messageType = frame.type,
                        marker = marker,
                        expectedMessageType = expectedMessageType,
                        messageTypeMatchedExpectedProbe = messageTypeMatchedExpectedProbe
                    )
                }

                is IncomingTransportFrameProcessingResult.HybridControlHandled ->
                    AutomatedDiagnosticsApplicationProbeTransportReceiveEvent(
                        groupId = result.groupId,
                        sourceDeviceAddress = result.sourceDeviceAddress?.trim()?.takeIf { it.isNotEmpty() },
                        observedAtMonotonicMillis = observedAtMonotonicMillis,
                        observedAtWallClockMillis = observedAtWallClockMillis,
                        transportResultKind = "Processed",
                        processingResultKind = "HybridControlHandled"
                    )

                is IncomingTransportFrameProcessingResult.HybridControlIgnored ->
                    AutomatedDiagnosticsApplicationProbeTransportReceiveEvent(
                        groupId = result.groupId,
                        sourceDeviceAddress = result.sourceDeviceAddress?.trim()?.takeIf { it.isNotEmpty() },
                        observedAtMonotonicMillis = observedAtMonotonicMillis,
                        observedAtWallClockMillis = observedAtWallClockMillis,
                        transportResultKind = "Processed",
                        processingResultKind = "HybridControlIgnored",
                        failureDetail = processingResult.reason
                    )

                is IncomingTransportFrameProcessingResult.IdentityHandled ->
                    AutomatedDiagnosticsApplicationProbeTransportReceiveEvent(
                        groupId = result.groupId,
                        sourceDeviceAddress = result.sourceDeviceAddress?.trim()?.takeIf { it.isNotEmpty() },
                        observedAtMonotonicMillis = observedAtMonotonicMillis,
                        observedAtWallClockMillis = observedAtWallClockMillis,
                        transportResultKind = "Processed",
                        processingResultKind = "IdentityHandled"
                    )

                is IncomingTransportFrameProcessingResult.IdentityHandlingUnavailable ->
                    AutomatedDiagnosticsApplicationProbeTransportReceiveEvent(
                        groupId = result.groupId,
                        sourceDeviceAddress = result.sourceDeviceAddress?.trim()?.takeIf { it.isNotEmpty() },
                        observedAtMonotonicMillis = observedAtMonotonicMillis,
                        observedAtWallClockMillis = observedAtWallClockMillis,
                        transportResultKind = "Processed",
                        processingResultKind = "IdentityHandlingUnavailable",
                        failureDetail = processingResult.reason
                    )

                is IncomingTransportFrameProcessingResult.RelayOnlyEncrypted ->
                    AutomatedDiagnosticsApplicationProbeTransportReceiveEvent(
                        groupId = result.groupId,
                        sourceDeviceAddress = result.sourceDeviceAddress?.trim()?.takeIf { it.isNotEmpty() },
                        observedAtMonotonicMillis = observedAtMonotonicMillis,
                        observedAtWallClockMillis = observedAtWallClockMillis,
                        transportResultKind = "Processed",
                        processingResultKind = "RelayOnlyEncrypted"
                    )
            }
        }
    }
}

internal fun automatedDiagnosticsApplicationProbeMarkerFromFrameOrNull(
    frame: MessageFrame
): AutomatedDiagnosticsApplicationProbeMarker? {
    return when (frame.type) {
        MessageFrameType.GLOBAL_TEXT ->
            automatedDiagnosticsApplicationProbeMarkerOrNull(frame.payload)

        MessageFrameType.PRIVATE_TEXT ->
            PrivateChatMessagePayloadCodec.decodeOrNull(frame.payload)
                ?.body
                ?.let(::automatedDiagnosticsApplicationProbeMarkerOrNull)

        else -> null
    }
}

internal fun automatedDiagnosticsIncomingTransportReceiveFailureDetail(
    result: IncomingTransportReceiveResult
): String {
    return when (result) {
        is IncomingTransportReceiveResult.IncompleteChunks -> result.reason
        is IncomingTransportReceiveResult.InvalidEnvelope -> result.reason
        is IncomingTransportReceiveResult.SessionMaterialUnavailable -> result.reason
        is IncomingTransportReceiveResult.UnsupportedSender -> result.reason
        is IncomingTransportReceiveResult.InvalidSenderIdentity -> result.reason
        is IncomingTransportReceiveResult.DecryptFailed -> result.reason
        is IncomingTransportReceiveResult.InvalidFrame -> result.reason
        is IncomingTransportReceiveResult.Received ->
            "Unexpected Received result in failure branch."
        is IncomingTransportReceiveResult.RelayOnlyEncrypted ->
            "Unexpected RelayOnlyEncrypted result in failure branch."
    }
}

internal fun automatedDiagnosticsApplicationProbeReceiveDiagnosticAfterReceiveOrNull(
    result: BleTransportReceiveResult,
    sourceDeviceAddress: String?,
    activeTransportPeerId: String?,
    activeTransportDeviceAddress: String?,
    reachablePeers: List<BleDiscoveredDevice>,
    selectedSecurePeerId: String?,
    diagnosticsSourceAssociationsByAddress:
    Map<String, AutomatedDiagnosticsAcceptedSourceAssociation>,
    receiverPeerId: String?,
    observedAtMonotonicMillis: Long,
    observedAtWallClockMillis: Long? = null
): AutomatedDiagnosticsApplicationProbeReceiveDiagnostic? {
    return when (result) {
        is BleTransportReceiveResult.Processed -> {
            when (val processingResult = result.processingResult) {
                is IncomingTransportFrameProcessingResult.Received -> {
                    val appendedMessage = processingResult.ingestionResult as? Appended
                        ?: return null
                    val marker = automatedDiagnosticsApplicationProbeMarkerOrNull(
                        appendedMessage.message.text
                    ) ?: return null
                    if (
                        processingResult.message.frame.type !=
                        automatedDiagnosticsApplicationProbeExpectedMessageType(marker.probeKind)
                    ) {
                        return null
                    }
                    val privateChatId = if (
                        processingResult.message.frame.type == MessageFrameType.PRIVATE_TEXT
                    ) {
                        PrivateChatMessagePayloadCodec.decodeOrNull(
                            processingResult.message.frame.payload
                        )?.privateChatId
                    } else {
                        null
                    }
                    val sourceResolution = automatedDiagnosticsApplicationProbeSourceResolution(
                        sourceDeviceAddress = sourceDeviceAddress,
                        marker = marker,
                        activeTransportPeerId = activeTransportPeerId,
                        activeTransportDeviceAddress = activeTransportDeviceAddress,
                        reachablePeers = reachablePeers,
                        receiverPeerId = receiverPeerId,
                        selectedSecurePeerId = selectedSecurePeerId,
                        diagnosticsSourceAssociationsByAddress =
                            diagnosticsSourceAssociationsByAddress
                    )
                    return AutomatedDiagnosticsApplicationProbeReceiveDiagnostic(
                        sharedRunId = marker.sharedRunId,
                        stepId = marker.stepId,
                        attemptNumber = marker.attemptNumber,
                        probeKind = marker.probeKind,
                        direction = marker.direction,
                        messageId = appendedMessage.message.id,
                        applicationSenderId = appendedMessage.message.senderId,
                        receiverPeerId = receiverPeerId?.trim()?.takeIf { it.isNotEmpty() },
                        messageType = processingResult.message.frame.type,
                        threadId = appendedMessage.message.threadId,
                        privateChatId = privateChatId,
                        transportGroupId = result.groupId,
                        marker = marker,
                        sourceResolution = sourceResolution,
                        observedAtMonotonicMillis = observedAtMonotonicMillis,
                        observedAtWallClockMillis = observedAtWallClockMillis
                    )
                }
                is IncomingTransportFrameProcessingResult.HybridControlHandled,
                is IncomingTransportFrameProcessingResult.HybridControlIgnored,
                is IncomingTransportFrameProcessingResult.IdentityHandled,
                is IncomingTransportFrameProcessingResult.IdentityHandlingUnavailable,
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

internal fun automatedDiagnosticsApplicationProbeSourceResolution(
    sourceDeviceAddress: String?,
    marker: gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsApplicationProbeMarker,
    activeTransportPeerId: String?,
    activeTransportDeviceAddress: String?,
    reachablePeers: List<BleDiscoveredDevice>,
    receiverPeerId: String?,
    selectedSecurePeerId: String?,
    diagnosticsSourceAssociationsByAddress:
    Map<String, AutomatedDiagnosticsAcceptedSourceAssociation>
): AutomatedDiagnosticsApplicationProbeSourceResolution {
    val sanitizedSourceDeviceAddress = sourceDeviceAddress?.trim()?.takeIf { it.isNotEmpty() }
    val sanitizedActiveTransportPeerId =
        activeTransportPeerId?.trim()?.takeIf { it.isNotEmpty() }
    val sanitizedActiveTransportDeviceAddress =
        activeTransportDeviceAddress?.trim()?.takeIf { it.isNotEmpty() }
    val exactAddressSourcePeerId = when {
        sanitizedSourceDeviceAddress == null -> null
        sanitizedActiveTransportDeviceAddress == sanitizedSourceDeviceAddress ->
            sanitizedActiveTransportPeerId
        else ->
            reachablePeers.firstOrNull { device ->
                device.address.trim() == sanitizedSourceDeviceAddress
            }?.let(::runtimeReachablePeerId)
    }
    val exactResolutionSource = when {
        sanitizedSourceDeviceAddress == null -> null
        sanitizedActiveTransportDeviceAddress == sanitizedSourceDeviceAddress &&
            sanitizedActiveTransportPeerId != null ->
            AutomatedDiagnosticsApplicationProbeSourceResolutionSource.EXACT_ACTIVE_ADDRESS
        sanitizedActiveTransportDeviceAddress == sanitizedSourceDeviceAddress -> null
        exactAddressSourcePeerId != null ->
            AutomatedDiagnosticsApplicationProbeSourceResolutionSource.EXACT_DISCOVERED_ADDRESS
        else -> null
    }
    val sanitizedReceiverPeerId = receiverPeerId?.trim()?.takeIf { it.isNotEmpty() }
    val sanitizedSelectedSecurePeerId = selectedSecurePeerId?.trim()?.takeIf { it.isNotEmpty() }
    val association = sanitizedSourceDeviceAddress
        ?.let(diagnosticsSourceAssociationsByAddress::get)
    val selectedSecurePeerGate = when {
        sanitizedSelectedSecurePeerId == null ->
            AutomatedDiagnosticsApplicationProbeSelectedSecurePeerGate
                .SELECTED_SECURE_PEER_UNAVAILABLE
        association != null &&
            association.peerId != sanitizedSelectedSecurePeerId ->
            AutomatedDiagnosticsApplicationProbeSelectedSecurePeerGate
                .SELECTED_SECURE_PEER_MISMATCH
        else ->
            AutomatedDiagnosticsApplicationProbeSelectedSecurePeerGate.MATCH
    }
    val diagnosticsAssociationOutcome = when {
        association == null ->
            AutomatedDiagnosticsApplicationProbeAssociationOutcome
                .NO_ASSOCIATION_FOR_SOURCE_ADDRESS
        association.sharedRunId != marker.sharedRunId ->
            AutomatedDiagnosticsApplicationProbeAssociationOutcome.ASSOCIATION_WRONG_RUN
        association.stepId != marker.stepId ->
            AutomatedDiagnosticsApplicationProbeAssociationOutcome.ASSOCIATION_WRONG_STEP
        association.attemptNumber != marker.attemptNumber ->
            AutomatedDiagnosticsApplicationProbeAssociationOutcome.ASSOCIATION_WRONG_ATTEMPT
        exactAddressSourcePeerId != null &&
            association.peerId != exactAddressSourcePeerId ->
            AutomatedDiagnosticsApplicationProbeAssociationOutcome.ASSOCIATION_WRONG_PEER
        sanitizedReceiverPeerId == null ||
            association.expectedRemotePeerId != sanitizedReceiverPeerId ->
            AutomatedDiagnosticsApplicationProbeAssociationOutcome.ASSOCIATION_WRONG_RECEIVER
        else ->
            AutomatedDiagnosticsApplicationProbeAssociationOutcome.RESOLVED
    }
    val diagnosticsAssociatedSourcePeerId = association
        ?.takeIf { diagnosticsAssociationOutcome == AutomatedDiagnosticsApplicationProbeAssociationOutcome.RESOLVED }
        ?.peerId
    return when {
        exactResolutionSource != null ->
            AutomatedDiagnosticsApplicationProbeSourceResolution(
                sourceDeviceAddress = sanitizedSourceDeviceAddress,
                exactAddressSourcePeerId = exactAddressSourcePeerId,
                diagnosticsAssociatedSourcePeerId = diagnosticsAssociatedSourcePeerId,
                resolvedSourcePeerId = exactAddressSourcePeerId,
                resolutionSource = exactResolutionSource,
                associationLookupHit = association != null,
                storedAssociationPeerId = association?.peerId,
                storedAssociationSharedRunId = association?.sharedRunId,
                storedAssociationStepId = association?.stepId,
                storedAssociationAttemptNumber = association?.attemptNumber,
                storedAssociationExpectedRemotePeerId = association?.expectedRemotePeerId,
                selectedSecurePeerId = sanitizedSelectedSecurePeerId,
                diagnosticsAssociationOutcome = diagnosticsAssociationOutcome,
                selectedSecurePeerGate = selectedSecurePeerGate
            )
        diagnosticsAssociatedSourcePeerId != null ->
            AutomatedDiagnosticsApplicationProbeSourceResolution(
                sourceDeviceAddress = sanitizedSourceDeviceAddress,
                exactAddressSourcePeerId = exactAddressSourcePeerId,
                diagnosticsAssociatedSourcePeerId = diagnosticsAssociatedSourcePeerId,
                resolvedSourcePeerId = diagnosticsAssociatedSourcePeerId,
                resolutionSource =
                    AutomatedDiagnosticsApplicationProbeSourceResolutionSource
                        .CURRENT_RUN_DIAGNOSTICS_ASSOCIATION,
                associationLookupHit = true,
                storedAssociationPeerId = association?.peerId,
                storedAssociationSharedRunId = association?.sharedRunId,
                storedAssociationStepId = association?.stepId,
                storedAssociationAttemptNumber = association?.attemptNumber,
                storedAssociationExpectedRemotePeerId = association?.expectedRemotePeerId,
                selectedSecurePeerId = sanitizedSelectedSecurePeerId,
                diagnosticsAssociationOutcome = diagnosticsAssociationOutcome,
                selectedSecurePeerGate = selectedSecurePeerGate
            )
        else ->
            AutomatedDiagnosticsApplicationProbeSourceResolution(
                sourceDeviceAddress = sanitizedSourceDeviceAddress,
                exactAddressSourcePeerId = exactAddressSourcePeerId,
                diagnosticsAssociatedSourcePeerId = diagnosticsAssociatedSourcePeerId,
                resolvedSourcePeerId = null,
                resolutionSource =
                    AutomatedDiagnosticsApplicationProbeSourceResolutionSource.UNRESOLVED,
                associationLookupHit = association != null,
                storedAssociationPeerId = association?.peerId,
                storedAssociationSharedRunId = association?.sharedRunId,
                storedAssociationStepId = association?.stepId,
                storedAssociationAttemptNumber = association?.attemptNumber,
                storedAssociationExpectedRemotePeerId = association?.expectedRemotePeerId,
                selectedSecurePeerId = sanitizedSelectedSecurePeerId,
                diagnosticsAssociationOutcome = diagnosticsAssociationOutcome,
                selectedSecurePeerGate = selectedSecurePeerGate
            )
    }
}

internal fun automatedDiagnosticsApplicationProbeObservationFromReceiveDiagnosticOrNull(
    diagnostic: AutomatedDiagnosticsApplicationProbeReceiveDiagnostic
): AutomatedDiagnosticsApplicationProbeObservation? {
    val senderPeerId = diagnostic.sourceResolution.resolvedSourcePeerId
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    val receiverPeerId = diagnostic.receiverPeerId
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    return AutomatedDiagnosticsApplicationProbeObservation(
        sharedRunId = diagnostic.sharedRunId,
        stepId = diagnostic.stepId,
        attemptNumber = diagnostic.attemptNumber,
        probeKind = diagnostic.probeKind,
        direction = diagnostic.direction,
        messageId = diagnostic.messageId,
        senderPeerId = senderPeerId,
        applicationSenderId = diagnostic.applicationSenderId,
        receiverPeerId = receiverPeerId,
        messageType = diagnostic.messageType,
        threadId = diagnostic.threadId,
        privateChatId = diagnostic.privateChatId,
        transportGroupId = diagnostic.transportGroupId,
        marker = diagnostic.marker,
        observedAtMonotonicMillis = diagnostic.observedAtMonotonicMillis,
        observedAtWallClockMillis = diagnostic.observedAtWallClockMillis,
        acceptanceResult = diagnostic.acceptanceResult
    )
}

internal fun automatedDiagnosticsApplicationProbeObservationAfterReceiveOrNull(
    result: BleTransportReceiveResult,
    sourcePeerId: String?,
    receiverPeerId: String?,
    observedAtMonotonicMillis: Long,
    observedAtWallClockMillis: Long? = null
): AutomatedDiagnosticsApplicationProbeObservation? {
    val sanitizedSourcePeerId = sourcePeerId
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    val sanitizedReceiverPeerId = receiverPeerId
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    return when (result) {
        is BleTransportReceiveResult.Processed -> {
            when (val processingResult = result.processingResult) {
                is IncomingTransportFrameProcessingResult.Received -> {
                    val appendedMessage = processingResult.ingestionResult as? Appended
                        ?: return null
                    val marker = automatedDiagnosticsApplicationProbeMarkerOrNull(
                        appendedMessage.message.text
                    ) ?: return null
                    if (
                        processingResult.message.frame.type !=
                        automatedDiagnosticsApplicationProbeExpectedMessageType(marker.probeKind)
                    ) {
                        return null
                    }
                    val privateChatId = if (
                        processingResult.message.frame.type == MessageFrameType.PRIVATE_TEXT
                    ) {
                        PrivateChatMessagePayloadCodec.decodeOrNull(
                            processingResult.message.frame.payload
                        )?.privateChatId
                    } else {
                        null
                    }
                    AutomatedDiagnosticsApplicationProbeObservation(
                        sharedRunId = marker.sharedRunId,
                        stepId = marker.stepId,
                        attemptNumber = marker.attemptNumber,
                        probeKind = marker.probeKind,
                        direction = marker.direction,
                        messageId = appendedMessage.message.id,
                        senderPeerId = sanitizedSourcePeerId,
                        applicationSenderId = appendedMessage.message.senderId,
                        receiverPeerId = sanitizedReceiverPeerId,
                        messageType = processingResult.message.frame.type,
                        threadId = appendedMessage.message.threadId,
                        privateChatId = privateChatId,
                        marker = marker,
                        observedAtMonotonicMillis = observedAtMonotonicMillis,
                        observedAtWallClockMillis = observedAtWallClockMillis
                    )
                }
                is IncomingTransportFrameProcessingResult.HybridControlHandled,
                is IncomingTransportFrameProcessingResult.HybridControlIgnored,
                is IncomingTransportFrameProcessingResult.IdentityHandled,
                is IncomingTransportFrameProcessingResult.IdentityHandlingUnavailable,
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

internal fun appendAutomatedDiagnosticsApplicationProbeObservation(
    observations: List<AutomatedDiagnosticsApplicationProbeObservation>,
    observation: AutomatedDiagnosticsApplicationProbeObservation,
    limit: Int = automatedDiagnosticsApplicationProbeObservationLimit
): List<AutomatedDiagnosticsApplicationProbeObservation> {
    return (observations + observation).takeLast(limit.coerceAtLeast(1))
}

internal fun appendBleTransportLocalSendTrace(
    traces: List<BleTransportLocalSendTrace>,
    trace: BleTransportLocalSendTrace,
    limit: Int = automatedDiagnosticsBleTransportLocalSendTraceLimit
): List<BleTransportLocalSendTrace> {
    val deduplicated = traces.filterNot { existing ->
        existing.messageId == trace.messageId &&
            existing.groupId == trace.groupId
    }
    return (deduplicated + trace).takeLast(limit.coerceAtLeast(1))
}

internal fun appendAutomatedDiagnosticsApplicationProbeReceiveDiagnostic(
    diagnostics: List<AutomatedDiagnosticsApplicationProbeReceiveDiagnostic>,
    diagnostic: AutomatedDiagnosticsApplicationProbeReceiveDiagnostic,
    limit: Int = automatedDiagnosticsApplicationProbeReceiveDiagnosticLimit
): List<AutomatedDiagnosticsApplicationProbeReceiveDiagnostic> {
    return (diagnostics + diagnostic).takeLast(limit.coerceAtLeast(1))
}

internal fun appendAutomatedDiagnosticsApplicationProbeTransportReceiveEvent(
    events: List<AutomatedDiagnosticsApplicationProbeTransportReceiveEvent>,
    event: AutomatedDiagnosticsApplicationProbeTransportReceiveEvent,
    limit: Int = automatedDiagnosticsApplicationProbeTransportReceiveEventLimit
): List<AutomatedDiagnosticsApplicationProbeTransportReceiveEvent> {
    return (events + event).takeLast(limit.coerceAtLeast(1))
}

internal fun recordLocallySentHybridBootstrapControlMessage(
    targetPeerId: String,
    message: HybridTransportControlMessage,
    hybridControlStore: HybridTransportControlStore,
    provider: HybridBootstrapDecisionProvider
): HybridBootstrapDecision {
    hybridControlStore.record(
        peerId = targetPeerId,
        message = message
    )
    return provider.currentDecision()
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

internal fun hybridBootstrapSocketHintObservationKey(
    observation: HybridBootstrapSocketHintObservation
): String {
    return hybridBootstrapSocketHintObservationKey(
        peerId = observation.peerId,
        sessionId = observation.sessionId,
        groupOwnerAddress = observation.groupOwnerAddress,
        socketPort = observation.socketPort
    )
}

internal fun hybridBootstrapSocketHintObservationKey(
    peerId: String,
    sessionId: String,
    groupOwnerAddress: String,
    socketPort: Int
): String {
    return listOf(
        peerId.trim(),
        sessionId.trim(),
        groupOwnerAddress.trim(),
        socketPort.toString()
    ).joinToString("|")
}

internal fun currentHybridBootstrapSocketHintObservationForDecision(
    decision: HybridBootstrapDecision,
    socketHintObservationsByKey: Map<String, HybridBootstrapSocketHintObservation>
): HybridBootstrapSocketHintObservation? {
    val selectedCandidate = (decision.selection as? HybridBootstrapCandidateSelection.Selected)
        ?.candidate
        ?: return null
    val groupOwnerAddress = selectedCandidate.groupOwnerAddress ?: return null
    val socketPort = selectedCandidate.socketPort ?: return null
    return socketHintObservationsByKey[
        hybridBootstrapSocketHintObservationKey(
            peerId = selectedCandidate.peerId,
            sessionId = selectedCandidate.sessionId,
            groupOwnerAddress = groupOwnerAddress,
            socketPort = socketPort
        )
    ]
}

internal fun currentHybridBootstrapSocketEndpointResolution(
    provider: HybridBootstrapDecisionProvider,
    socketHintObservationsByKey: Map<String, HybridBootstrapSocketHintObservation> = emptyMap()
): HybridBootstrapSocketEndpointResolution {
    val decision = provider.currentDecision()
    return HybridBootstrapSocketEndpointResolver.resolve(
        decision = decision,
        socketHintObservation = currentHybridBootstrapSocketHintObservationForDecision(
            decision = decision,
            socketHintObservationsByKey = socketHintObservationsByKey
        )
    )
}

internal fun currentHybridBootstrapAttemptDecision(
    provider: HybridBootstrapDecisionProvider,
    requestedAtMillis: Long,
    currentMonotonicMillis: Long = requestedAtMillis,
    socketHintObservationsByKey: Map<String, HybridBootstrapSocketHintObservation> = emptyMap()
): HybridBootstrapAttemptDecision {
    return HybridBootstrapAttemptPolicy.decide(
        resolution = currentHybridBootstrapSocketEndpointResolution(
            provider = provider,
            socketHintObservationsByKey = socketHintObservationsByKey
        ),
        requestedAtMillis = requestedAtMillis,
        currentMonotonicMillis = currentMonotonicMillis,
        maxEndpointAgeMillis = HybridBootstrapAttemptPolicy.DEFAULT_MAX_ENDPOINT_AGE_MILLIS
    )
}

internal fun hybridBootstrapSocketEndpointResolutionAfterReceiveOrNull(
    result: BleTransportReceiveResult,
    provider: HybridBootstrapDecisionProvider,
    socketHintObservation: HybridBootstrapSocketHintObservation? = null
): HybridBootstrapSocketEndpointResolution? {
    val decision = hybridBootstrapDecisionAfterReceiveOrNull(
        result = result,
        provider = provider
    ) ?: return null

    return HybridBootstrapSocketEndpointResolver.resolve(
        decision = decision,
        socketHintObservation = socketHintObservation
    )
}

internal fun hybridBootstrapAttemptDecisionAfterReceiveOrNull(
    result: BleTransportReceiveResult,
    provider: HybridBootstrapDecisionProvider,
    requestedAtMillis: Long,
    currentMonotonicMillis: Long = requestedAtMillis,
    socketHintObservation: HybridBootstrapSocketHintObservation? = null
): HybridBootstrapAttemptDecision? {
    val resolution = hybridBootstrapSocketEndpointResolutionAfterReceiveOrNull(
        result = result,
        provider = provider,
        socketHintObservation =
            socketHintObservation ?: hybridBootstrapSocketHintObservationAfterReceiveOrNull(
                result = result,
                observedAtMonotonicMillis = currentMonotonicMillis
            )
    ) ?: return null

    return HybridBootstrapAttemptPolicy.decide(
        resolution = resolution,
        requestedAtMillis = requestedAtMillis,
        currentMonotonicMillis = currentMonotonicMillis,
        maxEndpointAgeMillis = HybridBootstrapAttemptPolicy.DEFAULT_MAX_ENDPOINT_AGE_MILLIS
    )
}

internal fun currentHybridBootstrapAttemptCommandBuildResult(
    provider: HybridBootstrapDecisionProvider,
    requestedAtMillis: Long,
    currentMonotonicMillis: Long = requestedAtMillis,
    commandCreatedAtMillis: Long,
    socketHintObservationsByKey: Map<String, HybridBootstrapSocketHintObservation> = emptyMap()
): HybridBootstrapAttemptCommandBuildResult {
    return HybridBootstrapAttemptCommandBuilder.build(
        decision = currentHybridBootstrapAttemptDecision(
            provider = provider,
            requestedAtMillis = requestedAtMillis,
            currentMonotonicMillis = currentMonotonicMillis,
            socketHintObservationsByKey = socketHintObservationsByKey
        ),
        commandCreatedAtMillis = commandCreatedAtMillis
    )
}

internal fun hybridBootstrapSocketHintObservationAfterReceiveOrNull(
    result: BleTransportReceiveResult,
    observedAtMonotonicMillis: Long
): HybridBootstrapSocketHintObservation? {
    return when (result) {
        is BleTransportReceiveResult.Processed -> {
            when (val processingResult = result.processingResult) {
                is IncomingTransportFrameProcessingResult.HybridControlHandled -> {
                    if (
                        processingResult.controlMessage.messageType ==
                        HybridTransportControlMessage.MessageType.WIFI_DIRECT_SOCKET_HINT
                    ) {
                        val groupOwnerAddress = processingResult.controlMessage.groupOwnerAddress
                            ?: return null
                        val socketPort = processingResult.controlMessage.socketPort
                            ?: return null
                        HybridBootstrapSocketHintObservation(
                            peerId = processingResult.peerId,
                            sessionId = processingResult.controlMessage.sessionId,
                            groupOwnerAddress = groupOwnerAddress,
                            socketPort = socketPort,
                            createdAtMillis = processingResult.controlMessage.createdAtMillis,
                            observedAtMonotonicMillis = observedAtMonotonicMillis
                        )
                    } else {
                        null
                    }
                }
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

internal fun hybridBootstrapJavaNetRuntimeEnabled(): Boolean = true

internal fun currentHybridBootstrapRuntimeExecutorMode(): HybridBootstrapCommandExecutorMode {
    return if (hybridBootstrapJavaNetRuntimeEnabled()) {
        HybridBootstrapCommandExecutorMode.SOCKET_PLAN_JAVANET
    } else {
        HybridBootstrapCommandExecutorMode.SOCKET_PLAN_DISABLED
    }
}

internal fun currentHybridBootstrapCommandExecutorConfig(): HybridBootstrapCommandExecutorConfig {
    return HybridBootstrapCommandExecutorConfig(
        mode = currentHybridBootstrapRuntimeExecutorMode()
    )
}

internal fun currentHybridBootstrapCommandTriggerController(
    config: HybridBootstrapCommandExecutorConfig = currentHybridBootstrapCommandExecutorConfig(),
    socketConnectorOverride: HybridBootstrapSocketConnector? = null
): HybridBootstrapCommandTriggerController {
    return HybridBootstrapCommandTriggerController(
        executor = HybridBootstrapCommandExecutorFactory.create(
            config = config,
            socketConnectorOverride = socketConnectorOverride
        )
    )
}

internal fun initialHybridBootstrapCommandTriggerResult(): HybridBootstrapCommandTriggerResult? = null

internal fun recordExplicitHybridBootstrapTriggerResult(
    result: HybridBootstrapCommandTriggerResult
): HybridBootstrapCommandTriggerResult {
    return result
}

internal fun triggerHybridBootstrapCommandIfExplicitlyRequested(
    buildResult: HybridBootstrapAttemptCommandBuildResult,
    controller: HybridBootstrapCommandTriggerController
): HybridBootstrapCommandTriggerResult {
    return controller.trigger(buildResult)
}

internal fun triggerAndRecordHybridBootstrapCommandIfExplicitlyRequested(
    buildResult: HybridBootstrapAttemptCommandBuildResult,
    controller: HybridBootstrapCommandTriggerController,
    recordResult: (HybridBootstrapCommandTriggerResult) -> Unit
): HybridBootstrapCommandTriggerResult {
    val triggerResult = triggerHybridBootstrapCommandIfExplicitlyRequested(
        buildResult = buildResult,
        controller = controller
    )
    val recordedResult = recordExplicitHybridBootstrapTriggerResult(triggerResult)
    recordResult(recordedResult)
    return triggerResult
}

internal fun createHybridBootstrapManualTriggerAction(
    buildResultProvider: () -> HybridBootstrapAttemptCommandBuildResult,
    controllerProvider: () -> HybridBootstrapCommandTriggerController,
    recordResult: (HybridBootstrapCommandTriggerResult) -> Unit
): () -> HybridBootstrapCommandTriggerResult {
    return {
        triggerAndRecordHybridBootstrapCommandIfExplicitlyRequested(
            buildResult = buildResultProvider(),
            controller = controllerProvider(),
            recordResult = recordResult
        )
    }
}

internal fun currentHybridBootstrapManualTriggerSnapshot(
    commandBuildResult: HybridBootstrapAttemptCommandBuildResult,
    latestTriggerResult: HybridBootstrapCommandTriggerResult?
): HybridBootstrapManualTriggerSnapshot {
    return HybridBootstrapManualTriggerSnapshotFormatter.format(
        commandBuildResult = commandBuildResult,
        latestTriggerResult = latestTriggerResult
    )
}

internal fun triggerHybridBootstrapManuallyIfAvailable(
    snapshot: HybridBootstrapManualTriggerSnapshot,
    manualTriggerAction: () -> HybridBootstrapCommandTriggerResult
): HybridBootstrapCommandTriggerResult {
    return if (snapshot.canTriggerNow) {
        manualTriggerAction()
    } else {
        HybridBootstrapCommandTriggerResult.NotAllowed(
            reason = "Manual hybrid bootstrap trigger is not available."
        )
    }
}

internal fun createHybridBootstrapManualTriggerRequestCallback(
    guardedManualTriggerAction: () -> HybridBootstrapCommandTriggerResult,
    blockingExecutionDispatcher: CoroutineDispatcher = Dispatchers.IO
): suspend () -> HybridBootstrapCommandTriggerResult {
    return {
        withContext(blockingExecutionDispatcher) {
            guardedManualTriggerAction()
        }
    }
}

internal fun currentHybridBootstrapManualOfferAvailable(
    bleConnectionStatus: BleConnectionStatus,
    activeTransportPeerId: String?,
    peerSessionDiagnostics: PeerSessionRegistryDiagnostics,
    transportSender: BleTransportSender,
    localPeerId: String?
): Boolean {
    return currentHybridBootstrapManualOfferBlockedReason(
        bleConnectionStatus = bleConnectionStatus,
        activeTransportPeerId = activeTransportPeerId,
        peerSessionDiagnostics = peerSessionDiagnostics,
        transportSender = transportSender,
        localPeerId = localPeerId
    ) == null
}

internal fun currentHybridBootstrapManualOfferBlockedReason(
    bleConnectionStatus: BleConnectionStatus,
    activeTransportPeerId: String?,
    peerSessionDiagnostics: PeerSessionRegistryDiagnostics,
    transportSender: BleTransportSender,
    localPeerId: String?
): String? {
    if (bleConnectionStatus != BleConnectionStatus.CONNECTED) {
        return "No active BLE peer."
    }
    val activePeerId = activeTransportPeerId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return "No active BLE peer."
    if (!peerSessionDiagnostics.hasSessionForPeer(activePeerId)) {
        return "No active BLE session."
    }
    if (transportSender is NoOpBleTransportSender) {
        return "BLE writer unavailable."
    }
    val localSenderPeerId = localPeerId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return "Local hybrid bootstrap peer id unavailable."

    return if (localSenderPeerId.isNotEmpty()) {
        null
    } else {
        "Local hybrid bootstrap peer id unavailable."
    }
}

internal fun currentHybridBootstrapManualAcceptAvailable(
    decision: HybridBootstrapDecision,
    bleConnectionStatus: BleConnectionStatus,
    activeTransportPeerId: String?,
    peerSessionDiagnostics: PeerSessionRegistryDiagnostics,
    transportSender: BleTransportSender,
    localPeerId: String?
): Boolean {
    return currentHybridBootstrapManualAcceptBlockedReason(
        decision = decision,
        bleConnectionStatus = bleConnectionStatus,
        activeTransportPeerId = activeTransportPeerId,
        peerSessionDiagnostics = peerSessionDiagnostics,
        transportSender = transportSender,
        localPeerId = localPeerId
    ) == null
}

internal fun currentHybridBootstrapManualAcceptBlockedReason(
    decision: HybridBootstrapDecision,
    bleConnectionStatus: BleConnectionStatus,
    activeTransportPeerId: String?,
    peerSessionDiagnostics: PeerSessionRegistryDiagnostics,
    transportSender: BleTransportSender,
    localPeerId: String?
): String? {
    if (bleConnectionStatus != BleConnectionStatus.CONNECTED) {
        return "No active BLE peer."
    }
    val activePeerId = activeTransportPeerId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return "No active BLE peer."
    if (peerSessionDiagnostics.canonicalPeerIdFor(activePeerId) == null) {
        return "No active BLE session."
    }
    if (transportSender is NoOpBleTransportSender) {
        return "BLE writer unavailable."
    }
    val localSenderPeerId = localPeerId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return "Local hybrid bootstrap peer id unavailable."
    if (localSenderPeerId.isEmpty()) {
        return "Local hybrid bootstrap peer id unavailable."
    }
    val matchingCandidate = selectHybridBootstrapManualAcceptCandidateOrNull(
        decision = decision,
        activeTransportPeerId = activePeerId,
        peerSessionDiagnostics = peerSessionDiagnostics
    )
    if (matchingCandidate == null) {
        return if (decision.candidates.any(HybridBootstrapCandidate::hasOffer)) {
            "Received OFFER candidate is not for the active peer."
        } else {
            "No received OFFER candidate."
        }
    }

    return null
}

internal data class HybridBootstrapManualAcceptTarget(
    val peerId: String,
    val sessionId: String
) {
    init {
        require(peerId.isNotBlank()) {
            "Hybrid bootstrap manual accept target peerId must not be blank."
        }
        require(sessionId.isNotBlank()) {
            "Hybrid bootstrap manual accept target sessionId must not be blank."
        }
    }
}

internal fun selectHybridBootstrapManualAcceptTargetOrNull(
    decision: HybridBootstrapDecision,
    activeTransportPeerId: String?,
    peerSessionDiagnostics: PeerSessionRegistryDiagnostics
): HybridBootstrapManualAcceptTarget? {
    val candidate = selectHybridBootstrapManualAcceptCandidateOrNull(
        decision = decision,
        activeTransportPeerId = activeTransportPeerId,
        peerSessionDiagnostics = peerSessionDiagnostics
    ) ?: return null
    val canonicalPeerId = peerSessionDiagnostics.canonicalPeerIdFor(candidate.peerId)
        ?: candidate.peerId.trim().takeIf { it.isNotEmpty() }
        ?: return null

    return HybridBootstrapManualAcceptTarget(
        peerId = canonicalPeerId,
        sessionId = candidate.sessionId
    )
}

internal fun selectHybridBootstrapManualAcceptCandidateOrNull(
    decision: HybridBootstrapDecision,
    activeTransportPeerId: String?,
    peerSessionDiagnostics: PeerSessionRegistryDiagnostics
): HybridBootstrapCandidate? {
    val activePeerId = activeTransportPeerId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val activeCanonicalPeerId = peerSessionDiagnostics.canonicalPeerIdFor(activePeerId) ?: return null
    val matchingCandidates = decision.candidates.filter { candidate ->
        candidate.hasOffer &&
            candidate.peerId.isNotBlank() &&
            candidate.sessionId.isNotBlank() &&
            (
                peerSessionDiagnostics.canonicalPeerIdFor(candidate.peerId)
                    ?: candidate.peerId.trim().takeIf { it.isNotEmpty() }
            ) == activeCanonicalPeerId
    }

    return matchingCandidates.firstOrNull { !it.hasAccept } ?: matchingCandidates.firstOrNull()
}

internal suspend fun submitHybridBootstrapManualOffer(
    bleConnectionStatus: BleConnectionStatus,
    activeTransportPeerId: String?,
    peerSessionDiagnostics: PeerSessionRegistryDiagnostics,
    transportSender: BleTransportSender,
    localPeerId: String?,
    createdAtMillis: Long
): HybridBootstrapManualOfferSendResult {
    if (bleConnectionStatus != BleConnectionStatus.CONNECTED) {
        return HybridBootstrapManualOfferSendResult.NoActivePeer
    }
    val activePeerId = activeTransportPeerId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return HybridBootstrapManualOfferSendResult.NoActivePeer
    val activeSessionPeerId = peerSessionDiagnostics.canonicalPeerIdFor(activePeerId)
        ?: return HybridBootstrapManualOfferSendResult.NoActiveSession
    val localSenderPeerId = localPeerId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return HybridBootstrapManualOfferSendResult.InvalidOffer(
            reason = "Local hybrid bootstrap peer id unavailable."
        )
    val frame = runCatching {
        createHybridBootstrapManualOfferFrame(
            localPeerId = localSenderPeerId,
            targetPeerId = activeSessionPeerId,
            createdAtMillis = createdAtMillis
        )
    }.getOrElse { error ->
        return HybridBootstrapManualOfferSendResult.InvalidOffer(
            reason = error.message ?: "Hybrid bootstrap offer is invalid."
        )
    }

    return when (
        val sendResult = MessageFrameTransportSendUseCase.sendPublic(
            frame = frame,
            transportSender = transportSender,
            targetPeerId = activeSessionPeerId
        )
    ) {
        BleTransportSendResult.QueuedLocally ->
            HybridBootstrapManualOfferSendResult.Sent(
                peerId = activeSessionPeerId,
                sessionId = localSenderPeerId
            )

        BleTransportSendResult.NotAvailable ->
            HybridBootstrapManualOfferSendResult.WriterUnavailable

        is BleTransportSendResult.Failed ->
            HybridBootstrapManualOfferSendResult.SendFailed(sendResult.reason)
    }
}

internal suspend fun submitHybridBootstrapManualAccept(
    decision: HybridBootstrapDecision,
    bleConnectionStatus: BleConnectionStatus,
    activeTransportPeerId: String?,
    peerSessionDiagnostics: PeerSessionRegistryDiagnostics,
    transportSender: BleTransportSender,
    localPeerId: String?,
    createdAtMillis: Long
): HybridBootstrapManualAcceptSendResult {
    if (bleConnectionStatus != BleConnectionStatus.CONNECTED) {
        return HybridBootstrapManualAcceptSendResult.NoActivePeer
    }
    val activePeerId = activeTransportPeerId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return HybridBootstrapManualAcceptSendResult.NoActivePeer
    if (peerSessionDiagnostics.canonicalPeerIdFor(activePeerId) == null) {
        return HybridBootstrapManualAcceptSendResult.NoActiveSession
    }
    if (transportSender is NoOpBleTransportSender) {
        return HybridBootstrapManualAcceptSendResult.WriterUnavailable
    }
    val localSenderPeerId = localPeerId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return HybridBootstrapManualAcceptSendResult.InvalidAccept(
            reason = "Local hybrid bootstrap peer id unavailable."
        )
    val target = selectHybridBootstrapManualAcceptTargetOrNull(
        decision = decision,
        activeTransportPeerId = activePeerId,
        peerSessionDiagnostics = peerSessionDiagnostics
    ) ?: return HybridBootstrapManualAcceptSendResult.NoOfferCandidate
    val frame = runCatching {
        createHybridBootstrapManualAcceptFrame(
            localPeerId = localSenderPeerId,
            targetPeerId = target.peerId,
            sessionId = target.sessionId,
            createdAtMillis = createdAtMillis
        )
    }.getOrElse { error ->
        return HybridBootstrapManualAcceptSendResult.InvalidAccept(
            reason = error.message ?: "Hybrid bootstrap accept is invalid."
        )
    }

    return when (
        val sendResult = MessageFrameTransportSendUseCase.sendPublic(
            frame = frame,
            transportSender = transportSender,
            targetPeerId = target.peerId
        )
    ) {
        BleTransportSendResult.QueuedLocally ->
            HybridBootstrapManualAcceptSendResult.Sent(
                peerId = target.peerId,
                sessionId = target.sessionId
            )

        BleTransportSendResult.NotAvailable ->
            HybridBootstrapManualAcceptSendResult.WriterUnavailable

        is BleTransportSendResult.Failed ->
            HybridBootstrapManualAcceptSendResult.SendFailed(sendResult.reason)
    }
}

internal fun selectHybridBootstrapManualSocketHintCandidateOrNull(
    decision: HybridBootstrapDecision,
    activeTransportPeerId: String?,
    peerSessionDiagnostics: PeerSessionRegistryDiagnostics
): HybridBootstrapCandidate? {
    val activePeerId = activeTransportPeerId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    val activeCanonicalPeerId = peerSessionDiagnostics.canonicalPeerIdFor(activePeerId)
        ?: return null

    return decision.candidates.firstOrNull { candidate ->
        candidate.hasOffer &&
            candidate.hasAccept &&
            (
                peerSessionDiagnostics.canonicalPeerIdFor(candidate.peerId)
                    ?: candidate.peerId.trim().takeIf { it.isNotEmpty() }
            ) == activeCanonicalPeerId
    }
}

internal suspend fun submitHybridBootstrapManualSocketHint(
    decision: HybridBootstrapDecision,
    bleConnectionStatus: BleConnectionStatus,
    activeTransportPeerId: String?,
    peerSessionDiagnostics: PeerSessionRegistryDiagnostics,
    transportSender: BleTransportSender,
    localPeerId: String?,
    wifiDirectConnectionStatus: WifiDirectConnectionStatus,
    socketPort: Int,
    createdAtMillis: Long
): HybridBootstrapManualSocketHintSendResult {
    if (bleConnectionStatus != BleConnectionStatus.CONNECTED) {
        return HybridBootstrapManualSocketHintSendResult.NoActivePeer
    }
    val activePeerId = activeTransportPeerId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return HybridBootstrapManualSocketHintSendResult.NoActivePeer
    val activeSessionPeerId = peerSessionDiagnostics.canonicalPeerIdFor(activePeerId)
        ?: return HybridBootstrapManualSocketHintSendResult.NoActiveSession
    if (transportSender is NoOpBleTransportSender) {
        return HybridBootstrapManualSocketHintSendResult.WriterUnavailable
    }
    if (wifiDirectConnectionStatus.state != WifiDirectConnectionState.CONNECTED ||
        wifiDirectConnectionStatus.groupFormed != WifiDirectGroupFormedState.YES
    ) {
        return HybridBootstrapManualSocketHintSendResult.NoSocketEndpoint
    }
    if (wifiDirectConnectionStatus.role != WifiDirectConnectionRole.GROUP_OWNER) {
        return HybridBootstrapManualSocketHintSendResult.NotGroupOwner
    }
    val groupOwnerAddress = wifiDirectConnectionStatus.groupOwnerAddress
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return HybridBootstrapManualSocketHintSendResult.NoSocketEndpoint
    val localSenderPeerId = localPeerId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return HybridBootstrapManualSocketHintSendResult.InvalidSocketHint(
            reason = "Local hybrid bootstrap peer id unavailable."
        )
    val targetCandidate = selectHybridBootstrapManualSocketHintCandidateOrNull(
        decision = decision,
        activeTransportPeerId = activePeerId,
        peerSessionDiagnostics = peerSessionDiagnostics
    ) ?: return HybridBootstrapManualSocketHintSendResult.NoAcceptedCandidate
    val frame = runCatching {
        createHybridBootstrapManualSocketHintFrame(
            localPeerId = localSenderPeerId,
            targetPeerId = activeSessionPeerId,
            sessionId = targetCandidate.sessionId,
            groupOwnerAddress = groupOwnerAddress,
            socketPort = socketPort,
            createdAtMillis = createdAtMillis
        )
    }.getOrElse { error ->
        return HybridBootstrapManualSocketHintSendResult.InvalidSocketHint(
            reason = error.message ?: "Hybrid bootstrap socket hint is invalid."
        )
    }

    return when (
        val sendResult = MessageFrameTransportSendUseCase.sendPublic(
            frame = frame,
            transportSender = transportSender,
            targetPeerId = activeSessionPeerId
        )
    ) {
        BleTransportSendResult.QueuedLocally ->
            HybridBootstrapManualSocketHintSendResult.Sent(
                peerId = activeSessionPeerId,
                sessionId = targetCandidate.sessionId,
                groupOwnerAddress = groupOwnerAddress,
                socketPort = socketPort
            )

        BleTransportSendResult.NotAvailable ->
            HybridBootstrapManualSocketHintSendResult.WriterUnavailable

        is BleTransportSendResult.Failed ->
            HybridBootstrapManualSocketHintSendResult.SendFailed(sendResult.reason)
    }
}

internal suspend fun submitAutomatedDiagnosticsRunAnnouncement(
    bleConnectionStatus: BleConnectionStatus,
    activeTransportPeerId: String?,
    transportSender: BleTransportSender,
    localPeerId: String?,
    sharedRun: AutomatedDiagnosticsSharedRun
): AutomatedDiagnosticsRunAnnouncementSendResult {
    if (bleConnectionStatus != BleConnectionStatus.CONNECTED) {
        return AutomatedDiagnosticsRunAnnouncementSendResult.NoActivePeer
    }
    val targetPeerId = activeTransportPeerId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return AutomatedDiagnosticsRunAnnouncementSendResult.NoActivePeer
    val senderPeerId = localPeerId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return AutomatedDiagnosticsRunAnnouncementSendResult.InvalidAnnouncement(
            "Local peer identity unavailable."
        )
    val frame = runCatching {
        createAutomatedDiagnosticsRunAnnouncementFrame(
            announcement = AutomatedDiagnosticsRunAnnouncement(
                sharedRun = sharedRun,
                peerId = senderPeerId,
                createdAtMillis = sharedRun.createdAtMillis
            ),
            targetPeerId = targetPeerId
        )
    }.getOrElse { error ->
        return AutomatedDiagnosticsRunAnnouncementSendResult.InvalidAnnouncement(
            error.message ?: "Automated diagnostics run announcement is invalid."
        )
    }

    return when (
        val sendResult = MessageFrameTransportSendUseCase.sendPublic(
            frame = frame,
            transportSender = transportSender,
            targetPeerId = targetPeerId
        )
    ) {
        BleTransportSendResult.QueuedLocally ->
            AutomatedDiagnosticsRunAnnouncementSendResult.Sent(sharedRun)

        BleTransportSendResult.NotAvailable ->
            AutomatedDiagnosticsRunAnnouncementSendResult.WriterUnavailable

        is BleTransportSendResult.Failed ->
            AutomatedDiagnosticsRunAnnouncementSendResult.SendFailed(sendResult.reason)
    }
}

internal suspend fun submitAutomatedDiagnosticsParticipantJoin(
    bleConnectionStatus: BleConnectionStatus,
    activeTransportPeerId: String?,
    transportSender: BleTransportSender,
    localPeerId: String?,
    sharedRun: AutomatedDiagnosticsSharedRun,
    createdAtMillis: Long
): AutomatedDiagnosticsParticipantJoinSendResult {
    if (bleConnectionStatus != BleConnectionStatus.CONNECTED) {
        return AutomatedDiagnosticsParticipantJoinSendResult.NoActivePeer
    }
    val targetPeerId = activeTransportPeerId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return AutomatedDiagnosticsParticipantJoinSendResult.NoActivePeer
    val senderPeerId = localPeerId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return AutomatedDiagnosticsParticipantJoinSendResult.InvalidJoin(
            invalidAutomatedDiagnosticsParticipantJoinReason(
                baseReason = "Local peer identity unavailable.",
                sharedRun = sharedRun,
                joinCreatedAtMillis = createdAtMillis
            )
        )
    val frame = runCatching {
        createAutomatedDiagnosticsParticipantJoinFrame(
            join = AutomatedDiagnosticsParticipantJoin(
                sharedRun = sharedRun,
                peerId = senderPeerId,
                createdAtMillis = createdAtMillis
            ),
            targetPeerId = targetPeerId
        )
    }.getOrElse { error ->
        return AutomatedDiagnosticsParticipantJoinSendResult.InvalidJoin(
            invalidAutomatedDiagnosticsParticipantJoinReason(
                baseReason = error.message
                    ?: "Automated diagnostics participant join is invalid.",
                sharedRun = sharedRun,
                joinCreatedAtMillis = createdAtMillis
            )
        )
    }

    return when (
        val sendResult = MessageFrameTransportSendUseCase.sendPublic(
            frame = frame,
            transportSender = transportSender,
            targetPeerId = targetPeerId
        )
    ) {
        BleTransportSendResult.QueuedLocally ->
            AutomatedDiagnosticsParticipantJoinSendResult.Sent(sharedRun)

        BleTransportSendResult.NotAvailable ->
            AutomatedDiagnosticsParticipantJoinSendResult.WriterUnavailable

        is BleTransportSendResult.Failed ->
            AutomatedDiagnosticsParticipantJoinSendResult.SendFailed(sendResult.reason)
    }
}

private fun invalidAutomatedDiagnosticsParticipantJoinReason(
    baseReason: String,
    sharedRun: AutomatedDiagnosticsSharedRun,
    joinCreatedAtMillis: Long
): String {
    return buildString {
        append(baseReason)
        append(" runId=")
        append(sharedRun.runId)
        append(" joinCreatedAtMillis=")
        append(joinCreatedAtMillis)
        append(" sharedRunCreatedAtMillis=")
        append(sharedRun.createdAtMillis)
        append(" sharedRunExpiresAtMillis=")
        append(sharedRun.expiresAtMillis)
        append(" sharedRunExpiryMinusJoinCreatedAtMillis=")
        append(sharedRun.expiresAtMillis - joinCreatedAtMillis)
    }
}

internal suspend fun submitAutomatedDiagnosticsWifiDirectPeerReadySignal(
    bleConnectionStatus: BleConnectionStatus,
    activeTransportPeerId: String?,
    transportSender: BleTransportSender,
    localPeerId: String?,
    sharedRun: AutomatedDiagnosticsSharedRun,
    expectedRemotePeerId: String,
    wifiDirectCorrelationToken: String,
    wifiDirectDeviceName: String?,
    createdAtMillis: Long
): AutomatedDiagnosticsWifiDirectPeerReadySendResult {
    if (bleConnectionStatus != BleConnectionStatus.CONNECTED) {
        return AutomatedDiagnosticsWifiDirectPeerReadySendResult.NoActivePeer
    }
    val targetPeerId = activeTransportPeerId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return AutomatedDiagnosticsWifiDirectPeerReadySendResult.NoActivePeer
    val senderPeerId = localPeerId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return AutomatedDiagnosticsWifiDirectPeerReadySendResult.InvalidSignal(
            "Local peer identity unavailable."
        )
    val signal = runCatching {
        AutomatedDiagnosticsWifiDirectPeerReadySignal(
            sharedRun = sharedRun,
            peerId = senderPeerId,
            expectedRemotePeerId = expectedRemotePeerId,
            wifiDirectCorrelationToken = wifiDirectCorrelationToken,
            wifiDirectDeviceName = wifiDirectDeviceName,
            createdAtMillis = createdAtMillis,
            expiresAtMillis = createdAtMillis + 8_000L
        )
    }.getOrElse { error ->
        return AutomatedDiagnosticsWifiDirectPeerReadySendResult.InvalidSignal(
            error.message ?: "Automated diagnostics Wi-Fi peer-ready signal is invalid."
        )
    }
    val frame = runCatching {
        createAutomatedDiagnosticsWifiDirectPeerReadyFrame(
            signal = signal.copy(peerId = senderPeerId),
            targetPeerId = targetPeerId
        )
    }.getOrElse { error ->
        return AutomatedDiagnosticsWifiDirectPeerReadySendResult.InvalidSignal(
            error.message ?: "Automated diagnostics Wi-Fi peer-ready signal is invalid."
        )
    }

    return when (
        val sendResult = MessageFrameTransportSendUseCase.sendPublic(
            frame = frame,
            transportSender = transportSender,
            targetPeerId = targetPeerId
        )
    ) {
        BleTransportSendResult.QueuedLocally ->
            AutomatedDiagnosticsWifiDirectPeerReadySendResult.Sent(
                signal.copy(peerId = senderPeerId)
            )

        BleTransportSendResult.NotAvailable ->
            AutomatedDiagnosticsWifiDirectPeerReadySendResult.WriterUnavailable

        is BleTransportSendResult.Failed ->
            AutomatedDiagnosticsWifiDirectPeerReadySendResult.SendFailed(sendResult.reason)
    }
}

internal suspend fun submitAutomatedDiagnosticsPhaseStateSignal(
    bleConnectionStatus: BleConnectionStatus,
    activeTransportPeerId: String?,
    transportSender: BleTransportSender,
    localPeerId: String?,
    sharedRun: AutomatedDiagnosticsSharedRun,
    expectedRemotePeerId: String,
    stepId: AutomatedDiagnosticStepId,
    phaseState: AutomatedDiagnosticsPhaseState,
    attemptNumber: Int,
    applicationProbeDescriptors: List<AutomatedDiagnosticsPhaseApplicationProbeDescriptor> =
        emptyList(),
    createdAtMillis: Long
): AutomatedDiagnosticsPhaseStateSendResult {
    if (bleConnectionStatus != BleConnectionStatus.CONNECTED) {
        return AutomatedDiagnosticsPhaseStateSendResult.NoActivePeer
    }
    val targetPeerId = activeTransportPeerId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return AutomatedDiagnosticsPhaseStateSendResult.NoActivePeer
    val senderPeerId = localPeerId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return AutomatedDiagnosticsPhaseStateSendResult.InvalidSignal(
            "Local peer identity unavailable."
        )
    val phaseStateLeaseMillis =
        AutomatedDiagnosticsTimingPolicy.default().automatedDiagnosticsPhaseStateLeaseMillis
    val signal = runCatching {
        AutomatedDiagnosticsPhaseSignal(
            sharedRun = sharedRun,
            peerId = senderPeerId,
            expectedRemotePeerId = expectedRemotePeerId,
            stepId = stepId,
            phaseState = phaseState,
            attemptNumber = attemptNumber,
            applicationProbeDescriptors = applicationProbeDescriptors,
            createdAtMillis = createdAtMillis,
            expiresAtMillis = createdAtMillis + phaseStateLeaseMillis
        )
    }.getOrElse { error ->
        return AutomatedDiagnosticsPhaseStateSendResult.InvalidSignal(
            error.message ?: "Automated diagnostics phase state is invalid."
        )
    }
    val frame = runCatching {
        createAutomatedDiagnosticsPhaseStateFrame(
            signal = signal.copy(peerId = senderPeerId),
            targetPeerId = targetPeerId
        )
    }.getOrElse { error ->
        return AutomatedDiagnosticsPhaseStateSendResult.InvalidSignal(
            error.message ?: "Automated diagnostics phase state is invalid."
        )
    }

    return when (
        val sendResult = MessageFrameTransportSendUseCase.sendPublic(
            frame = frame,
            transportSender = transportSender,
            targetPeerId = targetPeerId
        )
    ) {
        BleTransportSendResult.QueuedLocally ->
            AutomatedDiagnosticsPhaseStateSendResult.Sent(signal.copy(peerId = senderPeerId))

        BleTransportSendResult.NotAvailable ->
            AutomatedDiagnosticsPhaseStateSendResult.WriterUnavailable

        is BleTransportSendResult.Failed ->
            AutomatedDiagnosticsPhaseStateSendResult.SendFailed(sendResult.reason)
    }
}

internal suspend fun submitAutomatedDiagnosticsServerReadySignal(
    bleConnectionStatus: BleConnectionStatus,
    activeTransportPeerId: String?,
    transportSender: BleTransportSender,
    localPeerId: String?,
    sharedRun: AutomatedDiagnosticsSharedRun,
    expectedClientPeerId: String,
    groupOwnerAddress: String,
    socketPort: Int,
    serverToken: Long,
    createdAtMillis: Long
): AutomatedDiagnosticsServerReadySendResult {
    if (bleConnectionStatus != BleConnectionStatus.CONNECTED) {
        return AutomatedDiagnosticsServerReadySendResult.NoActivePeer
    }
    val targetPeerId = activeTransportPeerId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return AutomatedDiagnosticsServerReadySendResult.NoActivePeer
    val senderPeerId = localPeerId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return AutomatedDiagnosticsServerReadySendResult.InvalidSignal(
            "Local peer identity unavailable."
        )
    val signal = runCatching {
        AutomatedDiagnosticsServerReadySignal(
            sharedRun = sharedRun,
            peerId = senderPeerId,
            expectedClientPeerId = expectedClientPeerId,
            groupOwnerAddress = groupOwnerAddress,
            socketPort = socketPort,
            serverToken = serverToken,
            createdAtMillis = createdAtMillis,
            expiresAtMillis = createdAtMillis + 8_000L
        )
    }.getOrElse { error ->
        return AutomatedDiagnosticsServerReadySendResult.InvalidSignal(
            error.message ?: "Automated diagnostics server-ready signal is invalid."
        )
    }
    val frame = runCatching {
        createAutomatedDiagnosticsServerReadyFrame(
            signal = signal.copy(peerId = senderPeerId),
            targetPeerId = targetPeerId
        )
    }.getOrElse { error ->
        return AutomatedDiagnosticsServerReadySendResult.InvalidSignal(
            error.message ?: "Automated diagnostics server-ready signal is invalid."
        )
    }

    return when (
        val sendResult = MessageFrameTransportSendUseCase.sendPublic(
            frame = frame,
            transportSender = transportSender,
            targetPeerId = targetPeerId
        )
    ) {
        BleTransportSendResult.QueuedLocally ->
            AutomatedDiagnosticsServerReadySendResult.Sent(signal.copy(peerId = senderPeerId))

        BleTransportSendResult.NotAvailable ->
            AutomatedDiagnosticsServerReadySendResult.WriterUnavailable

        is BleTransportSendResult.Failed ->
            AutomatedDiagnosticsServerReadySendResult.SendFailed(sendResult.reason)
    }
}

internal fun createHybridBootstrapManualOfferMessage(
    localPeerId: String,
    createdAtMillis: Long
): HybridTransportControlMessage {
    return HybridTransportControlMessage(
        messageType = HybridTransportControlMessage.MessageType.WIFI_DIRECT_OFFER,
        sessionId = localPeerId,
        publicPeerIdHint = localPeerId,
        createdAtMillis = createdAtMillis,
        capabilityFlags = setOf(
            HybridTransportControlMessage.CapabilityFlag.WIFI_DIRECT_BOOTSTRAP,
            HybridTransportControlMessage.CapabilityFlag.BLE_FALLBACK
        )
    )
}

internal fun createHybridBootstrapManualOfferFrame(
    localPeerId: String,
    targetPeerId: String,
    createdAtMillis: Long
): MessageFrame {
    val message = createHybridBootstrapManualOfferMessage(
        localPeerId = localPeerId,
        createdAtMillis = createdAtMillis
    )
    return HybridTransportControlFrameFactory.create(
        message = message,
        frameId = hybridBootstrapManualOfferFrameId(
            localPeerId = localPeerId,
            createdAtMillis = createdAtMillis
        ),
        senderId = localPeerId,
        recipientId = targetPeerId
    )
}

internal fun createHybridBootstrapManualAcceptMessage(
    localPeerId: String,
    sessionId: String,
    createdAtMillis: Long
): HybridTransportControlMessage {
    return HybridTransportControlMessage(
        messageType = HybridTransportControlMessage.MessageType.WIFI_DIRECT_ACCEPT,
        sessionId = sessionId,
        publicPeerIdHint = localPeerId,
        createdAtMillis = createdAtMillis,
        capabilityFlags = setOf(
            HybridTransportControlMessage.CapabilityFlag.WIFI_DIRECT_BOOTSTRAP,
            HybridTransportControlMessage.CapabilityFlag.BLE_FALLBACK
        )
    )
}

internal fun createHybridBootstrapManualAcceptFrame(
    localPeerId: String,
    targetPeerId: String,
    sessionId: String,
    createdAtMillis: Long
): MessageFrame {
    val message = createHybridBootstrapManualAcceptMessage(
        localPeerId = localPeerId,
        sessionId = sessionId,
        createdAtMillis = createdAtMillis
    )
    return HybridTransportControlFrameFactory.create(
        message = message,
        frameId = hybridBootstrapManualAcceptFrameId(
            localPeerId = localPeerId,
            createdAtMillis = createdAtMillis
        ),
        senderId = localPeerId,
        recipientId = targetPeerId
    )
}

internal fun createHybridBootstrapManualSocketHintMessage(
    localPeerId: String,
    sessionId: String,
    groupOwnerAddress: String,
    socketPort: Int,
    createdAtMillis: Long
): HybridTransportControlMessage {
    return HybridTransportControlMessage(
        messageType = HybridTransportControlMessage.MessageType.WIFI_DIRECT_SOCKET_HINT,
        sessionId = sessionId,
        publicPeerIdHint = localPeerId,
        groupOwnerAddress = groupOwnerAddress,
        socketPort = socketPort,
        createdAtMillis = createdAtMillis,
        capabilityFlags = setOf(
            HybridTransportControlMessage.CapabilityFlag.WIFI_DIRECT_BOOTSTRAP,
            HybridTransportControlMessage.CapabilityFlag.WIFI_DIRECT_SOCKET_HINT,
            HybridTransportControlMessage.CapabilityFlag.BLE_FALLBACK
        )
    )
}

internal fun createHybridBootstrapManualSocketHintFrame(
    localPeerId: String,
    targetPeerId: String,
    sessionId: String,
    groupOwnerAddress: String,
    socketPort: Int,
    createdAtMillis: Long
): MessageFrame {
    val message = createHybridBootstrapManualSocketHintMessage(
        localPeerId = localPeerId,
        sessionId = sessionId,
        groupOwnerAddress = groupOwnerAddress,
        socketPort = socketPort,
        createdAtMillis = createdAtMillis
    )
    return HybridTransportControlFrameFactory.create(
        message = message,
        frameId = hybridBootstrapManualSocketHintFrameId(
            localPeerId = localPeerId,
            createdAtMillis = createdAtMillis
        ),
        senderId = localPeerId,
        recipientId = targetPeerId
    )
}

internal fun createAutomatedDiagnosticsRunAnnouncementFrame(
    announcement: AutomatedDiagnosticsRunAnnouncement,
    targetPeerId: String
): MessageFrame {
    val message = HybridTransportControlMessage(
        messageType = HybridTransportControlMessage.MessageType.AUTOMATED_DIAGNOSTICS_RUN_ANNOUNCE,
        sessionId = announcement.sharedRun.runId,
        publicPeerIdHint = announcement.sharedRun.coordinatorPeerId,
        relatedPeerIdHint = announcement.sharedRun.participantPeerId,
        createdAtMillis = announcement.createdAtMillis,
        associatedSessionId = announcement.sharedRun.sessionAssociationId,
        expiresAtMillis = announcement.sharedRun.expiresAtMillis,
        capabilityFlags = setOf(
            HybridTransportControlMessage.CapabilityFlag.BLE_FALLBACK
        )
    )
    return HybridTransportControlFrameFactory.create(
        message = message,
        frameId = automatedDiagnosticsRunAnnouncementFrameId(
            localPeerId = announcement.sharedRun.coordinatorPeerId,
            runId = announcement.sharedRun.runId,
            createdAtMillis = announcement.createdAtMillis
        ),
        senderId = announcement.peerId,
        recipientId = targetPeerId
    )
}

internal fun createAutomatedDiagnosticsParticipantJoinFrame(
    join: AutomatedDiagnosticsParticipantJoin,
    targetPeerId: String
): MessageFrame {
    val message = HybridTransportControlMessage(
        messageType = HybridTransportControlMessage.MessageType.AUTOMATED_DIAGNOSTICS_PARTICIPANT_JOIN,
        sessionId = join.sharedRun.runId,
        publicPeerIdHint = join.sharedRun.participantPeerId,
        relatedPeerIdHint = join.sharedRun.coordinatorPeerId,
        createdAtMillis = join.createdAtMillis,
        associatedSessionId = join.sharedRun.sessionAssociationId,
        expiresAtMillis = join.sharedRun.expiresAtMillis,
        capabilityFlags = setOf(
            HybridTransportControlMessage.CapabilityFlag.BLE_FALLBACK
        )
    )
    return HybridTransportControlFrameFactory.create(
        message = message,
        frameId = automatedDiagnosticsParticipantJoinFrameId(
            localPeerId = join.sharedRun.participantPeerId,
            runId = join.sharedRun.runId,
            createdAtMillis = join.createdAtMillis
        ),
        senderId = join.peerId,
        recipientId = targetPeerId
    )
}

internal fun createAutomatedDiagnosticsWifiDirectPeerReadyFrame(
    signal: AutomatedDiagnosticsWifiDirectPeerReadySignal,
    targetPeerId: String
): MessageFrame {
    val message = HybridTransportControlMessage(
        messageType = HybridTransportControlMessage.MessageType.AUTOMATED_DIAGNOSTICS_PHASE_READY,
        sessionId = signal.sharedRun.runId,
        publicPeerIdHint = signal.sharedRun.coordinatorPeerId,
        relatedPeerIdHint = signal.sharedRun.participantPeerId,
        senderPeerIdHint = signal.peerId,
        expectedPeerIdHint = signal.expectedRemotePeerId,
        wifiDirectCorrelationToken = signal.wifiDirectCorrelationToken,
        wifiDirectDeviceName = signal.wifiDirectDeviceName,
        createdAtMillis = signal.createdAtMillis,
        associatedSessionId = signal.sharedRun.sessionAssociationId,
        expiresAtMillis = signal.expiresAtMillis,
        capabilityFlags = setOf(
            HybridTransportControlMessage.CapabilityFlag.BLE_FALLBACK
        )
    )
    return HybridTransportControlFrameFactory.create(
        message = message,
        frameId = automatedDiagnosticsWifiDirectPeerReadyFrameId(
            localPeerId = signal.peerId,
            runId = signal.sharedRun.runId,
            createdAtMillis = signal.createdAtMillis
        ),
        senderId = signal.peerId,
        recipientId = targetPeerId
    )
}

internal fun createAutomatedDiagnosticsPhaseStateFrame(
    signal: AutomatedDiagnosticsPhaseSignal,
    targetPeerId: String
): MessageFrame {
    val applicationProbePayload =
        automatedDiagnosticsPhaseApplicationProbePayloadOrNull(
            signal.applicationProbeDescriptors
        )
    val message = HybridTransportControlMessage(
        messageType = HybridTransportControlMessage.MessageType.AUTOMATED_DIAGNOSTICS_PHASE_READY,
        sessionId = signal.sharedRun.runId,
        publicPeerIdHint = signal.sharedRun.coordinatorPeerId,
        relatedPeerIdHint = signal.sharedRun.participantPeerId,
        senderPeerIdHint = signal.peerId,
        expectedPeerIdHint = signal.expectedRemotePeerId,
        diagnosticsStepNumber = signal.stepId.stepNumber,
        diagnosticsPhaseState = signal.phaseState.name,
        diagnosticsAttemptNumber = signal.attemptNumber,
        diagnosticsApplicationProbePayload = applicationProbePayload,
        createdAtMillis = signal.createdAtMillis,
        associatedSessionId = signal.sharedRun.sessionAssociationId,
        expiresAtMillis = signal.expiresAtMillis,
        capabilityFlags = setOf(
            HybridTransportControlMessage.CapabilityFlag.BLE_FALLBACK
        )
    )
    return HybridTransportControlFrameFactory.create(
        message = message,
        frameId = automatedDiagnosticsPhaseStateFrameId(
            localPeerId = signal.peerId,
            runId = signal.sharedRun.runId,
            stepNumber = signal.stepId.stepNumber,
            attemptNumber = signal.attemptNumber,
            phaseState = signal.phaseState,
            applicationProbePayload = applicationProbePayload,
            createdAtMillis = signal.createdAtMillis
        ),
        senderId = signal.peerId,
        recipientId = targetPeerId
    )
}

internal fun createAutomatedDiagnosticsServerReadyFrame(
    signal: AutomatedDiagnosticsServerReadySignal,
    targetPeerId: String
): MessageFrame {
    val message = HybridTransportControlMessage(
        messageType = HybridTransportControlMessage.MessageType.AUTOMATED_DIAGNOSTICS_SERVER_READY,
        sessionId = signal.sharedRun.runId,
        publicPeerIdHint = signal.sharedRun.coordinatorPeerId,
        relatedPeerIdHint = signal.sharedRun.participantPeerId,
        senderPeerIdHint = signal.peerId,
        expectedPeerIdHint = signal.expectedClientPeerId,
        groupOwnerAddress = signal.groupOwnerAddress,
        socketPort = signal.socketPort,
        createdAtMillis = signal.createdAtMillis,
        associatedSessionId = signal.sharedRun.sessionAssociationId,
        expiresAtMillis = signal.expiresAtMillis,
        generationToken = signal.serverToken,
        capabilityFlags = setOf(
            HybridTransportControlMessage.CapabilityFlag.BLE_FALLBACK
        )
    )
    return HybridTransportControlFrameFactory.create(
        message = message,
        frameId = automatedDiagnosticsServerReadyFrameId(
            localPeerId = signal.peerId,
            runId = signal.sharedRun.runId,
            createdAtMillis = signal.createdAtMillis
        ),
        senderId = signal.peerId,
        recipientId = targetPeerId
    )
}

internal fun hybridBootstrapManualOfferFrameId(
    localPeerId: String,
    createdAtMillis: Long
): String {
    return "hybrid-offer-$localPeerId-$createdAtMillis"
}

internal fun hybridBootstrapManualAcceptFrameId(
    localPeerId: String,
    createdAtMillis: Long
): String {
    return "hybrid-accept-$localPeerId-$createdAtMillis"
}

internal fun hybridBootstrapManualSocketHintFrameId(
    localPeerId: String,
    createdAtMillis: Long
): String {
    return "hybrid-socket-hint-$localPeerId-$createdAtMillis"
}

internal fun automatedDiagnosticsRunAnnouncementFrameId(
    localPeerId: String,
    runId: String,
    createdAtMillis: Long
): String {
    return "diag-run-announce-$localPeerId-$runId-$createdAtMillis"
}

internal fun automatedDiagnosticsParticipantJoinFrameId(
    localPeerId: String,
    runId: String,
    createdAtMillis: Long
): String {
    return "diag-participant-join-$localPeerId-$runId-$createdAtMillis"
}

internal fun automatedDiagnosticsWifiDirectPeerReadyFrameId(
    localPeerId: String,
    runId: String,
    createdAtMillis: Long
): String {
    return "diag-peer-ready-$localPeerId-$runId-$createdAtMillis"
}

internal fun automatedDiagnosticsPhaseStateFrameId(
    localPeerId: String,
    runId: String,
    stepNumber: Int,
    attemptNumber: Int,
    phaseState: AutomatedDiagnosticsPhaseState,
    applicationProbePayload: String?,
    createdAtMillis: Long
): String {
    val payloadFingerprint = applicationProbePayload?.hashCode()?.toString() ?: "none"
    return "diag-phase-$localPeerId-$runId-$stepNumber-$attemptNumber-${phaseState.name}-$payloadFingerprint-$createdAtMillis"
}

internal fun automatedDiagnosticsServerReadyFrameId(
    localPeerId: String,
    runId: String,
    createdAtMillis: Long
): String {
    return "diag-server-ready-$localPeerId-$runId-$createdAtMillis"
}

internal fun hybridTransportControlMessageAsRunAnnouncementOrNull(
    peerId: String,
    message: HybridTransportControlMessage
): AutomatedDiagnosticsRunAnnouncement? {
    if (
        message.messageType !=
        HybridTransportControlMessage.MessageType.AUTOMATED_DIAGNOSTICS_RUN_ANNOUNCE
    ) {
        return null
    }
    val sharedRun = automatedDiagnosticsSharedRunFromMessageOrNull(
        message = message,
        fallbackCoordinatorPeerId = peerId
    ) ?: return null
    val sanitizedPeerId = peerId.trim().ifEmpty {
        sharedRun.coordinatorPeerId
    }
    return runCatching {
        AutomatedDiagnosticsRunAnnouncement(
            sharedRun = sharedRun,
            peerId = sanitizedPeerId,
            createdAtMillis = message.createdAtMillis
        )
    }.getOrNull()
}

internal fun hybridTransportControlMessageAsParticipantJoinOrNull(
    peerId: String,
    message: HybridTransportControlMessage
): AutomatedDiagnosticsParticipantJoin? {
    if (
        message.messageType !=
        HybridTransportControlMessage.MessageType.AUTOMATED_DIAGNOSTICS_PARTICIPANT_JOIN
    ) {
        return null
    }
    val coordinatorPeerId = message.relatedPeerIdHint?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    val participantPeerId = message.publicPeerIdHint?.trim()?.takeIf { it.isNotEmpty() }
        ?: peerId.trim().takeIf { it.isNotEmpty() }
        ?: return null
    val sessionAssociationId = message.associatedSessionId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    val expiresAtMillis = message.expiresAtMillis ?: return null
    val sharedRun = runCatching {
        AutomatedDiagnosticsSharedRun(
            runId = message.sessionId,
            coordinatorPeerId = coordinatorPeerId,
            participantPeerId = participantPeerId,
            sessionAssociationId = sessionAssociationId,
            createdAtMillis = message.createdAtMillis,
            expiresAtMillis = expiresAtMillis
        )
    }.getOrNull() ?: return null
    val sanitizedPeerId = peerId.trim().ifEmpty {
        sharedRun.participantPeerId
    }
    return runCatching {
        AutomatedDiagnosticsParticipantJoin(
            sharedRun = sharedRun,
            peerId = sanitizedPeerId,
            createdAtMillis = message.createdAtMillis
        )
    }.getOrNull()
}

internal fun hybridTransportControlMessageAsWifiDirectPeerReadySignalOrNull(
    peerId: String,
    message: HybridTransportControlMessage
): AutomatedDiagnosticsWifiDirectPeerReadySignal? {
    if (
        message.messageType !=
        HybridTransportControlMessage.MessageType.AUTOMATED_DIAGNOSTICS_PHASE_READY
    ) {
        return null
    }
    val sharedRun = automatedDiagnosticsSharedRunFromMessageOrNull(message) ?: return null
    val senderPeerId = message.senderPeerIdHint?.trim()?.takeIf { it.isNotEmpty() }
        ?: peerId.trim().takeIf { it.isNotEmpty() }
        ?: return null
    val expectedRemotePeerId = message.expectedPeerIdHint?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    val wifiDirectCorrelationToken = message.wifiDirectCorrelationToken?.trim()?.takeIf {
        it.isNotEmpty()
    } ?: return null
    val wifiDirectDeviceName = message.wifiDirectDeviceName?.trim()?.takeIf { it.isNotEmpty() }
    return runCatching {
        AutomatedDiagnosticsWifiDirectPeerReadySignal(
            sharedRun = sharedRun,
            peerId = senderPeerId,
            expectedRemotePeerId = expectedRemotePeerId,
            wifiDirectCorrelationToken = wifiDirectCorrelationToken,
            wifiDirectDeviceName = wifiDirectDeviceName,
            createdAtMillis = message.createdAtMillis,
            expiresAtMillis = message.expiresAtMillis ?: return null
        )
    }.getOrNull()
}

internal fun hybridTransportControlMessageAsAutomatedDiagnosticsPhaseSignalOrNull(
    peerId: String,
    message: HybridTransportControlMessage
): AutomatedDiagnosticsPhaseSignal? {
    if (
        message.messageType !=
        HybridTransportControlMessage.MessageType.AUTOMATED_DIAGNOSTICS_PHASE_READY
    ) {
        return null
    }
    val stepNumber = message.diagnosticsStepNumber ?: return null
    val phaseStateName = message.diagnosticsPhaseState?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    if (message.wifiDirectCorrelationToken != null) {
        return null
    }
    val stepId = AutomatedDiagnosticStepId.entries.firstOrNull { it.stepNumber == stepNumber }
        ?: return null
    val phaseState = runCatching {
        AutomatedDiagnosticsPhaseState.valueOf(phaseStateName)
    }.getOrNull() ?: return null
    val sharedRun = automatedDiagnosticsSharedRunFromMessageOrNull(message) ?: return null
    val senderPeerId = message.senderPeerIdHint?.trim()?.takeIf { it.isNotEmpty() }
        ?: peerId.trim().takeIf { it.isNotEmpty() }
        ?: return null
    val expectedRemotePeerId = message.expectedPeerIdHint?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    val attemptNumber = message.diagnosticsAttemptNumber ?: return null
    val applicationProbeDescriptors =
        automatedDiagnosticsPhaseApplicationProbeDescriptors(
            message.diagnosticsApplicationProbePayload
        )
    return runCatching {
        AutomatedDiagnosticsPhaseSignal(
            sharedRun = sharedRun,
            peerId = senderPeerId,
            expectedRemotePeerId = expectedRemotePeerId,
            stepId = stepId,
            phaseState = phaseState,
            attemptNumber = attemptNumber,
            applicationProbeDescriptors = applicationProbeDescriptors,
            createdAtMillis = message.createdAtMillis,
            expiresAtMillis = message.expiresAtMillis ?: return null
        )
    }.getOrNull()
}

internal fun hybridTransportControlMessageAsServerReadySignalOrNull(
    peerId: String,
    message: HybridTransportControlMessage
): AutomatedDiagnosticsServerReadySignal? {
    if (
        message.messageType !=
        HybridTransportControlMessage.MessageType.AUTOMATED_DIAGNOSTICS_SERVER_READY
    ) {
        return null
    }
    val sharedRun = automatedDiagnosticsSharedRunFromMessageOrNull(message) ?: return null
    val sanitizedPeerId = peerId.trim().takeIf { it.isNotEmpty() }
        ?: message.senderPeerIdHint?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    val expectedClientPeerId = message.expectedPeerIdHint?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    val groupOwnerAddress = message.groupOwnerAddress?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    val socketPort = message.socketPort ?: return null
    val serverToken = message.generationToken ?: return null
    return runCatching {
        AutomatedDiagnosticsServerReadySignal(
            sharedRun = sharedRun,
            peerId = sanitizedPeerId.ifEmpty { return null },
            expectedClientPeerId = expectedClientPeerId,
            groupOwnerAddress = groupOwnerAddress,
            socketPort = socketPort,
            serverToken = serverToken,
            createdAtMillis = message.createdAtMillis,
            expiresAtMillis = message.expiresAtMillis ?: return null
        )
    }.getOrNull()
}

internal fun automatedDiagnosticsSharedRunFromMessageOrNull(
    message: HybridTransportControlMessage,
    fallbackCoordinatorPeerId: String? = null,
    fallbackParticipantPeerId: String? = null
): AutomatedDiagnosticsSharedRun? {
    val coordinatorPeerId = message.publicPeerIdHint?.trim()?.takeIf { it.isNotEmpty() }
        ?: fallbackCoordinatorPeerId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    val participantPeerId = message.relatedPeerIdHint?.trim()?.takeIf { it.isNotEmpty() }
        ?: fallbackParticipantPeerId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    val sessionAssociationId = message.associatedSessionId?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    val expiresAtMillis = message.expiresAtMillis ?: return null
    return runCatching {
        AutomatedDiagnosticsSharedRun(
            runId = message.sessionId,
            coordinatorPeerId = coordinatorPeerId,
            participantPeerId = participantPeerId,
            sessionAssociationId = sessionAssociationId,
            createdAtMillis = message.createdAtMillis,
            expiresAtMillis = expiresAtMillis
        )
    }.getOrNull()
}

internal fun hybridBootstrapManualOfferRuntimeStatusText(
    result: HybridBootstrapManualOfferSendResult
): String {
    return when (result) {
        is HybridBootstrapManualOfferSendResult.Sent ->
            "Manual bootstrap offer sent: peer=${result.peerId} session=${result.sessionId}"
        HybridBootstrapManualOfferSendResult.NoActivePeer ->
            "Manual bootstrap offer unavailable: no active BLE peer."
        HybridBootstrapManualOfferSendResult.NoActiveSession ->
            "Manual bootstrap offer unavailable: no active BLE session."
        HybridBootstrapManualOfferSendResult.WriterUnavailable ->
            "Manual bootstrap offer unavailable: BLE writer unavailable."
        is HybridBootstrapManualOfferSendResult.InvalidOffer ->
            "Manual bootstrap offer invalid: ${result.reason}"
        is HybridBootstrapManualOfferSendResult.SendFailed ->
            "Manual bootstrap offer failed: ${result.reason}"
    }
}

internal fun hybridBootstrapManualAcceptRuntimeStatusText(
    result: HybridBootstrapManualAcceptSendResult
): String {
    return when (result) {
        is HybridBootstrapManualAcceptSendResult.Sent ->
            "Manual bootstrap accept sent: peer=${result.peerId} session=${result.sessionId}"
        HybridBootstrapManualAcceptSendResult.NoOfferCandidate ->
            "Manual bootstrap accept unavailable: no received OFFER candidate."
        HybridBootstrapManualAcceptSendResult.NoActivePeer ->
            "Manual bootstrap accept unavailable: no active BLE peer."
        HybridBootstrapManualAcceptSendResult.NoActiveSession ->
            "Manual bootstrap accept unavailable: no active BLE session."
        HybridBootstrapManualAcceptSendResult.WriterUnavailable ->
            "Manual bootstrap accept unavailable: BLE writer unavailable."
        is HybridBootstrapManualAcceptSendResult.InvalidAccept ->
            "Manual bootstrap accept invalid: ${result.reason}"
        is HybridBootstrapManualAcceptSendResult.SendFailed ->
            "Manual bootstrap accept failed: ${result.reason}"
    }
}

internal fun hybridBootstrapManualSocketHintRuntimeStatusText(
    result: HybridBootstrapManualSocketHintSendResult
): String {
    return when (result) {
        is HybridBootstrapManualSocketHintSendResult.Sent ->
            "Manual bootstrap socket hint sent: peer=${result.peerId} session=${result.sessionId} " +
                "address=${result.groupOwnerAddress} port=${result.socketPort}"
        HybridBootstrapManualSocketHintSendResult.NoActivePeer ->
            "Manual bootstrap socket hint unavailable: no active BLE peer."
        HybridBootstrapManualSocketHintSendResult.NoActiveSession ->
            "Manual bootstrap socket hint unavailable: no active BLE session."
        HybridBootstrapManualSocketHintSendResult.NoAcceptedCandidate ->
            "Manual bootstrap socket hint unavailable: no accepted hybrid candidate."
        HybridBootstrapManualSocketHintSendResult.NoSocketEndpoint ->
            "Manual bootstrap socket hint unavailable: no Wi-Fi Direct socket endpoint."
        HybridBootstrapManualSocketHintSendResult.NotGroupOwner ->
            "Manual bootstrap socket hint unavailable: this device is not the Wi-Fi Direct group owner."
        HybridBootstrapManualSocketHintSendResult.WriterUnavailable ->
            "Manual bootstrap socket hint unavailable: BLE writer unavailable."
        is HybridBootstrapManualSocketHintSendResult.InvalidSocketHint ->
            "Manual bootstrap socket hint invalid: ${result.reason}"
        is HybridBootstrapManualSocketHintSendResult.SendFailed ->
            "Manual bootstrap socket hint failed: ${result.reason}"
    }
}

internal fun createHybridBootstrapManualOfferRequestCallback(
    explicitManualOfferAction: suspend () -> HybridBootstrapManualOfferSendResult
): suspend () -> HybridBootstrapManualOfferSendResult {
    return {
        explicitManualOfferAction()
    }
}

internal fun createHybridBootstrapManualAcceptRequestCallback(
    explicitManualAcceptAction: suspend () -> HybridBootstrapManualAcceptSendResult
): suspend () -> HybridBootstrapManualAcceptSendResult {
    return {
        explicitManualAcceptAction()
    }
}

internal fun createHybridBootstrapManualSocketHintRequestCallback(
    explicitManualSocketHintAction: suspend () -> HybridBootstrapManualSocketHintSendResult
): suspend () -> HybridBootstrapManualSocketHintSendResult {
    return {
        explicitManualSocketHintAction()
    }
}

internal fun hybridBootstrapAttemptCommandBuildResultAfterReceiveOrNull(
    result: BleTransportReceiveResult,
    provider: HybridBootstrapDecisionProvider,
    requestedAtMillis: Long,
    currentMonotonicMillis: Long = requestedAtMillis,
    commandCreatedAtMillis: Long
): HybridBootstrapAttemptCommandBuildResult? {
    val decision = hybridBootstrapAttemptDecisionAfterReceiveOrNull(
        result = result,
        provider = provider,
        requestedAtMillis = requestedAtMillis,
        currentMonotonicMillis = currentMonotonicMillis,
        socketHintObservation = hybridBootstrapSocketHintObservationAfterReceiveOrNull(
            result = result,
            observedAtMonotonicMillis = currentMonotonicMillis
        )
    ) ?: return null

    return HybridBootstrapAttemptCommandBuilder.build(
        decision = decision,
        commandCreatedAtMillis = commandCreatedAtMillis
    )
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

internal fun hybridBootstrapAttemptRuntimeStatusText(
    decision: HybridBootstrapAttemptDecision
): String? {
    return when (decision) {
        is HybridBootstrapAttemptDecision.Allowed ->
            "Hybrid bootstrap attempt: allowed peer=${decision.request.peerId} " +
                "session=${decision.request.sessionId} " +
                "address=${decision.request.groupOwnerAddress} " +
                "port=${decision.request.socketPort}"
        HybridBootstrapAttemptDecision.NoCandidates ->
            "Hybrid bootstrap attempt: no candidates"
        HybridBootstrapAttemptDecision.NoSocketReadyCandidate ->
            "Hybrid bootstrap attempt: no socket-ready candidate"
        is HybridBootstrapAttemptDecision.InvalidEndpoint ->
            "Hybrid bootstrap attempt: invalid endpoint: ${decision.reason}"
        is HybridBootstrapAttemptDecision.EndpointTooOld ->
            "Hybrid bootstrap attempt: endpoint too old age=${decision.ageMillis} max=${decision.maxAgeMillis}"
    }
}

internal fun hybridBootstrapAttemptCommandBuildRuntimeStatusText(
    result: HybridBootstrapAttemptCommandBuildResult
): String? {
    return when (result) {
        is HybridBootstrapAttemptCommandBuildResult.Built ->
            "Hybrid bootstrap command: built peer=${result.command.peerId} " +
                "session=${result.command.sessionId} " +
                "address=${result.command.groupOwnerAddress} " +
                "port=${result.command.socketPort}"
        HybridBootstrapAttemptCommandBuildResult.NoCandidates ->
            "Hybrid bootstrap command: no candidates"
        HybridBootstrapAttemptCommandBuildResult.NoSocketReadyCandidate ->
            "Hybrid bootstrap command: no socket-ready candidate"
        is HybridBootstrapAttemptCommandBuildResult.InvalidEndpoint ->
            "Hybrid bootstrap command: invalid endpoint: ${result.reason}"
        is HybridBootstrapAttemptCommandBuildResult.EndpointTooOld ->
            "Hybrid bootstrap command: endpoint too old age=${result.ageMillis} max=${result.maxAgeMillis}"
        is HybridBootstrapAttemptCommandBuildResult.NotAllowed ->
            "Hybrid bootstrap command: not allowed: ${result.reason}"
    }
}

internal fun hybridBootstrapCommandTriggerRuntimeStatusText(
    result: HybridBootstrapCommandTriggerResult
): String? {
    return when (result) {
        is HybridBootstrapCommandTriggerResult.Executed ->
            when (val executionResult = result.executionResult) {
                is HybridBootstrapCommandExecutionResult.Accepted ->
                    "Hybrid bootstrap trigger: accepted peer=${executionResult.peerId} " +
                        "session=${executionResult.sessionId} " +
                        "address=${executionResult.groupOwnerAddress} " +
                        "port=${executionResult.socketPort}"
                is HybridBootstrapCommandExecutionResult.Rejected ->
                    "Hybrid bootstrap trigger: rejected: ${executionResult.reason}"
            }
        HybridBootstrapCommandTriggerResult.NoCandidates ->
            "Hybrid bootstrap trigger: no candidates"
        HybridBootstrapCommandTriggerResult.NoSocketReadyCandidate ->
            "Hybrid bootstrap trigger: no socket-ready candidate"
        is HybridBootstrapCommandTriggerResult.InvalidEndpoint ->
            "Hybrid bootstrap trigger: invalid endpoint: ${result.reason}"
        is HybridBootstrapCommandTriggerResult.EndpointTooOld ->
            "Hybrid bootstrap trigger: endpoint too old age=${result.ageMillis} max=${result.maxAgeMillis}"
        is HybridBootstrapCommandTriggerResult.NotAllowed ->
            "Hybrid bootstrap trigger: not allowed: ${result.reason}"
    }
}

internal fun hybridBootstrapSocketEndpointRuntimeStatusText(
    resolution: HybridBootstrapSocketEndpointResolution
): String? {
    return when (resolution) {
        HybridBootstrapSocketEndpointResolution.NoCandidates ->
            "Hybrid bootstrap endpoint: no candidates"
        HybridBootstrapSocketEndpointResolution.NoSocketReadyCandidate ->
            "Hybrid bootstrap endpoint: no socket-ready candidate"
        is HybridBootstrapSocketEndpointResolution.InvalidSelectedCandidate ->
            "Hybrid bootstrap endpoint: invalid selected candidate: ${resolution.reason}"
        is HybridBootstrapSocketEndpointResolution.Resolved ->
            "Hybrid bootstrap endpoint: peer=${resolution.endpoint.peerId} " +
                "session=${resolution.endpoint.sessionId} " +
                "address=${resolution.endpoint.groupOwnerAddress} " +
                "port=${resolution.endpoint.socketPort}"
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
