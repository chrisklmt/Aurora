package gr.hua.aurora.diagnostics.automated

import gr.hua.aurora.ble.advertise.BleAdvertiseStatus
import gr.hua.aurora.ble.connection.BleConnectionStatus
import gr.hua.aurora.ble.discovery.BleDiscoveredDevice
import gr.hua.aurora.ble.discovery.BleScanStatus
import gr.hua.aurora.transport.hybrid.HybridBootstrapCandidate
import gr.hua.aurora.transport.hybrid.HybridBootstrapCommandExecutionResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapCommandTriggerResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapManualAcceptSendResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapManualOfferSendResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapManualSocketHintSendResult
import gr.hua.aurora.protocol.PeerIdentityExchangeSendResult
import gr.hua.aurora.protocol.canonicalPeerIdFor
import gr.hua.aurora.protocol.hasSessionForPeer
import gr.hua.aurora.state.AuroraAvailabilityPreference
import gr.hua.aurora.wifidirect.controller.automatedDiagnosticsWifiDirectDnsSdProtocolTxtKey
import gr.hua.aurora.wifidirect.controller.automatedDiagnosticsWifiDirectDnsSdProtocolVersion
import gr.hua.aurora.wifidirect.controller.automatedDiagnosticsWifiDirectDnsSdServiceType
import gr.hua.aurora.wifidirect.controller.automatedDiagnosticsWifiDirectDnsSdTokenTxtKey
import gr.hua.aurora.wifidirect.controller.wifiDirectPeerMatches
import gr.hua.aurora.wifidirect.controller.WifiDirectEnabledState
import gr.hua.aurora.wifidirect.model.WifiDirectPeer
import gr.hua.aurora.wifidirect.runtime.WifiDirectConnectionRole
import gr.hua.aurora.wifidirect.runtime.WifiDirectConnectionState
import gr.hua.aurora.wifidirect.runtime.WifiDirectDiscoveryState
import gr.hua.aurora.wifidirect.runtime.WifiDirectGroupFormedState
import gr.hua.aurora.wifidirect.runtime.WifiDirectDnsSdServiceResponse
import gr.hua.aurora.wifidirect.runtime.WifiDirectLocalAddressClassification
import gr.hua.aurora.wifidirect.runtime.WifiDirectRolePreference
import gr.hua.aurora.wifidirect.socket.WifiDirectSocketState
import java.security.SecureRandom
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private enum class AutomatedDiagnosticsWifiDirectGroupProvenance {
    NONE,
    PRE_EXISTING,
    CURRENT_RUN_VALIDATED
}

