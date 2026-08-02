package gr.hua.aurora.diagnostics.automated

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import gr.hua.aurora.ble.advertise.BleAdvertiseStatus
import gr.hua.aurora.ble.connection.BleConnectionStatus
import gr.hua.aurora.ble.discovery.BleDiscoveredDevice
import gr.hua.aurora.ble.discovery.BleScanStatus
import gr.hua.aurora.ble.permissions.BluetoothPermissionStatus
import gr.hua.aurora.model.AuroraContact
import gr.hua.aurora.model.PrivateChatIdentity
import gr.hua.aurora.transport.hybrid.HybridBootstrapDecision
import gr.hua.aurora.transport.hybrid.HybridBootstrapDiagnostics
import gr.hua.aurora.transport.hybrid.HybridBootstrapCommandTriggerResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapManualAcceptSendResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapManualOfferSendResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapManualSocketHintSendResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapManualTriggerSnapshot
import gr.hua.aurora.protocol.PeerIdentityExchangeSendResult
import gr.hua.aurora.protocol.PeerSessionRegistryDiagnostics
import gr.hua.aurora.state.AuroraAvailabilityPreference
import gr.hua.aurora.state.AuroraBleRuntimeState
import gr.hua.aurora.state.AuroraStateHolder
import gr.hua.aurora.wifidirect.debug.WifiDirectGlobalDebugSendDiagnostics
import gr.hua.aurora.wifidirect.debug.WifiDirectPrivateDebugSendDiagnostics
import gr.hua.aurora.wifidirect.debug.WifiDirectReceiveBridgeDiagnostics
import gr.hua.aurora.wifidirect.debug.WifiDirectSendBridgeDiagnostics
import gr.hua.aurora.wifidirect.frame.WifiDirectTransportAdapterDiagnostics
import gr.hua.aurora.wifidirect.model.WifiDirectPeer
import gr.hua.aurora.wifidirect.runtime.WifiDirectRolePreference
import gr.hua.aurora.wifidirect.runtime.WifiDirectRuntimeStatus
import gr.hua.aurora.wifidirect.socket.RememberedWifiDirectSocketState
import gr.hua.aurora.wifidirect.socket.WifiDirectSocketDiagnostics
import kotlinx.coroutines.CoroutineScope

data class AutomatedDiagnosticsRuntimeEvent(
    val atElapsedMillis: Long,
    val category: Category,
    val detail: String
) {
    init {
        require(atElapsedMillis >= 0L) {
            "Automated diagnostics runtime event time must be non-negative."
        }
        require(detail.isNotBlank()) {
            "Automated diagnostics runtime event detail must not be blank."
        }
    }

    enum class Category {
        ACTIVITY,
        BLE_RUNTIME,
        ADVERTISER,
        SCANNER,
        GATT,
        CONNECTION,
        CLEANUP
    }
}

enum class AutomatedDiagnosticsActivityLifecycleState {
    INITIALIZED,
    RESUMED,
    PAUSED,
    STOPPED,
    DISPOSED
}

data class AutomatedDiagnosticsRuntimeEvidence(
    val activityLifecycleState: AutomatedDiagnosticsActivityLifecycleState,
    val bleRuntimeHosted: Boolean,
    val lastCleanupReason: String?,
    val recentEvents: List<AutomatedDiagnosticsRuntimeEvent>
)

