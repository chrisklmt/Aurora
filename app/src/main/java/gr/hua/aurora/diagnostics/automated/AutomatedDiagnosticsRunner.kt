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
import gr.hua.aurora.transport.hybrid.HybridTransportControlStore
import gr.hua.aurora.protocol.GlobalMeshDeliveryResult
import gr.hua.aurora.protocol.PeerIdentityExchangeSendResult
import gr.hua.aurora.protocol.PrivateChatMessageSendResult
import gr.hua.aurora.protocol.canonicalPeerIdFor
import gr.hua.aurora.protocol.hasSessionForPeer
import gr.hua.aurora.state.AuroraAvailabilityPreference
import gr.hua.aurora.state.GlobalQueuedChatSubmissionResult
import gr.hua.aurora.state.PrivateQueuedChatSubmissionResult
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
import gr.hua.aurora.wifidirect.socket.wifiDirectDebugSocketPort
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
    private data class PhaseThreeCleanupResult(
        val attemptedIds: Set<String>,
        val removedIds: Set<String>
    ) {
        val attemptedCount: Int
            get() = attemptedIds.size

        val removedCount: Int
            get() = removedIds.size

        val remainingIds: Set<String>
            get() = attemptedIds - removedIds

        val remainingCount: Int
            get() = remainingIds.size

        val completed: Boolean
            get() = remainingIds.isEmpty()
    }

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
    private val capturedPhaseThreeMessageIds = linkedSetOf<String>()

    val state: StateFlow<AutomatedDiagnosticsRunState> = mutableState.asStateFlow()

    internal fun currentPreparationState(): AutomatedDiagnosticsPreparationState {
        return automatedDiagnosticsPreparationState(bindings.snapshot())
    }

    internal fun automaticPreparationPending(): Boolean {
        val current = mutableState.value
        return pendingParticipantAnnouncement != null &&
            runJob?.isActive != true &&
            current.overallStatus == AutomatedDiagnosticsOverallStatus.IDLE &&
            current.localPeerRole == AutomatedDiagnosticsPeerRole.PARTICIPANT &&
            current.sharedRunId != null
    }

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
        removeCapturedPhaseThreeMessages()
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
        cause: String,
        syncIdleState: Boolean = true
    ) {
        if (pendingParticipantAnnouncement != null) {
            announcementClearedCount += 1
        }
        pendingParticipantAnnouncement = null
        lastAnnouncementClearCause = cause
        if (syncIdleState) {
            syncPendingParticipantAnnouncementState()
        }
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
        clearPendingParticipantAnnouncement(
            cause = "launchParticipantRun",
            syncIdleState = false
        )
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
            removeCapturedPhaseThreeMessages()
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
                    runPhaseSynchronizedStep(stepId, context) {
                        runWifiDirectDiscoveryAndGroupStep(stepId, context)
                    }
                AutomatedDiagnosticStepId.WIFI_DIRECT_SOCKET ->
                    runPhaseSynchronizedStep(stepId, context) {
                        runWifiDirectSocketStep(stepId, context)
                    }
                AutomatedDiagnosticStepId.WIFI_DIRECT_BRIDGES ->
                    runPhaseSynchronizedStep(stepId, context) {
                        runWifiDirectBridgesStep(stepId, context)
                    }
                AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_OFFER ->
                    runPhaseSynchronizedStep(stepId, context) {
                        runHybridBootstrapOfferStep(stepId, context)
                    }
                AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_ACCEPT ->
                    runPhaseSynchronizedStep(stepId, context) {
                        runHybridBootstrapAcceptStep(stepId, context)
                    }
                AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT ->
                    runPhaseSynchronizedStep(stepId, context) {
                        runHybridBootstrapSocketHintStep(stepId, context)
                    }
                AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_TRIGGER ->
                    runPhaseSynchronizedStep(stepId, context) {
                        runHybridBootstrapTriggerStep(stepId, context)
                    }
                AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE ->
                    runPhaseSynchronizedStep(stepId, context) {
                        runGlobalMessageProbeStep(stepId, context)
                    }
                AutomatedDiagnosticStepId.PRIVATE_ENCRYPTED_MESSAGE_PROBE ->
                    runPhaseSynchronizedStep(stepId, context) {
                        runPrivateEncryptedMessageProbeStep(stepId, context)
                    }
                AutomatedDiagnosticStepId.REVERSE_DIRECTION_MESSAGING_PROBE ->
                    runPhaseSynchronizedStep(stepId, context) {
                        runReverseDirectionMessagingProbeStep(stepId, context)
                    }
                AutomatedDiagnosticStepId.FINAL_END_TO_END_VALIDATION ->
                    runPhaseSynchronizedStep(stepId, context) {
                        runFinalEndToEndValidationStep(stepId, context)
                    }
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

    private suspend fun runPhaseSynchronizedStep(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext,
        operation: suspend () -> AutomatedDiagnosticStepStatus
    ): AutomatedDiagnosticStepStatus {
        phaseSynchronizedLocalPreBarrierStatusOrNull(stepId, context)?.let { status ->
            return status
        }
        val sharedRun = context.sharedRun ?: return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.BLOCKED,
            summary = "${stepId.title} blocked",
            blocker = "Shared diagnostics run is unavailable."
        )
        val initialSnapshot = bindings.snapshot()
        val localPeerId = initialSnapshot.localPeerId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return completeStep(
                stepId = stepId,
                status = AutomatedDiagnosticStepStatus.BLOCKED,
                summary = "${stepId.title} blocked",
                blocker = "Local peer identity is unavailable."
            )
        val remotePeerId = otherSharedRunPeerId(sharedRun, localPeerId)
            ?: return completeStep(
                stepId = stepId,
                status = AutomatedDiagnosticStepStatus.BLOCKED,
                summary = "${stepId.title} blocked",
                blocker = "Shared diagnostics run does not include the local peer identity."
            )
        val attemptNumber = context.beginPhaseAttempt(
            stepId = stepId,
            startedAtMonotonicMillis = clock.nowMillis()
        )
        val barrierStatus = awaitPhaseBarrier(
            stepId = stepId,
            context = context,
            sharedRun = sharedRun,
            localPeerId = localPeerId,
            remotePeerId = remotePeerId,
            attemptNumber = attemptNumber
        )
        if (barrierStatus != null) {
            appendPhaseBarrierEvidenceToCurrentStep(stepId, context)
            return barrierStatus
        }
        requestAutomatedDiagnosticsPhaseState(
            stepId = stepId,
            context = context,
            sharedRun = sharedRun,
            remotePeerId = remotePeerId,
            phaseState = AutomatedDiagnosticsPhaseState.RUNNING,
            attemptNumber = attemptNumber
        )
        context.currentPhaseOperationalStartedAtMillis = clock.nowMillis()
        val result = operation()
        val terminalState = when (result) {
            AutomatedDiagnosticStepStatus.PASS -> AutomatedDiagnosticsPhaseState.PASS
            AutomatedDiagnosticStepStatus.FAIL -> AutomatedDiagnosticsPhaseState.FAIL
            AutomatedDiagnosticStepStatus.BLOCKED -> AutomatedDiagnosticsPhaseState.BLOCKED
            AutomatedDiagnosticStepStatus.CANCELLED -> AutomatedDiagnosticsPhaseState.CANCELLED
            AutomatedDiagnosticStepStatus.RUNNING,
            AutomatedDiagnosticStepStatus.WAITING,
            AutomatedDiagnosticStepStatus.SKIPPED ->
                null
        }
        val remoteTerminalPassAlreadyObservedBeforeLocalSend =
            terminalState == AutomatedDiagnosticsPhaseState.PASS &&
                context.currentPhaseObservedRemoteSignal?.phaseState ==
                AutomatedDiagnosticsPhaseState.PASS
        val completedStepAfterOperation = if (terminalState != null) {
            stepResult(stepId)
        } else {
            null
        }
        if (terminalState != null) {
            requestAutomatedDiagnosticsPhaseState(
                stepId = stepId,
                context = context,
                sharedRun = sharedRun,
                remotePeerId = remotePeerId,
                phaseState = terminalState,
                attemptNumber = attemptNumber,
                applicationProbeDescriptors = context.currentPhaseApplicationProbeDescriptors
            )
            if (terminalState == AutomatedDiagnosticsPhaseState.PASS) {
                val terminalHandoffStatus = awaitTerminalPhasePassHandoffIfNeeded(
                    stepId = stepId,
                    context = context,
                    sharedRun = sharedRun,
                    localPeerId = localPeerId,
                    remotePeerId = remotePeerId,
                    attemptNumber = attemptNumber,
                    remoteTerminalPassAlreadyObservedBeforeLocalSend =
                    remoteTerminalPassAlreadyObservedBeforeLocalSend
                )
                if (terminalHandoffStatus != null) {
                    appendPhaseBarrierEvidenceToCurrentStep(stepId, context)
                    return terminalHandoffStatus
                }
                completedStepAfterOperation?.let { completedStep ->
                    updateStep(
                        stepId = stepId,
                        status = completedStep.status,
                        startedAtMillis = completedStep.startedAtMillis,
                        completedAtMillis = completedStep.completedAtMillis,
                        elapsedMillis = completedStep.elapsedMillis,
                        retryCount = completedStep.retryCount,
                        summary = completedStep.summary,
                        blocker = completedStep.blockerOrFailure,
                        evidence = completedStep.evidenceValues,
                        waitingProgressText = completedStep.waitingProgressText,
                        stabilizationProgressText = completedStep.stabilizationProgressText,
                        technicalDetails = completedStep.technicalDetails,
                        requiredAction = completedStep.requiredAction
                    )
                }
            }
        }
        appendPhaseBarrierEvidenceToCurrentStep(stepId, context)
        return result
    }

    private fun phaseSynchronizedLocalPreBarrierStatusOrNull(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext
    ): AutomatedDiagnosticStepStatus? {
        val snapshot = bindings.snapshot()
        return when (stepId) {
            AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP -> {
                val requirement = wifiDirectReadinessRequirement(snapshot) ?: return null
                completeStep(
                    stepId = stepId,
                    status = AutomatedDiagnosticStepStatus.BLOCKED,
                    summary = "Wi-Fi Direct discovery blocked",
                    blocker = requirement.message,
                    evidence = wifiDirectGroupEvidence(
                        snapshot = snapshot,
                        selectedPeer = context.selectedWifiDirectPeer,
                        context = context
                    ),
                    requiredAction = requirement.requiredAction
                )
            }

            AutomatedDiagnosticStepId.WIFI_DIRECT_SOCKET -> {
                if (!wifiDirectGroupReady(snapshot)) {
                    completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.BLOCKED,
                        summary = "Wi-Fi Direct socket blocked",
                        blocker = "Wi-Fi Direct group is not ready.",
                        evidence = wifiDirectSocketEvidence(
                            snapshot = snapshot,
                            context = context,
                            minimumCreatedAtMillis = currentRunStartedAtMillis()
                        )
                    )
                } else if (
                    context.wifiDirectGroupProvenance !=
                    AutomatedDiagnosticsWifiDirectGroupProvenance.CURRENT_RUN_VALIDATED
                ) {
                    completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.BLOCKED,
                        summary = "Wi-Fi Direct socket blocked",
                        blocker = "Step 11 did not establish current-run Wi-Fi Direct group provenance.",
                        evidence = wifiDirectSocketEvidence(
                            snapshot = snapshot,
                            context = context,
                            minimumCreatedAtMillis = currentRunStartedAtMillis()
                        )
                    )
                } else {
                    null
                }
            }

            AutomatedDiagnosticStepId.WIFI_DIRECT_BRIDGES -> {
                if (!wifiDirectSocketReady(snapshot)) {
                    completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.BLOCKED,
                        summary = "Wi-Fi Direct bridges blocked",
                        blocker = wifiDirectSocketBlocker(snapshot)
                            ?: "Wi-Fi Direct socket is not ready."
                    )
                } else {
                    null
                }
            }

            else -> null
        }
    }

    private suspend fun awaitPhaseBarrier(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext,
        sharedRun: AutomatedDiagnosticsSharedRun,
        localPeerId: String,
        remotePeerId: String,
        attemptNumber: Int
    ): AutomatedDiagnosticStepStatus? {
        val startedAt = clock.nowMillis()
        var lastPhaseRequestAtMillis: Long? = null
        while (currentCoroutineContext().isActive) {
            val now = clock.nowMillis()
            val elapsed = now - startedAt
            val snapshot = bindings.snapshot()
            val remoteSignal = recentAcceptedAutomatedDiagnosticsPhaseSignalOrNull(
                snapshot = snapshot,
                expectedRun = sharedRun,
                expectedSenderPeerId = remotePeerId,
                expectedRecipientPeerId = localPeerId,
                expectedStepId = stepId,
                expectedAttemptNumber = attemptNumber,
                context = context
            )
            when (remoteSignal?.phaseState) {
                AutomatedDiagnosticsPhaseState.READY,
                AutomatedDiagnosticsPhaseState.RUNNING,
                AutomatedDiagnosticsPhaseState.PASS -> {
                    context.currentPhaseObservedRemoteSignal = remoteSignal
                    context.currentPhaseBarrierEstablished = true
                    if (context.currentPhaseBarrierEstablishedAtMillis == null) {
                        context.currentPhaseBarrierEstablishedAtMillis = now
                    }
                    return null
                }

                AutomatedDiagnosticsPhaseState.FAIL -> {
                    return completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.FAIL,
                        summary = "${stepId.title} failed",
                        blocker = "Remote device reported ${stepId.title.lowercase()} FAIL.",
                        evidence = phaseBarrierEvidence(stepId, context)
                    )
                }

                AutomatedDiagnosticsPhaseState.BLOCKED -> {
                    return completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.BLOCKED,
                        summary = "${stepId.title} blocked",
                        blocker = "Remote device reported ${stepId.title.lowercase()} BLOCKED.",
                        evidence = phaseBarrierEvidence(stepId, context)
                    )
                }

                AutomatedDiagnosticsPhaseState.CANCELLED -> {
                    return completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.CANCELLED,
                        summary = "${stepId.title} cancelled",
                        blocker = "Remote device cancelled during ${stepId.title.lowercase()}.",
                        evidence = phaseBarrierEvidence(stepId, context)
                    )
                }

                null -> Unit
            }

            if (
                lastPhaseRequestAtMillis == null ||
                now - lastPhaseRequestAtMillis >= timingPolicy.automatedDiagnosticsPhaseStateRefreshMillis
            ) {
                requestAutomatedDiagnosticsPhaseState(
                    stepId = stepId,
                    context = context,
                    sharedRun = sharedRun,
                    remotePeerId = remotePeerId,
                    phaseState = AutomatedDiagnosticsPhaseState.READY,
                    attemptNumber = attemptNumber
                )
                lastPhaseRequestAtMillis = now
            }

            updateStep(
                stepId = stepId,
                status = AutomatedDiagnosticStepStatus.RUNNING,
                startedAtMillis = startedAt,
                elapsedMillis = elapsed,
                retryCount = stepResult(stepId).retryCount,
                summary = "Waiting for synchronized ${stepId.title.lowercase()} phase",
                blocker = phaseBarrierBlocker(stepId, context),
                evidence = phaseBarrierEvidence(stepId, context),
                waitingProgressText =
                "Waiting ${formatAutomatedDiagnosticsDuration(elapsed)} / ${formatAutomatedDiagnosticsDuration(timingPolicy.phaseBarrierSync.timeoutMillis)}",
                stabilizationProgressText = null
            )
            if (elapsed >= timingPolicy.phaseBarrierSync.timeoutMillis) {
                return completeStep(
                    stepId = stepId,
                    status = AutomatedDiagnosticStepStatus.BLOCKED,
                    summary = "${stepId.title} blocked",
                    blocker = phaseBarrierBlocker(stepId, context)
                        ?: "Timed out waiting for synchronized ${stepId.title.lowercase()} phase.",
                    evidence = phaseBarrierEvidence(stepId, context),
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
            evidence = phaseBarrierEvidence(stepId, context),
            startedAtMillis = startedAt
        )
    }

    private suspend fun requestAutomatedDiagnosticsPhaseState(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext,
        sharedRun: AutomatedDiagnosticsSharedRun,
        remotePeerId: String,
        phaseState: AutomatedDiagnosticsPhaseState,
        attemptNumber: Int,
        applicationProbeDescriptors: List<AutomatedDiagnosticsPhaseApplicationProbeDescriptor> =
            emptyList()
    ): AutomatedDiagnosticsPhaseStateSendResult {
        context.currentPhaseLocalState = phaseState
        context.currentPhaseSendCount += 1
        val result = bindings.commands.requestAutomatedDiagnosticsPhaseState(
            sharedRun,
            remotePeerId,
            stepId,
            phaseState,
            attemptNumber,
            applicationProbeDescriptors
        )
        context.currentPhaseLastLocalSendStatus =
            automatedDiagnosticsPhaseStateSendStatusText(result)
        return result
    }

    private suspend fun awaitTerminalPhasePassHandoffIfNeeded(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext,
        sharedRun: AutomatedDiagnosticsSharedRun,
        localPeerId: String,
        remotePeerId: String,
        attemptNumber: Int,
        remoteTerminalPassAlreadyObservedBeforeLocalSend: Boolean
    ): AutomatedDiagnosticStepStatus? {
        if (stepId !in automatedDiagnosticsApplicationProbeStepIds) {
            return null
        }
        val nextStepId = AutomatedDiagnosticStepId.entries.getOrNull(stepId.ordinal + 1)
        val startedAt = clock.nowMillis()
        var lastTerminalPassRequestAtMillis: Long? = startedAt
        while (currentCoroutineContext().isActive) {
            val now = clock.nowMillis()
            val elapsed = now - startedAt
            val snapshot = bindings.snapshot()
            val remoteCurrentSignal = recentAcceptedAutomatedDiagnosticsPhaseSignalOrNull(
                snapshot = snapshot,
                expectedRun = sharedRun,
                expectedSenderPeerId = remotePeerId,
                expectedRecipientPeerId = localPeerId,
                expectedStepId = stepId,
                expectedAttemptNumber = attemptNumber,
                context = context
            )
            when (remoteCurrentSignal?.phaseState) {
                AutomatedDiagnosticsPhaseState.FAIL -> {
                    return completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.FAIL,
                        summary = "${stepId.title} failed",
                        blocker = "Remote device reported ${stepId.title.lowercase()} FAIL during terminal handoff.",
                        evidence = phaseBarrierEvidence(stepId, context)
                    )
                }

                AutomatedDiagnosticsPhaseState.BLOCKED -> {
                    return completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.BLOCKED,
                        summary = "${stepId.title} blocked",
                        blocker = "Remote device reported ${stepId.title.lowercase()} BLOCKED during terminal handoff.",
                        evidence = phaseBarrierEvidence(stepId, context)
                    )
                }

                AutomatedDiagnosticsPhaseState.CANCELLED -> {
                    return completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.CANCELLED,
                        summary = "${stepId.title} cancelled",
                        blocker = "Remote device cancelled during ${stepId.title.lowercase()} terminal handoff.",
                        evidence = phaseBarrierEvidence(stepId, context)
                    )
                }

                else -> Unit
            }
            val remoteNextStepSignal = nextStepId?.let { expectedNextStepId ->
                recentAcceptedAutomatedDiagnosticsPhaseSignalOrNull(
                    snapshot = snapshot,
                    expectedRun = sharedRun,
                    expectedSenderPeerId = remotePeerId,
                    expectedRecipientPeerId = localPeerId,
                    expectedStepId = expectedNextStepId,
                    expectedAttemptNumber = 1,
                    context = context,
                    allowLatestFallback = false
                )
            }
            val handoffComplete = if (remoteTerminalPassAlreadyObservedBeforeLocalSend) {
                remoteNextStepSignal != null
            } else {
                remoteCurrentSignal?.phaseState == AutomatedDiagnosticsPhaseState.PASS
            }
            if (handoffComplete) {
                return null
            }
            if (
                lastTerminalPassRequestAtMillis == null ||
                now - lastTerminalPassRequestAtMillis >=
                timingPolicy.automatedDiagnosticsPhaseStateRefreshMillis
            ) {
                requestAutomatedDiagnosticsPhaseState(
                    stepId = stepId,
                    context = context,
                    sharedRun = sharedRun,
                    remotePeerId = remotePeerId,
                    phaseState = AutomatedDiagnosticsPhaseState.PASS,
                    attemptNumber = attemptNumber,
                    applicationProbeDescriptors = context.currentPhaseApplicationProbeDescriptors
                )
                lastTerminalPassRequestAtMillis = now
            }
            updateStep(
                stepId = stepId,
                status = AutomatedDiagnosticStepStatus.RUNNING,
                startedAtMillis = startedAt,
                elapsedMillis = elapsed,
                retryCount = stepResult(stepId).retryCount,
                summary = "Completing ${stepId.title.lowercase()} handoff",
                blocker = terminalPhasePassHandoffBlocker(
                    stepId = stepId,
                    nextStepId = nextStepId,
                    remoteTerminalPassAlreadyObservedBeforeLocalSend =
                    remoteTerminalPassAlreadyObservedBeforeLocalSend
                ),
                evidence = phaseBarrierEvidence(stepId, context),
                waitingProgressText =
                "Waiting ${formatAutomatedDiagnosticsDuration(elapsed)} / ${formatAutomatedDiagnosticsDuration(timingPolicy.sharedRunCoordination.timeoutMillis)}",
                stabilizationProgressText = null
            )
            if (elapsed >= timingPolicy.sharedRunCoordination.timeoutMillis) {
                val timeoutBlocker = if (!remoteTerminalPassAlreadyObservedBeforeLocalSend) {
                    "Timed out waiting for remote terminal PASS for Step ${stepId.stepNumber} before entering Step ${nextStepId?.stepNumber ?: stepId.stepNumber + 1}."
                } else {
                    "Timed out waiting for the remote device to observe Step ${stepId.stepNumber} PASS and begin Step ${nextStepId?.stepNumber ?: stepId.stepNumber + 1}."
                }
                return completeStep(
                    stepId = stepId,
                    status = AutomatedDiagnosticStepStatus.BLOCKED,
                    summary = "${stepId.title} blocked",
                    blocker = timeoutBlocker,
                    evidence = phaseBarrierEvidence(stepId, context),
                    startedAtMillis = startedAt
                )
            }
            delay.delayMillis(timingPolicy.pollIntervalMillis)
        }
        return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.CANCELLED,
            summary = "Cancelled",
            blocker = "Automated diagnostics were cancelled during terminal handoff.",
            evidence = phaseBarrierEvidence(stepId, context),
            startedAtMillis = startedAt
        )
    }

    private fun terminalPhasePassHandoffBlocker(
        stepId: AutomatedDiagnosticStepId,
        nextStepId: AutomatedDiagnosticStepId?,
        remoteTerminalPassAlreadyObservedBeforeLocalSend: Boolean
    ): String {
        return if (!remoteTerminalPassAlreadyObservedBeforeLocalSend) {
            "Waiting for remote terminal PASS for Step ${stepId.stepNumber} before entering Step ${nextStepId?.stepNumber ?: stepId.stepNumber + 1}."
        } else {
            "Waiting for the remote device to observe Step ${stepId.stepNumber} PASS and begin Step ${nextStepId?.stepNumber ?: stepId.stepNumber + 1}."
        }
    }

    private fun phaseBarrierBlocker(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext
    ): String? {
        val remoteSignal = context.currentPhaseObservedRemoteSignal
        return when {
            context.currentPhaseBarrierEstablished ->
                null
            remoteSignal == null &&
                context.currentPhaseLastRejectedReason != null ->
                "Waiting for a fresh BLE phase state for Step ${stepId.stepNumber}. Last rejection: ${context.currentPhaseLastRejectedReason?.statusText}."
            remoteSignal == null ->
                "Waiting for the remote device to reach Step ${stepId.stepNumber} over BLE control."
            remoteSignal.phaseState == AutomatedDiagnosticsPhaseState.READY ->
                "Waiting for the BLE phase barrier to stabilize."
            else ->
                "Waiting for synchronized BLE phase control."
        }
    }

    private fun phaseBarrierEvidence(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext
    ): List<AutomatedDiagnosticEvidenceValue> {
        val remoteSignal = context.currentPhaseObservedRemoteSignal
        val remoteObservationAge = context.lastObservedPhaseSignalObservedAtMonotonicMillis?.let { observedAt ->
            (clock.nowMillis() - observedAt).coerceAtLeast(0L).toString()
        } ?: "none"
        val operationalElapsed = context.currentPhaseOperationalStartedAtMillis?.let { startedAt ->
            (clock.nowMillis() - startedAt).coerceAtLeast(0L).toString()
        } ?: "none"
        return listOf(
            AutomatedDiagnosticEvidenceValue("Local phase", stepId.stepNumber.toString()),
            AutomatedDiagnosticEvidenceValue(
                "Local phase state",
                context.currentPhaseLocalState?.statusText ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Remote phase",
                remoteSignal?.stepId?.stepNumber?.toString() ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Remote phase state",
                remoteSignal?.phaseState?.statusText ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Phase attempt number",
                context.currentPhaseAttemptNumber.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Barrier established",
                context.currentPhaseBarrierEstablished.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Barrier established timestamp",
                context.currentPhaseBarrierEstablishedAtMillis?.toString() ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Phase-state send count",
                context.currentPhaseSendCount.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Phase-state receive count",
                context.currentPhaseReceiveCount.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Phase-state accepted count",
                context.currentPhaseAcceptedCount.toString()
            ),
            AutomatedDiagnosticEvidenceValue(
                "Last phase-state rejection",
                context.currentPhaseLastRejectedReason?.statusText ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Remote state observation age",
                remoteObservationAge
            ),
            AutomatedDiagnosticEvidenceValue(
                "Operational timeout started",
                context.currentPhaseOperationalStartedAtMillis?.toString() ?: "none"
            ),
            AutomatedDiagnosticEvidenceValue(
                "Operational timeout elapsed",
                operationalElapsed
            ),
            AutomatedDiagnosticEvidenceValue(
                "Phase-state send status",
                context.currentPhaseLastLocalSendStatus ?: "none"
            )
        )
    }

    private fun appendPhaseBarrierEvidenceToCurrentStep(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext
    ) {
        val currentStep = stepResult(stepId)
        updateStep(
            stepId = stepId,
            status = currentStep.status,
            startedAtMillis = currentStep.startedAtMillis,
            completedAtMillis = currentStep.completedAtMillis,
            elapsedMillis = currentStep.elapsedMillis,
            retryCount = currentStep.retryCount,
            summary = currentStep.summary,
            blocker = currentStep.blockerOrFailure,
            evidence = mergeEvidenceValues(
                currentStep.evidenceValues,
                phaseBarrierEvidence(stepId, context)
            ),
            waitingProgressText = currentStep.waitingProgressText,
            stabilizationProgressText = currentStep.stabilizationProgressText,
            technicalDetails = currentStep.technicalDetails,
            requiredAction = currentStep.requiredAction
        )
    }

    private fun mergeEvidenceValues(
        existing: List<AutomatedDiagnosticEvidenceValue>,
        updates: List<AutomatedDiagnosticEvidenceValue>
    ): List<AutomatedDiagnosticEvidenceValue> {
        val mergedByLabel = linkedMapOf<String, AutomatedDiagnosticEvidenceValue>()
        existing.forEach { value ->
            mergedByLabel[value.label] = value
        }
        updates.forEach { value ->
            mergedByLabel[value.label] = value
        }
        return mergedByLabel.values.toList()
    }

    private suspend fun runWifiDirectDiscoveryAndGroupStep(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext
    ): AutomatedDiagnosticStepStatus {
        resetWifiDirectDiscoveryAttemptState(context)
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
                updateWifiDirectCurrentRunProofs(
                    localRole = localRole,
                    snapshot = snapshot,
                    acceptedRemoteSignal = acceptedRemoteSignal,
                    matchingDnsSdResponses = matchingDnsSdResponses,
                    context = context
                )

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
                    context.wifiDirectCurrentRunValidatedPeerProofReady = true
                    context.wifiDirectConnectTarget = wifiDirectPeerEvidenceText(matchedPeer)
                }

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
                updateWifiDirectCurrentRunProofs(
                    localRole = localRole,
                    snapshot = snapshot,
                    acceptedRemoteSignal = acceptedRemoteSignal,
                    matchingDnsSdResponses = matchingDnsSdResponses,
                    context = context
                )
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
                if (
                    localRole == AutomatedDiagnosticsPeerRole.COORDINATOR &&
                    visibleValidatedPeer != null &&
                    context.selectedWifiDirectPeerSource == validatedDnsSdTokenPeerSource
                ) {
                    context.wifiDirectCurrentRunValidatedPeerProofReady = true
                }
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
        val snapshot = bindings.snapshot()
        val sharedRun = context.sharedRun ?: return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.BLOCKED,
            summary = "Hybrid bootstrap accept blocked",
            blocker = "Shared diagnostics run is unavailable."
        )
        val localPeerId = snapshot.localPeerId?.trim()?.takeIf { it.isNotEmpty() } ?: return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.BLOCKED,
            summary = "Hybrid bootstrap accept blocked",
            blocker = "Local Aurora peer id is unavailable."
        )
        val remotePeerId = otherSharedRunPeerId(sharedRun, localPeerId) ?: return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.BLOCKED,
            summary = "Hybrid bootstrap accept blocked",
            blocker = "Remote Aurora peer id is unavailable for the shared diagnostics run."
        )
        val expectedSessionId = sharedRun.coordinatorPeerId
        context.beginHybridAcceptAttempt(
            expectedSessionId = expectedSessionId,
            startedAtMonotonicMillis = clock.nowMillis()
        )
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
                successEvidence = { currentSnapshot ->
                    hybridBootstrapAcceptEvidence(currentSnapshot, context)
                },
                isSatisfied = { currentSnapshot ->
                    currentSnapshot.hybridBootstrapManualAcceptAvailable
                }
            )
            if (readiness != AutomatedDiagnosticStepStatus.PASS) {
                return readiness
            }
            val attemptNumber = context.currentPhaseAttemptNumber
            val startedAt = clock.nowMillis()
            var lastAcceptRefreshAtMillis: Long? = null
            setStepRunning(
                stepId = stepId,
                retryCount = stepResult(stepId).retryCount,
                summary = "Waiting for coordinator confirmation of hybrid bootstrap accept",
                startedAtMillis = startedAt
            )
            while (currentCoroutineContext().isActive) {
                val now = clock.nowMillis()
                val currentSnapshot = bindings.snapshot()
                val elapsed = now - startedAt
                val remoteSignal = recentAcceptedAutomatedDiagnosticsPhaseSignalOrNull(
                    snapshot = currentSnapshot,
                    expectedRun = sharedRun,
                    expectedSenderPeerId = remotePeerId,
                    expectedRecipientPeerId = localPeerId,
                    expectedStepId = stepId,
                    expectedAttemptNumber = attemptNumber,
                    context = context
                )
                when (remoteSignal?.phaseState) {
                    AutomatedDiagnosticsPhaseState.PASS -> {
                        return completeStep(
                            stepId = stepId,
                            status = AutomatedDiagnosticStepStatus.PASS,
                            summary = "Hybrid bootstrap accept confirmed by coordinator",
                            evidence = hybridBootstrapAcceptEvidence(currentSnapshot, context),
                            startedAtMillis = startedAt
                        )
                    }
                    AutomatedDiagnosticsPhaseState.FAIL -> {
                        return completeStep(
                            stepId = stepId,
                            status = AutomatedDiagnosticStepStatus.FAIL,
                            summary = "Hybrid bootstrap accept failed",
                            blocker = "Coordinator reported Hybrid bootstrap accept FAIL.",
                            evidence = hybridBootstrapAcceptEvidence(currentSnapshot, context),
                            startedAtMillis = startedAt
                        )
                    }
                    AutomatedDiagnosticsPhaseState.BLOCKED -> {
                        return completeStep(
                            stepId = stepId,
                            status = AutomatedDiagnosticStepStatus.BLOCKED,
                            summary = "Hybrid bootstrap accept blocked",
                            blocker = "Coordinator reported Hybrid bootstrap accept BLOCKED.",
                            evidence = hybridBootstrapAcceptEvidence(currentSnapshot, context),
                            startedAtMillis = startedAt
                        )
                    }
                    AutomatedDiagnosticsPhaseState.CANCELLED -> {
                        return completeStep(
                            stepId = stepId,
                            status = AutomatedDiagnosticStepStatus.CANCELLED,
                            summary = "Hybrid bootstrap accept cancelled",
                            blocker = "Coordinator cancelled during Hybrid bootstrap accept.",
                            evidence = hybridBootstrapAcceptEvidence(currentSnapshot, context),
                            startedAtMillis = startedAt
                        )
                    }
                    AutomatedDiagnosticsPhaseState.READY,
                    AutomatedDiagnosticsPhaseState.RUNNING,
                    null -> Unit
                }
                if (
                    currentSnapshot.hybridBootstrapManualAcceptAvailable &&
                    (
                        lastAcceptRefreshAtMillis == null ||
                            now - lastAcceptRefreshAtMillis >=
                            timingPolicy.automatedDiagnosticsPhaseStateRefreshMillis
                        )
                ) {
                    context.hybridAcceptSendAttemptCount += 1
                    when (val result = bindings.commands.requestHybridBootstrapManualAccept()) {
                        is HybridBootstrapManualAcceptSendResult.Sent -> {
                            if (result.peerId != remotePeerId) {
                                context.hybridAcceptLastSendResult = "wrong-peer:${result.peerId}"
                                return completeStep(
                                    stepId = stepId,
                                    status = AutomatedDiagnosticStepStatus.FAIL,
                                    summary = "Hybrid bootstrap accept failed",
                                    blocker =
                                        "Hybrid bootstrap accept refresh targeted ${result.peerId} instead of $remotePeerId.",
                                    evidence = hybridBootstrapAcceptEvidence(currentSnapshot, context),
                                    startedAtMillis = startedAt
                                )
                            }
                            if (result.sessionId != expectedSessionId) {
                                context.hybridAcceptLastSendResult =
                                    "wrong-session:${result.sessionId}"
                                return completeStep(
                                    stepId = stepId,
                                    status = AutomatedDiagnosticStepStatus.FAIL,
                                    summary = "Hybrid bootstrap accept failed",
                                    blocker =
                                        "Hybrid bootstrap accept refresh targeted session ${result.sessionId} instead of $expectedSessionId.",
                                    evidence = hybridBootstrapAcceptEvidence(currentSnapshot, context),
                                    startedAtMillis = startedAt
                                )
                            }
                            context.hybridAcceptSuccessfulSendCount += 1
                            context.hybridAcceptLastSendResult = "sent"
                            context.hybridAcceptLastSentPeerId = result.peerId
                            context.hybridAcceptLastSentSessionId = result.sessionId
                            context.hybridAcceptLastSentAtMonotonicMillis = now
                            lastAcceptRefreshAtMillis = now
                        }
                        HybridBootstrapManualAcceptSendResult.NoOfferCandidate -> {
                            context.hybridAcceptLastSendResult = "no-offer-candidate"
                            return completeStep(
                                stepId = stepId,
                                status = AutomatedDiagnosticStepStatus.BLOCKED,
                                summary = "Hybrid bootstrap accept blocked",
                                blocker = "No recent hybrid bootstrap offer candidate is available.",
                                evidence = hybridBootstrapAcceptEvidence(currentSnapshot, context),
                                startedAtMillis = startedAt
                            )
                        }
                        HybridBootstrapManualAcceptSendResult.NoActivePeer -> {
                            context.hybridAcceptLastSendResult = "no-active-peer"
                            return completeStep(
                                stepId = stepId,
                                status = AutomatedDiagnosticStepStatus.BLOCKED,
                                summary = "Hybrid bootstrap accept blocked",
                                blocker = "No active BLE peer is connected.",
                                evidence = hybridBootstrapAcceptEvidence(currentSnapshot, context),
                                startedAtMillis = startedAt
                            )
                        }
                        HybridBootstrapManualAcceptSendResult.NoActiveSession -> {
                            context.hybridAcceptLastSendResult = "no-active-session"
                            return completeStep(
                                stepId = stepId,
                                status = AutomatedDiagnosticStepStatus.BLOCKED,
                                summary = "Hybrid bootstrap accept blocked",
                                blocker = "No active BLE secure session is available.",
                                evidence = hybridBootstrapAcceptEvidence(currentSnapshot, context),
                                startedAtMillis = startedAt
                            )
                        }
                        HybridBootstrapManualAcceptSendResult.WriterUnavailable -> {
                            context.hybridAcceptLastSendResult = "writer-unavailable"
                            return completeStep(
                                stepId = stepId,
                                status = AutomatedDiagnosticStepStatus.FAIL,
                                summary = "Hybrid bootstrap accept failed",
                                blocker = "BLE writer unavailable.",
                                evidence = hybridBootstrapAcceptEvidence(currentSnapshot, context),
                                startedAtMillis = startedAt
                            )
                        }
                        is HybridBootstrapManualAcceptSendResult.InvalidAccept -> {
                            context.hybridAcceptLastSendResult = "invalid:${result.reason}"
                            return completeStep(
                                stepId = stepId,
                                status = AutomatedDiagnosticStepStatus.FAIL,
                                summary = "Hybrid bootstrap accept failed",
                                blocker = result.reason,
                                evidence = hybridBootstrapAcceptEvidence(currentSnapshot, context),
                                startedAtMillis = startedAt
                            )
                        }
                        is HybridBootstrapManualAcceptSendResult.SendFailed -> {
                            context.hybridAcceptLastSendResult = "failed:${result.reason}"
                            return completeStep(
                                stepId = stepId,
                                status = AutomatedDiagnosticStepStatus.FAIL,
                                summary = "Hybrid bootstrap accept failed",
                                blocker = result.reason,
                                evidence = hybridBootstrapAcceptEvidence(currentSnapshot, context),
                                startedAtMillis = startedAt
                            )
                        }
                    }
                }
                updateStep(
                    stepId = stepId,
                    status = AutomatedDiagnosticStepStatus.RUNNING,
                    startedAtMillis = startedAt,
                    elapsedMillis = elapsed,
                    retryCount = stepResult(stepId).retryCount,
                    summary = "Waiting for coordinator confirmation of hybrid bootstrap accept",
                    blocker = participantHybridBootstrapAcceptBlocker(currentSnapshot, context),
                    evidence = hybridBootstrapAcceptEvidence(currentSnapshot, context),
                    waitingProgressText =
                    "Waiting ${formatAutomatedDiagnosticsDuration(elapsed)} / ${formatAutomatedDiagnosticsDuration(timingPolicy.hybridControlDelivery.timeoutMillis)}",
                    stabilizationProgressText = null
                )
                if (elapsed >= timingPolicy.hybridControlDelivery.timeoutMillis) {
                    return completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.BLOCKED,
                        summary = "Hybrid bootstrap accept blocked",
                        blocker = participantHybridBootstrapAcceptBlocker(
                            currentSnapshot,
                            context
                        ),
                        evidence = hybridBootstrapAcceptEvidence(currentSnapshot, context),
                        startedAtMillis = startedAt
                    )
                }
                delay.delayMillis(timingPolicy.pollIntervalMillis)
            }
            completeStep(
                stepId = stepId,
                status = AutomatedDiagnosticStepStatus.CANCELLED,
                summary = "Cancelled",
                blocker = "Automated diagnostics were cancelled.",
                evidence = hybridBootstrapAcceptEvidence(bindings.snapshot(), context),
                startedAtMillis = startedAt
            )
        } else {
            awaitStableSnapshotStep(
                stepId = stepId,
                window = timingPolicy.hybridControlDelivery,
                summary = "Waiting for hybrid bootstrap accept",
                successSummary = "Hybrid bootstrap accept recorded",
                blockingReason = {
                    hybridBootstrapAcceptBlocker(
                        snapshot = it,
                        context = context,
                        expectedPeerId = remotePeerId,
                        expectedSessionId = expectedSessionId
                    )
                },
                successEvidence = { currentSnapshot ->
                    hybridBootstrapAcceptEvidence(currentSnapshot, context)
                },
                isSatisfied = { currentSnapshot ->
                    recentAcceptedHybridBootstrapAcceptObservationOrNull(
                        snapshot = currentSnapshot,
                        context = context,
                        expectedPeerId = remotePeerId,
                        expectedSessionId = expectedSessionId
                    ) != null
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
        val sharedRun = context.sharedRun ?: return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.BLOCKED,
            summary = "Hybrid bootstrap socket hint blocked",
            blocker = "Shared diagnostics run is unavailable."
        )
        val localPeerId = snapshot.localPeerId?.trim()?.takeIf { it.isNotEmpty() } ?: return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.BLOCKED,
            summary = "Hybrid bootstrap socket hint blocked",
            blocker = "Local Aurora peer id is unavailable."
        )
        val remotePeerId = otherSharedRunPeerId(sharedRun, localPeerId) ?: return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.BLOCKED,
            summary = "Hybrid bootstrap socket hint blocked",
            blocker = "Remote Aurora peer id is unavailable for the shared diagnostics run."
        )
        val expectedSessionId = sharedRun.coordinatorPeerId
        val expectedGroupOwnerAddress =
            snapshot.wifiDirectRuntimeStatus.connectionStatus.groupOwnerAddress
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return completeStep(
                    stepId = stepId,
                    status = AutomatedDiagnosticStepStatus.BLOCKED,
                    summary = "Hybrid bootstrap socket hint blocked",
                    blocker = "No Wi-Fi Direct group-owner endpoint is available."
                )
        val expectedSocketPort = wifiDirectDebugSocketPort
        context.beginHybridSocketHintAttempt(
            expectedSessionId = expectedSessionId,
            expectedGroupOwnerAddress = expectedGroupOwnerAddress,
            expectedSocketPort = expectedSocketPort,
            startedAtMonotonicMillis = clock.nowMillis()
        )
        return when (snapshot.wifiDirectRuntimeStatus.connectionStatus.role) {
            WifiDirectConnectionRole.GROUP_OWNER -> {
                val attemptNumber = context.currentPhaseAttemptNumber
                val startedAt = clock.nowMillis()
                var lastSocketHintRefreshAtMillis: Long? = null
                setStepRunning(
                    stepId = stepId,
                    retryCount = stepResult(stepId).retryCount,
                    summary = "Waiting for client confirmation of hybrid bootstrap socket hint",
                    startedAtMillis = startedAt
                )
                while (currentCoroutineContext().isActive) {
                    val now = clock.nowMillis()
                    val currentSnapshot = bindings.snapshot()
                    val elapsed = now - startedAt
                    if (!wifiDirectSocketReady(currentSnapshot)) {
                        return completeStep(
                            stepId = stepId,
                            status = AutomatedDiagnosticStepStatus.BLOCKED,
                            summary = "Hybrid bootstrap socket hint blocked",
                            blocker = wifiDirectSocketBlocker(currentSnapshot)
                                ?: "Wi-Fi Direct socket is not ready.",
                            evidence = hybridBootstrapSocketHintEvidence(currentSnapshot, context),
                            startedAtMillis = startedAt
                        )
                    }
                    val remoteSignal = recentAcceptedAutomatedDiagnosticsPhaseSignalOrNull(
                        snapshot = currentSnapshot,
                        expectedRun = sharedRun,
                        expectedSenderPeerId = remotePeerId,
                        expectedRecipientPeerId = localPeerId,
                        expectedStepId = stepId,
                        expectedAttemptNumber = attemptNumber,
                        context = context
                    )
                    when (remoteSignal?.phaseState) {
                        AutomatedDiagnosticsPhaseState.PASS -> {
                            return completeStep(
                                stepId = stepId,
                                status = AutomatedDiagnosticStepStatus.PASS,
                                summary = "Hybrid bootstrap socket hint confirmed by client",
                                evidence = hybridBootstrapSocketHintEvidence(currentSnapshot, context),
                                startedAtMillis = startedAt
                            )
                        }
                        AutomatedDiagnosticsPhaseState.FAIL -> {
                            return completeStep(
                                stepId = stepId,
                                status = AutomatedDiagnosticStepStatus.FAIL,
                                summary = "Hybrid bootstrap socket hint failed",
                                blocker = "Client reported Hybrid bootstrap socket hint FAIL.",
                                evidence = hybridBootstrapSocketHintEvidence(currentSnapshot, context),
                                startedAtMillis = startedAt
                            )
                        }
                        AutomatedDiagnosticsPhaseState.BLOCKED -> {
                            return completeStep(
                                stepId = stepId,
                                status = AutomatedDiagnosticStepStatus.BLOCKED,
                                summary = "Hybrid bootstrap socket hint blocked",
                                blocker = "Client reported Hybrid bootstrap socket hint BLOCKED.",
                                evidence = hybridBootstrapSocketHintEvidence(currentSnapshot, context),
                                startedAtMillis = startedAt
                            )
                        }
                        AutomatedDiagnosticsPhaseState.CANCELLED -> {
                            return completeStep(
                                stepId = stepId,
                                status = AutomatedDiagnosticStepStatus.CANCELLED,
                                summary = "Hybrid bootstrap socket hint cancelled",
                                blocker = "Client cancelled during Hybrid bootstrap socket hint.",
                                evidence = hybridBootstrapSocketHintEvidence(currentSnapshot, context),
                                startedAtMillis = startedAt
                            )
                        }
                        AutomatedDiagnosticsPhaseState.READY,
                        AutomatedDiagnosticsPhaseState.RUNNING,
                        null -> Unit
                    }
                    if (
                        lastSocketHintRefreshAtMillis == null ||
                            now - lastSocketHintRefreshAtMillis >=
                            timingPolicy.automatedDiagnosticsPhaseStateRefreshMillis
                    ) {
                        context.hybridSocketHintSendAttemptCount += 1
                        when (val result = bindings.commands.requestHybridBootstrapManualSocketHint()) {
                            is HybridBootstrapManualSocketHintSendResult.Sent -> {
                                if (result.peerId != remotePeerId) {
                                    context.hybridSocketHintLastSendResult =
                                        "wrong-peer:${result.peerId}"
                                    return completeStep(
                                        stepId = stepId,
                                        status = AutomatedDiagnosticStepStatus.FAIL,
                                        summary = "Hybrid bootstrap socket hint failed",
                                        blocker =
                                            "Hybrid bootstrap socket hint targeted ${result.peerId} instead of $remotePeerId.",
                                        evidence = hybridBootstrapSocketHintEvidence(currentSnapshot, context),
                                        startedAtMillis = startedAt
                                    )
                                }
                                if (result.sessionId != expectedSessionId) {
                                    context.hybridSocketHintLastSendResult =
                                        "wrong-session:${result.sessionId}"
                                    return completeStep(
                                        stepId = stepId,
                                        status = AutomatedDiagnosticStepStatus.FAIL,
                                        summary = "Hybrid bootstrap socket hint failed",
                                        blocker =
                                            "Hybrid bootstrap socket hint targeted session ${result.sessionId} instead of $expectedSessionId.",
                                        evidence = hybridBootstrapSocketHintEvidence(currentSnapshot, context),
                                        startedAtMillis = startedAt
                                    )
                                }
                                if (result.groupOwnerAddress != expectedGroupOwnerAddress) {
                                    context.hybridSocketHintLastSendResult =
                                        "wrong-endpoint:${result.groupOwnerAddress}"
                                    return completeStep(
                                        stepId = stepId,
                                        status = AutomatedDiagnosticStepStatus.FAIL,
                                        summary = "Hybrid bootstrap socket hint failed",
                                        blocker =
                                            "Hybrid bootstrap socket hint targeted address ${result.groupOwnerAddress} instead of $expectedGroupOwnerAddress.",
                                        evidence = hybridBootstrapSocketHintEvidence(currentSnapshot, context),
                                        startedAtMillis = startedAt
                                    )
                                }
                                if (result.socketPort != expectedSocketPort) {
                                    context.hybridSocketHintLastSendResult =
                                        "wrong-port:${result.socketPort}"
                                    return completeStep(
                                        stepId = stepId,
                                        status = AutomatedDiagnosticStepStatus.FAIL,
                                        summary = "Hybrid bootstrap socket hint failed",
                                        blocker =
                                            "Hybrid bootstrap socket hint targeted port ${result.socketPort} instead of $expectedSocketPort.",
                                        evidence = hybridBootstrapSocketHintEvidence(currentSnapshot, context),
                                        startedAtMillis = startedAt
                                    )
                                }
                                context.hybridSocketHintSuccessfulSendCount += 1
                                context.hybridSocketHintLastSendResult = "sent"
                                context.hybridSocketHintLastSentPeerId = result.peerId
                                context.hybridSocketHintLastSentSessionId = result.sessionId
                                context.hybridSocketHintLastSentGroupOwnerAddress =
                                    result.groupOwnerAddress
                                context.hybridSocketHintLastSentSocketPort = result.socketPort
                                context.hybridSocketHintLastSentAtMonotonicMillis = now
                                lastSocketHintRefreshAtMillis = now
                            }
                            HybridBootstrapManualSocketHintSendResult.NoActivePeer -> {
                                context.hybridSocketHintLastSendResult = "no-active-peer"
                                return completeStep(
                                    stepId = stepId,
                                    status = AutomatedDiagnosticStepStatus.BLOCKED,
                                    summary = "Hybrid bootstrap socket hint blocked",
                                    blocker = "No active BLE peer is connected.",
                                    evidence = hybridBootstrapSocketHintEvidence(currentSnapshot, context),
                                    startedAtMillis = startedAt
                                )
                            }
                            HybridBootstrapManualSocketHintSendResult.NoActiveSession -> {
                                context.hybridSocketHintLastSendResult = "no-active-session"
                                return completeStep(
                                    stepId = stepId,
                                    status = AutomatedDiagnosticStepStatus.BLOCKED,
                                    summary = "Hybrid bootstrap socket hint blocked",
                                    blocker = "No active BLE secure session is available.",
                                    evidence = hybridBootstrapSocketHintEvidence(currentSnapshot, context),
                                    startedAtMillis = startedAt
                                )
                            }
                            HybridBootstrapManualSocketHintSendResult.NoAcceptedCandidate -> {
                                context.hybridSocketHintLastSendResult = "no-accepted-candidate"
                                return completeStep(
                                    stepId = stepId,
                                    status = AutomatedDiagnosticStepStatus.BLOCKED,
                                    summary = "Hybrid bootstrap socket hint blocked",
                                    blocker = "No accepted hybrid bootstrap candidate is available.",
                                    evidence = hybridBootstrapSocketHintEvidence(currentSnapshot, context),
                                    startedAtMillis = startedAt
                                )
                            }
                            HybridBootstrapManualSocketHintSendResult.NoSocketEndpoint -> {
                                context.hybridSocketHintLastSendResult = "no-socket-endpoint"
                                return completeStep(
                                    stepId = stepId,
                                    status = AutomatedDiagnosticStepStatus.BLOCKED,
                                    summary = "Hybrid bootstrap socket hint blocked",
                                    blocker = "No Wi-Fi Direct group-owner endpoint is available.",
                                    evidence = hybridBootstrapSocketHintEvidence(currentSnapshot, context),
                                    startedAtMillis = startedAt
                                )
                            }
                            HybridBootstrapManualSocketHintSendResult.NotGroupOwner -> {
                                context.hybridSocketHintLastSendResult = "not-group-owner"
                                return completeStep(
                                    stepId = stepId,
                                    status = AutomatedDiagnosticStepStatus.BLOCKED,
                                    summary = "Hybrid bootstrap socket hint blocked",
                                    blocker = "This device is not the Wi-Fi Direct group owner.",
                                    evidence = hybridBootstrapSocketHintEvidence(currentSnapshot, context),
                                    startedAtMillis = startedAt
                                )
                            }
                            HybridBootstrapManualSocketHintSendResult.WriterUnavailable -> {
                                context.hybridSocketHintLastSendResult = "writer-unavailable"
                                return completeStep(
                                    stepId = stepId,
                                    status = AutomatedDiagnosticStepStatus.FAIL,
                                    summary = "Hybrid bootstrap socket hint failed",
                                    blocker = "BLE writer unavailable.",
                                    evidence = hybridBootstrapSocketHintEvidence(currentSnapshot, context),
                                    startedAtMillis = startedAt
                                )
                            }
                            is HybridBootstrapManualSocketHintSendResult.InvalidSocketHint -> {
                                context.hybridSocketHintLastSendResult = "invalid:${result.reason}"
                                return completeStep(
                                    stepId = stepId,
                                    status = AutomatedDiagnosticStepStatus.FAIL,
                                    summary = "Hybrid bootstrap socket hint failed",
                                    blocker = result.reason,
                                    evidence = hybridBootstrapSocketHintEvidence(currentSnapshot, context),
                                    startedAtMillis = startedAt
                                )
                            }
                            is HybridBootstrapManualSocketHintSendResult.SendFailed -> {
                                context.hybridSocketHintLastSendResult = "failed:${result.reason}"
                                return completeStep(
                                    stepId = stepId,
                                    status = AutomatedDiagnosticStepStatus.FAIL,
                                    summary = "Hybrid bootstrap socket hint failed",
                                    blocker = result.reason,
                                    evidence = hybridBootstrapSocketHintEvidence(currentSnapshot, context),
                                    startedAtMillis = startedAt
                                )
                            }
                        }
                    }
                    updateStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.RUNNING,
                        startedAtMillis = startedAt,
                        elapsedMillis = elapsed,
                        retryCount = stepResult(stepId).retryCount,
                        summary = "Waiting for client confirmation of hybrid bootstrap socket hint",
                        blocker = groupOwnerHybridBootstrapSocketHintBlocker(currentSnapshot, context),
                        evidence = hybridBootstrapSocketHintEvidence(currentSnapshot, context),
                        waitingProgressText =
                        "Waiting ${formatAutomatedDiagnosticsDuration(elapsed)} / ${formatAutomatedDiagnosticsDuration(timingPolicy.hybridSocketHintDelivery.timeoutMillis)}",
                        stabilizationProgressText = null
                    )
                    if (elapsed >= timingPolicy.hybridSocketHintDelivery.timeoutMillis) {
                        return completeStep(
                            stepId = stepId,
                            status = AutomatedDiagnosticStepStatus.BLOCKED,
                            summary = "Hybrid bootstrap socket hint blocked",
                            blocker = groupOwnerHybridBootstrapSocketHintBlocker(
                                currentSnapshot,
                                context
                            ),
                            evidence = hybridBootstrapSocketHintEvidence(currentSnapshot, context),
                            startedAtMillis = startedAt
                        )
                    }
                    delay.delayMillis(timingPolicy.pollIntervalMillis)
                }
                completeStep(
                    stepId = stepId,
                    status = AutomatedDiagnosticStepStatus.CANCELLED,
                    summary = "Cancelled",
                    blocker = "Automated diagnostics were cancelled.",
                    evidence = hybridBootstrapSocketHintEvidence(bindings.snapshot(), context),
                    startedAtMillis = startedAt
                )
            }
            WifiDirectConnectionRole.CLIENT -> awaitStableSnapshotStep(
                stepId = stepId,
                window = timingPolicy.hybridSocketHintDelivery,
                summary = "Waiting for hybrid bootstrap socket hint",
                successSummary = "Hybrid bootstrap socket hint recorded",
                blockingReason = {
                    hybridBootstrapSocketHintBlocker(
                        snapshot = it,
                        context = context,
                        expectedPeerId = remotePeerId,
                        expectedSessionId = expectedSessionId,
                        expectedGroupOwnerAddress = expectedGroupOwnerAddress,
                        expectedSocketPort = expectedSocketPort
                    )
                },
                successEvidence = { currentSnapshot ->
                    hybridBootstrapSocketHintEvidence(currentSnapshot, context)
                },
                isSatisfied = { currentSnapshot ->
                    recentAcceptedHybridBootstrapSocketHintObservationOrNull(
                        snapshot = currentSnapshot,
                        context = context,
                        expectedPeerId = remotePeerId,
                        expectedSessionId = expectedSessionId,
                        expectedGroupOwnerAddress = expectedGroupOwnerAddress,
                        expectedSocketPort = expectedSocketPort
                    ) != null &&
                        matchingAcceptedSocketReadyHybridCandidateOrNull(
                            snapshot = currentSnapshot,
                            expectedPeerId = remotePeerId,
                            expectedSessionId = expectedSessionId,
                            expectedGroupOwnerAddress = expectedGroupOwnerAddress,
                            expectedSocketPort = expectedSocketPort
                        ) != null
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
                    "The Wi-Fi Direct client executes the hybrid bootstrap trigger from the current socket-ready endpoint in this phase."
                )
            )
            WifiDirectConnectionRole.CLIENT -> {
                val sharedRun = context.sharedRun
                    ?: return completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.BLOCKED,
                        summary = "Hybrid bootstrap trigger blocked",
                        blocker = "The shared automated diagnostics run is unavailable."
                    )
                val localPeerId = snapshot.localPeerId?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.BLOCKED,
                        summary = "Hybrid bootstrap trigger blocked",
                        blocker = "Local Aurora peer id is unavailable."
                    )
                val remotePeerId = otherSharedRunPeerId(sharedRun, localPeerId)
                    ?: return completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.BLOCKED,
                        summary = "Hybrid bootstrap trigger blocked",
                        blocker = "Remote Aurora peer id is unavailable for the shared diagnostics run."
                    )
                val expectedSessionId = sharedRun.coordinatorPeerId
                val expectedGroupOwnerAddress =
                    snapshot.wifiDirectRuntimeStatus.connectionStatus.groupOwnerAddress
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: return completeStep(
                            stepId = stepId,
                            status = AutomatedDiagnosticStepStatus.BLOCKED,
                            summary = "Hybrid bootstrap trigger blocked",
                            blocker = "No Wi-Fi Direct group-owner endpoint is available."
                        )
                val expectedSocketPort = wifiDirectDebugSocketPort
                val readiness = awaitStableSnapshotStep(
                    stepId = stepId,
                    window = timingPolicy.hybridBootstrapTrigger,
                    summary = "Waiting for hybrid bootstrap trigger readiness",
                    successSummary = "Hybrid bootstrap command is ready",
                    blockingReason = {
                        hybridBootstrapTriggerBlocker(
                            snapshot = it,
                            context = context,
                            expectedPeerId = remotePeerId,
                            expectedSessionId = expectedSessionId,
                            expectedGroupOwnerAddress = expectedGroupOwnerAddress,
                            expectedSocketPort = expectedSocketPort
                        )
                    },
                    successEvidence = { currentSnapshot ->
                        hybridBootstrapEvidence(currentSnapshot)
                    },
                    isSatisfied = { currentSnapshot ->
                        currentSnapshot.hybridBootstrapManualTriggerSnapshot.canTriggerNow &&
                            recentAcceptedHybridBootstrapSocketHintObservationOrNull(
                                snapshot = currentSnapshot,
                                context = context,
                                expectedPeerId = remotePeerId,
                                expectedSessionId = expectedSessionId,
                                expectedGroupOwnerAddress = expectedGroupOwnerAddress,
                                expectedSocketPort = expectedSocketPort
                            ) != null &&
                            matchingAcceptedSocketReadyHybridCandidateOrNull(
                                snapshot = currentSnapshot,
                                expectedPeerId = remotePeerId,
                                expectedSessionId = expectedSessionId,
                                expectedGroupOwnerAddress = expectedGroupOwnerAddress,
                                expectedSocketPort = expectedSocketPort
                            ) != null
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
                                summary = "Hybrid bootstrap trigger succeeded",
                                evidence = hybridBootstrapEvidence(bindings.snapshot()) +
                                    wifiDirectSocketEvidence(bindings.snapshot(), context) +
                                    listOf(
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
                                evidence = hybridBootstrapEvidence(bindings.snapshot()) +
                                    wifiDirectSocketEvidence(bindings.snapshot(), context)
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

    private data class AutomatedDiagnosticsExpectedApplicationProbe(
        val marker: AutomatedDiagnosticsApplicationProbeMarker,
        val expectedSenderPeerId: String,
        val expectedReceiverPeerId: String,
        val expectedThreadId: String,
        val expectedPrivateChatId: String? = null
    ) {
        val fingerprint: String =
            automatedDiagnosticsApplicationProbeFingerprint(marker)
    }

    private data class AutomatedDiagnosticsProbeSubmission(
        val spec: AutomatedDiagnosticsExpectedApplicationProbe,
        val queuedMessageId: String,
        val transportStatus: String,
        val localBleTransportResult: String? = null,
        val expectedReceiverTransportGroupId: Int? = null
    )

    private fun currentApplicationProbeDescriptors(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        submissions: List<AutomatedDiagnosticsProbeSubmission>
    ): List<AutomatedDiagnosticsPhaseApplicationProbeDescriptor> {
        return submissions.map { submission ->
            automatedDiagnosticsLocalPhaseApplicationProbeDescriptor(
                snapshot = snapshot,
                probeKind = submission.spec.marker.probeKind,
                messageId = submission.queuedMessageId,
                expectedReceiverPeerId = submission.spec.expectedReceiverPeerId,
                transportStatus = submission.transportStatus,
                localBleTransportResult = submission.localBleTransportResult,
                expectedReceiverTransportGroupId = submission.expectedReceiverTransportGroupId
            )
        }
    }

    private suspend fun runGlobalMessageProbeStep(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext
    ): AutomatedDiagnosticStepStatus {
        return runApplicationProbeSetStep(
            stepId = stepId,
            context = context,
            senderRole = AutomatedDiagnosticsPeerRole.COORDINATOR,
            probeKinds = listOf(AutomatedDiagnosticsApplicationProbeKind.GLOBAL),
            direction = AutomatedDiagnosticsApplicationProbeDirection.C2P
        )
    }

    private suspend fun runPrivateEncryptedMessageProbeStep(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext
    ): AutomatedDiagnosticStepStatus {
        return runApplicationProbeSetStep(
            stepId = stepId,
            context = context,
            senderRole = AutomatedDiagnosticsPeerRole.COORDINATOR,
            probeKinds = listOf(AutomatedDiagnosticsApplicationProbeKind.PRIVATE),
            direction = AutomatedDiagnosticsApplicationProbeDirection.C2P
        )
    }

    private suspend fun runReverseDirectionMessagingProbeStep(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext
    ): AutomatedDiagnosticStepStatus {
        return runApplicationProbeSetStep(
            stepId = stepId,
            context = context,
            senderRole = AutomatedDiagnosticsPeerRole.PARTICIPANT,
            probeKinds = listOf(
                AutomatedDiagnosticsApplicationProbeKind.GLOBAL,
                AutomatedDiagnosticsApplicationProbeKind.PRIVATE
            ),
            direction = AutomatedDiagnosticsApplicationProbeDirection.P2C
        )
    }

    private suspend fun runApplicationProbeSetStep(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext,
        senderRole: AutomatedDiagnosticsPeerRole,
        probeKinds: List<AutomatedDiagnosticsApplicationProbeKind>,
        direction: AutomatedDiagnosticsApplicationProbeDirection
    ): AutomatedDiagnosticStepStatus {
        val localRole = context.localRole ?: return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.BLOCKED,
            summary = "${stepId.title} blocked",
            blocker = "Local diagnostics role is unavailable."
        )
        val sharedRun = context.sharedRun ?: return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.BLOCKED,
            summary = "${stepId.title} blocked",
            blocker = "Shared diagnostics run is unavailable."
        )
        val snapshot = bindings.snapshot()
        val localPeerId = snapshot.localPeerId?.trim()?.takeIf { it.isNotEmpty() } ?: return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.BLOCKED,
            summary = "${stepId.title} blocked",
            blocker = "Local Aurora peer id is unavailable."
        )
        val remotePeerId = otherSharedRunPeerId(sharedRun, localPeerId) ?: return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.BLOCKED,
            summary = "${stepId.title} blocked",
            blocker = "Remote Aurora peer id is unavailable for the shared diagnostics run."
        )
        val attemptNumber = context.currentPhaseAttemptNumber.takeIf { it > 0 } ?: return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.BLOCKED,
            summary = "${stepId.title} blocked",
            blocker = "Current synchronized diagnostics attempt is unavailable."
        )
        val expectedSenderPeerId = when (senderRole) {
            AutomatedDiagnosticsPeerRole.COORDINATOR -> sharedRun.coordinatorPeerId
            AutomatedDiagnosticsPeerRole.PARTICIPANT -> sharedRun.participantPeerId
        }
        val expectedReceiverPeerId = when (senderRole) {
            AutomatedDiagnosticsPeerRole.COORDINATOR -> sharedRun.participantPeerId
            AutomatedDiagnosticsPeerRole.PARTICIPANT -> sharedRun.coordinatorPeerId
        }
        val probeSpecs = probeKinds.map { probeKind ->
            AutomatedDiagnosticsExpectedApplicationProbe(
                marker = AutomatedDiagnosticsApplicationProbeMarker(
                    sharedRunId = sharedRun.runId,
                    stepId = stepId,
                    attemptNumber = attemptNumber,
                    probeKind = probeKind,
                    direction = direction
                ),
                expectedSenderPeerId = expectedSenderPeerId,
                expectedReceiverPeerId = expectedReceiverPeerId,
                expectedThreadId = if (probeKind == AutomatedDiagnosticsApplicationProbeKind.GLOBAL) {
                    "global"
                } else {
                    "private:$expectedSenderPeerId"
                },
                expectedPrivateChatId = if (probeKind == AutomatedDiagnosticsApplicationProbeKind.PRIVATE) {
                    sharedRun.sessionAssociationId
                } else {
                    null
                }
            )
        }
        return if (localRole == senderRole) {
            runApplicationProbeSenderStep(
                stepId = stepId,
                context = context,
                localPeerId = localPeerId,
                remotePeerId = remotePeerId,
                probeSpecs = probeSpecs
            )
        } else {
            runApplicationProbeReceiverStep(
                stepId = stepId,
                context = context,
                localPeerId = localPeerId,
                probeSpecs = probeSpecs
            )
        }
    }

    private suspend fun runApplicationProbeSenderStep(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext,
        localPeerId: String,
        remotePeerId: String,
        probeSpecs: List<AutomatedDiagnosticsExpectedApplicationProbe>
    ): AutomatedDiagnosticStepStatus {
        val startedAt = clock.nowMillis()
        setStepRunning(
            stepId = stepId,
            retryCount = stepResult(stepId).retryCount,
            summary = "Sending ${stepId.title.lowercase()}",
            startedAtMillis = startedAt
        )
        val submissions = mutableListOf<AutomatedDiagnosticsProbeSubmission>()
        for (spec in probeSpecs) {
            val submission = sendApplicationProbe(
                stepId = stepId,
                spec = spec,
                localPeerId = localPeerId,
                remotePeerId = remotePeerId,
                startedAtMillis = startedAt,
                currentSubmissions = submissions
            ) ?: return stepResult(stepId).status
            submissions += submission
            capturePhaseThreeMessageId(submission.queuedMessageId)
        }
        requireNotNull(context.sharedRun)?.let { sharedRun ->
            val applicationProbeDescriptors = currentApplicationProbeDescriptors(
                snapshot = bindings.snapshot(),
                submissions = submissions
            )
            context.currentPhaseApplicationProbeDescriptors = applicationProbeDescriptors
            requestAutomatedDiagnosticsPhaseState(
                stepId = stepId,
                context = context,
                sharedRun = sharedRun,
                remotePeerId = remotePeerId,
                phaseState = AutomatedDiagnosticsPhaseState.RUNNING,
                attemptNumber = context.currentPhaseAttemptNumber,
                applicationProbeDescriptors = applicationProbeDescriptors
            )
        }
        return awaitRemoteApplicationProbeConfirmation(
            stepId = stepId,
            context = context,
            localPeerId = localPeerId,
            remotePeerId = remotePeerId,
            startedAtMillis = startedAt,
            probeSpecs = probeSpecs,
            submissions = submissions
        )
    }

    private suspend fun sendApplicationProbe(
        stepId: AutomatedDiagnosticStepId,
        spec: AutomatedDiagnosticsExpectedApplicationProbe,
        localPeerId: String,
        remotePeerId: String,
        startedAtMillis: Long,
        currentSubmissions: List<AutomatedDiagnosticsProbeSubmission>
    ): AutomatedDiagnosticsProbeSubmission? {
        val bodyText = spec.marker.bodyText()
        return when (spec.marker.probeKind) {
            AutomatedDiagnosticsApplicationProbeKind.GLOBAL -> {
                val submission = bindings.commands.sendGlobalChatMessage(bodyText)
                    ?: return completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.FAIL,
                        summary = "${stepId.title} failed",
                        blocker = "Global probe could not be queued locally.",
                        evidence = applicationProbeEvidence(
                            snapshot = bindings.snapshot(),
                            probeSpecs = currentSubmissions.map { it.spec } + spec,
                            localPeerId = localPeerId,
                            submissions = currentSubmissions,
                            startedAtMillis = startedAtMillis
                        ),
                        startedAtMillis = startedAtMillis
                    ).let { null }
                when (val result = submission.transportResult) {
                    is GlobalMeshDeliveryResult.QueuedToActivePeer ->
                        AutomatedDiagnosticsProbeSubmission(
                            spec = spec,
                            queuedMessageId = submission.queuedMessage.messageId,
                            transportStatus = "queued-active:${result.peerId}",
                            localBleTransportResult = "QueuedLocally",
                            expectedReceiverTransportGroupId =
                                automatedDiagnosticsApplicationProbeExpectedTransportGroupId(
                                    messageId = submission.queuedMessage.messageId,
                                    receiverPeerId = result.peerId
                                )
                        )
                    is GlobalMeshDeliveryResult.QueuedToPeers ->
                        AutomatedDiagnosticsProbeSubmission(
                            spec = spec,
                            queuedMessageId = submission.queuedMessage.messageId,
                            transportStatus = "queued-peers:${result.peerIds.joinToString(",")}",
                            localBleTransportResult = "QueuedLocally",
                            expectedReceiverTransportGroupId =
                                automatedDiagnosticsApplicationProbeExpectedTransportGroupId(
                                    messageId = submission.queuedMessage.messageId,
                                    receiverPeerId = remotePeerId
                                )
                        )
                    GlobalMeshDeliveryResult.NoReachablePeers ->
                        return completeStep(
                            stepId = stepId,
                            status = AutomatedDiagnosticStepStatus.BLOCKED,
                            summary = "${stepId.title} blocked",
                            blocker = "Global probe has no reachable peer.",
                            evidence = applicationProbeEvidence(
                                snapshot = bindings.snapshot(),
                                probeSpecs = currentSubmissions.map { it.spec } + spec,
                                localPeerId = localPeerId,
                                submissions = currentSubmissions,
                                startedAtMillis = startedAtMillis
                            ),
                            startedAtMillis = startedAtMillis
                        ).let { null }
                    GlobalMeshDeliveryResult.SenderUnavailable ->
                        return completeStep(
                            stepId = stepId,
                            status = AutomatedDiagnosticStepStatus.BLOCKED,
                            summary = "${stepId.title} blocked",
                            blocker = "Global probe transport sender is unavailable.",
                            evidence = applicationProbeEvidence(
                                snapshot = bindings.snapshot(),
                                probeSpecs = currentSubmissions.map { it.spec } + spec,
                                localPeerId = localPeerId,
                                submissions = currentSubmissions,
                                startedAtMillis = startedAtMillis
                            ),
                            startedAtMillis = startedAtMillis
                        ).let { null }
                    is GlobalMeshDeliveryResult.ConnectOnSendFailed ->
                        return completeStep(
                            stepId = stepId,
                            status = AutomatedDiagnosticStepStatus.FAIL,
                            summary = "${stepId.title} failed",
                            blocker = "Global probe connect-on-send failed for ${result.peerId}: ${result.reason}",
                            evidence = applicationProbeEvidence(
                                snapshot = bindings.snapshot(),
                                probeSpecs = currentSubmissions.map { it.spec } + spec,
                                localPeerId = localPeerId,
                                submissions = currentSubmissions,
                                startedAtMillis = startedAtMillis
                            ),
                            startedAtMillis = startedAtMillis
                        ).let { null }
                    is GlobalMeshDeliveryResult.Failed ->
                        return completeStep(
                            stepId = stepId,
                            status = AutomatedDiagnosticStepStatus.FAIL,
                            summary = "${stepId.title} failed",
                            blocker = result.reason,
                            evidence = applicationProbeEvidence(
                                snapshot = bindings.snapshot(),
                                probeSpecs = currentSubmissions.map { it.spec } + spec,
                                localPeerId = localPeerId,
                                submissions = currentSubmissions,
                                startedAtMillis = startedAtMillis
                            ),
                            startedAtMillis = startedAtMillis
                        ).let { null }
                    is GlobalMeshDeliveryResult.SkippedDuplicate ->
                        return completeStep(
                            stepId = stepId,
                            status = AutomatedDiagnosticStepStatus.FAIL,
                            summary = "${stepId.title} failed",
                            blocker = "Global probe message ${result.messageId} was treated as a duplicate.",
                            evidence = applicationProbeEvidence(
                                snapshot = bindings.snapshot(),
                                probeSpecs = currentSubmissions.map { it.spec } + spec,
                                localPeerId = localPeerId,
                                submissions = currentSubmissions,
                                startedAtMillis = startedAtMillis
                            ),
                            startedAtMillis = startedAtMillis
                        ).let { null }
                    is GlobalMeshDeliveryResult.SkippedSourcePeer ->
                        return completeStep(
                            stepId = stepId,
                            status = AutomatedDiagnosticStepStatus.FAIL,
                            summary = "${stepId.title} failed",
                            blocker = "Global probe was skipped back to source peer ${result.peerId}.",
                            evidence = applicationProbeEvidence(
                                snapshot = bindings.snapshot(),
                                probeSpecs = currentSubmissions.map { it.spec } + spec,
                                localPeerId = localPeerId,
                                submissions = currentSubmissions,
                                startedAtMillis = startedAtMillis
                            ),
                            startedAtMillis = startedAtMillis
                        ).let { null }
                    is GlobalMeshDeliveryResult.SkippedTtlExpired ->
                        return completeStep(
                            stepId = stepId,
                            status = AutomatedDiagnosticStepStatus.FAIL,
                            summary = "${stepId.title} failed",
                            blocker = "Global probe TTL expired for ${result.messageId}.",
                            evidence = applicationProbeEvidence(
                                snapshot = bindings.snapshot(),
                                probeSpecs = currentSubmissions.map { it.spec } + spec,
                                localPeerId = localPeerId,
                                submissions = currentSubmissions,
                                startedAtMillis = startedAtMillis
                            ),
                            startedAtMillis = startedAtMillis
                        ).let { null }
                }
            }
            AutomatedDiagnosticsApplicationProbeKind.PRIVATE -> {
                val submission = bindings.commands.sendPrivateChatMessage(
                    remotePeerId,
                    bodyText
                ) ?: return completeStep(
                    stepId = stepId,
                    status = AutomatedDiagnosticStepStatus.FAIL,
                    summary = "${stepId.title} failed",
                    blocker = "Private probe could not be queued locally.",
                        evidence = applicationProbeEvidence(
                            snapshot = bindings.snapshot(),
                            probeSpecs = currentSubmissions.map { it.spec } + spec,
                            localPeerId = localPeerId,
                            submissions = currentSubmissions,
                            startedAtMillis = startedAtMillis
                        ),
                    startedAtMillis = startedAtMillis
                ).let { null }
                when (val result = submission.transportResult) {
                    PrivateChatMessageSendResult.SubmittedLocally ->
                        AutomatedDiagnosticsProbeSubmission(
                            spec = spec,
                            queuedMessageId = submission.queuedMessage.messageId,
                            transportStatus = "submitted",
                            localBleTransportResult = "QueuedLocally",
                            expectedReceiverTransportGroupId =
                                automatedDiagnosticsApplicationProbeExpectedTransportGroupId(
                                    messageId = submission.queuedMessage.messageId,
                                    receiverPeerId = remotePeerId
                                )
                        )
                    PrivateChatMessageSendResult.KeysUnavailable ->
                        return completeStep(
                            stepId = stepId,
                            status = AutomatedDiagnosticStepStatus.BLOCKED,
                            summary = "${stepId.title} blocked",
                            blocker = "Private probe keys are unavailable.",
                            evidence = applicationProbeEvidence(
                                snapshot = bindings.snapshot(),
                                probeSpecs = currentSubmissions.map { it.spec } + spec,
                                localPeerId = localPeerId,
                                submissions = currentSubmissions,
                                startedAtMillis = startedAtMillis
                            ),
                            startedAtMillis = startedAtMillis
                        ).let { null }
                    PrivateChatMessageSendResult.ContactUnavailable ->
                        return completeStep(
                            stepId = stepId,
                            status = AutomatedDiagnosticStepStatus.BLOCKED,
                            summary = "${stepId.title} blocked",
                            blocker = "Private probe target contact is unavailable.",
                            evidence = applicationProbeEvidence(
                                snapshot = bindings.snapshot(),
                                probeSpecs = currentSubmissions.map { it.spec } + spec,
                                localPeerId = localPeerId,
                                submissions = currentSubmissions,
                                startedAtMillis = startedAtMillis
                            ),
                            startedAtMillis = startedAtMillis
                        ).let { null }
                    PrivateChatMessageSendResult.ContactNotReachable ->
                        return completeStep(
                            stepId = stepId,
                            status = AutomatedDiagnosticStepStatus.BLOCKED,
                            summary = "${stepId.title} blocked",
                            blocker = "Private probe target contact is not reachable.",
                            evidence = applicationProbeEvidence(
                                snapshot = bindings.snapshot(),
                                probeSpecs = currentSubmissions.map { it.spec } + spec,
                                localPeerId = localPeerId,
                                submissions = currentSubmissions,
                                startedAtMillis = startedAtMillis
                            ),
                            startedAtMillis = startedAtMillis
                        ).let { null }
                    is PrivateChatMessageSendResult.Failed ->
                        return completeStep(
                            stepId = stepId,
                            status = AutomatedDiagnosticStepStatus.FAIL,
                            summary = "${stepId.title} failed",
                            blocker = result.reason,
                            evidence = applicationProbeEvidence(
                                snapshot = bindings.snapshot(),
                                probeSpecs = currentSubmissions.map { it.spec } + spec,
                                localPeerId = localPeerId,
                                submissions = currentSubmissions,
                                startedAtMillis = startedAtMillis
                            ),
                            startedAtMillis = startedAtMillis
                        ).let { null }
                }
            }
        }
    }

    private suspend fun awaitRemoteApplicationProbeConfirmation(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext,
        localPeerId: String,
        remotePeerId: String,
        startedAtMillis: Long,
        probeSpecs: List<AutomatedDiagnosticsExpectedApplicationProbe>,
        submissions: List<AutomatedDiagnosticsProbeSubmission>
    ): AutomatedDiagnosticStepStatus {
        val sharedRun = requireNotNull(context.sharedRun)
        val timeoutMillis =
            timingPolicy.applicationProbeDelivery.timeoutMillis +
                timingPolicy.applicationProbeDuplicateObservationMillis
        while (currentCoroutineContext().isActive) {
            val now = clock.nowMillis()
            val elapsed = now - startedAtMillis
            val snapshot = bindings.snapshot()
            val applicationProbeDescriptors = currentApplicationProbeDescriptors(
                snapshot = snapshot,
                submissions = submissions
            )
            context.currentPhaseApplicationProbeDescriptors = applicationProbeDescriptors
            val selfReturnCount = probeSpecs.sumOf { spec ->
                val matchingSubmission = submissions.firstOrNull { it.spec == spec }
                matchingApplicationProbeReceiveDiagnostics(
                    snapshot = snapshot,
                    spec = spec.copy(
                        expectedSenderPeerId = localPeerId,
                        expectedReceiverPeerId = localPeerId,
                        expectedThreadId = if (spec.marker.probeKind == AutomatedDiagnosticsApplicationProbeKind.GLOBAL) {
                            "global"
                        } else {
                            "private:$localPeerId"
                        }
                    ),
                    expectedMessageId = matchingSubmission?.queuedMessageId,
                    minimumObservedAtMillis = startedAtMillis
                ).distinctBy { diagnostic ->
                    diagnostic.messageId
                }.size
            }
            if (selfReturnCount > 0) {
                return completeStep(
                    stepId = stepId,
                    status = AutomatedDiagnosticStepStatus.FAIL,
                    summary = "${stepId.title} failed",
                    blocker = "Probe message returned to the sender UI as an incoming message.",
                    evidence = applicationProbeEvidence(
                        snapshot = snapshot,
                        probeSpecs = probeSpecs,
                        localPeerId = localPeerId,
                        submissions = submissions,
                        startedAtMillis = startedAtMillis
                    ),
                    startedAtMillis = startedAtMillis
                )
            }
            val remoteSignal = recentAcceptedAutomatedDiagnosticsPhaseSignalOrNull(
                snapshot = snapshot,
                expectedRun = sharedRun,
                expectedSenderPeerId = remotePeerId,
                expectedRecipientPeerId = localPeerId,
                expectedStepId = stepId,
                expectedAttemptNumber = context.currentPhaseAttemptNumber,
                context = context
            )
            when (remoteSignal?.phaseState) {
                AutomatedDiagnosticsPhaseState.PASS -> {
                    return completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.PASS,
                        summary = "${stepId.title} confirmed by remote receiver",
                        evidence = applicationProbeEvidence(
                            snapshot = snapshot,
                            probeSpecs = probeSpecs,
                            localPeerId = localPeerId,
                            submissions = submissions,
                            startedAtMillis = startedAtMillis
                        ),
                        startedAtMillis = startedAtMillis
                    )
                }
                AutomatedDiagnosticsPhaseState.FAIL -> {
                    return completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.FAIL,
                        summary = "${stepId.title} failed",
                        blocker = "Remote device reported ${stepId.title.lowercase()} FAIL.",
                        evidence = applicationProbeEvidence(
                            snapshot = snapshot,
                            probeSpecs = probeSpecs,
                            localPeerId = localPeerId,
                            submissions = submissions,
                            startedAtMillis = startedAtMillis
                        ),
                        startedAtMillis = startedAtMillis
                    )
                }
                AutomatedDiagnosticsPhaseState.BLOCKED -> {
                    return completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.BLOCKED,
                        summary = "${stepId.title} blocked",
                        blocker = "Remote device reported ${stepId.title.lowercase()} BLOCKED.",
                        evidence = applicationProbeEvidence(
                            snapshot = snapshot,
                            probeSpecs = probeSpecs,
                            localPeerId = localPeerId,
                            submissions = submissions,
                            startedAtMillis = startedAtMillis
                        ),
                        startedAtMillis = startedAtMillis
                    )
                }
                AutomatedDiagnosticsPhaseState.CANCELLED -> {
                    return completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.CANCELLED,
                        summary = "${stepId.title} cancelled",
                        blocker = "Remote device cancelled during ${stepId.title.lowercase()}.",
                        evidence = applicationProbeEvidence(
                            snapshot = snapshot,
                            probeSpecs = probeSpecs,
                            localPeerId = localPeerId,
                            submissions = submissions,
                            startedAtMillis = startedAtMillis
                        ),
                        startedAtMillis = startedAtMillis
                    )
                }
                AutomatedDiagnosticsPhaseState.READY,
                AutomatedDiagnosticsPhaseState.RUNNING,
                null -> Unit
            }
            updateStep(
                stepId = stepId,
                status = AutomatedDiagnosticStepStatus.RUNNING,
                startedAtMillis = startedAtMillis,
                elapsedMillis = elapsed,
                retryCount = stepResult(stepId).retryCount,
                summary = "Waiting for remote confirmation of ${stepId.title.lowercase()}",
                blocker = null,
                evidence = applicationProbeEvidence(
                    snapshot = snapshot,
                    probeSpecs = probeSpecs,
                    localPeerId = localPeerId,
                    submissions = submissions,
                    startedAtMillis = startedAtMillis
                ),
                waitingProgressText =
                "Waiting ${formatAutomatedDiagnosticsDuration(elapsed)} / ${formatAutomatedDiagnosticsDuration(timeoutMillis)}",
                stabilizationProgressText = null
            )
            if (elapsed >= timeoutMillis) {
                return completeStep(
                    stepId = stepId,
                    status = AutomatedDiagnosticStepStatus.BLOCKED,
                    summary = "${stepId.title} blocked",
                    blocker = "Timed out waiting for remote confirmation of ${stepId.title.lowercase()}.",
                    evidence = applicationProbeEvidence(
                        snapshot = snapshot,
                        probeSpecs = probeSpecs,
                        localPeerId = localPeerId,
                        submissions = submissions,
                        startedAtMillis = startedAtMillis
                    ),
                    startedAtMillis = startedAtMillis
                )
            }
            delay.delayMillis(timingPolicy.pollIntervalMillis)
        }
        return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.CANCELLED,
            summary = "Cancelled",
            blocker = "Automated diagnostics were cancelled.",
            evidence = applicationProbeEvidence(
                snapshot = bindings.snapshot(),
                probeSpecs = probeSpecs,
                localPeerId = localPeerId,
                submissions = submissions,
                startedAtMillis = startedAtMillis
            ),
            startedAtMillis = startedAtMillis
        )
    }

    private suspend fun runApplicationProbeReceiverStep(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext,
        localPeerId: String,
        probeSpecs: List<AutomatedDiagnosticsExpectedApplicationProbe>
    ): AutomatedDiagnosticStepStatus {
        val startedAtMillis = clock.nowMillis()
        val observationMinimumObservedAtMillis =
            automatedDiagnosticsApplicationProbeMinimumObservedAtMillis(
                currentPhaseAttemptStartedAtMillis = context.currentPhaseAttemptStartedAtMillis,
                currentPhaseBarrierEstablishedAtMillis =
                    context.currentPhaseBarrierEstablishedAtMillis,
                fallbackStartedAtMillis = startedAtMillis
            )
        val firstObservedAtByFingerprint = linkedMapOf<String, Long>()
        val timeoutMillis =
            timingPolicy.applicationProbeDelivery.timeoutMillis +
                timingPolicy.applicationProbeDuplicateObservationMillis
        setStepRunning(
            stepId = stepId,
            retryCount = stepResult(stepId).retryCount,
            summary = "Waiting for ${stepId.title.lowercase()}",
            startedAtMillis = startedAtMillis
        )
        while (currentCoroutineContext().isActive) {
            val now = clock.nowMillis()
            val elapsed = now - startedAtMillis
            val snapshot = bindings.snapshot()
            val matchedLogicalDiagnostics = probeSpecs.associateWith { spec ->
                matchingApplicationProbeReceiveDiagnostics(
                    snapshot = snapshot,
                    spec = spec,
                    minimumObservedAtMillis = observationMinimumObservedAtMillis
                ).distinctBy { diagnostic ->
                    diagnostic.messageId
                }
            }
            matchedLogicalDiagnostics.values.flatten().forEach { diagnostic ->
                capturePhaseThreeMessageId(diagnostic.messageId)
            }
            val duplicateObservation = matchedLogicalDiagnostics
                .entries
                .firstOrNull { it.value.size > 1 }
            if (duplicateObservation != null) {
                return completeStep(
                    stepId = stepId,
                    status = AutomatedDiagnosticStepStatus.FAIL,
                    summary = "${stepId.title} failed",
                    blocker =
                        "Receiver observed ${duplicateObservation.value.size} logical copies for ${duplicateObservation.key.marker.probeKind.name} ${duplicateObservation.key.marker.direction.name}.",
                    evidence = applicationProbeEvidence(
                        snapshot = snapshot,
                        probeSpecs = probeSpecs,
                        localPeerId = localPeerId,
                        submissions = emptyList(),
                        startedAtMillis = observationMinimumObservedAtMillis
                    ),
                    startedAtMillis = startedAtMillis
                )
            }
            matchedLogicalDiagnostics.forEach { (spec, diagnostics) ->
                if (diagnostics.size == 1 && !firstObservedAtByFingerprint.containsKey(spec.fingerprint)) {
                    firstObservedAtByFingerprint[spec.fingerprint] =
                        diagnostics.single().observedAtMonotonicMillis
                }
            }
            val allObserved = probeSpecs.all { spec ->
                matchedLogicalDiagnostics[spec].orEmpty().size == 1
            }
            if (allObserved) {
                val duplicateWindowSatisfied = probeSpecs.all { spec ->
                    val firstObservedAt = firstObservedAtByFingerprint[spec.fingerprint] ?: return@all false
                    now - firstObservedAt >= timingPolicy.applicationProbeDuplicateObservationMillis
                }
                if (duplicateWindowSatisfied) {
                    return completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.PASS,
                        summary = "${stepId.title} observed exactly once",
                        evidence = applicationProbeEvidence(
                            snapshot = snapshot,
                            probeSpecs = probeSpecs,
                            localPeerId = localPeerId,
                            submissions = emptyList(),
                            startedAtMillis = observationMinimumObservedAtMillis
                        ),
                        startedAtMillis = startedAtMillis
                    )
                }
            }
            val stabilizationProgressText = if (allObserved) {
                val earliestRemaining = probeSpecs.minOfOrNull { spec ->
                    val firstObservedAt = firstObservedAtByFingerprint[spec.fingerprint]
                        ?: return@minOfOrNull timingPolicy.applicationProbeDuplicateObservationMillis
                    (timingPolicy.applicationProbeDuplicateObservationMillis -
                        (now - firstObservedAt)).coerceAtLeast(0L)
                } ?: timingPolicy.applicationProbeDuplicateObservationMillis
                "Stable ${timingPolicy.applicationProbeDuplicateObservationMillis - earliestRemaining} / ${timingPolicy.applicationProbeDuplicateObservationMillis} ms"
            } else {
                null
            }
            updateStep(
                stepId = stepId,
                status = AutomatedDiagnosticStepStatus.RUNNING,
                startedAtMillis = startedAtMillis,
                elapsedMillis = elapsed,
                retryCount = stepResult(stepId).retryCount,
                summary = "Waiting for ${stepId.title.lowercase()}",
                blocker = null,
                evidence = applicationProbeEvidence(
                    snapshot = snapshot,
                    probeSpecs = probeSpecs,
                    localPeerId = localPeerId,
                    submissions = emptyList(),
                    startedAtMillis = observationMinimumObservedAtMillis
                ),
                waitingProgressText =
                "Waiting ${formatAutomatedDiagnosticsDuration(elapsed)} / ${formatAutomatedDiagnosticsDuration(timeoutMillis)}",
                stabilizationProgressText = stabilizationProgressText
            )
            if (elapsed >= timeoutMillis) {
                return completeStep(
                    stepId = stepId,
                    status = AutomatedDiagnosticStepStatus.BLOCKED,
                    summary = "${stepId.title} blocked",
                    blocker = "Timed out waiting for ${stepId.title.lowercase()} to appear exactly once.",
                    evidence = applicationProbeEvidence(
                        snapshot = snapshot,
                        probeSpecs = probeSpecs,
                        localPeerId = localPeerId,
                        submissions = emptyList(),
                        startedAtMillis = observationMinimumObservedAtMillis
                    ),
                    startedAtMillis = startedAtMillis
                )
            }
            delay.delayMillis(timingPolicy.pollIntervalMillis)
        }
        return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.CANCELLED,
            summary = "Cancelled",
            blocker = "Automated diagnostics were cancelled.",
            evidence = applicationProbeEvidence(
                snapshot = bindings.snapshot(),
                probeSpecs = probeSpecs,
                localPeerId = localPeerId,
                submissions = emptyList(),
                startedAtMillis = observationMinimumObservedAtMillis
            ),
            startedAtMillis = startedAtMillis
        )
    }

    private suspend fun runFinalEndToEndValidationStep(
        stepId: AutomatedDiagnosticStepId,
        context: AutomatedDiagnosticsStepContext
    ): AutomatedDiagnosticStepStatus {
        val selectedPeerId = context.selectedPeer?.identityKey ?: mutableState.value.selectedPeerId
            ?: return completeStep(
                stepId = stepId,
                status = AutomatedDiagnosticStepStatus.BLOCKED,
                summary = "Final end-to-end validation blocked",
                blocker = "Selected diagnostics peer is unavailable."
            )
        val expectedLocalPhaseThreeMessageCount = 4
        val startedAtMillis = clock.nowMillis()
        var stableSince: Long? = null
        setStepRunning(
            stepId = stepId,
            retryCount = stepResult(stepId).retryCount,
            summary = "Validating final runtime stability",
            startedAtMillis = startedAtMillis
        )
        while (currentCoroutineContext().isActive) {
            val now = clock.nowMillis()
            val snapshot = bindings.snapshot()
            val elapsed = now - startedAtMillis
            val blocker = finalValidationBlocker(
                snapshot = snapshot,
                selectedPeerId = selectedPeerId,
                expectedLocalPhaseThreeMessageCount = expectedLocalPhaseThreeMessageCount
            )
            if (blocker == null) {
                if (stableSince == null) {
                    stableSince = now
                }
                val stableElapsed = now - stableSince
                updateStep(
                    stepId = stepId,
                    status = AutomatedDiagnosticStepStatus.RUNNING,
                    startedAtMillis = startedAtMillis,
                    elapsedMillis = elapsed,
                    retryCount = stepResult(stepId).retryCount,
                    summary = "Validating final runtime stability",
                    blocker = null,
                    evidence = finalValidationEvidence(
                        snapshot = snapshot,
                        selectedPeerId = selectedPeerId,
                        cleanedMessageCount = null
                    ),
                    waitingProgressText =
                    "Waiting ${formatAutomatedDiagnosticsDuration(elapsed)} / ${formatAutomatedDiagnosticsDuration(timingPolicy.finalValidation.timeoutMillis)}",
                    stabilizationProgressText =
                    "Stable ${stableElapsed.coerceAtMost(timingPolicy.finalValidation.stabilizationMillis)} / ${timingPolicy.finalValidation.stabilizationMillis} ms"
                )
                if (stableElapsed >= timingPolicy.finalValidation.stabilizationMillis) {
                    val finalPhaseThreeCapturedIdCount = capturedPhaseThreeMessageIds.size
                    val finalPhaseThreeObservationCount =
                        snapshot.recentAutomatedDiagnosticsApplicationProbeObservations
                            .count { it.stepId in automatedDiagnosticsApplicationProbeStepIds }
                    val cleanupResult = removeCapturedPhaseThreeMessages()
                    if (cleanupResult.completed) {
                        updateRunState { current ->
                            current.copy(
                                phaseTwoSummary =
                                    "Phase 3 cleanup removed ${cleanupResult.removedCount} exact automated diagnostics message id(s)."
                            )
                        }
                        refreshAggregateState()
                        return completeStep(
                            stepId = stepId,
                            status = AutomatedDiagnosticStepStatus.PASS,
                            summary = "Final runtime state validated and Phase 3 messages cleaned up",
                            evidence = finalValidationEvidence(
                                snapshot = bindings.snapshot(),
                                selectedPeerId = selectedPeerId,
                                cleanedMessageCount = cleanupResult.removedCount,
                                phaseThreeCapturedIdCount = finalPhaseThreeCapturedIdCount,
                                phaseThreeObservationCount = finalPhaseThreeObservationCount,
                                cleanupAttemptedCount = cleanupResult.attemptedCount,
                                cleanupRemainingCount = cleanupResult.remainingCount
                            ),
                            startedAtMillis = startedAtMillis
                        )
                    }
                    updateRunState { current ->
                        current.copy(
                            phaseTwoSummary =
                                "Phase 3 cleanup incomplete: removed ${cleanupResult.removedCount} of ${cleanupResult.attemptedCount} exact automated diagnostics message id(s)."
                        )
                    }
                    refreshAggregateState()
                    return completeStep(
                        stepId = stepId,
                        status = AutomatedDiagnosticStepStatus.FAIL,
                        summary = "Final cleanup failed",
                        blocker =
                            "Phase 3 cleanup removed ${cleanupResult.removedCount} of ${cleanupResult.attemptedCount} exact message ids; ${cleanupResult.remainingCount} remain unconfirmed.",
                        evidence = finalValidationEvidence(
                            snapshot = bindings.snapshot(),
                            selectedPeerId = selectedPeerId,
                            cleanedMessageCount = cleanupResult.removedCount,
                            phaseThreeCapturedIdCount = finalPhaseThreeCapturedIdCount,
                            phaseThreeObservationCount = finalPhaseThreeObservationCount,
                            cleanupAttemptedCount = cleanupResult.attemptedCount,
                            cleanupRemainingCount = cleanupResult.remainingCount
                        ),
                        startedAtMillis = startedAtMillis
                    )
                }
            } else {
                stableSince = null
                updateStep(
                    stepId = stepId,
                    status = AutomatedDiagnosticStepStatus.RUNNING,
                    startedAtMillis = startedAtMillis,
                    elapsedMillis = elapsed,
                    retryCount = stepResult(stepId).retryCount,
                    summary = "Validating final runtime stability",
                    blocker = blocker,
                    evidence = finalValidationEvidence(
                        snapshot = snapshot,
                        selectedPeerId = selectedPeerId,
                        cleanedMessageCount = null
                    ),
                    waitingProgressText =
                    "Waiting ${formatAutomatedDiagnosticsDuration(elapsed)} / ${formatAutomatedDiagnosticsDuration(timingPolicy.finalValidation.timeoutMillis)}",
                    stabilizationProgressText = null
                )
            }
            if (elapsed >= timingPolicy.finalValidation.timeoutMillis) {
                return completeStep(
                    stepId = stepId,
                    status = AutomatedDiagnosticStepStatus.BLOCKED,
                    summary = "Final end-to-end validation blocked",
                    blocker = blocker ?: "Timed out waiting for final runtime stability.",
                    evidence = finalValidationEvidence(
                        snapshot = snapshot,
                        selectedPeerId = selectedPeerId,
                        cleanedMessageCount = null
                    ),
                    startedAtMillis = startedAtMillis
                )
            }
            delay.delayMillis(timingPolicy.pollIntervalMillis)
        }
        return completeStep(
            stepId = stepId,
            status = AutomatedDiagnosticStepStatus.CANCELLED,
            summary = "Cancelled",
            blocker = "Automated diagnostics were cancelled.",
            evidence = finalValidationEvidence(
                snapshot = bindings.snapshot(),
                selectedPeerId = selectedPeerId,
                cleanedMessageCount = null
            ),
            startedAtMillis = startedAtMillis
        )
    }

    private fun matchingApplicationProbeObservations(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        spec: AutomatedDiagnosticsExpectedApplicationProbe,
        minimumObservedAtMillis: Long,
        expectedMessageId: String? = null,
        expectedTransportGroupId: Int? = null
    ): List<AutomatedDiagnosticsApplicationProbeObservation> {
        return snapshot.recentAutomatedDiagnosticsApplicationProbeObservations.filter { observation ->
            automatedDiagnosticsApplicationProbeMatchesExpected(
                observation = observation,
                expectedMarker = spec.marker,
                expectedSenderPeerId = spec.expectedSenderPeerId,
                expectedReceiverPeerId = spec.expectedReceiverPeerId,
                expectedThreadId = spec.expectedThreadId,
                expectedPrivateChatId = spec.expectedPrivateChatId,
                expectedMessageId = expectedMessageId,
                expectedTransportGroupId = expectedTransportGroupId,
                minimumObservedAtMillis = minimumObservedAtMillis
            )
        }
    }

    private fun matchingApplicationProbeReceiveDiagnostics(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        spec: AutomatedDiagnosticsExpectedApplicationProbe,
        minimumObservedAtMillis: Long,
        expectedMessageId: String? = null,
        expectedTransportGroupId: Int? = null
    ): List<AutomatedDiagnosticsApplicationProbeReceiveDiagnostic> {
        return snapshot.recentAutomatedDiagnosticsApplicationProbeReceiveDiagnostics.filter { diagnostic ->
            automatedDiagnosticsApplicationProbeReceiveDiagnosticMatchesExpected(
                diagnostic = diagnostic,
                expectedMarker = spec.marker,
                expectedReceiverPeerId = spec.expectedReceiverPeerId,
                expectedThreadId = spec.expectedThreadId,
                expectedPrivateChatId = spec.expectedPrivateChatId,
                expectedMessageId = expectedMessageId,
                expectedTransportGroupId = expectedTransportGroupId,
                minimumObservedAtMillis = minimumObservedAtMillis
            )
        }
    }

    private fun matchingApplicationProbeTransportReceiveEvents(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        spec: AutomatedDiagnosticsExpectedApplicationProbe,
        minimumObservedAtMillis: Long,
        expectedGroupId: Int? = null
    ): List<AutomatedDiagnosticsApplicationProbeTransportReceiveEvent> {
        return automatedDiagnosticsApplicationProbeMatchingTransportEvents(
            events = snapshot.recentAutomatedDiagnosticsApplicationProbeTransportReceiveEvents,
            minimumObservedAtMillis = minimumObservedAtMillis,
            expectedGroupId = expectedGroupId
        )
    }

    private fun applicationProbeReceiverFrameStatus(
        expectedGroupId: Int?,
        events: List<AutomatedDiagnosticsApplicationProbeTransportReceiveEvent>
    ): String {
        return automatedDiagnosticsApplicationProbeReceiverFrameStatus(
            expectedGroupId = expectedGroupId,
            matchingEvents = events
        )
    }

    private fun applicationProbeLatestTransportResultStatus(
        event: AutomatedDiagnosticsApplicationProbeTransportReceiveEvent?
    ): String {
        return event?.transportResultKind ?: "unavailable"
    }

    private fun applicationProbeReceiverProcessingStatus(
        event: AutomatedDiagnosticsApplicationProbeTransportReceiveEvent?
    ): String {
        return when {
            event == null -> "NOT_SEEN"
            event.transportResultKind == "ProcessorFailed" && event.receiveFailureKind != null ->
                "ReceiveFailed:${event.receiveFailureKind}"
            event.processingResultKind != null -> event.processingResultKind
            else -> event.transportResultKind
        }
    }

    private fun applicationProbeReceiverIngestionStatus(
        event: AutomatedDiagnosticsApplicationProbeTransportReceiveEvent?
    ): String {
        return event?.ingestionResultKind ?: "not-attempted"
    }

    private fun applicationProbeMarkerStatus(
        event: AutomatedDiagnosticsApplicationProbeTransportReceiveEvent?
    ): String {
        return when {
            event == null -> "not-attempted"
            event.marker != null -> "VALID"
            event.processingResultKind == "Received" -> "INVALID"
            else -> "not-attempted"
        }
    }

    private fun applicationProbeEvidence(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        probeSpecs: List<AutomatedDiagnosticsExpectedApplicationProbe>,
        localPeerId: String?,
        submissions: List<AutomatedDiagnosticsProbeSubmission>,
        startedAtMillis: Long
    ): List<AutomatedDiagnosticEvidenceValue> {
        return buildList {
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Phase 3 captured ids",
                    capturedPhaseThreeMessageIds.size.toString()
                )
            )
            localPeerId?.let { peerId ->
                add(AutomatedDiagnosticEvidenceValue("Local peer", peerId))
            }
            probeSpecs.forEach { spec ->
                val submission = submissions.firstOrNull { it.spec == spec }
                val phaseDescriptor =
                    automatedDiagnosticsAuthoritativePhaseApplicationProbeDescriptorOrNull(
                        snapshot = snapshot,
                        expectedMarker = spec.marker,
                        expectedSenderPeerId = spec.expectedSenderPeerId,
                        expectedReceiverPeerId = spec.expectedReceiverPeerId,
                        localPeerId = localPeerId,
                        localSubmissionMessageId = submission?.queuedMessageId,
                        localTransportStatus = submission?.transportStatus,
                        localBleTransportResult = submission?.localBleTransportResult,
                        localExpectedReceiverTransportGroupId =
                            submission?.expectedReceiverTransportGroupId
                    )
                val senderMessageId = phaseDescriptor?.messageId
                val expectedGroupId = phaseDescriptor?.expectedTransportGroupId
                val matchingObservations = matchingApplicationProbeObservations(
                    snapshot = snapshot,
                    spec = spec,
                    minimumObservedAtMillis = startedAtMillis,
                    expectedMessageId = senderMessageId,
                    expectedTransportGroupId = expectedGroupId
                )
                val descriptorExpectedChunkCount = phaseDescriptor?.expectedChunkCount
                val frameByteCount = phaseDescriptor?.frameByteCount
                val matchingTransportReceiveEvents = matchingApplicationProbeTransportReceiveEvents(
                    snapshot = snapshot,
                    spec = spec,
                    minimumObservedAtMillis = startedAtMillis,
                    expectedGroupId = expectedGroupId
                )
                val matchingReceiveDiagnostics = matchingApplicationProbeReceiveDiagnostics(
                    snapshot = snapshot,
                    spec = spec,
                    minimumObservedAtMillis = startedAtMillis,
                    expectedMessageId = senderMessageId,
                    expectedTransportGroupId = expectedGroupId
                )
                val matchingSuccessReceiveDiagnostics = matchingApplicationProbeReceiveDiagnostics(
                    snapshot = snapshot,
                    spec = spec,
                    minimumObservedAtMillis = startedAtMillis
                )
                val logicalMatchedReceiveDiagnostics = matchingSuccessReceiveDiagnostics.distinctBy {
                    diagnostic -> diagnostic.messageId
                }
                val latestTransportEvent = matchingTransportReceiveEvents.lastOrNull()
                val latestReceiveDiagnostic =
                    matchingReceiveDiagnostics.lastOrNull()
                        ?: matchingSuccessReceiveDiagnostics.lastOrNull()
                val latestObservation = matchingObservations.lastOrNull()
                val expectedChunkCount = automatedDiagnosticsApplicationProbeExpectedChunkCount(
                    descriptorExpectedChunkCount = descriptorExpectedChunkCount,
                    matchingEvents = matchingTransportReceiveEvents
                )
                val matchingChunkCount = automatedDiagnosticsApplicationProbeMatchingChunkCount(
                    matchingEvents = matchingTransportReceiveEvents,
                    expectedChunkCount = expectedChunkCount
                )
                val receiverFrameStatus = when {
                    latestTransportEvent != null ->
                        applicationProbeReceiverFrameStatus(
                            expectedGroupId = expectedGroupId,
                            events = matchingTransportReceiveEvents
                        )
                    latestReceiveDiagnostic != null -> "COMPLETE_FRAME_SEEN"
                    else ->
                        applicationProbeReceiverFrameStatus(
                            expectedGroupId = expectedGroupId,
                            events = matchingTransportReceiveEvents
                        )
                }
                val transportResultStatus = latestTransportEvent?.let(
                    ::applicationProbeLatestTransportResultStatus
                ) ?: if (latestReceiveDiagnostic != null) {
                    "Processed"
                } else {
                    "unavailable"
                }
                val processingStatus = latestTransportEvent?.let(
                    ::applicationProbeReceiverProcessingStatus
                ) ?: if (latestReceiveDiagnostic != null) {
                    "Received"
                } else {
                    "NOT_SEEN"
                }
                val ingestionStatus = latestTransportEvent?.let(
                    ::applicationProbeReceiverIngestionStatus
                ) ?: if (latestReceiveDiagnostic != null) {
                    "Appended"
                } else {
                    "not-attempted"
                }
                val markerStatus = latestTransportEvent?.let(
                    ::applicationProbeMarkerStatus
                ) ?: if (latestReceiveDiagnostic != null) {
                    "VALID"
                } else {
                    "not-attempted"
                }
                val frameTypeStatus =
                    latestTransportEvent?.messageType?.name
                        ?: latestReceiveDiagnostic?.messageType?.name
                        ?: "unavailable"
                val effectiveMatchingChunkCount = if (
                    latestTransportEvent == null &&
                        latestReceiveDiagnostic != null &&
                        expectedGroupId == null &&
                        expectedChunkCount != null
                ) {
                    expectedChunkCount
                } else {
                    matchingChunkCount
                }
                val rawWindowEvents = automatedDiagnosticsApplicationProbeTransportEventsWithinWindow(
                    events = snapshot.recentAutomatedDiagnosticsApplicationProbeTransportReceiveEvents,
                    minimumObservedAtMillis = startedAtMillis
                )
                val rawBleGroupsSeenCount = rawWindowEvents
                    .mapNotNull { event -> event.groupId }
                    .distinct()
                    .size
                val recentRawGroupSummaries = automatedDiagnosticsRawBleGroupSummaries(
                    events = snapshot.recentAutomatedDiagnosticsApplicationProbeTransportReceiveEvents,
                    minimumObservedAtMillis = startedAtMillis
                )
                val recentRawGroupsText = automatedDiagnosticsRawBleGroupSummaryText(
                    recentRawGroupSummaries
                )
                add(
                    AutomatedDiagnosticEvidenceValue(
                        "${spec.marker.probeKind.name} ${spec.marker.direction.name} observed",
                        logicalMatchedReceiveDiagnostics.size.toString()
                    )
                )
                add(
                    AutomatedDiagnosticEvidenceValue(
                        "${spec.marker.probeKind.name} ${spec.marker.direction.name} sender message id",
                        senderMessageId ?: latestReceiveDiagnostic?.messageId ?: "unavailable"
                    )
                )
                add(
                    AutomatedDiagnosticEvidenceValue(
                        "${spec.marker.probeKind.name} ${spec.marker.direction.name} submit",
                        submission?.transportStatus ?: phaseDescriptor?.transportStatus ?: "unavailable"
                    )
                )
                add(
                    AutomatedDiagnosticEvidenceValue(
                        "${spec.marker.probeKind.name} ${spec.marker.direction.name} send",
                        submission?.localBleTransportResult
                            ?: phaseDescriptor?.localBleTransportResult
                            ?: "unavailable"
                    )
                )
                frameByteCount?.let { bytes ->
                    add(
                        AutomatedDiagnosticEvidenceValue(
                            "${spec.marker.probeKind.name} ${spec.marker.direction.name} frame bytes",
                            bytes.toString()
                        )
                    )
                }
                phaseDescriptor?.expectedChunkCount?.let { chunkCount ->
                    add(
                        AutomatedDiagnosticEvidenceValue(
                            "${spec.marker.probeKind.name} ${spec.marker.direction.name} chunk count",
                            chunkCount.toString()
                        )
                    )
                }
                phaseDescriptor?.senderChunksQueued?.let { chunksQueued ->
                    add(
                        AutomatedDiagnosticEvidenceValue(
                            "${spec.marker.probeKind.name} ${spec.marker.direction.name} chunks queued",
                            chunksQueued.toString()
                        )
                    )
                }
                phaseDescriptor?.senderChunksWriteAttempted?.let { chunksWriteAttempted ->
                    add(
                        AutomatedDiagnosticEvidenceValue(
                            "${spec.marker.probeKind.name} ${spec.marker.direction.name} chunks write-attempted",
                            chunksWriteAttempted.toString()
                        )
                    )
                }
                phaseDescriptor?.senderLastLocalWriteResult?.let { localWriteResult ->
                    add(
                        AutomatedDiagnosticEvidenceValue(
                            "${spec.marker.probeKind.name} ${spec.marker.direction.name} last local write result",
                            localWriteResult
                        )
                    )
                }
                add(
                    AutomatedDiagnosticEvidenceValue(
                        "${spec.marker.probeKind.name} ${spec.marker.direction.name} expected group id",
                        expectedGroupId?.toString() ?: "unavailable"
                    )
                )
                add(
                    AutomatedDiagnosticEvidenceValue(
                        "${spec.marker.probeKind.name} ${spec.marker.direction.name} correlation",
                        if (expectedGroupId != null) "AVAILABLE" else "UNAVAILABLE"
                    )
                )
                add(
                    AutomatedDiagnosticEvidenceValue(
                        "${spec.marker.probeKind.name} ${spec.marker.direction.name} raw BLE groups seen",
                        rawBleGroupsSeenCount.toString()
                    )
                )
                recentRawGroupsText?.let { summary ->
                    add(
                        AutomatedDiagnosticEvidenceValue(
                            "${spec.marker.probeKind.name} ${spec.marker.direction.name} recent raw groups",
                            summary
                        )
                    )
                }
                add(
                    AutomatedDiagnosticEvidenceValue(
                        "${spec.marker.probeKind.name} ${spec.marker.direction.name} receiver frame",
                        receiverFrameStatus
                    )
                )
                add(
                    AutomatedDiagnosticEvidenceValue(
                        "${spec.marker.probeKind.name} ${spec.marker.direction.name} transport result",
                        transportResultStatus
                    )
                )
                add(
                    AutomatedDiagnosticEvidenceValue(
                        "${spec.marker.probeKind.name} ${spec.marker.direction.name} matching chunks seen",
                        effectiveMatchingChunkCount.toString()
                    )
                )
                add(
                    AutomatedDiagnosticEvidenceValue(
                        "${spec.marker.probeKind.name} ${spec.marker.direction.name} expected chunks",
                        expectedChunkCount?.toString() ?: "unavailable"
                    )
                )
                add(
                    AutomatedDiagnosticEvidenceValue(
                        "${spec.marker.probeKind.name} ${spec.marker.direction.name} processing",
                        processingStatus
                    )
                )
                add(
                    AutomatedDiagnosticEvidenceValue(
                        "${spec.marker.probeKind.name} ${spec.marker.direction.name} ingestion",
                        ingestionStatus
                    )
                )
                add(
                    AutomatedDiagnosticEvidenceValue(
                        "${spec.marker.probeKind.name} ${spec.marker.direction.name} marker",
                        markerStatus
                    )
                )
                add(
                    AutomatedDiagnosticEvidenceValue(
                        "${spec.marker.probeKind.name} ${spec.marker.direction.name} frame type",
                        frameTypeStatus
                    )
                )
                latestTransportEvent?.failureDetail?.let { detail ->
                    add(
                        AutomatedDiagnosticEvidenceValue(
                            "${spec.marker.probeKind.name} ${spec.marker.direction.name} receiver detail",
                            detail
                        )
                    )
                }
                add(
                    AutomatedDiagnosticEvidenceValue(
                        "${spec.marker.probeKind.name} ${spec.marker.direction.name} source resolution attempted",
                        (latestReceiveDiagnostic != null).toString()
                    )
                )
                latestReceiveDiagnostic?.let { diagnostic ->
                    add(
                        AutomatedDiagnosticEvidenceValue(
                            "${spec.marker.probeKind.name} ${spec.marker.direction.name} inbound source",
                            diagnostic.sourceResolution.sourceDeviceAddress ?: "unavailable"
                        )
                    )
                    add(
                        AutomatedDiagnosticEvidenceValue(
                            "${spec.marker.probeKind.name} ${spec.marker.direction.name} exact-address source",
                            diagnostic.sourceResolution.exactAddressSourcePeerId ?: "unresolved"
                        )
                    )
                    add(
                        AutomatedDiagnosticEvidenceValue(
                            "${spec.marker.probeKind.name} ${spec.marker.direction.name} diagnostics-associated source",
                            diagnostic.sourceResolution.diagnosticsAssociatedSourcePeerId
                                ?: "unresolved"
                        )
                    )
                    add(
                        AutomatedDiagnosticEvidenceValue(
                            "${spec.marker.probeKind.name} ${spec.marker.direction.name} association expected receiver",
                            diagnostic.sourceResolution.storedAssociationExpectedRemotePeerId
                                ?: "unavailable"
                        )
                    )
                    add(
                        AutomatedDiagnosticEvidenceValue(
                            "${spec.marker.probeKind.name} ${spec.marker.direction.name} association outcome",
                            diagnostic.sourceResolution.diagnosticsAssociationOutcome?.name
                                ?: "not-needed"
                        )
                    )
                    add(
                        AutomatedDiagnosticEvidenceValue(
                            "${spec.marker.probeKind.name} ${spec.marker.direction.name} resolved source",
                            diagnostic.sourceResolution.resolvedSourcePeerId ?: "unresolved"
                        )
                    )
                    add(
                        AutomatedDiagnosticEvidenceValue(
                            "${spec.marker.probeKind.name} ${spec.marker.direction.name} resolution",
                            diagnostic.sourceResolution.resolutionSource.name
                        )
                    )
                }
                add(
                    AutomatedDiagnosticEvidenceValue(
                        "${spec.marker.probeKind.name} ${spec.marker.direction.name} observation created",
                        (latestObservation != null).toString()
                    )
                )
                latestObservation?.let { observation ->
                    add(
                        AutomatedDiagnosticEvidenceValue(
                            "${spec.marker.probeKind.name} ${spec.marker.direction.name} message",
                            observation.messageId
                        )
                    )
                }
            }
        }
    }

    private fun finalValidationBlocker(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        selectedPeerId: String,
        expectedLocalPhaseThreeMessageCount: Int
    ): String? {
        return when {
            snapshot.bleAdvertiseStatus != BleAdvertiseStatus.ADVERTISING ->
                "BLE advertiser is no longer active."
            snapshot.bleScanStatus != BleScanStatus.SCANNING ->
                "BLE scanner is no longer active."
            snapshot.bleConnectionStatus != BleConnectionStatus.CONNECTED ->
                "BLE transport connection is no longer connected."
            secureSessionBlocker(snapshot, selectedPeerId) != null ->
                secureSessionBlocker(snapshot, selectedPeerId)
            capturedPhaseThreeMessageIds.size < expectedLocalPhaseThreeMessageCount ->
                "Expected at least $expectedLocalPhaseThreeMessageCount local Phase 3 message ids but captured ${capturedPhaseThreeMessageIds.size}."
            phaseThreeDuplicateObservationFingerprints(snapshot).isNotEmpty() ->
                "Duplicate logical Phase 3 application probe observations were recorded."
            else -> null
        }
    }

    private fun finalValidationEvidence(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        selectedPeerId: String,
        cleanedMessageCount: Int?,
        phaseThreeCapturedIdCount: Int = capturedPhaseThreeMessageIds.size,
        cleanupAttemptedCount: Int? = null,
        cleanupRemainingCount: Int? = null,
        phaseThreeObservationCount: Int =
            snapshot.recentAutomatedDiagnosticsApplicationProbeObservations
                .count { it.stepId in automatedDiagnosticsApplicationProbeStepIds }
    ): List<AutomatedDiagnosticEvidenceValue> {
        return buildList {
            add(AutomatedDiagnosticEvidenceValue("Advertiser", snapshot.bleAdvertiseStatus.name))
            add(AutomatedDiagnosticEvidenceValue("Scanner", snapshot.bleScanStatus.name))
            add(AutomatedDiagnosticEvidenceValue("BLE connection", snapshot.bleConnectionStatus.name))
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Secure session ready",
                    (secureSessionBlocker(snapshot, selectedPeerId) == null).toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Phase 3 captured ids",
                    phaseThreeCapturedIdCount.toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Phase 3 observations",
                    phaseThreeObservationCount.toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Wi-Fi Direct role",
                    snapshot.wifiDirectRuntimeStatus.connectionStatus.role.name
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Wi-Fi Direct socket",
                    snapshot.wifiDirectSocketDiagnostics.state.name
                )
            )
            cleanedMessageCount?.let { removed ->
                add(AutomatedDiagnosticEvidenceValue("Cleaned message ids", removed.toString()))
            }
            cleanupAttemptedCount?.let { attempted ->
                add(AutomatedDiagnosticEvidenceValue("Cleanup attempted ids", attempted.toString()))
            }
            cleanupRemainingCount?.let { remaining ->
                add(AutomatedDiagnosticEvidenceValue("Cleanup remaining ids", remaining.toString()))
            }
        }
    }

    private fun capturePhaseThreeMessageId(
        messageId: String?
    ) {
        val sanitizedMessageId = messageId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        capturedPhaseThreeMessageIds += sanitizedMessageId
    }

    private fun removeCapturedPhaseThreeMessages(): PhaseThreeCleanupResult {
        if (capturedPhaseThreeMessageIds.isEmpty()) {
            return PhaseThreeCleanupResult(
                attemptedIds = emptySet(),
                removedIds = emptySet()
            )
        }
        val capturedIds = capturedPhaseThreeMessageIds.toSet()
        val removedIds = bindings.commands.removeMessagesByIds(capturedIds)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
            .intersect(capturedIds)
        capturedPhaseThreeMessageIds.removeAll(removedIds)
        return PhaseThreeCleanupResult(
            attemptedIds = capturedIds,
            removedIds = removedIds
        )
    }

    private fun phaseThreeDuplicateObservationFingerprints(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot
    ): Set<String> {
        return snapshot.recentAutomatedDiagnosticsApplicationProbeObservations
            .filter { it.stepId in automatedDiagnosticsApplicationProbeStepIds }
            .groupBy { automatedDiagnosticsApplicationProbeFingerprint(it.marker) }
            .filterValues { observations -> observations.size > 1 }
            .keys
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
            connectionStatus.state == WifiDirectConnectionState.FAILED ||
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

    private fun resetWifiDirectDiscoveryAttemptState(
        context: AutomatedDiagnosticsStepContext
    ) {
        clearStaleWifiDirectStepState(context)
        context.wifiDirectCorrelationToken = null
        context.wifiDirectDnsSdRegisteredCorrelationToken = null
        context.wifiDirectCurrentRunTokenProofReady = false
        context.wifiDirectCurrentRunDnsSdRegistrationObserved = false
        context.wifiDirectCurrentRunDnsSdProofReady = false
        context.wifiDirectPeerReadySendAttempts = 0
        context.wifiDirectPeerReadySuccessfulSends = 0
        context.wifiDirectPeerReadyReceivedCount = 0
        context.wifiDirectPeerReadyAcceptedCount = 0
        context.wifiDirectPeerReadyValidationCounters = AutomatedDiagnosticsCoordinationCounters()
        context.wifiDirectPeerReadyLastRejectedReason = null
        context.wifiDirectPeerReadyLastRejectedField = null
        context.wifiDirectPeerReadyLastRejectedExpectedValue = null
        context.wifiDirectPeerReadyLastRejectedObservedValue = null
        context.lastWifiDirectDnsSdServiceRegistrationAtMillis = null
        context.lastWifiDirectDnsSdDiscoveryStartAtMillis = null
    }

    private fun updateWifiDirectCurrentRunProofs(
        localRole: AutomatedDiagnosticsPeerRole,
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        acceptedRemoteSignal: AutomatedDiagnosticsWifiDirectPeerReadySignal?,
        matchingDnsSdResponses: List<WifiDirectDnsSdServiceResponse>,
        context: AutomatedDiagnosticsStepContext
    ) {
        val currentCorrelationToken = context.wifiDirectCorrelationToken
        if (
            localRole == AutomatedDiagnosticsPeerRole.PARTICIPANT &&
            currentCorrelationToken != null &&
            context.wifiDirectPeerReadySuccessfulSends > 0
        ) {
            context.wifiDirectCurrentRunTokenProofReady = true
        }
        if (
            localRole == AutomatedDiagnosticsPeerRole.COORDINATOR &&
            acceptedRemoteSignal != null
        ) {
            context.wifiDirectCurrentRunTokenProofReady = true
        }
        if (
            localRole == AutomatedDiagnosticsPeerRole.PARTICIPANT &&
            currentCorrelationToken != null &&
            snapshot.wifiDirectRuntimeStatus.dnsSdDiagnostics.localServiceRegistered &&
            context.wifiDirectDnsSdRegisteredCorrelationToken == currentCorrelationToken
        ) {
            context.wifiDirectCurrentRunDnsSdRegistrationObserved = true
            context.wifiDirectCurrentRunDnsSdProofReady = true
        }
        if (
            localRole == AutomatedDiagnosticsPeerRole.COORDINATOR &&
            acceptedRemoteSignal != null &&
            matchingDnsSdResponses.size == 1
        ) {
            context.wifiDirectCurrentRunDnsSdProofReady = true
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
        context: AutomatedDiagnosticsStepContext,
        expectedPeerId: String,
        expectedSessionId: String
    ): String {
        val acceptedObservation = context.hybridAcceptAcceptedObservation
        if (acceptedObservation != null) {
            return "Hybrid bootstrap accept already recorded for the current Step 15 attempt."
        }
        val observation = snapshot.latestAutomatedDiagnosticsHybridAcceptObservation
        val failure = observation?.let {
            hybridBootstrapAcceptObservationValidationFailureOrNull(
                observation = it,
                expectedPeerId = expectedPeerId,
                expectedSessionId = expectedSessionId,
                minimumObservedAtMonotonicMillis =
                    context.hybridAcceptAttemptStartedAtMonotonicMillis ?: 0L
            )
        }
        return when {
            failure != null ->
                hybridBootstrapAcceptRejectionBlocker(expectedPeerId, failure)
            context.hybridAcceptLastRejectedReason != null ->
                hybridBootstrapAcceptRejectionBlocker(
                    expectedPeerId = expectedPeerId,
                    failure = HybridAcceptObservationValidationFailure(
                        reason = requireNotNull(context.hybridAcceptLastRejectedReason),
                        fieldName =
                            context.hybridAcceptLastRejectedField
                                ?: "latestAutomatedDiagnosticsHybridAcceptObservation",
                        expectedValue =
                            context.hybridAcceptLastRejectedExpectedValue ?: expectedSessionId,
                        observedValue =
                            context.hybridAcceptLastRejectedObservedValue
                                ?: "none"
                    )
                )
            else ->
                "Waiting for participant $expectedPeerId to send and record the current hybrid bootstrap accept."
        }
    }

    private fun participantHybridBootstrapAcceptBlocker(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        context: AutomatedDiagnosticsStepContext
    ): String {
        val remotePhaseState = context.currentPhaseObservedRemoteSignal?.phaseState
        return when {
            remotePhaseState == AutomatedDiagnosticsPhaseState.PASS ->
                "Coordinator Step 15 PASS observed."
            remotePhaseState == AutomatedDiagnosticsPhaseState.READY ->
                "Hybrid bootstrap accept sent, waiting for the coordinator to publish Step 15 PASS."
            remotePhaseState == AutomatedDiagnosticsPhaseState.RUNNING ->
                "Coordinator is processing the current hybrid bootstrap accept."
            context.currentPhaseLastRejectedReason != null ->
                "Waiting for a fresh coordinator Step 15 PASS signal (last rejection: ${context.currentPhaseLastRejectedReason?.statusText})."
            context.hybridAcceptSuccessfulSendCount > 0 ->
                "Hybrid bootstrap accept sent, waiting for the coordinator to receive and confirm it."
            snapshot.hybridBootstrapManualAcceptBlockedReason != null ->
                snapshot.hybridBootstrapManualAcceptBlockedReason
                    ?: "Hybrid bootstrap accept is blocked."
            context.hybridAcceptLastSendResult != null ->
                "Waiting for coordinator confirmation after accept result ${context.hybridAcceptLastSendResult}."
            else ->
                "Waiting to send and confirm the current hybrid bootstrap accept."
        }
    }

    private fun hybridBootstrapAcceptObservationValidationFailureOrNull(
        observation: AutomatedDiagnosticsHybridAcceptObservation,
        expectedPeerId: String,
        expectedSessionId: String,
        minimumObservedAtMonotonicMillis: Long
    ): HybridAcceptObservationValidationFailure? {
        if (!observation.recorded) {
            val reason = when (observation.storeResult) {
                HybridTransportControlStore.RecordResult.IgnoredOlderMessage ->
                    AutomatedDiagnosticsCoordinationRejectionReason.STALE
                HybridTransportControlStore.RecordResult.IgnoredInvalidPeerId,
                HybridTransportControlStore.RecordResult.IgnoredNonBootstrapMessageType ->
                    AutomatedDiagnosticsCoordinationRejectionReason.INVALID_PAYLOAD
                HybridTransportControlStore.RecordResult.Stored ->
                    AutomatedDiagnosticsCoordinationRejectionReason.INVALID_PAYLOAD
            }
            return HybridAcceptObservationValidationFailure(
                reason = reason,
                fieldName = "observation.storeResult",
                expectedValue = hybridBootstrapAcceptStoreResultStatusText(
                    HybridTransportControlStore.RecordResult.Stored
                ),
                observedValue = hybridBootstrapAcceptStoreResultStatusText(observation.storeResult)
            )
        }
        if (observation.peerId != expectedPeerId) {
            return HybridAcceptObservationValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER,
                fieldName = "observation.peerId",
                expectedValue = expectedPeerId,
                observedValue = observation.peerId
            )
        }
        val publicPeerIdHint = observation.publicPeerIdHint?.trim()?.takeIf { it.isNotEmpty() }
        if (publicPeerIdHint != null && publicPeerIdHint != expectedPeerId) {
            return HybridAcceptObservationValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER,
                fieldName = "observation.publicPeerIdHint",
                expectedValue = expectedPeerId,
                observedValue = publicPeerIdHint
            )
        }
        if (observation.sessionId != expectedSessionId) {
            return HybridAcceptObservationValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_SESSION,
                fieldName = "observation.sessionId",
                expectedValue = expectedSessionId,
                observedValue = observation.sessionId
            )
        }
        if (observation.observedAtMonotonicMillis < minimumObservedAtMonotonicMillis) {
            return HybridAcceptObservationValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.STALE,
                fieldName = "observation.observedAtMonotonicMillis",
                expectedValue = ">=$minimumObservedAtMonotonicMillis",
                observedValue = observation.observedAtMonotonicMillis.toString()
            )
        }
        return null
    }

    private fun hybridBootstrapAcceptRejectionBlocker(
        expectedPeerId: String,
        failure: HybridAcceptObservationValidationFailure
    ): String {
        return buildString {
            append("Waiting for a valid hybrid bootstrap accept from ")
            append(expectedPeerId)
            append(" (last rejection: ")
            append(failure.reason.statusText)
            append(", field=")
            append(failure.fieldName)
            append(", expected=")
            append(failure.expectedValue)
            append(", observed=")
            append(failure.observedValue)
            append(").")
        }
    }

    private fun hybridBootstrapSocketHintObservationValidationFailureOrNull(
        observation: AutomatedDiagnosticsHybridSocketHintObservation,
        expectedPeerId: String,
        expectedSessionId: String,
        expectedGroupOwnerAddress: String,
        expectedSocketPort: Int,
        minimumObservedAtMonotonicMillis: Long
    ): HybridSocketHintObservationValidationFailure? {
        if (!observation.recorded) {
            val reason = when (observation.storeResult) {
                HybridTransportControlStore.RecordResult.IgnoredOlderMessage ->
                    AutomatedDiagnosticsCoordinationRejectionReason.STALE
                HybridTransportControlStore.RecordResult.IgnoredInvalidPeerId,
                HybridTransportControlStore.RecordResult.IgnoredNonBootstrapMessageType ->
                    AutomatedDiagnosticsCoordinationRejectionReason.INVALID_PAYLOAD
                HybridTransportControlStore.RecordResult.Stored ->
                    AutomatedDiagnosticsCoordinationRejectionReason.INVALID_PAYLOAD
            }
            return HybridSocketHintObservationValidationFailure(
                reason = reason,
                fieldName = "observation.storeResult",
                expectedValue = hybridBootstrapAcceptStoreResultStatusText(
                    HybridTransportControlStore.RecordResult.Stored
                ),
                observedValue = hybridBootstrapAcceptStoreResultStatusText(observation.storeResult)
            )
        }
        if (observation.peerId != expectedPeerId) {
            return HybridSocketHintObservationValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER,
                fieldName = "observation.peerId",
                expectedValue = expectedPeerId,
                observedValue = observation.peerId
            )
        }
        val publicPeerIdHint = observation.publicPeerIdHint?.trim()?.takeIf { it.isNotEmpty() }
        if (publicPeerIdHint != null && publicPeerIdHint != expectedPeerId) {
            return HybridSocketHintObservationValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER,
                fieldName = "observation.publicPeerIdHint",
                expectedValue = expectedPeerId,
                observedValue = publicPeerIdHint
            )
        }
        if (observation.sessionId != expectedSessionId) {
            return HybridSocketHintObservationValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_SESSION,
                fieldName = "observation.sessionId",
                expectedValue = expectedSessionId,
                observedValue = observation.sessionId
            )
        }
        if (observation.groupOwnerAddress != expectedGroupOwnerAddress) {
            return HybridSocketHintObservationValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.INVALID_PAYLOAD,
                fieldName = "observation.groupOwnerAddress",
                expectedValue = expectedGroupOwnerAddress,
                observedValue = observation.groupOwnerAddress
            )
        }
        if (observation.socketPort != expectedSocketPort) {
            return HybridSocketHintObservationValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.INVALID_PAYLOAD,
                fieldName = "observation.socketPort",
                expectedValue = expectedSocketPort.toString(),
                observedValue = observation.socketPort.toString()
            )
        }
        if (observation.observedAtMonotonicMillis < minimumObservedAtMonotonicMillis) {
            return HybridSocketHintObservationValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.STALE,
                fieldName = "observation.observedAtMonotonicMillis",
                expectedValue = ">=$minimumObservedAtMonotonicMillis",
                observedValue = observation.observedAtMonotonicMillis.toString()
            )
        }
        return null
    }

    private fun hybridBootstrapSocketHintRejectionBlocker(
        expectedPeerId: String,
        failure: HybridSocketHintObservationValidationFailure
    ): String {
        return buildString {
            append("Waiting for a valid hybrid bootstrap socket hint from ")
            append(expectedPeerId)
            append(" (last rejection: ")
            append(failure.reason.statusText)
            append(", field=")
            append(failure.fieldName)
            append(", expected=")
            append(failure.expectedValue)
            append(", observed=")
            append(failure.observedValue)
            append(").")
        }
    }

    private fun hybridBootstrapSocketHintBlocker(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        context: AutomatedDiagnosticsStepContext,
        expectedPeerId: String,
        expectedSessionId: String,
        expectedGroupOwnerAddress: String,
        expectedSocketPort: Int
    ): String {
        val acceptedObservation = recentAcceptedHybridBootstrapSocketHintObservationOrNull(
            snapshot = snapshot,
            context = context,
            expectedPeerId = expectedPeerId,
            expectedSessionId = expectedSessionId,
            expectedGroupOwnerAddress = expectedGroupOwnerAddress,
            expectedSocketPort = expectedSocketPort
        )
        return when {
            acceptedObservation != null &&
                matchingAcceptedSocketReadyHybridCandidateOrNull(
                    snapshot = snapshot,
                    expectedPeerId = expectedPeerId,
                    expectedSessionId = expectedSessionId,
                    expectedGroupOwnerAddress = expectedGroupOwnerAddress,
                    expectedSocketPort = expectedSocketPort
                ) != null ->
                "Hybrid bootstrap socket hint already recorded for the current Step 16 attempt."
            acceptedObservation != null ->
                "Hybrid bootstrap socket hint recorded, waiting for matching socket-ready stabilization."
            context.hybridSocketHintLastRejectedReason != null ->
                hybridBootstrapSocketHintRejectionBlocker(
                    expectedPeerId = expectedPeerId,
                    failure = HybridSocketHintObservationValidationFailure(
                        reason = requireNotNull(context.hybridSocketHintLastRejectedReason),
                        fieldName = context.hybridSocketHintLastRejectedField
                            ?: "observation",
                        expectedValue =
                            context.hybridSocketHintLastRejectedExpectedValue
                                ?: expectedSessionId,
                        observedValue =
                            context.hybridSocketHintLastRejectedObservedValue ?: "none"
                    )
                )
            else ->
                "Waiting for a valid hybrid bootstrap socket hint from the Wi-Fi Direct group owner."
        }
    }

    private fun hybridBootstrapTriggerBlocker(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        context: AutomatedDiagnosticsStepContext,
        expectedPeerId: String,
        expectedSessionId: String,
        expectedGroupOwnerAddress: String,
        expectedSocketPort: Int
    ): String {
        if (
            recentAcceptedHybridBootstrapSocketHintObservationOrNull(
                snapshot = snapshot,
                context = context,
                expectedPeerId = expectedPeerId,
                expectedSessionId = expectedSessionId,
                expectedGroupOwnerAddress = expectedGroupOwnerAddress,
                expectedSocketPort = expectedSocketPort
            ) == null ||
            matchingAcceptedSocketReadyHybridCandidateOrNull(
                snapshot = snapshot,
                expectedPeerId = expectedPeerId,
                expectedSessionId = expectedSessionId,
                expectedGroupOwnerAddress = expectedGroupOwnerAddress,
                expectedSocketPort = expectedSocketPort
            ) == null
        ) {
            return hybridBootstrapSocketHintBlocker(
                snapshot = snapshot,
                context = context,
                expectedPeerId = expectedPeerId,
                expectedSessionId = expectedSessionId,
                expectedGroupOwnerAddress = expectedGroupOwnerAddress,
                expectedSocketPort = expectedSocketPort
            )
        }
        return snapshot.hybridBootstrapManualTriggerSnapshot.triggerStatusText
            ?: snapshot.hybridBootstrapManualTriggerSnapshot.commandStatusText
            ?: "Hybrid bootstrap command is not ready."
    }

    private fun groupOwnerHybridBootstrapSocketHintBlocker(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        context: AutomatedDiagnosticsStepContext
    ): String {
        return wifiDirectSocketBlocker(snapshot)
            ?: when {
                context.currentPhaseObservedRemoteSignal?.phaseState ==
                    AutomatedDiagnosticsPhaseState.PASS ->
                    "Client confirmed the hybrid bootstrap socket hint."
                context.hybridSocketHintSuccessfulSendCount > 0 ->
                    "Waiting for client confirmation after socket hint result ${context.hybridSocketHintLastSendResult ?: "sent"}."
                context.hybridSocketHintLastSendResult != null ->
                    "Waiting for client confirmation after socket hint result ${context.hybridSocketHintLastSendResult}."
                else ->
                    "Preparing to send the hybrid bootstrap socket hint to the Wi-Fi Direct client."
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

    private fun matchingAcceptedSocketReadyHybridCandidateOrNull(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        expectedPeerId: String,
        expectedSessionId: String,
        expectedGroupOwnerAddress: String,
        expectedSocketPort: Int
    ): HybridBootstrapCandidate? {
        val expectedCanonicalPeerId =
            snapshot.peerSessionDiagnostics.canonicalPeerIdFor(expectedPeerId)
                ?: expectedPeerId
        return snapshot.hybridBootstrapDecision.candidates.firstOrNull { candidate ->
            candidate.hasOffer &&
                candidate.hasAccept &&
                candidate.hasSocketHint &&
                candidate.socketReady &&
                candidate.sessionId == expectedSessionId &&
                candidate.groupOwnerAddress == expectedGroupOwnerAddress &&
                candidate.socketPort == expectedSocketPort &&
                (
                    snapshot.peerSessionDiagnostics.canonicalPeerIdFor(candidate.peerId)
                        ?: candidate.peerId.trim().takeIf { it.isNotEmpty() }
                ) == expectedCanonicalPeerId
        }
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
        val rejection = validateAutomatedDiagnosticsServerReadySignalForSocketStep(
            signal = signal,
            expectedRun = expectedRun,
            expectedOwnerPeerId = expectedOwnerPeerId,
            expectedClientPeerId = expectedClientPeerId,
            activeTransportPeerId =
                snapshot.activeTransportPeerId?.trim()?.takeIf { it.isNotEmpty() },
            localPeerId = snapshot.localPeerId?.trim()?.takeIf { it.isNotEmpty() },
            localWifiDirectRole = snapshot.wifiDirectRuntimeStatus.connectionStatus.role,
            observedAgeMillis = now - observedAtMonotonicMillis,
            effectiveLeaseDurationMillis = leaseDurationMillis,
            minimumCreatedAtMillis = minimumCreatedAtMillis,
            groupReady = !requireGroupReady || wifiDirectGroupReady(snapshot)
        )
        if (rejection != null) {
            if (isNewObservation) {
                context.coordinationCounters =
                    context.coordinationCounters.recordRejected(rejection.reason)
            }
            context.serverReadyLastRejectedReason = rejection.reason
            context.serverReadyLastRejectedField = rejection.fieldName
            context.serverReadyLastRejectedExpectedValue = rejection.expectedValue
            context.serverReadyLastRejectedObservedValue = rejection.observedValue
            return null
        }
        if (isNewObservation) {
            context.coordinationCounters = context.coordinationCounters.recordAccepted()
            context.serverReadyAcceptedCount += 1
            context.serverReadyLastRejectedReason = null
            context.serverReadyLastRejectedField = null
            context.serverReadyLastRejectedExpectedValue = null
            context.serverReadyLastRejectedObservedValue = null
        }
        return signal
    }

    private fun recentAcceptedAutomatedDiagnosticsPhaseSignalOrNull(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        expectedRun: AutomatedDiagnosticsSharedRun,
        expectedSenderPeerId: String,
        expectedRecipientPeerId: String,
        expectedStepId: AutomatedDiagnosticStepId,
        expectedAttemptNumber: Int,
        context: AutomatedDiagnosticsStepContext,
        allowLatestFallback: Boolean = true
    ): AutomatedDiagnosticsPhaseSignal? {
        val signal =
            snapshot.latestAutomatedDiagnosticsPhaseSignalsByStep[expectedStepId]
                ?: if (allowLatestFallback) {
                    snapshot.latestAutomatedDiagnosticsPhaseSignal
                } else {
                    null
                }
                ?: return null
        val signature = phaseSignalSignature(signal)
        val now = clock.nowMillis()
        val isNewObservation = context.markPhaseSignalObserved(signature, now)
        if (isNewObservation) {
            context.currentPhaseReceiveCount += 1
        }
        val activeTransportPeerId = snapshot.activeTransportPeerId?.trim()?.takeIf { it.isNotEmpty() }
        val localPeerId = snapshot.localPeerId?.trim()?.takeIf { it.isNotEmpty() }
        val observedAtMonotonicMillis =
            context.lastObservedPhaseSignalObservedAtMonotonicMillis
                ?: now
        val embeddedLeaseDurationMillis =
            (signal.expiresAtMillis - signal.createdAtMillis).coerceAtLeast(0L)
        val effectiveLeaseDurationMillis = minOf(
            embeddedLeaseDurationMillis.takeIf { it > 0L }
                ?: timingPolicy.automatedDiagnosticsPhaseStateLeaseMillis,
            timingPolicy.automatedDiagnosticsPhaseStateLeaseMillis
        )
        val rejection = validateAutomatedDiagnosticsPhaseSignalForBarrier(
            signal = signal,
            expectedRun = expectedRun,
            expectedSenderPeerId = expectedSenderPeerId,
            expectedRecipientPeerId = expectedRecipientPeerId,
            expectedStepId = expectedStepId,
            expectedAttemptNumber = expectedAttemptNumber,
            activeTransportPeerId = activeTransportPeerId,
            localPeerId = localPeerId,
            observedAgeMillis = (now - observedAtMonotonicMillis).coerceAtLeast(0L),
            effectiveLeaseDurationMillis = effectiveLeaseDurationMillis
        )?.let { failure ->
            coordinationValidationFailure(
                reason = failure.reason,
                functionName = "recentAcceptedAutomatedDiagnosticsPhaseSignalOrNull",
                fieldName = failure.fieldName,
                expectedValue = failure.expectedValue,
                observedValue = failure.observedValue
            )
        }
        if (rejection != null) {
            context.currentPhaseLastRejectedReason = rejection.reason
            if (
                rejection.reason == AutomatedDiagnosticsCoordinationRejectionReason.STALE &&
                context.currentPhaseObservedRemoteSignal?.let(::phaseSignalSignature) == signature
            ) {
                context.currentPhaseObservedRemoteSignal = null
            }
            return null
        }
        if (isNewObservation) {
            context.currentPhaseAcceptedCount += 1
        }
        context.currentPhaseLastRejectedReason = null
        context.currentPhaseObservedRemoteSignal = signal
        bindings.commands
            .recordAcceptedAutomatedDiagnosticsPhaseSignalSourceAssociation(signal)
        return context.currentPhaseObservedRemoteSignal ?: signal
    }

    private fun recentAcceptedHybridBootstrapAcceptObservationOrNull(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        context: AutomatedDiagnosticsStepContext,
        expectedPeerId: String,
        expectedSessionId: String
    ): AutomatedDiagnosticsHybridAcceptObservation? {
        val observation = snapshot.latestAutomatedDiagnosticsHybridAcceptObservation
            ?: return context.hybridAcceptAcceptedObservation
        val signature = hybridAcceptObservationSignature(observation)
        val isNewObservation = context.markHybridAcceptObserved(
            signature = signature,
            observedAtMonotonicMillis = observation.observedAtMonotonicMillis
        )
        if (isNewObservation) {
            context.hybridAcceptObservedCount += 1
        }
        context.hybridAcceptLastObservedPeerId = observation.peerId
        context.hybridAcceptLastObservedSessionId = observation.sessionId
        context.hybridAcceptLastObservedStoreResult =
            hybridBootstrapAcceptStoreResultStatusText(observation.storeResult)
        val failure = hybridBootstrapAcceptObservationValidationFailureOrNull(
            observation = observation,
            expectedPeerId = expectedPeerId,
            expectedSessionId = expectedSessionId,
            minimumObservedAtMonotonicMillis =
                context.hybridAcceptAttemptStartedAtMonotonicMillis ?: 0L
        )
        if (failure != null) {
            if (isNewObservation) {
                context.hybridAcceptLastRejectedReason = failure.reason
                context.hybridAcceptLastRejectedField = failure.fieldName
                context.hybridAcceptLastRejectedExpectedValue = failure.expectedValue
                context.hybridAcceptLastRejectedObservedValue = failure.observedValue
            }
            return context.hybridAcceptAcceptedObservation
        }
        if (isNewObservation) {
            context.hybridAcceptAcceptedCount += 1
        }
        context.hybridAcceptLastRejectedReason = null
        context.hybridAcceptLastRejectedField = null
        context.hybridAcceptLastRejectedExpectedValue = null
        context.hybridAcceptLastRejectedObservedValue = null
        context.hybridAcceptAcceptedObservation = observation
        return context.hybridAcceptAcceptedObservation ?: observation
    }

    private fun recentAcceptedHybridBootstrapSocketHintObservationOrNull(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        context: AutomatedDiagnosticsStepContext,
        expectedPeerId: String,
        expectedSessionId: String,
        expectedGroupOwnerAddress: String,
        expectedSocketPort: Int
    ): AutomatedDiagnosticsHybridSocketHintObservation? {
        val observation = snapshot.latestAutomatedDiagnosticsHybridSocketHintObservation
            ?: return context.hybridSocketHintAcceptedObservation
        val signature = hybridSocketHintObservationSignature(observation)
        val isNewObservation = context.markHybridSocketHintObserved(
            signature = signature,
            observedAtMonotonicMillis = observation.observedAtMonotonicMillis
        )
        if (isNewObservation) {
            context.hybridSocketHintObservedCount += 1
        }
        context.hybridSocketHintLastObservedPeerId = observation.peerId
        context.hybridSocketHintLastObservedSessionId = observation.sessionId
        context.hybridSocketHintLastObservedGroupOwnerAddress = observation.groupOwnerAddress
        context.hybridSocketHintLastObservedSocketPort = observation.socketPort
        context.hybridSocketHintLastObservedStoreResult =
            hybridBootstrapAcceptStoreResultStatusText(observation.storeResult)
        val failure = hybridBootstrapSocketHintObservationValidationFailureOrNull(
            observation = observation,
            expectedPeerId = expectedPeerId,
            expectedSessionId = expectedSessionId,
            expectedGroupOwnerAddress = expectedGroupOwnerAddress,
            expectedSocketPort = expectedSocketPort,
            minimumObservedAtMonotonicMillis =
                context.hybridSocketHintAttemptStartedAtMonotonicMillis ?: 0L
        )
        if (failure != null) {
            if (isNewObservation) {
                context.hybridSocketHintLastRejectedReason = failure.reason
                context.hybridSocketHintLastRejectedField = failure.fieldName
                context.hybridSocketHintLastRejectedExpectedValue = failure.expectedValue
                context.hybridSocketHintLastRejectedObservedValue = failure.observedValue
            }
            return context.hybridSocketHintAcceptedObservation
        }
        if (isNewObservation) {
            context.hybridSocketHintAcceptedCount += 1
        }
        context.hybridSocketHintLastRejectedReason = null
        context.hybridSocketHintLastRejectedField = null
        context.hybridSocketHintLastRejectedExpectedValue = null
        context.hybridSocketHintLastRejectedObservedValue = null
        context.hybridSocketHintAcceptedObservation = observation
        return context.hybridSocketHintAcceptedObservation ?: observation
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
                if (
                    !context.wifiDirectCurrentRunValidatedPeerProofReady &&
                    context.selectedWifiDirectPeerSource == validatedDnsSdTokenPeerSource
                ) {
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
                    "Current-run DNS-SD registration observed",
                    (context?.wifiDirectCurrentRunDnsSdRegistrationObserved ?: false).toString()
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
        val latestServerReadyObservationSource = when {
            rawServerReadySignal == null -> "none"
            localPeerId != null && rawServerReadySignal.peerId == localPeerId -> "locally-emitted"
            remotePeerId != null && rawServerReadySignal.peerId == remotePeerId -> "remotely-received"
            else -> "other-peer"
        }
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
                    "Latest SERVER_READY observation source",
                    latestServerReadyObservationSource
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Latest received SERVER_READY coordinator peer id",
                    rawServerReadySignal?.sharedRun?.coordinatorPeerId ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Latest received SERVER_READY participant peer id",
                    rawServerReadySignal?.sharedRun?.participantPeerId ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Latest received SERVER_READY transport sender peer id",
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
                    "Last SERVER_READY rejection field",
                    context?.serverReadyLastRejectedField ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Last SERVER_READY rejection expected",
                    context?.serverReadyLastRejectedExpectedValue ?: "none"
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Last SERVER_READY rejection observed",
                    context?.serverReadyLastRejectedObservedValue ?: "none"
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

    private fun hybridBootstrapAcceptEvidence(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        context: AutomatedDiagnosticsStepContext
    ): List<AutomatedDiagnosticEvidenceValue> {
        return buildList {
            addAll(hybridBootstrapEvidence(snapshot))
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Accept send attempts",
                    context.hybridAcceptSendAttemptCount.toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Accept sends ok",
                    context.hybridAcceptSuccessfulSendCount.toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Accept observed",
                    context.hybridAcceptObservedCount.toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Accept recorded",
                    context.hybridAcceptAcceptedCount.toString()
                )
            )
            context.hybridAcceptLastSendResult?.let { result ->
                add(AutomatedDiagnosticEvidenceValue("Accept last send", result))
            }
            context.hybridAcceptLastSentPeerId?.let { peerId ->
                add(AutomatedDiagnosticEvidenceValue("Accept peer", peerId))
            }
            context.hybridAcceptLastSentSessionId?.let { sessionId ->
                add(AutomatedDiagnosticEvidenceValue("Accept session", sessionId))
            }
            context.hybridAcceptLastSentAtMonotonicMillis?.let { observedAt ->
                add(
                    AutomatedDiagnosticEvidenceValue(
                        "Accept send age",
                        formatAutomatedDiagnosticsDuration(
                            (clock.nowMillis() - observedAt).coerceAtLeast(0L)
                        )
                    )
                )
            }
            context.hybridAcceptLastObservedPeerId?.let { peerId ->
                add(AutomatedDiagnosticEvidenceValue("Accept observed peer", peerId))
            }
            context.hybridAcceptLastObservedSessionId?.let { sessionId ->
                add(AutomatedDiagnosticEvidenceValue("Accept observed session", sessionId))
            }
            context.hybridAcceptLastObservedStoreResult?.let { result ->
                add(AutomatedDiagnosticEvidenceValue("Accept store result", result))
            }
            context.lastObservedHybridAcceptObservedAtMonotonicMillis?.let { observedAt ->
                add(
                    AutomatedDiagnosticEvidenceValue(
                        "Accept observed age",
                        formatAutomatedDiagnosticsDuration(
                            (clock.nowMillis() - observedAt).coerceAtLeast(0L)
                        )
                    )
                )
            }
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Accept current-attempt match",
                    (context.hybridAcceptAcceptedObservation != null).toString()
                )
            )
            context.currentPhaseObservedRemoteSignal?.let { signal ->
                add(
                    AutomatedDiagnosticEvidenceValue(
                        "Coordinator Step15 state",
                        signal.phaseState.name
                    )
                )
            }
            context.hybridAcceptLastRejectedReason?.let { reason ->
                add(
                    AutomatedDiagnosticEvidenceValue(
                        "Accept last rejection",
                        buildString {
                            append(reason.statusText)
                            context.hybridAcceptLastRejectedField?.let { field ->
                                append(" @ ")
                                append(field)
                            }
                            context.hybridAcceptLastRejectedExpectedValue?.let { expected ->
                                append(" expected=")
                                append(expected)
                            }
                            context.hybridAcceptLastRejectedObservedValue?.let { observed ->
                                append(" observed=")
                                append(observed)
                            }
                        }
                    )
                )
            }
        }
    }

    private fun hybridBootstrapSocketHintEvidence(
        snapshot: AutomatedDiagnosticsRuntimeSnapshot,
        context: AutomatedDiagnosticsStepContext
    ): List<AutomatedDiagnosticEvidenceValue> {
        return buildList {
            addAll(hybridBootstrapEvidence(snapshot))
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Socket hint send attempts",
                    context.hybridSocketHintSendAttemptCount.toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Socket hint sends ok",
                    context.hybridSocketHintSuccessfulSendCount.toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Socket hint observed",
                    context.hybridSocketHintObservedCount.toString()
                )
            )
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Socket hint recorded",
                    context.hybridSocketHintAcceptedCount.toString()
                )
            )
            context.hybridSocketHintLastSendResult?.let { result ->
                add(AutomatedDiagnosticEvidenceValue("Socket hint last send", result))
            }
            context.hybridSocketHintLastSentPeerId?.let { peerId ->
                add(AutomatedDiagnosticEvidenceValue("Hint peer", peerId))
            }
            context.hybridSocketHintLastSentSessionId?.let { sessionId ->
                add(AutomatedDiagnosticEvidenceValue("Hint session", sessionId))
            }
            context.hybridSocketHintLastSentGroupOwnerAddress?.let { address ->
                add(AutomatedDiagnosticEvidenceValue("Hint address", address))
            }
            context.hybridSocketHintLastSentSocketPort?.let { socketPort ->
                add(AutomatedDiagnosticEvidenceValue("Hint port", socketPort.toString()))
            }
            context.hybridSocketHintLastSentAtMonotonicMillis?.let { observedAt ->
                add(
                    AutomatedDiagnosticEvidenceValue(
                        "Hint send age",
                        formatAutomatedDiagnosticsDuration(
                            (clock.nowMillis() - observedAt).coerceAtLeast(0L)
                        )
                    )
                )
            }
            context.hybridSocketHintLastObservedPeerId?.let { peerId ->
                add(AutomatedDiagnosticEvidenceValue("Hint observed peer", peerId))
            }
            context.hybridSocketHintLastObservedSessionId?.let { sessionId ->
                add(AutomatedDiagnosticEvidenceValue("Hint observed session", sessionId))
            }
            context.hybridSocketHintLastObservedGroupOwnerAddress?.let { address ->
                add(AutomatedDiagnosticEvidenceValue("Hint observed address", address))
            }
            context.hybridSocketHintLastObservedSocketPort?.let { socketPort ->
                add(
                    AutomatedDiagnosticEvidenceValue(
                        "Hint observed port",
                        socketPort.toString()
                    )
                )
            }
            context.hybridSocketHintLastObservedStoreResult?.let { result ->
                add(AutomatedDiagnosticEvidenceValue("Hint store result", result))
            }
            context.lastObservedHybridSocketHintObservedAtMonotonicMillis?.let { observedAt ->
                add(
                    AutomatedDiagnosticEvidenceValue(
                        "Hint observed age",
                        formatAutomatedDiagnosticsDuration(
                            (clock.nowMillis() - observedAt).coerceAtLeast(0L)
                        )
                    )
                )
            }
            add(
                AutomatedDiagnosticEvidenceValue(
                    "Hint current-attempt match",
                    (context.hybridSocketHintAcceptedObservation != null).toString()
                )
            )
            context.currentPhaseObservedRemoteSignal?.let { signal ->
                add(
                    AutomatedDiagnosticEvidenceValue(
                        "Remote Step16 state",
                        signal.phaseState.name
                    )
                )
            }
            context.hybridSocketHintLastRejectedReason?.let { reason ->
                add(
                    AutomatedDiagnosticEvidenceValue(
                        "Hint last rejection",
                        buildString {
                            append(reason.statusText)
                            context.hybridSocketHintLastRejectedField?.let { field ->
                                append(" @ ")
                                append(field)
                            }
                            context.hybridSocketHintLastRejectedExpectedValue?.let { expected ->
                                append(" expected=")
                                append(expected)
                            }
                            context.hybridSocketHintLastRejectedObservedValue?.let { observed ->
                                append(" observed=")
                                append(observed)
                            }
                        }
                    )
                )
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
        val preparationState = automatedDiagnosticsPreparationState(refreshedSnapshot)
        if (!preparationState.isReady) {
            lastAutoJoinBlocker = buildString {
                append("preparation")
                preparationState.requiredAction?.kind?.name?.let { kind ->
                    append(":")
                    append(kind)
                }
                append(" [")
                append(preparationState.summary)
                append("]")
            }
            if (
                clock.nowMillis() - pendingAnnouncement.observedMonotonicMillis >
                timingPolicy.sharedRunCoordination.timeoutMillis
            ) {
                clearPendingParticipantAnnouncement(
                    "participantAnnouncementSeedOrNull: preparation-timeout"
                )
            }
            return null
        }
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
                    phaseTwoSummary = ""
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
        syncPendingParticipantAnnouncementState()
        return pending
    }

    private fun syncPendingParticipantAnnouncementState() {
        val pendingAnnouncement = pendingParticipantAnnouncement
        updateRunState { current ->
            if (current.overallStatus != AutomatedDiagnosticsOverallStatus.IDLE) {
                return@updateRunState current
            }
            val updated = if (pendingAnnouncement == null) {
                if (
                    current.localPeerRole != AutomatedDiagnosticsPeerRole.PARTICIPANT &&
                    current.sharedRunId == null
                ) {
                    return@updateRunState current
                }
                current.copy(
                    selectedPeerId = null,
                    localPeerRole = null,
                    sharedRunId = null,
                    sharedRunCoordinatorPeerId = null,
                    sharedRunParticipantPeerId = null,
                    sharedRunSessionAssociationId = null,
                    sharedRunCreatedAtMillis = null,
                    sharedRunExpiresAtMillis = null,
                    sharedRunCanonicalPeerPair = null
                )
            } else {
                val sharedRun = pendingAnnouncement.announcement.sharedRun
                current.copy(
                    selectedPeerId = pendingAnnouncement.selectedPeer.identityKey,
                    localPeerRole = AutomatedDiagnosticsPeerRole.PARTICIPANT,
                    sharedRunId = sharedRun.runId,
                    sharedRunCoordinatorPeerId = sharedRun.coordinatorPeerId,
                    sharedRunParticipantPeerId = sharedRun.participantPeerId,
                    sharedRunSessionAssociationId = sharedRun.sessionAssociationId,
                    sharedRunCreatedAtMillis = sharedRun.createdAtMillis,
                    sharedRunExpiresAtMillis = sharedRun.expiresAtMillis,
                    sharedRunCanonicalPeerPair =
                        canonicalPeerPairText(sharedRun.canonicalPeerPair())
                )
            }
            updated.copy(
                reportText = automatedDiagnosticsPlainTextReport(
                    overallStatus = updated.overallStatus,
                    selectedPeerId = updated.selectedPeerId,
                    localPeerRole = updated.localPeerRole,
                    localRunnerExecutionId = updated.localRunnerExecutionId,
                    sharedRunId = updated.sharedRunId,
                    sharedRunCoordinatorPeerId = updated.sharedRunCoordinatorPeerId,
                    sharedRunParticipantPeerId = updated.sharedRunParticipantPeerId,
                    sharedRunSessionAssociationId = updated.sharedRunSessionAssociationId,
                    sharedRunCreatedAtMillis = updated.sharedRunCreatedAtMillis,
                    sharedRunExpiresAtMillis = updated.sharedRunExpiresAtMillis,
                    sharedRunCanonicalPeerPair = updated.sharedRunCanonicalPeerPair,
                    elapsedMillis = updated.elapsedMillis,
                    steps = updated.steps,
                    phaseTwoSummary = updated.phaseTwoSummary
                )
            )
        }
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

    private fun phaseSignalSignature(
        signal: AutomatedDiagnosticsPhaseSignal
    ): String {
        return listOf(
            signal.sharedRun.runId,
            signal.sharedRun.coordinatorPeerId,
            signal.sharedRun.participantPeerId,
            signal.sharedRun.sessionAssociationId,
            signal.peerId,
            signal.expectedRemotePeerId,
            signal.stepId.name,
            signal.phaseState.name,
            signal.attemptNumber.toString(),
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

    private fun hybridAcceptObservationSignature(
        observation: AutomatedDiagnosticsHybridAcceptObservation
    ): String {
        return listOf(
            observation.peerId,
            observation.sessionId,
            observation.publicPeerIdHint ?: "none",
            observation.createdAtMillis.toString(),
            hybridBootstrapAcceptStoreResultStatusText(observation.storeResult)
        ).joinToString(separator = "|")
    }

    private fun hybridSocketHintObservationSignature(
        observation: AutomatedDiagnosticsHybridSocketHintObservation
    ): String {
        return listOf(
            observation.peerId,
            observation.sessionId,
            observation.publicPeerIdHint ?: "none",
            observation.groupOwnerAddress,
            observation.socketPort.toString(),
            observation.createdAtMillis.toString(),
            hybridBootstrapAcceptStoreResultStatusText(observation.storeResult)
        ).joinToString(separator = "|")
    }

    private fun hybridBootstrapAcceptStoreResultStatusText(
        result: HybridTransportControlStore.RecordResult
    ): String {
        return when (result) {
            HybridTransportControlStore.RecordResult.Stored -> "stored"
            HybridTransportControlStore.RecordResult.IgnoredOlderMessage ->
                "ignored-older-message"
            HybridTransportControlStore.RecordResult.IgnoredNonBootstrapMessageType ->
                "ignored-non-bootstrap-message-type"
            HybridTransportControlStore.RecordResult.IgnoredInvalidPeerId ->
                "ignored-invalid-peer-id"
        }
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

    private fun phaseAttemptNumberFromEvidence(
        stepId: AutomatedDiagnosticStepId
    ): Int {
        return stepEvidenceValue(stepId, "Phase attempt number")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    }

    private fun restoreStepContextForStartIndex(
        startIndex: Int
    ): AutomatedDiagnosticsStepContext {
        if (startIndex <= AutomatedDiagnosticStepId.AURORA_PEER_DISCOVERY.ordinal) {
            return AutomatedDiagnosticsStepContext()
        }
        val stepElevenOrdinal = AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP.ordinal
        val shouldRestoreStepElevenAttemptState = startIndex > stepElevenOrdinal
        val snapshot = bindings.snapshot()
        val selectedPeerId = mutableState.value.selectedPeerId
        val selectedPeer = selectedPeerId?.let { peerId ->
            selectedPeerForIdentityKey(snapshot.discoveredAuroraPeers, peerId)
        }
        val selectedWifiDirectPeer = if (
            shouldRestoreStepElevenAttemptState
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
        val restoredPhaseAttemptNumbers = if (startIndex > stepElevenOrdinal) {
            AutomatedDiagnosticStepId.entries
                .filter { it.ordinal >= stepElevenOrdinal }
                .associateWith(::phaseAttemptNumberFromEvidence)
                .filterValues { it > 0 }
                .toMutableMap()
        } else {
            linkedMapOf()
        }
        val restoredDnsSdRegistrationObserved =
            shouldRestoreStepElevenAttemptState && (
                stepEvidenceValue(
                    AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                    "Current-run DNS-SD registration observed"
                ) == "true" ||
                    stepEvidenceValue(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                        "Current-run DNS-SD proof ready"
                    ) == "true" ||
                    stepElevenProvenance ==
                    AutomatedDiagnosticsWifiDirectGroupProvenance.CURRENT_RUN_VALIDATED
                )
        return AutomatedDiagnosticsStepContext(
            selectedPeer = selectedPeer,
            localRole = mutableState.value.localPeerRole,
            localRoleAfterConflict = mutableState.value.localPeerRole,
            selectedWifiDirectPeer = selectedWifiDirectPeer,
            selectedWifiDirectPeerSource = if (shouldRestoreStepElevenAttemptState) {
                stepEvidenceValue(
                    AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                    "Selected peer source"
                )?.takeUnless { it == "none" }
            } else {
                null
            },
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
                shouldRestoreStepElevenAttemptState &&
                stepEvidenceValue(
                    AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                    "Fresh baseline established"
                ) == "true" ||
                    shouldRestoreStepElevenAttemptState &&
                    stepElevenProvenance ==
                    AutomatedDiagnosticsWifiDirectGroupProvenance.CURRENT_RUN_VALIDATED,
            preExistingWifiDirectGroupDetected =
                shouldRestoreStepElevenAttemptState &&
                    stepEvidenceValue(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                        "Pre-existing group detected"
                    ) == "true",
            preExistingWifiDirectSocketDetected =
                shouldRestoreStepElevenAttemptState &&
                    stepEvidenceValue(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                        "Pre-existing socket detected"
                    ) == "true",
            wifiDirectBaselineDisconnectRequested =
                shouldRestoreStepElevenAttemptState &&
                    stepEvidenceValue(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                        "Fresh baseline disconnect requested"
                    ) == "true",
            wifiDirectBaselineDisconnectRequestCount = if (shouldRestoreStepElevenAttemptState) {
                stepEvidenceValue(
                    AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                    "Fresh baseline disconnect request count"
                )?.toIntOrNull() ?: 0
            } else {
                0
            },
            wifiDirectBaselineSocketCleanupRequested =
                shouldRestoreStepElevenAttemptState &&
                    stepEvidenceValue(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                        "Fresh baseline socket close/reset requested"
                    ) == "true",
            wifiDirectCurrentRunTokenProofReady =
                shouldRestoreStepElevenAttemptState && (
                    stepEvidenceValue(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                        "Current-run token proof ready"
                    ) == "true" ||
                    stepElevenProvenance ==
                        AutomatedDiagnosticsWifiDirectGroupProvenance.CURRENT_RUN_VALIDATED
                    ),
            wifiDirectCurrentRunDnsSdRegistrationObserved = restoredDnsSdRegistrationObserved,
            wifiDirectCurrentRunDnsSdProofReady =
                shouldRestoreStepElevenAttemptState && (
                    stepEvidenceValue(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                        "Current-run DNS-SD proof ready"
                    ) == "true" ||
                    stepElevenProvenance ==
                        AutomatedDiagnosticsWifiDirectGroupProvenance.CURRENT_RUN_VALIDATED
                    ),
            wifiDirectCurrentRunValidatedPeerProofReady =
                shouldRestoreStepElevenAttemptState && (
                    stepEvidenceValue(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                        "Current-run validated-peer proof ready"
                    ) == "true" ||
                    stepElevenProvenance ==
                        AutomatedDiagnosticsWifiDirectGroupProvenance.CURRENT_RUN_VALIDATED
                    ),
            wifiDirectCurrentRunConnectProofReady =
                shouldRestoreStepElevenAttemptState && (
                    stepEvidenceValue(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                        "Current-run connect proof ready"
                    ) == "true" ||
                    stepElevenProvenance ==
                        AutomatedDiagnosticsWifiDirectGroupProvenance.CURRENT_RUN_VALIDATED
                    ),
            wifiDirectGroupObservedAfterCurrentRunProof =
                shouldRestoreStepElevenAttemptState && (
                    stepEvidenceValue(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                        "Group observed after current-run proof"
                    ) == "true" ||
                    stepElevenProvenance ==
                        AutomatedDiagnosticsWifiDirectGroupProvenance.CURRENT_RUN_VALIDATED
                    ),
            wifiDirectGroupProvenance = if (shouldRestoreStepElevenAttemptState) {
                stepElevenProvenance
            } else {
                AutomatedDiagnosticsWifiDirectGroupProvenance.NONE
            },
            wifiDirectConnectInvocationCount = if (shouldRestoreStepElevenAttemptState) {
                stepEvidenceValue(
                    AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                    "Wi-Fi Direct connect invocation count"
                )?.toIntOrNull() ?: 0
            } else {
                0
            },
            phaseAttemptNumbersByStep = restoredPhaseAttemptNumbers
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
        var wifiDirectCurrentRunDnsSdRegistrationObserved: Boolean = false,
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
        var serverReadyLastRejectedField: String? = null,
        var serverReadyLastRejectedExpectedValue: String? = null,
        var serverReadyLastRejectedObservedValue: String? = null,
        var clientConnectRequestCount: Int = 0,
        var clientConnectRequestAtMillis: Long? = null,
        var clientConnectRequestHost: String? = null,
        var hybridAcceptAttemptStartedAtMonotonicMillis: Long? = null,
        var hybridAcceptExpectedSessionId: String? = null,
        var hybridAcceptSendAttemptCount: Int = 0,
        var hybridAcceptSuccessfulSendCount: Int = 0,
        var hybridAcceptObservedCount: Int = 0,
        var hybridAcceptAcceptedCount: Int = 0,
        var hybridAcceptLastSendResult: String? = null,
        var hybridAcceptLastSentPeerId: String? = null,
        var hybridAcceptLastSentSessionId: String? = null,
        var hybridAcceptLastSentAtMonotonicMillis: Long? = null,
        var hybridAcceptLastObservedPeerId: String? = null,
        var hybridAcceptLastObservedSessionId: String? = null,
        var hybridAcceptLastObservedStoreResult: String? = null,
        var hybridAcceptLastRejectedReason: AutomatedDiagnosticsCoordinationRejectionReason? =
            null,
        var hybridAcceptLastRejectedField: String? = null,
        var hybridAcceptLastRejectedExpectedValue: String? = null,
        var hybridAcceptLastRejectedObservedValue: String? = null,
        var hybridAcceptAcceptedObservation: AutomatedDiagnosticsHybridAcceptObservation? = null,
        var hybridSocketHintAttemptStartedAtMonotonicMillis: Long? = null,
        var hybridSocketHintExpectedSessionId: String? = null,
        var hybridSocketHintExpectedGroupOwnerAddress: String? = null,
        var hybridSocketHintExpectedSocketPort: Int? = null,
        var hybridSocketHintSendAttemptCount: Int = 0,
        var hybridSocketHintSuccessfulSendCount: Int = 0,
        var hybridSocketHintObservedCount: Int = 0,
        var hybridSocketHintAcceptedCount: Int = 0,
        var hybridSocketHintLastSendResult: String? = null,
        var hybridSocketHintLastSentPeerId: String? = null,
        var hybridSocketHintLastSentSessionId: String? = null,
        var hybridSocketHintLastSentGroupOwnerAddress: String? = null,
        var hybridSocketHintLastSentSocketPort: Int? = null,
        var hybridSocketHintLastSentAtMonotonicMillis: Long? = null,
        var hybridSocketHintLastObservedPeerId: String? = null,
        var hybridSocketHintLastObservedSessionId: String? = null,
        var hybridSocketHintLastObservedGroupOwnerAddress: String? = null,
        var hybridSocketHintLastObservedSocketPort: Int? = null,
        var hybridSocketHintLastObservedStoreResult: String? = null,
        var hybridSocketHintLastRejectedReason: AutomatedDiagnosticsCoordinationRejectionReason? =
            null,
        var hybridSocketHintLastRejectedField: String? = null,
        var hybridSocketHintLastRejectedExpectedValue: String? = null,
        var hybridSocketHintLastRejectedObservedValue: String? = null,
        var hybridSocketHintAcceptedObservation: AutomatedDiagnosticsHybridSocketHintObservation? =
            null,
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
        internal var lastObservedServerReadyObservedAtMonotonicMillis: Long? = null,
        private var lastObservedHybridAcceptSignature: String? = null,
        internal var lastObservedHybridAcceptObservedAtMonotonicMillis: Long? = null,
        private var lastObservedHybridSocketHintSignature: String? = null,
        internal var lastObservedHybridSocketHintObservedAtMonotonicMillis: Long? = null,
        private val phaseAttemptNumbersByStep:
        MutableMap<AutomatedDiagnosticStepId, Int> = linkedMapOf(),
        private var lastObservedPhaseSignalSignature: String? = null,
        internal var lastObservedPhaseSignalObservedAtMonotonicMillis: Long? = null,
        var currentPhaseStepId: AutomatedDiagnosticStepId? = null,
        var currentPhaseAttemptNumber: Int = 0,
        var currentPhaseLocalState: AutomatedDiagnosticsPhaseState? = null,
        var currentPhaseObservedRemoteSignal: AutomatedDiagnosticsPhaseSignal? = null,
        var currentPhaseBarrierEstablished: Boolean = false,
        var currentPhaseBarrierEstablishedAtMillis: Long? = null,
        var currentPhaseAttemptStartedAtMillis: Long? = null,
        var currentPhaseOperationalStartedAtMillis: Long? = null,
        var currentPhaseApplicationProbeDescriptors:
        List<AutomatedDiagnosticsPhaseApplicationProbeDescriptor> = emptyList(),
        var currentPhaseSendCount: Int = 0,
        var currentPhaseReceiveCount: Int = 0,
        var currentPhaseAcceptedCount: Int = 0,
        var currentPhaseLastRejectedReason: AutomatedDiagnosticsCoordinationRejectionReason? = null,
        var currentPhaseLastLocalSendStatus: String? = null
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
            wifiDirectCurrentRunDnsSdRegistrationObserved =
                wifiDirectCurrentRunDnsSdRegistrationObserved ||
                    override.wifiDirectCurrentRunDnsSdRegistrationObserved
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
            if (override.serverReadyLastRejectedField != null) {
                serverReadyLastRejectedField = override.serverReadyLastRejectedField
            }
            if (override.serverReadyLastRejectedExpectedValue != null) {
                serverReadyLastRejectedExpectedValue =
                    override.serverReadyLastRejectedExpectedValue
            }
            if (override.serverReadyLastRejectedObservedValue != null) {
                serverReadyLastRejectedObservedValue =
                    override.serverReadyLastRejectedObservedValue
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
            if (override.hybridAcceptAttemptStartedAtMonotonicMillis != null) {
                hybridAcceptAttemptStartedAtMonotonicMillis =
                    override.hybridAcceptAttemptStartedAtMonotonicMillis
            }
            if (override.hybridAcceptExpectedSessionId != null) {
                hybridAcceptExpectedSessionId = override.hybridAcceptExpectedSessionId
            }
            hybridAcceptSendAttemptCount = maxOf(
                hybridAcceptSendAttemptCount,
                override.hybridAcceptSendAttemptCount
            )
            hybridAcceptSuccessfulSendCount = maxOf(
                hybridAcceptSuccessfulSendCount,
                override.hybridAcceptSuccessfulSendCount
            )
            hybridAcceptObservedCount = maxOf(
                hybridAcceptObservedCount,
                override.hybridAcceptObservedCount
            )
            hybridAcceptAcceptedCount = maxOf(
                hybridAcceptAcceptedCount,
                override.hybridAcceptAcceptedCount
            )
            if (override.hybridAcceptLastSendResult != null) {
                hybridAcceptLastSendResult = override.hybridAcceptLastSendResult
            }
            if (override.hybridAcceptLastSentPeerId != null) {
                hybridAcceptLastSentPeerId = override.hybridAcceptLastSentPeerId
            }
            if (override.hybridAcceptLastSentSessionId != null) {
                hybridAcceptLastSentSessionId = override.hybridAcceptLastSentSessionId
            }
            if (override.hybridAcceptLastSentAtMonotonicMillis != null) {
                hybridAcceptLastSentAtMonotonicMillis =
                    override.hybridAcceptLastSentAtMonotonicMillis
            }
            if (override.hybridAcceptLastObservedPeerId != null) {
                hybridAcceptLastObservedPeerId = override.hybridAcceptLastObservedPeerId
            }
            if (override.hybridAcceptLastObservedSessionId != null) {
                hybridAcceptLastObservedSessionId = override.hybridAcceptLastObservedSessionId
            }
            if (override.hybridAcceptLastObservedStoreResult != null) {
                hybridAcceptLastObservedStoreResult =
                    override.hybridAcceptLastObservedStoreResult
            }
            if (override.hybridAcceptLastRejectedReason != null) {
                hybridAcceptLastRejectedReason = override.hybridAcceptLastRejectedReason
            }
            if (override.hybridAcceptLastRejectedField != null) {
                hybridAcceptLastRejectedField = override.hybridAcceptLastRejectedField
            }
            if (override.hybridAcceptLastRejectedExpectedValue != null) {
                hybridAcceptLastRejectedExpectedValue =
                    override.hybridAcceptLastRejectedExpectedValue
            }
            if (override.hybridAcceptLastRejectedObservedValue != null) {
                hybridAcceptLastRejectedObservedValue =
                    override.hybridAcceptLastRejectedObservedValue
            }
            if (override.hybridAcceptAcceptedObservation != null) {
                hybridAcceptAcceptedObservation = override.hybridAcceptAcceptedObservation
            }
            if (override.hybridSocketHintAttemptStartedAtMonotonicMillis != null) {
                hybridSocketHintAttemptStartedAtMonotonicMillis =
                    override.hybridSocketHintAttemptStartedAtMonotonicMillis
            }
            if (override.hybridSocketHintExpectedSessionId != null) {
                hybridSocketHintExpectedSessionId = override.hybridSocketHintExpectedSessionId
            }
            if (override.hybridSocketHintExpectedGroupOwnerAddress != null) {
                hybridSocketHintExpectedGroupOwnerAddress =
                    override.hybridSocketHintExpectedGroupOwnerAddress
            }
            if (override.hybridSocketHintExpectedSocketPort != null) {
                hybridSocketHintExpectedSocketPort = override.hybridSocketHintExpectedSocketPort
            }
            hybridSocketHintSendAttemptCount = maxOf(
                hybridSocketHintSendAttemptCount,
                override.hybridSocketHintSendAttemptCount
            )
            hybridSocketHintSuccessfulSendCount = maxOf(
                hybridSocketHintSuccessfulSendCount,
                override.hybridSocketHintSuccessfulSendCount
            )
            hybridSocketHintObservedCount = maxOf(
                hybridSocketHintObservedCount,
                override.hybridSocketHintObservedCount
            )
            hybridSocketHintAcceptedCount = maxOf(
                hybridSocketHintAcceptedCount,
                override.hybridSocketHintAcceptedCount
            )
            if (override.hybridSocketHintLastSendResult != null) {
                hybridSocketHintLastSendResult = override.hybridSocketHintLastSendResult
            }
            if (override.hybridSocketHintLastSentPeerId != null) {
                hybridSocketHintLastSentPeerId = override.hybridSocketHintLastSentPeerId
            }
            if (override.hybridSocketHintLastSentSessionId != null) {
                hybridSocketHintLastSentSessionId = override.hybridSocketHintLastSentSessionId
            }
            if (override.hybridSocketHintLastSentGroupOwnerAddress != null) {
                hybridSocketHintLastSentGroupOwnerAddress =
                    override.hybridSocketHintLastSentGroupOwnerAddress
            }
            if (override.hybridSocketHintLastSentSocketPort != null) {
                hybridSocketHintLastSentSocketPort = override.hybridSocketHintLastSentSocketPort
            }
            if (override.hybridSocketHintLastSentAtMonotonicMillis != null) {
                hybridSocketHintLastSentAtMonotonicMillis =
                    override.hybridSocketHintLastSentAtMonotonicMillis
            }
            if (override.hybridSocketHintLastObservedPeerId != null) {
                hybridSocketHintLastObservedPeerId = override.hybridSocketHintLastObservedPeerId
            }
            if (override.hybridSocketHintLastObservedSessionId != null) {
                hybridSocketHintLastObservedSessionId =
                    override.hybridSocketHintLastObservedSessionId
            }
            if (override.hybridSocketHintLastObservedGroupOwnerAddress != null) {
                hybridSocketHintLastObservedGroupOwnerAddress =
                    override.hybridSocketHintLastObservedGroupOwnerAddress
            }
            if (override.hybridSocketHintLastObservedSocketPort != null) {
                hybridSocketHintLastObservedSocketPort =
                    override.hybridSocketHintLastObservedSocketPort
            }
            if (override.hybridSocketHintLastObservedStoreResult != null) {
                hybridSocketHintLastObservedStoreResult =
                    override.hybridSocketHintLastObservedStoreResult
            }
            if (override.hybridSocketHintLastRejectedReason != null) {
                hybridSocketHintLastRejectedReason =
                    override.hybridSocketHintLastRejectedReason
            }
            if (override.hybridSocketHintLastRejectedField != null) {
                hybridSocketHintLastRejectedField = override.hybridSocketHintLastRejectedField
            }
            if (override.hybridSocketHintLastRejectedExpectedValue != null) {
                hybridSocketHintLastRejectedExpectedValue =
                    override.hybridSocketHintLastRejectedExpectedValue
            }
            if (override.hybridSocketHintLastRejectedObservedValue != null) {
                hybridSocketHintLastRejectedObservedValue =
                    override.hybridSocketHintLastRejectedObservedValue
            }
            if (override.hybridSocketHintAcceptedObservation != null) {
                hybridSocketHintAcceptedObservation =
                    override.hybridSocketHintAcceptedObservation
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
            override.phaseAttemptNumbersByStep.forEach { (stepId, attemptNumber) ->
                phaseAttemptNumbersByStep[stepId] =
                    maxOf(phaseAttemptNumbersByStep[stepId] ?: 0, attemptNumber)
            }
            return this
        }

        fun beginPhaseAttempt(
            stepId: AutomatedDiagnosticStepId,
            startedAtMonotonicMillis: Long
        ): Int {
            currentPhaseStepId = stepId
            currentPhaseAttemptNumber = (phaseAttemptNumbersByStep[stepId] ?: 0) + 1
            phaseAttemptNumbersByStep[stepId] = currentPhaseAttemptNumber
            currentPhaseLocalState = null
            currentPhaseObservedRemoteSignal = null
            currentPhaseBarrierEstablished = false
            currentPhaseBarrierEstablishedAtMillis = null
            currentPhaseAttemptStartedAtMillis = startedAtMonotonicMillis
            currentPhaseOperationalStartedAtMillis = null
            currentPhaseApplicationProbeDescriptors = emptyList()
            currentPhaseSendCount = 0
            currentPhaseReceiveCount = 0
            currentPhaseAcceptedCount = 0
            currentPhaseLastRejectedReason = null
            currentPhaseLastLocalSendStatus = null
            lastObservedPhaseSignalSignature = null
            lastObservedPhaseSignalObservedAtMonotonicMillis = null
            return currentPhaseAttemptNumber
        }

        fun beginHybridAcceptAttempt(
            expectedSessionId: String,
            startedAtMonotonicMillis: Long
        ) {
            hybridAcceptAttemptStartedAtMonotonicMillis = startedAtMonotonicMillis
            hybridAcceptExpectedSessionId = expectedSessionId
            hybridAcceptSendAttemptCount = 0
            hybridAcceptSuccessfulSendCount = 0
            hybridAcceptObservedCount = 0
            hybridAcceptAcceptedCount = 0
            hybridAcceptLastSendResult = null
            hybridAcceptLastSentPeerId = null
            hybridAcceptLastSentSessionId = null
            hybridAcceptLastSentAtMonotonicMillis = null
            hybridAcceptLastObservedPeerId = null
            hybridAcceptLastObservedSessionId = null
            hybridAcceptLastObservedStoreResult = null
            hybridAcceptLastRejectedReason = null
            hybridAcceptLastRejectedField = null
            hybridAcceptLastRejectedExpectedValue = null
            hybridAcceptLastRejectedObservedValue = null
            hybridAcceptAcceptedObservation = null
            lastObservedHybridAcceptSignature = null
            lastObservedHybridAcceptObservedAtMonotonicMillis = null
        }

        fun beginHybridSocketHintAttempt(
            expectedSessionId: String,
            expectedGroupOwnerAddress: String,
            expectedSocketPort: Int,
            startedAtMonotonicMillis: Long
        ) {
            hybridSocketHintAttemptStartedAtMonotonicMillis = startedAtMonotonicMillis
            hybridSocketHintExpectedSessionId = expectedSessionId
            hybridSocketHintExpectedGroupOwnerAddress = expectedGroupOwnerAddress
            hybridSocketHintExpectedSocketPort = expectedSocketPort
            hybridSocketHintSendAttemptCount = 0
            hybridSocketHintSuccessfulSendCount = 0
            hybridSocketHintObservedCount = 0
            hybridSocketHintAcceptedCount = 0
            hybridSocketHintLastSendResult = null
            hybridSocketHintLastSentPeerId = null
            hybridSocketHintLastSentSessionId = null
            hybridSocketHintLastSentGroupOwnerAddress = null
            hybridSocketHintLastSentSocketPort = null
            hybridSocketHintLastSentAtMonotonicMillis = null
            hybridSocketHintLastObservedPeerId = null
            hybridSocketHintLastObservedSessionId = null
            hybridSocketHintLastObservedGroupOwnerAddress = null
            hybridSocketHintLastObservedSocketPort = null
            hybridSocketHintLastObservedStoreResult = null
            hybridSocketHintLastRejectedReason = null
            hybridSocketHintLastRejectedField = null
            hybridSocketHintLastRejectedExpectedValue = null
            hybridSocketHintLastRejectedObservedValue = null
            hybridSocketHintAcceptedObservation = null
            lastObservedHybridSocketHintSignature = null
            lastObservedHybridSocketHintObservedAtMonotonicMillis = null
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

        fun markHybridAcceptObserved(
            signature: String,
            observedAtMonotonicMillis: Long
        ): Boolean {
            if (lastObservedHybridAcceptSignature == signature) {
                return false
            }
            lastObservedHybridAcceptSignature = signature
            lastObservedHybridAcceptObservedAtMonotonicMillis =
                observedAtMonotonicMillis
            return true
        }

        fun markHybridSocketHintObserved(
            signature: String,
            observedAtMonotonicMillis: Long
        ): Boolean {
            if (lastObservedHybridSocketHintSignature == signature) {
                return false
            }
            lastObservedHybridSocketHintSignature = signature
            lastObservedHybridSocketHintObservedAtMonotonicMillis =
                observedAtMonotonicMillis
            return true
        }

        fun markPhaseSignalObserved(
            signature: String,
            observedAtMonotonicMillis: Long
        ): Boolean {
            if (lastObservedPhaseSignalSignature == signature) {
                return false
            }
            lastObservedPhaseSignalSignature = signature
            lastObservedPhaseSignalObservedAtMonotonicMillis =
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

    private data class HybridAcceptObservationValidationFailure(
        val reason: AutomatedDiagnosticsCoordinationRejectionReason,
        val fieldName: String,
        val expectedValue: String,
        val observedValue: String
    )

    private data class HybridSocketHintObservationValidationFailure(
        val reason: AutomatedDiagnosticsCoordinationRejectionReason,
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

internal fun automatedDiagnosticsApplicationProbeMinimumObservedAtMillis(
    currentPhaseAttemptStartedAtMillis: Long?,
    currentPhaseBarrierEstablishedAtMillis: Long?,
    fallbackStartedAtMillis: Long
): Long {
    return currentPhaseAttemptStartedAtMillis
        ?: currentPhaseBarrierEstablishedAtMillis
        ?: fallbackStartedAtMillis
}

internal fun automatedDiagnosticsApplicationProbeMatchesExpected(
    observation: AutomatedDiagnosticsApplicationProbeObservation,
    expectedMarker: AutomatedDiagnosticsApplicationProbeMarker,
    expectedSenderPeerId: String,
    expectedReceiverPeerId: String,
    expectedThreadId: String,
    expectedPrivateChatId: String?,
    expectedMessageId: String? = null,
    expectedTransportGroupId: Int? = null,
    minimumObservedAtMillis: Long
): Boolean {
    return observation.marker == expectedMarker &&
        observation.senderPeerId == expectedSenderPeerId &&
        observation.receiverPeerId == expectedReceiverPeerId &&
        observation.threadId == expectedThreadId &&
        (expectedMessageId == null || observation.messageId == expectedMessageId) &&
        (expectedTransportGroupId == null || observation.transportGroupId == expectedTransportGroupId) &&
        observation.observedAtMonotonicMillis >= minimumObservedAtMillis &&
        observation.privateChatId == expectedPrivateChatId
}

internal fun automatedDiagnosticsApplicationProbeReceiveDiagnosticMatchesExpected(
    diagnostic: AutomatedDiagnosticsApplicationProbeReceiveDiagnostic,
    expectedMarker: AutomatedDiagnosticsApplicationProbeMarker,
    expectedReceiverPeerId: String,
    expectedThreadId: String,
    expectedPrivateChatId: String?,
    expectedMessageId: String? = null,
    expectedTransportGroupId: Int? = null,
    minimumObservedAtMillis: Long
): Boolean {
    return diagnostic.marker == expectedMarker &&
        diagnostic.receiverPeerId == expectedReceiverPeerId &&
        diagnostic.threadId == expectedThreadId &&
        (expectedMessageId == null || diagnostic.messageId == expectedMessageId) &&
        (expectedTransportGroupId == null || diagnostic.transportGroupId == expectedTransportGroupId) &&
        diagnostic.observedAtMonotonicMillis >= minimumObservedAtMillis &&
        diagnostic.privateChatId == expectedPrivateChatId
}

internal fun automatedDiagnosticsIsLocalProbeSender(
    localPeerId: String?,
    expectedSenderPeerId: String
): Boolean {
    return localPeerId?.trim()?.takeIf { it.isNotEmpty() } == expectedSenderPeerId
}

internal fun automatedDiagnosticsAcceptedRemotePhaseSignalForProbeOrNull(
    snapshot: AutomatedDiagnosticsRuntimeSnapshot,
    expectedMarker: AutomatedDiagnosticsApplicationProbeMarker,
    expectedSenderPeerId: String,
    expectedReceiverPeerId: String
): AutomatedDiagnosticsPhaseSignal? {
    val signal = snapshot.latestAutomatedDiagnosticsPhaseSignalsByStep[expectedMarker.stepId]
        ?: return null
    return signal.takeIf { acceptedSignal ->
        acceptedSignal.sharedRun.runId == expectedMarker.sharedRunId &&
            acceptedSignal.stepId == expectedMarker.stepId &&
            acceptedSignal.attemptNumber == expectedMarker.attemptNumber &&
            acceptedSignal.peerId == expectedSenderPeerId &&
            acceptedSignal.expectedRemotePeerId == expectedReceiverPeerId
    }
}

internal fun automatedDiagnosticsRemotePhaseApplicationProbeDescriptorOrNull(
    snapshot: AutomatedDiagnosticsRuntimeSnapshot,
    expectedMarker: AutomatedDiagnosticsApplicationProbeMarker,
    expectedSenderPeerId: String,
    expectedReceiverPeerId: String,
    expectedMessageId: String? = null
): AutomatedDiagnosticsPhaseApplicationProbeDescriptor? {
    val signal = automatedDiagnosticsAcceptedRemotePhaseSignalForProbeOrNull(
        snapshot = snapshot,
        expectedMarker = expectedMarker,
        expectedSenderPeerId = expectedSenderPeerId,
        expectedReceiverPeerId = expectedReceiverPeerId
    ) ?: return null
    val matchingDescriptors = signal.applicationProbeDescriptors.filter { descriptor ->
        descriptor.probeKind == expectedMarker.probeKind &&
            (expectedMessageId == null || descriptor.messageId == expectedMessageId)
    }
    return when {
        matchingDescriptors.isEmpty() -> null
        expectedMessageId != null -> matchingDescriptors.firstOrNull()
        matchingDescriptors.size == 1 -> matchingDescriptors.single()
        else -> null
    }
}

internal fun automatedDiagnosticsLocalPhaseApplicationProbeDescriptor(
    snapshot: AutomatedDiagnosticsRuntimeSnapshot,
    probeKind: AutomatedDiagnosticsApplicationProbeKind,
    messageId: String,
    expectedReceiverPeerId: String,
    transportStatus: String?,
    localBleTransportResult: String?,
    expectedReceiverTransportGroupId: Int?
): AutomatedDiagnosticsPhaseApplicationProbeDescriptor {
    val latestLocalSendTrace = snapshot.recentBleTransportLocalSendTraces.lastOrNull { trace ->
        trace.messageId == messageId &&
            trace.targetPeerId == expectedReceiverPeerId
    }
    return AutomatedDiagnosticsPhaseApplicationProbeDescriptor(
        probeKind = probeKind,
        messageId = messageId,
        transportStatus = transportStatus,
        localBleTransportResult = localBleTransportResult,
        expectedTransportGroupId =
            latestLocalSendTrace?.groupId ?: expectedReceiverTransportGroupId,
        expectedChunkCount = latestLocalSendTrace?.chunkCount,
        frameByteCount = latestLocalSendTrace?.encodedPayloadByteCount,
        senderChunksQueued = latestLocalSendTrace?.chunksQueued,
        senderChunksWriteAttempted = latestLocalSendTrace?.chunksWriteAttempted,
        senderLastLocalWriteResult = latestLocalSendTrace?.lastLocalWriteResult
    )
}

internal fun automatedDiagnosticsAuthoritativePhaseApplicationProbeDescriptorOrNull(
    snapshot: AutomatedDiagnosticsRuntimeSnapshot,
    expectedMarker: AutomatedDiagnosticsApplicationProbeMarker,
    expectedSenderPeerId: String,
    expectedReceiverPeerId: String,
    localPeerId: String?,
    localSubmissionMessageId: String? = null,
    localTransportStatus: String? = null,
    localBleTransportResult: String? = null,
    localExpectedReceiverTransportGroupId: Int? = null
): AutomatedDiagnosticsPhaseApplicationProbeDescriptor? {
    val remoteDescriptor = automatedDiagnosticsRemotePhaseApplicationProbeDescriptorOrNull(
        snapshot = snapshot,
        expectedMarker = expectedMarker,
        expectedSenderPeerId = expectedSenderPeerId,
        expectedReceiverPeerId = expectedReceiverPeerId,
        expectedMessageId = localSubmissionMessageId
    )
    if (!automatedDiagnosticsIsLocalProbeSender(localPeerId, expectedSenderPeerId)) {
        return remoteDescriptor
    }
    val localMessageId = localSubmissionMessageId ?: remoteDescriptor?.messageId ?: return null
    val localDescriptor = automatedDiagnosticsLocalPhaseApplicationProbeDescriptor(
        snapshot = snapshot,
        probeKind = expectedMarker.probeKind,
        messageId = localMessageId,
        expectedReceiverPeerId = expectedReceiverPeerId,
        transportStatus = localTransportStatus ?: remoteDescriptor?.transportStatus,
        localBleTransportResult =
            localBleTransportResult ?: remoteDescriptor?.localBleTransportResult,
        expectedReceiverTransportGroupId =
            localExpectedReceiverTransportGroupId ?: remoteDescriptor?.expectedTransportGroupId
    )
    return if (
        localDescriptor.expectedChunkCount != null ||
            localDescriptor.frameByteCount != null ||
            localDescriptor.senderChunksQueued != null ||
            localDescriptor.senderChunksWriteAttempted != null ||
            localDescriptor.senderLastLocalWriteResult != null ||
            localDescriptor.expectedTransportGroupId != null
    ) {
        localDescriptor
    } else {
        remoteDescriptor ?: localDescriptor
    }
}

internal data class AutomatedDiagnosticsPhaseSignalBarrierValidationFailure(
    val reason: AutomatedDiagnosticsCoordinationRejectionReason,
    val fieldName: String,
    val expectedValue: String,
    val observedValue: String
)

internal data class AutomatedDiagnosticsServerReadyValidationFailure(
    val reason: AutomatedDiagnosticsCoordinationRejectionReason,
    val fieldName: String,
    val expectedValue: String,
    val observedValue: String
)

internal fun validateAutomatedDiagnosticsServerReadySignalForSocketStep(
    signal: AutomatedDiagnosticsServerReadySignal,
    expectedRun: AutomatedDiagnosticsSharedRun,
    expectedOwnerPeerId: String,
    expectedClientPeerId: String,
    activeTransportPeerId: String?,
    localPeerId: String?,
    localWifiDirectRole: WifiDirectConnectionRole,
    observedAgeMillis: Long,
    effectiveLeaseDurationMillis: Long,
    minimumCreatedAtMillis: Long,
    groupReady: Boolean
): AutomatedDiagnosticsServerReadyValidationFailure? {
    return when {
        observedAgeMillis > effectiveLeaseDurationMillis ->
            AutomatedDiagnosticsServerReadyValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.STALE,
                fieldName = "signal.localMonotonicLeaseMillis",
                expectedValue = "<=$effectiveLeaseDurationMillis",
                observedValue = observedAgeMillis.toString()
            )
        signal.createdAtMillis < minimumCreatedAtMillis ->
            AutomatedDiagnosticsServerReadyValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.STALE,
                fieldName = "signal.createdAtMillis",
                expectedValue = ">=$minimumCreatedAtMillis",
                observedValue = signal.createdAtMillis.toString()
            )
        signal.sharedRun.runId != expectedRun.runId ->
            AutomatedDiagnosticsServerReadyValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_RUN,
                fieldName = "signal.sharedRun.runId",
                expectedValue = expectedRun.runId,
                observedValue = signal.sharedRun.runId
            )
        signal.sharedRun.coordinatorPeerId != expectedRun.coordinatorPeerId ->
            AutomatedDiagnosticsServerReadyValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER,
                fieldName = "signal.sharedRun.coordinatorPeerId",
                expectedValue = expectedRun.coordinatorPeerId,
                observedValue = signal.sharedRun.coordinatorPeerId
            )
        signal.sharedRun.participantPeerId != expectedRun.participantPeerId ->
            AutomatedDiagnosticsServerReadyValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER,
                fieldName = "signal.sharedRun.participantPeerId",
                expectedValue = expectedRun.participantPeerId,
                observedValue = signal.sharedRun.participantPeerId
            )
        signal.sharedRun.sessionAssociationId != expectedRun.sessionAssociationId ->
            AutomatedDiagnosticsServerReadyValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_SESSION,
                fieldName = "signal.sharedRun.sessionAssociationId",
                expectedValue = expectedRun.sessionAssociationId,
                observedValue = signal.sharedRun.sessionAssociationId
            )
        signal.peerId != expectedOwnerPeerId ->
            AutomatedDiagnosticsServerReadyValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER,
                fieldName = "signal.peerId",
                expectedValue = expectedOwnerPeerId,
                observedValue = signal.peerId
            )
        localWifiDirectRole == WifiDirectConnectionRole.CLIENT &&
            activeTransportPeerId != null &&
            activeTransportPeerId != expectedOwnerPeerId ->
            AutomatedDiagnosticsServerReadyValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER,
                fieldName = "snapshot.activeTransportPeerId",
                expectedValue = expectedOwnerPeerId,
                observedValue = activeTransportPeerId
            )
        signal.expectedClientPeerId != expectedClientPeerId ->
            AutomatedDiagnosticsServerReadyValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER,
                fieldName = "signal.expectedClientPeerId",
                expectedValue = expectedClientPeerId,
                observedValue = signal.expectedClientPeerId
            )
        localWifiDirectRole == WifiDirectConnectionRole.CLIENT &&
            localPeerId != null &&
            localPeerId != expectedClientPeerId ->
            AutomatedDiagnosticsServerReadyValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER,
                fieldName = "snapshot.localPeerId",
                expectedValue = expectedClientPeerId,
                observedValue = localPeerId
            )
        signal.groupOwnerAddress.isBlank() ->
            AutomatedDiagnosticsServerReadyValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.INVALID_PAYLOAD,
                fieldName = "signal.groupOwnerAddress",
                expectedValue = "non-blank-host",
                observedValue = signal.groupOwnerAddress
            )
        signal.socketPort !in 1..65535 ->
            AutomatedDiagnosticsServerReadyValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.INVALID_PAYLOAD,
                fieldName = "signal.socketPort",
                expectedValue = "1..65535",
                observedValue = signal.socketPort.toString()
            )
        !groupReady ->
            AutomatedDiagnosticsServerReadyValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.BEFORE_GROUP_READY,
                fieldName = "wifiDirectGroupReady(snapshot)",
                expectedValue = "true",
                observedValue = "false"
            )
        else -> null
    }
}