internal class AutomatedDiagnosticsRunner(
    private val bindings: AutomatedDiagnosticsRunnerBindings,
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val delay: AutomatedDiagnosticsDelay = RealAutomatedDiagnosticsDelay,
    private val timingPolicy: AutomatedDiagnosticsTimingPolicy = AutomatedDiagnosticsTimingPolicy.default(),
    private val wallClockMillis: () -> Long = System::currentTimeMillis
) {
    private companion object {
        const val validatedDnsSdTokenPeerSource = "VALIDATED_DNS_SD_TOKEN"
        private const val sharedRunIdByteLength = 12
        private const val wifiDirectCorrelationTokenByteLength = 16
        private val sharedRunIdRandom = SecureRandom()
        private val wifiDirectCorrelationTokenRandom = SecureRandom()
        private val runnerExecutionIdRandom = SecureRandom()
        private val runnerInstanceIdRandom = SecureRandom()
    }

    private val mutableState = MutableStateFlow(AutomatedDiagnosticsRunState.initial())
    private val runnerInstanceId = generateRunnerInstanceId()
    private var runJob: Job? = null
    private var participantAutoJoinJob: Job? = null
    private var automaticParticipationEnabled: Boolean = false
    private var automaticParticipationEnableCallCount: Int = 0
    private var participantListenerGeneration: Int = 0
    private var participantListenerStartCount: Int = 0
    private var participantListenerCancellationCount: Int = 0
    private var manualStartInvocationCount: Int = 0
    private var participantStartInvocationCount: Int = 0
    private var participantJobGeneration: Int = 0
    private var announcementObservedCount: Int = 0
    private var announcementClaimedCount: Int = 0
    private var announcementClearedCount: Int = 0
    private var runAnnouncementSendCount: Int = 0
    private var participantJoinSendCount: Int = 0
    private var participantJoinSuccessfulSendCount: Int = 0
    private var lastAnnouncementClearCause: String = "none"
    private var lastAutoJoinBlocker: String = "none"
    private var lastSharedRunGenerationFunction: String = "none"
    private var lastObservedAutomaticAnnouncementSignature: String? = null
    private var pendingParticipantAnnouncement: PendingParticipantAnnouncement? = null

    val state: StateFlow<AutomatedDiagnosticsRunState> = mutableState.asStateFlow()

    fun start() {
        if (runJob?.isActive == true) {
            return
        }
        manualStartInvocationCount = 0
        participantStartInvocationCount = 0
        runAnnouncementSendCount = 0
        participantJoinSendCount = 0
        participantJoinSuccessfulSendCount = 0
        announcementObservedCount = 0
        announcementClaimedCount = 0
        announcementClearedCount = 0
        participantJobGeneration = 0
        lastAnnouncementClearCause = "manual-start"
        lastAutoJoinBlocker = "none"
        lastObservedAutomaticAnnouncementSignature = null
        lastSharedRunGenerationFunction = "none"
        clearPendingParticipantAnnouncement("start")
        manualStartInvocationCount += 1
        runJob = bindings.scope.launch {
            runFromStepIndex(
                resetReport = true,
                startIndex = 0,
                initialContextOverride = AutomatedDiagnosticsStepContext(
                    localRole = null,
                    runStartCause = AutomatedDiagnosticsRunStartCause.MANUAL_START
                )
            )
        }
    }

    fun setAutomaticParticipationEnabled(
        enabled: Boolean
    ) {
        if (enabled) {
            automaticParticipationEnableCallCount += 1
            if (
                automaticParticipationEnabled &&
                participantAutoJoinJob?.isActive == true
            ) {
                return
            }
            automaticParticipationEnabled = true
            if (participantAutoJoinJob?.isActive != true) {
                startParticipantAutoJoinListener()
            }
            return
        }

        if (!automaticParticipationEnabled && participantAutoJoinJob == null) {
            return
        }
        automaticParticipationEnabled = false
        participantAutoJoinJob?.cancel()
        participantAutoJoinJob = null
    }

    private fun startParticipantAutoJoinListener() {
        participantListenerGeneration += 1
        participantListenerStartCount += 1
        val listenerJob = bindings.scope.launch {
            runAutomaticParticipationLoop()
        }
        listenerJob.invokeOnCompletion { cause ->
            if (participantAutoJoinJob === listenerJob) {
                participantAutoJoinJob = null
            }
            if (cause != null) {
                participantListenerCancellationCount += 1
            }
        }
        participantAutoJoinJob = listenerJob
    }

    private suspend fun runAutomaticParticipationLoop() {
        while (currentCoroutineContext().isActive) {
            if (
                automaticParticipationEnabled &&
                runJob?.isActive != true &&
                mutableState.value.overallStatus == AutomatedDiagnosticsOverallStatus.IDLE
            ) {
                maybeAutoJoinParticipantRun()
            }
            delay.delayMillis(timingPolicy.pollIntervalMillis)
        }
    }

    fun stop() {
        val currentJob = runJob ?: return
        currentJob.cancel(CancellationException("Automated diagnostics stopped by user."))
        bindings.commands.clearAutomatedDiagnosticsCoordinationState()
        markCancelled()
    }

    fun retryFailedStep() {
        if (runJob?.isActive == true) {
            return
        }
        val retryIndex = mutableState.value.steps.indexOfFirst { step ->
            step.status == AutomatedDiagnosticStepStatus.FAIL ||
                step.status == AutomatedDiagnosticStepStatus.BLOCKED ||
                step.status == AutomatedDiagnosticStepStatus.CANCELLED
        }
        if (retryIndex < 0) {
            return
        }
        runJob = bindings.scope.launch {
            runFromStepIndex(resetReport = false, startIndex = retryIndex)
        }
    }

    fun resetReport() {
        runJob?.cancel()
        runJob = null
        clearPendingParticipantAnnouncement("resetReport")
        manualStartInvocationCount = 0
        participantStartInvocationCount = 0
        participantJobGeneration = 0
        announcementObservedCount = 0
        announcementClaimedCount = 0
        announcementClearedCount = 0
        runAnnouncementSendCount = 0
        participantJoinSendCount = 0
        participantJoinSuccessfulSendCount = 0
        lastAnnouncementClearCause = "resetReport"
        lastAutoJoinBlocker = "none"
        lastObservedAutomaticAnnouncementSignature = null
        lastSharedRunGenerationFunction = "none"
        bindings.commands.clearAutomatedDiagnosticsCoordinationState()
        mutableState.value = AutomatedDiagnosticsRunState.initial()
    }

    internal fun listenerDiagnosticsForTest(): ListenerDiagnosticsSnapshot {
        return ListenerDiagnosticsSnapshot(
            runnerInstanceId = runnerInstanceId,
            automaticParticipationEnabled = automaticParticipationEnabled,
            enableCallCount = automaticParticipationEnableCallCount,
            listenerGeneration = participantListenerGeneration,
            listenerActive = participantAutoJoinJob?.isActive == true,
            listenerStartCount = participantListenerStartCount,
            listenerCancellationCount = participantListenerCancellationCount,
            manualStartInvocationCount = manualStartInvocationCount,
            participantStartInvocationCount = participantStartInvocationCount,
            participantJobGeneration = participantJobGeneration,
            announcementObservedCount = announcementObservedCount,
            announcementClaimedCount = announcementClaimedCount,
            announcementClearedCount = announcementClearedCount,
            runAnnouncementSendCount = runAnnouncementSendCount,
            participantJoinSendCount = participantJoinSendCount,
            participantJoinSuccessfulSendCount = participantJoinSuccessfulSendCount,
            pendingAnnouncementRunId = pendingParticipantAnnouncement?.announcement?.sharedRun?.runId,
            lastAnnouncementClearCause = lastAnnouncementClearCause,
            lastAutoJoinBlocker = lastAutoJoinBlocker
        )
    }

    internal suspend fun pollAutomaticParticipationOnceForTest() {
        maybeAutoJoinParticipantRun()
    }

    internal suspend fun runFromStepIndexForTest(
        startIndex: Int = 0,
        resetReport: Boolean = true
    ) {
        runFromStepIndex(
            resetReport = resetReport,
            startIndex = startIndex
        )
    }

    private fun clearPendingParticipantAnnouncement(
        cause: String
    ) {
        if (pendingParticipantAnnouncement != null) {
            announcementClearedCount += 1
        }
        pendingParticipantAnnouncement = null
        lastAnnouncementClearCause = cause
    }

    private fun selectedPeerPropagationState(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot
    ): String {
        val targetPeerId = pendingParticipantAnnouncement?.selectedPeer?.identityKey
            ?: return "none"
        val selectedReady = snapshot.selectedSecurePeerId == targetPeerId
        val activeReady = snapshot.activeTransportPeerId == targetPeerId
        val sessionReady = pendingParticipantAnnouncement?.let { pendingAnnouncement ->
            currentSharedRunSessionAssociationId(snapshot, targetPeerId) ==
                pendingAnnouncement.sessionAssociationId
        } ?: (secureSessionBlocker(snapshot, targetPeerId) == null)
        return "selected=$selectedReady active=$activeReady session=$sessionReady"
    }

    private fun launchParticipantRun(
        seed: ParticipantAutoJoinSeed
    ) {
        if (runJob?.isActive == true) {
            return
        }
        participantStartInvocationCount += 1
        participantJobGeneration += 1
        clearPendingParticipantAnnouncement("launchParticipantRun")
        runJob = bindings.scope.launch {
            mutableState.value = seed.seededState
            runFromStepIndex(
                resetReport = false,
                startIndex = seed.startIndex,
                initialContextOverride = seed.context
            )
        }
    }

    private suspend fun runFromStepIndex(
        resetReport: Boolean,
        startIndex: Int,
        initialContextOverride: AutomatedDiagnosticsStepContext? = null
    ) {
        if (resetReport) {
            bindings.commands.clearAutomatedDiagnosticsCoordinationState()
            mutableState.value = AutomatedDiagnosticsRunState.initial().copy(
                localRunnerExecutionId = generateRunnerExecutionId()
            )
        } else {
            resetStepsFrom(startIndex)
        }
        val runStartedAt = mutableState.value.startedAtMillis ?: clock.nowMillis()
        updateRunState { current ->
            current.copy(
                overallStatus = AutomatedDiagnosticsOverallStatus.RUNNING,
                currentStepNumber = null,
                startedAtMillis = runStartedAt,
                completedAtMillis = null,
                elapsedMillis = 0L
            )
        }

        val stepContext = restoreStepContextForStartIndex(startIndex)
            .mergeFrom(initialContextOverride)
        val result = try {
            executeSteps(startIndex, stepContext)
        } catch (_: CancellationException) {
            markCancelled()
            return
        }

        updateRunState { current ->
            current.copy(
                overallStatus = result,
                currentStepNumber = null,
                completedAtMillis = clock.nowMillis(),
                elapsedMillis = clock.nowMillis() - runStartedAt
            )
        }
        refreshAggregateState()
        runJob = null
    }

    private suspend fun executeSteps(
        startIndex: Int,
        context: AutomatedDiagnosticsStepContext
    ): AutomatedDiagnosticsOverallStatus {
        val steps = AutomatedDiagnosticStepId.entries
        for (index in startIndex until steps.size) {
            val stepId = steps[index]
            if (
                stepId.ordinal > AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION.ordinal &&
                context.sharedRun != null
            ) {
                refreshSharedRunActiveLease(context)
            }
            val result = when (stepId) {
                AutomatedDiagnosticStepId.PREFLIGHT -> runPreflightStep(stepId)
                AutomatedDiagnosticStepId.BLE_RUNTIME -> runBleRuntimeStep(stepId)
                AutomatedDiagnosticStepId.AURORA_PEER_DISCOVERY -> runPeerDiscoveryStep(stepId, context)
                AutomatedDiagnosticStepId.ROLE_ELECTION -> runRoleElectionStep(stepId, context)
                AutomatedDiagnosticStepId.BLE_CONNECTION -> runBleConnectionStep(stepId, context)
                AutomatedDiagnosticStepId.SECURE_PEER_SELECTION -> runSecurePeerSelectionStep(stepId, context)
                AutomatedDiagnosticStepId.IDENTITY_KEY_SETUP -> runIdentitySetupStep(stepId, context)
                AutomatedDiagnosticStepId.SECURE_SESSION_READINESS -> runSecureSessionReadinessStep(stepId, context)
                AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION ->
                    runRemoteParticipantCoordinationStep(stepId, context)
                AutomatedDiagnosticStepId.BLE_STABILITY -> runBleStabilityStep(stepId, context)
                AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP ->
                    runWifiDirectDiscoveryAndGroupStep(stepId, context)
                AutomatedDiagnosticStepId.WIFI_DIRECT_SOCKET ->
                    runWifiDirectSocketStep(stepId, context)
                AutomatedDiagnosticStepId.WIFI_DIRECT_BRIDGES ->
                    runWifiDirectBridgesStep(stepId, context)
                AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_OFFER ->
                    runHybridBootstrapOfferStep(stepId, context)
                AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_ACCEPT ->
                    runHybridBootstrapAcceptStep(stepId, context)
                AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT ->
                    runHybridBootstrapSocketHintStep(stepId, context)
                AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_TRIGGER ->
                    runHybridBootstrapTriggerStep(stepId, context)
            }
            when (result) {
                AutomatedDiagnosticStepStatus.PASS -> Unit
                AutomatedDiagnosticStepStatus.FAIL -> {
                    blockRemainingSteps(index + 1, "Blocked by ${stepId.title.lowercase()}.")
                    refreshAggregateState()
                    return AutomatedDiagnosticsOverallStatus.FAIL
                }
                AutomatedDiagnosticStepStatus.BLOCKED -> {
                    blockRemainingSteps(index + 1, "Blocked by ${stepId.title.lowercase()}.")
                    refreshAggregateState()
                    return AutomatedDiagnosticsOverallStatus.BLOCKED
                }
                AutomatedDiagnosticStepStatus.CANCELLED -> {
                    blockRemainingSteps(index + 1, "Cancelled after ${stepId.title.lowercase()}.")
                    refreshAggregateState()
                    return AutomatedDiagnosticsOverallStatus.CANCELLED
                }
                else -> Unit
            }
        }
        refreshAggregateState()
        return AutomatedDiagnosticsOverallStatus.PASS
    }

    private suspend fun runPreflightStep(
        stepId: AutomatedDiagnosticStepId
    ): AutomatedDiagnosticStepStatus {
        bindings.commands.updateDesiredAvailability(AuroraAvailabilityPreference.ONLINE)
        return awaitStableSnapshotStep(
            stepId = stepId,
            window = timingPolicy.recompositionSettle,
            summary = "Checking device readiness",
            successSummary = "Device preflight passed",
            blockingReason = {
                preflightBlocker(snapshot = it)
            },
            requiredActionForSnapshot = {
                preflightRequiredAction(snapshot = it)
            },
            blockImmediatelyWhenActionRequired = true,
            successEvidence = { snapshot ->
                listOf(
                    AutomatedDiagnosticEvidenceValue(
                        "Permissions",
                        if (snapshot.bluetoothPermissionStatus.allRequiredGranted) "ready" else "missing"
                    ),
                    AutomatedDiagnosticEvidenceValue(
                        "Bluetooth",
                        booleanStateText(snapshot.bluetoothPermissionStatus.isBluetoothEnabled)
                    ),
                    AutomatedDiagnosticEvidenceValue(
                        "Location/GPS",
                        booleanStateText(snapshot.bluetoothPermissionStatus.isLocationEnabled)
                    ),
                    AutomatedDiagnosticEvidenceValue(
                        "Desired availability",
                        snapshot.desiredAvailability.name
                    ),
                    AutomatedDiagnosticEvidenceValue(
                        "Runtime hosted",
                        snapshot.runtimeEvidence.bleRuntimeHosted.toString()
                    ),
                    AutomatedDiagnosticEvidenceValue(
                        "JavaNet runtime",
                        snapshot.hybridBootstrapJavaNetRuntimeEnabled.toString()
                    )
                )
            },
            isSatisfied = { snapshot ->
                preflightBlocker(snapshot) == null
            }
        )
    }

    private suspend fun runBleRuntimeStep(
        stepId: AutomatedDiagnosticStepId
    ): AutomatedDiagnosticStepStatus {
        return awaitStableSnapshotStep(
            stepId = stepId,
            window = timingPolicy.bleRuntimeStable,
            summary = "Waiting for BLE advertiser and scanner",
            successSummary = "BLE advertiser and scanner are stable",
            blockingReason = {
                bleRuntimeBlocker(it)
            },
            successEvidence = { snapshot ->
                listOf(
                    AutomatedDiagnosticEvidenceValue("Advertiser", snapshot.bleAdvertiseStatus.name),
                    AutomatedDiagnosticEvidenceValue("Scanner", snapshot.bleScanStatus.name),
                    AutomatedDiagnosticEvidenceValue(
                        "Runtime hosted",
                        snapshot.runtimeEvidence.bleRuntimeHosted.toString()
                    )
                )
            },
            isSatisfied = { snapshot ->
                bleRuntimeBlocker(snapshot) == null
            }
        )
    }

    private suspend fun runPeerDiscoveryStep(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext
    ): AutomatedDiagnosticStepStatus {
        val status = awaitStableSnapshotStep(
            stepId = stepId,
            window = timingPolicy.auroraPeerDiscovery,
            summary = "Waiting for an Aurora peer",
            successSummary = "Aurora peer discovered",
            blockingReason = {
                if (selectDeterministicPeer(it.discoveredAuroraPeers) == null) {
                    "Timed out waiting for a real Aurora discovery payload."
                } else {
                    null
                }
            },
            successEvidence = { snapshot ->
                val peer = selectDeterministicPeer(snapshot.discoveredAuroraPeers)
                buildList {
                    add(
                        AutomatedDiagnosticEvidenceValue(
                            "Visible peers",
                            snapshot.discoveredAuroraPeers.size.toString()
                        )
                    )
                    if (peer != null) {
                        add(AutomatedDiagnosticEvidenceValue("Chosen peer", peer.identityKey))
                        add(AutomatedDiagnosticEvidenceValue("Address", peer.device.address))
                    }
                }
            },
            isSatisfied = { snapshot ->
                selectDeterministicPeer(snapshot.discoveredAuroraPeers) != null
            }
        )
        if (status == AutomatedDiagnosticStepStatus.PASS) {
            selectDeterministicPeer(bindings.snapshot().discoveredAuroraPeers)?.let { selected ->
                context.selectedPeer = selected
                updateRunState { current ->
                    current.copy(selectedPeerId = selected.identityKey)
                }
            }
        }
        return status
    }

    private suspend fun runRoleElectionStep(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext
    ): AutomatedDiagnosticStepStatus {
        val selectedPeer = context.selectedPeer ?: return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.BLOCKED,
            summary = "Role election blocked",
            blocker = "No deterministic Aurora peer has been selected."
        )
        val snapshot = bindings.snapshot()
        val localPeerId = snapshot.localPeerId
            ?: return completeStep(
                stepId = stepId,
                status = AutomatedDiagnosticStepStatus.BLOCKED,
                summary = "Role election blocked",
                blocker = "Local peer identity is unavailable."
            )
        val role = context.localRole ?: if (localPeerId <= selectedPeer.identityKey) {
            AutomatedDiagnosticsPeerRole.COORDINATOR
        } else {
            AutomatedDiagnosticsPeerRole.PARTICIPANT
        }
        context.localRole = role
        context.conflictAuthorityPeerId = listOf(localPeerId, selectedPeer.identityKey)
            .sorted()
            .first()
        updateRunState { current ->
            current.copy(localPeerRole = role)
        }
        return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.PASS,
            summary = if (context.sharedRun != null) {
                "Shared diagnostics role restored"
            } else if (role == AutomatedDiagnosticsPeerRole.COORDINATOR) {
                "Local runner will provisionally coordinate the shared run"
            } else {
                "Local runner will join as the participant"
            },
            evidence = listOf(
                AutomatedDiagnosticEvidenceValue("Local peer", localPeerId),
                AutomatedDiagnosticEvidenceValue("Remote peer", selectedPeer.identityKey),
                AutomatedDiagnosticEvidenceValue("Role", role.name),
                AutomatedDiagnosticEvidenceValue(
                    "Conflict authority peer",
                    context.conflictAuthorityPeerId ?: "none"
                )
            ),
            technicalDetails = listOf(
                "If both devices press Run at nearly the same time, the lower stable peer id wins the authoritative coordinator tie-break."
            )
        )
    }

    private suspend fun runBleConnectionStep(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext
    ): AutomatedDiagnosticStepStatus {
        val selectedPeer = context.selectedPeer ?: return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.BLOCKED,
            summary = "BLE connection blocked",
            blocker = "No discovered Aurora peer is available."
        )

        repeat(timingPolicy.bleConnection.maxRetries + 1) { attemptIndex ->
            setStepRunning(
                stepId = stepId,
                retryCount = attemptIndex,
                summary = if (attemptIndex == 0) {
                    "Connecting over BLE"
                } else {
                    "Retry ${attemptIndex}/${timingPolicy.bleConnection.maxRetries} — BLE connection"
                }
            )
            bindings.commands.connectToTransportPeer(
                selectedPeer.device.address,
                selectedPeer.identityKey
            )
            val status = awaitStableSnapshotStep(
                stepId = stepId,
                window = timingPolicy.bleConnection,
                summary = "Waiting for BLE connection",
                successSummary = "BLE connection is stable",
                retryCount = attemptIndex,
                blockingReason = {
                    "BLE connection did not become stable within ${timingPolicy.bleConnection.timeoutMillis} ms."
                },
                successEvidence = { snapshot ->
                    listOf(
                        AutomatedDiagnosticEvidenceValue("Connection", snapshot.bleConnectionStatus.name),
                        AutomatedDiagnosticEvidenceValue("Active peer", snapshot.activeTransportPeerId ?: "none"),
                        AutomatedDiagnosticEvidenceValue("Target peer", selectedPeer.identityKey)
                    )
                },
                isSatisfied = { snapshot ->
                    snapshot.bleConnectionStatus == BleConnectionStatus.CONNECTED &&
                        snapshot.activeTransportPeerId == selectedPeer.identityKey
                }
            )
            if (status == AutomatedDiagnosticStepStatus.PASS) {
                return status
            }
        }

        return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.FAIL,
            summary = "BLE connection failed",
            blocker = "BLE connection did not become stable after ${timingPolicy.bleConnection.maxRetries + 1} attempts.",
            evidence = latestRuntimeEvidence()
        )
    }

    private suspend fun runSecurePeerSelectionStep(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext
    ): AutomatedDiagnosticStepStatus {
        val selectedPeer = context.selectedPeer ?: return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.BLOCKED,
            summary = "Secure peer selection blocked",
            blocker = "No discovered Aurora peer is available."
        )
        bindings.commands.selectSecurePeer(selectedPeer.identityKey)
        return awaitStableSnapshotStep(
            stepId = stepId,
            window = timingPolicy.securePeerSelection,
            summary = "Selecting secure peer",
            successSummary = "Secure peer selected",
            blockingReason = {
                securePeerSelectionBlocker(it, selectedPeer.identityKey)
            },
            successEvidence = { snapshot ->
                listOf(
                    AutomatedDiagnosticEvidenceValue("Selected peer", snapshot.selectedSecurePeerId ?: "none"),
                    AutomatedDiagnosticEvidenceValue("Active peer", snapshot.activeTransportPeerId ?: "none")
                )
            },
            isSatisfied = { snapshot ->
                securePeerSelectionBlocker(snapshot, selectedPeer.identityKey) == null
            }
        )
    }

    private suspend fun runIdentitySetupStep(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext
    ): AutomatedDiagnosticStepStatus {
        val selectedPeer = context.selectedPeer ?: return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.BLOCKED,
            summary = "Identity setup blocked",
            blocker = "No discovered Aurora peer is available."
        )
        val proposalId = bindings.commands.addOrUpdateContact(
            selectedPeer.identityKey,
            selectedPeer.displayName,
            System.currentTimeMillis(),
            false
        )
        val sendResult = bindings.commands.exchangeIdentityWithPeer(
            selectedPeer.device,
            proposalId
        )
        return when (sendResult) {
            PeerIdentityExchangeSendResult.SubmittedLocally -> {
                val readinessStatus = awaitStableSnapshotStep(
                    stepId = stepId,
                    window = timingPolicy.identityExchange,
                    summary = "Waiting for secure setup to complete",
                    successSummary = "Identity/key setup completed",
                    blockingReason = {
                        "Waiting for the peer to complete identity setup."
                    },
                    successEvidence = { snapshot ->
                        identityEvidence(snapshot, selectedPeer.identityKey)
                    },
                    isSatisfied = { snapshot ->
                        secureSessionBlocker(snapshot, selectedPeer.identityKey) == null
                    }
                )
                if (readinessStatus == AutomatedDiagnosticStepStatus.PASS) {
                    readinessStatus
                } else {
                    completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.BLOCKED,
                        summary = "Identity setup needs peer-side completion",
                        blocker = "On the peer device, add this contact once or run the same diagnostics screen, then retry this step.",
                        evidence = identityEvidence(bindings.snapshot(), selectedPeer.identityKey)
                    )
                }
            }
            PeerIdentityExchangeSendResult.SenderUnavailable ->
                completeStep(
                    stepId = stepId,
                    status = AutomatedDiagnosticStepStatus.FAIL,
                    summary = "Identity setup failed",
                    blocker = "Identity sender unavailable."
                )

            is PeerIdentityExchangeSendResult.InvalidLocalIdentity ->
                completeStep(
                    stepId = stepId,
                    status = AutomatedDiagnosticStepStatus.FAIL,
                    summary = "Identity setup failed",
                    blocker = sendResult.reason
                )

            is PeerIdentityExchangeSendResult.Failed ->
                completeStep(
                    stepId = stepId,
                    status = AutomatedDiagnosticStepStatus.FAIL,
                    summary = "Identity setup failed",
                    blocker = sendResult.reason
                )
        }
    }

    private suspend fun runSecureSessionReadinessStep(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext
    ): AutomatedDiagnosticStepStatus {
        val selectedPeer = context.selectedPeer ?: return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.BLOCKED,
            summary = "Secure session readiness blocked",
            blocker = "No discovered Aurora peer is available."
        )
        return awaitStableSnapshotStep(
            stepId = stepId,
            window = timingPolicy.secureSessionReadiness,
            summary = "Waiting for secure session readiness",
            successSummary = "Secure session is ready",
            blockingReason = {
                secureSessionBlocker(it, selectedPeer.identityKey)
            },
            successEvidence = { snapshot ->
                secureSessionEvidence(snapshot, selectedPeer.identityKey)
            },
            isSatisfied = { snapshot ->
                secureSessionBlocker(snapshot, selectedPeer.identityKey) == null
            }
        )
    }

    private suspend fun runRemoteParticipantCoordinationStep(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext
    ): AutomatedDiagnosticStepStatus {
        val selectedPeer = context.selectedPeer ?: return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.BLOCKED,
            summary = "Remote coordination blocked",
            blocker = "No discovered Aurora peer is available."
        )
        val initialSnapshot = bindings.snapshot()
        val localPeerId = initialSnapshot.localPeerId
            ?: return completeStep(
                stepId = stepId,
                status = AutomatedDiagnosticStepStatus.BLOCKED,
                summary = "Remote coordination blocked",
                blocker = "Local peer identity is unavailable."
            )
        val sessionAssociationId =
            currentSharedRunSessionAssociationId(initialSnapshot, selectedPeer.identityKey)
                ?: return completeStep(
                    stepId = stepId,
                    status = AutomatedDiagnosticStepStatus.BLOCKED,
                    summary = "Remote coordination blocked",
                blocker = "Private chat id is unavailable for the selected peer."
            )
        val canonicalPeerPair = AutomatedDiagnosticsCanonicalPeerPair.from(
            localPeerId,
            selectedPeer.identityKey
        )

        if (context.runStartCause == null) {
            context.runStartCause = if (context.localRole == AutomatedDiagnosticsPeerRole.PARTICIPANT) {
                AutomatedDiagnosticsRunStartCause.AUTOMATIC_PARTICIPANT_JOIN
            } else {
                AutomatedDiagnosticsRunStartCause.MANUAL_START
            }
        }
        if (context.sharedRun == null && context.localRole == AutomatedDiagnosticsPeerRole.PARTICIPANT) {
            context.sharedRun = recentAcceptedRunAnnouncementOrNull(
                snapshot = initialSnapshot,
                expectedSenderPeerId = selectedPeer.identityKey,
                expectedLocalPeerId = localPeerId,
                expectedRemotePeerId = selectedPeer.identityKey,
                expectedSessionAssociationId = sessionAssociationId,
                context = context
            )?.also { announcement ->
                rememberAcceptedRunAnnouncement(
                    context = context,
                    announcement = announcement,
                    observationWallClockMillis = currentWallClockMillis(),
                    observationMonotonicMillis = clock.nowMillis(),
                    localPeerId = localPeerId,
                    remotePeerId = selectedPeer.identityKey
                )
            }?.sharedRun
        }

        if (context.sharedRun != null) {
            updateSharedRunState(
                role = context.localRole,
                sharedRun = requireNotNull(context.sharedRun)
            )
        }
        participantAutoJoinInvariantFailureOrNull(
            context = context,
            localPeerId = localPeerId,
            remotePeerId = selectedPeer.identityKey
        )?.let { blocker ->
            return completeStep(
                stepId = stepId,
                status = AutomatedDiagnosticStepStatus.FAIL,
                summary = "Remote coordination failed",
                blocker = blocker,
                evidence = remoteCoordinationEvidence(
                    snapshot = bindings.snapshot(),
                    context = context
                )
            )
        }

        val startedAt = stepResult(stepId).startedAtMillis ?: clock.nowMillis()
        setStepRunning(
            stepId = stepId,
            retryCount = stepResult(stepId).retryCount,
            summary = "Coordinating shared diagnostics run",
            startedAtMillis = startedAt
        )
        if (context.lastCoordinationTransition == null) {
            context.lastCoordinationTransition =
                AutomatedDiagnosticsCoordinationTransition.WAITING_FOR_ANNOUNCEMENT
        }
        var stableSince: Long? = null
        var lastAnnouncementRequestAtMillis: Long? = null
        var lastJoinRequestAtMillis: Long? = null

        while (currentCoroutineContext().isActive) {
            val now = clock.nowMillis()
            val nowWallClockMillis = currentWallClockMillis()
            val elapsed = now - startedAt
            val snapshot = bindings.snapshot()
            val liveSessionAssociationId =
                currentSharedRunSessionAssociationId(snapshot, selectedPeer.identityKey)
                    ?: return completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.BLOCKED,
                        summary = "Remote coordination blocked",
                        blocker = "Secure session changed before shared run coordination completed.",
                        evidence = remoteCoordinationEvidence(
                            snapshot = snapshot,
                            context = context
                        ),
                        startedAtMillis = startedAt
                    )
            if (liveSessionAssociationId != sessionAssociationId) {
                return completeStep(
                    stepId = stepId,
                    status = AutomatedDiagnosticStepStatus.BLOCKED,
                    summary = "Remote coordination blocked",
                    blocker = "Secure session association changed before shared run coordination completed.",
                    evidence = remoteCoordinationEvidence(
                        snapshot = snapshot,
                        context = context
                        ),
                        startedAtMillis = startedAt
                    )
            }

            val remoteAnnouncement = recentAcceptedRunAnnouncementOrNull(
                snapshot = snapshot,
                expectedSenderPeerId = selectedPeer.identityKey,
                expectedLocalPeerId = localPeerId,
                expectedRemotePeerId = selectedPeer.identityKey,
                expectedSessionAssociationId = sessionAssociationId,
                context = context
            )?.also { announcement ->
                rememberAcceptedRunAnnouncement(
                    context = context,
                    announcement = announcement,
                    observationWallClockMillis = nowWallClockMillis,
                    observationMonotonicMillis = now,
                    localPeerId = localPeerId,
                    remotePeerId = selectedPeer.identityKey
                )
            }

            if (
                context.localRole == AutomatedDiagnosticsPeerRole.COORDINATOR &&
                remoteAnnouncement != null
            ) {
                val activeSharedRun =
                    context.sharedRun ?: ensureLocalPendingSharedRun(
                        context = context,
                        coordinatorPeerId = localPeerId,
                        participantPeerId = selectedPeer.identityKey,
                        sessionAssociationId = sessionAssociationId
                    )
                if (remoteAnnouncement.sharedRun.runId != activeSharedRun.runId) {
                    when (
                        resolveSharedRunAuthority(
                            localSharedRun = activeSharedRun,
                            remoteAnnouncement = remoteAnnouncement,
                            context = context,
                            nowWallClockMillis = nowWallClockMillis
                        )
                    ) {
                        SharedRunAuthorityResolution.RETAIN_LOCAL_PROVISIONAL -> {
                            context.lastCoordinationTransition =
                                AutomatedDiagnosticsCoordinationTransition.LOCAL_AUTHORITY_RETAINED
                        }

                        SharedRunAuthorityResolution.ADOPT_REMOTE_PROVISIONAL -> {
                            context.localRoleBeforeConflict =
                                context.localRoleBeforeConflict ?: context.localRole
                            context.localRole = AutomatedDiagnosticsPeerRole.PARTICIPANT
                            context.sharedRun = remoteAnnouncement.sharedRun
                            context.localPendingSharedRun = null
                            context.participantJoined = false
                            context.participantJoinSent = false
                            context.remoteRunAdopted = true
                            context.provisionalRunAbandoned = true
                            context.runStartCause =
                                AutomatedDiagnosticsRunStartCause.REMOTE_PROVISIONAL_ADOPTED
                            context.lastCoordinationTransition =
                                AutomatedDiagnosticsCoordinationTransition.REMOTE_AUTHORITY_ADOPTED
                            updateSharedRunState(
                                role = context.localRole,
                                sharedRun = remoteAnnouncement.sharedRun,
                                context = context
                            )
                        }
                    }
                }
            }

            val activeRun = when (context.localRole) {
                AutomatedDiagnosticsPeerRole.COORDINATOR ->
                    context.sharedRun ?: ensureLocalPendingSharedRun(
                        context = context,
                        coordinatorPeerId = localPeerId,
                        participantPeerId = selectedPeer.identityKey,
                        sessionAssociationId = sessionAssociationId
                    )
                AutomatedDiagnosticsPeerRole.PARTICIPANT ->
                    context.sharedRun
                null -> return completeStep(
                    stepId = stepId,
                    status = AutomatedDiagnosticStepStatus.BLOCKED,
                    summary = "Remote coordination blocked",
                    blocker = "Automated diagnostics role is not available for shared run coordination.",
                    evidence = remoteCoordinationEvidence(
                        snapshot = snapshot,
                        context = context
                    ),
                    startedAtMillis = startedAt
                )
            }
            when (context.localRole) {
                AutomatedDiagnosticsPeerRole.COORDINATOR -> {
                    val coordinatorRun = activeRun ?: return completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.BLOCKED,
                        summary = "Remote coordination blocked",
                        blocker = "Coordinator run state is unavailable for announcement.",
                        evidence = remoteCoordinationEvidence(
                            snapshot = snapshot,
                            context = context
                        ),
                        startedAtMillis = startedAt
                    )
                    if (
                        lastAnnouncementRequestAtMillis == null ||
                        now - lastAnnouncementRequestAtMillis >= timingPolicy.pollIntervalMillis * 5L
                    ) {
                        val announcementRun = context.sharedRun ?: provisionAnnouncementLease(
                            sharedRun = coordinatorRun,
                            nowWallClockMillis = nowWallClockMillis
                        )
                        runAnnouncementSendCount += 1
                        val sendResult =
                            bindings.commands.requestAutomatedDiagnosticsRunAnnouncement(
                                announcementRun
                            )
                        if (
                            sendResult is AutomatedDiagnosticsRunAnnouncementSendResult.Sent &&
                            context.runAnnouncementSentMonotonicMillis == null
                        ) {
                            context.sharedRun = announcementRun
                            updateSharedRunState(
                                role = context.localRole,
                                sharedRun = announcementRun,
                                context = context
                            )
                            context.runAnnouncementSentAtMillis = nowWallClockMillis
                            context.runAnnouncementReceivedAtMillis = null
                            context.runAnnouncementSentMonotonicMillis = now
                            context.joinTimeoutStartedAtMillis = nowWallClockMillis
                            context.joinTimeoutStartedMonotonicMillis = now
                            context.lastCoordinationTransition =
                                AutomatedDiagnosticsCoordinationTransition.ANNOUNCEMENT_SENT
                        }
                        lastAnnouncementRequestAtMillis = now
                    }
                    val participantJoin = recentAcceptedParticipantJoinOrNull(
                        snapshot = bindings.snapshot(),
                        expectedRun = coordinatorRun,
                        expectedPeerId = selectedPeer.identityKey,
                        context = context
                    )
                    if (participantJoin != null) {
                        rememberAcceptedParticipantJoin(
                            context = context,
                            join = participantJoin,
                            observationWallClockMillis = nowWallClockMillis
                        )
                        context.participantJoined = true
                        context.sharedRun = refreshSharedRunActiveLease(
                            context = context,
                            remoteActiveLeaseExpiresAtMillis = participantJoin.sharedRun.expiresAtMillis,
                            nowWallClockMillis = nowWallClockMillis
                        )
                        context.lastCoordinationTransition =
                            AutomatedDiagnosticsCoordinationTransition.JOIN_ACCEPTED
                    }
                }

                AutomatedDiagnosticsPeerRole.PARTICIPANT -> {
                    if (context.sharedRun == null && context.runAnnouncementReceivedAtMillis != null) {
                        context.sharedRun = recentAcceptedRunAnnouncementOrNull(
                            snapshot = snapshot,
                            expectedSenderPeerId = selectedPeer.identityKey,
                            expectedLocalPeerId = localPeerId,
                            expectedRemotePeerId = selectedPeer.identityKey,
                            expectedSessionAssociationId = sessionAssociationId,
                            context = context
                        )?.sharedRun
                        context.sharedRun?.let { adoptedRun ->
                            updateSharedRunState(
                                role = context.localRole,
                                sharedRun = adoptedRun,
                                context = context
                            )
                        }
                    }
                    if (
                        context.joinTimeoutStartedAtMillis == null &&
                        context.runAnnouncementReceivedAtMillis != null
                    ) {
                        context.joinTimeoutStartedAtMillis = context.runAnnouncementReceivedAtMillis
                        context.joinTimeoutStartedMonotonicMillis =
                            context.runAnnouncementReceivedMonotonicMillis ?: now
                    }
                    if (
                        activeRun != null &&
                        !context.participantJoinSent &&
                        (
                            lastJoinRequestAtMillis == null ||
                                now - lastJoinRequestAtMillis >= timingPolicy.pollIntervalMillis * 5L
                            )
                    ) {
                        participantJoinSendCount += 1
                        val joinCreatedAtMillis = currentWallClockMillis()
                        val joinRunForSend = prepareSharedRunActiveLeaseForSend(
                            sharedRun = requireNotNull(activeRun),
                            nowWallClockMillis = joinCreatedAtMillis
                        )
                        context.lastParticipantJoinFrameCreatedAtMillis = joinCreatedAtMillis
                        context.lastParticipantJoinFrameExpiresAtMillis =
                            joinRunForSend.expiresAtMillis
                        context.lastParticipantJoinLeasePreparedBeforeSend =
                            joinRunForSend.expiresAtMillis >=
                                joinCreatedAtMillis + timingPolicy.sharedRunActiveLeaseMillis
                        val joinResult =
                            bindings.commands.requestAutomatedDiagnosticsParticipantJoin(
                                joinRunForSend
                            )
                        context.lastParticipantJoinResult =
                            automatedDiagnosticsParticipantJoinSendStatusText(joinResult)
                        if (joinResult is AutomatedDiagnosticsParticipantJoinSendResult.Sent) {
                            participantJoinSuccessfulSendCount += 1
                            context.sharedRun = joinRunForSend
                            updateSharedRunState(
                                role = context.localRole,
                                sharedRun = joinRunForSend,
                                context = context
                            )
                            context.participantJoinSent = true
                            context.participantJoined = true
                            context.participantJoinSentAtMillis = joinCreatedAtMillis
                            refreshSharedRunActiveLease(
                                context = context,
                                nowWallClockMillis = joinCreatedAtMillis
                            )
                            context.lastCoordinationTransition =
                                AutomatedDiagnosticsCoordinationTransition.JOIN_SENT
                        }
                        lastJoinRequestAtMillis = now
                    }
                }

                null -> Unit
            }

            if (context.participantJoined) {
                if (stableSince == null) {
                    stableSince = now
                }
                val stableElapsed = now - stableSince
                if (
                    stableElapsed >= timingPolicy.sharedRunCoordination.stabilizationMillis
                ) {
                    context.lastCoordinationTransition =
                        AutomatedDiagnosticsCoordinationTransition.SHARED_RUN_READY
                }
                updateStep(
                    stepId = stepId,
                    status = AutomatedDiagnosticStepStatus.RUNNING,
                    elapsedMillis = elapsed,
                    summary = "Coordinating shared diagnostics run",
                    blocker = null,
                    evidence = remoteCoordinationEvidence(
                        snapshot = snapshot,
                        context = context
                    ),
                    waitingProgressText = "Waiting ${formatAutomatedDiagnosticsDuration(elapsed)} / ${formatAutomatedDiagnosticsDuration(timingPolicy.sharedRunCoordination.timeoutMillis)}",
                    stabilizationProgressText = "Stable ${stableElapsed.coerceAtMost(timingPolicy.sharedRunCoordination.stabilizationMillis)} / ${timingPolicy.sharedRunCoordination.stabilizationMillis} ms"
                )
                if (stableElapsed >= timingPolicy.sharedRunCoordination.stabilizationMillis) {
                    return completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.PASS,
                        summary = "Shared diagnostics run confirmed",
                        evidence = remoteCoordinationEvidence(
                            snapshot = snapshot,
                            context = context
                        ),
                        startedAtMillis = startedAt
                    )
                }
            } else {
                stableSince = null
                val coordinationElapsed = coordinationElapsedMillis(
                    stepStartedAtMillis = startedAt,
                    context = context,
                    nowMonotonicMillis = now
                )
                updateStep(
                    stepId = stepId,
                    status = AutomatedDiagnosticStepStatus.RUNNING,
                    elapsedMillis = elapsed,
                    summary = "Coordinating shared diagnostics run",
                    blocker = if (context.localRole == AutomatedDiagnosticsPeerRole.COORDINATOR) {
                        "Waiting for the participant to join the shared diagnostics run."
                    } else if (context.runAnnouncementReceivedAtMillis == null) {
                        "Waiting for an incoming automated diagnostics run from the coordinator."
                    } else {
                        "Waiting for the shared diagnostics run join to be sent."
                    },
                    evidence = remoteCoordinationEvidence(
                        snapshot = snapshot,
                        context = context
                    ),
                    waitingProgressText = "Waiting ${formatAutomatedDiagnosticsDuration(coordinationElapsed)} / ${formatAutomatedDiagnosticsDuration(timingPolicy.sharedRunCoordination.timeoutMillis)}",
                    stabilizationProgressText = null
                )
            }

            if (
                coordinationElapsedMillis(
                    stepStartedAtMillis = startedAt,
                    context = context,
                    nowMonotonicMillis = now
                ) >= timingPolicy.sharedRunCoordination.timeoutMillis
            ) {
                break
            }
            delay.delayMillis(timingPolicy.pollIntervalMillis)
        }

        if (context.localRole == AutomatedDiagnosticsPeerRole.COORDINATOR) {
            val finalSnapshot = bindings.snapshot()
            val finalExpectedRun = requireNotNull(activeCoordinatorRunOrNull(context)) {
                "Coordinator shared run must be available before final participant-join validation."
            }
            val finalJoin = recentAcceptedParticipantJoinOrNull(
                snapshot = finalSnapshot,
                expectedRun = finalExpectedRun,
                expectedPeerId = selectedPeer.identityKey,
                context = context
            )
            if (finalJoin != null) {
                rememberAcceptedParticipantJoin(
                    context = context,
                    join = finalJoin,
                    observationWallClockMillis = currentWallClockMillis()
                )
                context.participantJoined = true
                context.sharedRun = refreshSharedRunActiveLease(
                    context = context,
                    remoteActiveLeaseExpiresAtMillis = finalJoin.sharedRun.expiresAtMillis,
                    nowWallClockMillis = currentWallClockMillis()
                )
                context.lastCoordinationTransition =
                    AutomatedDiagnosticsCoordinationTransition.JOIN_ACCEPTED
                return completeStep(
                    stepId = stepId,
                    status = AutomatedDiagnosticStepStatus.PASS,
                    summary = "Shared diagnostics run confirmed",
                    evidence = remoteCoordinationEvidence(
                        snapshot = bindings.snapshot(),
                        context = context
                    ),
                    startedAtMillis = startedAt
                )
            }
        }

        return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.FAIL,
            summary = "Remote coordination failed",
            blocker = if (context.localRole == AutomatedDiagnosticsPeerRole.COORDINATOR) {
                "Participant did not join shared diagnostics run within ${timingPolicy.sharedRunCoordination.timeoutMillis / 1_000L} seconds of the RUN_ANNOUNCE lease."
            } else {
                "Coordinator announcement did not reach a shared-run-ready state within ${timingPolicy.sharedRunCoordination.timeoutMillis / 1_000L} seconds."
            },
            evidence = remoteCoordinationEvidence(
                snapshot = bindings.snapshot(),
                context = context
            ),
            startedAtMillis = startedAt
        )
    }

    private suspend fun runBleStabilityStep(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext
    ): AutomatedDiagnosticStepStatus {
        val selectedPeer = context.selectedPeer ?: return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.BLOCKED,
            summary = "BLE stability blocked",
            blocker = "No discovered Aurora peer is available."
        )
        val before = bindings.snapshot()
        val status = awaitStableSnapshotStep(
            stepId = stepId,
            window = timingPolicy.bleFinalStability,
            summary = "Verifying BLE stability after secure setup",
            successSummary = "BLE state remained stable after secure setup",
            blockingReason = {
                bleStabilityBlocker(it, selectedPeer.identityKey)
            },
            successEvidence = { snapshot ->
                secureSessionEvidence(snapshot, selectedPeer.identityKey) + listOf(
                    AutomatedDiagnosticEvidenceValue("Advertiser", snapshot.bleAdvertiseStatus.name),
                    AutomatedDiagnosticEvidenceValue("Scanner", snapshot.bleScanStatus.name),
                    AutomatedDiagnosticEvidenceValue("Connection", snapshot.bleConnectionStatus.name)
                )
            },
            isSatisfied = { snapshot ->
                bleStabilityBlocker(snapshot, selectedPeer.identityKey) == null
            }
        )
        if (status == AutomatedDiagnosticStepStatus.PASS) {
            return status
        }
        val after = bindings.snapshot()
        return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.FAIL,
            summary = "BLE stability failed",
            blocker = bleStabilityBlocker(after, selectedPeer.identityKey)
                ?: "BLE state became unstable after secure setup.",
            evidence = listOf(
                AutomatedDiagnosticEvidenceValue("Advertiser before", before.bleAdvertiseStatus.name),
                AutomatedDiagnosticEvidenceValue("Advertiser after", after.bleAdvertiseStatus.name),
                AutomatedDiagnosticEvidenceValue("Scanner before", before.bleScanStatus.name),
                AutomatedDiagnosticEvidenceValue("Scanner after", after.bleScanStatus.name),
                AutomatedDiagnosticEvidenceValue("Connection before", before.bleConnectionStatus.name),
                AutomatedDiagnosticEvidenceValue("Connection after", after.bleConnectionStatus.name),
                AutomatedDiagnosticEvidenceValue(
                    "Lifecycle",
                    after.runtimeEvidence.activityLifecycleState.name
                )
            ),
            technicalDetails = after.runtimeEvidence.recentEvents.map { event ->
                "${event.category.name}: ${event.detail}"
            }
        )
    }

    private suspend fun runWifiDirectDiscoveryAndGroupStep(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext
    ): AutomatedDiagnosticStepStatus {
        val initialSnapshot = bindings.snapshot()
        recordInitialWifiDirectState(initialSnapshot, context)
        val initialReadinessRequirement = wifiDirectReadinessRequirement(initialSnapshot)
        if (initialReadinessRequirement != null) {
            return completeStep(
                stepId = stepId,
                status = AutomatedDiagnosticStepStatus.BLOCKED,
                summary = "Wi-Fi Direct discovery blocked",
                blocker = initialReadinessRequirement.message,
                evidence = wifiDirectGroupEvidence(
                    snapshot = initialSnapshot,
                    selectedPeer = context.selectedWifiDirectPeer,
                    context = context
                ),
                requiredAction = initialReadinessRequirement.requiredAction
            )
        }
        val localRole = context.localRole ?: return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.BLOCKED,
            summary = "Wi-Fi Direct discovery blocked",
            blocker = "Local diagnostics role is unavailable."
        )
        val sharedRun = context.sharedRun ?: return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.BLOCKED,
            summary = "Wi-Fi Direct discovery blocked",
            blocker = "Shared diagnostics run is unavailable."
        )
        val localPeerId = initialSnapshot.localPeerId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return completeStep(
                stepId = stepId,
                status = AutomatedDiagnosticStepStatus.BLOCKED,
                summary = "Wi-Fi Direct discovery blocked",
                blocker = "Local peer identity is unavailable."
            )
        val remotePeerId = otherSharedRunPeerId(sharedRun, localPeerId)
            ?: return completeStep(
                stepId = stepId,
                status = AutomatedDiagnosticStepStatus.BLOCKED,
                summary = "Wi-Fi Direct discovery blocked",
                blocker = "Shared diagnostics run does not include the local peer identity."
            )
        prepareWifiDirectFreshBaseline(stepId, context)?.let { status ->
            return status
        }
        bindings.commands.clearAutomatedDiagnosticsWifiDirectServiceDiscovery()
        bindings.commands.startWifiDirectDiscovery()
        val discoveryStartedAt = clock.nowMillis()
        setStepRunning(
            stepId = stepId,
            retryCount = stepResult(stepId).retryCount,
            summary = "Waiting for Wi-Fi Direct participant discovery",
            startedAtMillis = discoveryStartedAt
        )
        var lastPeerReadyRequestAtMillis: Long? = null
        try {
            while (currentCoroutineContext().isActive) {
                val now = clock.nowMillis()
                val elapsed = now - discoveryStartedAt
                val snapshot = bindings.snapshot()
                val requiredAction = wifiDirectReadinessRequiredAction(snapshot)
                val acceptedRemoteSignal = if (
                    localRole == AutomatedDiagnosticsPeerRole.COORDINATOR
                ) {
                    recentAcceptedAutomatedDiagnosticsWifiDirectPeerReadySignalOrNull(
                        snapshot = snapshot,
                        expectedRun = sharedRun,
                        expectedSenderPeerId = remotePeerId,
                        expectedRecipientPeerId = localPeerId,
                        context = context
                    )
                } else {
                    null
                }
                val matchingDnsSdResponses = matchingAutomatedDiagnosticsDnsSdResponses(
                    snapshot = snapshot,
                    correlationToken = acceptedRemoteSignal?.wifiDirectCorrelationToken
                )
                context.acceptedWifiDirectPeerReadySignal = acceptedRemoteSignal
                context.wifiDirectCurrentRunTokenProofReady = when (localRole) {
                    AutomatedDiagnosticsPeerRole.COORDINATOR -> acceptedRemoteSignal != null
                    AutomatedDiagnosticsPeerRole.PARTICIPANT ->
                        context.wifiDirectCorrelationToken != null &&
                            context.wifiDirectPeerReadySuccessfulSends > 0
                }
                context.wifiDirectCurrentRunDnsSdProofReady = when (localRole) {
                    AutomatedDiagnosticsPeerRole.COORDINATOR -> matchingDnsSdResponses.size == 1
                    AutomatedDiagnosticsPeerRole.PARTICIPANT ->
                        snapshot.wifiDirectRuntimeStatus.dnsSdDiagnostics.localServiceRegistered &&
                            context.wifiDirectDnsSdRegisteredCorrelationToken ==
                            context.wifiDirectCorrelationToken
                }

                if (localRole == AutomatedDiagnosticsPeerRole.PARTICIPANT) {
                    val correlationToken = context.wifiDirectCorrelationToken
                        ?: generateWifiDirectCorrelationToken().also { token ->
                            context.wifiDirectCorrelationToken = token
                        }
                    if (
                        !wifiDirectGroupReady(snapshot) &&
                        shouldRequestWifiDirectPeerReadySignal(
                            snapshot = snapshot,
                            context = context,
                            lastRequestAtMillis = lastPeerReadyRequestAtMillis,
                            now = now
                        )
                    ) {
                        context.wifiDirectPeerReadySendAttempts += 1
                        val sendResult =
                            bindings.commands.requestAutomatedDiagnosticsWifiDirectPeerReadySignal(
                                sharedRun,
                                remotePeerId,
                                correlationToken,
                                snapshot.wifiDirectRuntimeStatus.localDeviceInfo.deviceName
                            )
                        if (sendResult is AutomatedDiagnosticsWifiDirectPeerReadySendResult.Sent) {
                            context.wifiDirectPeerReadySuccessfulSends += 1
                        }
                        lastPeerReadyRequestAtMillis = now
                    }
                    if (
                        !wifiDirectGroupReady(snapshot) &&
                        !snapshot.wifiDirectRuntimeStatus.dnsSdDiagnostics.localServiceRegistered &&
                        (
                            context.lastWifiDirectDnsSdServiceRegistrationAtMillis == null ||
                                now - requireNotNull(
                                context.lastWifiDirectDnsSdServiceRegistrationAtMillis
                            ) >= timingPolicy.pollIntervalMillis * 5L
                            )
                    ) {
                        bindings.commands.registerAutomatedDiagnosticsWifiDirectService(
                            correlationToken,
                            snapshot.wifiDirectRuntimeStatus.localDeviceInfo.deviceName
                        )
                        context.wifiDirectDnsSdRegisteredCorrelationToken = correlationToken
                        context.lastWifiDirectDnsSdServiceRegistrationAtMillis = now
                    }
                }

                if (
                    localRole == AutomatedDiagnosticsPeerRole.COORDINATOR &&
                    !wifiDirectGroupReady(snapshot) &&
                    acceptedRemoteSignal != null &&
                    !snapshot.wifiDirectRuntimeStatus.dnsSdDiagnostics.discoveryStarted &&
                    (
                        context.lastWifiDirectDnsSdDiscoveryStartAtMillis == null ||
                            now - requireNotNull(
                            context.lastWifiDirectDnsSdDiscoveryStartAtMillis
                        ) >= timingPolicy.pollIntervalMillis * 5L
                        )
                ) {
                    bindings.commands.startAutomatedDiagnosticsWifiDirectServiceDiscovery()
                    context.lastWifiDirectDnsSdDiscoveryStartAtMillis = now
                }

                if (
                    localRole == AutomatedDiagnosticsPeerRole.COORDINATOR &&
                    !wifiDirectGroupReady(snapshot) &&
                    acceptedRemoteSignal != null &&
                    matchingDnsSdResponses.size == 1
                ) {
                    val matchedPeer = matchingDnsSdResponses.single().peer
                    context.selectedWifiDirectPeer = matchedPeer
                    context.selectedWifiDirectPeerSource = validatedDnsSdTokenPeerSource
                    context.wifiDirectConnectTarget = wifiDirectPeerEvidenceText(matchedPeer)
                }
                context.wifiDirectCurrentRunValidatedPeerProofReady =
                    localRole == AutomatedDiagnosticsPeerRole.COORDINATOR &&
                        context.selectedWifiDirectPeerSource == validatedDnsSdTokenPeerSource

                val blocker = wifiDirectPeerCorrelationBlocker(
                    snapshot = snapshot,
                    localRole = localRole,
                    context = context,
                    remotePeerId = remotePeerId,
                    acceptedRemoteSignal = acceptedRemoteSignal,
                    matchingDnsSdResponses = matchingDnsSdResponses
                )
                val evidence = wifiDirectGroupEvidence(
                    snapshot = snapshot,
                    selectedPeer = context.selectedWifiDirectPeer,
                    context = context
                )
                val discoverySatisfied = when (localRole) {
                    AutomatedDiagnosticsPeerRole.COORDINATOR ->
                        acceptedRemoteSignal != null &&
                            matchingDnsSdResponses.size == 1

                    AutomatedDiagnosticsPeerRole.PARTICIPANT ->
                        context.wifiDirectCurrentRunTokenProofReady &&
                            context.wifiDirectCurrentRunDnsSdProofReady
                }

                updateStep(
                    stepId = stepId,
                    status = AutomatedDiagnosticStepStatus.RUNNING,
                    startedAtMillis = discoveryStartedAt,
                    elapsedMillis = elapsed,
                    retryCount = stepResult(stepId).retryCount,
                    summary = "Waiting for Wi-Fi Direct participant discovery",
                    blocker = if (discoverySatisfied) null else blocker,
                    evidence = evidence,
                    waitingProgressText = "Waiting ${formatAutomatedDiagnosticsDuration(elapsed)} / ${formatAutomatedDiagnosticsDuration(timingPolicy.wifiDirectDiscovery.timeoutMillis)}",
                    stabilizationProgressText = null,
                    requiredAction = requiredAction
                )
                if (requiredAction != null) {
                    return completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.BLOCKED,
                        summary = "Wi-Fi Direct discovery blocked",
                        blocker = blocker ?: "Action required.",
                        evidence = evidence,
                        startedAtMillis = discoveryStartedAt,
                        requiredAction = requiredAction
                    )
                }
                if (discoverySatisfied) {
                    break
                }
                if (elapsed >= timingPolicy.wifiDirectDiscovery.timeoutMillis) {
                    return completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.BLOCKED,
                        summary = "Wi-Fi Direct discovery blocked",
                        blocker = blocker
                            ?: "Timed out after ${timingPolicy.wifiDirectDiscovery.timeoutMillis} ms.",
                        evidence = evidence,
                        startedAtMillis = discoveryStartedAt
                    )
                }
                delay.delayMillis(timingPolicy.pollIntervalMillis)
            }

            val groupStartedAt = clock.nowMillis()
            var stableSince: Long? = null
            while (currentCoroutineContext().isActive) {
                val now = clock.nowMillis()
                val totalElapsed = now - discoveryStartedAt
                val groupElapsed = now - groupStartedAt
                val snapshot = bindings.snapshot()
                val requiredAction = wifiDirectReadinessRequiredAction(snapshot)
                if (localRole == AutomatedDiagnosticsPeerRole.PARTICIPANT) {
                    val correlationToken = context.wifiDirectCorrelationToken
                        ?: generateWifiDirectCorrelationToken().also { token ->
                            context.wifiDirectCorrelationToken = token
                        }
                    if (
                        !wifiDirectGroupReady(snapshot) &&
                        shouldRequestWifiDirectPeerReadySignal(
                            snapshot = snapshot,
                            context = context,
                            lastRequestAtMillis = lastPeerReadyRequestAtMillis,
                            now = now
                        )
                    ) {
                        context.wifiDirectPeerReadySendAttempts += 1
                        val sendResult =
                            bindings.commands.requestAutomatedDiagnosticsWifiDirectPeerReadySignal(
                                sharedRun,
                                remotePeerId,
                                correlationToken,
                                snapshot.wifiDirectRuntimeStatus.localDeviceInfo.deviceName
                            )
                        if (sendResult is AutomatedDiagnosticsWifiDirectPeerReadySendResult.Sent) {
                            context.wifiDirectPeerReadySuccessfulSends += 1
                        }
                        lastPeerReadyRequestAtMillis = now
                    }
                }
                val acceptedRemoteSignal = if (
                    localRole == AutomatedDiagnosticsPeerRole.COORDINATOR
                ) {
                    recentAcceptedAutomatedDiagnosticsWifiDirectPeerReadySignalOrNull(
                        snapshot = snapshot,
                        expectedRun = sharedRun,
                        expectedSenderPeerId = remotePeerId,
                        expectedRecipientPeerId = localPeerId,
                        context = context
                    )
                } else {
                    null
                }
                val matchingDnsSdResponses = matchingAutomatedDiagnosticsDnsSdResponses(
                    snapshot = snapshot,
                    correlationToken = acceptedRemoteSignal?.wifiDirectCorrelationToken
                )
                context.acceptedWifiDirectPeerReadySignal = acceptedRemoteSignal
                context.wifiDirectCurrentRunTokenProofReady = when (localRole) {
                    AutomatedDiagnosticsPeerRole.COORDINATOR -> acceptedRemoteSignal != null
                    AutomatedDiagnosticsPeerRole.PARTICIPANT ->
                        context.wifiDirectCorrelationToken != null &&
                            context.wifiDirectPeerReadySuccessfulSends > 0
                }
                context.wifiDirectCurrentRunDnsSdProofReady = when (localRole) {
                    AutomatedDiagnosticsPeerRole.COORDINATOR -> matchingDnsSdResponses.size == 1
                    AutomatedDiagnosticsPeerRole.PARTICIPANT ->
                        snapshot.wifiDirectRuntimeStatus.dnsSdDiagnostics.localServiceRegistered &&
                            context.wifiDirectDnsSdRegisteredCorrelationToken ==
                            context.wifiDirectCorrelationToken
                }
                if (
                    localRole == AutomatedDiagnosticsPeerRole.COORDINATOR &&
                    !wifiDirectGroupReady(snapshot) &&
                    acceptedRemoteSignal != null &&
                    matchingDnsSdResponses.size == 1
                ) {
                    val matchedPeer = matchingDnsSdResponses.single().peer
                    context.selectedWifiDirectPeer = matchedPeer
                    context.selectedWifiDirectPeerSource = validatedDnsSdTokenPeerSource
                    context.wifiDirectConnectTarget = wifiDirectPeerEvidenceText(matchedPeer)
                }
                val visibleValidatedPeer = visibleWifiDirectPeerForSelectedTarget(
                    snapshot = snapshot,
                    selectedPeer = context.selectedWifiDirectPeer,
                    selectedPeerSource = context.selectedWifiDirectPeerSource
                )
                context.wifiDirectCurrentRunValidatedPeerProofReady =
                    localRole == AutomatedDiagnosticsPeerRole.COORDINATOR &&
                        visibleValidatedPeer != null &&
                        context.selectedWifiDirectPeerSource == validatedDnsSdTokenPeerSource
                if (
                    localRole == AutomatedDiagnosticsPeerRole.COORDINATOR &&
                    visibleValidatedPeer != null &&
                    shouldAttemptValidatedWifiDirectConnect(
                        snapshot = snapshot,
                        context = context,
                        targetPeer = visibleValidatedPeer
                    )
                ) {
                    context.wifiDirectConnectInvocationCount += 1
                    context.wifiDirectConnectTarget = wifiDirectPeerEvidenceText(visibleValidatedPeer)
                    context.wifiDirectCurrentRunConnectProofReady = true
                    bindings.commands.connectToWifiDirectPeer(
                        visibleValidatedPeer,
                        WifiDirectRolePreference.AUTOMATIC
                    )
                }
                val currentRunProofReady = wifiDirectCurrentRunProofReady(localRole, context)
                val blocker = if (wifiDirectGroupReady(snapshot) && !currentRunProofReady) {
                    wifiDirectCurrentRunProofBlocker(localRole, context)
                } else {
                    wifiDirectGroupFormationBlocker(
                        snapshot = snapshot,
                        localRole = localRole,
                        selectedPeer = context.selectedWifiDirectPeer,
                        selectedPeerSource = context.selectedWifiDirectPeerSource,
                        connectInvocationCount = context.wifiDirectConnectInvocationCount
                    )
                }
                val evidence = wifiDirectGroupEvidence(
                    snapshot = snapshot,
                    selectedPeer = context.selectedWifiDirectPeer,
                    context = context
                )

                if (wifiDirectGroupReady(snapshot) && currentRunProofReady) {
                    if (!context.wifiDirectGroupObservedAfterCurrentRunProof) {
                        context.wifiDirectGroupObservedAfterCurrentRunProof = true
                        context.wifiDirectGroupObservedAfterCurrentRunProofAtMillis = now
                    }
                    context.wifiDirectGroupProvenance =
                        AutomatedDiagnosticsWifiDirectGroupProvenance.CURRENT_RUN_VALIDATED
                    val provenancedEvidence = wifiDirectGroupEvidence(
                        snapshot = snapshot,
                        selectedPeer = context.selectedWifiDirectPeer,
                        context = context
                    )
                    if (stableSince == null) {
                        stableSince = now
                    }
                    val stableElapsed = now - stableSince
                    updateStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.RUNNING,
                        startedAtMillis = discoveryStartedAt,
                        elapsedMillis = totalElapsed,
                        retryCount = stepResult(stepId).retryCount,
                        summary = "Waiting for Wi-Fi Direct group formation",
                        blocker = null,
                        evidence = provenancedEvidence,
                        waitingProgressText = "Waiting ${formatAutomatedDiagnosticsDuration(groupElapsed)} / ${formatAutomatedDiagnosticsDuration(timingPolicy.wifiDirectGroupFormation.timeoutMillis)}",
                        stabilizationProgressText =
                        "Stable ${stableElapsed.coerceAtMost(timingPolicy.wifiDirectGroupFormation.stabilizationMillis)} / ${timingPolicy.wifiDirectGroupFormation.stabilizationMillis} ms"
                    )
                    if (stableElapsed >= timingPolicy.wifiDirectGroupFormation.stabilizationMillis) {
                        return completeStep(
                            stepId = stepId,
                            status = AutomatedDiagnosticStepStatus.PASS,
                            summary = "Wi-Fi Direct group is ready",
                            evidence = provenancedEvidence,
                            startedAtMillis = discoveryStartedAt
                        )
                    }
                } else {
                    stableSince = null
                    updateStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.RUNNING,
                        startedAtMillis = discoveryStartedAt,
                        elapsedMillis = totalElapsed,
                        retryCount = stepResult(stepId).retryCount,
                        summary = "Waiting for Wi-Fi Direct group formation",
                        blocker = blocker,
                        evidence = evidence,
                        waitingProgressText = "Waiting ${formatAutomatedDiagnosticsDuration(groupElapsed)} / ${formatAutomatedDiagnosticsDuration(timingPolicy.wifiDirectGroupFormation.timeoutMillis)}",
                        stabilizationProgressText = null,
                        requiredAction = requiredAction
                    )
                    if (requiredAction != null) {
                        return completeStep(
                            stepId = stepId,
                            status = AutomatedDiagnosticStepStatus.BLOCKED,
                            summary = "Wi-Fi Direct group formation blocked",
                            blocker = blocker ?: "Action required.",
                            evidence = evidence,
                            startedAtMillis = discoveryStartedAt,
                            requiredAction = requiredAction
                        )
                    }
                    if (groupElapsed >= timingPolicy.wifiDirectGroupFormation.timeoutMillis) {
                        return completeStep(
                            stepId = stepId,
                            status = AutomatedDiagnosticStepStatus.BLOCKED,
                            summary = "Wi-Fi Direct group formation blocked",
                            blocker = blocker
                                ?: "Timed out after ${timingPolicy.wifiDirectGroupFormation.timeoutMillis} ms.",
                            evidence = evidence,
                            startedAtMillis = discoveryStartedAt
                        )
                    }
                }
                delay.delayMillis(timingPolicy.pollIntervalMillis)
            }
            return completeStep(
                stepId = stepId,
                status = AutomatedDiagnosticStepStatus.CANCELLED,
                summary = "Cancelled",
                blocker = "Automated diagnostics were cancelled.",
                startedAtMillis = discoveryStartedAt
            )
        } finally {
            bindings.commands.clearAutomatedDiagnosticsWifiDirectServiceDiscovery()
        }
    }

    private suspend fun runWifiDirectSocketStep(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext
    ): AutomatedDiagnosticStepStatus {
        val sharedRun = context.sharedRun ?: return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.BLOCKED,
            summary = "Wi-Fi Direct socket blocked",
            blocker = "Shared diagnostics run is unavailable."
        )
        val initialSnapshot = bindings.snapshot()
        if (!wifiDirectGroupReady(initialSnapshot)) {
            return completeStep(
                stepId = stepId,
                status = AutomatedDiagnosticStepStatus.BLOCKED,
                summary = "Wi-Fi Direct socket blocked",
                blocker = "Wi-Fi Direct group is not ready."
            )
        }
        if (
            context.wifiDirectGroupProvenance !=
            AutomatedDiagnosticsWifiDirectGroupProvenance.CURRENT_RUN_VALIDATED
        ) {
            return completeStep(
                stepId = stepId,
                status = AutomatedDiagnosticStepStatus.BLOCKED,
                summary = "Wi-Fi Direct socket blocked",
                blocker = "Step 11 did not establish current-run Wi-Fi Direct group provenance.",
                evidence = wifiDirectSocketEvidence(
                    snapshot = initialSnapshot,
                    context = context,
                    minimumCreatedAtMillis = currentRunStartedAtMillis()
                )
            )
        }
        val localPeerId = initialSnapshot.localPeerId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return completeStep(
                stepId = stepId,
                status = AutomatedDiagnosticStepStatus.BLOCKED,
                summary = "Wi-Fi Direct socket blocked",
                blocker = "Local peer identity is unavailable."
            )
        val remotePeerId = otherSharedRunPeerId(sharedRun, localPeerId)
            ?: return completeStep(
                stepId = stepId,
                status = AutomatedDiagnosticStepStatus.BLOCKED,
                summary = "Wi-Fi Direct socket blocked",
                blocker = "Shared diagnostics run does not include the local peer identity."
            )
        val runStartedAtMillis = currentRunStartedAtMillis()

        repeat(timingPolicy.wifiDirectSocketConnection.maxRetries + 1) { attemptIndex ->
            val startedAt = clock.nowMillis()
            val summary = if (attemptIndex == 0) {
                "Waiting for Wi-Fi Direct socket readiness"
            } else {
                "Retry ${attemptIndex}/${timingPolicy.wifiDirectSocketConnection.maxRetries} - Wi-Fi Direct socket"
            }
            setStepRunning(
                stepId = stepId,
                retryCount = attemptIndex,
                summary = summary,
                startedAtMillis = startedAt
            )
            var stableSince: Long? = null
            var lastServerReadySignalRequestAtMillis: Long? = null
            while (currentCoroutineContext().isActive) {
                val now = clock.nowMillis()
                val elapsed = now - startedAt
                val snapshot = bindings.snapshot()
                if (!wifiDirectGroupReady(snapshot)) {
                    return completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.BLOCKED,
                        summary = summary,
                        blocker = wifiDirectGroupFormationBlocker(
                            snapshot = snapshot,
                            localRole = mutableState.value.localPeerRole
                                ?: AutomatedDiagnosticsPeerRole.COORDINATOR,
                            selectedPeer = context.selectedWifiDirectPeer,
                            selectedPeerSource = context.selectedWifiDirectPeerSource,
                            connectInvocationCount = context.wifiDirectConnectInvocationCount
                        ) ?: "Wi-Fi Direct group is not ready.",
                        evidence = wifiDirectSocketEvidence(
                            snapshot = snapshot,
                            context = context,
                            minimumCreatedAtMillis = runStartedAtMillis
                        ),
                        startedAtMillis = startedAt
                    )
                }

                when (snapshot.wifiDirectRuntimeStatus.connectionStatus.role) {
                    WifiDirectConnectionRole.GROUP_OWNER -> {
                        if (shouldStartWifiDirectSocketServer(snapshot)) {
                            context.serverStartRequestCount += 1
                            context.serverStartRequestAtMillis = now
                            context.serverStartRequestHost =
                                snapshot.wifiDirectRuntimeStatus.connectionStatus.groupOwnerAddress
                            bindings.commands.startWifiDirectSocketServer(
                                snapshot.wifiDirectRuntimeStatus.connectionStatus.groupOwnerAddress
                            )
                        }
                        val endpoint = snapshot.wifiDirectSocketDiagnostics.endpoint
                        val groupOwnerAddress =
                            snapshot.wifiDirectRuntimeStatus.connectionStatus.groupOwnerAddress
                                ?.trim()
                                ?.takeIf { it.isNotEmpty() }
                                ?: endpoint?.host?.trim()?.takeIf { it.isNotEmpty() }
                        val socketPort = endpoint?.port
                        val hasRecentServerReadySignal =
                            recentAcceptedAutomatedDiagnosticsServerReadySignalOrNull(
                                snapshot = snapshot,
                                expectedRun = sharedRun,
                                expectedOwnerPeerId = localPeerId,
                                expectedClientPeerId = remotePeerId,
                                minimumCreatedAtMillis = runStartedAtMillis,
                                requireGroupReady = true,
                                context = context
                            ) != null
                        if (
                            snapshot.wifiDirectSocketDiagnostics.state ==
                            WifiDirectSocketState.SERVER_LISTENING &&
                            groupOwnerAddress != null &&
                            socketPort != null &&
                            !hasRecentServerReadySignal &&
                            (
                                lastServerReadySignalRequestAtMillis == null ||
                                    now - lastServerReadySignalRequestAtMillis >=
                                    timingPolicy.pollIntervalMillis * 5L
                                )
                        ) {
                            val sendResult =
                                bindings.commands.requestAutomatedDiagnosticsServerReadySignal(
                                    sharedRun,
                                    remotePeerId,
                                    groupOwnerAddress,
                                    socketPort,
                                    snapshot.wifiDirectSocketDiagnostics.lastOperationToken
                                )
                            if (sendResult is AutomatedDiagnosticsServerReadySendResult.Sent) {
                                context.serverReadySentCount += 1
                            }
                            lastServerReadySignalRequestAtMillis = now
                        }
                    }

                    WifiDirectConnectionRole.CLIENT -> {
                        val serverReadySignal =
                            recentAcceptedAutomatedDiagnosticsServerReadySignalOrNull(
                            snapshot = snapshot,
                            expectedRun = sharedRun,
                            expectedOwnerPeerId = remotePeerId,
                            expectedClientPeerId = localPeerId,
                            minimumCreatedAtMillis = runStartedAtMillis,
                            requireGroupReady = true,
                            context = context
                        )
                        if (
                            serverReadySignal != null &&
                            shouldConnectWifiDirectSocketClient(snapshot)
                        ) {
                            context.clientConnectRequestCount += 1
                            context.clientConnectRequestAtMillis = now
                            context.clientConnectRequestHost = serverReadySignal.groupOwnerAddress
                            bindings.commands.connectWifiDirectSocketClient(
                                serverReadySignal.groupOwnerAddress
                            )
                        }
                    }

                    WifiDirectConnectionRole.UNKNOWN -> Unit
                }

                if (wifiDirectSocketReady(snapshot)) {
                    if (stableSince == null) {
                        stableSince = now
                    }
                    val stableElapsed = now - stableSince
                    updateStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.RUNNING,
                        elapsedMillis = elapsed,
                        retryCount = attemptIndex,
                        summary = summary,
                        blocker = null,
                        evidence = wifiDirectSocketEvidence(
                            snapshot = snapshot,
                            context = context,
                            minimumCreatedAtMillis = runStartedAtMillis
                        ),
                        waitingProgressText = "Waiting ${formatAutomatedDiagnosticsDuration(elapsed)} / ${formatAutomatedDiagnosticsDuration(timingPolicy.wifiDirectSocketConnection.timeoutMillis)}",
                        stabilizationProgressText = "Stable ${stableElapsed.coerceAtMost(timingPolicy.wifiDirectSocketConnection.stabilizationMillis)} / ${timingPolicy.wifiDirectSocketConnection.stabilizationMillis} ms"
                    )
                    if (stableElapsed >= timingPolicy.wifiDirectSocketConnection.stabilizationMillis) {
                        return completeStep(
                            stepId = stepId,
                            status = AutomatedDiagnosticStepStatus.PASS,
                            summary = "Wi-Fi Direct socket is ready",
                            evidence = wifiDirectSocketEvidence(
                                snapshot = snapshot,
                                context = context,
                                minimumCreatedAtMillis = runStartedAtMillis
                            ),
                            startedAtMillis = startedAt
                        )
                    }
                } else {
                    stableSince = null
                    updateStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.RUNNING,
                        elapsedMillis = elapsed,
                        retryCount = attemptIndex,
                        summary = summary,
                        blocker = wifiDirectSocketBlocker(
                            snapshot = snapshot,
                            context = context,
                            minimumCreatedAtMillis = runStartedAtMillis
                        ),
                        evidence = wifiDirectSocketEvidence(
                            snapshot = snapshot,
                            context = context,
                            minimumCreatedAtMillis = runStartedAtMillis
                        ),
                        waitingProgressText = "Waiting ${formatAutomatedDiagnosticsDuration(elapsed)} / ${formatAutomatedDiagnosticsDuration(timingPolicy.wifiDirectSocketConnection.timeoutMillis)}",
                        stabilizationProgressText = null
                    )
                }

                if (elapsed >= timingPolicy.wifiDirectSocketConnection.timeoutMillis) {
                    break
                }
                delay.delayMillis(timingPolicy.pollIntervalMillis)
            }
        }

        return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.FAIL,
            summary = "Wi-Fi Direct socket failed",
            blocker = wifiDirectSocketBlocker(
                snapshot = bindings.snapshot(),
                context = context,
                minimumCreatedAtMillis = runStartedAtMillis
            )
                ?: "Wi-Fi Direct socket did not become ready.",
            evidence = wifiDirectSocketEvidence(
                snapshot = bindings.snapshot(),
                context = context,
                minimumCreatedAtMillis = runStartedAtMillis
            )
        )
    }

    private suspend fun runWifiDirectBridgesStep(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext
    ): AutomatedDiagnosticStepStatus {
        val snapshot = bindings.snapshot()
        if (!wifiDirectSocketReady(snapshot)) {
            return completeStep(
                stepId = stepId,
                status = AutomatedDiagnosticStepStatus.BLOCKED,
                summary = "Wi-Fi Direct bridges blocked",
                blocker = wifiDirectSocketBlocker(snapshot)
                    ?: "Wi-Fi Direct socket is not ready."
            )
        }
        bindings.commands.setWifiDirectSendBridgeEnabled(true)
        bindings.commands.setWifiDirectReceiveBridgeEnabled(true)
        return awaitStableSnapshotStep(
            stepId = stepId,
            window = timingPolicy.wifiDirectBridgeEnable,
            summary = "Enabling Wi-Fi Direct bridges",
            successSummary = "Wi-Fi Direct bridges are enabled",
            blockingReason = {
                wifiDirectBridgeBlocker(it)
            },
            successEvidence = { currentSnapshot ->
                wifiDirectBridgeEvidence(currentSnapshot)
            },
            isSatisfied = { currentSnapshot ->
                wifiDirectBridgesReady(currentSnapshot)
            }
        )
    }

    private suspend fun runHybridBootstrapOfferStep(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext
    ): AutomatedDiagnosticStepStatus {
        val localRole = context.localRole ?: return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.BLOCKED,
            summary = "Hybrid bootstrap offer blocked",
            blocker = "Local diagnostics role is unavailable."
        )
        val runStartedAtMillis = currentRunStartedAtMillis()
        return if (localRole == AutomatedDiagnosticsPeerRole.COORDINATOR) {
            when (val result = bindings.commands.requestHybridBootstrapManualOffer()) {
                is HybridBootstrapManualOfferSendResult.Sent -> completeStep(
                    stepId = stepId,
                    status = AutomatedDiagnosticStepStatus.PASS,
                    summary = "Hybrid bootstrap offer sent over BLE control",
                    evidence = hybridBootstrapEvidence(bindings.snapshot()) + listOf(
                        AutomatedDiagnosticEvidenceValue("Offer peer", result.peerId),
                        AutomatedDiagnosticEvidenceValue("Offer session", result.sessionId)
                    )
                )
                HybridBootstrapManualOfferSendResult.NoActivePeer ->
                    completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.BLOCKED,
                        summary = "Hybrid bootstrap offer blocked",
                        blocker = "No active BLE peer is connected."
                    )
                HybridBootstrapManualOfferSendResult.NoActiveSession ->
                    completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.BLOCKED,
                        summary = "Hybrid bootstrap offer blocked",
                        blocker = "No active BLE secure session is available."
                    )
                HybridBootstrapManualOfferSendResult.WriterUnavailable ->
                    completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.FAIL,
                        summary = "Hybrid bootstrap offer failed",
                        blocker = "BLE writer unavailable."
                    )
                is HybridBootstrapManualOfferSendResult.InvalidOffer ->
                    completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.FAIL,
                        summary = "Hybrid bootstrap offer failed",
                        blocker = result.reason
                    )
                is HybridBootstrapManualOfferSendResult.SendFailed ->
                    completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.FAIL,
                        summary = "Hybrid bootstrap offer failed",
                        blocker = result.reason
                    )
            }
        } else {
            awaitStableSnapshotStep(
                stepId = stepId,
                window = timingPolicy.hybridControlDelivery,
                summary = "Waiting for hybrid bootstrap offer",
                successSummary = "Hybrid bootstrap offer recorded",
                blockingReason = {
                    hybridBootstrapOfferBlocker(it, runStartedAtMillis)
                },
                successEvidence = { snapshot ->
                    hybridBootstrapEvidence(snapshot)
                },
                isSatisfied = { snapshot ->
                    hasRecentHybridOffer(snapshot, runStartedAtMillis)
                }
            )
        }
    }

    private fun participantAutoJoinInvariantFailureOrNull(
        context: AutomatedDiagnosticsStepContext,
        localPeerId: String,
        remotePeerId: String
    ): String? {
        if (
            context.runStartCause !=
            AutomatedDiagnosticsRunStartCause.AUTOMATIC_PARTICIPANT_JOIN
        ) {
            return null
        }
        val sharedRun = context.sharedRun ?: return "Participant auto-join is missing the authoritative shared run."
        return when {
            context.localRole != AutomatedDiagnosticsPeerRole.PARTICIPANT ->
                "Participant auto-join changed local role to ${context.localRole ?: "none"}."
            sharedRun.coordinatorPeerId != remotePeerId ->
                "Participant auto-join coordinator peer mismatch: expected $remotePeerId observed ${sharedRun.coordinatorPeerId}."
            sharedRun.participantPeerId != localPeerId ->
                "Participant auto-join participant peer mismatch: expected $localPeerId observed ${sharedRun.participantPeerId}."
            context.localProvisionalRunId != null ->
                "Participant auto-join must not create a provisional run id."
            context.localSharedRunGenerationCount != 0 ->
                "Participant auto-join must not generate local shared run ids."
            runAnnouncementSendCount != 0 ->
                "Participant auto-join must not send RUN_ANNOUNCE."
            manualStartInvocationCount != 0 ->
                "Participant auto-join must not invoke public start()."
            else -> null
        }
    }

    private suspend fun runHybridBootstrapAcceptStep(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext
    ): AutomatedDiagnosticStepStatus {
        val localRole = context.localRole ?: return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.BLOCKED,
            summary = "Hybrid bootstrap accept blocked",
            blocker = "Local diagnostics role is unavailable."
        )
        val runStartedAtMillis = currentRunStartedAtMillis()
        return if (localRole == AutomatedDiagnosticsPeerRole.PARTICIPANT) {
            val readiness = awaitStableSnapshotStep(
                stepId = stepId,
                window = timingPolicy.hybridControlDelivery,
                summary = "Waiting for hybrid bootstrap accept readiness",
                successSummary = "Hybrid bootstrap accept is ready to send",
                blockingReason = {
                    it.hybridBootstrapManualAcceptBlockedReason
                        ?: "Waiting for a recent hybrid bootstrap offer."
                },
                successEvidence = { snapshot ->
                    hybridBootstrapEvidence(snapshot)
                },
                isSatisfied = { snapshot ->
                    snapshot.hybridBootstrapManualAcceptAvailable ||
                        hasRecentHybridAccept(snapshot, runStartedAtMillis)
                }
            )
            if (readiness != AutomatedDiagnosticStepStatus.PASS) {
                return readiness
            }
            if (hasRecentHybridAccept(bindings.snapshot(), runStartedAtMillis)) {
                return completeStep(
                    stepId = stepId,
                    status = AutomatedDiagnosticStepStatus.PASS,
                    summary = "Hybrid bootstrap accept already recorded",
                    evidence = hybridBootstrapEvidence(bindings.snapshot())
                )
            }
            when (val result = bindings.commands.requestHybridBootstrapManualAccept()) {
                is HybridBootstrapManualAcceptSendResult.Sent -> completeStep(
                    stepId = stepId,
                    status = AutomatedDiagnosticStepStatus.PASS,
                    summary = "Hybrid bootstrap accept sent over BLE control",
                    evidence = hybridBootstrapEvidence(bindings.snapshot()) + listOf(
                        AutomatedDiagnosticEvidenceValue("Accept peer", result.peerId),
                        AutomatedDiagnosticEvidenceValue("Accept session", result.sessionId)
                    )
                )
                HybridBootstrapManualAcceptSendResult.NoOfferCandidate ->
                    completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.BLOCKED,
                        summary = "Hybrid bootstrap accept blocked",
                        blocker = "No recent hybrid bootstrap offer candidate is available."
                    )
                HybridBootstrapManualAcceptSendResult.NoActivePeer ->
                    completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.BLOCKED,
                        summary = "Hybrid bootstrap accept blocked",
                        blocker = "No active BLE peer is connected."
                    )
                HybridBootstrapManualAcceptSendResult.NoActiveSession ->
                    completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.BLOCKED,
                        summary = "Hybrid bootstrap accept blocked",
                        blocker = "No active BLE secure session is available."
                    )
                HybridBootstrapManualAcceptSendResult.WriterUnavailable ->
                    completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.FAIL,
                        summary = "Hybrid bootstrap accept failed",
                        blocker = "BLE writer unavailable."
                    )
                is HybridBootstrapManualAcceptSendResult.InvalidAccept ->
                    completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.FAIL,
                        summary = "Hybrid bootstrap accept failed",
                        blocker = result.reason
                    )
                is HybridBootstrapManualAcceptSendResult.SendFailed ->
                    completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.FAIL,
                        summary = "Hybrid bootstrap accept failed",
                        blocker = result.reason
                    )
            }
        } else {
            awaitStableSnapshotStep(
                stepId = stepId,
                window = timingPolicy.hybridControlDelivery,
                summary = "Waiting for hybrid bootstrap accept",
                successSummary = "Hybrid bootstrap accept recorded",
                blockingReason = {
                    hybridBootstrapAcceptBlocker(it, runStartedAtMillis)
                },
                successEvidence = { snapshot ->
                    hybridBootstrapEvidence(snapshot)
                },
                isSatisfied = { snapshot ->
                    hasRecentHybridAccept(snapshot, runStartedAtMillis)
                }
            )
        }
    }

    private suspend fun runHybridBootstrapSocketHintStep(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext
    ): AutomatedDiagnosticStepStatus {
        val snapshot = bindings.snapshot()
        if (!wifiDirectSocketReady(snapshot)) {
            return completeStep(
                stepId = stepId,
                status = AutomatedDiagnosticStepStatus.BLOCKED,
                summary = "Hybrid bootstrap socket hint blocked",
                blocker = wifiDirectSocketBlocker(snapshot)
                    ?: "Wi-Fi Direct socket is not ready."
            )
        }
        val runStartedAtMillis = currentRunStartedAtMillis()
        return when (snapshot.wifiDirectRuntimeStatus.connectionStatus.role) {
            WifiDirectConnectionRole.GROUP_OWNER -> {
                when (val result = bindings.commands.requestHybridBootstrapManualSocketHint()) {
                    is HybridBootstrapManualSocketHintSendResult.Sent -> completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.PASS,
                        summary = "Hybrid bootstrap socket hint sent over BLE control",
                        evidence = hybridBootstrapEvidence(bindings.snapshot()) + listOf(
                            AutomatedDiagnosticEvidenceValue("Hint peer", result.peerId),
                            AutomatedDiagnosticEvidenceValue("Hint session", result.sessionId),
                            AutomatedDiagnosticEvidenceValue("Hint address", result.groupOwnerAddress),
                            AutomatedDiagnosticEvidenceValue("Hint port", result.socketPort.toString())
                        )
                    )
                    HybridBootstrapManualSocketHintSendResult.NoActivePeer ->
                        completeStep(
                            stepId = stepId,
                            status = AutomatedDiagnosticStepStatus.BLOCKED,
                            summary = "Hybrid bootstrap socket hint blocked",
                            blocker = "No active BLE peer is connected."
                        )
                    HybridBootstrapManualSocketHintSendResult.NoActiveSession ->
                        completeStep(
                            stepId = stepId,
                            status = AutomatedDiagnosticStepStatus.BLOCKED,
                            summary = "Hybrid bootstrap socket hint blocked",
                            blocker = "No active BLE secure session is available."
                        )
                    HybridBootstrapManualSocketHintSendResult.NoAcceptedCandidate ->
                        completeStep(
                            stepId = stepId,
                            status = AutomatedDiagnosticStepStatus.BLOCKED,
                            summary = "Hybrid bootstrap socket hint blocked",
                            blocker = "No accepted hybrid bootstrap candidate is available."
                        )
                    HybridBootstrapManualSocketHintSendResult.NoSocketEndpoint ->
                        completeStep(
                            stepId = stepId,
                            status = AutomatedDiagnosticStepStatus.BLOCKED,
                            summary = "Hybrid bootstrap socket hint blocked",
                            blocker = "No Wi-Fi Direct group-owner endpoint is available."
                        )
                    HybridBootstrapManualSocketHintSendResult.NotGroupOwner ->
                        completeStep(
                            stepId = stepId,
                            status = AutomatedDiagnosticStepStatus.BLOCKED,
                            summary = "Hybrid bootstrap socket hint blocked",
                            blocker = "This device is not the Wi-Fi Direct group owner."
                        )
                    HybridBootstrapManualSocketHintSendResult.WriterUnavailable ->
                        completeStep(
                            stepId = stepId,
                            status = AutomatedDiagnosticStepStatus.FAIL,
                            summary = "Hybrid bootstrap socket hint failed",
                            blocker = "BLE writer unavailable."
                        )
                    is HybridBootstrapManualSocketHintSendResult.InvalidSocketHint ->
                        completeStep(
                            stepId = stepId,
                            status = AutomatedDiagnosticStepStatus.FAIL,
                            summary = "Hybrid bootstrap socket hint failed",
                            blocker = result.reason
                        )
                    is HybridBootstrapManualSocketHintSendResult.SendFailed ->
                        completeStep(
                            stepId = stepId,
                            status = AutomatedDiagnosticStepStatus.FAIL,
                            summary = "Hybrid bootstrap socket hint failed",
                            blocker = result.reason
                        )
                }
            }
            WifiDirectConnectionRole.CLIENT -> awaitStableSnapshotStep(
                stepId = stepId,
                window = timingPolicy.hybridSocketHintDelivery,
                summary = "Waiting for hybrid bootstrap socket hint",
                successSummary = "Hybrid bootstrap socket hint recorded",
                blockingReason = {
                    hybridBootstrapSocketHintBlocker(it, runStartedAtMillis)
                },
                successEvidence = { currentSnapshot ->
                    hybridBootstrapEvidence(currentSnapshot)
                },
                isSatisfied = { currentSnapshot ->
                    hasRecentSocketReadyHybridCandidate(currentSnapshot, runStartedAtMillis)
                }
            )
            WifiDirectConnectionRole.UNKNOWN -> completeStep(
                stepId = stepId,
                status = AutomatedDiagnosticStepStatus.BLOCKED,
                summary = "Hybrid bootstrap socket hint blocked",
                blocker = "Wi-Fi Direct connection role is unknown."
            )
        }
    }

    private suspend fun runHybridBootstrapTriggerStep(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext
    ): AutomatedDiagnosticStepStatus {
        val snapshot = bindings.snapshot()
        return when (snapshot.wifiDirectRuntimeStatus.connectionStatus.role) {
            WifiDirectConnectionRole.GROUP_OWNER -> completeStep(
                stepId = stepId,
                status = AutomatedDiagnosticStepStatus.PASS,
                summary = "Hybrid bootstrap endpoint is ready on the Wi-Fi Direct group owner",
                evidence = hybridBootstrapEvidence(snapshot) + wifiDirectSocketEvidence(snapshot),
                technicalDetails = listOf(
                    "The JavaNet hybrid dial is executed from the Wi-Fi Direct client side in this phase."
                )
            )
            WifiDirectConnectionRole.CLIENT -> {
                val readiness = awaitStableSnapshotStep(
                    stepId = stepId,
                    window = timingPolicy.hybridBootstrapTrigger,
                    summary = "Waiting for hybrid bootstrap trigger readiness",
                    successSummary = "Hybrid bootstrap command is ready",
                    blockingReason = {
                        it.hybridBootstrapManualTriggerSnapshot.triggerStatusText
                            ?: it.hybridBootstrapManualTriggerSnapshot.commandStatusText
                    },
                    successEvidence = { currentSnapshot ->
                        hybridBootstrapEvidence(currentSnapshot)
                    },
                    isSatisfied = { currentSnapshot ->
                        currentSnapshot.hybridBootstrapManualTriggerSnapshot.canTriggerNow
                    }
                )
                if (readiness != AutomatedDiagnosticStepStatus.PASS) {
                    return readiness
                }
                when (val result = bindings.commands.requestHybridBootstrapManualTrigger()) {
                    is HybridBootstrapCommandTriggerResult.Executed -> {
                        when (val executionResult = result.executionResult) {
                            is HybridBootstrapCommandExecutionResult.Accepted -> completeStep(
                                stepId = stepId,
                                status = AutomatedDiagnosticStepStatus.PASS,
                                summary = "Hybrid bootstrap JavaNet trigger succeeded",
                                evidence = hybridBootstrapEvidence(bindings.snapshot()) + listOf(
                                    AutomatedDiagnosticEvidenceValue("Dial peer", executionResult.peerId),
                                    AutomatedDiagnosticEvidenceValue("Dial session", executionResult.sessionId),
                                    AutomatedDiagnosticEvidenceValue(
                                        "Dial address",
                                        executionResult.groupOwnerAddress
                                    ),
                                    AutomatedDiagnosticEvidenceValue(
                                        "Dial port",
                                        executionResult.socketPort.toString()
                                    )
                                )
                            )
                            is HybridBootstrapCommandExecutionResult.Rejected -> completeStep(
                                stepId = stepId,
                                status = AutomatedDiagnosticStepStatus.FAIL,
                                summary = "Hybrid bootstrap trigger failed",
                                blocker = executionResult.reason,
                                evidence = hybridBootstrapEvidence(bindings.snapshot())
                            )
                        }
                    }
                    HybridBootstrapCommandTriggerResult.NoCandidates -> completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.BLOCKED,
                        summary = "Hybrid bootstrap trigger blocked",
                        blocker = "No hybrid bootstrap candidates are available."
                    )
                    HybridBootstrapCommandTriggerResult.NoSocketReadyCandidate -> completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.BLOCKED,
                        summary = "Hybrid bootstrap trigger blocked",
                        blocker = "No socket-ready hybrid candidate is available."
                    )
                    is HybridBootstrapCommandTriggerResult.InvalidEndpoint -> completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.BLOCKED,
                        summary = "Hybrid bootstrap trigger blocked",
                        blocker = result.reason
                    )
                    is HybridBootstrapCommandTriggerResult.EndpointTooOld -> completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.BLOCKED,
                        summary = "Hybrid bootstrap trigger blocked",
                        blocker = "Hybrid socket hint is stale: ${result.ageMillis} ms old, max ${result.maxAgeMillis} ms."
                    )
                    is HybridBootstrapCommandTriggerResult.NotAllowed -> completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.BLOCKED,
                        summary = "Hybrid bootstrap trigger blocked",
                        blocker = result.reason
                    )
                }
            }
            WifiDirectConnectionRole.UNKNOWN -> completeStep(
                stepId = stepId,
                status = AutomatedDiagnosticStepStatus.BLOCKED,
                summary = "Hybrid bootstrap trigger blocked",
                blocker = "Wi-Fi Direct connection role is unknown."
            )
        }
    }

    private suspend fun awaitStableSnapshotStep(
        stepId: AutomatedDiagnosticStepId,
        window: AutomatedDiagnosticsTimingWindow,
        summary: String,
        successSummary: String,
        retryCount: Int = 0,
        blockingReason: (AutomatedDiagnosticsRuntimeSnapshot) -> String?,
        requiredActionForSnapshot: (AutomatedDiagnosticsRuntimeSnapshot) -> AutomatedDiagnosticsRequiredAction? =
            { null },
        blockImmediatelyWhenActionRequired: Boolean = false,
        successEvidence: (AutomatedDiagnosticsRuntimeSnapshot) -> List<AutomatedDiagnosticEvidenceValue>,
        isSatisfied: (AutomatedDiagnosticsRuntimeSnapshot) -> Boolean
    ): AutomatedDiagnosticStepStatus {
        val startedAt = clock.nowMillis()
        var stableSince: Long? = null
        setStepRunning(
            stepId = stepId,
            retryCount = retryCount,
            summary = summary,
            startedAtMillis = startedAt
        )
        while (currentCoroutineContext().isActive) {
            val now = clock.nowMillis()
            val snapshot = bindings.snapshot()
            val elapsed = now - startedAt
            val conditionSatisfied = isSatisfied(snapshot)
            val requiredAction = requiredActionForSnapshot(snapshot)
            if (conditionSatisfied) {
                if (stableSince == null) {
                    stableSince = now
                }
                val stableElapsed = now - stableSince
                updateStep(
                    stepId = stepId,
                    status = AutomatedDiagnosticStepStatus.RUNNING,
                    elapsedMillis = elapsed,
                    retryCount = retryCount,
                    summary = summary,
                    waitingProgressText = "Waiting ${formatAutomatedDiagnosticsDuration(elapsed)} / ${formatAutomatedDiagnosticsDuration(window.timeoutMillis)}",
                    stabilizationProgressText = if (window.stabilizationMillis > 0L) {
                        "Stable ${stableElapsed.coerceAtMost(window.stabilizationMillis)} / ${window.stabilizationMillis} ms"
                    } else {
                        null
                    },
                    requiredAction = null
                )
                if (stableElapsed >= window.stabilizationMillis) {
                    return completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.PASS,
                        summary = successSummary,
                        evidence = successEvidence(snapshot),
                        startedAtMillis = startedAt
                    )
                }
            } else {
                stableSince = null
                updateStep(
                    stepId = stepId,
                    status = AutomatedDiagnosticStepStatus.RUNNING,
                    elapsedMillis = elapsed,
                    retryCount = retryCount,
                    summary = summary,
                    blocker = blockingReason(snapshot),
                    evidence = successEvidence(snapshot),
                    waitingProgressText = "Waiting ${formatAutomatedDiagnosticsDuration(elapsed)} / ${formatAutomatedDiagnosticsDuration(window.timeoutMillis)}",
                    stabilizationProgressText = null,
                    requiredAction = requiredAction
                )
                if (blockImmediatelyWhenActionRequired && requiredAction != null) {
                    return completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.BLOCKED,
                        summary = summary,
                        blocker = blockingReason(snapshot) ?: "Action required.",
                        evidence = successEvidence(snapshot),
                        startedAtMillis = startedAt,
                        requiredAction = requiredAction
                    )
                }
            }
            if (elapsed >= window.timeoutMillis) {
                return completeStep(
                    stepId = stepId,
                    status = AutomatedDiagnosticStepStatus.BLOCKED,
                    summary = summary,
                    blocker = blockingReason(snapshot) ?: "Timed out after ${window.timeoutMillis} ms.",
                    evidence = successEvidence(snapshot),
                    startedAtMillis = startedAt,
                    requiredAction = requiredAction
                )
            }
            delay.delayMillis(timingPolicy.pollIntervalMillis)
        }
        return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.CANCELLED,
            summary = "Cancelled",
            blocker = "Automated diagnostics were cancelled.",
            startedAtMillis = startedAt
        )
    }

    private fun preflightBlocker(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot
    ): String? {
        return preflightRequirement(snapshot)?.message
    }

    private fun preflightRequiredAction(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot
    ): AutomatedDiagnosticsRequiredAction? {
        return preflightRequirement(snapshot)?.requiredAction
    }

    private fun preflightRequirement(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot
    ): AutomatedDiagnosticsBlocker? {
        return when {
            !snapshot.bluetoothPermissionStatus.allRequiredGranted ->
                AutomatedDiagnosticsBlocker(
                    message = "Grant Bluetooth and Location permissions, then retry.",
                    requiredAction = AutomatedDiagnosticsRequiredAction(
                        kind = AutomatedDiagnosticsRequiredActionKind.REQUEST_BLUETOOTH_PERMISSIONS,
                        title = "Bluetooth permissions required",
                        buttonLabel = "Grant Bluetooth permissions"
                    )
                )
            snapshot.bluetoothPermissionStatus.isBluetoothEnabled != true ->
                AutomatedDiagnosticsBlocker(
                    message = "Enable Bluetooth, then retry.",
                    requiredAction = AutomatedDiagnosticsRequiredAction(
                        kind = AutomatedDiagnosticsRequiredActionKind.OPEN_BLUETOOTH_SETTINGS,
                        title = "Bluetooth is disabled",
                        buttonLabel = "Open Bluetooth settings"
                    )
                )
            snapshot.bluetoothPermissionStatus.isLocationEnabled != true ->
                AutomatedDiagnosticsBlocker(
                    message = "Enable Location/GPS, then retry.",
                    requiredAction = AutomatedDiagnosticsRequiredAction(
                        kind = AutomatedDiagnosticsRequiredActionKind.OPEN_LOCATION_SETTINGS,
                        title = "Location/GPS is disabled",
                        buttonLabel = "Open Location settings"
                    )
                )
            snapshot.desiredAvailability != AuroraAvailabilityPreference.ONLINE ->
                AutomatedDiagnosticsBlocker("Set availability to Online, then retry.")
            !snapshot.hybridBootstrapJavaNetRuntimeEnabled ->
                AutomatedDiagnosticsBlocker("JavaNet runtime is disabled.")
            !snapshot.runtimeEvidence.bleRuntimeHosted ->
                AutomatedDiagnosticsBlocker("BLE runtime is not active yet.")
            else -> null
        }
    }

    private fun bleRuntimeBlocker(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot
    ): String? {
        return when {
            !snapshot.runtimeEvidence.bleRuntimeHosted ->
                "BLE runtime is not active."
            snapshot.bleAdvertiseStatus != BleAdvertiseStatus.ADVERTISING ->
                "Advertiser is ${snapshot.bleAdvertiseStatus.name}."
            snapshot.bleScanStatus != BleScanStatus.SCANNING ->
                "Scanner is ${snapshot.bleScanStatus.name}."
            else -> null
        }
    }

    private fun securePeerSelectionBlocker(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        targetPeerId: String
    ): String? {
        return when {
            snapshot.selectedSecurePeerId != targetPeerId ->
                "Selected peer is ${snapshot.selectedSecurePeerId ?: "none"}."
            snapshot.activeTransportPeerId != targetPeerId ->
                "Active peer is ${snapshot.activeTransportPeerId ?: "none"}."
            else -> null
        }
    }

    private fun secureSessionBlocker(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        targetPeerId: String
    ): String? {
        val contact = snapshot.contacts.firstOrNull { it.canonicalPeerId == targetPeerId }
        val identity = snapshot.privateChatIdentitiesByPeerId[targetPeerId]
        return when {
            contact == null -> "Contact is missing."
            snapshot.selectedSecurePeerId != targetPeerId -> "Selected peer is missing."
            snapshot.activeTransportPeerId != targetPeerId -> "Active peer is missing."
            !snapshot.peerSessionDiagnostics.hasSessionForPeer(targetPeerId) ->
                "Secure session is missing."
            identity == null -> "Private chat identity is missing."
            identity.privateChatId.isNullOrBlank() -> "Private chat id is missing."
            !identity.isEstablished -> "Private chat proposals are incomplete."
            else -> null
        }
    }

    private fun bleStabilityBlocker(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        targetPeerId: String
    ): String? {
        return bleRuntimeBlocker(snapshot)
            ?: when {
                snapshot.bleConnectionStatus != BleConnectionStatus.CONNECTED ->
                    "BLE connection is ${snapshot.bleConnectionStatus.name}."
                snapshot.activeTransportPeerId != targetPeerId ->
                    "Active peer changed to ${snapshot.activeTransportPeerId ?: "none"}."
                secureSessionBlocker(snapshot, targetPeerId) != null ->
                    secureSessionBlocker(snapshot, targetPeerId)
                else -> null
            }
    }

    private fun wifiDirectReadinessBlocker(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot
    ): String? {
        return wifiDirectReadinessRequirement(snapshot)?.message
    }

    private fun wifiDirectReadinessRequiredAction(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot
    ): AutomatedDiagnosticsRequiredAction? {
        return wifiDirectReadinessRequirement(snapshot)?.requiredAction
    }

    private fun wifiDirectReadinessRequirement(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot
    ): AutomatedDiagnosticsBlocker? {
        val runtimeStatus = snapshot.wifiDirectRuntimeStatus
        val permissionStatus = runtimeStatus.permissionStatus
        return when {
            !permissionStatus.isWifiDirectSupported ->
                AutomatedDiagnosticsBlocker("Wi-Fi Direct is unsupported on this device.")
            !permissionStatus.allRequiredGranted -> {
                val missingLabels = permissionStatus.missingPermissionLabels
                AutomatedDiagnosticsBlocker(
                    message = if (missingLabels.isEmpty()) {
                        "Wi-Fi Direct permission missing."
                    } else {
                        "Grant Wi-Fi Direct permission: ${missingLabels.joinToString(separator = ", ")}."
                    },
                    requiredAction = AutomatedDiagnosticsRequiredAction(
                        kind = AutomatedDiagnosticsRequiredActionKind.REQUEST_WIFI_DIRECT_PERMISSIONS,
                        title = "Wi-Fi Direct permission required",
                        buttonLabel = "Grant Wi-Fi Direct permission"
                    )
                )
            }
            permissionStatus.enabledState == WifiDirectEnabledState.DISABLED ->
                AutomatedDiagnosticsBlocker(
                    message = "Enable Wi-Fi before continuing.",
                    requiredAction = AutomatedDiagnosticsRequiredAction(
                        kind = AutomatedDiagnosticsRequiredActionKind.OPEN_WIFI_SETTINGS,
                        title = "Wi-Fi is disabled",
                        buttonLabel = "Open Wi-Fi settings"
                    )
                )
            permissionStatus.enabledState == WifiDirectEnabledState.UNKNOWN ->
                AutomatedDiagnosticsBlocker("Wi-Fi Direct enabled state is unknown.")
            else -> runtimeStatus.lastError?.trim()?.takeIf { it.isNotEmpty() }
                ?.let(::AutomatedDiagnosticsBlocker)
        }
    }

    private fun wifiDirectGroupReady(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot
    ): Boolean {
        val connectionStatus = snapshot.wifiDirectRuntimeStatus.connectionStatus
        return connectionStatus.state == WifiDirectConnectionState.CONNECTED &&
            connectionStatus.groupFormed == WifiDirectGroupFormedState.YES &&
            connectionStatus.role != WifiDirectConnectionRole.UNKNOWN &&
            !connectionStatus.groupOwnerAddress.isNullOrBlank()
    }

    private fun hasPreExistingWifiDirectGroupOrConnection(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot
    ): Boolean {
        val connectionStatus = snapshot.wifiDirectRuntimeStatus.connectionStatus
        return connectionStatus.state == WifiDirectConnectionState.CONNECTED ||
            connectionStatus.state == WifiDirectConnectionState.CONNECTING ||
            connectionStatus.state == WifiDirectConnectionState.DISCONNECTING ||
            connectionStatus.groupFormed == WifiDirectGroupFormedState.YES
    }

    private fun hasPreExistingWifiDirectSocketState(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot
    ): Boolean {
        val diagnostics = snapshot.wifiDirectSocketDiagnostics
        return diagnostics.state != WifiDirectSocketState.IDLE ||
            diagnostics.isConnected ||
            diagnostics.isReadLoopActive ||
            diagnostics.lastCommand != gr.hua.aurora.wifidirect.socket.WifiDirectSocketCommand.NONE ||
            diagnostics.lastCommandResult !=
            gr.hua.aurora.wifidirect.socket.WifiDirectSocketCommandResult.NONE ||
            diagnostics.lastCommandError != null ||
            diagnostics.lastError != null
    }

    private fun hasAutomatedDiagnosticsDnsSdState(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot
    ): Boolean {
        val diagnostics = snapshot.wifiDirectRuntimeStatus.dnsSdDiagnostics
        return diagnostics.localServiceRegistered ||
            diagnostics.serviceRequestRegistered ||
            diagnostics.discoveryStarted ||
            diagnostics.discoveredServices.isNotEmpty() ||
            diagnostics.lastError != null
    }

    private fun recordInitialWifiDirectState(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        context: AutomatedDiagnosticsStepContext
    ) {
        val connectionStatus = snapshot.wifiDirectRuntimeStatus.connectionStatus
        if (context.initialWifiDirectConnectionState == null) {
            context.initialWifiDirectConnectionState = connectionStatus.state
        }
        if (context.initialWifiDirectGroupFormed == null) {
            context.initialWifiDirectGroupFormed = connectionStatus.groupFormed
        }
        if (context.initialWifiDirectRole == null) {
            context.initialWifiDirectRole = connectionStatus.role
        }
        if (context.initialWifiDirectSocketState == null) {
            context.initialWifiDirectSocketState = snapshot.wifiDirectSocketDiagnostics.state
        }
        val hadPreExistingGroup = hasPreExistingWifiDirectGroupOrConnection(snapshot)
        val hadPreExistingSocket = hasPreExistingWifiDirectSocketState(snapshot)
        context.preExistingWifiDirectGroupDetected =
            context.preExistingWifiDirectGroupDetected || hadPreExistingGroup
        context.preExistingWifiDirectSocketDetected =
            context.preExistingWifiDirectSocketDetected || hadPreExistingSocket
        if (
            (hadPreExistingGroup || hadPreExistingSocket) &&
            context.wifiDirectGroupProvenance !=
            AutomatedDiagnosticsWifiDirectGroupProvenance.CURRENT_RUN_VALIDATED
        ) {
            context.wifiDirectGroupProvenance =
                AutomatedDiagnosticsWifiDirectGroupProvenance.PRE_EXISTING
        }
    }

    private fun clearStaleWifiDirectStepState(
        context: AutomatedDiagnosticsStepContext
    ) {
        context.selectedWifiDirectPeer = null
        context.selectedWifiDirectPeerSource = null
        context.wifiDirectConnectTarget = null
        context.acceptedWifiDirectPeerReadySignal = null
        context.wifiDirectCurrentRunValidatedPeerProofReady = false
        context.wifiDirectCurrentRunConnectProofReady = false
        context.wifiDirectGroupObservedAfterCurrentRunProof = false
        context.wifiDirectGroupObservedAfterCurrentRunProofAtMillis = null
        if (
            context.wifiDirectGroupProvenance !=
            AutomatedDiagnosticsWifiDirectGroupProvenance.CURRENT_RUN_VALIDATED
        ) {
            context.wifiDirectGroupProvenance =
                if (
                    context.preExistingWifiDirectGroupDetected ||
                    context.preExistingWifiDirectSocketDetected
                ) {
                    AutomatedDiagnosticsWifiDirectGroupProvenance.PRE_EXISTING
                } else {
                    AutomatedDiagnosticsWifiDirectGroupProvenance.NONE
                }
        }
    }

    private fun wifiDirectCleanBaselineEstablished(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        context: AutomatedDiagnosticsStepContext
    ): Boolean {
        val connectionStatus = snapshot.wifiDirectRuntimeStatus.connectionStatus
        val dnsSdDiagnostics = snapshot.wifiDirectRuntimeStatus.dnsSdDiagnostics
        return connectionStatus.state == WifiDirectConnectionState.DISCONNECTED &&
            connectionStatus.groupFormed != WifiDirectGroupFormedState.YES &&
            connectionStatus.targetPeer == null &&
            snapshot.wifiDirectSocketDiagnostics.state == WifiDirectSocketState.IDLE &&
            !snapshot.wifiDirectSocketDiagnostics.isConnected &&
            !snapshot.wifiDirectSocketDiagnostics.isReadLoopActive &&
            !dnsSdDiagnostics.localServiceRegistered &&
            !dnsSdDiagnostics.serviceRequestRegistered &&
            !dnsSdDiagnostics.discoveryStarted &&
            dnsSdDiagnostics.discoveredServices.isEmpty() &&
            context.selectedWifiDirectPeer == null &&
            context.selectedWifiDirectPeerSource == null
    }

    private fun wifiDirectCurrentRunProofReady(
        localRole: AutomatedDiagnosticsPeerRole,
        context: AutomatedDiagnosticsStepContext
    ): Boolean {
        return when (localRole) {
            AutomatedDiagnosticsPeerRole.COORDINATOR ->
                context.wifiDirectBaselineEstablished &&
                    context.wifiDirectCurrentRunTokenProofReady &&
                    context.wifiDirectCurrentRunDnsSdProofReady &&
                    context.wifiDirectCurrentRunValidatedPeerProofReady &&
                    context.wifiDirectCurrentRunConnectProofReady

            AutomatedDiagnosticsPeerRole.PARTICIPANT ->
                context.wifiDirectBaselineEstablished &&
                    context.wifiDirectCurrentRunTokenProofReady &&
                    context.wifiDirectCurrentRunDnsSdProofReady
        }
    }

    private fun wifiDirectCurrentRunProofBlocker(
        localRole: AutomatedDiagnosticsPeerRole,
        context: AutomatedDiagnosticsStepContext
    ): String {
        return when (localRole) {
            AutomatedDiagnosticsPeerRole.COORDINATOR -> when {
                !context.wifiDirectBaselineEstablished ->
                    "Waiting for a clean Wi-Fi Direct baseline."
                !context.wifiDirectCurrentRunTokenProofReady ->
                    "Waiting for a fresh participant correlation token over BLE."
                !context.wifiDirectCurrentRunDnsSdProofReady ->
                    "Waiting for an exact current-run DNS-SD token match."
                !context.wifiDirectCurrentRunValidatedPeerProofReady ->
                    "Waiting for the validated Wi-Fi Direct peer to appear in the ordinary peer list."
                !context.wifiDirectCurrentRunConnectProofReady ->
                    "Waiting for a current-run validated Wi-Fi Direct connect."
                else ->
                    "Waiting for current-run Wi-Fi Direct group provenance."
            }

            AutomatedDiagnosticsPeerRole.PARTICIPANT -> when {
                !context.wifiDirectBaselineEstablished ->
                    "Waiting for a clean Wi-Fi Direct baseline."
                context.wifiDirectCorrelationToken == null ->
                    "Waiting to generate a participant correlation token."
                !context.wifiDirectCurrentRunTokenProofReady ->
                    "Waiting for a successful participant correlation-token BLE send."
                !context.wifiDirectCurrentRunDnsSdProofReady ->
                    "Waiting for participant DNS-SD registration for the current run."
                else ->
                    "Waiting for current-run Wi-Fi Direct group provenance."
            }
        }
    }

    private fun wifiDirectBaselineBlocker(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        context: AutomatedDiagnosticsStepContext
    ): String {
        val connectionStatus = snapshot.wifiDirectRuntimeStatus.connectionStatus
        return when {
            hasPreExistingWifiDirectGroupOrConnection(snapshot) &&
                !context.wifiDirectBaselineDisconnectRequested ->
                "A pre-existing Wi-Fi Direct group or connection was detected."
            hasPreExistingWifiDirectGroupOrConnection(snapshot) ->
                "Waiting for the pre-existing Wi-Fi Direct group to disconnect."
            hasPreExistingWifiDirectSocketState(snapshot) &&
                !context.wifiDirectBaselineSocketCleanupRequested ->
                "A stale Wi-Fi Direct socket state was detected."
            hasPreExistingWifiDirectSocketState(snapshot) ->
                "Waiting for stale Wi-Fi Direct socket state to clear."
            hasAutomatedDiagnosticsDnsSdState(snapshot) ->
                "Waiting for automated diagnostics DNS-SD state to clear."
            context.selectedWifiDirectPeer != null || context.selectedWifiDirectPeerSource != null ->
                "Waiting for stale Wi-Fi Direct diagnostics target state to clear."
            connectionStatus.state != WifiDirectConnectionState.DISCONNECTED ->
                "Waiting for Wi-Fi Direct connection state DISCONNECTED."
            connectionStatus.targetPeer != null ->
                "Waiting for stale Wi-Fi Direct target selection to clear."
            else -> "Waiting for a clean Wi-Fi Direct baseline."
        }
    }

    private suspend fun prepareWifiDirectFreshBaseline(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext
    ): AutomatedDiagnosticStepStatus? {
        if (context.wifiDirectBaselineEstablished) {
            return null
        }
        val startedAt = clock.nowMillis()
        while (currentCoroutineContext().isActive) {
            val now = clock.nowMillis()
            val elapsed = now - startedAt
            val snapshot = bindings.snapshot()
            recordInitialWifiDirectState(snapshot, context)
            if (
                hasPreExistingWifiDirectSocketState(snapshot) &&
                !context.wifiDirectBaselineSocketCleanupRequested
            ) {
                context.wifiDirectBaselineSocketCleanupRequested = true
                bindings.commands.closeWifiDirectSocket()
                bindings.commands.resetWifiDirectSocketDiagnostics()
            }
            if (
                hasPreExistingWifiDirectGroupOrConnection(snapshot) &&
                context.wifiDirectBaselineDisconnectRequestCount == 0
            ) {
                context.wifiDirectBaselineDisconnectRequested = true
                context.wifiDirectBaselineDisconnectRequestCount = 1
                bindings.commands.disconnectWifiDirectPeer()
            }
            if (
                hasAutomatedDiagnosticsDnsSdState(snapshot) ||
                context.selectedWifiDirectPeer != null ||
                context.selectedWifiDirectPeerSource != null
            ) {
                bindings.commands.clearAutomatedDiagnosticsWifiDirectServiceDiscovery()
                clearStaleWifiDirectStepState(context)
            }

            val refreshedSnapshot = bindings.snapshot()
            if (wifiDirectCleanBaselineEstablished(refreshedSnapshot, context)) {
                context.wifiDirectBaselineEstablished = true
                context.wifiDirectBaselineEstablishedAtMillis = now
                return null
            }

            updateStep(
                stepId = stepId,
                status = AutomatedDiagnosticStepStatus.RUNNING,
                startedAtMillis = startedAt,
                elapsedMillis = elapsed,
                retryCount = stepResult(stepId).retryCount,
                summary = "Preparing clean Wi-Fi Direct baseline",
                blocker = wifiDirectBaselineBlocker(refreshedSnapshot, context),
                evidence = wifiDirectGroupEvidence(
                    snapshot = refreshedSnapshot,
                    selectedPeer = context.selectedWifiDirectPeer,
                    context = context
                ),
                waitingProgressText =
                "Waiting ${formatAutomatedDiagnosticsDuration(elapsed)} / ${formatAutomatedDiagnosticsDuration(timingPolicy.wifiDirectGroupFormation.timeoutMillis)}",
                stabilizationProgressText = null
            )

            if (elapsed >= timingPolicy.wifiDirectGroupFormation.timeoutMillis) {
                return completeStep(
                    stepId = stepId,
                    status = AutomatedDiagnosticStepStatus.BLOCKED,
                    summary = "Wi-Fi Direct baseline blocked",
                    blocker = wifiDirectBaselineBlocker(refreshedSnapshot, context),
                    evidence = wifiDirectGroupEvidence(
                        snapshot = refreshedSnapshot,
                        selectedPeer = context.selectedWifiDirectPeer,
                        context = context
                    ),
                    startedAtMillis = startedAt
                )
            }
            delay.delayMillis(timingPolicy.pollIntervalMillis)
        }
        return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.CANCELLED,
            summary = "Cancelled",
            blocker = "Automated diagnostics were cancelled.",
            startedAtMillis = startedAt
        )
    }

    private fun wifiDirectSocketReady(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot
    ): Boolean {
        return snapshot.wifiDirectSocketDiagnostics.isConnected &&
            snapshot.wifiDirectSocketDiagnostics.isReadLoopActive &&
            snapshot.wifiDirectAdapterDiagnostics.state.name == "READY"
    }

    private fun wifiDirectBridgesReady(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot
    ): Boolean {
        return snapshot.wifiDirectSendBridgeDiagnostics.enabled &&
            snapshot.wifiDirectReceiveBridgeDiagnostics.enabled
    }

    private fun wifiDirectPeerDiscoveryBlocker(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        localRole: AutomatedDiagnosticsPeerRole
    ): String? {
        return wifiDirectReadinessBlocker(snapshot)
            ?: when {
                wifiDirectGroupReady(snapshot) -> null
                snapshot.wifiDirectRuntimeStatus.discoveryState != WifiDirectDiscoveryState.ACTIVE ->
                    "Wi-Fi Direct discovery is not active."
                snapshot.wifiDirectRuntimeStatus.peers.isNotEmpty() -> null
                localRole == AutomatedDiagnosticsPeerRole.PARTICIPANT ->
                    "Waiting for the coordinator to expose a Wi-Fi Direct peer."
                else -> "No Wi-Fi Direct peer discovered yet."
            }
    }

    private fun wifiDirectGroupFormationBlocker(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        localRole: AutomatedDiagnosticsPeerRole,
        selectedPeer: WifiDirectPeer?,
        selectedPeerSource: String?,
        connectInvocationCount: Int
    ): String? {
        val connectionStatus = snapshot.wifiDirectRuntimeStatus.connectionStatus
        val validatedPeerVisible = visibleWifiDirectPeerForSelectedTarget(
            snapshot = snapshot,
            selectedPeer = selectedPeer,
            selectedPeerSource = selectedPeerSource
        ) != null
        val maxAttempts = timingPolicy.wifiDirectGroupFormation.maxRetries + 1
        return wifiDirectReadinessBlocker(snapshot)
            ?: when (connectionStatus.state) {
                WifiDirectConnectionState.CONNECTED -> {
                    if (wifiDirectGroupReady(snapshot)) {
                        null
                    } else {
                        "Wi-Fi Direct group is connected but group-owner details are incomplete."
                    }
                }
                WifiDirectConnectionState.CONNECTING ->
                    "Waiting for Wi-Fi Direct group formation."
                WifiDirectConnectionState.DISCONNECTING ->
                    "Wi-Fi Direct is disconnecting."
                WifiDirectConnectionState.FAILED ->
                    if (connectInvocationCount >= maxAttempts) {
                        connectionStatus.lastError
                            ?: snapshot.wifiDirectRuntimeStatus.lastError
                            ?: "Wi-Fi Direct group formation failed."
                    } else {
                        connectionStatus.lastError
                            ?: "Waiting to retry Wi-Fi Direct group formation."
                    }
                WifiDirectConnectionState.DISCONNECTED -> when {
                    localRole == AutomatedDiagnosticsPeerRole.PARTICIPANT ->
                        "Waiting for the coordinator to initiate Wi-Fi Direct group formation."
                    selectedPeer == null ->
                        "No Wi-Fi Direct peer is available for connection."
                    !validatedPeerVisible &&
                        selectedPeerSource == validatedDnsSdTokenPeerSource ->
                        "Waiting for the validated Wi-Fi Direct peer to appear in the ordinary peer list."
                    connectInvocationCount >= maxAttempts &&
                        connectionStatus.lastError != null ->
                        connectionStatus.lastError
                    else ->
                        "Waiting for the coordinator to connect to the validated Wi-Fi Direct peer."
                }
            }
    }

    private fun wifiDirectSocketBlocker(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        context: AutomatedDiagnosticsStepContext? = null,
        minimumCreatedAtMillis: Long = 0L
    ): String? {
        val sharedRun = context?.sharedRun
        val localPeerId = snapshot.localPeerId?.trim()?.takeIf { it.isNotEmpty() }
        val remotePeerId = if (sharedRun != null && localPeerId != null) {
            otherSharedRunPeerId(sharedRun, localPeerId)
        } else {
            null
        }
        val serverReadySignal = if (
            sharedRun != null &&
            localPeerId != null &&
            remotePeerId != null
        ) {
            recentAcceptedAutomatedDiagnosticsServerReadySignalOrNull(
                snapshot = snapshot,
                expectedRun = sharedRun,
                expectedOwnerPeerId = if (
                    snapshot.wifiDirectRuntimeStatus.connectionStatus.role ==
                    WifiDirectConnectionRole.GROUP_OWNER
                ) {
                    localPeerId
                } else {
                    remotePeerId
                },
                expectedClientPeerId = if (
                    snapshot.wifiDirectRuntimeStatus.connectionStatus.role ==
                    WifiDirectConnectionRole.GROUP_OWNER
                ) {
                    remotePeerId
                } else {
                    localPeerId
                },
                minimumCreatedAtMillis = minimumCreatedAtMillis,
                requireGroupReady = true,
                context = context
            )
        } else {
            null
        }
        val lastRejectedReason =
            context?.serverReadyLastRejectedReason ?: context?.coordinationCounters?.lastRejectedReason
        return wifiDirectGroupFormationBlocker(
            snapshot = snapshot,
            localRole = mutableState.value.localPeerRole ?: AutomatedDiagnosticsPeerRole.COORDINATOR,
            selectedPeer = null,
            selectedPeerSource = null,
            connectInvocationCount = context?.wifiDirectConnectInvocationCount ?: 0
        )
            ?: when {
                snapshot.wifiDirectRuntimeStatus.connectionStatus.role ==
                    WifiDirectConnectionRole.CLIENT &&
                    serverReadySignal == null &&
                    lastRejectedReason != null ->
                    "Waiting for a fresh Wi-Fi Direct server-ready signal from the group owner. Last rejection: ${lastRejectedReason.statusText}."
                snapshot.wifiDirectRuntimeStatus.connectionStatus.role ==
                    WifiDirectConnectionRole.CLIENT &&
                    serverReadySignal == null ->
                    "Waiting for a fresh Wi-Fi Direct server-ready signal from the group owner."
                snapshot.wifiDirectRuntimeStatus.connectionStatus.role ==
                    WifiDirectConnectionRole.CLIENT &&
                    snapshot.wifiDirectRuntimeStatus.connectionStatus.groupOwnerAddress.isNullOrBlank() ->
                    "Wi-Fi Direct group owner address is unavailable."
                snapshot.wifiDirectSocketDiagnostics.state == WifiDirectSocketState.SERVER_LISTENING ->
                    snapshot.lastAutomatedDiagnosticsServerReadyStatus
                        ?: "Waiting for the Wi-Fi Direct socket client to connect."
                snapshot.wifiDirectSocketDiagnostics.state == WifiDirectSocketState.CONNECTING ->
                    "Connecting the Wi-Fi Direct socket client."
                snapshot.wifiDirectSocketDiagnostics.state == WifiDirectSocketState.STARTING_SERVER ->
                    "Starting the Wi-Fi Direct socket server."
                snapshot.wifiDirectSocketDiagnostics.state == WifiDirectSocketState.FAILED ->
                    snapshot.wifiDirectSocketDiagnostics.lastError
                        ?: snapshot.wifiDirectSocketDiagnostics.lastCommandError
                        ?: "Wi-Fi Direct socket failed."
                !snapshot.wifiDirectSocketDiagnostics.isConnected ->
                    "Wi-Fi Direct socket is not connected."
                !snapshot.wifiDirectSocketDiagnostics.isReadLoopActive ->
                    "Wi-Fi Direct socket read loop is not active."
                snapshot.wifiDirectAdapterDiagnostics.state.name != "READY" ->
                    snapshot.wifiDirectAdapterDiagnostics.notReadyReason
                        ?: snapshot.wifiDirectAdapterDiagnostics.lastError
                        ?: "Wi-Fi Direct frame adapter is not ready."
                else -> null
            }
    }

    private fun wifiDirectBridgeBlocker(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot
    ): String? {
        return wifiDirectSocketBlocker(snapshot)
            ?: when {
                !snapshot.wifiDirectSendBridgeDiagnostics.enabled ->
                    snapshot.wifiDirectSendBridgeDiagnostics.lastSendBridgeError
                        ?: "Wi-Fi Direct send bridge is not enabled."
                !snapshot.wifiDirectReceiveBridgeDiagnostics.enabled ->
                    snapshot.wifiDirectReceiveBridgeDiagnostics.lastToggleBlockedReason
                        ?: "Wi-Fi Direct receive bridge is not enabled."
                else -> null
            }
    }

    private fun hybridBootstrapOfferBlocker(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        runStartedAtMillis: Long
    ): String {
        return if (hasRecentHybridOffer(snapshot, runStartedAtMillis)) {
            "Recent hybrid bootstrap offer already recorded."
        } else {
            "Waiting for the coordinator to send a recent hybrid bootstrap offer."
        }
    }

    private fun hybridBootstrapAcceptBlocker(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        runStartedAtMillis: Long
    ): String {
        return if (hasRecentHybridAccept(snapshot, runStartedAtMillis)) {
            "Recent hybrid bootstrap accept already recorded."
        } else {
            "Waiting for the participant to send a recent hybrid bootstrap accept."
        }
    }

    private fun hybridBootstrapSocketHintBlocker(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        runStartedAtMillis: Long
    ): String {
        return when {
            hasRecentSocketReadyHybridCandidate(snapshot, runStartedAtMillis) ->
                "Recent hybrid bootstrap socket hint already recorded."
            snapshot.hybridBootstrapDiagnostics.socketReadyCandidateCount > 0 ->
                "Recent hybrid bootstrap socket hint recorded, waiting for socket-ready stabilization."
            else ->
                "Waiting for a recent hybrid bootstrap socket hint from the Wi-Fi Direct group owner."
        }
    }

    private fun hasRecentHybridOffer(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        runStartedAtMillis: Long
    ): Boolean {
        return recentHybridCandidateOrNull(snapshot, runStartedAtMillis) { candidate ->
            candidate.hasOffer
        } != null
    }

    private fun hasRecentHybridAccept(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        runStartedAtMillis: Long
    ): Boolean {
        return recentHybridCandidateOrNull(snapshot, runStartedAtMillis) { candidate ->
            candidate.hasOffer && candidate.hasAccept
        } != null
    }

    private fun hasRecentSocketReadyHybridCandidate(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        runStartedAtMillis: Long
    ): Boolean {
        return recentHybridCandidateOrNull(snapshot, runStartedAtMillis) { candidate ->
            candidate.hasOffer &&
                candidate.hasAccept &&
                candidate.hasSocketHint &&
                candidate.socketReady
        } != null
    }

    private fun recentHybridCandidateOrNull(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        runStartedAtMillis: Long,
        predicate: (HybridBootstrapCandidate) -> Boolean
    ): HybridBootstrapCandidate? {
        return activeHybridCandidateOrNull(snapshot, predicate)?.takeIf { candidate ->
            candidate.latestCreatedAtMillis >= runStartedAtMillis
        }
    }

    private fun activeHybridCandidateOrNull(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        predicate: (HybridBootstrapCandidate) -> Boolean
    ): HybridBootstrapCandidate? {
        val activePeerId = snapshot.activeTransportPeerId?.trim()?.takeIf { it.isNotEmpty() }
        val matchingCandidates = snapshot.hybridBootstrapDecision.candidates.filter { candidate ->
            predicate(candidate) &&
                (activePeerId == null || hybridCandidateMatchesActivePeer(snapshot, candidate, activePeerId))
        }
        return matchingCandidates.firstOrNull()
    }

    private fun hybridCandidateMatchesActivePeer(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        candidate: HybridBootstrapCandidate,
        activePeerId: String
    ): Boolean {
        val activeCanonicalPeerId = snapshot.peerSessionDiagnostics.canonicalPeerIdFor(activePeerId)
            ?: activePeerId
        val candidateCanonicalPeerId =
            snapshot.peerSessionDiagnostics.canonicalPeerIdFor(candidate.peerId)
                ?: candidate.peerId
        return candidateCanonicalPeerId == activeCanonicalPeerId
    }

    private fun recentAcceptedAutomatedDiagnosticsServerReadySignalOrNull(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        expectedRun: AutomatedDiagnosticsSharedRun,
        expectedOwnerPeerId: String,
        expectedClientPeerId: String,
        minimumCreatedAtMillis: Long,
        requireGroupReady: Boolean,
        context: AutomatedDiagnosticsStepContext
    ): AutomatedDiagnosticsServerReadySignal? {
        val signal = snapshot.latestAutomatedDiagnosticsServerReadySignal ?: return null
        val signature = serverReadySignalSignature(signal)
        val now = clock.nowMillis()
        val isNewObservation = context.markServerReadyObserved(signature, now)
        if (isNewObservation) {
            context.serverReadyReceivedCount += 1
        }
        val observedAtMonotonicMillis =
            context.lastObservedServerReadyObservedAtMonotonicMillis
                ?: now
        val leaseDurationMillis =
            (signal.expiresAtMillis - signal.createdAtMillis).coerceAtLeast(0L)
        val rejectionReason = when {
            now - observedAtMonotonicMillis > leaseDurationMillis ->
                AutomatedDiagnosticsCoordinationRejectionReason.STALE
            signal.sharedRun.runId != expectedRun.runId ->
                AutomatedDiagnosticsCoordinationRejectionReason.WRONG_RUN
            signal.sharedRun.coordinatorPeerId != expectedRun.coordinatorPeerId ||
                signal.sharedRun.participantPeerId != expectedRun.participantPeerId ->
                AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER
            signal.sharedRun.sessionAssociationId != expectedRun.sessionAssociationId ->
                AutomatedDiagnosticsCoordinationRejectionReason.WRONG_SESSION
            signal.peerId != expectedOwnerPeerId ||
                signal.expectedClientPeerId != expectedClientPeerId ->
                AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER
            signal.groupOwnerAddress.isBlank() || signal.socketPort !in 1..65535 ->
                AutomatedDiagnosticsCoordinationRejectionReason.INVALID_PAYLOAD
            requireGroupReady && !wifiDirectGroupReady(snapshot) ->
                AutomatedDiagnosticsCoordinationRejectionReason.BEFORE_GROUP_READY
            else -> null
        }
        if (rejectionReason != null) {
            if (isNewObservation) {
                context.coordinationCounters =
                    context.coordinationCounters.recordRejected(rejectionReason)
                context.serverReadyLastRejectedReason = rejectionReason
            }
            return null
        }
        if (isNewObservation) {
            context.coordinationCounters = context.coordinationCounters.recordAccepted()
            context.serverReadyAcceptedCount += 1
            context.serverReadyLastRejectedReason = null
        }
        return signal
    }

    private fun recentAcceptedAutomatedDiagnosticsWifiDirectPeerReadySignalOrNull(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        expectedRun: AutomatedDiagnosticsSharedRun,
        expectedSenderPeerId: String,
        expectedRecipientPeerId: String,
        context: AutomatedDiagnosticsStepContext
    ): AutomatedDiagnosticsWifiDirectPeerReadySignal? {
        val signal = snapshot.latestAutomatedDiagnosticsWifiDirectPeerReadySignal ?: return null
        val signature = wifiDirectPeerReadySignalSignature(signal)
        val now = clock.nowMillis()
        val isNewObservation = context.markWifiDirectPeerReadyObserved(signature, now)
        if (isNewObservation) {
            context.wifiDirectPeerReadyReceivedCount += 1
        }
        val expectedCanonicalPeerPair = expectedRun.canonicalPeerPair()
        val actualCanonicalPeerPair = signal.sharedRun.canonicalPeerPair()
        val activeTransportPeerId = snapshot.activeTransportPeerId?.trim()?.takeIf { it.isNotEmpty() }
        val localPeerId = snapshot.localPeerId?.trim()?.takeIf { it.isNotEmpty() }
        val observedAtMonotonicMillis =
            context.lastObservedWifiDirectPeerReadyObservedAtMonotonicMillis
                ?: now
        val leaseDurationMillis =
            (signal.expiresAtMillis - signal.createdAtMillis).coerceAtLeast(0L)
        val rejection = when {
            signal.sharedRun.runId != expectedRun.runId ->
                coordinationValidationFailure(
                    reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_RUN,
                    functionName = "recentAcceptedAutomatedDiagnosticsWifiDirectPeerReadySignalOrNull",
                    fieldName = "signal.sharedRun.runId",
                    expectedValue = expectedRun.runId,
                    observedValue = signal.sharedRun.runId
                )
            signal.sharedRun.coordinatorPeerId != expectedRun.coordinatorPeerId ->
                coordinationValidationFailure(
                    reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER,
                    functionName = "recentAcceptedAutomatedDiagnosticsWifiDirectPeerReadySignalOrNull",
                    fieldName = "signal.sharedRun.coordinatorPeerId",
                    expectedValue = expectedRun.coordinatorPeerId,
                    observedValue = signal.sharedRun.coordinatorPeerId
                )
            signal.sharedRun.participantPeerId != expectedRun.participantPeerId ->
                coordinationValidationFailure(
                    reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER,
                    functionName = "recentAcceptedAutomatedDiagnosticsWifiDirectPeerReadySignalOrNull",
                    fieldName = "signal.sharedRun.participantPeerId",
                    expectedValue = expectedRun.participantPeerId,
                    observedValue = signal.sharedRun.participantPeerId
                )
            actualCanonicalPeerPair != expectedCanonicalPeerPair ->
                coordinationValidationFailure(
                    reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER,
                    functionName = "recentAcceptedAutomatedDiagnosticsWifiDirectPeerReadySignalOrNull",
                    fieldName = "signal.sharedRun.canonicalPeerPair()",
                    expectedValue = canonicalPeerPairText(expectedCanonicalPeerPair),
                    observedValue = canonicalPeerPairText(actualCanonicalPeerPair)
                )
            signal.sharedRun.sessionAssociationId != expectedRun.sessionAssociationId ->
                coordinationValidationFailure(
                    reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_SESSION,
                    functionName = "recentAcceptedAutomatedDiagnosticsWifiDirectPeerReadySignalOrNull",
                    fieldName = "signal.sharedRun.sessionAssociationId",
                    expectedValue = expectedRun.sessionAssociationId,
                    observedValue = signal.sharedRun.sessionAssociationId
                )
            signal.peerId != expectedSenderPeerId ->
                coordinationValidationFailure(
                    reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER,
                    functionName = "recentAcceptedAutomatedDiagnosticsWifiDirectPeerReadySignalOrNull",
                    fieldName = "signal.peerId",
                    expectedValue = expectedSenderPeerId,
                    observedValue = signal.peerId
                )
            signal.expectedRemotePeerId != expectedRecipientPeerId ->
                coordinationValidationFailure(
                    reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER,
                    functionName = "recentAcceptedAutomatedDiagnosticsWifiDirectPeerReadySignalOrNull",
                    fieldName = "signal.expectedRemotePeerId",
                    expectedValue = expectedRecipientPeerId,
                    observedValue = signal.expectedRemotePeerId
                )
            activeTransportPeerId != expectedSenderPeerId ->
                coordinationValidationFailure(
                    reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER,
                    functionName = "recentAcceptedAutomatedDiagnosticsWifiDirectPeerReadySignalOrNull",
                    fieldName = "snapshot.activeTransportPeerId",
                    expectedValue = expectedSenderPeerId,
                    observedValue = activeTransportPeerId ?: "none"
                )
            localPeerId != null && localPeerId != expectedRecipientPeerId ->
                coordinationValidationFailure(
                    reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER,
                    functionName = "recentAcceptedAutomatedDiagnosticsWifiDirectPeerReadySignalOrNull",
                    fieldName = "snapshot.localPeerId",
                    expectedValue = expectedRecipientPeerId,
                    observedValue = localPeerId
                )
            !isAutomatedDiagnosticsCorrelationTokenValid(signal.wifiDirectCorrelationToken) ->
                coordinationValidationFailure(
                    reason = AutomatedDiagnosticsCoordinationRejectionReason.INVALID_TOKEN,
                    functionName = "recentAcceptedAutomatedDiagnosticsWifiDirectPeerReadySignalOrNull",
                    fieldName = "signal.wifiDirectCorrelationToken",
                    expectedValue = "opaque-url-safe-token",
                    observedValue = signal.wifiDirectCorrelationToken.take(16)
                )
            now - observedAtMonotonicMillis > leaseDurationMillis ->
                coordinationValidationFailure(
                    reason = AutomatedDiagnosticsCoordinationRejectionReason.STALE,
                    functionName = "recentAcceptedAutomatedDiagnosticsWifiDirectPeerReadySignalOrNull",
                    fieldName = "signal.localMonotonicLeaseMillis",
                    expectedValue = "<=$leaseDurationMillis",
                    observedValue = (now - observedAtMonotonicMillis).toString()
                )
            else -> null
        }
        if (rejection != null) {
            context.wifiDirectPeerReadyLastRejectedReason = rejection.reason
            context.wifiDirectPeerReadyLastRejectedField = rejection.fieldName
            context.wifiDirectPeerReadyLastRejectedExpectedValue = rejection.expectedValue
            context.wifiDirectPeerReadyLastRejectedObservedValue = rejection.observedValue
            if (isNewObservation) {
                context.wifiDirectPeerReadyValidationCounters =
                    context.wifiDirectPeerReadyValidationCounters.recordRejected(rejection.reason)
            }
            if (rejection.reason == AutomatedDiagnosticsCoordinationRejectionReason.STALE) {
                context.acceptedWifiDirectPeerReadySignal = null
                if (context.selectedWifiDirectPeerSource == validatedDnsSdTokenPeerSource) {
                    context.selectedWifiDirectPeer = null
                    context.selectedWifiDirectPeerSource = null
                    context.wifiDirectConnectTarget = null
                }
            }
            return null
        }
        if (isNewObservation) {
            context.wifiDirectPeerReadyValidationCounters =
                context.wifiDirectPeerReadyValidationCounters.recordAccepted()
            context.wifiDirectPeerReadyAcceptedCount += 1
            context.acceptedWifiDirectPeerReadySignal = signal
            context.wifiDirectPeerReadyLastRejectedReason = null
            context.wifiDirectPeerReadyLastRejectedField = null
            context.wifiDirectPeerReadyLastRejectedExpectedValue = null
            context.wifiDirectPeerReadyLastRejectedObservedValue = null
        }
        return context.acceptedWifiDirectPeerReadySignal ?: signal
    }

    private fun identityEvidence(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        targetPeerId: String
    ): List<AutomatedDiagnosticEvidenceValue> {
        val identity = snapshot.privateChatIdentitiesByPeerId[targetPeerId]
        return listOf(
            AutomatedDiagnosticEvidenceValue("Selected peer", snapshot.selectedSecurePeerId ?: "none"),
            AutomatedDiagnosticEvidenceValue("Active peer", snapshot.activeTransportPeerId ?: "none"),
            AutomatedDiagnosticEvidenceValue(
                "Last identity",
                snapshot.lastIdentityExchangeStatus ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Local proposal",
                identity?.localProposalId ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Remote proposal",
                identity?.remoteProposalId ?: "none"
            )
        )
    }

    private fun secureSessionEvidence(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        targetPeerId: String
    ): List<AutomatedDiagnosticEvidenceValue> {
        val contact = snapshot.contacts.firstOrNull { it.canonicalPeerId == targetPeerId }
        val identity = snapshot.privateChatIdentitiesByPeerId[targetPeerId]
        return listOf(
            AutomatedDiagnosticEvidenceValue("Contact", if (contact != null) "present" else "missing"),
            AutomatedDiagnosticEvidenceValue("Selected peer", snapshot.selectedSecurePeerId ?: "none"),
            AutomatedDiagnosticEvidenceValue("Active peer", snapshot.activeTransportPeerId ?: "none"),
            AutomatedDiagnosticEvidenceValue(
                "Selected session",
                snapshot.peerSessionDiagnostics.hasSessionForPeer(targetPeerId).toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Private chat id",
                identity?.privateChatId ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Identity established",
                (identity?.isEstablished == true).toString()
            )
        )
    }

    private fun latestRuntimeEvidence(): List<AutomatedDiagnosticEvidenceValue> {
        val snapshot = bindings.snapshot()
        return listOf(
            AutomatedDiagnosticEvidenceValue(
                "Lifecycle",
                snapshot.runtimeEvidence.activityLifecycleState.name
            ),
            AutomatedDiagnosticEvidenceValue(
                "Cleanup reason",
                snapshot.runtimeEvidence.lastCleanupReason ?: "none"
            )
        )
    }

    private fun shouldRequestWifiDirectPeerReadySignal(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        context: AutomatedDiagnosticsStepContext,
        lastRequestAtMillis: Long?,
        now: Long
    ): Boolean {
        return (wifiDirectGroupReady(snapshot) ||
            snapshot.wifiDirectRuntimeStatus.discoveryState == WifiDirectDiscoveryState.ACTIVE) &&
            (
                lastRequestAtMillis == null ||
                    now - lastRequestAtMillis >=
                    timingPolicy.automatedDiagnosticsWifiDirectPeerReadyRefreshMillis
                )
    }

    private fun visibleWifiDirectPeerForSelectedTarget(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        selectedPeer: WifiDirectPeer?,
        selectedPeerSource: String?
    ): WifiDirectPeer? {
        if (selectedPeer == null || selectedPeerSource != validatedDnsSdTokenPeerSource) {
            return null
        }
        return snapshot.wifiDirectRuntimeStatus.peers.firstOrNull { peer ->
            wifiDirectPeerMatches(peer, selectedPeer)
        }
    }

    private fun shouldAttemptValidatedWifiDirectConnect(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        context: AutomatedDiagnosticsStepContext,
        targetPeer: WifiDirectPeer
    ): Boolean {
        if (wifiDirectGroupReady(snapshot)) {
            return false
        }
        val connectionStatus = snapshot.wifiDirectRuntimeStatus.connectionStatus
        val maxAttempts = timingPolicy.wifiDirectGroupFormation.maxRetries + 1
        if (context.wifiDirectConnectInvocationCount >= maxAttempts) {
            return false
        }
        return when (connectionStatus.state) {
            WifiDirectConnectionState.CONNECTING,
            WifiDirectConnectionState.CONNECTED,
            WifiDirectConnectionState.DISCONNECTING -> false

            WifiDirectConnectionState.DISCONNECTED,
            WifiDirectConnectionState.FAILED ->
                connectionStatus.targetPeer == null ||
                    wifiDirectPeerMatches(connectionStatus.targetPeer, targetPeer)
        }
    }

    private fun matchingAutomatedDiagnosticsDnsSdResponses(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        correlationToken: String?
    ): List<WifiDirectDnsSdServiceResponse> {
        val expectedToken = correlationToken?.trim()?.takeIf { it.isNotEmpty() }
            ?: return emptyList()
        return snapshot.wifiDirectRuntimeStatus.dnsSdDiagnostics.discoveredServices.filter { response ->
            response.serviceType == automatedDiagnosticsWifiDirectDnsSdServiceType &&
                response.txtRecord[automatedDiagnosticsWifiDirectDnsSdProtocolTxtKey] ==
                automatedDiagnosticsWifiDirectDnsSdProtocolVersion &&
                response.txtRecord[automatedDiagnosticsWifiDirectDnsSdTokenTxtKey] ==
                expectedToken
        }
    }

    private fun wifiDirectPeerCorrelationBlocker(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        localRole: AutomatedDiagnosticsPeerRole,
        context: AutomatedDiagnosticsStepContext,
        remotePeerId: String,
        acceptedRemoteSignal: AutomatedDiagnosticsWifiDirectPeerReadySignal?,
        matchingDnsSdResponses: List<WifiDirectDnsSdServiceResponse>
    ): String? {
        return wifiDirectReadinessBlocker(snapshot)
            ?: when {
                snapshot.wifiDirectRuntimeStatus.discoveryState != WifiDirectDiscoveryState.ACTIVE ->
                    "Wi-Fi Direct discovery is not active."
                localRole == AutomatedDiagnosticsPeerRole.PARTICIPANT &&
                    context.wifiDirectPeerReadySuccessfulSends == 0 ->
                    snapshot.lastAutomatedDiagnosticsWifiDirectPeerReadyStatus
                        ?: "Waiting to send the participant correlation token over BLE."
                localRole == AutomatedDiagnosticsPeerRole.PARTICIPANT &&
                    !snapshot.wifiDirectRuntimeStatus.dnsSdDiagnostics.localServiceRegistered ->
                    "Waiting for the participant DNS-SD diagnostics service registration."
                localRole == AutomatedDiagnosticsPeerRole.PARTICIPANT ->
                    "Waiting for the coordinator to initiate Wi-Fi Direct group formation."
                acceptedRemoteSignal == null &&
                    context.wifiDirectPeerReadyLastRejectedReason != null ->
                    "Waiting for a valid participant correlation token over BLE (last rejection: ${context.wifiDirectPeerReadyLastRejectedReason?.statusText})."
                acceptedRemoteSignal == null ->
                    "Waiting for participant $remotePeerId to expose a correlation token over BLE."
                !snapshot.wifiDirectRuntimeStatus.dnsSdDiagnostics.serviceRequestRegistered ->
                    "Waiting for Wi-Fi Direct DNS-SD service-request registration."
                !snapshot.wifiDirectRuntimeStatus.dnsSdDiagnostics.discoveryStarted ->
                    "Waiting for Wi-Fi Direct DNS-SD discovery to start."
                matchingDnsSdResponses.isEmpty() ->
                    "Waiting for a Wi-Fi Direct DNS-SD token match for ${acceptedRemoteSignal.wifiDirectCorrelationTokenFingerprint()}."
                matchingDnsSdResponses.size > 1 ->
                    "Multiple Wi-Fi Direct DNS-SD peers matched token ${acceptedRemoteSignal.wifiDirectCorrelationTokenFingerprint()}."
                else -> null
            }
    }

    private fun wifiDirectGroupEvidence(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        selectedPeer: WifiDirectPeer?,
        context: AutomatedDiagnosticsStepContext? = null
    ): List<AutomatedDiagnosticEvidenceValue> {
        val runtimeStatus = snapshot.wifiDirectRuntimeStatus
        val connectionStatus = runtimeStatus.connectionStatus
        val localDeviceInfo = runtimeStatus.localDeviceInfo
        val dnsSdDiagnostics = runtimeStatus.dnsSdDiagnostics
        val remoteSignal =
            context?.acceptedWifiDirectPeerReadySignal
                ?: snapshot.latestAutomatedDiagnosticsWifiDirectPeerReadySignal
        val sharedRun = context?.sharedRun
        val localPeerId = snapshot.localPeerId?.trim()?.takeIf { it.isNotEmpty() }
        val remotePeerId = if (sharedRun != null && localPeerId != null) {
            otherSharedRunPeerId(sharedRun, localPeerId)
        } else {
            null
        }
        val matchingDnsSdResponses = matchingAutomatedDiagnosticsDnsSdResponses(
            snapshot = snapshot,
            correlationToken = remoteSignal?.wifiDirectCorrelationToken
        )
        val matchedDnsSdResponse = matchingDnsSdResponses.singleOrNull()
        return buildList {
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Wi-Fi Direct discovery",
                    runtimeStatus.discoveryState.name
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Wi-Fi Direct peers",
                    runtimeStatus.peers.size.toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Wi-Fi Direct state",
                    connectionStatus.state.name
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Initial Wi-Fi Direct state",
                    context?.initialWifiDirectConnectionState?.name ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Group formed",
                    connectionStatus.groupFormed.name
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Initial group formed",
                    context?.initialWifiDirectGroupFormed?.name ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Role",
                    connectionStatus.role.name
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Initial Wi-Fi Direct role",
                    context?.initialWifiDirectRole?.name ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Group owner",
                    connectionStatus.groupOwnerAddress ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Initial socket state",
                    context?.initialWifiDirectSocketState?.name ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Pre-existing group detected",
                    (context?.preExistingWifiDirectGroupDetected ?: false).toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Pre-existing socket detected",
                    (context?.preExistingWifiDirectSocketDetected ?: false).toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Fresh baseline disconnect requested",
                    (context?.wifiDirectBaselineDisconnectRequested ?: false).toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Fresh baseline disconnect request count",
                    (context?.wifiDirectBaselineDisconnectRequestCount ?: 0).toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Fresh baseline socket close/reset requested",
                    (context?.wifiDirectBaselineSocketCleanupRequested ?: false).toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Fresh baseline established",
                    (context?.wifiDirectBaselineEstablished ?: false).toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Fresh baseline established timestamp",
                    context?.wifiDirectBaselineEstablishedAtMillis?.toString() ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Local Wi-Fi device name",
                    localDeviceInfo.deviceName ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Local address raw value",
                    localDeviceInfo.deviceAddress ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Local address classification",
                    localDeviceInfo.addressClassification.name
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Local Wi-Fi device info error",
                    localDeviceInfo.lastError ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Correlation token generated",
                    (context?.wifiDirectCorrelationToken != null).toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Correlation token fingerprint",
                    context?.wifiDirectCorrelationToken?.let(
                        ::automatedDiagnosticsCorrelationTokenFingerprint
                    ) ?: remoteSignal?.wifiDirectCorrelationTokenFingerprint() ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Correlation token BLE send attempts",
                    (context?.wifiDirectPeerReadySendAttempts ?: 0).toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Correlation token BLE successful sends",
                    (context?.wifiDirectPeerReadySuccessfulSends ?: 0).toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Current-run token proof ready",
                    (context?.wifiDirectCurrentRunTokenProofReady ?: false).toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Correlation token received",
                    (context?.wifiDirectPeerReadyReceivedCount ?: 0).toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Correlation token accepted",
                    (context?.wifiDirectPeerReadyAcceptedCount ?: 0).toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Remote Aurora peer id",
                    remotePeerId ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Remote signaled token",
                    remoteSignal?.wifiDirectCorrelationTokenFingerprint() ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Discovered peer count",
                    runtimeStatus.peers.size.toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Discovered peers",
                    runtimeStatus.peers.joinToString(separator = " | ") { peer ->
                        wifiDirectPeerEvidenceText(peer)
                    }.ifBlank { "none" }
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "DNS-SD local service registered",
                    dnsSdDiagnostics.localServiceRegistered.toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "DNS-SD registered token",
                    context?.wifiDirectDnsSdRegisteredCorrelationToken?.let(
                        ::automatedDiagnosticsCorrelationTokenFingerprint
                    ) ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "DNS-SD request registered",
                    dnsSdDiagnostics.serviceRequestRegistered.toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "DNS-SD discovery started",
                    dnsSdDiagnostics.discoveryStarted.toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "DNS-SD responses received",
                    dnsSdDiagnostics.discoveredServices.size.toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "DNS-SD token matches",
                    matchingDnsSdResponses.size.toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Current-run DNS-SD proof ready",
                    (context?.wifiDirectCurrentRunDnsSdProofReady ?: false).toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Matched service device name",
                    matchedDnsSdResponse?.peer?.deviceName ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Matched service observed address",
                    matchedDnsSdResponse?.peer?.deviceAddress ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Selected peer source",
                    context?.selectedWifiDirectPeerSource ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Current-run validated-peer proof ready",
                    when (context?.localRole) {
                        AutomatedDiagnosticsPeerRole.PARTICIPANT -> "n/a"
                        else -> (context?.wifiDirectCurrentRunValidatedPeerProofReady
                            ?: false).toString()
                    }
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Validated peer visible",
                    (
                        visibleWifiDirectPeerForSelectedTarget(
                            snapshot = snapshot,
                            selectedPeer = selectedPeer,
                            selectedPeerSource = context?.selectedWifiDirectPeerSource
                        ) != null
                        ).toString()
                )
            )
            selectedPeer?.deviceName?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
                add(AutomatedDiagnosticEvidenceValue("Selected Wi-Fi peer", name))
            }
            selectedPeer?.deviceAddress?.trim()?.takeIf { it.isNotEmpty() }?.let { address ->
                add(AutomatedDiagnosticEvidenceValue("Selected Wi-Fi address", address))
            }
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Wi-Fi Direct connect invocation count",
                    (context?.wifiDirectConnectInvocationCount ?: 0).toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Current-run connect proof ready",
                    when (context?.localRole) {
                        AutomatedDiagnosticsPeerRole.PARTICIPANT -> "n/a"
                        else -> (context?.wifiDirectCurrentRunConnectProofReady
                            ?: false).toString()
                    }
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Wi-Fi Direct connect target",
                    context?.wifiDirectConnectTarget ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Wi-Fi Direct connect last failure",
                    connectionStatus.lastError ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Group observed after current-run proof",
                    (context?.wifiDirectGroupObservedAfterCurrentRunProof ?: false).toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Group provenance",
                    context?.wifiDirectGroupProvenance?.name
                        ?: AutomatedDiagnosticsWifiDirectGroupProvenance.NONE.name
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Last DNS-SD error",
                    dnsSdDiagnostics.lastError ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Last token rejection reason",
                    context?.wifiDirectPeerReadyLastRejectedReason?.statusText ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Last peer-ready rejection field",
                    context?.wifiDirectPeerReadyLastRejectedField ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Last peer-ready expected",
                    context?.wifiDirectPeerReadyLastRejectedExpectedValue ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Last peer-ready observed",
                    context?.wifiDirectPeerReadyLastRejectedObservedValue ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Wi-Fi peer-ready status",
                    snapshot.lastAutomatedDiagnosticsWifiDirectPeerReadyStatus ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Cleanup completed",
                    dnsSdDiagnostics.cleanupCompleted.toString()
                )
            )
        }
    }

    private fun wifiDirectPeerEvidenceText(
        peer: WifiDirectPeer
    ): String {
        val name = peer.deviceName?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown"
        val address = peer.deviceAddress?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown"
        return "$name <$address>"
    }

    private fun wifiDirectSocketEvidence(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        context: AutomatedDiagnosticsStepContext? = null,
        minimumCreatedAtMillis: Long = 0L
    ): List<AutomatedDiagnosticEvidenceValue> {
        val socketDiagnostics = snapshot.wifiDirectSocketDiagnostics
        val actualRole = snapshot.wifiDirectRuntimeStatus.connectionStatus.role
        val sharedRun = context?.sharedRun
        val localPeerId = snapshot.localPeerId?.trim()?.takeIf { it.isNotEmpty() }
        val remotePeerId = if (sharedRun != null && localPeerId != null) {
            otherSharedRunPeerId(sharedRun, localPeerId)
        } else {
            null
        }
        val expectedServerOwnerPeerId = when (actualRole) {
            WifiDirectConnectionRole.GROUP_OWNER -> localPeerId
            WifiDirectConnectionRole.CLIENT -> remotePeerId
            WifiDirectConnectionRole.UNKNOWN -> remotePeerId
        }
        val expectedClientPeerId = when (actualRole) {
            WifiDirectConnectionRole.GROUP_OWNER -> remotePeerId
            WifiDirectConnectionRole.CLIENT -> localPeerId
            WifiDirectConnectionRole.UNKNOWN -> localPeerId
        }
        val rawServerReadySignal = snapshot.latestAutomatedDiagnosticsServerReadySignal
        val acceptedServerReadySignal = if (
            sharedRun != null &&
            localPeerId != null &&
            remotePeerId != null
        ) {
            recentAcceptedAutomatedDiagnosticsServerReadySignalOrNull(
                snapshot = snapshot,
                expectedRun = sharedRun,
                expectedOwnerPeerId = if (
                    snapshot.wifiDirectRuntimeStatus.connectionStatus.role ==
                    WifiDirectConnectionRole.GROUP_OWNER
                ) {
                    localPeerId
                } else {
                    remotePeerId
                },
                expectedClientPeerId = if (
                    snapshot.wifiDirectRuntimeStatus.connectionStatus.role ==
                    WifiDirectConnectionRole.GROUP_OWNER
                ) {
                    remotePeerId
                } else {
                    localPeerId
                },
                minimumCreatedAtMillis = minimumCreatedAtMillis,
                requireGroupReady = true,
                context = context
            )
        } else {
            null
        }
        val serverStartEligibility =
            context?.wifiDirectGroupProvenance ==
                AutomatedDiagnosticsWifiDirectGroupProvenance.CURRENT_RUN_VALIDATED &&
                actualRole == WifiDirectConnectionRole.GROUP_OWNER &&
                shouldStartWifiDirectSocketServer(snapshot)
        val serverStartBlocker = when {
            context?.wifiDirectGroupProvenance !=
                AutomatedDiagnosticsWifiDirectGroupProvenance.CURRENT_RUN_VALIDATED ->
                "Step 11 did not establish current-run Wi-Fi Direct group provenance."
            actualRole != WifiDirectConnectionRole.GROUP_OWNER ->
                "Local Wi-Fi Direct role is not GROUP_OWNER."
            !shouldStartWifiDirectSocketServer(snapshot) ->
                "Socket state ${socketDiagnostics.state.name} is not eligible for server start."
            else -> null
        }
        val serverReadySendEligibility =
            context?.wifiDirectGroupProvenance ==
                AutomatedDiagnosticsWifiDirectGroupProvenance.CURRENT_RUN_VALIDATED &&
                actualRole == WifiDirectConnectionRole.GROUP_OWNER &&
                socketDiagnostics.state == WifiDirectSocketState.SERVER_LISTENING &&
                !snapshot.wifiDirectRuntimeStatus.connectionStatus.groupOwnerAddress.isNullOrBlank() &&
                socketDiagnostics.endpoint?.port != null &&
                acceptedServerReadySignal == null
        val serverReadySendBlocker = when {
            context?.wifiDirectGroupProvenance !=
                AutomatedDiagnosticsWifiDirectGroupProvenance.CURRENT_RUN_VALIDATED ->
                "Step 11 did not establish current-run Wi-Fi Direct group provenance."
            actualRole != WifiDirectConnectionRole.GROUP_OWNER ->
                "Local Wi-Fi Direct role is not GROUP_OWNER."
            socketDiagnostics.state != WifiDirectSocketState.SERVER_LISTENING ->
                "Socket state ${socketDiagnostics.state.name} is not SERVER_LISTENING."
            snapshot.wifiDirectRuntimeStatus.connectionStatus.groupOwnerAddress.isNullOrBlank() ->
                "Group owner address unavailable."
            socketDiagnostics.endpoint?.port == null ->
                "Socket port unavailable."
            acceptedServerReadySignal != null ->
                "Fresh matching SERVER_READY already accepted."
            else -> null
        }
        val clientConnectEligibility =
            context?.wifiDirectGroupProvenance ==
                AutomatedDiagnosticsWifiDirectGroupProvenance.CURRENT_RUN_VALIDATED &&
                actualRole == WifiDirectConnectionRole.CLIENT &&
                acceptedServerReadySignal != null &&
                shouldConnectWifiDirectSocketClient(snapshot)
        val clientConnectBlocker = when {
            context?.wifiDirectGroupProvenance !=
                AutomatedDiagnosticsWifiDirectGroupProvenance.CURRENT_RUN_VALIDATED ->
                "Step 11 did not establish current-run Wi-Fi Direct group provenance."
            actualRole != WifiDirectConnectionRole.CLIENT ->
                "Local Wi-Fi Direct role is not CLIENT."
            acceptedServerReadySignal == null ->
                "Waiting for a fresh matching SERVER_READY signal."
            !shouldConnectWifiDirectSocketClient(snapshot) ->
                "Socket state ${socketDiagnostics.state.name} is not eligible for client connect."
            else -> null
        }
        return buildList {
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Current shared run id",
                    sharedRun?.runId ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Local shared run id",
                    sharedRun?.runId ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Remote shared run id",
                    rawServerReadySignal?.sharedRun?.runId ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Participant joined",
                    (context?.participantJoined ?: false).toString()
                )
            )
            add(AutomatedDiagnosticEvidenceValue("Local Aurora peer id", localPeerId ?: "none"))
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Expected server-owner Aurora peer id",
                    expectedServerOwnerPeerId ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Expected client Aurora peer id",
                    expectedClientPeerId ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Step 11 group provenance",
                    context?.wifiDirectGroupProvenance?.name
                        ?: AutomatedDiagnosticsWifiDirectGroupProvenance.NONE.name
                )
            )
            add(AutomatedDiagnosticEvidenceValue("Socket state", socketDiagnostics.state.name))
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Socket connected",
                    socketDiagnostics.isConnected.toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Socket read loop",
                    socketDiagnostics.isReadLoopActive.toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Adapter state",
                    snapshot.wifiDirectAdapterDiagnostics.state.name
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Actual Wi-Fi role",
                    actualRole.name
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Current group owner address",
                    snapshot.wifiDirectRuntimeStatus.connectionStatus.groupOwnerAddress ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Socket diagnostics instance id",
                    snapshot.wifiDirectSocketRuntimeInstanceId
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Socket command-binding instance id",
                    snapshot.wifiDirectSocketCommandBindingInstanceId
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Socket endpoint",
                    socketEndpointEvidenceText(socketDiagnostics)
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Socket command",
                    socketDiagnostics.lastCommand.name
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Socket result",
                    socketDiagnostics.lastCommandResult.name
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Socket attempts",
                    "server=${socketDiagnostics.serverStartAttempts} client=${socketDiagnostics.clientConnectAttempts} close=${socketDiagnostics.closeAttempts}"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Server start invoked/count",
                    socketDiagnostics.serverStartAttempts.toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Server start eligibility",
                    serverStartEligibility.toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Server start blocker",
                    serverStartBlocker ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Server start request issued",
                    ((context?.serverStartRequestCount ?: 0) > 0).toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Server start request count",
                    (context?.serverStartRequestCount ?: 0).toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Server start request timestamp",
                    context?.serverStartRequestAtMillis?.toString() ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Server start request host",
                    context?.serverStartRequestHost ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Server listening observed",
                    (socketDiagnostics.state == WifiDirectSocketState.SERVER_LISTENING ||
                        socketDiagnostics.isConnected).toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Server-ready send eligibility",
                    serverReadySendEligibility.toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Server-ready send blocker",
                    serverReadySendBlocker ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Server-ready sent/count",
                    (context?.serverReadySentCount ?: 0).toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Server-ready received/count",
                    (context?.serverReadyReceivedCount ?: 0).toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Server-ready accepted/count",
                    (context?.serverReadyAcceptedCount ?: 0).toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Last rejected signal reason",
                    context?.serverReadyLastRejectedReason?.statusText ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Latest received SERVER_READY run id",
                    rawServerReadySignal?.sharedRun?.runId ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Latest received SERVER_READY owner peer id",
                    rawServerReadySignal?.peerId ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Latest received SERVER_READY client peer id",
                    rawServerReadySignal?.expectedClientPeerId ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "SERVER_READY rejection reason",
                    context?.serverReadyLastRejectedReason?.statusText ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Client connect eligibility",
                    clientConnectEligibility.toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Client connect blocker",
                    clientConnectBlocker ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Client connect invoked/count",
                    socketDiagnostics.clientConnectAttempts.toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Client connect request count",
                    (context?.clientConnectRequestCount ?: 0).toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Client connect host",
                    context?.clientConnectRequestHost ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Controller token",
                    socketDiagnostics.lastOperationToken.toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Socket sequence",
                    socketDiagnostics.lastCommandSequence.toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Last transition",
                    socketDiagnostics.lastStateTransition ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Last endpoint",
                    socketEndpointEvidenceText(socketDiagnostics)
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Last result",
                    socketDiagnostics.lastCommandResult.name
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Socket command time",
                    socketDiagnostics.lastCommandAtMillis?.toString() ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Socket state time",
                    socketDiagnostics.lastStateChangedAtMillis?.toString() ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Close/dispose reason",
                    listOfNotNull(
                        socketDiagnostics.lastCloseReason,
                        socketDiagnostics.lastDisposeReason
                    ).joinToString(separator = " | ").ifBlank { "none" }
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Last error",
                    socketDiagnostics.lastCommandError
                        ?: socketDiagnostics.lastError
                        ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Server-ready signal",
                    acceptedServerReadySignal?.let(::serverReadySignalEvidenceText)
                        ?: rawServerReadySignal?.let(::serverReadySignalEvidenceText)
                        ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Server-ready status",
                    snapshot.lastAutomatedDiagnosticsServerReadyStatus ?: "none"
                )
            )
        }
    }

    private fun socketEndpointEvidenceText(
        diagnostics: gr.hua.aurora.wifidirect.socket.WifiDirectSocketDiagnostics
    ): String {
        val host = diagnostics.endpoint?.host?.trim()?.takeIf { it.isNotEmpty() }
        val port = diagnostics.endpoint?.port
        return when {
            host != null && port != null -> "$host:$port"
            host != null -> host
            port != null -> port.toString()
            else -> "none"
        }
    }

    private fun serverReadySignalEvidenceText(
        signal: AutomatedDiagnosticsServerReadySignal
    ): String {
        return buildString {
            append(signal.peerId)
            append(" run=")
            append(signal.sharedRun.runId)
            append(" client=")
            append(signal.expectedClientPeerId)
            append(" ")
            append(signal.groupOwnerAddress)
            append(":")
            append(signal.socketPort)
            append(" token=")
            append(signal.serverToken)
            append(" @")
            append(signal.createdAtMillis)
        }
    }

    private fun wifiDirectBridgeEvidence(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot
    ): List<AutomatedDiagnosticEvidenceValue> {
        return listOf(
            AutomatedDiagnosticEvidenceValue(
                "Send bridge",
                snapshot.wifiDirectSendBridgeDiagnostics.enabled.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Receive bridge",
                snapshot.wifiDirectReceiveBridgeDiagnostics.enabled.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Adapter state",
                snapshot.wifiDirectAdapterDiagnostics.state.name
            ),
            AutomatedDiagnosticEvidenceValue(
                "Receive blocked reason",
                snapshot.wifiDirectReceiveBridgeDiagnostics.lastToggleBlockedReason ?: "none"
            )
        )
    }

    private fun hybridBootstrapEvidence(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot
    ): List<AutomatedDiagnosticEvidenceValue> {
        return buildList {
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Hybrid candidates",
                    snapshot.hybridBootstrapDiagnostics.candidateCount.toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Socket-ready candidates",
                    snapshot.hybridBootstrapDiagnostics.socketReadyCandidateCount.toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Hybrid selection",
                    snapshot.hybridBootstrapDiagnostics.selectionStatus.name
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Hybrid status",
                    snapshot.hybridBootstrapDiagnostics.statusText
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Hybrid command",
                    snapshot.hybridBootstrapManualTriggerSnapshot.commandStatusText
                )
            )
            snapshot.hybridBootstrapManualTriggerSnapshot.triggerStatusText?.let { status ->
                add(AutomatedDiagnosticEvidenceValue("Hybrid trigger", status))
            }
            snapshot.lastHybridBootstrapManualOfferStatus?.let { status ->
                add(AutomatedDiagnosticEvidenceValue("Offer status", status))
            }
            snapshot.lastHybridBootstrapManualAcceptStatus?.let { status ->
                add(AutomatedDiagnosticEvidenceValue("Accept status", status))
            }
            snapshot.lastHybridBootstrapManualSocketHintStatus?.let { status ->
                add(AutomatedDiagnosticEvidenceValue("Socket hint status", status))
            }
        }
    }

    private fun selectDeterministicPeer(
        devices: List<BleDiscoveredDevice>
    ): SelectedAutomatedDiagnosticsPeer? {
        val selectedDevice = devices
            .filter { it.hasAuroraDiscoveryPayload }
            .sortedWith(
                compareBy<BleDiscoveredDevice> { it.stableIdentityKey() }
                    .thenBy { it.address }
            )
            .firstOrNull()
            ?: return null
        return SelectedAutomatedDiagnosticsPeer(
            device = selectedDevice,
            identityKey = selectedDevice.stableIdentityKey(),
            displayName = selectedDevice.name?.trim()?.takeIf { it.isNotEmpty() }
                ?: "Aurora device ${selectedDevice.stableIdentityKey().take(8)}"
        )
    }

    private fun selectDeterministicWifiDirectPeer(
        peers: List<WifiDirectPeer>
    ): WifiDirectPeer? {
        return peers.sortedWith(
            compareBy<WifiDirectPeer> { it.deviceName?.trim().orEmpty() }
                .thenBy { it.deviceAddress?.trim().orEmpty() }
        ).firstOrNull()
    }

    private fun BleDiscoveredDevice.stableIdentityKey(): String {
        val stablePeerId = stablePeerId
        return if (stablePeerId != null) {
            stablePeerId.toByteArray().joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xFF)
            }
        } else {
            address.trim()
        }
    }

    private fun booleanStateText(value: Boolean?): String {
        return when (value) {
            true -> "enabled"
            false -> "disabled"
            null -> "unknown"
        }
    }

    private fun shouldStartWifiDirectSocketServer(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot
    ): Boolean {
        return when (snapshot.wifiDirectSocketDiagnostics.state) {
            WifiDirectSocketState.IDLE,
            WifiDirectSocketState.FAILED -> true
            else -> false
        }
    }

    private fun shouldConnectWifiDirectSocketClient(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot
    ): Boolean {
        return !snapshot.wifiDirectRuntimeStatus.connectionStatus.groupOwnerAddress.isNullOrBlank() &&
            when (snapshot.wifiDirectSocketDiagnostics.state) {
                WifiDirectSocketState.IDLE,
                WifiDirectSocketState.FAILED -> true
                else -> false
            }
    }

    private suspend fun maybeAutoJoinParticipantRun() {
        val snapshot = bindings.snapshot()
        val seed = participantAnnouncementSeedOrNull(snapshot) ?: return
        launchParticipantRun(seed)
    }

    private fun participantAnnouncementSeedOrNull(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot
    ): ParticipantAutoJoinSeed? {
        val pendingAnnouncement = pendingParticipantAnnouncement
            ?: claimPendingParticipantAnnouncementOrNull(snapshot)
            ?: return null
        if (snapshot.selectedSecurePeerId != pendingAnnouncement.selectedPeer.identityKey) {
            bindings.commands.selectSecurePeer(pendingAnnouncement.selectedPeer.identityKey)
        }
        val refreshedSnapshot = bindings.snapshot()
        val propagationState = selectedPeerPropagationState(refreshedSnapshot)
        val blocker = secureSessionBlocker(
            refreshedSnapshot,
            pendingAnnouncement.selectedPeer.identityKey
        )
        if (blocker != null) {
            lastAutoJoinBlocker = "$blocker [$propagationState]"
            if (
                clock.nowMillis() - pendingAnnouncement.observedMonotonicMillis >
                timingPolicy.sharedRunCoordination.timeoutMillis
            ) {
                clearPendingParticipantAnnouncement(
                    "participantAnnouncementSeedOrNull: announcement-timeout"
                )
            }
            return null
        }
        lastAutoJoinBlocker = "none"
        val sharedRun = pendingAnnouncement.announcement.sharedRun
        val localRunnerExecutionId = generateRunnerExecutionId()
        val seededSteps = AutomatedDiagnosticStepId.entries.map { stepId ->
            when (stepId) {
                AutomatedDiagnosticStepId.PREFLIGHT ->
                    AutomatedDiagnosticStepResult(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.PASS,
                        summary = "Device preflight already satisfied"
                    )

                AutomatedDiagnosticStepId.BLE_RUNTIME ->
                    AutomatedDiagnosticStepResult(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.PASS,
                        summary = "BLE runtime already active"
                    )

                AutomatedDiagnosticStepId.AURORA_PEER_DISCOVERY ->
                    AutomatedDiagnosticStepResult(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.PASS,
                        summary = "Coordinator peer already discovered",
                        evidenceValues = listOf(
                            AutomatedDiagnosticEvidenceValue(
                                "Chosen peer",
                                pendingAnnouncement.selectedPeer.identityKey
                            )
                        )
                    )

                AutomatedDiagnosticStepId.ROLE_ELECTION ->
                    AutomatedDiagnosticStepResult(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.PASS,
                        summary = "Joined as participant from shared run announcement",
                        evidenceValues = listOf(
                            AutomatedDiagnosticEvidenceValue(
                                "Role",
                                AutomatedDiagnosticsPeerRole.PARTICIPANT.name
                            )
                        )
                    )

                AutomatedDiagnosticStepId.BLE_CONNECTION ->
                    AutomatedDiagnosticStepResult(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.PASS,
                        summary = "BLE connection already active"
                    )

                AutomatedDiagnosticStepId.SECURE_PEER_SELECTION ->
                    AutomatedDiagnosticStepResult(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.PASS,
                        summary = "Secure peer already selected"
                    )

                AutomatedDiagnosticStepId.IDENTITY_KEY_SETUP ->
                    AutomatedDiagnosticStepResult(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.PASS,
                        summary = "Identity setup already satisfied",
                        evidenceValues = identityEvidence(
                            refreshedSnapshot,
                            pendingAnnouncement.selectedPeer.identityKey
                        )
                    )

                AutomatedDiagnosticStepId.SECURE_SESSION_READINESS ->
                    AutomatedDiagnosticStepResult(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.PASS,
                        summary = "Secure session already ready",
                        evidenceValues = secureSessionEvidence(
                            refreshedSnapshot,
                            pendingAnnouncement.selectedPeer.identityKey
                        )
                    )

                else -> AutomatedDiagnosticStepResult(stepId)
            }
        }
        return ParticipantAutoJoinSeed(
            seededState = AutomatedDiagnosticsRunState(
                overallStatus = AutomatedDiagnosticsOverallStatus.IDLE,
                currentStepNumber = null,
                totalSteps = seededSteps.size,
                localRunnerExecutionId = localRunnerExecutionId,
                selectedPeerId = pendingAnnouncement.selectedPeer.identityKey,
                localPeerRole = AutomatedDiagnosticsPeerRole.PARTICIPANT,
                sharedRunId = sharedRun.runId,
                sharedRunCoordinatorPeerId = sharedRun.coordinatorPeerId,
                sharedRunParticipantPeerId = sharedRun.participantPeerId,
                sharedRunSessionAssociationId = sharedRun.sessionAssociationId,
                sharedRunCreatedAtMillis = sharedRun.createdAtMillis,
                sharedRunExpiresAtMillis = sharedRun.expiresAtMillis,
                sharedRunCanonicalPeerPair = canonicalPeerPairText(sharedRun.canonicalPeerPair()),
                steps = seededSteps,
                reportText = automatedDiagnosticsPlainTextReport(
                    overallStatus = AutomatedDiagnosticsOverallStatus.IDLE,
                    selectedPeerId = pendingAnnouncement.selectedPeer.identityKey,
                    localPeerRole = AutomatedDiagnosticsPeerRole.PARTICIPANT,
                    localRunnerExecutionId = localRunnerExecutionId,
                    sharedRunId = sharedRun.runId,
                    sharedRunCoordinatorPeerId = sharedRun.coordinatorPeerId,
                    sharedRunParticipantPeerId = sharedRun.participantPeerId,
                    sharedRunSessionAssociationId = sharedRun.sessionAssociationId,
                    sharedRunCreatedAtMillis = sharedRun.createdAtMillis,
                    sharedRunExpiresAtMillis = sharedRun.expiresAtMillis,
                    sharedRunCanonicalPeerPair = canonicalPeerPairText(sharedRun.canonicalPeerPair()),
                    elapsedMillis = 0L,
                    steps = seededSteps,
                    phaseTwoSummary = automatedDiagnosticsPhaseTwoSummary
                )
            ),
            context = pendingAnnouncement.context,
            startIndex = AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION.ordinal
        )
    }

    private fun claimPendingParticipantAnnouncementOrNull(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot
    ): PendingParticipantAnnouncement? {
        val localPeerId = snapshot.localPeerId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val activePeerId = snapshot.activeTransportPeerId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val sessionAssociationId = currentSharedRunSessionAssociationId(snapshot, activePeerId)
            ?: return null
        val announcement = recentAcceptedRunAnnouncementOrNull(
            snapshot = snapshot,
            expectedSenderPeerId = activePeerId,
            expectedLocalPeerId = localPeerId,
            expectedRemotePeerId = activePeerId,
            expectedSessionAssociationId = sessionAssociationId,
            context = AutomatedDiagnosticsStepContext()
        ) ?: return null
        val signature = runAnnouncementSignature(announcement)
        if (lastObservedAutomaticAnnouncementSignature != signature) {
            lastObservedAutomaticAnnouncementSignature = signature
            announcementObservedCount += 1
        }
        val selectedPeer = selectedPeerForIdentityKey(
            snapshot.discoveredAuroraPeers,
            announcement.sharedRun.coordinatorPeerId
        ) ?: return null
        announcementClaimedCount += 1
        val sharedRun = announcement.sharedRun
        val observationWallClockMillis = currentWallClockMillis()
        val observationMonotonicMillis = clock.nowMillis()
        val pending = PendingParticipantAnnouncement(
            announcement = announcement,
            selectedPeer = selectedPeer,
            sessionAssociationId = sessionAssociationId,
            observedWallClockMillis = observationWallClockMillis,
            observedMonotonicMillis = observationMonotonicMillis,
            context = AutomatedDiagnosticsStepContext(
                selectedPeer = selectedPeer,
                localRole = AutomatedDiagnosticsPeerRole.PARTICIPANT,
                runStartCause = AutomatedDiagnosticsRunStartCause.AUTOMATIC_PARTICIPANT_JOIN,
                sharedRun = sharedRun,
                sharedCanonicalPeerPair = canonicalPeerPairText(sharedRun.canonicalPeerPair()),
                runAnnouncementReceivedAtMillis = observationWallClockMillis,
                runAnnouncementReceivedMonotonicMillis = observationMonotonicMillis,
                conflictAuthorityPeerId = listOf(
                    sharedRun.coordinatorPeerId,
                    sharedRun.participantPeerId
                ).sorted().first()
            )
        )
        pendingParticipantAnnouncement = pending
        return pending
    }

    private fun currentSharedRunSessionAssociationId(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        targetPeerId: String
    ): String? {
        return snapshot.privateChatIdentitiesByPeerId[targetPeerId]
            ?.privateChatId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun automatedDiagnosticsPreparationLeaseMillis(): Long {
        return timingPolicy.auroraPeerDiscovery.timeoutMillis +
            timingPolicy.identityExchange.timeoutMillis +
            timingPolicy.secureSessionReadiness.timeoutMillis
    }

    private fun otherSharedRunPeerId(
        sharedRun: AutomatedDiagnosticsSharedRun,
        localPeerId: String
    ): String? {
        return when (localPeerId) {
            sharedRun.coordinatorPeerId -> sharedRun.participantPeerId
            sharedRun.participantPeerId -> sharedRun.coordinatorPeerId
            else -> null
        }
    }

    private fun ensureLocalPendingSharedRun(
        context: AutomatedDiagnosticsStepContext,
        coordinatorPeerId: String,
        participantPeerId: String,
        sessionAssociationId: String
    ): AutomatedDiagnosticsSharedRun {
        return context.localPendingSharedRun ?: createSharedDiagnosticsRun(
            coordinatorPeerId = coordinatorPeerId,
            participantPeerId = participantPeerId,
            sessionAssociationId = sessionAssociationId
        ).also { provisionalRun ->
            context.localPendingSharedRun = provisionalRun
            context.localProvisionalRunId = provisionalRun.runId
            context.localSharedRunGenerationCount += 1
            lastSharedRunGenerationFunction =
                "runRemoteParticipantCoordinationStep.ensureLocalPendingSharedRun"
        }
    }

    private fun activeCoordinatorRunOrNull(
        context: AutomatedDiagnosticsStepContext
    ): AutomatedDiagnosticsSharedRun? {
        return context.sharedRun ?: context.localPendingSharedRun
    }

    private fun currentWallClockMillis(): Long = wallClockMillis()

    private fun createSharedDiagnosticsRun(
        coordinatorPeerId: String,
        participantPeerId: String,
        sessionAssociationId: String
    ): AutomatedDiagnosticsSharedRun {
        val createdAtMillis = currentWallClockMillis()
        return AutomatedDiagnosticsSharedRun(
            runId = generateSharedRunId(),
            coordinatorPeerId = coordinatorPeerId,
            participantPeerId = participantPeerId,
            sessionAssociationId = sessionAssociationId,
            createdAtMillis = createdAtMillis,
            expiresAtMillis = createdAtMillis + timingPolicy.sharedRunAnnouncementLeaseMillis
        )
    }

    private fun updateSharedRunState(
        role: AutomatedDiagnosticsPeerRole?,
        sharedRun: AutomatedDiagnosticsSharedRun,
        context: AutomatedDiagnosticsStepContext? = null
    ) {
        updateRunState { current ->
            current.copy(
                localPeerRole = role,
                sharedRunId = sharedRun.runId,
                sharedRunCoordinatorPeerId = sharedRun.coordinatorPeerId,
                sharedRunParticipantPeerId = sharedRun.participantPeerId,
                sharedRunSessionAssociationId = sharedRun.sessionAssociationId,
                sharedRunCreatedAtMillis = sharedRun.createdAtMillis,
                sharedRunExpiresAtMillis = sharedRun.expiresAtMillis,
                sharedRunCanonicalPeerPair = canonicalPeerPairText(sharedRun.canonicalPeerPair())
            )
        }
    }

    private fun provisionAnnouncementLease(
        sharedRun: AutomatedDiagnosticsSharedRun,
        nowWallClockMillis: Long
    ): AutomatedDiagnosticsSharedRun {
        return sharedRun.copy(
            createdAtMillis = nowWallClockMillis,
            expiresAtMillis = nowWallClockMillis + timingPolicy.sharedRunAnnouncementLeaseMillis
        )
    }

    private fun prepareSharedRunActiveLeaseForSend(
        sharedRun: AutomatedDiagnosticsSharedRun,
        nowWallClockMillis: Long
    ): AutomatedDiagnosticsSharedRun {
        return sharedRun.copy(
            expiresAtMillis = maxOf(
                sharedRun.expiresAtMillis,
                nowWallClockMillis + timingPolicy.sharedRunActiveLeaseMillis
            )
        )
    }

    private fun refreshSharedRunActiveLease(
        context: AutomatedDiagnosticsStepContext,
        nowWallClockMillis: Long = currentWallClockMillis(),
        remoteActiveLeaseExpiresAtMillis: Long? = null
    ): AutomatedDiagnosticsSharedRun {
        val sharedRun = requireNotNull(context.sharedRun) {
            "Shared run must be available before refreshing the active lease."
        }
        val refreshedRun = sharedRun.copy(
            expiresAtMillis = maxOf(
                sharedRun.expiresAtMillis,
                remoteActiveLeaseExpiresAtMillis ?: 0L,
                nowWallClockMillis + timingPolicy.sharedRunActiveLeaseMillis
            )
        )
        if (refreshedRun != sharedRun) {
            context.sharedRun = refreshedRun
            updateSharedRunState(
                role = context.localRole,
                sharedRun = refreshedRun,
                context = context
            )
        }
        if (context.activeLeaseStartedAtMillis == null) {
            context.activeLeaseStartedAtMillis = nowWallClockMillis
            context.activeLeaseStartedMonotonicMillis = clock.nowMillis()
        }
        return refreshedRun
    }

    private fun generateSharedRunId(): String {
        val bytes = ByteArray(sharedRunIdByteLength)
        sharedRunIdRandom.nextBytes(bytes)
        return "diag-" + bytes.joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }
    }

    private fun generateWifiDirectCorrelationToken(): String {
        val bytes = ByteArray(wifiDirectCorrelationTokenByteLength)
        wifiDirectCorrelationTokenRandom.nextBytes(bytes)
        return bytes.joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }
    }

    private fun generateRunnerExecutionId(): String {
        val bytes = ByteArray(sharedRunIdByteLength)
        runnerExecutionIdRandom.nextBytes(bytes)
        return "diag-exec-" + bytes.joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }
    }

    private fun generateRunnerInstanceId(): String {
        val bytes = ByteArray(sharedRunIdByteLength)
        runnerInstanceIdRandom.nextBytes(bytes)
        return "diag-runner-" + bytes.joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }
    }

    private fun selectedPeerForIdentityKey(
        devices: List<BleDiscoveredDevice>,
        identityKey: String
    ): SelectedAutomatedDiagnosticsPeer? {
        return devices.firstOrNull { device ->
            device.hasAuroraDiscoveryPayload &&
                device.stableIdentityKey() == identityKey
        }?.let { device ->
            SelectedAutomatedDiagnosticsPeer(
                device = device,
                identityKey = identityKey,
                displayName = device.name?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "Aurora device ${identityKey.take(8)}"
            )
        }
    }

    private fun recentAcceptedRunAnnouncementOrNull(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        expectedSenderPeerId: String?,
        expectedLocalPeerId: String?,
        expectedRemotePeerId: String?,
        expectedSessionAssociationId: String,
        context: AutomatedDiagnosticsStepContext
    ): AutomatedDiagnosticsRunAnnouncement? {
        val announcement = snapshot.latestAutomatedDiagnosticsRunAnnouncement ?: return null
        val signature = runAnnouncementSignature(announcement)
        val isNewObservation = context.markAnnouncementObserved(signature)
        val expectedCanonicalPeerPair = if (
            expectedLocalPeerId != null && expectedRemotePeerId != null
        ) {
            AutomatedDiagnosticsCanonicalPeerPair.from(
                expectedLocalPeerId,
                expectedRemotePeerId
            )
        } else {
            null
        }
        val actualCanonicalPeerPair = announcement.sharedRun.canonicalPeerPair()
        val rejection = when {
            expectedCanonicalPeerPair != null &&
                actualCanonicalPeerPair != expectedCanonicalPeerPair ->
                coordinationValidationFailure(
                    reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER,
                    functionName = "recentAcceptedRunAnnouncementOrNull",
                    fieldName = "announcement.sharedRun.canonicalPeerPair()",
                    expectedValue = canonicalPeerPairText(expectedCanonicalPeerPair),
                    observedValue = canonicalPeerPairText(actualCanonicalPeerPair)
                )
            expectedSenderPeerId != null &&
                announcement.peerId != expectedSenderPeerId ->
                coordinationValidationFailure(
                    reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER,
                    functionName = "recentAcceptedRunAnnouncementOrNull",
                    fieldName = "announcement.peerId",
                    expectedValue = expectedSenderPeerId,
                    observedValue = announcement.peerId
                )
            announcement.peerId != announcement.sharedRun.coordinatorPeerId ->
                coordinationValidationFailure(
                    reason = AutomatedDiagnosticsCoordinationRejectionReason.INVALID_PAYLOAD,
                    functionName = "recentAcceptedRunAnnouncementOrNull",
                    fieldName = "announcement.sharedRun.coordinatorPeerId",
                    expectedValue = announcement.peerId,
                    observedValue = announcement.sharedRun.coordinatorPeerId
                )
            announcement.sharedRun.sessionAssociationId != expectedSessionAssociationId ->
                coordinationValidationFailure(
                    reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_SESSION,
                    functionName = "recentAcceptedRunAnnouncementOrNull",
                    fieldName = "announcement.sharedRun.sessionAssociationId",
                    expectedValue = expectedSessionAssociationId,
                    observedValue = announcement.sharedRun.sessionAssociationId
                )
            else -> null
        }
        if (rejection != null) {
            recordCoordinationValidationRejection(
                context = context,
                isNewObservation = isNewObservation,
                failure = rejection
            )
            return null
        }
        if (isNewObservation) {
            context.coordinationCounters = context.coordinationCounters.recordAccepted()
            clearLastCoordinationValidationFailure(context)
        }
        return announcement
    }

    private fun recentAcceptedParticipantJoinOrNull(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        expectedRun: AutomatedDiagnosticsSharedRun,
        expectedPeerId: String,
        context: AutomatedDiagnosticsStepContext
    ): AutomatedDiagnosticsParticipantJoin? {
        val join = snapshot.latestAutomatedDiagnosticsParticipantJoin ?: return null
        val signature = participantJoinSignature(join)
        val isNewObservation = context.markParticipantJoinObserved(signature)
        val expectedCanonicalPeerPair = expectedRun.canonicalPeerPair()
        val actualCanonicalPeerPair = join.sharedRun.canonicalPeerPair()
        val rejection = when {
            join.sharedRun.runId != expectedRun.runId ->
                coordinationValidationFailure(
                    reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_RUN,
                    functionName = "recentAcceptedParticipantJoinOrNull",
                    fieldName = "join.sharedRun.runId",
                    expectedValue = expectedRun.runId,
                    observedValue = join.sharedRun.runId
                )
            actualCanonicalPeerPair != expectedCanonicalPeerPair ->
                coordinationValidationFailure(
                    reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER,
                    functionName = "recentAcceptedParticipantJoinOrNull",
                    fieldName = "join.sharedRun.canonicalPeerPair()",
                    expectedValue = canonicalPeerPairText(expectedCanonicalPeerPair),
                    observedValue = canonicalPeerPairText(actualCanonicalPeerPair)
                )
            join.peerId != expectedPeerId ->
                coordinationValidationFailure(
                    reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER,
                    functionName = "recentAcceptedParticipantJoinOrNull",
                    fieldName = "join.peerId",
                    expectedValue = expectedPeerId,
                    observedValue = join.peerId
                )
            join.peerId != join.sharedRun.participantPeerId ->
                coordinationValidationFailure(
                    reason = AutomatedDiagnosticsCoordinationRejectionReason.INVALID_PAYLOAD,
                    functionName = "recentAcceptedParticipantJoinOrNull",
                    fieldName = "join.sharedRun.participantPeerId",
                    expectedValue = join.peerId,
                    observedValue = join.sharedRun.participantPeerId
                )
            join.sharedRun.sessionAssociationId != expectedRun.sessionAssociationId ->
                coordinationValidationFailure(
                    reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_SESSION,
                    functionName = "recentAcceptedParticipantJoinOrNull",
                    fieldName = "join.sharedRun.sessionAssociationId",
                    expectedValue = expectedRun.sessionAssociationId,
                    observedValue = join.sharedRun.sessionAssociationId
                )
            else -> null
        }
        if (rejection != null) {
            recordCoordinationValidationRejection(
                context = context,
                isNewObservation = isNewObservation,
                failure = rejection
            )
            return null
        }
        if (isNewObservation) {
            context.coordinationCounters = context.coordinationCounters.recordAccepted()
            clearLastCoordinationValidationFailure(context)
        }
        return join
    }

    private fun coordinationValidationFailure(
        reason: AutomatedDiagnosticsCoordinationRejectionReason,
        functionName: String,
        fieldName: String,
        expectedValue: String,
        observedValue: String
    ): CoordinationValidationFailure {
        return CoordinationValidationFailure(
            reason = reason,
            functionName = functionName,
            fieldName = fieldName,
            expectedValue = expectedValue,
            observedValue = observedValue
        )
    }

    private fun recordCoordinationValidationRejection(
        context: AutomatedDiagnosticsStepContext,
        isNewObservation: Boolean,
        failure: CoordinationValidationFailure
    ) {
        context.lastRejectedFunction = failure.functionName
        context.lastRejectedField = failure.fieldName
        context.lastRejectedExpectedValue = failure.expectedValue
        context.lastRejectedObservedValue = failure.observedValue
        if (isNewObservation) {
            context.coordinationCounters =
                context.coordinationCounters.recordRejected(failure.reason)
        }
    }

    private fun clearLastCoordinationValidationFailure(
        context: AutomatedDiagnosticsStepContext
    ) {
        context.lastRejectedFunction = null
        context.lastRejectedField = null
        context.lastRejectedExpectedValue = null
        context.lastRejectedObservedValue = null
    }

    private fun rememberAcceptedRunAnnouncement(
        context: AutomatedDiagnosticsStepContext,
        announcement: AutomatedDiagnosticsRunAnnouncement,
        observationWallClockMillis: Long,
        observationMonotonicMillis: Long,
        localPeerId: String,
        remotePeerId: String
    ) {
        if (
            context.sharedRun?.runId != announcement.sharedRun.runId ||
            context.runAnnouncementReceivedAtMillis == null
        ) {
            context.runAnnouncementReceivedAtMillis = observationWallClockMillis
            context.runAnnouncementReceivedMonotonicMillis = observationMonotonicMillis
        }
        if (
            context.localProvisionalRunId != null &&
            context.localProvisionalRunId != announcement.sharedRun.runId
        ) {
            context.remoteProvisionalRunId = announcement.sharedRun.runId
        }
        context.sharedCanonicalPeerPair = canonicalPeerPairText(
            AutomatedDiagnosticsCanonicalPeerPair.from(localPeerId, remotePeerId)
        )
    }

    private fun rememberAcceptedParticipantJoin(
        context: AutomatedDiagnosticsStepContext,
        join: AutomatedDiagnosticsParticipantJoin,
        observationWallClockMillis: Long
    ) {
        if (
            context.participantJoinReceivedAtMillis == null ||
            context.remoteProvisionalRunId != join.sharedRun.runId
        ) {
            context.participantJoinReceivedAtMillis = observationWallClockMillis
        }
        context.sharedCanonicalPeerPair = canonicalPeerPairText(
            join.sharedRun.canonicalPeerPair()
        )
    }

    private fun coordinationElapsedMillis(
        stepStartedAtMillis: Long,
        context: AutomatedDiagnosticsStepContext,
        nowMonotonicMillis: Long
    ): Long {
        val timeoutStartedAtMillis =
            context.joinTimeoutStartedMonotonicMillis ?: stepStartedAtMillis
        return (nowMonotonicMillis - timeoutStartedAtMillis).coerceAtLeast(0L)
    }

    private fun resolveSharedRunAuthority(
        localSharedRun: AutomatedDiagnosticsSharedRun,
        remoteAnnouncement: AutomatedDiagnosticsRunAnnouncement,
        context: AutomatedDiagnosticsStepContext,
        nowWallClockMillis: Long
    ): SharedRunAuthorityResolution {
        context.conflictDetected = true
        context.localRoleBeforeConflict = context.localRoleBeforeConflict ?: context.localRole
        context.conflictClassification =
            AutomatedDiagnosticsCoordinationConflictOutcome.SAME_PAIR_PROVISIONAL_CONFLICT
        context.remoteProvisionalRunId = remoteAnnouncement.sharedRun.runId
        context.lastCoordinationTransition =
            AutomatedDiagnosticsCoordinationTransition.CONFLICT_DETECTED

        val localAnnouncementSentAtMillis = context.runAnnouncementSentAtMillis
        val remoteAnnouncementSentAtMillis = remoteAnnouncement.createdAtMillis
        val simultaneousStartDetected =
            localAnnouncementSentAtMillis != null &&
                abs(localAnnouncementSentAtMillis - remoteAnnouncementSentAtMillis) <=
                timingPolicy.sharedRunConflictWindowMillis
        val remoteCoordinatorWins = when {
            localAnnouncementSentAtMillis == null -> true
            simultaneousStartDetected ->
                remoteAnnouncement.sharedRun.coordinatorPeerId <
                    localSharedRun.coordinatorPeerId
            remoteAnnouncementSentAtMillis + timingPolicy.crossDeviceClockSkewToleranceMillis <
                localAnnouncementSentAtMillis -> true
            else -> false
        }

        return if (remoteCoordinatorWins) {
            context.conflictWinnerPeerId = remoteAnnouncement.sharedRun.coordinatorPeerId
            context.conflictClassification =
                AutomatedDiagnosticsCoordinationConflictOutcome.REMOTE_PROVISIONAL_WON_AND_ADOPTED
            context.localRoleAfterConflict = AutomatedDiagnosticsPeerRole.PARTICIPANT
            context.joinTimeoutStartedAtMillis = nowWallClockMillis
            context.joinTimeoutStartedMonotonicMillis = clock.nowMillis()
            SharedRunAuthorityResolution.ADOPT_REMOTE_PROVISIONAL
        } else {
            context.conflictWinnerPeerId = localSharedRun.coordinatorPeerId
            context.conflictClassification =
                AutomatedDiagnosticsCoordinationConflictOutcome.LOCAL_PROVISIONAL_WON
            context.localRoleAfterConflict = context.localRole
            SharedRunAuthorityResolution.RETAIN_LOCAL_PROVISIONAL
        }
    }

    private fun remoteCoordinationEvidence(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        context: AutomatedDiagnosticsStepContext
    ): List<AutomatedDiagnosticEvidenceValue> {
        val sharedRun = context.sharedRun
        val nowMonotonicMillis = clock.nowMillis()
        val announcementObservedMonotonicMillis =
            context.runAnnouncementSentMonotonicMillis
                ?: context.runAnnouncementReceivedMonotonicMillis
        val announcementAgeMillis = announcementObservedMonotonicMillis?.let {
            (nowMonotonicMillis - it).coerceAtLeast(0L)
        }
        val announcementLeaseRemainingMillis = announcementObservedMonotonicMillis?.let {
            (it + timingPolicy.sharedRunAnnouncementLeaseMillis - nowMonotonicMillis)
                .coerceAtLeast(0L)
        }
        val activeLeaseRemainingMillis = context.activeLeaseStartedMonotonicMillis?.let {
            (it + timingPolicy.sharedRunActiveLeaseMillis - nowMonotonicMillis)
                .coerceAtLeast(0L)
        }
        return listOf(
            AutomatedDiagnosticEvidenceValue(
                "Local runner execution id",
                mutableState.value.localRunnerExecutionId ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Runner instance id",
                runnerInstanceId
            ),
            AutomatedDiagnosticEvidenceValue(
                "Automatic participation enabled",
                automaticParticipationEnabled.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Enable call count",
                automaticParticipationEnableCallCount.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Listener generation",
                participantListenerGeneration.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Listener active",
                (participantAutoJoinJob?.isActive == true).toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Listener start count",
                participantListenerStartCount.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Listener cancellation count",
                participantListenerCancellationCount.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Manual start invocation count",
                manualStartInvocationCount.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Participant start invocation count",
                participantStartInvocationCount.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Participant job generation",
                participantJobGeneration.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Run start cause",
                context.runStartCause?.statusText ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Announcement observed count",
                announcementObservedCount.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Announcement claimed count",
                announcementClaimedCount.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Announcement cleared count",
                announcementClearedCount.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Last clear function/cause",
                lastAnnouncementClearCause
            ),
            AutomatedDiagnosticEvidenceValue(
                "Last auto-join blocker",
                lastAutoJoinBlocker
            ),
            AutomatedDiagnosticEvidenceValue(
                "Pending announcement run id",
                pendingParticipantAnnouncement?.announcement?.sharedRun?.runId ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Waiting for RUN_ANNOUNCE",
                (
                    context.localRole == AutomatedDiagnosticsPeerRole.PARTICIPANT &&
                        context.runAnnouncementReceivedAtMillis == null &&
                        context.sharedRun == null
                    ).toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Identity exchange result",
                snapshot.lastIdentityExchangeStatus ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Secure session ready",
                (secureSessionBlocker(
                    snapshot,
                    context.selectedPeer?.identityKey ?: snapshot.activeTransportPeerId.orEmpty()
                ) == null).toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Selected-peer propagation state",
                selectedPeerPropagationState(snapshot)
            ),
            AutomatedDiagnosticEvidenceValue(
                "Local shared run ids generated",
                context.localSharedRunGenerationCount.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Last shared run generation function",
                lastSharedRunGenerationFunction
            ),
            AutomatedDiagnosticEvidenceValue(
                "Local provisional run id",
                context.localProvisionalRunId ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Remote provisional run id",
                context.remoteProvisionalRunId ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Authoritative run id",
                sharedRun?.runId ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue("Shared run id", sharedRun?.runId ?: "none"),
            AutomatedDiagnosticEvidenceValue(
                "Coordinator peer",
                sharedRun?.coordinatorPeerId ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Participant peer",
                sharedRun?.participantPeerId ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Canonical peer pair",
                context.sharedCanonicalPeerPair
                    ?: sharedRun?.let { canonicalPeerPairText(it.canonicalPeerPair()) }
                    ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Session",
                sharedRun?.sessionAssociationId ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Local role before conflict",
                context.localRoleBeforeConflict?.name ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Local role after conflict",
                context.localRoleAfterConflict?.name ?: context.localRole?.name ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Conflict detected",
                context.conflictDetected.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Conflict classification",
                context.conflictClassification?.statusText ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Conflict winner peer",
                context.conflictWinnerPeerId ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Provisional run abandoned",
                context.provisionalRunAbandoned.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Remote run adopted",
                context.remoteRunAdopted.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Participant joined",
                context.participantJoined.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Participant join sent",
                context.participantJoinSent.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "RUN_ANNOUNCE send count",
                runAnnouncementSendCount.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "PARTICIPANT_JOIN attempt count",
                participantJoinSendCount.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "PARTICIPANT_JOIN successful send count",
                participantJoinSuccessfulSendCount.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Announcement age",
                announcementAgeMillis?.toString() ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Announcement lease remaining",
                announcementLeaseRemainingMillis?.toString() ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Active run lease remaining",
                activeLeaseRemainingMillis?.toString() ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Run announcement sent timestamp",
                context.runAnnouncementSentAtMillis?.toString() ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Run announcement received timestamp",
                context.runAnnouncementReceivedAtMillis?.toString() ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Join sent timestamp",
                context.participantJoinSentAtMillis?.toString() ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Last participant join result",
                context.lastParticipantJoinResult ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Join frame createdAtMillis",
                context.lastParticipantJoinFrameCreatedAtMillis?.toString() ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Join frame expiresAtMillis",
                context.lastParticipantJoinFrameExpiresAtMillis?.toString() ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Join expiry minus createdAt",
                context.lastParticipantJoinFrameCreatedAtMillis?.let { createdAtMillis ->
                    context.lastParticipantJoinFrameExpiresAtMillis?.minus(createdAtMillis)
                }?.toString() ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Active lease prepared before send",
                context.lastParticipantJoinLeasePreparedBeforeSend?.toString() ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Join received timestamp",
                context.participantJoinReceivedAtMillis?.toString() ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Join timeout started timestamp",
                context.joinTimeoutStartedAtMillis?.toString() ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Last coordination transition",
                context.lastCoordinationTransition?.statusText ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Coordination status",
                snapshot.lastAutomatedDiagnosticsCoordinationStatus ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Server-ready status",
                snapshot.lastAutomatedDiagnosticsServerReadyStatus ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Coordination messages received",
                context.coordinationCounters.messagesReceived.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Coordination messages accepted",
                context.coordinationCounters.messagesAccepted.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Wrong-run rejected",
                context.coordinationCounters.wrongRunRejected.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Wrong-peer rejected",
                context.coordinationCounters.wrongPeerRejected.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Wrong-session rejected",
                context.coordinationCounters.wrongSessionRejected.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Stale rejected",
                context.coordinationCounters.staleRejected.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Invalid-payload rejected",
                context.coordinationCounters.invalidPayloadRejected.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Last rejected reason",
                context.coordinationCounters.lastRejectedReason?.statusText ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Last rejected function",
                context.lastRejectedFunction ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Last rejected field",
                context.lastRejectedField ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Last rejected expected",
                context.lastRejectedExpectedValue ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Last rejected observed",
                context.lastRejectedObservedValue ?: "none"
            )
        )
    }

    private fun canonicalPeerPairText(
        canonicalPeerPair: AutomatedDiagnosticsCanonicalPeerPair
    ): String {
        return "${canonicalPeerPair.lowerPeerId} | ${canonicalPeerPair.higherPeerId}"
    }

    private enum class SharedRunAuthorityResolution {
        RETAIN_LOCAL_PROVISIONAL,
        ADOPT_REMOTE_PROVISIONAL
    }

    private fun runAnnouncementSignature(
        announcement: AutomatedDiagnosticsRunAnnouncement
    ): String {
        return listOf(
            announcement.sharedRun.runId,
            announcement.sharedRun.coordinatorPeerId,
            announcement.sharedRun.participantPeerId,
            announcement.sharedRun.sessionAssociationId,
            announcement.sharedRun.createdAtMillis.toString(),
            announcement.sharedRun.expiresAtMillis.toString(),
            announcement.peerId,
            announcement.createdAtMillis.toString()
        ).joinToString(separator = "|")
    }

    private fun participantJoinSignature(
        join: AutomatedDiagnosticsParticipantJoin
    ): String {
        return listOf(
            join.sharedRun.runId,
            join.sharedRun.coordinatorPeerId,
            join.sharedRun.participantPeerId,
            join.sharedRun.sessionAssociationId,
            join.sharedRun.expiresAtMillis.toString(),
            join.peerId,
            join.createdAtMillis.toString()
        ).joinToString(separator = "|")
    }

    private fun serverReadySignalSignature(
        signal: AutomatedDiagnosticsServerReadySignal
    ): String {
        return listOf(
            signal.sharedRun.runId,
            signal.sharedRun.coordinatorPeerId,
            signal.sharedRun.participantPeerId,
            signal.sharedRun.sessionAssociationId,
            signal.peerId,
            signal.expectedClientPeerId,
            signal.groupOwnerAddress,
            signal.socketPort.toString(),
            signal.serverToken.toString(),
            signal.createdAtMillis.toString(),
            signal.expiresAtMillis.toString()
        ).joinToString(separator = "|")
    }

    private fun wifiDirectPeerReadySignalSignature(
        signal: AutomatedDiagnosticsWifiDirectPeerReadySignal
    ): String {
        return listOf(
            signal.sharedRun.runId,
            signal.sharedRun.coordinatorPeerId,
            signal.sharedRun.participantPeerId,
            signal.sharedRun.sessionAssociationId,
            signal.peerId,
            signal.expectedRemotePeerId,
            signal.wifiDirectCorrelationToken,
            signal.wifiDirectDeviceName ?: "none",
            signal.createdAtMillis.toString(),
            signal.expiresAtMillis.toString()
        ).joinToString(separator = "|")
    }

    private fun currentRunStartedAtMillis(): Long {
        return mutableState.value.startedAtMillis ?: 0L
    }

    private fun refreshAggregateState() {
        updateRunState { current ->
            val passed = current.steps.count { it.status == AutomatedDiagnosticStepStatus.PASS }
            val failed = current.steps.count { it.status == AutomatedDiagnosticStepStatus.FAIL }
            val blocked = current.steps.count { it.status == AutomatedDiagnosticStepStatus.BLOCKED }
            val cancelled = current.steps.count { it.status == AutomatedDiagnosticStepStatus.CANCELLED }
            current.copy(
                passedCount = passed,
                failedCount = failed,
                blockedCount = blocked,
                cancelledCount = cancelled,
                reportText = automatedDiagnosticsPlainTextReport(
                    overallStatus = current.overallStatus,
                    selectedPeerId = current.selectedPeerId,
                    localPeerRole = current.localPeerRole,
                    localRunnerExecutionId = current.localRunnerExecutionId,
                    sharedRunId = current.sharedRunId,
                    sharedRunCoordinatorPeerId = current.sharedRunCoordinatorPeerId,
                    sharedRunParticipantPeerId = current.sharedRunParticipantPeerId,
                    sharedRunSessionAssociationId = current.sharedRunSessionAssociationId,
                    sharedRunCreatedAtMillis = current.sharedRunCreatedAtMillis,
                    sharedRunExpiresAtMillis = current.sharedRunExpiresAtMillis,
                    sharedRunCanonicalPeerPair = current.sharedRunCanonicalPeerPair,
                    elapsedMillis = current.elapsedMillis,
                    steps = current.steps,
                    phaseTwoSummary = current.phaseTwoSummary
                )
            )
        }
    }

    private fun resetStepsFrom(startIndex: Int) {
        updateRunState { current ->
            val updatedSteps = current.steps.mapIndexed { index, step ->
                if (index < startIndex && step.status == AutomatedDiagnosticStepStatus.PASS) {
                    step
                } else {
                    AutomatedDiagnosticStepResult(step.stepId)
                }
            }
            current.copy(
                overallStatus = AutomatedDiagnosticsOverallStatus.IDLE,
                currentStepNumber = null,
                completedAtMillis = null,
                elapsedMillis = 0L,
                steps = updatedSteps
            )
        }
        refreshAggregateState()
    }

    private fun restoreStepContextForStartIndex(
        startIndex: Int
    ): AutomatedDiagnosticsStepContext {
        if (startIndex <= AutomatedDiagnosticStepId.AURORA_PEER_DISCOVERY.ordinal) {
            return AutomatedDiagnosticsStepContext()
        }
        val snapshot = bindings.snapshot()
        val selectedPeerId = mutableState.value.selectedPeerId
        val selectedPeer = selectedPeerId?.let { peerId ->
            selectedPeerForIdentityKey(snapshot.discoveredAuroraPeers, peerId)
        }
        val selectedWifiDirectPeer = if (
            startIndex >= AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP.ordinal
        ) {
            snapshot.wifiDirectRuntimeStatus.connectionStatus.targetPeer
        } else {
            null
        }
        val sharedRun = if (
            mutableState.value.sharedRunId != null &&
            mutableState.value.sharedRunCoordinatorPeerId != null &&
            mutableState.value.sharedRunParticipantPeerId != null &&
            mutableState.value.sharedRunSessionAssociationId != null &&
            mutableState.value.sharedRunCreatedAtMillis != null &&
            mutableState.value.sharedRunExpiresAtMillis != null
        ) {
            AutomatedDiagnosticsSharedRun(
                runId = requireNotNull(mutableState.value.sharedRunId),
                coordinatorPeerId = requireNotNull(mutableState.value.sharedRunCoordinatorPeerId),
                participantPeerId = requireNotNull(mutableState.value.sharedRunParticipantPeerId),
                sessionAssociationId = requireNotNull(mutableState.value.sharedRunSessionAssociationId),
                createdAtMillis = requireNotNull(mutableState.value.sharedRunCreatedAtMillis),
                expiresAtMillis = requireNotNull(mutableState.value.sharedRunExpiresAtMillis)
            )
        } else {
            null
        }
        val stepElevenProvenance = wifiDirectGroupProvenanceOrNone(
            stepEvidenceValue(
                AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                "Group provenance"
            )
        )
        return AutomatedDiagnosticsStepContext(
            selectedPeer = selectedPeer,
            localRole = mutableState.value.localPeerRole,
            localRoleAfterConflict = mutableState.value.localPeerRole,
            selectedWifiDirectPeer = selectedWifiDirectPeer,
            selectedWifiDirectPeerSource = stepEvidenceValue(
                AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                "Selected peer source"
            )?.takeUnless { it == "none" },
            sharedRun = sharedRun,
            sharedCanonicalPeerPair = sharedRun?.let { canonicalPeerPairText(it.canonicalPeerPair()) },
            conflictAuthorityPeerId = if (
                snapshot.localPeerId != null && selectedPeerId != null
            ) {
                listOf(snapshot.localPeerId, selectedPeerId).sorted().first()
            } else {
                null
            },
            participantJoined = sharedRun != null &&
                startIndex > AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION.ordinal,
            participantJoinSent = sharedRun != null &&
                mutableState.value.localPeerRole == AutomatedDiagnosticsPeerRole.PARTICIPANT &&
                startIndex >= AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP.ordinal,
            wifiDirectBaselineEstablished =
                stepEvidenceValue(
                    AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                    "Fresh baseline established"
                ) == "true" ||
                    stepElevenProvenance ==
                    AutomatedDiagnosticsWifiDirectGroupProvenance.CURRENT_RUN_VALIDATED,
            wifiDirectCurrentRunTokenProofReady =
                stepEvidenceValue(
                    AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                    "Current-run token proof ready"
                ) == "true" ||
                    stepElevenProvenance ==
                    AutomatedDiagnosticsWifiDirectGroupProvenance.CURRENT_RUN_VALIDATED,
            wifiDirectCurrentRunDnsSdProofReady =
                stepEvidenceValue(
                    AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                    "Current-run DNS-SD proof ready"
                ) == "true" ||
                    stepElevenProvenance ==
                    AutomatedDiagnosticsWifiDirectGroupProvenance.CURRENT_RUN_VALIDATED,
            wifiDirectCurrentRunValidatedPeerProofReady =
                stepEvidenceValue(
                    AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                    "Current-run validated-peer proof ready"
                ) == "true" ||
                    stepElevenProvenance ==
                    AutomatedDiagnosticsWifiDirectGroupProvenance.CURRENT_RUN_VALIDATED,
            wifiDirectCurrentRunConnectProofReady =
                stepEvidenceValue(
                    AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                    "Current-run connect proof ready"
                ) == "true" ||
                    stepElevenProvenance ==
                    AutomatedDiagnosticsWifiDirectGroupProvenance.CURRENT_RUN_VALIDATED,
            wifiDirectGroupObservedAfterCurrentRunProof =
                stepEvidenceValue(
                    AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                    "Group observed after current-run proof"
                ) == "true" ||
                    stepElevenProvenance ==
                    AutomatedDiagnosticsWifiDirectGroupProvenance.CURRENT_RUN_VALIDATED,
            wifiDirectGroupProvenance = stepElevenProvenance,
            wifiDirectConnectInvocationCount =
                stepEvidenceValue(
                    AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                    "Wi-Fi Direct connect invocation count"
                )?.toIntOrNull() ?: 0
        )
    }

    private fun markCancelled() {
        updateRunState { current ->
            val updatedSteps = current.steps.map { step ->
                if (step.status == AutomatedDiagnosticStepStatus.RUNNING) {
                    step.copy(
                        status = AutomatedDiagnosticStepStatus.CANCELLED,
                        completedAtMillis = clock.nowMillis(),
                        elapsedMillis = step.startedAtMillis?.let { clock.nowMillis() - it } ?: step.elapsedMillis,
                        blockerOrFailure = "Stopped by user."
                    )
                } else {
                    step
                }
            }
            current.copy(
                overallStatus = AutomatedDiagnosticsOverallStatus.CANCELLED,
                currentStepNumber = null,
                completedAtMillis = clock.nowMillis(),
                elapsedMillis = current.startedAtMillis?.let { clock.nowMillis() - it } ?: current.elapsedMillis,
                sharedRunId = null,
                sharedRunCoordinatorPeerId = null,
                sharedRunParticipantPeerId = null,
                sharedRunSessionAssociationId = null,
                sharedRunCreatedAtMillis = null,
                sharedRunExpiresAtMillis = null,
                sharedRunCanonicalPeerPair = null,
                steps = updatedSteps
            )
        }
        refreshAggregateState()
    }

    private fun blockRemainingSteps(
        startIndex: Int,
        blockerReason: String
    ) {
        updateRunState { current ->
            val updatedSteps = current.steps.mapIndexed { index, step ->
                if (index >= startIndex && step.status == AutomatedDiagnosticStepStatus.WAITING) {
                    step.copy(
                        status = AutomatedDiagnosticStepStatus.BLOCKED,
                        summary = "Blocked by an earlier prerequisite",
                        blockerOrFailure = blockerReason
                    )
                } else {
                    step
                }
            }
            current.copy(steps = updatedSteps)
        }
    }

    private fun setStepRunning(
        stepId: AutomatedDiagnosticStepId,
        retryCount: Int,
        summary: String,
        startedAtMillis: Long = clock.nowMillis()
    ) {
        updateStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.RUNNING,
            startedAtMillis = startedAtMillis,
            elapsedMillis = 0L,
            retryCount = retryCount,
            summary = summary,
            blocker = null,
            evidence = emptyList(),
            waitingProgressText = null,
            stabilizationProgressText = null,
            technicalDetails = emptyList(),
            requiredAction = null
        )
    }

    private fun completeStep(
        stepId: AutomatedDiagnosticStepId,
        status: AutomatedDiagnosticStepStatus,
        summary: String,
        blocker: String? = null,
        evidence: List<AutomatedDiagnosticEvidenceValue> = emptyList(),
        startedAtMillis: Long? = stepResult(stepId).startedAtMillis,
        technicalDetails: List<String> = emptyList(),
        requiredAction: AutomatedDiagnosticsRequiredAction? = null
    ): AutomatedDiagnosticStepStatus {
        val completedAt = clock.nowMillis()
        updateStep(
            stepId = stepId,
            status = status,
            startedAtMillis = startedAtMillis,
            completedAtMillis = completedAt,
            elapsedMillis = startedAtMillis?.let { completedAt - it } ?: 0L,
            summary = summary,
            blocker = blocker,
            evidence = evidence,
            waitingProgressText = null,
            stabilizationProgressText = null,
            technicalDetails = technicalDetails,
            requiredAction = requiredAction
        )
        return status
    }

    private fun stepResult(stepId: AutomatedDiagnosticStepId): AutomatedDiagnosticStepResult {
        return mutableState.value.steps.first { it.stepId == stepId }
    }

    private fun stepEvidenceValue(
        stepId: AutomatedDiagnosticStepId,
        label: String
    ): String? {
        return stepResult(stepId).evidenceValues.firstOrNull { it.label == label }?.value
    }

    private fun wifiDirectGroupProvenanceOrNone(
        rawValue: String?
    ): AutomatedDiagnosticsWifiDirectGroupProvenance {
        return runCatching {
            rawValue?.let(AutomatedDiagnosticsWifiDirectGroupProvenance::valueOf)
        }.getOrNull() ?: AutomatedDiagnosticsWifiDirectGroupProvenance.NONE
    }

    private fun updateStep(
        stepId: AutomatedDiagnosticStepId,
        status: AutomatedDiagnosticStepStatus,
        startedAtMillis: Long? = stepResult(stepId).startedAtMillis,
        completedAtMillis: Long? = stepResult(stepId).completedAtMillis,
        elapsedMillis: Long = stepResult(stepId).elapsedMillis,
        retryCount: Int = stepResult(stepId).retryCount,
        summary: String = stepResult(stepId).summary,
        blocker: String? = stepResult(stepId).blockerOrFailure,
        evidence: List<AutomatedDiagnosticEvidenceValue> = stepResult(stepId).evidenceValues,
        waitingProgressText: String? = stepResult(stepId).waitingProgressText,
        stabilizationProgressText: String? = stepResult(stepId).stabilizationProgressText,
        technicalDetails: List<String> = stepResult(stepId).technicalDetails,
        requiredAction: AutomatedDiagnosticsRequiredAction? = stepResult(stepId).requiredAction
    ) {
        updateRunState { current ->
            val updatedSteps = current.steps.map { step ->
                if (step.stepId == stepId) {
                    step.copy(
                        status = status,
                        startedAtMillis = startedAtMillis,
                        completedAtMillis = completedAtMillis,
                        elapsedMillis = elapsedMillis,
                        retryCount = retryCount,
                        summary = summary,
                        blockerOrFailure = blocker,
                        requiredAction = requiredAction,
                        evidenceValues = evidence,
                        waitingProgressText = waitingProgressText,
                        stabilizationProgressText = stabilizationProgressText,
                        technicalDetails = technicalDetails
                    )
                } else {
                    step
                }
            }
            current.copy(
                currentStepNumber = stepId.stepNumber,
                elapsedMillis = current.startedAtMillis?.let { clock.nowMillis() - it } ?: current.elapsedMillis,
                steps = updatedSteps
            )
        }
        refreshAggregateState()
    }

    private fun updateRunState(
        transform: (AutomatedDiagnosticsRunState) -> AutomatedDiagnosticsRunState
    ) {
        mutableState.value = transform(mutableState.value)
    }

    private data class AutomatedDiagnosticsStepContext(
        var selectedPeer: SelectedAutomatedDiagnosticsPeer? = null,
        var localRole: AutomatedDiagnosticsPeerRole? = null,
        var runStartCause: AutomatedDiagnosticsRunStartCause? = null,
        var localRoleBeforeConflict: AutomatedDiagnosticsPeerRole? = null,
        var localRoleAfterConflict: AutomatedDiagnosticsPeerRole? = null,
        var selectedWifiDirectPeer: WifiDirectPeer? = null,
        var selectedWifiDirectPeerSource: String? = null,
        var sharedRun: AutomatedDiagnosticsSharedRun? = null,
        var localPendingSharedRun: AutomatedDiagnosticsSharedRun? = null,
        var localProvisionalRunId: String? = null,
        var remoteProvisionalRunId: String? = null,
        var localSharedRunGenerationCount: Int = 0,
        var sharedCanonicalPeerPair: String? = null,
        var conflictAuthorityPeerId: String? = null,
        var conflictDetected: Boolean = false,
        var conflictClassification: AutomatedDiagnosticsCoordinationConflictOutcome? = null,
        var conflictWinnerPeerId: String? = null,
        var provisionalRunAbandoned: Boolean = false,
        var remoteRunAdopted: Boolean = false,
        var participantJoined: Boolean = false,
        var participantJoinSent: Boolean = false,
        var runAnnouncementSentAtMillis: Long? = null,
        var runAnnouncementReceivedAtMillis: Long? = null,
        var runAnnouncementSentMonotonicMillis: Long? = null,
        var runAnnouncementReceivedMonotonicMillis: Long? = null,
        var participantJoinSentAtMillis: Long? = null,
        var participantJoinReceivedAtMillis: Long? = null,
        var lastParticipantJoinResult: String? = null,
        var lastParticipantJoinFrameCreatedAtMillis: Long? = null,
        var lastParticipantJoinFrameExpiresAtMillis: Long? = null,
        var lastParticipantJoinLeasePreparedBeforeSend: Boolean? = null,
        var joinTimeoutStartedAtMillis: Long? = null,
        var joinTimeoutStartedMonotonicMillis: Long? = null,
        var activeLeaseStartedAtMillis: Long? = null,
        var activeLeaseStartedMonotonicMillis: Long? = null,
        var lastCoordinationTransition: AutomatedDiagnosticsCoordinationTransition? = null,
        var acceptedWifiDirectPeerReadySignal: AutomatedDiagnosticsWifiDirectPeerReadySignal? = null,
        var wifiDirectCorrelationToken: String? = null,
        var initialWifiDirectConnectionState: WifiDirectConnectionState? = null,
        var initialWifiDirectGroupFormed: WifiDirectGroupFormedState? = null,
        var initialWifiDirectRole: WifiDirectConnectionRole? = null,
        var initialWifiDirectSocketState: WifiDirectSocketState? = null,
        var preExistingWifiDirectGroupDetected: Boolean = false,
        var preExistingWifiDirectSocketDetected: Boolean = false,
        var wifiDirectBaselineDisconnectRequested: Boolean = false,
        var wifiDirectBaselineDisconnectRequestCount: Int = 0,
        var wifiDirectBaselineSocketCleanupRequested: Boolean = false,
        var wifiDirectBaselineEstablished: Boolean = false,
        var wifiDirectBaselineEstablishedAtMillis: Long? = null,
        var wifiDirectDnsSdRegisteredCorrelationToken: String? = null,
        var wifiDirectCurrentRunTokenProofReady: Boolean = false,
        var wifiDirectCurrentRunDnsSdProofReady: Boolean = false,
        var wifiDirectCurrentRunValidatedPeerProofReady: Boolean = false,
        var wifiDirectCurrentRunConnectProofReady: Boolean = false,
        var wifiDirectGroupObservedAfterCurrentRunProof: Boolean = false,
        var wifiDirectGroupObservedAfterCurrentRunProofAtMillis: Long? = null,
        var wifiDirectGroupProvenance:
        AutomatedDiagnosticsWifiDirectGroupProvenance =
            AutomatedDiagnosticsWifiDirectGroupProvenance.NONE,
        var wifiDirectPeerReadySendAttempts: Int = 0,
        var wifiDirectPeerReadySuccessfulSends: Int = 0,
        var wifiDirectPeerReadyReceivedCount: Int = 0,
        var wifiDirectPeerReadyAcceptedCount: Int = 0,
        var lastWifiDirectDnsSdServiceRegistrationAtMillis: Long? = null,
        var lastWifiDirectDnsSdDiscoveryStartAtMillis: Long? = null,
        var wifiDirectPeerReadyValidationCounters: AutomatedDiagnosticsCoordinationCounters =
            AutomatedDiagnosticsCoordinationCounters(),
        var wifiDirectPeerReadyLastRejectedReason: AutomatedDiagnosticsCoordinationRejectionReason? =
            null,
        var wifiDirectPeerReadyLastRejectedField: String? = null,
        var wifiDirectPeerReadyLastRejectedExpectedValue: String? = null,
        var wifiDirectPeerReadyLastRejectedObservedValue: String? = null,
        var wifiDirectConnectInvocationCount: Int = 0,
        var wifiDirectConnectTarget: String? = null,
        var serverStartRequestCount: Int = 0,
        var serverStartRequestAtMillis: Long? = null,
        var serverStartRequestHost: String? = null,
        var serverReadySentCount: Int = 0,
        var serverReadyReceivedCount: Int = 0,
        var serverReadyAcceptedCount: Int = 0,
        var serverReadyLastRejectedReason: AutomatedDiagnosticsCoordinationRejectionReason? = null,
        var clientConnectRequestCount: Int = 0,
        var clientConnectRequestAtMillis: Long? = null,
        var clientConnectRequestHost: String? = null,
        var coordinationCounters: AutomatedDiagnosticsCoordinationCounters =
            AutomatedDiagnosticsCoordinationCounters(),
        var lastRejectedFunction: String? = null,
        var lastRejectedField: String? = null,
        var lastRejectedExpectedValue: String? = null,
        var lastRejectedObservedValue: String? = null,
        private var lastObservedRunAnnouncementSignature: String? = null,
        private var lastObservedParticipantJoinSignature: String? = null,
        private var lastObservedWifiDirectPeerReadySignature: String? = null,
        internal var lastObservedWifiDirectPeerReadyObservedAtMonotonicMillis: Long? = null,
        private var lastObservedServerReadySignature: String? = null,
        internal var lastObservedServerReadyObservedAtMonotonicMillis: Long? = null
    ) {
        fun mergeFrom(
            override: AutomatedDiagnosticsStepContext?
        ): AutomatedDiagnosticsStepContext {
            if (override == null) {
                return this
            }
            if (override.selectedPeer != null) {
                selectedPeer = override.selectedPeer
            }
            if (override.localRole != null) {
                localRole = override.localRole
            }
            if (override.runStartCause != null) {
                runStartCause = override.runStartCause
            }
            if (override.localRoleBeforeConflict != null) {
                localRoleBeforeConflict = override.localRoleBeforeConflict
            }
            if (override.localRoleAfterConflict != null) {
                localRoleAfterConflict = override.localRoleAfterConflict
            }
            if (override.selectedWifiDirectPeer != null) {
                selectedWifiDirectPeer = override.selectedWifiDirectPeer
            }
            if (override.selectedWifiDirectPeerSource != null) {
                selectedWifiDirectPeerSource = override.selectedWifiDirectPeerSource
            }
            if (override.sharedRun != null) {
                sharedRun = override.sharedRun
            }
            if (override.localPendingSharedRun != null) {
                localPendingSharedRun = override.localPendingSharedRun
            }
            if (override.localProvisionalRunId != null) {
                localProvisionalRunId = override.localProvisionalRunId
            }
            if (override.remoteProvisionalRunId != null) {
                remoteProvisionalRunId = override.remoteProvisionalRunId
            }
            localSharedRunGenerationCount = maxOf(
                localSharedRunGenerationCount,
                override.localSharedRunGenerationCount
            )
            if (override.sharedCanonicalPeerPair != null) {
                sharedCanonicalPeerPair = override.sharedCanonicalPeerPair
            }
            if (override.conflictAuthorityPeerId != null) {
                conflictAuthorityPeerId = override.conflictAuthorityPeerId
            }
            conflictDetected = conflictDetected || override.conflictDetected
            if (override.conflictClassification != null) {
                conflictClassification = override.conflictClassification
            }
            if (override.conflictWinnerPeerId != null) {
                conflictWinnerPeerId = override.conflictWinnerPeerId
            }
            provisionalRunAbandoned = provisionalRunAbandoned || override.provisionalRunAbandoned
            remoteRunAdopted = remoteRunAdopted || override.remoteRunAdopted
            participantJoined = participantJoined || override.participantJoined
            participantJoinSent = participantJoinSent || override.participantJoinSent
            if (override.runAnnouncementSentAtMillis != null) {
                runAnnouncementSentAtMillis = override.runAnnouncementSentAtMillis
            }
            if (override.runAnnouncementReceivedAtMillis != null) {
                runAnnouncementReceivedAtMillis = override.runAnnouncementReceivedAtMillis
            }
            if (override.runAnnouncementSentMonotonicMillis != null) {
                runAnnouncementSentMonotonicMillis = override.runAnnouncementSentMonotonicMillis
            }
            if (override.runAnnouncementReceivedMonotonicMillis != null) {
                runAnnouncementReceivedMonotonicMillis =
                    override.runAnnouncementReceivedMonotonicMillis
            }
            if (override.participantJoinSentAtMillis != null) {
                participantJoinSentAtMillis = override.participantJoinSentAtMillis
            }
            if (override.participantJoinReceivedAtMillis != null) {
                participantJoinReceivedAtMillis = override.participantJoinReceivedAtMillis
            }
            if (override.lastParticipantJoinResult != null) {
                lastParticipantJoinResult = override.lastParticipantJoinResult
            }
            if (override.lastParticipantJoinFrameCreatedAtMillis != null) {
                lastParticipantJoinFrameCreatedAtMillis =
                    override.lastParticipantJoinFrameCreatedAtMillis
            }
            if (override.lastParticipantJoinFrameExpiresAtMillis != null) {
                lastParticipantJoinFrameExpiresAtMillis =
                    override.lastParticipantJoinFrameExpiresAtMillis
            }
            if (override.lastParticipantJoinLeasePreparedBeforeSend != null) {
                lastParticipantJoinLeasePreparedBeforeSend =
                    override.lastParticipantJoinLeasePreparedBeforeSend
            }
            if (override.joinTimeoutStartedAtMillis != null) {
                joinTimeoutStartedAtMillis = override.joinTimeoutStartedAtMillis
            }
            if (override.joinTimeoutStartedMonotonicMillis != null) {
                joinTimeoutStartedMonotonicMillis = override.joinTimeoutStartedMonotonicMillis
            }
            if (override.activeLeaseStartedAtMillis != null) {
                activeLeaseStartedAtMillis = override.activeLeaseStartedAtMillis
            }
            if (override.activeLeaseStartedMonotonicMillis != null) {
                activeLeaseStartedMonotonicMillis = override.activeLeaseStartedMonotonicMillis
            }
            if (override.lastCoordinationTransition != null) {
                lastCoordinationTransition = override.lastCoordinationTransition
            }
            if (override.acceptedWifiDirectPeerReadySignal != null) {
                acceptedWifiDirectPeerReadySignal = override.acceptedWifiDirectPeerReadySignal
            }
            if (override.wifiDirectCorrelationToken != null) {
                wifiDirectCorrelationToken = override.wifiDirectCorrelationToken
            }
            if (override.initialWifiDirectConnectionState != null) {
                initialWifiDirectConnectionState = override.initialWifiDirectConnectionState
            }
            if (override.initialWifiDirectGroupFormed != null) {
                initialWifiDirectGroupFormed = override.initialWifiDirectGroupFormed
            }
            if (override.initialWifiDirectRole != null) {
                initialWifiDirectRole = override.initialWifiDirectRole
            }
            if (override.initialWifiDirectSocketState != null) {
                initialWifiDirectSocketState = override.initialWifiDirectSocketState
            }
            preExistingWifiDirectGroupDetected =
                preExistingWifiDirectGroupDetected || override.preExistingWifiDirectGroupDetected
            preExistingWifiDirectSocketDetected =
                preExistingWifiDirectSocketDetected || override.preExistingWifiDirectSocketDetected
            wifiDirectBaselineDisconnectRequested =
                wifiDirectBaselineDisconnectRequested ||
                    override.wifiDirectBaselineDisconnectRequested
            wifiDirectBaselineSocketCleanupRequested =
                wifiDirectBaselineSocketCleanupRequested ||
                    override.wifiDirectBaselineSocketCleanupRequested
            wifiDirectBaselineEstablished =
                wifiDirectBaselineEstablished || override.wifiDirectBaselineEstablished
            wifiDirectBaselineDisconnectRequestCount = maxOf(
                wifiDirectBaselineDisconnectRequestCount,
                override.wifiDirectBaselineDisconnectRequestCount
            )
            if (override.wifiDirectBaselineEstablishedAtMillis != null) {
                wifiDirectBaselineEstablishedAtMillis =
                    override.wifiDirectBaselineEstablishedAtMillis
            }
            if (override.wifiDirectDnsSdRegisteredCorrelationToken != null) {
                wifiDirectDnsSdRegisteredCorrelationToken =
                    override.wifiDirectDnsSdRegisteredCorrelationToken
            }
            wifiDirectCurrentRunTokenProofReady =
                wifiDirectCurrentRunTokenProofReady || override.wifiDirectCurrentRunTokenProofReady
            wifiDirectCurrentRunDnsSdProofReady =
                wifiDirectCurrentRunDnsSdProofReady || override.wifiDirectCurrentRunDnsSdProofReady
            wifiDirectCurrentRunValidatedPeerProofReady =
                wifiDirectCurrentRunValidatedPeerProofReady ||
                    override.wifiDirectCurrentRunValidatedPeerProofReady
            wifiDirectCurrentRunConnectProofReady =
                wifiDirectCurrentRunConnectProofReady ||
                    override.wifiDirectCurrentRunConnectProofReady
            wifiDirectGroupObservedAfterCurrentRunProof =
                wifiDirectGroupObservedAfterCurrentRunProof ||
                    override.wifiDirectGroupObservedAfterCurrentRunProof
            if (override.wifiDirectGroupObservedAfterCurrentRunProofAtMillis != null) {
                wifiDirectGroupObservedAfterCurrentRunProofAtMillis =
                    override.wifiDirectGroupObservedAfterCurrentRunProofAtMillis
            }
            if (
                override.wifiDirectGroupProvenance !=
                AutomatedDiagnosticsWifiDirectGroupProvenance.NONE
            ) {
                wifiDirectGroupProvenance = override.wifiDirectGroupProvenance
            }
            wifiDirectPeerReadySendAttempts = maxOf(
                wifiDirectPeerReadySendAttempts,
                override.wifiDirectPeerReadySendAttempts
            )
            wifiDirectPeerReadySuccessfulSends = maxOf(
                wifiDirectPeerReadySuccessfulSends,
                override.wifiDirectPeerReadySuccessfulSends
            )
            wifiDirectPeerReadyReceivedCount = maxOf(
                wifiDirectPeerReadyReceivedCount,
                override.wifiDirectPeerReadyReceivedCount
            )
            wifiDirectPeerReadyAcceptedCount = maxOf(
                wifiDirectPeerReadyAcceptedCount,
                override.wifiDirectPeerReadyAcceptedCount
            )
            if (override.lastWifiDirectDnsSdServiceRegistrationAtMillis != null) {
                lastWifiDirectDnsSdServiceRegistrationAtMillis =
                    override.lastWifiDirectDnsSdServiceRegistrationAtMillis
            }
            if (override.lastWifiDirectDnsSdDiscoveryStartAtMillis != null) {
                lastWifiDirectDnsSdDiscoveryStartAtMillis =
                    override.lastWifiDirectDnsSdDiscoveryStartAtMillis
            }
            wifiDirectPeerReadyValidationCounters =
                override.wifiDirectPeerReadyValidationCounters.takeIf {
                    it != AutomatedDiagnosticsCoordinationCounters()
                } ?: wifiDirectPeerReadyValidationCounters
            if (override.wifiDirectPeerReadyLastRejectedReason != null) {
                wifiDirectPeerReadyLastRejectedReason =
                    override.wifiDirectPeerReadyLastRejectedReason
            }
            if (override.wifiDirectPeerReadyLastRejectedField != null) {
                wifiDirectPeerReadyLastRejectedField =
                    override.wifiDirectPeerReadyLastRejectedField
            }
            if (override.wifiDirectPeerReadyLastRejectedExpectedValue != null) {
                wifiDirectPeerReadyLastRejectedExpectedValue =
                    override.wifiDirectPeerReadyLastRejectedExpectedValue
            }
            if (override.wifiDirectPeerReadyLastRejectedObservedValue != null) {
                wifiDirectPeerReadyLastRejectedObservedValue =
                    override.wifiDirectPeerReadyLastRejectedObservedValue
            }
            wifiDirectConnectInvocationCount = maxOf(
                wifiDirectConnectInvocationCount,
                override.wifiDirectConnectInvocationCount
            )
            if (override.wifiDirectConnectTarget != null) {
                wifiDirectConnectTarget = override.wifiDirectConnectTarget
            }
            serverStartRequestCount = maxOf(
                serverStartRequestCount,
                override.serverStartRequestCount
            )
            if (override.serverStartRequestAtMillis != null) {
                serverStartRequestAtMillis = override.serverStartRequestAtMillis
            }
            if (override.serverStartRequestHost != null) {
                serverStartRequestHost = override.serverStartRequestHost
            }
            serverReadySentCount = maxOf(serverReadySentCount, override.serverReadySentCount)
            serverReadyReceivedCount = maxOf(
                serverReadyReceivedCount,
                override.serverReadyReceivedCount
            )
            serverReadyAcceptedCount = maxOf(
                serverReadyAcceptedCount,
                override.serverReadyAcceptedCount
            )
            if (override.serverReadyLastRejectedReason != null) {
                serverReadyLastRejectedReason = override.serverReadyLastRejectedReason
            }
            clientConnectRequestCount = maxOf(
                clientConnectRequestCount,
                override.clientConnectRequestCount
            )
            if (override.clientConnectRequestAtMillis != null) {
                clientConnectRequestAtMillis = override.clientConnectRequestAtMillis
            }
            if (override.clientConnectRequestHost != null) {
                clientConnectRequestHost = override.clientConnectRequestHost
            }
            coordinationCounters = override.coordinationCounters.takeIf {
                it != AutomatedDiagnosticsCoordinationCounters()
            } ?: coordinationCounters
            if (override.lastRejectedFunction != null) {
                lastRejectedFunction = override.lastRejectedFunction
            }
            if (override.lastRejectedField != null) {
                lastRejectedField = override.lastRejectedField
            }
            if (override.lastRejectedExpectedValue != null) {
                lastRejectedExpectedValue = override.lastRejectedExpectedValue
            }
            if (override.lastRejectedObservedValue != null) {
                lastRejectedObservedValue = override.lastRejectedObservedValue
            }
            return this
        }

        fun markAnnouncementObserved(
            signature: String
        ): Boolean {
            if (lastObservedRunAnnouncementSignature == signature) {
                return false
            }
            lastObservedRunAnnouncementSignature = signature
            return true
        }

        fun markParticipantJoinObserved(
            signature: String
        ): Boolean {
            if (lastObservedParticipantJoinSignature == signature) {
                return false
            }
            lastObservedParticipantJoinSignature = signature
            return true
        }

        fun markWifiDirectPeerReadyObserved(
            signature: String,
            observedAtMonotonicMillis: Long
        ): Boolean {
            if (lastObservedWifiDirectPeerReadySignature == signature) {
                return false
            }
            lastObservedWifiDirectPeerReadySignature = signature
            lastObservedWifiDirectPeerReadyObservedAtMonotonicMillis =
                observedAtMonotonicMillis
            return true
        }

        fun markServerReadyObserved(
            signature: String,
            observedAtMonotonicMillis: Long
        ): Boolean {
            if (lastObservedServerReadySignature == signature) {
                return false
            }
            lastObservedServerReadySignature = signature
            lastObservedServerReadyObservedAtMonotonicMillis =
                observedAtMonotonicMillis
            return true
        }
    }

    private enum class AutomatedDiagnosticsRunStartCause(
        val statusText: String
    ) {
        MANUAL_START("MANUAL_START"),
        AUTOMATIC_PARTICIPANT_JOIN("AUTOMATIC_PARTICIPANT_JOIN"),
        REMOTE_PROVISIONAL_ADOPTED("REMOTE_PROVISIONAL_ADOPTED")
    }

    private data class ParticipantAutoJoinSeed(
        val seededState: AutomatedDiagnosticsRunState,
        val context: AutomatedDiagnosticsStepContext,
        val startIndex: Int
    )

    internal data class ListenerDiagnosticsSnapshot(
        val runnerInstanceId: String,
        val automaticParticipationEnabled: Boolean,
        val enableCallCount: Int,
        val listenerGeneration: Int,
        val listenerActive: Boolean,
        val listenerStartCount: Int,
        val listenerCancellationCount: Int,
        val manualStartInvocationCount: Int,
        val participantStartInvocationCount: Int,
        val participantJobGeneration: Int,
        val announcementObservedCount: Int,
        val announcementClaimedCount: Int,
        val announcementClearedCount: Int,
        val runAnnouncementSendCount: Int,
        val participantJoinSendCount: Int,
        val participantJoinSuccessfulSendCount: Int,
        val pendingAnnouncementRunId: String?,
        val lastAnnouncementClearCause: String,
        val lastAutoJoinBlocker: String
    )

    private data class PendingParticipantAnnouncement(
        val announcement: AutomatedDiagnosticsRunAnnouncement,
        val selectedPeer: SelectedAutomatedDiagnosticsPeer,
        val sessionAssociationId: String,
        val observedWallClockMillis: Long,
        val observedMonotonicMillis: Long,
        val context: AutomatedDiagnosticsStepContext
    )

    private data class CoordinationValidationFailure(
        val reason: AutomatedDiagnosticsCoordinationRejectionReason,
        val functionName: String,
        val fieldName: String,
        val expectedValue: String,
        val observedValue: String
    )

    private data class AutomatedDiagnosticsBlocker(
        val message: String,
        val requiredAction: AutomatedDiagnosticsRequiredAction? = null
    ) {
        init {
            require(message.isNotBlank()) {
                "Automated diagnostics blocker message must not be blank."
            }
        }
    }

    private data class SelectedAutomatedDiagnosticsPeer(
        val device: BleDiscoveredDevice,
        val identityKey: String,
        val displayName: String
    )
}