internal data class AutomatedDiagnosticsRuntimeSnapshot(
    val desiredAvailability: AuroraAvailabilityPreference,
    val bluetoothPermissionStatus: BluetoothPermissionStatus,
    val bleAdvertiseStatus: BleAdvertiseStatus,
    val bleScanStatus: BleScanStatus,
    val bleConnectionStatus: BleConnectionStatus,
    val activeTransportPeerId: String?,
    val selectedSecurePeerId: String?,
    val localPeerId: String?,
    val discoveredAuroraPeers: List<BleDiscoveredDevice>,
    val peerSessionDiagnostics: PeerSessionRegistryDiagnostics,
    val contacts: List<AuroraContact>,
    val privateChatIdentitiesByPeerId: Map<String, PrivateChatIdentity>,
    val identityHandlerStatus: String,
    val lastIdentityExchangeStatus: String?,
    val wifiDirectRuntimeStatus: WifiDirectRuntimeStatus,
    val wifiDirectSocketRuntimeInstanceId: String,
    val wifiDirectSocketCommandBindingInstanceId: String,
    val wifiDirectSocketDiagnostics: WifiDirectSocketDiagnostics,
    val wifiDirectAdapterDiagnostics: WifiDirectTransportAdapterDiagnostics,
    val wifiDirectSendBridgeDiagnostics: WifiDirectSendBridgeDiagnostics,
    val wifiDirectReceiveBridgeDiagnostics: WifiDirectReceiveBridgeDiagnostics,
    val wifiDirectGlobalDebugSendDiagnostics: WifiDirectGlobalDebugSendDiagnostics,
    val wifiDirectPrivateDebugSendDiagnostics: WifiDirectPrivateDebugSendDiagnostics,
    val hybridBootstrapDecision: HybridBootstrapDecision,
    val hybridBootstrapDiagnostics: HybridBootstrapDiagnostics,
    val latestAutomatedDiagnosticsRunAnnouncement: AutomatedDiagnosticsRunAnnouncement? = null,
    val latestAutomatedDiagnosticsParticipantJoin: AutomatedDiagnosticsParticipantJoin? = null,
    val latestAutomatedDiagnosticsWifiDirectPeerReadySignal:
    AutomatedDiagnosticsWifiDirectPeerReadySignal? = null,
    val latestAutomatedDiagnosticsServerReadySignal: AutomatedDiagnosticsServerReadySignal? = null,
    val lastAutomatedDiagnosticsCoordinationStatus: String? = null,
    val lastAutomatedDiagnosticsWifiDirectPeerReadyStatus: String? = null,
    val lastAutomatedDiagnosticsServerReadyStatus: String? = null,
    val hybridBootstrapManualTriggerSnapshot: HybridBootstrapManualTriggerSnapshot,
    val hybridBootstrapManualAcceptAvailable: Boolean,
    val hybridBootstrapManualAcceptBlockedReason: String?,
    val lastHybridBootstrapManualAcceptStatus: String?,
    val hybridBootstrapManualOfferAvailable: Boolean,
    val hybridBootstrapManualOfferBlockedReason: String?,
    val lastHybridBootstrapManualOfferStatus: String?,
    val lastHybridBootstrapManualSocketHintStatus: String?,
    val hybridBootstrapJavaNetRuntimeEnabled: Boolean,
    val runtimeEvidence: AutomatedDiagnosticsRuntimeEvidence
)

internal data class AutomatedDiagnosticsRunnerCommands(
    val updateDesiredAvailability: (AuroraAvailabilityPreference) -> Unit,
    val selectSecurePeer: (String) -> Unit,
    val connectToTransportPeer: (String, String?) -> Unit,
    val addOrUpdateContact: (String, String, Long?, Boolean) -> String?,
    val exchangeIdentityWithPeer: suspend (BleDiscoveredDevice, String?) -> PeerIdentityExchangeSendResult,
    val startWifiDirectDiscovery: () -> Unit,
    val stopWifiDirectDiscovery: () -> Unit,
    val registerAutomatedDiagnosticsWifiDirectService: (String, String?) -> Unit = { _, _ -> },
    val startAutomatedDiagnosticsWifiDirectServiceDiscovery: () -> Unit = {},
    val clearAutomatedDiagnosticsWifiDirectServiceDiscovery: () -> Unit = {},
    val connectToWifiDirectPeer: (WifiDirectPeer, WifiDirectRolePreference) -> Unit,
    val disconnectWifiDirectPeer: () -> Unit,
    val wifiDirectSocketCommandBindingInstanceId: () -> String = { "unknown" },
    val startWifiDirectSocketServer: (String?) -> Unit,
    val connectWifiDirectSocketClient: (String) -> Unit,
    val closeWifiDirectSocket: () -> Unit,
    val resetWifiDirectSocketDiagnostics: () -> Unit,
    val setWifiDirectSendBridgeEnabled: (Boolean) -> Unit,
    val setWifiDirectReceiveBridgeEnabled: (Boolean) -> Unit,
    val reportWifiDirectReceiveBridgeBlocked: (String) -> Unit,
    val setWifiDirectGlobalDebugSendEnabled: (Boolean) -> Unit,
    val setWifiDirectPrivateDebugSendEnabled: (Boolean) -> Unit,
    val clearAutomatedDiagnosticsSharedRunCoordinationState: () -> Unit = {},
    val clearAutomatedDiagnosticsCoordinationState: () -> Unit = {},
    val requestAutomatedDiagnosticsRunAnnouncement:
    suspend (AutomatedDiagnosticsSharedRun) -> AutomatedDiagnosticsRunAnnouncementSendResult =
        { AutomatedDiagnosticsRunAnnouncementSendResult.NoActivePeer },
    val requestAutomatedDiagnosticsParticipantJoin:
    suspend (AutomatedDiagnosticsSharedRun) -> AutomatedDiagnosticsParticipantJoinSendResult =
        { AutomatedDiagnosticsParticipantJoinSendResult.NoActivePeer },
    val requestAutomatedDiagnosticsWifiDirectPeerReadySignal:
    suspend (
        AutomatedDiagnosticsSharedRun,
        String,
        String,
        String?
    ) -> AutomatedDiagnosticsWifiDirectPeerReadySendResult =
        { _, _, _, _ -> AutomatedDiagnosticsWifiDirectPeerReadySendResult.NoActivePeer },
    val requestAutomatedDiagnosticsServerReadySignal:
    suspend (AutomatedDiagnosticsSharedRun, String, String, Int, Long) -> AutomatedDiagnosticsServerReadySendResult =
        { _, _, _, _, _ -> AutomatedDiagnosticsServerReadySendResult.NoActivePeer },
    val requestHybridBootstrapManualTrigger: () -> HybridBootstrapCommandTriggerResult,
    val requestHybridBootstrapManualOffer: suspend () -> HybridBootstrapManualOfferSendResult,
    val requestHybridBootstrapManualAccept: suspend () -> HybridBootstrapManualAcceptSendResult,
    val requestHybridBootstrapManualSocketHint: suspend () -> HybridBootstrapManualSocketHintSendResult
)