internal fun validateAutomatedDiagnosticsPhaseSignalForBarrier(
    signal: AutomatedDiagnosticsPhaseSignal,
    expectedRun: AutomatedDiagnosticsSharedRun,
    expectedSenderPeerId: String,
    expectedRecipientPeerId: String,
    expectedStepId: AutomatedDiagnosticStepId,
    expectedAttemptNumber: Int,
    activeTransportPeerId: String?,
    localPeerId: String?,
    observedAgeMillis: Long,
    effectiveLeaseDurationMillis: Long
): AutomatedDiagnosticsPhaseSignalBarrierValidationFailure? {
    val expectedCanonicalPeerPair = expectedRun.canonicalPeerPair()
    val actualCanonicalPeerPair = signal.sharedRun.canonicalPeerPair()
    return when {
        signal.sharedRun.runId != expectedRun.runId ->
            AutomatedDiagnosticsPhaseSignalBarrierValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_RUN,
                fieldName = "signal.sharedRun.runId",
                expectedValue = expectedRun.runId,
                observedValue = signal.sharedRun.runId
            )
        signal.sharedRun.coordinatorPeerId != expectedRun.coordinatorPeerId ->
            AutomatedDiagnosticsPhaseSignalBarrierValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER,
                fieldName = "signal.sharedRun.coordinatorPeerId",
                expectedValue = expectedRun.coordinatorPeerId,
                observedValue = signal.sharedRun.coordinatorPeerId
            )
        signal.sharedRun.participantPeerId != expectedRun.participantPeerId ->
            AutomatedDiagnosticsPhaseSignalBarrierValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER,
                fieldName = "signal.sharedRun.participantPeerId",
                expectedValue = expectedRun.participantPeerId,
                observedValue = signal.sharedRun.participantPeerId
            )
        actualCanonicalPeerPair != expectedCanonicalPeerPair ->
            AutomatedDiagnosticsPhaseSignalBarrierValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER,
                fieldName = "signal.sharedRun.canonicalPeerPair()",
                expectedValue = automatedDiagnosticsCanonicalPeerPairText(expectedCanonicalPeerPair),
                observedValue = automatedDiagnosticsCanonicalPeerPairText(actualCanonicalPeerPair)
            )
        signal.sharedRun.sessionAssociationId != expectedRun.sessionAssociationId ->
            AutomatedDiagnosticsPhaseSignalBarrierValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_SESSION,
                fieldName = "signal.sharedRun.sessionAssociationId",
                expectedValue = expectedRun.sessionAssociationId,
                observedValue = signal.sharedRun.sessionAssociationId
            )
        signal.peerId != expectedSenderPeerId ->
            AutomatedDiagnosticsPhaseSignalBarrierValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER,
                fieldName = "signal.peerId",
                expectedValue = expectedSenderPeerId,
                observedValue = signal.peerId
            )
        signal.expectedRemotePeerId != expectedRecipientPeerId ->
            AutomatedDiagnosticsPhaseSignalBarrierValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER,
                fieldName = "signal.expectedRemotePeerId",
                expectedValue = expectedRecipientPeerId,
                observedValue = signal.expectedRemotePeerId
            )
        signal.stepId.ordinal < expectedStepId.ordinal ->
            AutomatedDiagnosticsPhaseSignalBarrierValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.STALE,
                fieldName = "signal.stepId",
                expectedValue = expectedStepId.name,
                observedValue = signal.stepId.name
            )
        signal.stepId != expectedStepId ->
            AutomatedDiagnosticsPhaseSignalBarrierValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.UNEXPECTED_PHASE,
                fieldName = "signal.stepId",
                expectedValue = expectedStepId.name,
                observedValue = signal.stepId.name
            )
        signal.attemptNumber < expectedAttemptNumber ->
            AutomatedDiagnosticsPhaseSignalBarrierValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.STALE,
                fieldName = "signal.attemptNumber",
                expectedValue = expectedAttemptNumber.toString(),
                observedValue = signal.attemptNumber.toString()
            )
        signal.attemptNumber > expectedAttemptNumber ->
            AutomatedDiagnosticsPhaseSignalBarrierValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.UNEXPECTED_PHASE,
                fieldName = "signal.attemptNumber",
                expectedValue = expectedAttemptNumber.toString(),
                observedValue = signal.attemptNumber.toString()
            )
        activeTransportPeerId != expectedSenderPeerId ->
            AutomatedDiagnosticsPhaseSignalBarrierValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER,
                fieldName = "snapshot.activeTransportPeerId",
                expectedValue = expectedSenderPeerId,
                observedValue = activeTransportPeerId ?: "none"
            )
        localPeerId != null && localPeerId != expectedRecipientPeerId ->
            AutomatedDiagnosticsPhaseSignalBarrierValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER,
                fieldName = "snapshot.localPeerId",
                expectedValue = expectedRecipientPeerId,
                observedValue = localPeerId
            )
        observedAgeMillis > effectiveLeaseDurationMillis ->
            AutomatedDiagnosticsPhaseSignalBarrierValidationFailure(
                reason = AutomatedDiagnosticsCoordinationRejectionReason.STALE,
                fieldName = "signal.localMonotonicLeaseMillis",
                expectedValue = "<=$effectiveLeaseDurationMillis",
                observedValue = observedAgeMillis.toString()
            )
        else -> null
    }
}

private fun automatedDiagnosticsCanonicalPeerPairText(
    canonicalPeerPair: AutomatedDiagnosticsCanonicalPeerPair
): String {
    return "${canonicalPeerPair.lowerPeerId} | ${canonicalPeerPair.higherPeerId}"
}
