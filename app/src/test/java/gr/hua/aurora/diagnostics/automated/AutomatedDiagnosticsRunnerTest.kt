package gr.hua.aurora.diagnostics.automated

import gr.hua.aurora.ble.advertise.BleAdvertiseStatus
import gr.hua.aurora.ble.connection.BleConnectionStatus
import gr.hua.aurora.ble.discovery.BleDiscoveredDevice
import gr.hua.aurora.ble.discovery.BleScanStatus
import gr.hua.aurora.ble.discovery.BleStablePeerId
import gr.hua.aurora.ble.permissions.BluetoothPermissionStatus
import gr.hua.aurora.ble.transport.BleGattTransportFrame
import gr.hua.aurora.ble.transport.BleGattTransportFrameReassembler
import gr.hua.aurora.ble.transport.BleTransportFrameReceiver
import gr.hua.aurora.ble.transport.BleTransportReceiveResult
import gr.hua.aurora.ble.transport.BleTransportSendResult
import gr.hua.aurora.ble.transport.BleTransportSender
import gr.hua.aurora.ble.transport.OutgoingBleTransportSendPlan
import gr.hua.aurora.data.LocalProfileSettings
import gr.hua.aurora.data.LocalProfileSettingsStore
import gr.hua.aurora.model.AuroraContact
import gr.hua.aurora.model.PrivateChatIdentity
import gr.hua.aurora.protocol.MessageFrame
import gr.hua.aurora.protocol.MessageFrameCodec
import gr.hua.aurora.protocol.PeerIdentityExchangeSendResult
import gr.hua.aurora.protocol.PeerSessionRegistryDiagnostics
import gr.hua.aurora.protocol.hasSessionForPeer
import gr.hua.aurora.state.AuroraAvailabilityPreference
import gr.hua.aurora.state.AuroraStateHolder
import gr.hua.aurora.state.SampleAuroraState
import gr.hua.aurora.state.automatedDiagnosticsParticipantJoinAfterReceiveOrNull
import gr.hua.aurora.state.automatedDiagnosticsRunAnnouncementAfterReceiveOrNull
import gr.hua.aurora.state.automatedDiagnosticsServerReadySignalAfterReceiveOrNull
import gr.hua.aurora.state.automatedDiagnosticsWifiDirectPeerReadySignalAfterReceiveOrNull
import gr.hua.aurora.state.createAuroraBleTransportFrameReceiver
import gr.hua.aurora.state.submitHybridBootstrapManualSocketHint
import gr.hua.aurora.state.submitAutomatedDiagnosticsParticipantJoin
import gr.hua.aurora.state.submitAutomatedDiagnosticsRunAnnouncement
import gr.hua.aurora.state.submitAutomatedDiagnosticsServerReadySignal
import gr.hua.aurora.state.submitAutomatedDiagnosticsWifiDirectPeerReadySignal
import gr.hua.aurora.transport.hybrid.HybridTransportControlMessage
import gr.hua.aurora.transport.processing.IncomingTransportFrameProcessingResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapAttemptCommand
import gr.hua.aurora.transport.hybrid.HybridBootstrapAttemptCommandBuildResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapCandidate
import gr.hua.aurora.transport.hybrid.HybridBootstrapCandidateSelection
import gr.hua.aurora.transport.hybrid.HybridBootstrapCommandExecutionResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapCommandTriggerResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapDecision
import gr.hua.aurora.transport.hybrid.HybridBootstrapDiagnostics
import gr.hua.aurora.transport.hybrid.HybridBootstrapDiagnosticsFormatter
import gr.hua.aurora.transport.hybrid.HybridBootstrapManualAcceptSendResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapManualOfferSendResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapManualSocketHintSendResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapManualTriggerSnapshot
import gr.hua.aurora.transport.hybrid.HybridTransportControlFrameFactory
import gr.hua.aurora.transport.hybrid.InMemoryHybridTransportControlStore
import gr.hua.aurora.wifidirect.controller.automatedDiagnosticsWifiDirectDnsSdInstanceName
import gr.hua.aurora.wifidirect.controller.automatedDiagnosticsWifiDirectDnsSdProtocolTxtKey
import gr.hua.aurora.wifidirect.controller.automatedDiagnosticsWifiDirectDnsSdProtocolVersion
import gr.hua.aurora.wifidirect.controller.automatedDiagnosticsWifiDirectDnsSdServiceType
import gr.hua.aurora.wifidirect.controller.automatedDiagnosticsWifiDirectDnsSdTokenTxtKey
import gr.hua.aurora.wifidirect.controller.WifiDirectPermissionStatus
import gr.hua.aurora.wifidirect.debug.WifiDirectGlobalDebugSendDiagnostics
import gr.hua.aurora.wifidirect.debug.WifiDirectPrivateDebugSendDiagnostics
import gr.hua.aurora.wifidirect.debug.WifiDirectReceiveBridgeDiagnostics
import gr.hua.aurora.wifidirect.debug.WifiDirectSendBridgeDiagnostics
import gr.hua.aurora.wifidirect.frame.WifiDirectTransportAdapterDiagnostics
import gr.hua.aurora.wifidirect.frame.WifiDirectTransportAdapterState
import gr.hua.aurora.wifidirect.model.WifiDirectPeer
import gr.hua.aurora.wifidirect.runtime.WifiDirectConnectionRole
import gr.hua.aurora.wifidirect.runtime.WifiDirectConnectionState
import gr.hua.aurora.wifidirect.runtime.WifiDirectConnectionStatus
import gr.hua.aurora.wifidirect.runtime.WifiDirectDnsSdDiagnostics
import gr.hua.aurora.wifidirect.runtime.WifiDirectDnsSdServiceResponse
import gr.hua.aurora.wifidirect.runtime.WifiDirectDiscoveryState
import gr.hua.aurora.wifidirect.runtime.WifiDirectGroupFormedState
import gr.hua.aurora.wifidirect.runtime.WifiDirectLocalDeviceInfo
import gr.hua.aurora.wifidirect.runtime.WifiDirectRolePreference
import gr.hua.aurora.wifidirect.runtime.WifiDirectRuntimeStatus
import gr.hua.aurora.wifidirect.runtime.normalizeWifiDirectDeviceAddress
import gr.hua.aurora.wifidirect.socket.WifiDirectSocketCommand
import gr.hua.aurora.wifidirect.socket.WifiDirectSocketCommandResult
import gr.hua.aurora.wifidirect.socket.WifiDirectSocketDiagnostics
import gr.hua.aurora.wifidirect.socket.WifiDirectSocketEndpoint
import gr.hua.aurora.wifidirect.socket.WifiDirectSocketRole
import gr.hua.aurora.wifidirect.socket.WifiDirectSocketState
import gr.hua.aurora.wifidirect.socket.wifiDirectDebugSocketPort
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.nio.charset.StandardCharsets.UTF_8
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomatedDiagnosticsRunnerTest {
    @Test
    fun runCompletesPhaseTwoParticipantPathWhenRuntimeAndHybridBecomeReady() = runBlocking {
        val participantPhonePeer = wifiDirectPeer(
            name = "Participant Pixel",
            address = "aa:bb:cc:dd:ee:20"
        )
        val coordinatorPhonePeer = wifiDirectPeer(
            name = "Coordinator Pixel",
            address = "aa:bb:cc:dd:ee:10"
        )
        val harness = createStepElevenHarness(
            coordinatorLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
                deviceName = "Coordinator Pixel"
            ),
            participantLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
                deviceName = "Participant Pixel"
            ),
            coordinatorWifiDirectPeers = listOf(participantPhonePeer),
            participantWifiDirectPeers = listOf(coordinatorPhonePeer),
            coordinatorObservedParticipantPeer = participantPhonePeer,
            participantObservedCoordinatorPeer = coordinatorPhonePeer
        )

        try {
            harness.startBothManualRuns()
            harness.advanceSteps(1)

            assertTrue(
                harness.advanceUntil(maxSteps = 720) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION
                        ).status == AutomatedDiagnosticStepStatus.PASS
                }
            )
            val bothPassedOverall = harness.advanceUntil(maxSteps = 1600) {
                harness.coordinatorRunner.state.value.overallStatus ==
                    AutomatedDiagnosticsOverallStatus.PASS &&
                    harness.participantRunner.state.value.overallStatus ==
                    AutomatedDiagnosticsOverallStatus.PASS
            }
            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                bothPassedOverall
            )

            val state = harness.participantRunner.state.value
            val environment = harness.participantEnvironment
            assertEquals(AutomatedDiagnosticsOverallStatus.PASS, state.overallStatus)
            assertEquals(AutomatedDiagnosticStepId.entries.size, state.passedCount)
            assertTrue(state.steps.all { it.status == AutomatedDiagnosticStepStatus.PASS })
            assertEquals(AutomatedDiagnosticsPeerRole.PARTICIPANT, state.localPeerRole)
            val coordinationStep = state.steps[
                AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION.ordinal
            ]
            assertEquals("true", coordinationStep.evidenceValue("Participant join sent"))
            assertTrue(
                coordinationStep.evidenceValue("Run announcement received timestamp") != "none"
            )
            assertEquals(1, environment.hybridBootstrapManualTriggerRequestCount)
            assertEquals(0, environment.hybridBootstrapManualOfferRequestCount)
            assertEquals(0, environment.hybridBootstrapManualSocketHintRequestCount)
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun runCompletesCoordinatorPhaseTwoAndInvokesWifiDirectAndHybridActions() = runBlocking {
        val clock = FakeMonotonicClock()
        val environment = FakePhaseOneEnvironment().apply {
            localPeerId = "0000-local-peer"
            configureCoordinatorPhaseTwoState()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runner = AutomatedDiagnosticsRunner(
            bindings = environment.createBindings(clock = clock, scope = scope),
            clock = clock,
            delay = AdvancingDelay(clock)
        )

        runner.runFromStepIndexForTest()

        val state = runner.state.value
        assertEquals(AutomatedDiagnosticsOverallStatus.PASS, state.overallStatus)
        assertEquals(AutomatedDiagnosticsPeerRole.COORDINATOR, state.localPeerRole)
        assertEquals(1, environment.startWifiDirectDiscoveryCallCount)
        assertEquals(1, environment.connectToWifiDirectPeerCallCount)
        assertEquals(1, environment.startWifiDirectSocketServerCallCount)
        assertEquals(1, environment.setWifiDirectSendBridgeEnabledCallCount)
        assertEquals(1, environment.setWifiDirectReceiveBridgeEnabledCallCount)
        assertEquals(1, environment.hybridBootstrapManualOfferRequestCount)
        assertEquals(1, environment.hybridBootstrapManualSocketHintRequestCount)
        assertEquals(0, environment.hybridBootstrapManualTriggerRequestCount)
        scope.cancel()
    }

    @Test
    fun peerDiscoveryTimeoutBlocksRemainingStepsAndRetryResumesFromBlockedStep() = runBlocking {
        val clock = FakeMonotonicClock()
        val environment = FakePhaseOneEnvironment(
            discoveredPeersProvider = { emptyList() }
        ).apply {
            localPeerId = "0000-local-peer"
            configureCoordinatorPhaseTwoState()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runner = AutomatedDiagnosticsRunner(
            bindings = environment.createBindings(clock = clock, scope = scope),
            clock = clock,
            delay = AdvancingDelay(clock)
        )

        runner.runFromStepIndexForTest()

        assertEquals(
            AutomatedDiagnosticsOverallStatus.BLOCKED,
            runner.state.value.overallStatus
        )
        assertEquals(
            AutomatedDiagnosticStepStatus.BLOCKED,
            runner.state.value.steps[2].status
        )
        assertEquals(
            AutomatedDiagnosticStepStatus.BLOCKED,
            runner.state.value.steps[3].status
        )

        environment.discoveredPeersProvider = { listOf(environment.samplePeer) }
        runner.retryFailedStep()

        val retriedState = runner.state.value
        assertEquals(AutomatedDiagnosticsOverallStatus.PASS, retriedState.overallStatus)
        assertEquals(AutomatedDiagnosticStepStatus.PASS, retriedState.steps[0].status)
        assertEquals(AutomatedDiagnosticStepStatus.PASS, retriedState.steps[1].status)
        scope.cancel()
    }

    @Test
    fun resetReportReturnsRunnerToInitialIdleState() = runBlocking {
        val clock = FakeMonotonicClock()
        val environment = FakePhaseOneEnvironment()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runner = AutomatedDiagnosticsRunner(
            bindings = environment.createBindings(clock = clock, scope = scope),
            clock = clock,
            delay = AdvancingDelay(clock)
        )

        runner.runFromStepIndexForTest()
        runner.resetReport()

        val state = runner.state.value
        assertEquals(AutomatedDiagnosticsOverallStatus.IDLE, state.overallStatus)
        assertTrue(state.steps.all { it.status == AutomatedDiagnosticStepStatus.WAITING })
        scope.cancel()
    }

    @Test
    fun stopCancelsAnActiveRunWithoutWaitingForRealTimeouts() {
        val delayEntered = CountDownLatch(1)
        val clock = FakeMonotonicClock()
        val environment = FakePhaseOneEnvironment(
            scanStatusOverride = { BleScanStatus.STOPPED }
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runner = AutomatedDiagnosticsRunner(
            bindings = environment.createBindings(clock = clock, scope = scope),
            clock = clock,
            delay = HangingDelay(delayEntered)
        )

        runner.start()
        assertTrue(delayEntered.await(1, TimeUnit.SECONDS))

        runner.stop()

        assertEquals(
            AutomatedDiagnosticsOverallStatus.CANCELLED,
            runner.state.value.overallStatus
        )
        scope.cancel()
    }

    @Test
    fun conditionMustRestabilizeAfterTransientBleRuntimeLoss() = runBlocking {
        val clock = FakeMonotonicClock()
        val environment = FakePhaseOneEnvironment(
            discoveredPeersProvider = { emptyList() },
            scanStatusOverride = { nowMillis: Long ->
                when {
                    nowMillis < 200L -> BleScanStatus.SCANNING
                    nowMillis < 400L -> BleScanStatus.STOPPED
                    else -> BleScanStatus.SCANNING
                }
            }
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runner = AutomatedDiagnosticsRunner(
            bindings = environment.createBindings(clock = clock, scope = scope),
            clock = clock,
            delay = AdvancingDelay(clock),
            timingPolicy = AutomatedDiagnosticsTimingPolicy.default().copy(
                recompositionSettle = AutomatedDiagnosticsTimingWindow(
                    stabilizationMillis = 0L,
                    timeoutMillis = 10L
                ),
                auroraPeerDiscovery = AutomatedDiagnosticsTimingWindow(
                    stabilizationMillis = 0L,
                    timeoutMillis = 10L
                )
            )
        )

        runner.runFromStepIndexForTest(startIndex = 1, resetReport = false)

        val bleRuntimeStep = runner.state.value.steps[1]
        assertEquals(AutomatedDiagnosticStepStatus.PASS, bleRuntimeStep.status)
        assertTrue(bleRuntimeStep.elapsedMillis >= 900L)
        scope.cancel()
    }

    @Test
    fun wifiDirectPermissionBlockExposesInlineRequiredActionImmediately() = runBlocking {
        val clock = FakeMonotonicClock()
        val environment = FakePhaseOneEnvironment().apply {
            configureCoordinatorPhaseTwoState()
            wifiDirectRuntimeStatus = wifiDirectRuntimeStatus.copy(
                permissionStatus = WifiDirectPermissionStatus(
                    requiredPermissions = setOf("android.permission.NEARBY_WIFI_DEVICES"),
                    missingPermissions = setOf("android.permission.NEARBY_WIFI_DEVICES"),
                    isWifiDirectSupported = true,
                    isWifiEnabled = true,
                    isWifiP2pEnabled = true
                )
            )
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runner = AutomatedDiagnosticsRunner(
            bindings = environment.createBindings(clock = clock, scope = scope),
            clock = clock,
            delay = AdvancingDelay(clock)
        )

        runner.runFromStepIndexForTest(
            startIndex = AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP.ordinal
        )

        val step = runner.state.value.steps[
            AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP.ordinal
        ]
        assertEquals(AutomatedDiagnosticStepStatus.BLOCKED, step.status)
        assertEquals(
            AutomatedDiagnosticsRequiredActionKind.REQUEST_WIFI_DIRECT_PERMISSIONS,
            step.requiredAction?.kind
        )
        assertEquals(0, environment.connectToWifiDirectPeerCallCount)
        scope.cancel()
    }

    @Test
    fun wifiDirectPermissionRetryPreservesSharedRunAndResumesWithoutNewRunId() = runBlocking {
        val clock = FakeMonotonicClock()
        val environment = FakePhaseOneEnvironment().apply {
            localPeerId = "0000-local-peer"
            configureCoordinatorPhaseTwoState()
            wifiDirectRuntimeStatus = wifiDirectRuntimeStatus.copy(
                permissionStatus = WifiDirectPermissionStatus(
                    requiredPermissions = setOf("android.permission.NEARBY_WIFI_DEVICES"),
                    missingPermissions = setOf("android.permission.NEARBY_WIFI_DEVICES"),
                    isWifiDirectSupported = true,
                    isWifiEnabled = true,
                    isWifiP2pEnabled = true
                )
            )
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runner = AutomatedDiagnosticsRunner(
            bindings = environment.createBindings(clock = clock, scope = scope),
            clock = clock,
            delay = AdvancingDelay(clock)
        )

        runner.runFromStepIndexForTest()

        val blockedState = runner.state.value
        assertEquals(AutomatedDiagnosticsOverallStatus.BLOCKED, blockedState.overallStatus)
        val preservedSharedRunId = blockedState.sharedRunId
        assertTrue(preservedSharedRunId != null)

        environment.wifiDirectRuntimeStatus = environment.wifiDirectRuntimeStatus.copy(
            permissionStatus = WifiDirectPermissionStatus(
                requiredPermissions = emptySet(),
                missingPermissions = emptySet(),
                isWifiDirectSupported = true,
                isWifiEnabled = true,
                isWifiP2pEnabled = true
            )
        )

        runner.retryFailedStep()

        val resumedState = runner.state.value
        assertEquals(
            resumedState.reportText,
            AutomatedDiagnosticsOverallStatus.PASS,
            resumedState.overallStatus
        )
        assertEquals(preservedSharedRunId, resumedState.sharedRunId)
        scope.cancel()
    }

    @Test
    fun sameCanonicalPairConflictKeepsLowerPeerCoordinatorWithoutWrongPeerRejection() = runBlocking {
        val clock = FakeMonotonicClock()
        val environment = FakePhaseOneEnvironment().apply {
            localPeerId = "0000-local-peer"
            configureCoordinatorPhaseTwoState()
            onRunAnnouncementRequested = { sharedRun, createdAtMillis ->
                if (automatedDiagnosticsRunAnnouncementRequestCount == 1) {
                    latestAutomatedDiagnosticsRunAnnouncement =
                        AutomatedDiagnosticsRunAnnouncement(
                            sharedRun = sharedRunFor(
                                coordinatorPeerId = peerIdentityKey,
                                participantPeerId = localPeerId,
                                createdAtMillis = createdAtMillis
                            ),
                            peerId = peerIdentityKey,
                            createdAtMillis = createdAtMillis
                        )
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runner = AutomatedDiagnosticsRunner(
            bindings = environment.createBindings(clock = clock, scope = scope),
            clock = clock,
            delay = AdvancingDelay(clock)
        )

        runner.runFromStepIndexForTest()

        val state = runner.state.value
        assertEquals(state.reportText, AutomatedDiagnosticsOverallStatus.PASS, state.overallStatus)
        assertEquals(AutomatedDiagnosticsPeerRole.COORDINATOR, state.localPeerRole)
        val coordinationStep = state.steps[
            AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION.ordinal
        ]
        assertEquals(
            "LOCAL_PROVISIONAL_WON",
            coordinationStep.evidenceValue("Conflict classification")
        )
        assertEquals("0", coordinationStep.evidenceValue("Wrong-peer rejected"))
        scope.cancel()
    }

    @Test
    fun unrelatedRunAnnouncementCountsAsWrongPeerAndDoesNotAdoptRemoteRun() = runBlocking {
        val clock = FakeMonotonicClock()
        val unrelatedPeerId = "ffffffffffffffff"
        val environment = FakePhaseOneEnvironment().apply {
            localPeerId = "0000-local-peer"
            onRunAnnouncementRequested = { _, createdAtMillis ->
                latestAutomatedDiagnosticsParticipantJoin = null
                latestAutomatedDiagnosticsRunAnnouncement =
                    AutomatedDiagnosticsRunAnnouncement(
                        sharedRun = sharedRunFor(
                            coordinatorPeerId = unrelatedPeerId,
                            participantPeerId = localPeerId,
                            createdAtMillis = createdAtMillis
                        ),
                        peerId = unrelatedPeerId,
                        createdAtMillis = createdAtMillis
                    )
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runner = AutomatedDiagnosticsRunner(
            bindings = environment.createBindings(clock = clock, scope = scope),
            clock = clock,
            delay = AdvancingDelay(clock)
        )

        runner.runFromStepIndexForTest()

        val state = runner.state.value
        assertEquals(AutomatedDiagnosticsOverallStatus.FAIL, state.overallStatus)
        val coordinationStep = state.steps[
            AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION.ordinal
        ]
        assertEquals(AutomatedDiagnosticStepStatus.FAIL, coordinationStep.status)
        assertTrue(
            coordinationStep.evidenceValue("Wrong-peer rejected")?.toIntOrNull()?.let { it > 0 }
                == true
        )
        assertEquals("wrong-peer", coordinationStep.evidenceValue("Last rejected reason"))
        assertEquals("false", coordinationStep.evidenceValue("Remote run adopted"))
        scope.cancel()
    }

    @Test
    fun oneButtonParticipantAutoJoinUsesAuthoritativeRunThroughRealReceivePath_whenSessionAlreadyReady() = runBlocking {
        val coordinatorPeerId = "9a04c27f89ba5ac7"
        val participantPeerId = "edb0abb84737d8c0"
        val coordinatorWallClock = FakeWallClock(1_000_000L)
        val participantWallClock = FakeWallClock(1_045_000L)
        val coordinatorEnvironment = FakePhaseOneEnvironment(
            initialDiscoveredPeers = listOf(sampleDiscoveredPeer(stableIdHex = participantPeerId))
        ).apply {
            localPeerId = coordinatorPeerId
            wallClockMillisProvider = coordinatorWallClock::nowMillis
            configureCoordinatorPhaseTwoState()
        }
        val participantEnvironment = FakePhaseOneEnvironment(
            initialDiscoveredPeers = listOf(sampleDiscoveredPeer(stableIdHex = coordinatorPeerId))
        ).apply {
            localPeerId = participantPeerId
            wallClockMillisProvider = participantWallClock::nowMillis
            autoPopulateDefaultRemoteRunAnnouncement = false
            secureSessionAssociationIdOverride = "chat-$participantPeerId"
            configureReadySecureSessionState()
            selectedSecurePeerId = null
            delayedSelectedSecurePeerSnapshotCount = 2
        }
        val coordinationTransport = SharedAutomatedDiagnosticsCoordinationTransport(
            first = coordinatorEnvironment,
            second = participantEnvironment
        )
        coordinatorEnvironment.coordinationTransport = coordinationTransport
        participantEnvironment.coordinationTransport = coordinationTransport

        val coordinatorClock = FakeMonotonicClock()
        val participantClock = FakeMonotonicClock()
        val coordinatorDelay = SuspendingAdvancingDelay(coordinatorClock)
        val participantScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val participantDelay = SuspendingAdvancingDelay(participantClock)
        val participantRunner = AutomatedDiagnosticsRunner(
            bindings = participantEnvironment.createBindings(
                clock = participantClock,
                scope = participantScope
            ),
            clock = participantClock,
            delay = participantDelay,
            wallClockMillis = participantWallClock::nowMillis
        )
        participantRunner.setAutomaticParticipationEnabled(true)
        val initialParticipantListener = participantRunner.listenerDiagnosticsForTest()
        assertTrue(initialParticipantListener.automaticParticipationEnabled)
        assertTrue(initialParticipantListener.listenerActive)
        assertEquals(1, initialParticipantListener.enableCallCount)
        assertEquals(1, initialParticipantListener.listenerGeneration)
        assertEquals(1, initialParticipantListener.listenerStartCount)
        var repeatedEnableTriggered = false
        val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinatorRunner = AutomatedDiagnosticsRunner(
            bindings = coordinatorEnvironment.createBindings(
                clock = coordinatorClock,
                scope = coordinatorScope
            ),
            clock = coordinatorClock,
            wallClockMillis = coordinatorWallClock::nowMillis,
            delay = coordinatorDelay
        )

        coordinatorRunner.start()
        assertTrue(
            "Coordinator and participant did not both reach Step 9 PASS through the real listener path.",
            advanceUntil(maxSteps = 240, advance = {
                coordinatorDelay.advanceSteps(1)
                if (
                    !repeatedEnableTriggered &&
                    participantEnvironment.latestAutomatedDiagnosticsRunAnnouncement != null
                ) {
                    repeatedEnableTriggered = true
                    repeat(3) {
                        participantRunner.setAutomaticParticipationEnabled(true)
                    }
                }
                participantDelay.advanceSteps(6)
            }) {
                val participantStepStatus = participantRunner.state.value.steps[
                    AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION.ordinal
                ].status
                val coordinatorStepStatus = coordinatorRunner.state.value.steps[
                    AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION.ordinal
                ].status
                participantStepStatus == AutomatedDiagnosticStepStatus.PASS &&
                    coordinatorStepStatus == AutomatedDiagnosticStepStatus.PASS
            }
        )

        val coordinatorState = coordinatorRunner.state.value
        val participantState = participantRunner.state.value
        val coordinatorStep = coordinatorState.steps[
            AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION.ordinal
        ]
        val participantStep = participantState.steps[
            AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION.ordinal
        ]
        val participantListener = participantRunner.listenerDiagnosticsForTest()

        assertTrue(repeatedEnableTriggered)
        assertEquals(
            "Coordinator report:\n${coordinatorState.reportText}\n\nParticipant report:\n${participantState.reportText}",
            AutomatedDiagnosticStepStatus.PASS,
            coordinatorStep.status
        )
        assertEquals(
            participantState.reportText,
            AutomatedDiagnosticStepStatus.PASS,
            participantStep.status
        )
        assertEquals(coordinatorState.sharedRunId, participantState.sharedRunId)
        assertEquals(
            coordinatorState.sharedRunCanonicalPeerPair,
            participantState.sharedRunCanonicalPeerPair
        )
        assertEquals(coordinatorPeerId, coordinatorState.sharedRunCoordinatorPeerId)
        assertEquals(coordinatorPeerId, participantState.sharedRunCoordinatorPeerId)
        assertEquals(participantPeerId, coordinatorState.sharedRunParticipantPeerId)
        assertEquals(participantPeerId, participantState.sharedRunParticipantPeerId)
        assertEquals(
            coordinatorState.sharedRunSessionAssociationId,
            participantState.sharedRunSessionAssociationId
        )
        assertEquals("true", coordinatorStep.evidenceValue("Participant joined"))
        assertEquals("true", participantStep.evidenceValue("Participant join sent"))
        assertEquals("0", coordinatorStep.evidenceValue("Wrong-peer rejected"))
        assertEquals("0", participantStep.evidenceValue("Wrong-peer rejected"))
        assertEquals("false", participantStep.evidenceValue("Conflict detected"))
        assertEquals("MANUAL_START", coordinatorStep.evidenceValue("Run start cause"))
        assertEquals("AUTOMATIC_PARTICIPANT_JOIN", participantStep.evidenceValue("Run start cause"))
        assertEquals("0", participantStep.evidenceValue("Manual start invocation count"))
        assertEquals("1", participantStep.evidenceValue("Participant start invocation count"))
        assertEquals("0", participantStep.evidenceValue("Local shared run ids generated"))
        assertEquals("none", participantStep.evidenceValue("Local provisional run id"))
        assertEquals("none", participantStep.evidenceValue("Remote provisional run id"))
        assertEquals("none", participantStep.evidenceValue("Run announcement sent timestamp"))
        assertEquals("0", participantStep.evidenceValue("RUN_ANNOUNCE send count"))
        assertEquals("1", participantStep.evidenceValue("PARTICIPANT_JOIN attempt count"))
        assertEquals("1", participantStep.evidenceValue("PARTICIPANT_JOIN successful send count"))
        assertEquals(
            "Participant join sent: run=${coordinatorState.sharedRunId} participant=$participantPeerId",
            participantStep.evidenceValue("Last participant join result")
        )
        assertEquals("1045000", participantStep.evidenceValue("Join frame createdAtMillis"))
        assertEquals("1105000", participantStep.evidenceValue("Join frame expiresAtMillis"))
        assertEquals("60000", participantStep.evidenceValue("Join expiry minus createdAt"))
        assertEquals("true", participantStep.evidenceValue("Active lease prepared before send"))
        assertEquals(1, coordinatorEnvironment.automatedDiagnosticsRunAnnouncementRequestCount)
        assertEquals(0, participantEnvironment.automatedDiagnosticsRunAnnouncementRequestCount)
        assertEquals(1, participantEnvironment.automatedDiagnosticsParticipantJoinRequestCount)
        assertEquals(coordinatorPeerId, participantEnvironment.selectedSecurePeerId)
        assertEquals(4, participantListener.enableCallCount)
        assertEquals(1, participantListener.listenerGeneration)
        assertEquals(1, participantListener.listenerStartCount)
        assertTrue(participantListener.listenerActive)
        assertEquals(0, participantListener.manualStartInvocationCount)
        assertEquals(1, participantListener.participantStartInvocationCount)
        assertEquals(1, participantListener.participantJobGeneration)
        assertEquals(0, participantListener.runAnnouncementSendCount)
        assertEquals(1, participantListener.participantJoinSendCount)
        assertEquals(1, participantListener.participantJoinSuccessfulSendCount)
        assertEquals(null, participantListener.pendingAnnouncementRunId)

        coordinatorScope.cancel()
        participantScope.cancel()
    }

    @Test
    fun manualTwoRunnerStartElectsLowerPeerCoordinatorAndWaitsForRunAnnouncement_whenStartingClean() = runBlocking {
        val coordinatorPeerId = "9a04c27f89ba5ac7"
        val participantPeerId = "edb0abb84737d8c0"
        val coordinatorWallClock = FakeWallClock(1_000_000L)
        val participantWallClock = FakeWallClock(1_045_000L)
        val coordinatorEnvironment = FakePhaseOneEnvironment(
            initialDiscoveredPeers = listOf(sampleDiscoveredPeer(stableIdHex = participantPeerId))
        ).apply {
            localPeerId = coordinatorPeerId
            wallClockMillisProvider = coordinatorWallClock::nowMillis
            configureCoordinatorPhaseTwoState()
        }
        val participantEnvironment = FakePhaseOneEnvironment(
            initialDiscoveredPeers = listOf(sampleDiscoveredPeer(stableIdHex = coordinatorPeerId))
        ).apply {
            localPeerId = participantPeerId
            wallClockMillisProvider = participantWallClock::nowMillis
            autoPopulateDefaultRemoteRunAnnouncement = false
            secureSessionAssociationIdOverride = "chat-$participantPeerId"
            selectedSecurePeerId = null
        }
        val coordinationTransport = SharedAutomatedDiagnosticsCoordinationTransport(
            first = coordinatorEnvironment,
            second = participantEnvironment
        )
        coordinatorEnvironment.coordinationTransport = coordinationTransport
        participantEnvironment.coordinationTransport = coordinationTransport

        val coordinatorClock = FakeMonotonicClock()
        val participantClock = FakeMonotonicClock()
        val coordinatorDelay = SuspendingAdvancingDelay(coordinatorClock)
        val participantDelay = SuspendingAdvancingDelay(participantClock)
        val participantScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val participantRunner = AutomatedDiagnosticsRunner(
            bindings = participantEnvironment.createBindings(
                clock = participantClock,
                scope = participantScope
            ),
            clock = participantClock,
            delay = participantDelay,
            wallClockMillis = participantWallClock::nowMillis
        )
        participantRunner.setAutomaticParticipationEnabled(true)
        val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinatorRunner = AutomatedDiagnosticsRunner(
            bindings = coordinatorEnvironment.createBindings(
                clock = coordinatorClock,
                scope = coordinatorScope
            ),
            clock = coordinatorClock,
            delay = coordinatorDelay,
            wallClockMillis = coordinatorWallClock::nowMillis
        )
        coordinatorRunner.setAutomaticParticipationEnabled(true)

        participantRunner.start()
        coordinatorRunner.start()

        val bothPassedStepNine = advanceUntil(maxSteps = 320, advance = {
            participantDelay.advanceSteps(1)
            coordinatorDelay.advanceSteps(1)
        }) {
            val participantStepStatus = participantRunner.state.value.steps[
                AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION.ordinal
            ].status
            val coordinatorStepStatus = coordinatorRunner.state.value.steps[
                AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION.ordinal
            ].status
            participantStepStatus == AutomatedDiagnosticStepStatus.PASS &&
                coordinatorStepStatus == AutomatedDiagnosticStepStatus.PASS
        }
        assertTrue(
            "Coordinator report:\n${coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${participantRunner.state.value.reportText}",
            bothPassedStepNine
        )

        val coordinatorState = coordinatorRunner.state.value
        val participantState = participantRunner.state.value
        val coordinatorStep = coordinatorState.steps[
            AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION.ordinal
        ]
        val participantStep = participantState.steps[
            AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION.ordinal
        ]
        val coordinatorRoleStep = coordinatorState.steps[
            AutomatedDiagnosticStepId.ROLE_ELECTION.ordinal
        ]
        val participantRoleStep = participantState.steps[
            AutomatedDiagnosticStepId.ROLE_ELECTION.ordinal
        ]
        val coordinatorIdentityStep = coordinatorState.steps[
            AutomatedDiagnosticStepId.IDENTITY_KEY_SETUP.ordinal
        ]
        val participantIdentityStep = participantState.steps[
            AutomatedDiagnosticStepId.IDENTITY_KEY_SETUP.ordinal
        ]
        val coordinatorSecureSessionStep = coordinatorState.steps[
            AutomatedDiagnosticStepId.SECURE_SESSION_READINESS.ordinal
        ]
        val participantSecureSessionStep = participantState.steps[
            AutomatedDiagnosticStepId.SECURE_SESSION_READINESS.ordinal
        ]
        val coordinatorListener = coordinatorRunner.listenerDiagnosticsForTest()
        val participantListener = participantRunner.listenerDiagnosticsForTest()

        assertEquals(AutomatedDiagnosticStepStatus.PASS, coordinatorRoleStep.status)
        assertEquals(AutomatedDiagnosticStepStatus.PASS, participantRoleStep.status)
        assertEquals("COORDINATOR", coordinatorRoleStep.evidenceValue("Role"))
        assertEquals("PARTICIPANT", participantRoleStep.evidenceValue("Role"))
        assertEquals(AutomatedDiagnosticStepStatus.PASS, coordinatorIdentityStep.status)
        assertEquals(AutomatedDiagnosticStepStatus.PASS, participantIdentityStep.status)
        assertEquals(AutomatedDiagnosticStepStatus.PASS, coordinatorSecureSessionStep.status)
        assertEquals(AutomatedDiagnosticStepStatus.PASS, participantSecureSessionStep.status)
        assertEquals("MANUAL_START", coordinatorStep.evidenceValue("Run start cause"))
        assertEquals("MANUAL_START", participantStep.evidenceValue("Run start cause"))
        assertEquals("1", coordinatorStep.evidenceValue("Manual start invocation count"))
        assertEquals("1", participantStep.evidenceValue("Manual start invocation count"))
        assertEquals("0", participantStep.evidenceValue("Participant start invocation count"))
        assertEquals("0", participantStep.evidenceValue("Local shared run ids generated"))
        assertEquals("none", participantStep.evidenceValue("Local provisional run id"))
        assertEquals("0", participantStep.evidenceValue("RUN_ANNOUNCE send count"))
        assertEquals("1", participantStep.evidenceValue("PARTICIPANT_JOIN successful send count"))
        assertEquals("PASS", coordinatorStep.status.name)
        assertEquals("PASS", participantStep.status.name)
        assertEquals("true", coordinatorStep.evidenceValue("Participant joined"))
        assertEquals("true", participantStep.evidenceValue("Participant join sent"))
        assertEquals("false", coordinatorStep.evidenceValue("Conflict detected"))
        assertEquals("false", participantStep.evidenceValue("Conflict detected"))
        assertEquals("1", coordinatorStep.evidenceValue("Local shared run ids generated"))
        assertTrue(requireNotNull(coordinatorStep.evidenceValue("RUN_ANNOUNCE send count")).toInt() >= 1)
        assertEquals(0, participantEnvironment.automatedDiagnosticsRunAnnouncementRequestCount)
        assertTrue(coordinatorEnvironment.automatedDiagnosticsRunAnnouncementRequestCount >= 1)
        assertEquals(1, participantEnvironment.automatedDiagnosticsParticipantJoinRequestCount)
        assertEquals(1, coordinatorListener.manualStartInvocationCount)
        assertEquals(1, participantListener.manualStartInvocationCount)
        assertEquals(0, coordinatorListener.participantStartInvocationCount)
        assertEquals(0, participantListener.participantStartInvocationCount)
        assertEquals(0, participantListener.runAnnouncementSendCount)
        assertEquals(1, participantListener.participantJoinSuccessfulSendCount)
        assertTrue(coordinatorListener.runAnnouncementSendCount >= 1)
        assertEquals(0, participantState.failedCount)
        assertEquals(0, coordinatorState.failedCount)
        assertEquals(
            coordinatorState.sharedRunId,
            participantState.sharedRunId
        )
        assertEquals(
            coordinatorState.sharedRunCanonicalPeerPair,
            participantState.sharedRunCanonicalPeerPair
        )
        assertEquals(
            coordinatorState.sharedRunSessionAssociationId,
            participantState.sharedRunSessionAssociationId
        )
        assertEquals(coordinatorPeerId, participantEnvironment.selectedSecurePeerId)
        assertEquals(participantPeerId, coordinatorEnvironment.selectedSecurePeerId)
        assertTrue(participantEnvironment.contacts.any { it.canonicalPeerId == coordinatorPeerId })
        assertTrue(coordinatorEnvironment.contacts.any { it.canonicalPeerId == participantPeerId })
        assertTrue(
            participantEnvironment.privateChatIdentitiesByPeerId[coordinatorPeerId]?.isEstablished
                == true
        )
        assertTrue(
            coordinatorEnvironment.privateChatIdentitiesByPeerId[participantPeerId]?.isEstablished
                == true
        )
        assertTrue(participantEnvironment.peerSessionDiagnostics.hasSessionForPeer(coordinatorPeerId))
        assertTrue(coordinatorEnvironment.peerSessionDiagnostics.hasSessionForPeer(participantPeerId))
        assertEquals("1045000", participantStep.evidenceValue("Join frame createdAtMillis"))
        assertEquals("1105000", participantStep.evidenceValue("Join frame expiresAtMillis"))
        assertEquals("60000", participantStep.evidenceValue("Join expiry minus createdAt"))
        assertEquals("true", participantStep.evidenceValue("Active lease prepared before send"))

        coordinatorScope.cancel()
        participantScope.cancel()
    }

    @Test
    fun stepElevenUsesValidatedDnsSdTokenWhenNoisePeersSortFirst() {
        val coordinatorLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
            deviceName = "Coordinator Pixel"
        )
        val participantLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
            deviceName = "Participant Pixel"
        )
        val hpDeskJetPeer = wifiDirectPeer(
            name = "DIRECT-80-HP DeskJet 5200 series",
            address = "ae:e2:d3:fe:08:80"
        )
        val participantPhonePeer = wifiDirectPeer(
            name = "Participant Pixel",
            address = "aa:bb:cc:dd:ee:20"
        )
        val braviaPeer = wifiDirectPeer(
            name = "BRAVIA KDL-48W605B",
            address = "92:48:9a:a6:36:b2"
        )
        val coordinatorPhonePeer = wifiDirectPeer(
            name = "Coordinator Pixel",
            address = "aa:bb:cc:dd:ee:10"
        )
        val harness = createStepElevenHarness(
            coordinatorLocalDeviceInfo = coordinatorLocalDeviceInfo,
            participantLocalDeviceInfo = participantLocalDeviceInfo,
            coordinatorWifiDirectPeers = listOf(hpDeskJetPeer, participantPhonePeer),
            participantWifiDirectPeers = listOf(braviaPeer, coordinatorPhonePeer),
            coordinatorObservedParticipantPeer = participantPhonePeer,
            participantObservedCoordinatorPeer = coordinatorPhonePeer,
            coordinatorExtraDnsSdResponses = listOf(
                wifiDirectDnsSdServiceResponse(
                    peer = hpDeskJetPeer,
                    token = "deadbeefdeadbeefdeadbeefdeadbeef"
                ),
                wifiDirectDnsSdServiceResponse(
                    peer = braviaPeer,
                    token = "feedfacefeedfacefeedfacefeedface"
                )
            )
        )

        try {
            harness.startBothManualRuns()

            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                harness.advanceUntil(maxSteps = 720) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION
                        ).status == AutomatedDiagnosticStepStatus.PASS
                }
            )
            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                harness.advanceUntil(maxSteps = 240) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                        ).status == AutomatedDiagnosticStepStatus.PASS
                }
            )

            val coordinatorState = harness.coordinatorRunner.state.value
            val participantState = harness.participantRunner.state.value
            val coordinatorStep = harness.coordinatorStep(
                AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
            )
            val participantStep = harness.participantStep(
                AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
            )

            assertStepsPassedThrough(
                coordinatorState,
                AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
            )
            assertStepsPassedThrough(
                participantState,
                AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
            )
            assertEquals(coordinatorState.sharedRunId, participantState.sharedRunId)
            assertEquals(
                coordinatorState.sharedRunSessionAssociationId,
                participantState.sharedRunSessionAssociationId
            )
            assertEquals(
                "VALIDATED_DNS_SD_TOKEN",
                coordinatorStep.evidenceValue("Selected peer source")
            )
            assertEquals("ANONYMIZED", coordinatorStep.evidenceValue("Local address classification"))
            assertTrue(
                coordinatorStep.evidenceValue("Discovered peers")
                    ?.contains("DIRECT-80-HP DeskJet 5200 series") == true
            )
            assertTrue(
                coordinatorStep.evidenceValue("Discovered peers")
                    ?.contains("Participant Pixel") == true
            )
            assertEquals(1, harness.coordinatorEnvironment.connectToWifiDirectPeerCallCount)
            assertEquals(
                normalizeWifiDirectDeviceAddress(participantPhonePeer.deviceAddress),
                normalizeWifiDirectDeviceAddress(
                    harness.coordinatorEnvironment.lastConnectedWifiDirectPeer?.deviceAddress
                )
            )
            assertTrue(
                normalizeWifiDirectDeviceAddress(
                    harness.coordinatorEnvironment.lastConnectedWifiDirectPeer?.deviceAddress
                ) != normalizeWifiDirectDeviceAddress(hpDeskJetPeer.deviceAddress)
            )
            assertEquals(0, harness.participantEnvironment.connectToWifiDirectPeerCallCount)
            assertEquals(1, harness.participantEnvironment.automatedDiagnosticsWifiDirectPeerReadyRequestCount)
            assertEquals("1", participantStep.evidenceValue("Correlation token BLE successful sends"))
            assertEquals("1", coordinatorStep.evidenceValue("DNS-SD token matches"))
            assertEquals("3", coordinatorStep.evidenceValue("DNS-SD responses received"))
            assertEquals(
                normalizeWifiDirectDeviceAddress(participantPhonePeer.deviceAddress),
                normalizeWifiDirectDeviceAddress(
                    coordinatorStep.evidenceValue("Matched service observed address")
                )
            )
            assertEquals(
                harness.participantEnvironment.localPeerId,
                coordinatorStep.evidenceValue("Remote Aurora peer id")
            )
            assertEquals(
                harness.coordinatorEnvironment.localPeerId,
                participantStep.evidenceValue("Remote Aurora peer id")
            )
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun stepElevenDoesNotConnectWhenOnlyNoiseDnsSdServicesAreVisible() {
        val coordinatorLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
            deviceName = "Coordinator Pixel"
        )
        val participantLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
            deviceName = "Participant Pixel"
        )
        val hpDeskJetPeer = wifiDirectPeer(
            name = "DIRECT-80-HP DeskJet 5200 series",
            address = "ae:e2:d3:fe:08:80"
        )
        val braviaPeer = wifiDirectPeer(
            name = "BRAVIA KDL-48W605B",
            address = "92:48:9a:a6:36:b2"
        )
        val coordinatorPhonePeer = wifiDirectPeer(
            name = "Coordinator Pixel",
            address = "aa:bb:cc:dd:ee:10"
        )
        val harness = createStepElevenHarness(
            coordinatorLocalDeviceInfo = coordinatorLocalDeviceInfo,
            participantLocalDeviceInfo = participantLocalDeviceInfo,
            coordinatorWifiDirectPeers = listOf(hpDeskJetPeer),
            participantWifiDirectPeers = listOf(braviaPeer, coordinatorPhonePeer),
            participantObservedCoordinatorPeer = coordinatorPhonePeer,
            coordinatorExtraDnsSdResponses = listOf(
                wifiDirectDnsSdServiceResponse(
                    peer = hpDeskJetPeer,
                    token = "deadbeefdeadbeefdeadbeefdeadbeef"
                ),
                wifiDirectDnsSdServiceResponse(
                    peer = braviaPeer,
                    token = "feedfacefeedfacefeedfacefeedface"
                )
            ),
            timingPolicy = stepElevenTimingPolicy()
        )

        try {
            harness.startBothManualRuns()

            assertTrue(
                harness.advanceUntil(maxSteps = 720) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION
                        ).status == AutomatedDiagnosticStepStatus.PASS
                }
            )
            assertTrue(
                harness.advanceUntil(maxSteps = 320) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                    ).status == AutomatedDiagnosticStepStatus.BLOCKED
                }
            )

            val coordinatorStep = harness.coordinatorStep(
                AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
            )
            assertEquals(0, harness.coordinatorEnvironment.connectToWifiDirectPeerCallCount)
            assertEquals(null, harness.coordinatorEnvironment.lastConnectedWifiDirectPeer)
            assertEquals("0", coordinatorStep.evidenceValue("DNS-SD token matches"))
            assertEquals("2", coordinatorStep.evidenceValue("DNS-SD responses received"))
            assertTrue(
                coordinatorStep.evidenceValue("Discovered peers")
                    ?.contains("DIRECT-80-HP DeskJet 5200 series") == true
            )
            assertTrue(
                coordinatorStep.blockerOrFailure
                    ?.contains("DNS-SD token match") == true
            )
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun stepElevenRejectsWrongRunPeerReadySignalWithoutConnecting() {
        val coordinatorLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
            deviceName = "Coordinator Pixel"
        )
        val participantLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
            deviceName = "Participant Pixel"
        )
        val participantPhonePeer = wifiDirectPeer(
            name = "Participant Pixel",
            address = "aa:bb:cc:dd:ee:20"
        )
        val coordinatorPhonePeer = wifiDirectPeer(
            name = "Coordinator Pixel",
            address = "aa:bb:cc:dd:ee:10"
        )
        val harness = createStepElevenHarness(
            coordinatorLocalDeviceInfo = coordinatorLocalDeviceInfo,
            participantLocalDeviceInfo = participantLocalDeviceInfo,
            coordinatorWifiDirectPeers = listOf(participantPhonePeer),
            participantWifiDirectPeers = listOf(coordinatorPhonePeer),
            timingPolicy = stepElevenTimingPolicy()
        )
        harness.participantEnvironment.onWifiDirectPeerReadyRequested = {
                sharedRun,
                expectedRemotePeerId,
                wifiDirectCorrelationToken,
                wifiDirectDeviceName,
                createdAtMillis ->
            requireNotNull(harness.participantEnvironment.coordinationTransport).sendWifiDirectPeerReady(
                from = harness.participantEnvironment,
                sharedRun = sharedRun.copy(runId = "${sharedRun.runId}-wrong"),
                expectedRemotePeerId = expectedRemotePeerId,
                wifiDirectCorrelationToken = wifiDirectCorrelationToken,
                wifiDirectDeviceName = wifiDirectDeviceName,
                createdAtMillis = createdAtMillis
            )
        }

        try {
            harness.startBothManualRuns()

            assertTrue(
                harness.advanceUntil(maxSteps = 720) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION
                        ).status == AutomatedDiagnosticStepStatus.PASS
                }
            )
            assertTrue(
                harness.advanceUntil(maxSteps = 320) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                    ).status == AutomatedDiagnosticStepStatus.BLOCKED
                }
            )

            val coordinatorStep = harness.coordinatorStep(
                AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
            )
            assertEquals(0, harness.coordinatorEnvironment.connectToWifiDirectPeerCallCount)
            assertEquals("wrong-run", coordinatorStep.evidenceValue("Last token rejection reason"))
            assertEquals(
                "signal.sharedRun.runId",
                coordinatorStep.evidenceValue("Last peer-ready rejection field")
            )
            assertEquals("none", coordinatorStep.evidenceValue("Selected peer source"))
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun stepElevenRejectsWrongSessionPeerReadySignalWithoutConnecting() {
        val coordinatorLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
            deviceName = "Coordinator Pixel"
        )
        val participantLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
            deviceName = "Participant Pixel"
        )
        val participantPhonePeer = wifiDirectPeer(
            name = "Participant Pixel",
            address = "aa:bb:cc:dd:ee:20"
        )
        val coordinatorPhonePeer = wifiDirectPeer(
            name = "Coordinator Pixel",
            address = "aa:bb:cc:dd:ee:10"
        )
        val harness = createStepElevenHarness(
            coordinatorLocalDeviceInfo = coordinatorLocalDeviceInfo,
            participantLocalDeviceInfo = participantLocalDeviceInfo,
            coordinatorWifiDirectPeers = listOf(participantPhonePeer),
            participantWifiDirectPeers = listOf(coordinatorPhonePeer),
            timingPolicy = stepElevenTimingPolicy()
        )
        harness.participantEnvironment.onWifiDirectPeerReadyRequested = {
                sharedRun,
                expectedRemotePeerId,
                wifiDirectCorrelationToken,
                wifiDirectDeviceName,
                createdAtMillis ->
            requireNotNull(harness.participantEnvironment.coordinationTransport).sendWifiDirectPeerReady(
                from = harness.participantEnvironment,
                sharedRun = sharedRun.copy(
                    sessionAssociationId = "${sharedRun.sessionAssociationId}-wrong"
                ),
                expectedRemotePeerId = expectedRemotePeerId,
                wifiDirectCorrelationToken = wifiDirectCorrelationToken,
                wifiDirectDeviceName = wifiDirectDeviceName,
                createdAtMillis = createdAtMillis
            )
        }

        try {
            harness.startBothManualRuns()

            assertTrue(
                harness.advanceUntil(maxSteps = 720) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION
                        ).status == AutomatedDiagnosticStepStatus.PASS
                }
            )
            assertTrue(
                harness.advanceUntil(maxSteps = 320) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                    ).status == AutomatedDiagnosticStepStatus.BLOCKED
                }
            )

            val coordinatorStep = harness.coordinatorStep(
                AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
            )
            assertEquals(0, harness.coordinatorEnvironment.connectToWifiDirectPeerCallCount)
            assertEquals("wrong-session", coordinatorStep.evidenceValue("Last token rejection reason"))
            assertEquals(
                "signal.sharedRun.sessionAssociationId",
                coordinatorStep.evidenceValue("Last peer-ready rejection field")
            )
            assertEquals("none", coordinatorStep.evidenceValue("Selected peer source"))
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun stepElevenDoesNotUseDeviceNameMatchWhenDnsSdTokenDiffers() {
        val coordinatorLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
            deviceName = "Coordinator Pixel"
        )
        val participantLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
            deviceName = "Participant Pixel"
        )
        val misleadingNamePeer = wifiDirectPeer(
            name = "Participant Pixel",
            address = "aa:bb:cc:dd:ee:99"
        )
        val coordinatorPhonePeer = wifiDirectPeer(
            name = "Coordinator Pixel",
            address = "aa:bb:cc:dd:ee:10"
        )
        val harness = createStepElevenHarness(
            coordinatorLocalDeviceInfo = coordinatorLocalDeviceInfo,
            participantLocalDeviceInfo = participantLocalDeviceInfo,
            coordinatorWifiDirectPeers = listOf(misleadingNamePeer),
            participantWifiDirectPeers = listOf(coordinatorPhonePeer),
            participantObservedCoordinatorPeer = coordinatorPhonePeer,
            coordinatorExtraDnsSdResponses = listOf(
                wifiDirectDnsSdServiceResponse(
                    peer = misleadingNamePeer,
                    token = "deadbeefdeadbeefdeadbeefdeadbeef"
                )
            ),
            timingPolicy = stepElevenTimingPolicy()
        )

        try {
            harness.startBothManualRuns()

            assertTrue(
                harness.advanceUntil(maxSteps = 720) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION
                        ).status == AutomatedDiagnosticStepStatus.PASS
                }
            )
            assertTrue(
                harness.advanceUntil(maxSteps = 320) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                    ).status == AutomatedDiagnosticStepStatus.BLOCKED
                }
            )

            val coordinatorStep = harness.coordinatorStep(
                AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
            )
            assertEquals(0, harness.coordinatorEnvironment.connectToWifiDirectPeerCallCount)
            assertTrue(
                coordinatorStep.evidenceValue("Discovered peers")
                    ?.contains("Participant Pixel <aa:bb:cc:dd:ee:99>") == true
            )
            assertEquals("0", coordinatorStep.evidenceValue("DNS-SD token matches"))
            assertEquals("1", coordinatorStep.evidenceValue("DNS-SD responses received"))
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun stepElevenRefreshesStalePeerReadySignalUsingTheSameToken() {
        val participantPhonePeer = wifiDirectPeer(
            name = "Participant Pixel",
            address = "aa:bb:cc:dd:ee:20"
        )
        val coordinatorPhonePeer = wifiDirectPeer(
            name = "Coordinator Pixel",
            address = "aa:bb:cc:dd:ee:10"
        )
        val harness = createStepElevenHarness(
            coordinatorLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
                deviceName = "Coordinator Pixel"
            ),
            participantLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
                deviceName = "Participant Pixel"
            ),
            coordinatorWifiDirectPeers = emptyList(),
            participantWifiDirectPeers = listOf(coordinatorPhonePeer),
            coordinatorObservedParticipantPeer = null,
            participantObservedCoordinatorPeer = coordinatorPhonePeer,
            timingPolicy = stepElevenTimingPolicy().copy(
                wifiDirectDiscovery = AutomatedDiagnosticsTimingWindow(
                    stabilizationMillis = 0L,
                    timeoutMillis = 14_000L
                ),
                wifiDirectGroupFormation = AutomatedDiagnosticsTimingWindow(
                    stabilizationMillis = 0L,
                    timeoutMillis = 12_000L,
                    maxRetries = 1
                ),
                pollIntervalMillis = 250L
            )
        )
        val sentTokens = mutableListOf<String>()
        var successfulSendCount = 0
        var firstSuccessfulExpiresAtMillis: Long? = null
        var latestSuccessfulCreatedAtMillis: Long? = null
        var dnsSdInjected = false
        harness.participantEnvironment.onWifiDirectPeerReadyRequested = {
                sharedRun,
                expectedRemotePeerId,
                wifiDirectCorrelationToken,
                wifiDirectDeviceName,
                createdAtMillis ->
            sentTokens += wifiDirectCorrelationToken
            when (sentTokens.size) {
                2 -> AutomatedDiagnosticsWifiDirectPeerReadySendResult.SendFailed(
                    "Intentional stale-window delay for resend recovery test."
                )

                else -> {
                    val result = requireNotNull(harness.participantEnvironment.coordinationTransport)
                        .sendWifiDirectPeerReady(
                            from = harness.participantEnvironment,
                            sharedRun = sharedRun,
                            expectedRemotePeerId = expectedRemotePeerId,
                            wifiDirectCorrelationToken = wifiDirectCorrelationToken,
                            wifiDirectDeviceName = wifiDirectDeviceName,
                            createdAtMillis = createdAtMillis
                        )
                    if (result is AutomatedDiagnosticsWifiDirectPeerReadySendResult.Sent) {
                        successfulSendCount += 1
                        latestSuccessfulCreatedAtMillis = result.signal.createdAtMillis
                        if (firstSuccessfulExpiresAtMillis == null) {
                            firstSuccessfulExpiresAtMillis = result.signal.expiresAtMillis
                        }
                    }
                    result
                }
            }
        }

        try {
            harness.startBothManualRuns()

            val bothPassedGroupStep = harness.advanceUntil(maxSteps = 640) {
                    val firstExpiry = firstSuccessfulExpiresAtMillis
                    val latestCreatedAt = latestSuccessfulCreatedAtMillis
                    if (
                        !dnsSdInjected &&
                        firstExpiry != null &&
                        latestCreatedAt != null &&
                        successfulSendCount >= 2 &&
                        harness.participantEnvironment.currentWallClockMillis() > firstExpiry &&
                        harness.coordinatorEnvironment.latestAutomatedDiagnosticsWifiDirectPeerReadySignal
                            ?.createdAtMillis == latestCreatedAt
                    ) {
                        harness.coordinatorEnvironment.observedWifiDirectPeerByRemotePeerId[
                            harness.participantEnvironment.localPeerId
                        ] = participantPhonePeer
                        harness.coordinatorEnvironment.wifiDirectRuntimeStatus =
                            harness.coordinatorEnvironment.wifiDirectRuntimeStatus.copy(
                                peers = listOf(participantPhonePeer),
                                dnsSdDiagnostics =
                                harness.coordinatorEnvironment.wifiDirectRuntimeStatus.dnsSdDiagnostics.copy(
                                    serviceRequestRegistered = true,
                                    discoveryStarted = true,
                                    serviceType = automatedDiagnosticsWifiDirectDnsSdServiceType,
                                    discoveredServices = listOf(
                                        wifiDirectDnsSdServiceResponse(
                                            peer = participantPhonePeer,
                                            token = sentTokens.last(),
                                            deviceName =
                                            harness.participantEnvironment
                                                .wifiDirectRuntimeStatus
                                                .localDeviceInfo
                                                .deviceName
                                        )
                                    ),
                                    lastError = null,
                                    cleanupCompleted = false
                                )
                            )
                        dnsSdInjected = true
                    }
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                        ).status == AutomatedDiagnosticStepStatus.PASS
                }
            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                bothPassedGroupStep
            )

            val coordinatorStep = harness.coordinatorStep(
                AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
            )
            val participantStep = harness.participantStep(
                AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
            )
            assertTrue(dnsSdInjected)
            assertTrue(successfulSendCount >= 2)
            assertEquals(1, sentTokens.distinct().size)
            assertEquals(
                participantStep.evidenceValue("Correlation token fingerprint"),
                coordinatorStep.evidenceValue("Remote signaled token")
            )
            assertTrue(
                (participantStep.evidenceValue("Correlation token BLE successful sends")
                    ?.toInt() ?: 0) >= 2
            )
            assertTrue(
                (coordinatorStep.evidenceValue("Correlation token accepted")
                    ?.toInt() ?: 0) >= 2
            )
            assertEquals(1, harness.coordinatorEnvironment.connectToWifiDirectPeerCallCount)
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun stepElevenWaitsForValidatedPeerToAppearBeforeConnecting() {
        val participantPhonePeer = wifiDirectPeer(
            name = "Participant Pixel",
            address = "aa:bb:cc:dd:ee:20"
        )
        val hpDeskJetPeer = wifiDirectPeer(
            name = "DIRECT-80-HP DeskJet 5200 series",
            address = "ae:e2:d3:fe:08:80"
        )
        val coordinatorPhonePeer = wifiDirectPeer(
            name = "Coordinator Pixel",
            address = "aa:bb:cc:dd:ee:10"
        )
        val harness = createStepElevenHarness(
            coordinatorLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
                deviceName = "Coordinator Pixel"
            ),
            participantLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
                deviceName = "Participant Pixel"
            ),
            coordinatorWifiDirectPeers = emptyList(),
            participantWifiDirectPeers = listOf(coordinatorPhonePeer),
            coordinatorObservedParticipantPeer = participantPhonePeer,
            participantObservedCoordinatorPeer = coordinatorPhonePeer,
            timingPolicy = stepElevenTimingPolicy()
        )

        try {
            harness.startBothManualRuns()

            assertTrue(
                harness.advanceUntil(maxSteps = 240) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                    ).evidenceValue("DNS-SD token matches") == "1" &&
                        harness.coordinatorEnvironment.connectToWifiDirectPeerCallCount == 0
                }
            )

            val waitingStep = harness.coordinatorStep(
                AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
            )
            assertEquals(AutomatedDiagnosticStepStatus.RUNNING, waitingStep.status)
            assertEquals("false", waitingStep.evidenceValue("Validated peer visible"))
            assertTrue(
                waitingStep.blockerOrFailure?.contains("ordinary peer list") == true
            )

            harness.coordinatorEnvironment.wifiDirectRuntimeStatus =
                harness.coordinatorEnvironment.wifiDirectRuntimeStatus.copy(
                    peers = listOf(hpDeskJetPeer, participantPhonePeer)
                )

            assertTrue(
                harness.advanceUntil(maxSteps = 240) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                        ).status == AutomatedDiagnosticStepStatus.PASS
                }
            )

            assertEquals(1, harness.coordinatorEnvironment.connectToWifiDirectPeerCallCount)
            assertEquals(
                normalizeWifiDirectDeviceAddress(participantPhonePeer.deviceAddress),
                normalizeWifiDirectDeviceAddress(
                    harness.coordinatorEnvironment.lastConnectedWifiDirectPeer?.deviceAddress
                )
            )
            assertTrue(
                normalizeWifiDirectDeviceAddress(
                    harness.coordinatorEnvironment.lastConnectedWifiDirectPeer?.deviceAddress
                ) != normalizeWifiDirectDeviceAddress(hpDeskJetPeer.deviceAddress)
            )
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun stepElevenRetriesValidatedConnectOnceAfterFailure() {
        val participantPhonePeer = wifiDirectPeer(
            name = "Participant Pixel",
            address = "aa:bb:cc:dd:ee:20"
        )
        val coordinatorPhonePeer = wifiDirectPeer(
            name = "Coordinator Pixel",
            address = "aa:bb:cc:dd:ee:10"
        )
        val harness = createStepElevenHarness(
            coordinatorLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
                deviceName = "Coordinator Pixel"
            ),
            participantLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
                deviceName = "Participant Pixel"
            ),
            coordinatorWifiDirectPeers = listOf(participantPhonePeer),
            participantWifiDirectPeers = listOf(coordinatorPhonePeer),
            coordinatorObservedParticipantPeer = participantPhonePeer,
            participantObservedCoordinatorPeer = coordinatorPhonePeer,
            timingPolicy = stepElevenTimingPolicy()
        )
        harness.coordinatorEnvironment.pendingWifiDirectConnectFailures.addLast(
            "Wi-Fi Direct connect failed: busy"
        )

        try {
            harness.startBothManualRuns()

            val bothPassedGroupStep = harness.advanceUntil(maxSteps = 240) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                        ).status == AutomatedDiagnosticStepStatus.PASS
                }
            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                bothPassedGroupStep
            )

            val coordinatorStep = harness.coordinatorStep(
                AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
            )
            assertEquals(2, harness.coordinatorEnvironment.connectToWifiDirectPeerCallCount)
            assertEquals("2", coordinatorStep.evidenceValue("Wi-Fi Direct connect invocation count"))
            assertEquals("none", coordinatorStep.evidenceValue("Wi-Fi Direct connect last failure"))
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun stepElevenDoesNotDuplicateConnectWhileValidatedPeerIsConnecting() {
        val participantPhonePeer = wifiDirectPeer(
            name = "Participant Pixel",
            address = "aa:bb:cc:dd:ee:20"
        )
        val coordinatorPhonePeer = wifiDirectPeer(
            name = "Coordinator Pixel",
            address = "aa:bb:cc:dd:ee:10"
        )
        val harness = createStepElevenHarness(
            coordinatorLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
                deviceName = "Coordinator Pixel"
            ),
            participantLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
                deviceName = "Participant Pixel"
            ),
            coordinatorWifiDirectPeers = listOf(participantPhonePeer),
            participantWifiDirectPeers = listOf(coordinatorPhonePeer),
            coordinatorObservedParticipantPeer = participantPhonePeer,
            participantObservedCoordinatorPeer = coordinatorPhonePeer,
            timingPolicy = stepElevenTimingPolicy()
        )
        harness.coordinatorEnvironment.autoCompleteWifiDirectGroupFormationOnConnect = false

        try {
            harness.startBothManualRuns()

            assertTrue(
                harness.advanceUntil(maxSteps = 240) {
                    harness.coordinatorEnvironment.connectToWifiDirectPeerCallCount == 1 &&
                        harness.coordinatorEnvironment.wifiDirectRuntimeStatus.connectionStatus.state ==
                        WifiDirectConnectionState.CONNECTING
                }
            )

            harness.advanceSteps(10)

            assertEquals(1, harness.coordinatorEnvironment.connectToWifiDirectPeerCallCount)

            harness.coordinatorEnvironment.completeWifiDirectGroupAsGroupOwner(participantPhonePeer)
            harness.participantEnvironment.completeWifiDirectGroupAsClient(coordinatorPhonePeer)

            val bothPassedGroupStep = harness.advanceUntil(maxSteps = 240) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                        ).status == AutomatedDiagnosticStepStatus.PASS
                }
            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                bothPassedGroupStep
            )

            assertEquals(1, harness.coordinatorEnvironment.connectToWifiDirectPeerCallCount)
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun stepElevenDeduplicatesMatchingDnsSdCallbacksAndConnectsOnce() {
        val participantPhonePeer = wifiDirectPeer(
            name = "Participant Pixel",
            address = "aa:bb:cc:dd:ee:20"
        )
        val coordinatorPhonePeer = wifiDirectPeer(
            name = "Coordinator Pixel",
            address = "aa:bb:cc:dd:ee:10"
        )
        val harness = createStepElevenHarness(
            coordinatorLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
                deviceName = "Coordinator Pixel"
            ),
            participantLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
                deviceName = "Participant Pixel"
            ),
            coordinatorWifiDirectPeers = listOf(participantPhonePeer),
            participantWifiDirectPeers = listOf(coordinatorPhonePeer),
            coordinatorObservedParticipantPeer = participantPhonePeer,
            participantObservedCoordinatorPeer = coordinatorPhonePeer
        )
        harness.coordinatorEnvironment.duplicateRemoteAutomatedDiagnosticsDnsSdResponses = true

        try {
            harness.startBothManualRuns()

            assertTrue(
                harness.advanceUntil(maxSteps = 240) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                        ).status == AutomatedDiagnosticStepStatus.PASS
                }
            )

            val coordinatorStep = harness.coordinatorStep(
                AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
            )
            assertEquals(1, harness.coordinatorEnvironment.connectToWifiDirectPeerCallCount)
            assertEquals("1", coordinatorStep.evidenceValue("DNS-SD responses received"))
            assertEquals("1", coordinatorStep.evidenceValue("DNS-SD token matches"))
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun stepElevenEvidenceReportsRemoteAuroraPeerIdForBothRoles() {
        val participantPhonePeer = wifiDirectPeer(
            name = "Participant Pixel",
            address = "aa:bb:cc:dd:ee:20"
        )
        val coordinatorPhonePeer = wifiDirectPeer(
            name = "Coordinator Pixel",
            address = "aa:bb:cc:dd:ee:10"
        )
        val harness = createStepElevenHarness(
            coordinatorLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
                deviceName = "Coordinator Pixel"
            ),
            participantLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
                deviceName = "Participant Pixel"
            ),
            coordinatorWifiDirectPeers = listOf(participantPhonePeer),
            participantWifiDirectPeers = listOf(coordinatorPhonePeer),
            coordinatorObservedParticipantPeer = participantPhonePeer,
            participantObservedCoordinatorPeer = coordinatorPhonePeer
        )

        try {
            harness.startBothManualRuns()

            assertTrue(
                harness.advanceUntil(maxSteps = 240) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                        ).status == AutomatedDiagnosticStepStatus.PASS
                }
            )

            assertEquals(
                harness.participantEnvironment.localPeerId,
                harness.coordinatorStep(
                    AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                ).evidenceValue("Remote Aurora peer id")
            )
            assertEquals(
                harness.coordinatorEnvironment.localPeerId,
                harness.participantStep(
                    AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                ).evidenceValue("Remote Aurora peer id")
            )
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun stepElevenStopCleansDnsSdStateOnceSafely() {
        val participantPhonePeer = wifiDirectPeer(
            name = "Participant Pixel",
            address = "aa:bb:cc:dd:ee:20"
        )
        val coordinatorPhonePeer = wifiDirectPeer(
            name = "Coordinator Pixel",
            address = "aa:bb:cc:dd:ee:10"
        )
        val harness = createStepElevenHarness(
            coordinatorLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
                deviceName = "Coordinator Pixel"
            ),
            participantLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
                deviceName = "Participant Pixel"
            ),
            coordinatorWifiDirectPeers = listOf(participantPhonePeer),
            participantWifiDirectPeers = listOf(coordinatorPhonePeer),
            coordinatorObservedParticipantPeer = participantPhonePeer,
            participantObservedCoordinatorPeer = coordinatorPhonePeer,
            timingPolicy = stepElevenTimingPolicy().copy(
                wifiDirectDiscovery = AutomatedDiagnosticsTimingWindow(
                    stabilizationMillis = 0L,
                    timeoutMillis = 3_000L
                )
            )
        )

        try {
            harness.startBothManualRuns()

            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                harness.advanceUntil(maxSteps = 320) {
                    harness.participantEnvironment.registerAutomatedDiagnosticsWifiDirectServiceCallCount == 1 &&
                        harness.coordinatorEnvironment.startAutomatedDiagnosticsWifiDirectServiceDiscoveryCallCount == 1
                }
            )

            harness.participantRunner.stop()
            harness.coordinatorRunner.stop()

            assertTrue(
                harness.advanceUntil(maxSteps = 40) {
                    harness.participantEnvironment.clearAutomatedDiagnosticsWifiDirectServiceDiscoveryCallCount == 1 &&
                        harness.coordinatorEnvironment.clearAutomatedDiagnosticsWifiDirectServiceDiscoveryCallCount == 1
                }
            )
            assertTrue(
                harness.participantEnvironment.wifiDirectRuntimeStatus.dnsSdDiagnostics.cleanupCompleted
            )
            assertTrue(
                harness.coordinatorEnvironment.wifiDirectRuntimeStatus.dnsSdDiagnostics.cleanupCompleted
            )
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun stepElevenRequiresFreshCurrentRunProofAfterClearingPreExistingGroupAndSocket() {
        val participantPhonePeer = wifiDirectPeer(
            name = "Participant Pixel",
            address = "aa:bb:cc:dd:ee:20"
        )
        val coordinatorPhonePeer = wifiDirectPeer(
            name = "Coordinator Pixel",
            address = "aa:bb:cc:dd:ee:10"
        )
        val harness = createStepElevenHarness(
            coordinatorLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
                deviceName = "Coordinator Pixel"
            ),
            participantLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
                deviceName = "Participant Pixel"
            ),
            coordinatorWifiDirectPeers = listOf(participantPhonePeer),
            participantWifiDirectPeers = listOf(coordinatorPhonePeer),
            coordinatorObservedParticipantPeer = participantPhonePeer,
            participantObservedCoordinatorPeer = coordinatorPhonePeer,
            timingPolicy = stepElevenTimingPolicy()
        )
        harness.coordinatorEnvironment.completeWifiDirectGroupAsGroupOwner(participantPhonePeer)
        harness.participantEnvironment.completeWifiDirectGroupAsClient(coordinatorPhonePeer)
        harness.coordinatorEnvironment.wifiDirectSocketDiagnostics = WifiDirectSocketDiagnostics(
            state = WifiDirectSocketState.CONNECTED,
            role = WifiDirectSocketRole.SERVER,
            endpoint = WifiDirectSocketEndpoint(
                host = "192.168.49.1",
                port = wifiDirectDebugSocketPort
            ),
            isConnected = true,
            isReadLoopActive = true
        )
        harness.participantEnvironment.wifiDirectSocketDiagnostics = WifiDirectSocketDiagnostics(
            state = WifiDirectSocketState.CONNECTED,
            role = WifiDirectSocketRole.CLIENT,
            endpoint = WifiDirectSocketEndpoint(
                host = "192.168.49.1",
                port = wifiDirectDebugSocketPort
            ),
            isConnected = true,
            isReadLoopActive = true
        )
        harness.coordinatorEnvironment.wifiDirectAdapterDiagnostics =
            WifiDirectTransportAdapterDiagnostics(
                state = WifiDirectTransportAdapterState.READY
            )
        harness.participantEnvironment.wifiDirectAdapterDiagnostics =
            WifiDirectTransportAdapterDiagnostics(
                state = WifiDirectTransportAdapterState.READY
            )
        harness.coordinatorEnvironment.registerAutomatedDiagnosticsWifiDirectService(
            correlationToken = "stale-coordinator-token",
            deviceNameHint = "Coordinator Pixel"
        )
        harness.participantEnvironment.registerAutomatedDiagnosticsWifiDirectService(
            correlationToken = "stale-participant-token",
            deviceNameHint = "Participant Pixel"
        )
        harness.coordinatorEnvironment.startAutomatedDiagnosticsWifiDirectServiceDiscovery()
        harness.participantEnvironment.startAutomatedDiagnosticsWifiDirectServiceDiscovery()

        try {
            harness.startBothManualRuns()
            harness.advanceSteps(1)

            assertTrue(
                harness.advanceUntil(maxSteps = 720) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION
                        ).status == AutomatedDiagnosticStepStatus.PASS
                }
            )
            assertTrue(
                harness.advanceUntil(maxSteps = 160) {
                    harness.coordinatorEnvironment.disconnectWifiDirectPeerCallCount == 1 &&
                        harness.participantEnvironment.disconnectWifiDirectPeerCallCount == 1 &&
                        harness.coordinatorEnvironment.closeWifiDirectSocketCallCount == 1 &&
                        harness.participantEnvironment.closeWifiDirectSocketCallCount == 1
                }
            )

            val interimCoordinatorStep = harness.coordinatorStep(
                AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
            )
            assertTrue(interimCoordinatorStep.status != AutomatedDiagnosticStepStatus.PASS)
            assertEquals(
                "true",
                interimCoordinatorStep.evidenceValue("Fresh baseline socket close/reset requested")
            )

            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                harness.advanceUntil(maxSteps = 320) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                        ).status == AutomatedDiagnosticStepStatus.PASS
                }
            )

            val coordinatorStep = harness.coordinatorStep(
                AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
            )
            val participantStep = harness.participantStep(
                AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
            )
            assertEquals("true", coordinatorStep.evidenceValue("Pre-existing group detected"))
            assertEquals("true", coordinatorStep.evidenceValue("Pre-existing socket detected"))
            assertEquals("1", coordinatorStep.evidenceValue("Fresh baseline disconnect request count"))
            assertEquals("true", coordinatorStep.evidenceValue("Fresh baseline established"))
            assertEquals("CURRENT_RUN_VALIDATED", coordinatorStep.evidenceValue("Group provenance"))
            assertEquals("true", participantStep.evidenceValue("Pre-existing group detected"))
            assertEquals("true", participantStep.evidenceValue("Pre-existing socket detected"))
            assertEquals("1", participantStep.evidenceValue("Fresh baseline disconnect request count"))
            assertEquals("true", participantStep.evidenceValue("Fresh baseline established"))
            assertEquals("CURRENT_RUN_VALIDATED", participantStep.evidenceValue("Group provenance"))
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun stepElevenBlocksPreExistingGroupWhenFreshParticipantTokenNeverArrives() {
        val participantPhonePeer = wifiDirectPeer(
            name = "Participant Pixel",
            address = "aa:bb:cc:dd:ee:20"
        )
        val coordinatorPhonePeer = wifiDirectPeer(
            name = "Coordinator Pixel",
            address = "aa:bb:cc:dd:ee:10"
        )
        val harness = createStepElevenHarness(
            coordinatorLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
                deviceName = "Coordinator Pixel"
            ),
            participantLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
                deviceName = "Participant Pixel"
            ),
            coordinatorWifiDirectPeers = listOf(participantPhonePeer),
            participantWifiDirectPeers = listOf(coordinatorPhonePeer),
            coordinatorObservedParticipantPeer = participantPhonePeer,
            participantObservedCoordinatorPeer = coordinatorPhonePeer,
            timingPolicy = stepElevenTimingPolicy()
        )
        harness.coordinatorEnvironment.completeWifiDirectGroupAsGroupOwner(participantPhonePeer)
        harness.participantEnvironment.completeWifiDirectGroupAsClient(coordinatorPhonePeer)
        harness.participantEnvironment.onWifiDirectPeerReadyRequested = { _, _, _, _, _ ->
            AutomatedDiagnosticsWifiDirectPeerReadySendResult.SendFailed(
                "Participant peer-ready send intentionally suppressed for regression test."
            )
        }

        try {
            harness.startBothManualRuns()

            assertTrue(
                harness.advanceUntil(maxSteps = 320) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                    ).status == AutomatedDiagnosticStepStatus.BLOCKED
                }
            )

            val coordinatorStep = harness.coordinatorStep(
                AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
            )
            assertEquals(0, harness.coordinatorEnvironment.connectToWifiDirectPeerCallCount)
            assertEquals("true", coordinatorStep.evidenceValue("Pre-existing group detected"))
            assertEquals("true", coordinatorStep.evidenceValue("Fresh baseline established"))
            assertEquals("false", coordinatorStep.evidenceValue("Current-run token proof ready"))
            assertEquals("PRE_EXISTING", coordinatorStep.evidenceValue("Group provenance"))
            assertTrue(
                coordinatorStep.blockerOrFailure?.contains("correlation token", ignoreCase = true) ==
                    true
            )
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun participantJoinSubmitRequiresPreparedLeaseWhenParticipantClockIsAhead() = runBlocking {
        val sender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)
        val staleAnnouncementRun = AutomatedDiagnosticsSharedRun(
            runId = "diag-run-skew",
            coordinatorPeerId = "peer-coordinator",
            participantPeerId = "peer-participant",
            sessionAssociationId = "chat-peer-participant",
            createdAtMillis = 1_000_000L,
            expiresAtMillis = 1_012_000L
        )
        val joinCreatedAtMillis = 1_045_000L
        val invalidResult = runSuspending {
            submitAutomatedDiagnosticsParticipantJoin(
                bleConnectionStatus = BleConnectionStatus.CONNECTED,
                activeTransportPeerId = "peer-coordinator",
                transportSender = sender,
                localPeerId = "peer-participant",
                sharedRun = staleAnnouncementRun,
                createdAtMillis = joinCreatedAtMillis
            )
        }

        assertTrue(invalidResult is AutomatedDiagnosticsParticipantJoinSendResult.InvalidJoin)
        val invalidReason =
            (invalidResult as AutomatedDiagnosticsParticipantJoinSendResult.InvalidJoin).reason
        assertTrue(invalidReason.contains("runId=diag-run-skew"))
        assertTrue(invalidReason.contains("joinCreatedAtMillis=1045000"))
        assertTrue(invalidReason.contains("sharedRunCreatedAtMillis=1000000"))
        assertTrue(invalidReason.contains("sharedRunExpiresAtMillis=1012000"))
        assertTrue(invalidReason.contains("sharedRunExpiryMinusJoinCreatedAtMillis=-33000"))
        assertEquals(0, sender.sendCallCount)

        val preparedRun = staleAnnouncementRun.copy(expiresAtMillis = 1_105_000L)
        val sentResult = runSuspending {
            submitAutomatedDiagnosticsParticipantJoin(
                bleConnectionStatus = BleConnectionStatus.CONNECTED,
                activeTransportPeerId = "peer-coordinator",
                transportSender = sender,
                localPeerId = "peer-participant",
                sharedRun = preparedRun,
                createdAtMillis = joinCreatedAtMillis
            )
        }

        assertEquals(
            AutomatedDiagnosticsParticipantJoinSendResult.Sent(preparedRun),
            sentResult
        )
        assertEquals(1, sender.sendCallCount)

        val sentPlan = requireNotNull(sender.capturedPlan)
        val sentFrame = decodePlaintextFrame(sentPlan)
        val controlMessage = requireNotNull(HybridTransportControlFrameFactory.parseOrNull(sentFrame))

        assertEquals(joinCreatedAtMillis, controlMessage.createdAtMillis)
        assertEquals(preparedRun.expiresAtMillis, controlMessage.expiresAtMillis)
        assertEquals(preparedRun.runId, controlMessage.sessionId)
        assertEquals(preparedRun.participantPeerId, controlMessage.publicPeerIdHint)
        assertEquals(preparedRun.coordinatorPeerId, controlMessage.relatedPeerIdHint)
        assertEquals(preparedRun.sessionAssociationId, controlMessage.associatedSessionId)
    }

    @Test
    fun repeatedEnablingAutomaticParticipationDoesNotClearAnnouncementsOrRestartListener() {
        val clock = FakeMonotonicClock()
        val delayEntered = CountDownLatch(1)
        val environment = FakePhaseOneEnvironment().apply {
            lastAutomatedDiagnosticsCoordinationStatus = "stale-shared-run"
            lastAutomatedDiagnosticsServerReadyStatus = "keep-server-ready"
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runner = AutomatedDiagnosticsRunner(
            bindings = environment.createBindings(clock = clock, scope = scope),
            clock = clock,
            delay = HangingDelay(delayEntered)
        )

        runner.setAutomaticParticipationEnabled(true)

        assertTrue(delayEntered.await(1, TimeUnit.SECONDS))
        val sharedRun = environment.sharedRunFor(
            coordinatorPeerId = environment.peerIdentityKey,
            participantPeerId = environment.localPeerId,
            createdAtMillis = 1_000L
        )
        environment.latestAutomatedDiagnosticsRunAnnouncement = AutomatedDiagnosticsRunAnnouncement(
            sharedRun = sharedRun,
            peerId = environment.peerIdentityKey,
            createdAtMillis = 1_000L
        )
        environment.latestAutomatedDiagnosticsParticipantJoin = AutomatedDiagnosticsParticipantJoin(
            sharedRun = sharedRun,
            peerId = environment.localPeerId,
            createdAtMillis = 1_100L
        )
        environment.latestAutomatedDiagnosticsServerReadySignal = AutomatedDiagnosticsServerReadySignal(
            sharedRun = sharedRun,
            peerId = environment.peerIdentityKey,
            expectedClientPeerId = environment.localPeerId,
            groupOwnerAddress = "192.168.49.1",
            socketPort = wifiDirectDebugSocketPort,
            serverToken = 5L,
            createdAtMillis = 1_200L,
            expiresAtMillis = 9_200L
        )

        repeat(9) {
            runner.setAutomaticParticipationEnabled(true)
        }

        val diagnostics = runner.listenerDiagnosticsForTest()
        assertEquals(10, diagnostics.enableCallCount)
        assertEquals(1, diagnostics.listenerGeneration)
        assertEquals(1, diagnostics.listenerStartCount)
        assertTrue(diagnostics.listenerActive)
        assertEquals(0, environment.clearSharedRunCoordinationStateCallCount)
        assertEquals(0, environment.clearAutomatedDiagnosticsCoordinationStateCallCount)
        assertTrue(environment.latestAutomatedDiagnosticsRunAnnouncement != null)
        assertTrue(environment.latestAutomatedDiagnosticsParticipantJoin != null)
        assertEquals("stale-shared-run", environment.lastAutomatedDiagnosticsCoordinationStatus)
        assertTrue(environment.latestAutomatedDiagnosticsServerReadySignal != null)
        assertEquals("keep-server-ready", environment.lastAutomatedDiagnosticsServerReadyStatus)

        runner.setAutomaticParticipationEnabled(false)
        assertFalse(runner.listenerDiagnosticsForTest().listenerActive)
        scope.cancel()
    }

    @Test
    fun delayedSelectedSecurePeerPropagationRetainsAnnouncementUntilParticipantJoin() {
        val coordinatorPeerId = "9a04c27f89ba5ac7"
        val participantPeerId = "edb0abb84737d8c0"
        val clock = FakeMonotonicClock()
        val wallClock = FakeWallClock(1_000_000L)
        val environment = FakePhaseOneEnvironment(
            initialDiscoveredPeers = listOf(sampleDiscoveredPeer(stableIdHex = coordinatorPeerId))
        ).apply {
            localPeerId = participantPeerId
            wallClockMillisProvider = wallClock::nowMillis
            secureSessionAssociationIdOverride = "chat-$participantPeerId"
            configureReadySecureSessionState()
            selectedSecurePeerId = null
            delayedSelectedSecurePeerSnapshotCount = 2
            val sharedRun = sharedRunFor(
                coordinatorPeerId = coordinatorPeerId,
                participantPeerId = participantPeerId,
                createdAtMillis = wallClock.nowMillis()
            )
            latestAutomatedDiagnosticsRunAnnouncement = AutomatedDiagnosticsRunAnnouncement(
                sharedRun = sharedRun,
                peerId = coordinatorPeerId,
                createdAtMillis = wallClock.nowMillis()
            )
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val delay = SuspendingAdvancingDelay(clock)
        val runner = AutomatedDiagnosticsRunner(
            bindings = environment.createBindings(clock = clock, scope = scope),
            clock = clock,
            delay = delay,
            wallClockMillis = wallClock::nowMillis
        )

        runner.setAutomaticParticipationEnabled(true)
        assertTrue(
            "Pending announcement was not retained while selected-peer propagation was still stale.",
            advanceUntil(maxSteps = 40, advance = {
                delay.advanceSteps(1)
            }) {
                val diagnostics = runner.listenerDiagnosticsForTest()
                diagnostics.pendingAnnouncementRunId != null &&
                    environment.selectedSecurePeerId == null
            }
        )
        assertTrue(
            "Participant Step 9 did not pass after delayed selected-peer propagation.",
            advanceUntil(maxSteps = 200, advance = {
                delay.advanceSteps(1)
            }) {
                runner.state.value.steps[
                    AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION.ordinal
                ].status == AutomatedDiagnosticStepStatus.PASS
            }
        )

        val state = runner.state.value
        val step = state.steps[AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION.ordinal]
        val diagnostics = runner.listenerDiagnosticsForTest()

        assertEquals(AutomatedDiagnosticStepStatus.PASS, step.status)
        assertEquals("AUTOMATIC_PARTICIPANT_JOIN", step.evidenceValue("Run start cause"))
        assertEquals("0", step.evidenceValue("Local shared run ids generated"))
        assertEquals("none", step.evidenceValue("Local provisional run id"))
        assertEquals("0", step.evidenceValue("RUN_ANNOUNCE send count"))
        assertEquals("1", step.evidenceValue("PARTICIPANT_JOIN attempt count"))
        assertEquals("1", step.evidenceValue("PARTICIPANT_JOIN successful send count"))
        assertEquals("true", step.evidenceValue("Participant join sent"))
        assertEquals(0, environment.automatedDiagnosticsRunAnnouncementRequestCount)
        assertEquals(1, environment.automatedDiagnosticsParticipantJoinRequestCount)
        assertEquals(coordinatorPeerId, environment.selectedSecurePeerId)
        assertEquals(0, diagnostics.manualStartInvocationCount)
        assertEquals(1, diagnostics.participantStartInvocationCount)
        assertEquals(0, diagnostics.runAnnouncementSendCount)
        assertEquals(1, diagnostics.participantJoinSendCount)
        assertEquals(1, diagnostics.participantJoinSuccessfulSendCount)
        assertEquals(null, diagnostics.pendingAnnouncementRunId)
        assertEquals("none", diagnostics.lastAutoJoinBlocker)

        runner.setAutomaticParticipationEnabled(false)
        scope.cancel()
    }

    @Test
    fun participantWaitsForFreshServerReadySignalBeforeConnectingSocketClient() = runBlocking {
        val participantPhonePeer = wifiDirectPeer(
            name = "Participant Pixel",
            address = "aa:bb:cc:dd:ee:20"
        )
        val coordinatorPhonePeer = wifiDirectPeer(
            name = "Coordinator Pixel",
            address = "aa:bb:cc:dd:ee:10"
        )
        val harness = createStepElevenHarness(
            coordinatorLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
                deviceName = "Coordinator Pixel"
            ),
            participantLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
                deviceName = "Participant Pixel"
            ),
            coordinatorWifiDirectPeers = listOf(participantPhonePeer),
            participantWifiDirectPeers = listOf(coordinatorPhonePeer),
            coordinatorObservedParticipantPeer = participantPhonePeer,
            participantObservedCoordinatorPeer = coordinatorPhonePeer,
            timingPolicy = stepElevenTimingPolicy()
        )
        var serverReadyAttemptCount = 0
        harness.coordinatorEnvironment.onServerReadyRequested = {
                sharedRun,
                expectedClientPeerId,
                groupOwnerAddress,
                socketPort,
                serverToken ->
            serverReadyAttemptCount += 1
            if (serverReadyAttemptCount < 3) {
                AutomatedDiagnosticsServerReadySendResult.SendFailed(
                    "Intentional delay for fresh SERVER_READY test."
                )
            } else {
                requireNotNull(harness.coordinatorEnvironment.coordinationTransport).sendServerReady(
                    from = harness.coordinatorEnvironment,
                    sharedRun = sharedRun,
                    expectedClientPeerId = expectedClientPeerId,
                    groupOwnerAddress = groupOwnerAddress,
                    socketPort = socketPort,
                    serverToken = serverToken,
                    createdAtMillis = harness.coordinatorEnvironment.currentWallClockMillis()
                )
            }
        }

        try {
            harness.startBothManualRuns()
            harness.advanceSteps(1)

            assertTrue(
                harness.advanceUntil(maxSteps = 720) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                        ).status == AutomatedDiagnosticStepStatus.PASS
                }
            )
            assertTrue(
                harness.advanceUntil(maxSteps = 320) {
                    serverReadyAttemptCount >= 2 &&
                        harness.participantEnvironment.connectWifiDirectSocketClientCallCount == 0
                }
            )

            val bothPassedSocketStep = harness.advanceUntil(maxSteps = 960) {
                harness.participantStep(
                    AutomatedDiagnosticStepId.WIFI_DIRECT_SOCKET
                ).status == AutomatedDiagnosticStepStatus.PASS &&
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_SOCKET
                    ).status == AutomatedDiagnosticStepStatus.PASS
            }
            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                bothPassedSocketStep
            )

            val participantStep = harness.participantStep(
                AutomatedDiagnosticStepId.WIFI_DIRECT_SOCKET
            )
            assertEquals(1, harness.participantEnvironment.connectWifiDirectSocketClientCallCount)
            assertEquals(0, harness.participantEnvironment.automatedDiagnosticsServerReadyRequestCount)
            assertTrue(serverReadyAttemptCount >= 3)
            assertEquals(
                AutomatedDiagnosticStepStatus.PASS,
                participantStep.status
            )
            assertTrue(
                (participantStep.evidenceValue("Server-ready accepted/count")?.toInt() ?: 0) >= 1
            )
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun stepTwelveRecordsValidatedGroupProvenanceAndSocketBindingEvidence() {
        val participantPhonePeer = wifiDirectPeer(
            name = "Participant Pixel",
            address = "aa:bb:cc:dd:ee:20"
        )
        val coordinatorPhonePeer = wifiDirectPeer(
            name = "Coordinator Pixel",
            address = "aa:bb:cc:dd:ee:10"
        )
        val harness = createStepElevenHarness(
            coordinatorLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
                deviceName = "Coordinator Pixel"
            ),
            participantLocalDeviceInfo = anonymizedWifiDirectLocalDeviceInfo(
                deviceName = "Participant Pixel"
            ),
            coordinatorWifiDirectPeers = listOf(participantPhonePeer),
            participantWifiDirectPeers = listOf(coordinatorPhonePeer),
            coordinatorObservedParticipantPeer = participantPhonePeer,
            participantObservedCoordinatorPeer = coordinatorPhonePeer,
            timingPolicy = stepElevenTimingPolicy()
        )

        try {
            harness.startBothManualRuns()
            harness.advanceSteps(1)

            assertTrue(
                harness.advanceUntil(maxSteps = 720) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                        ).status == AutomatedDiagnosticStepStatus.PASS
                }
            )
            val bothPassedSocketStep = harness.advanceUntil(maxSteps = 960) {
                harness.coordinatorStep(
                    AutomatedDiagnosticStepId.WIFI_DIRECT_SOCKET
                ).status == AutomatedDiagnosticStepStatus.PASS &&
                    harness.participantStep(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_SOCKET
                    ).status == AutomatedDiagnosticStepStatus.PASS
            }
            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                bothPassedSocketStep
            )

            val coordinatorStep = harness.coordinatorStep(
                AutomatedDiagnosticStepId.WIFI_DIRECT_SOCKET
            )
            val participantStep = harness.participantStep(
                AutomatedDiagnosticStepId.WIFI_DIRECT_SOCKET
            )
            assertEquals(1, harness.coordinatorEnvironment.startWifiDirectSocketServerCallCount)
            assertEquals(1, harness.participantEnvironment.connectWifiDirectSocketClientCallCount)
            assertEquals(1, harness.coordinatorEnvironment.automatedDiagnosticsServerReadyRequestCount)
            assertEquals(0, harness.participantEnvironment.automatedDiagnosticsServerReadyRequestCount)
            assertEquals("CURRENT_RUN_VALIDATED", coordinatorStep.evidenceValue("Step 11 group provenance"))
            assertEquals(
                harness.coordinatorEnvironment.wifiDirectSocketRuntimeInstanceId,
                coordinatorStep.evidenceValue("Socket diagnostics instance id")
            )
            assertEquals(
                harness.coordinatorEnvironment.wifiDirectSocketRuntimeInstanceId,
                coordinatorStep.evidenceValue("Socket command-binding instance id")
            )
            assertEquals("1", coordinatorStep.evidenceValue("Server start request count"))
            assertEquals("0", coordinatorStep.evidenceValue("Client connect request count"))
            assertEquals("CURRENT_RUN_VALIDATED", participantStep.evidenceValue("Step 11 group provenance"))
            assertEquals(
                harness.participantEnvironment.wifiDirectSocketRuntimeInstanceId,
                participantStep.evidenceValue("Socket diagnostics instance id")
            )
            assertEquals(
                harness.participantEnvironment.wifiDirectSocketRuntimeInstanceId,
                participantStep.evidenceValue("Socket command-binding instance id")
            )
            assertEquals("1", participantStep.evidenceValue("Client connect request count"))
        } finally {
            harness.cancel()
        }
    }

    private fun createStepElevenHarness(
        coordinatorLocalDeviceInfo: WifiDirectLocalDeviceInfo,
        participantLocalDeviceInfo: WifiDirectLocalDeviceInfo,
        coordinatorWifiDirectPeers: List<WifiDirectPeer>,
        participantWifiDirectPeers: List<WifiDirectPeer>,
        coordinatorObservedParticipantPeer: WifiDirectPeer? = null,
        participantObservedCoordinatorPeer: WifiDirectPeer? = null,
        coordinatorExtraDnsSdResponses: List<WifiDirectDnsSdServiceResponse> = emptyList(),
        participantExtraDnsSdResponses: List<WifiDirectDnsSdServiceResponse> = emptyList(),
        timingPolicy: AutomatedDiagnosticsTimingPolicy =
            AutomatedDiagnosticsTimingPolicy.default()
    ): StepElevenHarness {
        val coordinatorPeerId = "9a04c27f89ba5ac7"
        val participantPeerId = "edb0abb84737d8c0"
        val coordinatorWallClock = FakeWallClock(1_000_000L)
        val participantWallClock = FakeWallClock(1_045_000L)
        val coordinatorEnvironment = FakePhaseOneEnvironment(
            initialDiscoveredPeers = listOf(sampleDiscoveredPeer(stableIdHex = participantPeerId))
        ).apply {
            localPeerId = coordinatorPeerId
            wallClockMillisProvider = coordinatorWallClock::nowMillis
            configureCoordinatorPhaseTwoState()
            observedWifiDirectPeerByRemotePeerId[participantPeerId] =
                coordinatorObservedParticipantPeer
            extraAutomatedDiagnosticsDnsSdResponses = coordinatorExtraDnsSdResponses
            wifiDirectRuntimeStatus = wifiDirectRuntimeStatus.copy(
                localDeviceInfo = coordinatorLocalDeviceInfo,
                peers = coordinatorWifiDirectPeers
            )
        }
        val participantEnvironment = FakePhaseOneEnvironment(
            initialDiscoveredPeers = listOf(sampleDiscoveredPeer(stableIdHex = coordinatorPeerId))
        ).apply {
            localPeerId = participantPeerId
            wallClockMillisProvider = participantWallClock::nowMillis
            configureCoordinatorPhaseTwoState()
            autoPopulateDefaultRemoteRunAnnouncement = false
            secureSessionAssociationIdOverride = "chat-$participantPeerId"
            selectedSecurePeerId = null
            observedWifiDirectPeerByRemotePeerId[coordinatorPeerId] =
                participantObservedCoordinatorPeer
            extraAutomatedDiagnosticsDnsSdResponses = participantExtraDnsSdResponses
            wifiDirectRuntimeStatus = wifiDirectRuntimeStatus.copy(
                discoveryState = WifiDirectDiscoveryState.INACTIVE,
                connectionStatus = WifiDirectConnectionStatus(
                    state = WifiDirectConnectionState.DISCONNECTED,
                    targetPeer = null,
                    groupFormed = WifiDirectGroupFormedState.NO,
                    role = WifiDirectConnectionRole.UNKNOWN,
                    groupOwnerAddress = null
                ),
                localDeviceInfo = participantLocalDeviceInfo,
                peers = participantWifiDirectPeers
            )
        }
        val coordinationTransport = SharedAutomatedDiagnosticsCoordinationTransport(
            first = coordinatorEnvironment,
            second = participantEnvironment
        )
        coordinatorEnvironment.coordinationTransport = coordinationTransport
        participantEnvironment.coordinationTransport = coordinationTransport

        val coordinatorClock = FakeMonotonicClock()
        val participantClock = FakeMonotonicClock()
        val coordinatorDelay = SuspendingAdvancingDelay(coordinatorClock)
        val participantDelay = SuspendingAdvancingDelay(participantClock)
        val participantScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val participantRunner = AutomatedDiagnosticsRunner(
            bindings = participantEnvironment.createBindings(
                clock = participantClock,
                scope = participantScope
            ),
            clock = participantClock,
            delay = participantDelay,
            timingPolicy = timingPolicy,
            wallClockMillis = participantWallClock::nowMillis
        )
        val coordinatorRunner = AutomatedDiagnosticsRunner(
            bindings = coordinatorEnvironment.createBindings(
                clock = coordinatorClock,
                scope = coordinatorScope
            ),
            clock = coordinatorClock,
            delay = coordinatorDelay,
            timingPolicy = timingPolicy,
            wallClockMillis = coordinatorWallClock::nowMillis
        )
        return StepElevenHarness(
            coordinatorEnvironment = coordinatorEnvironment,
            participantEnvironment = participantEnvironment,
            coordinatorRunner = coordinatorRunner,
            participantRunner = participantRunner,
            coordinatorDelay = coordinatorDelay,
            participantDelay = participantDelay,
            coordinatorWallClock = coordinatorWallClock,
            participantWallClock = participantWallClock,
            stepAdvanceMillis = timingPolicy.pollIntervalMillis,
            coordinatorScope = coordinatorScope,
            participantScope = participantScope
        )
    }

    private fun stepElevenTimingPolicy(): AutomatedDiagnosticsTimingPolicy {
        return AutomatedDiagnosticsTimingPolicy.default().copy(
            wifiDirectDiscovery = AutomatedDiagnosticsTimingWindow(
                stabilizationMillis = 0L,
                timeoutMillis = 800L
            ),
            wifiDirectGroupFormation = AutomatedDiagnosticsTimingWindow(
                stabilizationMillis = 0L,
                timeoutMillis = 800L,
                maxRetries = 1
            ),
            pollIntervalMillis = 50L
        )
    }

    private fun assertStepsPassedThrough(
        state: AutomatedDiagnosticsRunState,
        terminalStepId: AutomatedDiagnosticStepId
    ) {
        AutomatedDiagnosticStepId.entries
            .take(terminalStepId.ordinal + 1)
            .forEach { stepId ->
                assertEquals(
                    "Expected ${stepId.name} to remain PASS.\n${state.reportText}",
                    AutomatedDiagnosticStepStatus.PASS,
                    state.steps[stepId.ordinal].status
                )
            }
    }

    private class StepElevenHarness(
        val coordinatorEnvironment: FakePhaseOneEnvironment,
        val participantEnvironment: FakePhaseOneEnvironment,
        val coordinatorRunner: AutomatedDiagnosticsRunner,
        val participantRunner: AutomatedDiagnosticsRunner,
        val coordinatorDelay: SuspendingAdvancingDelay,
        val participantDelay: SuspendingAdvancingDelay,
        private val coordinatorWallClock: FakeWallClock,
        private val participantWallClock: FakeWallClock,
        private val stepAdvanceMillis: Long,
        private val coordinatorScope: CoroutineScope,
        private val participantScope: CoroutineScope
    ) {
        fun startBothManualRuns() {
            participantRunner.setAutomaticParticipationEnabled(true)
            coordinatorRunner.setAutomaticParticipationEnabled(true)
            participantRunner.start()
            coordinatorRunner.start()
        }

        fun advanceSteps(
            stepCount: Int
        ) {
            repeat(stepCount) {
                participantWallClock.advanceMillis(stepAdvanceMillis)
                coordinatorWallClock.advanceMillis(stepAdvanceMillis)
                participantDelay.advanceSteps(1)
                coordinatorDelay.advanceSteps(1)
            }
        }

        fun advanceUntil(
            maxSteps: Int,
            condition: () -> Boolean
        ): Boolean {
            repeat(maxSteps) {
                if (condition()) {
                    return true
                }
                advanceSteps(1)
            }
            return condition()
        }

        fun coordinatorStep(
            stepId: AutomatedDiagnosticStepId
        ): AutomatedDiagnosticStepResult {
            return coordinatorRunner.state.value.steps[stepId.ordinal]
        }

        fun participantStep(
            stepId: AutomatedDiagnosticStepId
        ): AutomatedDiagnosticStepResult {
            return participantRunner.state.value.steps[stepId.ordinal]
        }

        fun cancel() {
            coordinatorScope.cancel()
            participantScope.cancel()
        }
    }

    private class FakeMonotonicClock(
        var nowMillisValue: Long = 0L
    ) : MonotonicClock {
        override fun nowMillis(): Long = nowMillisValue
    }

    private class FakeWallClock(
        var nowMillisValue: Long
    ) {
        fun nowMillis(): Long = nowMillisValue

        fun advanceMillis(
            millis: Long
        ) {
            nowMillisValue += millis
        }
    }

    private class AdvancingDelay(
        private val clock: FakeMonotonicClock
    ) : AutomatedDiagnosticsDelay {
        override suspend fun delayMillis(millis: Long) {
            clock.nowMillisValue += millis
        }
    }

    private class HangingDelay(
        private val enteredLatch: CountDownLatch
    ) : AutomatedDiagnosticsDelay {
        override suspend fun delayMillis(millis: Long) {
            enteredLatch.countDown()
            suspendCancellableCoroutine<Unit> { }
        }
    }

    private class SuspendingAdvancingDelay(
        private val clock: FakeMonotonicClock,
        private val onAdvanced: suspend (Long) -> Unit = {}
    ) : AutomatedDiagnosticsDelay {
        private val waiters = ArrayDeque<Continuation<Unit>>()

        override suspend fun delayMillis(millis: Long) {
            clock.nowMillisValue += millis
            onAdvanced(clock.nowMillisValue)
            suspendCancellableCoroutine<Unit> { continuation ->
                waiters.addLast(continuation)
                continuation.invokeOnCancellation {
                    waiters.remove(continuation)
                }
            }
        }

        fun advanceSteps(stepCount: Int) {
            repeat(stepCount) {
                if (waiters.isEmpty()) {
                    return
                }
                waiters.removeFirst().resume(Unit)
            }
        }
    }

    private class AdvancingDelayWithHook(
        private val clock: FakeMonotonicClock,
        private val onAdvanced: suspend (Long) -> Unit
    ) : AutomatedDiagnosticsDelay {
        override suspend fun delayMillis(millis: Long) {
            clock.nowMillisValue += millis
            onAdvanced(clock.nowMillisValue)
        }
    }

    private class FakePhaseOneEnvironment(
        initialDiscoveredPeers: List<BleDiscoveredDevice> = listOf(sampleDiscoveredPeer()),
        private val advertiseStatusProvider: (Long) -> BleAdvertiseStatus = {
            BleAdvertiseStatus.ADVERTISING
        },
        private val scanStatusOverride: ((Long) -> BleScanStatus)? = null,
        discoveredPeersProvider: (Long) -> List<BleDiscoveredDevice> = { initialDiscoveredPeers }
    ) {
        val samplePeer: BleDiscoveredDevice = initialDiscoveredPeers.firstOrNull()
            ?: sampleDiscoveredPeer()
        val peerIdentityKey: String = samplePeer.stablePeerId
            ?.toByteArray()
            ?.joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xFF)
            }
            ?: samplePeer.address
        val connectedPeerIds = mutableListOf<String>()
        val wifiDirectPeer = WifiDirectPeer(
            deviceName = "Aurora Wi-Fi peer",
            deviceAddress = "11:22:33:44:55:66"
        )

        var localPeerId: String = "local-peer-0001"
        var secureSessionAssociationIdOverride: String? = null
        var desiredAvailability: AuroraAvailabilityPreference = AuroraAvailabilityPreference.ONLINE
        var bluetoothPermissionStatus: BluetoothPermissionStatus = BluetoothPermissionStatus(
            requiredPermissions = emptySet(),
            missingPermissions = emptySet(),
            isBluetoothEnabled = true,
            isLocationEnabled = true
        )
        var bleConnectionStatus: BleConnectionStatus = BleConnectionStatus.IDLE
        var activeTransportDeviceAddress: String? = null
        var activeTransportPeerId: String? = null
        var selectedSecurePeerId: String? = null
        var contacts: List<AuroraContact> = emptyList()
        var privateChatIdentitiesByPeerId: Map<String, PrivateChatIdentity> = emptyMap()
        var peerSessionDiagnostics: PeerSessionRegistryDiagnostics = PeerSessionRegistryDiagnostics(
            establishedPeerIds = emptyList(),
            canonicalPeerIdByAlias = emptyMap()
        )
        var lastIdentityExchangeStatus: String? = null
        var wifiDirectRuntimeStatus: WifiDirectRuntimeStatus =
            participantWifiDirectRuntimeStatus()
        var wifiDirectSocketDiagnostics: WifiDirectSocketDiagnostics =
            readySocketDiagnostics(role = WifiDirectSocketRole.CLIENT)
        var wifiDirectAdapterDiagnostics: WifiDirectTransportAdapterDiagnostics =
            WifiDirectTransportAdapterDiagnostics(
                state = WifiDirectTransportAdapterState.READY
            )
        var wifiDirectSendBridgeDiagnostics: WifiDirectSendBridgeDiagnostics =
            WifiDirectSendBridgeDiagnostics(enabled = true)
        var wifiDirectReceiveBridgeDiagnostics: WifiDirectReceiveBridgeDiagnostics =
            WifiDirectReceiveBridgeDiagnostics(enabled = true)
        var wifiDirectGlobalDebugSendDiagnostics: WifiDirectGlobalDebugSendDiagnostics =
            WifiDirectGlobalDebugSendDiagnostics()
        var wifiDirectPrivateDebugSendDiagnostics: WifiDirectPrivateDebugSendDiagnostics =
            WifiDirectPrivateDebugSendDiagnostics()
        var hybridBootstrapDecision: HybridBootstrapDecision =
            selectedHybridDecision(socketReady = true)
        var hybridBootstrapDiagnostics: HybridBootstrapDiagnostics =
            selectedHybridDiagnostics(socketReady = true)
        var latestAutomatedDiagnosticsRunAnnouncement: AutomatedDiagnosticsRunAnnouncement? = null
        var latestAutomatedDiagnosticsParticipantJoin: AutomatedDiagnosticsParticipantJoin? = null
        var latestAutomatedDiagnosticsWifiDirectPeerReadySignal:
            AutomatedDiagnosticsWifiDirectPeerReadySignal? = null
        var latestAutomatedDiagnosticsServerReadySignal: AutomatedDiagnosticsServerReadySignal? = null
        var lastAutomatedDiagnosticsCoordinationStatus: String? = null
        var lastAutomatedDiagnosticsWifiDirectPeerReadyStatus: String? = null
        var lastAutomatedDiagnosticsServerReadyStatus: String? = null
        var hybridBootstrapManualTriggerSnapshot: HybridBootstrapManualTriggerSnapshot =
            readyHybridTriggerSnapshot()
        var hybridBootstrapManualAcceptAvailable: Boolean = true
        var hybridBootstrapManualAcceptBlockedReason: String? = null
        var lastHybridBootstrapManualAcceptStatus: String? = null
        var hybridBootstrapManualOfferAvailable: Boolean = true
        var hybridBootstrapManualOfferBlockedReason: String? = null
        var lastHybridBootstrapManualOfferStatus: String? = null
        var lastHybridBootstrapManualSocketHintStatus: String? = null
        var runtimeEvidence: AutomatedDiagnosticsRuntimeEvidence = AutomatedDiagnosticsRuntimeEvidence(
            activityLifecycleState = AutomatedDiagnosticsActivityLifecycleState.RESUMED,
            bleRuntimeHosted = true,
            lastCleanupReason = null,
            recentEvents = emptyList()
        )
        var autoPopulateDefaultRemoteRunAnnouncement: Boolean = true
        var discoveredPeersProvider: (Long) -> List<BleDiscoveredDevice> = discoveredPeersProvider
        var scanStatusProvider: (Long) -> BleScanStatus =
            scanStatusOverride ?: { BleScanStatus.SCANNING }
        var wallClockMillisProvider: () -> Long = System::currentTimeMillis
        var startWifiDirectDiscoveryCallCount: Int = 0
        var connectToWifiDirectPeerCallCount: Int = 0
        var disconnectWifiDirectPeerCallCount: Int = 0
        var startWifiDirectSocketServerCallCount: Int = 0
        var connectWifiDirectSocketClientCallCount: Int = 0
        var closeWifiDirectSocketCallCount: Int = 0
        var resetWifiDirectSocketDiagnosticsCallCount: Int = 0
        var setWifiDirectSendBridgeEnabledCallCount: Int = 0
        var setWifiDirectReceiveBridgeEnabledCallCount: Int = 0
        var hybridBootstrapManualTriggerRequestCount: Int = 0
        var hybridBootstrapManualOfferRequestCount: Int = 0
        var hybridBootstrapManualAcceptRequestCount: Int = 0
        var hybridBootstrapManualSocketHintRequestCount: Int = 0
        var automatedDiagnosticsRunAnnouncementRequestCount: Int = 0
        var automatedDiagnosticsParticipantJoinRequestCount: Int = 0
        var automatedDiagnosticsWifiDirectPeerReadyRequestCount: Int = 0
        var automatedDiagnosticsServerReadyRequestCount: Int = 0
        var registerAutomatedDiagnosticsWifiDirectServiceCallCount: Int = 0
        var startAutomatedDiagnosticsWifiDirectServiceDiscoveryCallCount: Int = 0
        var clearAutomatedDiagnosticsWifiDirectServiceDiscoveryCallCount: Int = 0
        var clearSharedRunCoordinationStateCallCount: Int = 0
        var clearAutomatedDiagnosticsCoordinationStateCallCount: Int = 0
        var delayedSelectedSecurePeerSnapshotCount: Int = 0
        private var pendingSelectedSecurePeerId: String? = null
        val wifiDirectSocketRuntimeInstanceId =
            "fake-wifi-direct-socket-runtime-${fakeWifiDirectSocketRuntimeCounter.incrementAndGet()}"
        private var wifiDirectSocketCommandSequenceCounter: Long = 0L
        private var wifiDirectSocketOperationTokenCounter: Long = 0L
        val connectedWifiDirectPeers = mutableListOf<WifiDirectPeer>()
        var lastConnectedWifiDirectPeer: WifiDirectPeer? = null
        val observedWifiDirectPeerByRemotePeerId = mutableMapOf<String, WifiDirectPeer?>()
        var extraAutomatedDiagnosticsDnsSdResponses: List<WifiDirectDnsSdServiceResponse> =
            emptyList()
        var duplicateRemoteAutomatedDiagnosticsDnsSdResponses: Boolean = false
        var registeredAutomatedDiagnosticsCorrelationToken: String? = null
        var registeredAutomatedDiagnosticsDeviceName: String? = null
        var autoCompleteWifiDirectGroupFormationOnConnect: Boolean = true
        val pendingWifiDirectConnectFailures = ArrayDeque<String>()
        var onRunAnnouncementRequested:
            ((AutomatedDiagnosticsSharedRun, Long) -> Unit)? = null
        var onWifiDirectPeerReadyRequested:
            (suspend (
                AutomatedDiagnosticsSharedRun,
                String,
                String,
                String?,
                Long
            ) -> AutomatedDiagnosticsWifiDirectPeerReadySendResult)? = null
        var onServerReadyRequested:
            (suspend (
                AutomatedDiagnosticsSharedRun,
                String,
                String,
                Int,
                Long
            ) -> AutomatedDiagnosticsServerReadySendResult)? = null
        var coordinationTransport: SharedAutomatedDiagnosticsCoordinationTransport? = null

        fun configureReadySecureSessionState() {
            bleConnectionStatus = BleConnectionStatus.CONNECTED
            activeTransportDeviceAddress = samplePeer.address
            activeTransportPeerId = peerIdentityKey
            selectedSecurePeerId = peerIdentityKey
            contacts = listOf(
                AuroraContact(
                    canonicalPeerId = peerIdentityKey,
                    displayName = samplePeer.name ?: "Aurora peer",
                    createdAtMillis = 1_000L,
                    lastSeenMillis = 2_000L,
                    hasSession = true
                )
            )
            privateChatIdentitiesByPeerId = mapOf(
                peerIdentityKey to PrivateChatIdentity(
                    canonicalPeerId = peerIdentityKey,
                    privateChatId = secureSessionAssociationId(),
                    localProposalId = "local-$peerIdentityKey",
                    remoteProposalId = "remote-$peerIdentityKey",
                    createdAtMillis = 1_000L,
                    lastUpdatedMillis = 2_000L
                )
            )
            peerSessionDiagnostics = PeerSessionRegistryDiagnostics(
                establishedPeerIds = listOf(peerIdentityKey),
                canonicalPeerIdByAlias = emptyMap()
            )
            lastIdentityExchangeStatus = "submitted"
        }

        fun clearAutomatedDiagnosticsCoordinationState() {
            clearAutomatedDiagnosticsCoordinationStateCallCount += 1
            clearSharedRunCoordinationState()
            clearAutomatedDiagnosticsWifiDirectServiceDiscoveryState()
            latestAutomatedDiagnosticsWifiDirectPeerReadySignal = null
            lastAutomatedDiagnosticsWifiDirectPeerReadyStatus = null
            latestAutomatedDiagnosticsServerReadySignal = null
            lastAutomatedDiagnosticsServerReadyStatus = null
        }

        fun clearSharedRunCoordinationState() {
            clearSharedRunCoordinationStateCallCount += 1
            latestAutomatedDiagnosticsRunAnnouncement = null
            latestAutomatedDiagnosticsParticipantJoin = null
            lastAutomatedDiagnosticsCoordinationStatus = null
        }

        fun registerAutomatedDiagnosticsWifiDirectService(
            correlationToken: String,
            deviceNameHint: String?
        ) {
            registerAutomatedDiagnosticsWifiDirectServiceCallCount += 1
            registeredAutomatedDiagnosticsCorrelationToken = correlationToken
            registeredAutomatedDiagnosticsDeviceName = deviceNameHint
            wifiDirectRuntimeStatus = wifiDirectRuntimeStatus.copy(
                dnsSdDiagnostics = wifiDirectRuntimeStatus.dnsSdDiagnostics.copy(
                    localServiceRegistered = true,
                    localServiceInstanceName =
                        automatedDiagnosticsWifiDirectDnsSdInstanceName,
                    serviceType = automatedDiagnosticsWifiDirectDnsSdServiceType,
                    lastError = null,
                    cleanupCompleted = false
                )
            )
        }

        fun startAutomatedDiagnosticsWifiDirectServiceDiscovery() {
            startAutomatedDiagnosticsWifiDirectServiceDiscoveryCallCount += 1
            val remoteServices =
                coordinationTransport?.discoveredAutomatedDiagnosticsDnsSdResponsesFor(this)
                    ?: defaultRemoteAutomatedDiagnosticsDnsSdResponseOrNull()?.let(::listOf)
                    ?: emptyList()
            val dedupeInput = if (duplicateRemoteAutomatedDiagnosticsDnsSdResponses) {
                remoteServices + remoteServices + extraAutomatedDiagnosticsDnsSdResponses
            } else {
                extraAutomatedDiagnosticsDnsSdResponses + remoteServices
            }
            val discoveredServices =
                dedupeInput
                    .associateBy(::dnsSdDiscoveryResponseKey)
                    .values
                    .toList()
            wifiDirectRuntimeStatus = wifiDirectRuntimeStatus.copy(
                dnsSdDiagnostics = wifiDirectRuntimeStatus.dnsSdDiagnostics.copy(
                    serviceRequestRegistered = true,
                    discoveryStarted = true,
                    serviceType = automatedDiagnosticsWifiDirectDnsSdServiceType,
                    discoveredServices = discoveredServices,
                    lastError = null,
                    cleanupCompleted = false
                )
            )
        }

        fun clearAutomatedDiagnosticsWifiDirectServiceDiscoveryState() {
            val diagnostics = wifiDirectRuntimeStatus.dnsSdDiagnostics
            val hadRegisteredState =
                registeredAutomatedDiagnosticsCorrelationToken != null ||
                    registeredAutomatedDiagnosticsDeviceName != null ||
                    diagnostics.localServiceRegistered ||
                    diagnostics.serviceRequestRegistered ||
                    diagnostics.discoveryStarted ||
                    diagnostics.discoveredServices.isNotEmpty() ||
                    diagnostics.lastError != null
            if (hadRegisteredState) {
                clearAutomatedDiagnosticsWifiDirectServiceDiscoveryCallCount += 1
            }
            registeredAutomatedDiagnosticsCorrelationToken = null
            registeredAutomatedDiagnosticsDeviceName = null
            wifiDirectRuntimeStatus = wifiDirectRuntimeStatus.copy(
                dnsSdDiagnostics = WifiDirectDnsSdDiagnostics(
                    cleanupCompleted = true
                )
            )
        }

        fun hasSecureSessionForPeer(
            peerId: String
        ): Boolean {
            val identity = privateChatIdentitiesByPeerId[peerId]
            return contacts.any { it.canonicalPeerId == peerId && it.hasSession } &&
                peerSessionDiagnostics.hasSessionForPeer(peerId) &&
                identity?.privateChatId.isNullOrBlank().not() &&
                identity?.isEstablished == true
        }

        fun finalizeSecureSessionForPeer(
            peerId: String,
            displayName: String,
            localProposalId: String,
            remoteProposalId: String
        ) {
            contacts = listOf(
                AuroraContact(
                    canonicalPeerId = peerId,
                    displayName = displayName,
                    createdAtMillis = 1_000L,
                    lastSeenMillis = 2_000L,
                    hasSession = true
                )
            )
            privateChatIdentitiesByPeerId = mapOf(
                peerId to PrivateChatIdentity(
                    canonicalPeerId = peerId,
                    privateChatId = secureSessionAssociationId(),
                    localProposalId = localProposalId,
                    remoteProposalId = remoteProposalId,
                    createdAtMillis = 1_000L,
                    lastUpdatedMillis = 2_000L
                )
            )
            peerSessionDiagnostics = PeerSessionRegistryDiagnostics(
                establishedPeerIds = listOf(peerId),
                canonicalPeerIdByAlias = emptyMap()
            )
            lastIdentityExchangeStatus = "submitted"
        }

        private fun snapshotSelectedSecurePeerId(): String? {
            val delayedPeerId = pendingSelectedSecurePeerId
            if (delayedPeerId != null) {
                if (delayedSelectedSecurePeerSnapshotCount > 0) {
                    delayedSelectedSecurePeerSnapshotCount -= 1
                } else {
                    selectedSecurePeerId = delayedPeerId
                    pendingSelectedSecurePeerId = null
                }
            }
            return selectedSecurePeerId
        }

        fun currentWallClockMillis(): Long {
            return wallClockMillisProvider()
        }

        private fun defaultRemoteAutomatedDiagnosticsDnsSdResponseOrNull():
            WifiDirectDnsSdServiceResponse? {
            if (coordinationTransport != null || localPeerId >= peerIdentityKey) {
                return null
            }
            val remoteSignal = defaultRemoteWifiDirectPeerReadySignal(currentWallClockMillis())
            return wifiDirectDnsSdServiceResponse(
                peer = wifiDirectPeer,
                token = remoteSignal.wifiDirectCorrelationToken,
                deviceName = remoteSignal.wifiDirectDeviceName,
                observedAtMillis = currentWallClockMillis()
            )
        }

        fun applyReceivedCoordinationResult(
            result: BleTransportReceiveResult
        ) {
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
            automatedDiagnosticsServerReadySignalAfterReceiveOrNull(result)?.let { signal ->
                latestAutomatedDiagnosticsServerReadySignal = signal
                lastAutomatedDiagnosticsServerReadyStatus =
                    automatedDiagnosticsServerReadyStatusText(signal)
            }
        }

        fun applyReceivedHybridBootstrapResult(
            result: BleTransportReceiveResult
        ) {
            val processingResult =
                (result as? BleTransportReceiveResult.Processed)?.processingResult
                    as? IncomingTransportFrameProcessingResult.HybridControlHandled
                    ?: return
            val message = processingResult.controlMessage
            if (
                message.messageType !=
                HybridTransportControlMessage.MessageType.WIFI_DIRECT_SOCKET_HINT
            ) {
                return
            }
            val existingCandidate = hybridBootstrapDecision.candidates.firstOrNull()
            val sessionId = message.sessionId
            val remotePeerId = processingResult.peerId.trim().ifEmpty {
                message.publicPeerIdHint?.trim().orEmpty()
            }.ifEmpty {
                existingCandidate?.peerId ?: peerIdentityKey
            }
            val candidate = HybridBootstrapCandidate(
                peerId = remotePeerId,
                sessionId = sessionId,
                bootstrapIdentifier = sessionId,
                publicPeerIdHint = message.publicPeerIdHint?.trim()?.takeIf { it.isNotEmpty() }
                    ?: remotePeerId,
                groupOwnerAddress = message.groupOwnerAddress,
                socketPort = message.socketPort,
                latestCreatedAtMillis = message.createdAtMillis,
                hasOffer = existingCandidate?.hasOffer ?: true,
                hasAccept = existingCandidate?.hasAccept ?: true,
                hasSocketHint = true,
                socketReady =
                    !message.groupOwnerAddress.isNullOrBlank() &&
                        message.socketPort in 1..65535 &&
                        sessionId.isNotBlank()
            )
            val decision = HybridBootstrapDecision.create(
                candidates = listOf(candidate),
                selection = if (candidate.socketReady) {
                    HybridBootstrapCandidateSelection.Selected(candidate)
                } else {
                    HybridBootstrapCandidateSelection.NoSocketReadyCandidates
                }
            )
            hybridBootstrapDecision = decision
            hybridBootstrapDiagnostics = HybridBootstrapDiagnosticsFormatter.format(decision)
            hybridBootstrapManualTriggerSnapshot =
                readyHybridTriggerSnapshotFor(candidate) ?: blockedHybridTriggerSnapshot()
        }

        fun configureCoordinatorPhaseTwoState() {
            wifiDirectRuntimeStatus = disconnectedWifiDirectRuntimeStatus(
                localDeviceInfo = wifiDirectRuntimeStatus.localDeviceInfo,
                peers = wifiDirectRuntimeStatus.peers
            )
            wifiDirectSocketDiagnostics = WifiDirectSocketDiagnostics(
                endpoint = WifiDirectSocketEndpoint(port = wifiDirectDebugSocketPort)
            )
            wifiDirectAdapterDiagnostics = WifiDirectTransportAdapterDiagnostics(
                state = WifiDirectTransportAdapterState.NOT_READY,
                notReadyReason = "Waiting for socket connection."
            )
            wifiDirectSendBridgeDiagnostics = WifiDirectSendBridgeDiagnostics(enabled = false)
            wifiDirectReceiveBridgeDiagnostics = WifiDirectReceiveBridgeDiagnostics(enabled = false)
            hybridBootstrapDecision = selectedHybridDecision(
                hasSocketHint = false,
                socketReady = false
            )
            hybridBootstrapDiagnostics = selectedHybridDiagnostics(
                hasSocketHint = false,
                socketReady = false
            )
            hybridBootstrapManualTriggerSnapshot = blockedHybridTriggerSnapshot()
        }

        private fun wifiDirectPermissionStatus(): WifiDirectPermissionStatus {
            return WifiDirectPermissionStatus(
                requiredPermissions = emptySet(),
                missingPermissions = emptySet(),
                isWifiDirectSupported = true,
                isWifiEnabled = true,
                isWifiP2pEnabled = true
            )
        }

        private fun defaultLocalWifiDirectDeviceInfo(): WifiDirectLocalDeviceInfo {
            return WifiDirectLocalDeviceInfo(
                deviceName = "Local Aurora Wi-Fi device",
                deviceAddress = "66:55:44:33:22:11"
            )
        }

        private fun participantWifiDirectRuntimeStatus(
            localDeviceInfo: WifiDirectLocalDeviceInfo = defaultLocalWifiDirectDeviceInfo(),
            peers: List<WifiDirectPeer> = listOf(wifiDirectPeer)
        ): WifiDirectRuntimeStatus {
            return WifiDirectRuntimeStatus(
                permissionStatus = wifiDirectPermissionStatus(),
                discoveryState = WifiDirectDiscoveryState.ACTIVE,
                connectionStatus = WifiDirectConnectionStatus(
                    state = WifiDirectConnectionState.CONNECTED,
                    targetPeer = wifiDirectPeer,
                    groupFormed = WifiDirectGroupFormedState.YES,
                    role = WifiDirectConnectionRole.CLIENT,
                    groupOwnerAddress = "192.168.49.1"
                ),
                localDeviceInfo = localDeviceInfo,
                dnsSdDiagnostics = WifiDirectDnsSdDiagnostics(cleanupCompleted = true),
                peers = peers
            )
        }

        private fun disconnectedWifiDirectRuntimeStatus(
            localDeviceInfo: WifiDirectLocalDeviceInfo = wifiDirectRuntimeStatus.localDeviceInfo,
            peers: List<WifiDirectPeer> = wifiDirectRuntimeStatus.peers
        ): WifiDirectRuntimeStatus {
            return WifiDirectRuntimeStatus(
                permissionStatus = wifiDirectPermissionStatus(),
                discoveryState = WifiDirectDiscoveryState.INACTIVE,
                connectionStatus = WifiDirectConnectionStatus(
                    state = WifiDirectConnectionState.DISCONNECTED,
                    targetPeer = null,
                    groupFormed = WifiDirectGroupFormedState.NO,
                    role = WifiDirectConnectionRole.UNKNOWN,
                    groupOwnerAddress = null
                ),
                localDeviceInfo = localDeviceInfo,
                dnsSdDiagnostics = WifiDirectDnsSdDiagnostics(cleanupCompleted = true),
                peers = peers
            )
        }

        fun beginWifiDirectConnection(
            peer: WifiDirectPeer
        ) {
            wifiDirectRuntimeStatus = wifiDirectRuntimeStatus.copy(
                discoveryState = WifiDirectDiscoveryState.ACTIVE,
                connectionStatus = WifiDirectConnectionStatus(
                    state = WifiDirectConnectionState.CONNECTING,
                    targetPeer = peer,
                    groupFormed = WifiDirectGroupFormedState.NO,
                    role = WifiDirectConnectionRole.UNKNOWN,
                    groupOwnerAddress = null
                )
            )
        }

        fun completeWifiDirectGroupAsGroupOwner(
            peer: WifiDirectPeer
        ) {
            wifiDirectRuntimeStatus = wifiDirectRuntimeStatus.copy(
                discoveryState = WifiDirectDiscoveryState.ACTIVE,
                connectionStatus = WifiDirectConnectionStatus(
                    state = WifiDirectConnectionState.CONNECTED,
                    targetPeer = peer,
                    groupFormed = WifiDirectGroupFormedState.YES,
                    role = WifiDirectConnectionRole.GROUP_OWNER,
                    groupOwnerAddress = "192.168.49.1"
                )
            )
        }

        fun completeWifiDirectGroupAsClient(
            peer: WifiDirectPeer
        ) {
            wifiDirectRuntimeStatus = wifiDirectRuntimeStatus.copy(
                discoveryState = WifiDirectDiscoveryState.ACTIVE,
                connectionStatus = WifiDirectConnectionStatus(
                    state = WifiDirectConnectionState.CONNECTED,
                    targetPeer = peer,
                    groupFormed = WifiDirectGroupFormedState.YES,
                    role = WifiDirectConnectionRole.CLIENT,
                    groupOwnerAddress = "192.168.49.1"
                )
            )
        }

        fun beginWifiDirectSocketServerListening(
            hostHint: String?
        ) {
            val host = hostHint?.trim()?.takeIf { it.isNotEmpty() }
                ?: wifiDirectRuntimeStatus.connectionStatus.groupOwnerAddress
                ?: "192.168.49.1"
            val now = currentWallClockMillis()
            wifiDirectSocketDiagnostics = WifiDirectSocketDiagnostics(
                state = WifiDirectSocketState.SERVER_LISTENING,
                role = WifiDirectSocketRole.SERVER,
                endpoint = WifiDirectSocketEndpoint(
                    host = host,
                    port = wifiDirectDebugSocketPort
                ),
                isConnected = false,
                isReadLoopActive = false,
                lastCommand = WifiDirectSocketCommand.START_SERVER,
                lastCommandResult = WifiDirectSocketCommandResult.LISTENING,
                lastCommandSequence = nextWifiDirectSocketCommandSequence(),
                lastOperationToken = nextWifiDirectSocketOperationToken(),
                lastCommandAtMillis = now,
                lastStateChangedAtMillis = now,
                lastStateTransition = "starting_server->server_listening",
                lastCommandHost = host,
                serverStartAttempts = wifiDirectSocketDiagnostics.serverStartAttempts + 1,
                clientConnectAttempts = wifiDirectSocketDiagnostics.clientConnectAttempts,
                closeAttempts = wifiDirectSocketDiagnostics.closeAttempts
            )
            wifiDirectAdapterDiagnostics = WifiDirectTransportAdapterDiagnostics(
                state = WifiDirectTransportAdapterState.NOT_READY,
                notReadyReason = "Waiting for socket connection."
            )
        }

        fun completeWifiDirectSocketAsServerConnected(
            host: String
        ) {
            val current = wifiDirectSocketDiagnostics
            val now = currentWallClockMillis()
            val normalizedHost = host.trim().ifEmpty { "192.168.49.1" }
            wifiDirectSocketDiagnostics = WifiDirectSocketDiagnostics(
                state = WifiDirectSocketState.CONNECTED,
                role = WifiDirectSocketRole.SERVER,
                endpoint = WifiDirectSocketEndpoint(
                    host = normalizedHost,
                    port = wifiDirectDebugSocketPort
                ),
                isConnected = true,
                isReadLoopActive = true,
                lastCommand = WifiDirectSocketCommand.START_SERVER,
                lastCommandResult = WifiDirectSocketCommandResult.CONNECTED,
                lastCommandSequence = current.lastCommandSequence.takeIf { it > 0L }
                    ?: nextWifiDirectSocketCommandSequence(),
                lastOperationToken = current.lastOperationToken.takeIf { it > 0L }
                    ?: nextWifiDirectSocketOperationToken(),
                lastCommandAtMillis = current.lastCommandAtMillis ?: now,
                lastStateChangedAtMillis = now,
                lastStateTransition = "server_listening->connected",
                lastCommandHost = normalizedHost,
                serverStartAttempts = maxOf(1, current.serverStartAttempts),
                clientConnectAttempts = current.clientConnectAttempts,
                closeAttempts = current.closeAttempts
            )
            wifiDirectAdapterDiagnostics = WifiDirectTransportAdapterDiagnostics(
                state = WifiDirectTransportAdapterState.READY
            )
        }

        fun completeWifiDirectSocketAsClientConnected(
            host: String
        ) {
            val current = wifiDirectSocketDiagnostics
            val now = currentWallClockMillis()
            val normalizedHost = host.trim().ifEmpty { "192.168.49.1" }
            wifiDirectSocketDiagnostics = WifiDirectSocketDiagnostics(
                state = WifiDirectSocketState.CONNECTED,
                role = WifiDirectSocketRole.CLIENT,
                endpoint = WifiDirectSocketEndpoint(
                    host = normalizedHost,
                    port = wifiDirectDebugSocketPort
                ),
                isConnected = true,
                isReadLoopActive = true,
                lastCommand = WifiDirectSocketCommand.CONNECT_CLIENT,
                lastCommandResult = WifiDirectSocketCommandResult.CONNECTED,
                lastCommandSequence = nextWifiDirectSocketCommandSequence(),
                lastOperationToken = nextWifiDirectSocketOperationToken(),
                lastCommandAtMillis = now,
                lastStateChangedAtMillis = now,
                lastStateTransition = "connecting->connected",
                lastCommandHost = normalizedHost,
                serverStartAttempts = current.serverStartAttempts,
                clientConnectAttempts = current.clientConnectAttempts + 1,
                closeAttempts = current.closeAttempts
            )
            wifiDirectAdapterDiagnostics = WifiDirectTransportAdapterDiagnostics(
                state = WifiDirectTransportAdapterState.READY
            )
        }

        private fun readySocketDiagnostics(
            role: WifiDirectSocketRole
        ): WifiDirectSocketDiagnostics {
            return WifiDirectSocketDiagnostics(
                state = WifiDirectSocketState.CONNECTED,
                role = role,
                endpoint = WifiDirectSocketEndpoint(
                    host = "192.168.49.1",
                    port = wifiDirectDebugSocketPort
                ),
                isConnected = true,
                isReadLoopActive = true,
                lastCommand = if (role == WifiDirectSocketRole.SERVER) {
                    WifiDirectSocketCommand.START_SERVER
                } else {
                    WifiDirectSocketCommand.CONNECT_CLIENT
                },
                lastCommandResult = WifiDirectSocketCommandResult.CONNECTED
            )
        }

        private fun nextWifiDirectSocketCommandSequence(): Long {
            wifiDirectSocketCommandSequenceCounter += 1L
            return wifiDirectSocketCommandSequenceCounter
        }

        private fun nextWifiDirectSocketOperationToken(): Long {
            wifiDirectSocketOperationTokenCounter += 1L
            return wifiDirectSocketOperationTokenCounter
        }

        private fun hybridSessionId(): String = "session-$peerIdentityKey"

        private fun secureSessionAssociationId(): String {
            return secureSessionAssociationIdOverride ?: "chat-$peerIdentityKey"
        }

        fun sharedRunFor(
            coordinatorPeerId: String,
            participantPeerId: String,
            createdAtMillis: Long
        ): AutomatedDiagnosticsSharedRun {
            return AutomatedDiagnosticsSharedRun(
                runId = "diag-$coordinatorPeerId-$participantPeerId",
                coordinatorPeerId = coordinatorPeerId,
                participantPeerId = participantPeerId,
                sessionAssociationId = secureSessionAssociationId(),
                createdAtMillis = createdAtMillis,
                expiresAtMillis = createdAtMillis + 60_000L
            )
        }

        private fun defaultRemoteRunAnnouncement(
            createdAtMillis: Long
        ): AutomatedDiagnosticsRunAnnouncement {
            val sharedRun = sharedRunFor(
                coordinatorPeerId = peerIdentityKey,
                participantPeerId = localPeerId,
                createdAtMillis = createdAtMillis
            )
            return AutomatedDiagnosticsRunAnnouncement(
                sharedRun = sharedRun,
                peerId = peerIdentityKey,
                createdAtMillis = createdAtMillis
            )
        }

        private fun syntheticAuthoritativeSharedRun(
            createdAtMillis: Long
        ): AutomatedDiagnosticsSharedRun {
            return latestAutomatedDiagnosticsParticipantJoin?.sharedRun
                ?: sharedRunFor(
                    coordinatorPeerId = localPeerId,
                    participantPeerId = peerIdentityKey,
                    createdAtMillis = createdAtMillis
                )
        }

        private fun defaultRemoteWifiDirectPeerReadySignal(
            createdAtMillis: Long
        ): AutomatedDiagnosticsWifiDirectPeerReadySignal {
            return AutomatedDiagnosticsWifiDirectPeerReadySignal(
                sharedRun = syntheticAuthoritativeSharedRun(createdAtMillis),
                peerId = peerIdentityKey,
                expectedRemotePeerId = localPeerId,
                wifiDirectCorrelationToken = "a1b2c3d4e5f60718293a4b5c6d7e8f90",
                wifiDirectDeviceName = wifiDirectPeer.deviceName,
                createdAtMillis = createdAtMillis,
                expiresAtMillis = createdAtMillis + 8_000L
            )
        }

        private fun hybridCandidate(
            hasSocketHint: Boolean,
            socketReady: Boolean
        ): HybridBootstrapCandidate {
            return HybridBootstrapCandidate(
                peerId = peerIdentityKey,
                sessionId = hybridSessionId(),
                bootstrapIdentifier = hybridSessionId(),
                publicPeerIdHint = peerIdentityKey,
                groupOwnerAddress = if (hasSocketHint) "192.168.49.1" else null,
                socketPort = if (hasSocketHint) wifiDirectDebugSocketPort else null,
                latestCreatedAtMillis = 1_000L,
                hasOffer = true,
                hasAccept = true,
                hasSocketHint = hasSocketHint,
                socketReady = socketReady
            )
        }

        private fun selectedHybridDecision(
            hasSocketHint: Boolean = true,
            socketReady: Boolean = true
        ): HybridBootstrapDecision {
            val candidate = hybridCandidate(
                hasSocketHint = hasSocketHint,
                socketReady = socketReady
            )
            val selection = if (socketReady) {
                HybridBootstrapCandidateSelection.Selected(candidate)
            } else {
                HybridBootstrapCandidateSelection.NoSocketReadyCandidates
            }
            return HybridBootstrapDecision.create(
                candidates = listOf(candidate),
                selection = selection
            )
        }

        private fun selectedHybridDiagnostics(
            hasSocketHint: Boolean = true,
            socketReady: Boolean = true
        ): HybridBootstrapDiagnostics {
            val candidate = hybridCandidate(
                hasSocketHint = hasSocketHint,
                socketReady = socketReady
            )
            return HybridBootstrapDiagnostics(
                candidateCount = 1,
                socketReadyCandidateCount = if (socketReady) 1 else 0,
                selectionStatus = if (socketReady) {
                    HybridBootstrapDiagnostics.SelectionStatus.Selected
                } else {
                    HybridBootstrapDiagnostics.SelectionStatus.NoSocketReadyCandidates
                },
                selectedPeerId = if (socketReady) candidate.peerId else null,
                selectedSessionId = if (socketReady) candidate.sessionId else null,
                selectedGroupOwnerAddress = if (socketReady) candidate.groupOwnerAddress else null,
                selectedSocketPort = if (socketReady) candidate.socketPort else null,
                selectedLatestCreatedAtMillis = if (socketReady) {
                    candidate.latestCreatedAtMillis
                } else {
                    null
                },
                statusText = if (socketReady) {
                    "Hybrid bootstrap candidate ready: peer=${candidate.peerId} session=${candidate.sessionId} address=${candidate.groupOwnerAddress} port=${candidate.socketPort}"
                } else {
                    "Hybrid bootstrap candidates available, none socket-ready"
                }
            )
        }

        private fun readyHybridTriggerSnapshot(): HybridBootstrapManualTriggerSnapshot {
            return HybridBootstrapManualTriggerSnapshot(
                commandBuildResult = HybridBootstrapAttemptCommandBuildResult.Built(
                    HybridBootstrapAttemptCommand(
                        peerId = peerIdentityKey,
                        sessionId = hybridSessionId(),
                        bootstrapIdentifier = hybridSessionId(),
                        groupOwnerAddress = "192.168.49.1",
                        socketPort = wifiDirectDebugSocketPort,
                        latestCreatedAtMillis = 1_000L,
                        requestedAtMillis = 1_001L,
                        commandCreatedAtMillis = 1_002L
                    )
                ),
                latestTriggerResult = null,
                canTriggerNow = true,
                commandStatusText = "Socket-ready candidate selected.",
                triggerStatusText = null
            )
        }

        private fun readyHybridTriggerSnapshotFor(
            candidate: HybridBootstrapCandidate
        ): HybridBootstrapManualTriggerSnapshot? {
            val groupOwnerAddress = candidate.groupOwnerAddress ?: return null
            val socketPort = candidate.socketPort ?: return null
            if (!candidate.socketReady) {
                return null
            }
            return HybridBootstrapManualTriggerSnapshot(
                commandBuildResult = HybridBootstrapAttemptCommandBuildResult.Built(
                    HybridBootstrapAttemptCommand(
                        peerId = candidate.peerId,
                        sessionId = candidate.sessionId,
                        bootstrapIdentifier = candidate.bootstrapIdentifier,
                        groupOwnerAddress = groupOwnerAddress,
                        socketPort = socketPort,
                        latestCreatedAtMillis = candidate.latestCreatedAtMillis,
                        requestedAtMillis = candidate.latestCreatedAtMillis,
                        commandCreatedAtMillis = candidate.latestCreatedAtMillis
                    )
                ),
                latestTriggerResult = null,
                canTriggerNow = true,
                commandStatusText = "Socket-ready candidate selected.",
                triggerStatusText = null
            )
        }

        private fun blockedHybridTriggerSnapshot(): HybridBootstrapManualTriggerSnapshot {
            return HybridBootstrapManualTriggerSnapshot(
                commandBuildResult = HybridBootstrapAttemptCommandBuildResult.NoSocketReadyCandidate,
                latestTriggerResult = null,
                canTriggerNow = false,
                commandStatusText = "No socket-ready hybrid candidate.",
                triggerStatusText = "No socket-ready candidate."
            )
        }

        fun createBindings(
            clock: FakeMonotonicClock,
            scope: CoroutineScope
        ): AutomatedDiagnosticsRunnerBindings {
            return AutomatedDiagnosticsRunnerBindings(
                snapshot = {
                    val currentWallClockMillis = currentWallClockMillis()
                    val snapshotRunAnnouncement =
                        latestAutomatedDiagnosticsRunAnnouncement ?: if (
                            autoPopulateDefaultRemoteRunAnnouncement &&
                            localPeerId > peerIdentityKey
                        ) {
                            defaultRemoteRunAnnouncement(currentWallClockMillis)
                        } else {
                            null
                        }
                    val snapshotWifiDirectPeerReadySignal =
                        latestAutomatedDiagnosticsWifiDirectPeerReadySignal ?: if (
                            coordinationTransport == null &&
                            localPeerId < peerIdentityKey
                        ) {
                            defaultRemoteWifiDirectPeerReadySignal(currentWallClockMillis)
                        } else {
                            null
                        }
                    AutomatedDiagnosticsRuntimeSnapshot(
                        desiredAvailability = desiredAvailability,
                        bluetoothPermissionStatus = bluetoothPermissionStatus,
                        bleAdvertiseStatus = advertiseStatusProvider(clock.nowMillis()),
                        bleScanStatus = scanStatusProvider(clock.nowMillis()),
                        bleConnectionStatus = bleConnectionStatus,
                        activeTransportPeerId = activeTransportPeerId,
                        selectedSecurePeerId = snapshotSelectedSecurePeerId(),
                        localPeerId = localPeerId,
                        discoveredAuroraPeers = discoveredPeersProvider(clock.nowMillis()),
                        peerSessionDiagnostics = peerSessionDiagnostics,
                        contacts = contacts,
                        privateChatIdentitiesByPeerId = privateChatIdentitiesByPeerId,
                        identityHandlerStatus = "ready",
                        lastIdentityExchangeStatus = lastIdentityExchangeStatus,
                        wifiDirectRuntimeStatus = wifiDirectRuntimeStatus,
                        wifiDirectSocketRuntimeInstanceId = wifiDirectSocketRuntimeInstanceId,
                        wifiDirectSocketCommandBindingInstanceId = wifiDirectSocketRuntimeInstanceId,
                        wifiDirectSocketDiagnostics = wifiDirectSocketDiagnostics,
                        wifiDirectAdapterDiagnostics = wifiDirectAdapterDiagnostics,
                        wifiDirectSendBridgeDiagnostics = wifiDirectSendBridgeDiagnostics,
                        wifiDirectReceiveBridgeDiagnostics = wifiDirectReceiveBridgeDiagnostics,
                        wifiDirectGlobalDebugSendDiagnostics = wifiDirectGlobalDebugSendDiagnostics,
                        wifiDirectPrivateDebugSendDiagnostics = wifiDirectPrivateDebugSendDiagnostics,
                        hybridBootstrapDecision = hybridBootstrapDecision,
                        hybridBootstrapDiagnostics = hybridBootstrapDiagnostics,
                        latestAutomatedDiagnosticsRunAnnouncement = snapshotRunAnnouncement,
                        latestAutomatedDiagnosticsParticipantJoin =
                            latestAutomatedDiagnosticsParticipantJoin,
                        latestAutomatedDiagnosticsWifiDirectPeerReadySignal =
                            snapshotWifiDirectPeerReadySignal,
                        latestAutomatedDiagnosticsServerReadySignal =
                            latestAutomatedDiagnosticsServerReadySignal,
                        lastAutomatedDiagnosticsCoordinationStatus =
                            lastAutomatedDiagnosticsCoordinationStatus,
                        lastAutomatedDiagnosticsWifiDirectPeerReadyStatus =
                            lastAutomatedDiagnosticsWifiDirectPeerReadyStatus
                                ?: snapshotWifiDirectPeerReadySignal?.let(
                                    ::automatedDiagnosticsWifiDirectPeerReadyStatusText
                                ),
                        lastAutomatedDiagnosticsServerReadyStatus =
                            lastAutomatedDiagnosticsServerReadyStatus,
                        hybridBootstrapManualTriggerSnapshot = hybridBootstrapManualTriggerSnapshot,
                        hybridBootstrapManualAcceptAvailable = hybridBootstrapManualAcceptAvailable,
                        hybridBootstrapManualAcceptBlockedReason =
                            hybridBootstrapManualAcceptBlockedReason,
                        lastHybridBootstrapManualAcceptStatus =
                            lastHybridBootstrapManualAcceptStatus,
                        hybridBootstrapManualOfferAvailable = hybridBootstrapManualOfferAvailable,
                        hybridBootstrapManualOfferBlockedReason =
                            hybridBootstrapManualOfferBlockedReason,
                        lastHybridBootstrapManualOfferStatus =
                            lastHybridBootstrapManualOfferStatus,
                        lastHybridBootstrapManualSocketHintStatus =
                            lastHybridBootstrapManualSocketHintStatus,
                        hybridBootstrapJavaNetRuntimeEnabled = true,
                        runtimeEvidence = runtimeEvidence
                    )
                },
                commands = AutomatedDiagnosticsRunnerCommands(
                    updateDesiredAvailability = { preference ->
                        desiredAvailability = preference
                    },
                    selectSecurePeer = { peerId ->
                        if (delayedSelectedSecurePeerSnapshotCount > 0) {
                            pendingSelectedSecurePeerId = peerId
                        } else {
                            selectedSecurePeerId = peerId
                            pendingSelectedSecurePeerId = null
                        }
                    },
                    connectToTransportPeer = { deviceAddress, peerId ->
                        bleConnectionStatus = BleConnectionStatus.CONNECTED
                        activeTransportDeviceAddress = deviceAddress
                        activeTransportPeerId = peerId
                        peerId?.let(connectedPeerIds::add)
                        coordinationTransport?.mirrorConnectedTransportPeer(
                            from = this,
                            deviceAddress = deviceAddress,
                            peerId = peerId
                        )
                    },
                    addOrUpdateContact = { peerId, displayName, lastSeenMillis, hasSession ->
                        val contact = AuroraContact(
                            canonicalPeerId = peerId,
                            displayName = displayName,
                            createdAtMillis = 1_000L,
                            lastSeenMillis = lastSeenMillis,
                            hasSession = hasSession
                        )
                        contacts = listOf(contact)
                        val proposalId = "local-$peerId"
                        privateChatIdentitiesByPeerId = mapOf(
                            peerId to PrivateChatIdentity(
                                canonicalPeerId = peerId,
                                localProposalId = proposalId,
                                createdAtMillis = 1_000L,
                                lastUpdatedMillis = 2_000L
                            )
                        )
                        proposalId
                    },
                    exchangeIdentityWithPeer = { device, proposalId ->
                        val peerId = device.stablePeerId
                            ?.toByteArray()
                            ?.joinToString(separator = "") { byte ->
                                "%02x".format(byte.toInt() and 0xFF)
                            }
                            ?: device.address
                        coordinationTransport?.exchangeIdentity(
                            from = this,
                            device = device,
                            proposalId = proposalId
                        ) ?: run {
                            finalizeSecureSessionForPeer(
                                peerId = peerId,
                                displayName = device.name ?: "Aurora device",
                                localProposalId = proposalId ?: "local-$peerId",
                                remoteProposalId = "remote-$peerId"
                            )
                            PeerIdentityExchangeSendResult.SubmittedLocally
                        }
                    },
                    startWifiDirectDiscovery = {
                        startWifiDirectDiscoveryCallCount += 1
                        wifiDirectRuntimeStatus = wifiDirectRuntimeStatus.copy(
                            discoveryState = WifiDirectDiscoveryState.ACTIVE
                        )
                    },
                    stopWifiDirectDiscovery = {
                        wifiDirectRuntimeStatus = wifiDirectRuntimeStatus.copy(
                            discoveryState = WifiDirectDiscoveryState.INACTIVE
                        )
                    },
                    connectToWifiDirectPeer = { peer, _ ->
                        connectToWifiDirectPeerCallCount += 1
                        lastConnectedWifiDirectPeer = peer
                        connectedWifiDirectPeers += peer
                        if (pendingWifiDirectConnectFailures.isNotEmpty()) {
                            val failureReason = pendingWifiDirectConnectFailures.removeFirst()
                            wifiDirectRuntimeStatus = wifiDirectRuntimeStatus.copy(
                                discoveryState = WifiDirectDiscoveryState.ACTIVE,
                                connectionStatus = WifiDirectConnectionStatus(
                                    state = WifiDirectConnectionState.FAILED,
                                    targetPeer = peer,
                                    groupFormed = WifiDirectGroupFormedState.UNKNOWN,
                                    role = WifiDirectConnectionRole.UNKNOWN,
                                    groupOwnerAddress = null,
                                    lastError = failureReason
                                )
                            )
                        } else {
                            val transport = coordinationTransport
                            if (transport != null) {
                                beginWifiDirectConnection(peer)
                                if (autoCompleteWifiDirectGroupFormationOnConnect) {
                                    transport.completeWifiDirectGroupFormation(
                                        from = this,
                                        selectedPeer = peer
                                    )
                                }
                            } else {
                                completeWifiDirectGroupAsGroupOwner(peer)
                            }
                        }
                    },
                    disconnectWifiDirectPeer = {
                        disconnectWifiDirectPeerCallCount += 1
                        wifiDirectRuntimeStatus = disconnectedWifiDirectRuntimeStatus(
                            localDeviceInfo = wifiDirectRuntimeStatus.localDeviceInfo,
                            peers = wifiDirectRuntimeStatus.peers
                        )
                        clearAutomatedDiagnosticsWifiDirectServiceDiscoveryState()
                    },
                    wifiDirectSocketCommandBindingInstanceId = {
                        wifiDirectSocketRuntimeInstanceId
                    },
                    startWifiDirectSocketServer = { hostHint ->
                        startWifiDirectSocketServerCallCount += 1
                        val normalizedHost = hostHint?.trim()?.takeIf { it.isNotEmpty() }
                            ?: wifiDirectRuntimeStatus.connectionStatus.groupOwnerAddress
                            ?: "192.168.49.1"
                        if (coordinationTransport != null) {
                            beginWifiDirectSocketServerListening(normalizedHost)
                        } else {
                            completeWifiDirectSocketAsServerConnected(normalizedHost)
                        }
                    },
                    connectWifiDirectSocketClient = { host ->
                        connectWifiDirectSocketClientCallCount += 1
                        coordinationTransport?.completeWifiDirectSocketConnection(
                            from = this,
                            host = host
                        ) ?: completeWifiDirectSocketAsClientConnected(host)
                    },
                    closeWifiDirectSocket = {
                        closeWifiDirectSocketCallCount += 1
                        wifiDirectSocketDiagnostics = WifiDirectSocketDiagnostics(
                            endpoint = WifiDirectSocketEndpoint(port = wifiDirectDebugSocketPort)
                        )
                        wifiDirectAdapterDiagnostics = WifiDirectTransportAdapterDiagnostics(
                            state = WifiDirectTransportAdapterState.NOT_READY,
                            notReadyReason = "Socket closed."
                        )
                    },
                    resetWifiDirectSocketDiagnostics = {
                        resetWifiDirectSocketDiagnosticsCallCount += 1
                        wifiDirectSocketDiagnostics = wifiDirectSocketDiagnostics.copy(
                            lastError = null,
                            lastCommandError = null
                        )
                    },
                    setWifiDirectSendBridgeEnabled = { enabled ->
                        setWifiDirectSendBridgeEnabledCallCount += 1
                        wifiDirectSendBridgeDiagnostics = WifiDirectSendBridgeDiagnostics(
                            enabled = enabled
                        )
                    },
                    setWifiDirectReceiveBridgeEnabled = { enabled ->
                        setWifiDirectReceiveBridgeEnabledCallCount += 1
                        wifiDirectReceiveBridgeDiagnostics = WifiDirectReceiveBridgeDiagnostics(
                            enabled = enabled
                        )
                    },
                    reportWifiDirectReceiveBridgeBlocked = { reason ->
                        wifiDirectReceiveBridgeDiagnostics = WifiDirectReceiveBridgeDiagnostics(
                            enabled = false,
                            lastToggleResult = "blocked",
                            lastToggleBlockedReason = reason
                        )
                    },
                setWifiDirectGlobalDebugSendEnabled = { enabled ->
                    wifiDirectGlobalDebugSendDiagnostics =
                        WifiDirectGlobalDebugSendDiagnostics(enabled = enabled)
                },
                setWifiDirectPrivateDebugSendEnabled = { enabled ->
                    wifiDirectPrivateDebugSendDiagnostics =
                        WifiDirectPrivateDebugSendDiagnostics(enabled = enabled)
                },
                registerAutomatedDiagnosticsWifiDirectService = {
                        correlationToken,
                        deviceNameHint ->
                    registerAutomatedDiagnosticsWifiDirectService(
                        correlationToken = correlationToken,
                        deviceNameHint = deviceNameHint
                    )
                },
                startAutomatedDiagnosticsWifiDirectServiceDiscovery = {
                    startAutomatedDiagnosticsWifiDirectServiceDiscovery()
                },
                clearAutomatedDiagnosticsWifiDirectServiceDiscovery = {
                    clearAutomatedDiagnosticsWifiDirectServiceDiscoveryState()
                },
                clearAutomatedDiagnosticsSharedRunCoordinationState = {
                    clearSharedRunCoordinationState()
                },
                clearAutomatedDiagnosticsCoordinationState = {
                    clearAutomatedDiagnosticsCoordinationState()
                },
                    requestAutomatedDiagnosticsRunAnnouncement = { sharedRun ->
                        automatedDiagnosticsRunAnnouncementRequestCount += 1
                        val transport = coordinationTransport
                        if (transport != null) {
                            transport.sendRunAnnouncement(from = this, sharedRun = sharedRun)
                        } else {
                            val createdAtMillis = currentWallClockMillis()
                            latestAutomatedDiagnosticsParticipantJoin =
                                AutomatedDiagnosticsParticipantJoin(
                                    sharedRun = sharedRun,
                                    peerId = sharedRun.participantPeerId,
                                    createdAtMillis = createdAtMillis
                                )
                            val result =
                                AutomatedDiagnosticsRunAnnouncementSendResult.Sent(sharedRun)
                            lastAutomatedDiagnosticsCoordinationStatus =
                                automatedDiagnosticsRunAnnouncementSendStatusText(result)
                            onRunAnnouncementRequested?.invoke(sharedRun, createdAtMillis)
                            result
                        }
                    },
                    requestAutomatedDiagnosticsParticipantJoin = { sharedRun ->
                        automatedDiagnosticsParticipantJoinRequestCount += 1
                        val transport = coordinationTransport
                        if (transport != null) {
                            transport.sendParticipantJoin(from = this, sharedRun = sharedRun)
                        } else {
                            val createdAtMillis = currentWallClockMillis()
                            latestAutomatedDiagnosticsParticipantJoin =
                                AutomatedDiagnosticsParticipantJoin(
                                    sharedRun = sharedRun,
                                    peerId = localPeerId,
                                    createdAtMillis = createdAtMillis
                                )
                            val result =
                                AutomatedDiagnosticsParticipantJoinSendResult.Sent(sharedRun)
                            lastAutomatedDiagnosticsCoordinationStatus =
                                automatedDiagnosticsParticipantJoinSendStatusText(result)
                            result
                        }
                    },
                    requestAutomatedDiagnosticsWifiDirectPeerReadySignal = {
                            sharedRun,
                            expectedRemotePeerId,
                            wifiDirectCorrelationToken,
                            wifiDirectDeviceName ->
                        automatedDiagnosticsWifiDirectPeerReadyRequestCount += 1
                        val createdAtMillis = currentWallClockMillis()
                        val result = onWifiDirectPeerReadyRequested?.invoke(
                            sharedRun,
                            expectedRemotePeerId,
                            wifiDirectCorrelationToken,
                            wifiDirectDeviceName,
                            createdAtMillis
                        ) ?: coordinationTransport?.sendWifiDirectPeerReady(
                            from = this,
                            sharedRun = sharedRun,
                            expectedRemotePeerId = expectedRemotePeerId,
                            wifiDirectCorrelationToken = wifiDirectCorrelationToken,
                            wifiDirectDeviceName = wifiDirectDeviceName,
                            createdAtMillis = createdAtMillis
                        ) ?: runCatching {
                            AutomatedDiagnosticsWifiDirectPeerReadySignal(
                                sharedRun = sharedRun,
                                peerId = localPeerId,
                                expectedRemotePeerId = expectedRemotePeerId,
                                wifiDirectCorrelationToken = wifiDirectCorrelationToken,
                                wifiDirectDeviceName = wifiDirectDeviceName,
                                createdAtMillis = createdAtMillis,
                                expiresAtMillis = createdAtMillis + 8_000L
                            )
                        }.fold(
                            onSuccess = {
                                AutomatedDiagnosticsWifiDirectPeerReadySendResult.Sent(it)
                            },
                            onFailure = { error ->
                                AutomatedDiagnosticsWifiDirectPeerReadySendResult.InvalidSignal(
                                    error.message
                                        ?: "Automated diagnostics Wi-Fi peer-ready signal is invalid."
                                )
                            }
                        )
                        if (result is AutomatedDiagnosticsWifiDirectPeerReadySendResult.Sent) {
                            latestAutomatedDiagnosticsWifiDirectPeerReadySignal = result.signal
                        }
                        lastAutomatedDiagnosticsWifiDirectPeerReadyStatus =
                            automatedDiagnosticsWifiDirectPeerReadySendStatusText(result)
                        result
                    },
                    requestAutomatedDiagnosticsServerReadySignal = {
                            sharedRun,
                            expectedClientPeerId,
                            groupOwnerAddress,
                            socketPort,
                            serverToken ->
                        automatedDiagnosticsServerReadyRequestCount += 1
                        val result = onServerReadyRequested?.invoke(
                            sharedRun,
                            expectedClientPeerId,
                            groupOwnerAddress,
                            socketPort,
                            serverToken
                        ) ?: coordinationTransport?.sendServerReady(
                            from = this,
                            sharedRun = sharedRun,
                            expectedClientPeerId = expectedClientPeerId,
                            groupOwnerAddress = groupOwnerAddress,
                            socketPort = socketPort,
                            serverToken = serverToken,
                            createdAtMillis = currentWallClockMillis()
                        ) ?: runCatching {
                            val createdAtMillis = currentWallClockMillis()
                            AutomatedDiagnosticsServerReadySignal(
                                sharedRun = sharedRun,
                                peerId = localPeerId,
                                expectedClientPeerId = expectedClientPeerId,
                                groupOwnerAddress = groupOwnerAddress,
                                socketPort = socketPort,
                                serverToken = serverToken,
                                createdAtMillis = createdAtMillis,
                                expiresAtMillis = createdAtMillis + 8_000L
                            )
                        }.fold(
                            onSuccess = {
                                AutomatedDiagnosticsServerReadySendResult.Sent(it)
                            },
                            onFailure = { error ->
                                AutomatedDiagnosticsServerReadySendResult.InvalidSignal(
                                    error.message
                                        ?: "Automated diagnostics server-ready signal is invalid."
                                )
                            }
                        )
                        if (result is AutomatedDiagnosticsServerReadySendResult.Sent) {
                            latestAutomatedDiagnosticsServerReadySignal = result.signal
                        }
                        lastAutomatedDiagnosticsServerReadyStatus =
                            automatedDiagnosticsServerReadySendStatusText(result)
                        result
                    },
                    requestHybridBootstrapManualTrigger = {
                        hybridBootstrapManualTriggerRequestCount += 1
                        HybridBootstrapCommandTriggerResult.Executed(
                            HybridBootstrapCommandExecutionResult.Accepted(
                                peerId = peerIdentityKey,
                                sessionId = hybridSessionId(),
                                bootstrapIdentifier = hybridSessionId(),
                                groupOwnerAddress = "192.168.49.1",
                                socketPort = wifiDirectDebugSocketPort,
                                commandCreatedAtMillis = clock.nowMillis()
                            )
                        )
                    },
                    requestHybridBootstrapManualOffer = {
                        hybridBootstrapManualOfferRequestCount += 1
                        lastHybridBootstrapManualOfferStatus = "sent"
                        HybridBootstrapManualOfferSendResult.Sent(
                            peerId = peerIdentityKey,
                            sessionId = hybridSessionId()
                        )
                    },
                    requestHybridBootstrapManualAccept = {
                        hybridBootstrapManualAcceptRequestCount += 1
                        lastHybridBootstrapManualAcceptStatus = "sent"
                        HybridBootstrapManualAcceptSendResult.Sent(
                            peerId = peerIdentityKey,
                            sessionId = hybridSessionId()
                        )
                    },
                    requestHybridBootstrapManualSocketHint = {
                        hybridBootstrapManualSocketHintRequestCount += 1
                        val result = coordinationTransport?.sendHybridBootstrapManualSocketHint(
                            from = this
                        ) ?: HybridBootstrapManualSocketHintSendResult.Sent(
                            peerId = peerIdentityKey,
                            sessionId = hybridSessionId(),
                            groupOwnerAddress = "192.168.49.1",
                            socketPort = wifiDirectDebugSocketPort
                        )
                        lastHybridBootstrapManualSocketHintStatus = when (result) {
                            is HybridBootstrapManualSocketHintSendResult.Sent -> "sent"
                            HybridBootstrapManualSocketHintSendResult.NoActivePeer -> "no-active-peer"
                            HybridBootstrapManualSocketHintSendResult.NoActiveSession -> "no-active-session"
                            HybridBootstrapManualSocketHintSendResult.NoAcceptedCandidate -> "no-accepted-candidate"
                            HybridBootstrapManualSocketHintSendResult.NoSocketEndpoint -> "no-socket-endpoint"
                            HybridBootstrapManualSocketHintSendResult.NotGroupOwner -> "not-group-owner"
                            HybridBootstrapManualSocketHintSendResult.WriterUnavailable -> "writer-unavailable"
                            is HybridBootstrapManualSocketHintSendResult.InvalidSocketHint ->
                                "invalid:${result.reason}"
                            is HybridBootstrapManualSocketHintSendResult.SendFailed ->
                                "failed:${result.reason}"
                        }
                        result
                    }
                ),
                scope = scope
            )
        }
    }

    private class SharedAutomatedDiagnosticsCoordinationTransport(
        first: FakePhaseOneEnvironment,
        second: FakePhaseOneEnvironment
    ) {
        private val firstEndpoint = CoordinationEndpoint(first)
        private val secondEndpoint = CoordinationEndpoint(second)

        init {
            first.coordinationTransport = this
            second.coordinationTransport = this
        }

        fun mirrorConnectedTransportPeer(
            from: FakePhaseOneEnvironment,
            deviceAddress: String,
            peerId: String?
        ) {
            val receiver = otherEndpoint(from).environment
            receiver.bleConnectionStatus = BleConnectionStatus.CONNECTED
            receiver.activeTransportDeviceAddress = deviceAddress
            receiver.activeTransportPeerId = from.localPeerId.takeIf { it.isNotBlank() }
            receiver.connectedPeerIds.add(from.localPeerId)
            peerId?.let(from.connectedPeerIds::add)
        }

        fun exchangeIdentity(
            from: FakePhaseOneEnvironment,
            device: BleDiscoveredDevice,
            proposalId: String?
        ): PeerIdentityExchangeSendResult {
            val sender = endpointFor(from).environment
            val receiver = otherEndpoint(from).environment
            val senderPeerId = sender.localPeerId
            val receiverPeerId = receiver.localPeerId
            val senderProposalId = proposalId ?: "local-$receiverPeerId"
            val receiverProposalId =
                receiver.privateChatIdentitiesByPeerId[senderPeerId]?.localProposalId
                    ?: "local-$senderPeerId"
            sender.lastIdentityExchangeStatus = "submitted"
            if (receiver.hasSecureSessionForPeer(senderPeerId)) {
                sender.finalizeSecureSessionForPeer(
                    peerId = receiverPeerId,
                    displayName = device.name ?: "Aurora device",
                    localProposalId = senderProposalId,
                    remoteProposalId = receiverProposalId
                )
                return PeerIdentityExchangeSendResult.SubmittedLocally
            }
            if (
                receiver.privateChatIdentitiesByPeerId[senderPeerId]?.localProposalId != null
            ) {
                sender.finalizeSecureSessionForPeer(
                    peerId = receiverPeerId,
                    displayName = device.name ?: "Aurora device",
                    localProposalId = senderProposalId,
                    remoteProposalId = receiverProposalId
                )
                receiver.finalizeSecureSessionForPeer(
                    peerId = senderPeerId,
                    displayName = sender.samplePeer.name ?: "Aurora device",
                    localProposalId = receiverProposalId,
                    remoteProposalId = senderProposalId
                )
            }
            return PeerIdentityExchangeSendResult.SubmittedLocally
        }

        fun sendRunAnnouncement(
            from: FakePhaseOneEnvironment,
            sharedRun: AutomatedDiagnosticsSharedRun
        ): AutomatedDiagnosticsRunAnnouncementSendResult {
            val sender = endpointFor(from)
            val receiver = otherEndpoint(from)
            val result = runSuspending {
                submitAutomatedDiagnosticsRunAnnouncement(
                    bleConnectionStatus = sender.environment.bleConnectionStatus,
                    activeTransportPeerId = sender.environment.activeTransportPeerId,
                    transportSender = receiver.bridgeTransportSender(),
                    localPeerId = sender.environment.localPeerId,
                    sharedRun = sharedRun
                )
            }
            sender.environment.lastAutomatedDiagnosticsCoordinationStatus =
                automatedDiagnosticsRunAnnouncementSendStatusText(result)
            return result
        }

        fun sendParticipantJoin(
            from: FakePhaseOneEnvironment,
            sharedRun: AutomatedDiagnosticsSharedRun
        ): AutomatedDiagnosticsParticipantJoinSendResult {
            val sender = endpointFor(from)
            val receiver = otherEndpoint(from)
            val result = runSuspending {
                submitAutomatedDiagnosticsParticipantJoin(
                    bleConnectionStatus = sender.environment.bleConnectionStatus,
                    activeTransportPeerId = sender.environment.activeTransportPeerId,
                    transportSender = receiver.bridgeTransportSender(),
                    localPeerId = sender.environment.localPeerId,
                    sharedRun = sharedRun,
                    createdAtMillis = sender.environment.currentWallClockMillis()
                )
            }
            sender.environment.lastAutomatedDiagnosticsCoordinationStatus =
                automatedDiagnosticsParticipantJoinSendStatusText(result)
            return result
        }

        fun sendWifiDirectPeerReady(
            from: FakePhaseOneEnvironment,
            sharedRun: AutomatedDiagnosticsSharedRun,
            expectedRemotePeerId: String,
            wifiDirectCorrelationToken: String,
            wifiDirectDeviceName: String?,
            createdAtMillis: Long
        ): AutomatedDiagnosticsWifiDirectPeerReadySendResult {
            val sender = endpointFor(from)
            val receiver = otherEndpoint(from)
            val result = runSuspending {
                submitAutomatedDiagnosticsWifiDirectPeerReadySignal(
                    bleConnectionStatus = sender.environment.bleConnectionStatus,
                    activeTransportPeerId = sender.environment.activeTransportPeerId,
                    transportSender = receiver.bridgeTransportSender(),
                    localPeerId = sender.environment.localPeerId,
                    sharedRun = sharedRun,
                    expectedRemotePeerId = expectedRemotePeerId,
                    wifiDirectCorrelationToken = wifiDirectCorrelationToken,
                    wifiDirectDeviceName = wifiDirectDeviceName,
                    createdAtMillis = createdAtMillis
                )
            }
            sender.environment.lastAutomatedDiagnosticsWifiDirectPeerReadyStatus =
                automatedDiagnosticsWifiDirectPeerReadySendStatusText(result)
            if (result is AutomatedDiagnosticsWifiDirectPeerReadySendResult.Sent) {
                sender.environment.latestAutomatedDiagnosticsWifiDirectPeerReadySignal =
                    result.signal
            }
            return result
        }

        fun sendServerReady(
            from: FakePhaseOneEnvironment,
            sharedRun: AutomatedDiagnosticsSharedRun,
            expectedClientPeerId: String,
            groupOwnerAddress: String,
            socketPort: Int,
            serverToken: Long,
            createdAtMillis: Long
        ): AutomatedDiagnosticsServerReadySendResult {
            val sender = endpointFor(from)
            val receiver = otherEndpoint(from)
            val result = runSuspending {
                submitAutomatedDiagnosticsServerReadySignal(
                    bleConnectionStatus = sender.environment.bleConnectionStatus,
                    activeTransportPeerId = sender.environment.activeTransportPeerId,
                    transportSender = receiver.bridgeTransportSender(),
                    localPeerId = sender.environment.localPeerId,
                    sharedRun = sharedRun,
                    expectedClientPeerId = expectedClientPeerId,
                    groupOwnerAddress = groupOwnerAddress,
                    socketPort = socketPort,
                    serverToken = serverToken,
                    createdAtMillis = createdAtMillis
                )
            }
            sender.environment.lastAutomatedDiagnosticsServerReadyStatus =
                automatedDiagnosticsServerReadySendStatusText(result)
            if (result is AutomatedDiagnosticsServerReadySendResult.Sent) {
                sender.environment.latestAutomatedDiagnosticsServerReadySignal = result.signal
            }
            return result
        }

        fun sendHybridBootstrapManualSocketHint(
            from: FakePhaseOneEnvironment
        ): HybridBootstrapManualSocketHintSendResult {
            val sender = endpointFor(from)
            val receiver = otherEndpoint(from)
            return runSuspending {
                submitHybridBootstrapManualSocketHint(
                    decision = sender.environment.hybridBootstrapDecision,
                    bleConnectionStatus = sender.environment.bleConnectionStatus,
                    activeTransportPeerId = sender.environment.activeTransportPeerId,
                    peerSessionDiagnostics = sender.environment.peerSessionDiagnostics,
                    transportSender = receiver.bridgeTransportSender(),
                    localPeerId = sender.environment.localPeerId,
                    wifiDirectConnectionStatus =
                        sender.environment.wifiDirectRuntimeStatus.connectionStatus,
                    socketPort = wifiDirectDebugSocketPort,
                    createdAtMillis = sender.environment.currentWallClockMillis()
                )
            }
        }

        fun completeWifiDirectSocketConnection(
            from: FakePhaseOneEnvironment,
            host: String
        ) {
            val client = endpointFor(from).environment
            val server = otherEndpoint(from).environment
            client.completeWifiDirectSocketAsClientConnected(host)
            server.completeWifiDirectSocketAsServerConnected(host)
        }

        fun discoveredAutomatedDiagnosticsDnsSdResponsesFor(
            requester: FakePhaseOneEnvironment
        ): List<WifiDirectDnsSdServiceResponse> {
            val responder = otherEndpoint(requester).environment
            val observedPeer =
                requester.observedWifiDirectPeerByRemotePeerId[responder.localPeerId]
                    ?: return emptyList()
            val token = responder.registeredAutomatedDiagnosticsCorrelationToken
                ?: return emptyList()
            return listOf(
                wifiDirectDnsSdServiceResponse(
                    peer = observedPeer,
                    token = token,
                    deviceName = responder.registeredAutomatedDiagnosticsDeviceName
                        ?: observedPeer.deviceName
                )
            )
        }

        fun completeWifiDirectGroupFormation(
            from: FakePhaseOneEnvironment,
            selectedPeer: WifiDirectPeer
        ) {
            val sender = endpointFor(from).environment
            val receiver = otherEndpoint(from).environment
            val expectedRemotePeer =
                sender.observedWifiDirectPeerByRemotePeerId[receiver.localPeerId]
            if (
                expectedRemotePeer == null ||
                    normalizeWifiDirectDeviceAddress(selectedPeer.deviceAddress) !=
                    normalizeWifiDirectDeviceAddress(expectedRemotePeer.deviceAddress)
            ) {
                return
            }
            sender.completeWifiDirectGroupAsGroupOwner(selectedPeer)
            val mirroredReceiverPeer =
                receiver.observedWifiDirectPeerByRemotePeerId[sender.localPeerId] ?: return
            receiver.completeWifiDirectGroupAsClient(mirroredReceiverPeer)
        }

        private fun endpointFor(
            environment: FakePhaseOneEnvironment
        ): CoordinationEndpoint {
            return when (environment) {
                firstEndpoint.environment -> firstEndpoint
                secondEndpoint.environment -> secondEndpoint
                else -> error("Unknown automated diagnostics coordination endpoint.")
            }
        }

        private fun otherEndpoint(
            environment: FakePhaseOneEnvironment
        ): CoordinationEndpoint {
            return when (environment) {
                firstEndpoint.environment -> secondEndpoint
                secondEndpoint.environment -> firstEndpoint
                else -> error("Unknown automated diagnostics coordination endpoint.")
            }
        }
    }

    private class CoordinationEndpoint(
        val environment: FakePhaseOneEnvironment
    ) {
        private val stateHolder = AuroraStateHolder(
            initialState = SampleAuroraState.create(generatedUsername = "DIAGTEST"),
            localProfileStore = FakeDiagnosticsProfileStore()
        )
        private val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = stateHolder,
            hybridControlStore = InMemoryHybridTransportControlStore()
        )

        fun bridgeTransportSender(): BleTransportSender {
            return object : BleTransportSender {
                override fun send(
                    plan: OutgoingBleTransportSendPlan,
                    listener: BleTransportSender.Listener
                ) {
                    val receiveResult = receiveFrames(receiver, plan.framesInSendOrder())
                    environment.applyReceivedCoordinationResult(receiveResult)
                    environment.applyReceivedHybridBootstrapResult(receiveResult)
                    listener.onSendResult(BleTransportSendResult.QueuedLocally)
                }
            }
        }
    }

    private class RecordingTransportSender(
        private val result: BleTransportSendResult
    ) : BleTransportSender {
        var capturedPlan: OutgoingBleTransportSendPlan? = null
        var sendCallCount: Int = 0

        override fun send(
            plan: OutgoingBleTransportSendPlan,
            listener: BleTransportSender.Listener
        ) {
            sendCallCount += 1
            capturedPlan = plan
            listener.onSendResult(result)
        }
    }

    private class FakeDiagnosticsProfileStore : LocalProfileSettingsStore {
        private var settings = LocalProfileSettings(
            generatedUsername = "DIAGTEST",
            customUsername = null,
            useCustomUsernameInGlobalChat = false
        )

        override fun loadProfileSettings(): LocalProfileSettings = settings

        override fun saveGeneratedUsername(username: String) {
            settings = settings.copy(generatedUsername = username)
        }

        override fun saveCustomUsername(username: String?) {
            settings = settings.copy(customUsername = username)
        }

        override fun saveUseCustomUsernameInGlobalChat(enabled: Boolean) {
            settings = settings.copy(useCustomUsernameInGlobalChat = enabled)
        }

        override fun clearProfile() {
            settings = LocalProfileSettings(
                generatedUsername = null,
                customUsername = null,
                useCustomUsernameInGlobalChat = false
            )
        }
    }

    private fun AutomatedDiagnosticStepResult.evidenceValue(
        label: String
    ): String? {
        return evidenceValues.firstOrNull { it.label == label }?.value
    }

    private fun decodePlaintextFrame(
        plan: OutgoingBleTransportSendPlan
    ): MessageFrame {
        val frameBytes = BleGattTransportFrameReassembler.reassemble(plan.framesInSendOrder())
        return MessageFrameCodec.decode(String(frameBytes, UTF_8))
    }

    private fun advanceUntil(
        maxSteps: Int,
        advance: () -> Unit,
        condition: () -> Boolean
    ): Boolean {
        repeat(maxSteps) {
            if (condition()) {
                return true
            }
            advance()
        }
        return condition()
    }

    private companion object {
        val fakeWifiDirectSocketRuntimeCounter = AtomicLong(0L)

        fun anonymizedWifiDirectLocalDeviceInfo(
            deviceName: String
        ): WifiDirectLocalDeviceInfo {
            return WifiDirectLocalDeviceInfo(
                deviceName = deviceName,
                deviceAddress = "02:00:00:00:00:00"
            )
        }

        fun sampleDiscoveredPeer(
            stableIdHex: String = "1032547611223344"
        ): BleDiscoveredDevice {
            return BleDiscoveredDevice(
                address = "AA:BB:CC:DD:EE:FF",
                name = "Aurora peer",
                rssi = -40,
                isConnectable = true,
                hasAuroraDiscoveryPayload = true,
                stablePeerId = BleStablePeerId.fromBytes(
                    stableIdHex.chunked(2).map { byteHex ->
                        byteHex.toInt(16).toByte()
                    }.toByteArray()
                )
            )
        }

        fun wifiDirectPeer(
            name: String,
            address: String
        ): WifiDirectPeer {
            return WifiDirectPeer(
                deviceName = name,
                deviceAddress = address
            )
        }

        fun wifiDirectDnsSdServiceResponse(
            peer: WifiDirectPeer,
            token: String,
            deviceName: String? = peer.deviceName,
            observedAtMillis: Long = 1_000L
        ): WifiDirectDnsSdServiceResponse {
            return WifiDirectDnsSdServiceResponse(
                serviceType = automatedDiagnosticsWifiDirectDnsSdServiceType,
                instanceName = automatedDiagnosticsWifiDirectDnsSdInstanceName,
                peer = if (deviceName == peer.deviceName) {
                    peer
                } else {
                    peer.copy(deviceName = deviceName)
                },
                txtRecord = mapOf(
                    automatedDiagnosticsWifiDirectDnsSdTokenTxtKey to token,
                    automatedDiagnosticsWifiDirectDnsSdProtocolTxtKey to
                        automatedDiagnosticsWifiDirectDnsSdProtocolVersion
                ),
                observedAtMillis = observedAtMillis
            )
        }
    }
}

private fun receiveFrames(
    receiver: BleTransportFrameReceiver,
    frames: List<BleGattTransportFrame>
): BleTransportReceiveResult {
    return frames.fold<BleGattTransportFrame, BleTransportReceiveResult?>(null) { _, frame ->
        receiver.receive(frame)
    } ?: error("Expected at least one transport frame.")
}

private fun <T> runSuspending(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                outcome = result
            }
        }
    )

    return requireNotNull(outcome) {
        "Suspending automated diagnostics test operation did not complete synchronously."
    }.getOrThrow()
}

private fun dnsSdDiscoveryResponseKey(
    response: WifiDirectDnsSdServiceResponse
): String {
    return listOf(
        response.serviceType,
        response.instanceName ?: "",
        normalizeWifiDirectDeviceAddress(response.peer.deviceAddress) ?: response.peer.deviceAddress
    ).joinToString("|")
}