internal class AutomatedDiagnosticsRunnerBindings(
    val snapshot: () -> AutomatedDiagnosticsRuntimeSnapshot,
    val commands: AutomatedDiagnosticsRunnerCommands,
    val scope: CoroutineScope
)

internal fun createAutomatedDiagnosticsRunnerBindings(
    stateHolderProvider: () -> AuroraStateHolder,
    bleRuntimeStateProvider: () -> AuroraBleRuntimeState,
    wifiDirectSocketStateProvider: () -> RememberedWifiDirectSocketState,
    scope: CoroutineScope
): AutomatedDiagnosticsRunnerBindings {
    return AutomatedDiagnosticsRunnerBindings(
        snapshot = {
            val runtimeState = bleRuntimeStateProvider()
            val holder = stateHolderProvider()
            val wifiDirectSocketState = wifiDirectSocketStateProvider()
            AutomatedDiagnosticsRuntimeSnapshot(
                desiredAvailability = holder.uiState.desiredAvailability,
                bluetoothPermissionStatus = runtimeState.bluetoothPermissionStatus,
                bleAdvertiseStatus = runtimeState.bleAdvertiseStatus,
                bleScanStatus = runtimeState.bleScanStatus,
                bleConnectionStatus = runtimeState.bleConnectionStatus,
                activeTransportPeerId = runtimeState.activeTransportPeerId,
                selectedSecurePeerId = holder.uiState.selectedSecurePeerId,
                localPeerId = runtimeState.localPeerId,
                discoveredAuroraPeers = runtimeState.discoveredAuroraPeers,
                peerSessionDiagnostics = runtimeState.peerSessionDiagnostics,
                contacts = holder.uiState.contacts,
                privateChatIdentitiesByPeerId = holder.uiState.privateChatIdentitiesByPeerId,
                identityHandlerStatus = runtimeState.identityHandlerStatus,
                lastIdentityExchangeStatus = runtimeState.lastIdentityExchangeStatus,
                wifiDirectRuntimeStatus = runtimeState.wifiDirectRuntimeStatus,
                wifiDirectSocketRuntimeInstanceId = wifiDirectSocketState.instanceId,
                wifiDirectSocketCommandBindingInstanceId = wifiDirectSocketState.instanceId,
                wifiDirectSocketDiagnostics = wifiDirectSocketState.diagnostics,
                wifiDirectAdapterDiagnostics = wifiDirectSocketState.adapterDiagnostics,
                wifiDirectSendBridgeDiagnostics = wifiDirectSocketState.sendBridgeDiagnostics,
                wifiDirectReceiveBridgeDiagnostics = wifiDirectSocketState.receiveBridgeDiagnostics,
                wifiDirectGlobalDebugSendDiagnostics =
                wifiDirectSocketState.globalDebugSendDiagnostics,
                wifiDirectPrivateDebugSendDiagnostics =
                wifiDirectSocketState.privateDebugSendDiagnostics,
                hybridBootstrapDecision = runtimeState.hybridBootstrapDecision,
                hybridBootstrapDiagnostics = runtimeState.hybridBootstrapDiagnostics,
                latestAutomatedDiagnosticsRunAnnouncement =
                runtimeState.latestAutomatedDiagnosticsRunAnnouncement,
                latestAutomatedDiagnosticsParticipantJoin =
                runtimeState.latestAutomatedDiagnosticsParticipantJoin,
                latestAutomatedDiagnosticsWifiDirectPeerReadySignal =
                runtimeState.latestAutomatedDiagnosticsWifiDirectPeerReadySignal,
                latestAutomatedDiagnosticsServerReadySignal =
                runtimeState.latestAutomatedDiagnosticsServerReadySignal,
                lastAutomatedDiagnosticsCoordinationStatus =
                runtimeState.lastAutomatedDiagnosticsCoordinationStatus,
                lastAutomatedDiagnosticsWifiDirectPeerReadyStatus =
                runtimeState.lastAutomatedDiagnosticsWifiDirectPeerReadyStatus,
                lastAutomatedDiagnosticsServerReadyStatus =
                runtimeState.lastAutomatedDiagnosticsServerReadyStatus,
                hybridBootstrapManualTriggerSnapshot =
                runtimeState.hybridBootstrapManualTriggerSnapshot,
                hybridBootstrapManualAcceptAvailable =
                runtimeState.hybridBootstrapManualAcceptAvailable,
                hybridBootstrapManualAcceptBlockedReason =
                runtimeState.hybridBootstrapManualAcceptBlockedReason,
                lastHybridBootstrapManualAcceptStatus =
                runtimeState.lastHybridBootstrapManualAcceptStatus,
                hybridBootstrapManualOfferAvailable =
                runtimeState.hybridBootstrapManualOfferAvailable,
                hybridBootstrapManualOfferBlockedReason =
                runtimeState.hybridBootstrapManualOfferBlockedReason,
                lastHybridBootstrapManualOfferStatus =
                runtimeState.lastHybridBootstrapManualOfferStatus,
                lastHybridBootstrapManualSocketHintStatus =
                runtimeState.lastHybridBootstrapManualSocketHintStatus,
                hybridBootstrapJavaNetRuntimeEnabled = runtimeState.hybridBootstrapJavaNetRuntimeEnabled,
                runtimeEvidence = runtimeState.runtimeEvidence
            )
        },
        commands = AutomatedDiagnosticsRunnerCommands(
            updateDesiredAvailability = { preference ->
                stateHolderProvider().updateDesiredAvailability(preference)
            },
            selectSecurePeer = { peerId ->
                stateHolderProvider().selectSecurePeer(peerId)
            },
            connectToTransportPeer = { address, peerId ->
                bleRuntimeStateProvider().connectToTransportPeer(address, peerId)
            },
            addOrUpdateContact = { peerId, displayName, lastSeenMillis, hasSession ->
                stateHolderProvider().addOrUpdateContact(
                    canonicalPeerId = peerId,
                    displayName = displayName,
                    lastSeenMillis = lastSeenMillis,
                    hasSession = hasSession
                )
                stateHolderProvider().privateChatIdentityForPeerId(peerId)?.localProposalId
            },
            exchangeIdentityWithPeer = { device, proposalId ->
                bleRuntimeStateProvider().exchangeIdentityWithPeer(device, proposalId)
            },
            startWifiDirectDiscovery = {
                bleRuntimeStateProvider().startWifiDirectDiscovery()
            },
            stopWifiDirectDiscovery = {
                bleRuntimeStateProvider().stopWifiDirectDiscovery()
            },
            registerAutomatedDiagnosticsWifiDirectService = { token, deviceNameHint ->
                bleRuntimeStateProvider().registerAutomatedDiagnosticsWifiDirectService(
                    token,
                    deviceNameHint
                )
            },
            startAutomatedDiagnosticsWifiDirectServiceDiscovery = {
                bleRuntimeStateProvider().startAutomatedDiagnosticsWifiDirectServiceDiscovery()
            },
            clearAutomatedDiagnosticsWifiDirectServiceDiscovery = {
                bleRuntimeStateProvider().clearAutomatedDiagnosticsWifiDirectServiceDiscovery()
            },
            connectToWifiDirectPeer = { peer, rolePreference ->
                bleRuntimeStateProvider().connectToWifiDirectPeer(peer, rolePreference)
            },
            disconnectWifiDirectPeer = {
                bleRuntimeStateProvider().disconnectWifiDirectPeer()
            },
            wifiDirectSocketCommandBindingInstanceId = {
                wifiDirectSocketStateProvider().instanceId
            },
            startWifiDirectSocketServer = { hostHint ->
                wifiDirectSocketStateProvider().startServer(hostHint)
            },
            connectWifiDirectSocketClient = { host ->
                wifiDirectSocketStateProvider().connectClient(host)
            },
            closeWifiDirectSocket = {
                wifiDirectSocketStateProvider().closeSocket()
            },
            resetWifiDirectSocketDiagnostics = {
                wifiDirectSocketStateProvider().resetDiagnostics()
            },
            setWifiDirectSendBridgeEnabled = { enabled ->
                wifiDirectSocketStateProvider().setSendBridgeEnabled(enabled)
            },
            setWifiDirectReceiveBridgeEnabled = { enabled ->
                wifiDirectSocketStateProvider().setReceiveBridgeEnabled(enabled)
            },
            reportWifiDirectReceiveBridgeBlocked = { reason ->
                wifiDirectSocketStateProvider().reportReceiveBridgeToggleBlocked(reason)
            },
            setWifiDirectGlobalDebugSendEnabled = { enabled ->
                wifiDirectSocketStateProvider().setGlobalDebugSendEnabled(enabled)
            },
            setWifiDirectPrivateDebugSendEnabled = { enabled ->
                wifiDirectSocketStateProvider().setPrivateDebugSendEnabled(enabled)
            },
            clearAutomatedDiagnosticsSharedRunCoordinationState = {
                bleRuntimeStateProvider().clearAutomatedDiagnosticsSharedRunCoordinationState()
            },
            clearAutomatedDiagnosticsCoordinationState = {
                bleRuntimeStateProvider().clearAutomatedDiagnosticsCoordinationState()
            },
            requestAutomatedDiagnosticsRunAnnouncement = { sharedRun ->
                bleRuntimeStateProvider().onAutomatedDiagnosticsRunAnnouncementRequested(sharedRun)
            },
            requestAutomatedDiagnosticsParticipantJoin = { sharedRun ->
                bleRuntimeStateProvider().onAutomatedDiagnosticsParticipantJoinRequested(sharedRun)
            },
            requestAutomatedDiagnosticsWifiDirectPeerReadySignal = {
                    sharedRun,
                    expectedRemotePeerId,
                    wifiDirectCorrelationToken,
                    wifiDirectDeviceName ->
                bleRuntimeStateProvider().onAutomatedDiagnosticsWifiDirectPeerReadyRequested(
                    sharedRun,
                    expectedRemotePeerId,
                    wifiDirectCorrelationToken,
                    wifiDirectDeviceName
                )
            },
            requestAutomatedDiagnosticsServerReadySignal = {
                    sharedRun,
                    peerId,
                    groupOwnerAddress,
                    socketPort,
                    serverToken ->
                bleRuntimeStateProvider().onAutomatedDiagnosticsServerReadyRequested(
                    sharedRun,
                    peerId,
                    groupOwnerAddress,
                    socketPort,
                    serverToken
                )
            },
            requestHybridBootstrapManualTrigger = {
                bleRuntimeStateProvider().onHybridBootstrapManualTriggerRequested()
            },
            requestHybridBootstrapManualOffer = {
                bleRuntimeStateProvider().onHybridBootstrapManualOfferRequested()
            },
            requestHybridBootstrapManualAccept = {
                bleRuntimeStateProvider().onHybridBootstrapManualAcceptRequested()
            },
            requestHybridBootstrapManualSocketHint = {
                bleRuntimeStateProvider().onHybridBootstrapManualSocketHintRequested()
            }
        ),
        scope = scope
    )
}

@Composable
internal fun rememberAutomatedDiagnosticsRunnerBindings(
    stateHolder: AuroraStateHolder,
    bleRuntimeState: AuroraBleRuntimeState,
    wifiDirectSocketState: RememberedWifiDirectSocketState
): AutomatedDiagnosticsRunnerBindings {
    val scope = rememberCoroutineScope()
    val latestStateHolder = rememberUpdatedState(stateHolder)
    val latestRuntimeState = rememberUpdatedState(bleRuntimeState)
    val latestWifiDirectSocketState = rememberUpdatedState(wifiDirectSocketState)
    return remember(scope) {
        createAutomatedDiagnosticsRunnerBindings(
            stateHolderProvider = { latestStateHolder.value },
            bleRuntimeStateProvider = { latestRuntimeState.value },
            wifiDirectSocketStateProvider = { latestWifiDirectSocketState.value },
            scope = scope
        )
    }
}
