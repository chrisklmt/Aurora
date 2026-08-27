package gr.hua.aurora.diagnostics.automated

import gr.hua.aurora.ble.advertise.BleAdvertiseStatus
import gr.hua.aurora.ble.connection.BleConnectionStatus
import gr.hua.aurora.ble.discovery.BleDiscoveredDevice
import gr.hua.aurora.ble.discovery.BleScanStatus
import gr.hua.aurora.ble.discovery.BleStablePeerId
import gr.hua.aurora.ble.permissions.BluetoothPermissionStatus
import gr.hua.aurora.ble.transport.BleGattTransportFrame
import gr.hua.aurora.ble.transport.BleGattTransportFrameReassembler
import gr.hua.aurora.ble.transport.BleTransportLocalSendTrace
import gr.hua.aurora.ble.transport.BleTransportFrameReceiver
import gr.hua.aurora.ble.transport.BleTransportReceiveResult
import gr.hua.aurora.ble.transport.BleTransportSendResult
import gr.hua.aurora.ble.transport.BleTransportSender
import gr.hua.aurora.ble.transport.OutgoingBleTransportSendPlan
import gr.hua.aurora.ble.transport.OutgoingBleTransportSendPlanBuilder
import gr.hua.aurora.data.LocalProfileSettings
import gr.hua.aurora.data.LocalProfileSettingsStore
import gr.hua.aurora.model.AuroraContact
import gr.hua.aurora.model.PrivateChatIdentity
import gr.hua.aurora.protocol.MessageFrame
import gr.hua.aurora.protocol.MessageFrameCodec
import gr.hua.aurora.protocol.MessageFrameType
import gr.hua.aurora.protocol.GlobalMeshDeliveryResult
import gr.hua.aurora.protocol.PeerIdentityExchangeSendResult
import gr.hua.aurora.protocol.PeerSessionRegistryDiagnostics
import gr.hua.aurora.protocol.PrivateChatMessageSendResult
import gr.hua.aurora.protocol.hasSessionForPeer
import gr.hua.aurora.state.AuroraAvailabilityPreference
import gr.hua.aurora.state.AuroraStateHolder
import gr.hua.aurora.state.GlobalQueuedChatSubmissionResult
import gr.hua.aurora.state.PrivateQueuedChatSubmissionResult
import gr.hua.aurora.state.SampleAuroraState
import gr.hua.aurora.state.automatedDiagnosticsParticipantJoinAfterReceiveOrNull
import gr.hua.aurora.state.appendAutomatedDiagnosticsApplicationProbeObservation
import gr.hua.aurora.state.automatedDiagnosticsHybridAcceptObservationAfterReceiveOrNull
import gr.hua.aurora.state.automatedDiagnosticsHybridSocketHintObservationAfterReceiveOrNull
import gr.hua.aurora.state.automatedDiagnosticsPhaseStateAfterReceiveOrNull
import gr.hua.aurora.state.automatedDiagnosticsRunAnnouncementAfterReceiveOrNull
import gr.hua.aurora.state.automatedDiagnosticsServerReadySignalAfterReceiveOrNull
import gr.hua.aurora.state.automatedDiagnosticsWifiDirectPeerReadySignalAfterReceiveOrNull
import gr.hua.aurora.state.createAuroraBleTransportFrameReceiver
import gr.hua.aurora.state.createHybridBootstrapManualAcceptMessage
import gr.hua.aurora.state.hybridBootstrapDecisionAfterReceiveOrNull
import gr.hua.aurora.state.currentHybridBootstrapManualTriggerSnapshot
import gr.hua.aurora.state.createHybridBootstrapManualOfferMessage
import gr.hua.aurora.state.createHybridBootstrapManualAcceptFrame
import gr.hua.aurora.state.createHybridBootstrapManualSocketHintFrame
import gr.hua.aurora.state.createHybridBootstrapManualSocketHintMessage
import gr.hua.aurora.state.recordLocallySentHybridBootstrapControlMessage
import gr.hua.aurora.state.submitAutomatedDiagnosticsPhaseStateSignal
import gr.hua.aurora.state.submitHybridBootstrapManualAccept
import gr.hua.aurora.state.submitHybridBootstrapManualOffer
import gr.hua.aurora.state.submitHybridBootstrapManualSocketHint
import gr.hua.aurora.state.submitAutomatedDiagnosticsParticipantJoin
import gr.hua.aurora.state.submitAutomatedDiagnosticsRunAnnouncement
import gr.hua.aurora.state.submitAutomatedDiagnosticsServerReadySignal
import gr.hua.aurora.state.submitAutomatedDiagnosticsWifiDirectPeerReadySignal
import gr.hua.aurora.transport.hybrid.HybridTransportControlMessage
import gr.hua.aurora.transport.processing.IncomingTransportFrameProcessingResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapAttemptCommand
import gr.hua.aurora.transport.hybrid.HybridBootstrapAttemptCommandBuilder
import gr.hua.aurora.transport.hybrid.HybridBootstrapAttemptCommandBuildResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapAttemptPolicy
import gr.hua.aurora.transport.hybrid.HybridBootstrapCandidate
import gr.hua.aurora.transport.hybrid.HybridBootstrapCandidateSelection
import gr.hua.aurora.transport.hybrid.HybridBootstrapCommandExecutionResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapCommandTriggerResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapDecision
import gr.hua.aurora.transport.hybrid.HybridBootstrapDecisionProvider
import gr.hua.aurora.transport.hybrid.HybridBootstrapDiagnostics
import gr.hua.aurora.transport.hybrid.HybridBootstrapDiagnosticsFormatter
import gr.hua.aurora.transport.hybrid.HybridBootstrapManualAcceptSendResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapManualOfferSendResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapManualSocketHintSendResult
import gr.hua.aurora.transport.hybrid.HybridBootstrapManualTriggerSnapshot
import gr.hua.aurora.transport.hybrid.HybridBootstrapSocketEndpointResolver
import gr.hua.aurora.transport.hybrid.HybridBootstrapSocketHintObservation
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
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
            val bothReachedStepSeventeenAfterMutualSocketHintPass = harness.advanceUntil(maxSteps = 2200) {
                harness.coordinatorStep(
                    AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_ACCEPT
                ).status == AutomatedDiagnosticStepStatus.PASS &&
                    harness.participantStep(
                        AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_ACCEPT
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                    harness.participantStep(
                        AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_TRIGGER
                    ).status != AutomatedDiagnosticStepStatus.WAITING &&
                    harness.participantStep(
                        AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_TRIGGER
                    ).status != AutomatedDiagnosticStepStatus.WAITING
            }
            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                bothReachedStepSeventeenAfterMutualSocketHintPass
            )

            val state = harness.participantRunner.state.value
            val environment = harness.participantEnvironment
            val acceptStep = state.steps[AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_ACCEPT.ordinal]
            val socketHintStep =
                state.steps[AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT.ordinal]
            assertStepsPassedThrough(state, AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT)
            assertEquals(AutomatedDiagnosticStepStatus.PASS, socketHintStep.status)
            assertEquals(AutomatedDiagnosticsPeerRole.PARTICIPANT, state.localPeerRole)
            val coordinationStep = state.steps[
                AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION.ordinal
            ]
            assertEquals("true", coordinationStep.evidenceValue("Participant join sent"))
            assertTrue(
                coordinationStep.evidenceValue("Run announcement received timestamp") != "none"
            )
            assertEquals("PASS", acceptStep.evidenceValue("Coordinator Step15 state"))
            assertEquals("sent", acceptStep.evidenceValue("Accept last send"))
            assertTrue((acceptStep.evidenceValue("Accept send attempts")?.toIntOrNull() ?: 0) >= 1)
            assertEquals("true", socketHintStep.evidenceValue("Hint current-attempt match"))
            assertTrue((socketHintStep.evidenceValue("Socket hint observed")?.toIntOrNull() ?: 0) >= 1)
            assertEquals(0, environment.hybridBootstrapManualTriggerRequestCount)
            assertEquals(0, environment.hybridBootstrapManualOfferRequestCount)
            assertEquals(0, environment.hybridBootstrapManualSocketHintRequestCount)
            assertTrue(environment.hybridBootstrapManualAcceptRequestCount >= 1)
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun runCompletesCoordinatorPhaseTwoAndInvokesWifiDirectAndHybridActions() = runBlocking {
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

            val bothReachedStepSeventeenAfterMutualSocketHintPass = harness.advanceUntil(maxSteps = 2200) {
                harness.coordinatorStep(
                    AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_ACCEPT
                ).status == AutomatedDiagnosticStepStatus.PASS &&
                    harness.participantStep(
                        AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_ACCEPT
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                    harness.participantStep(
                        AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_TRIGGER
                    ).status != AutomatedDiagnosticStepStatus.WAITING &&
                    harness.participantStep(
                        AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_TRIGGER
                    ).status != AutomatedDiagnosticStepStatus.WAITING
            }
            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                bothReachedStepSeventeenAfterMutualSocketHintPass
            )

            val state = harness.coordinatorRunner.state.value
            val environment = harness.coordinatorEnvironment
            val acceptStep = state.steps[AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_ACCEPT.ordinal]
            val socketHintStep =
                state.steps[AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT.ordinal]
            assertStepsPassedThrough(state, AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT)
            assertEquals(AutomatedDiagnosticStepStatus.PASS, socketHintStep.status)
            assertEquals(AutomatedDiagnosticsPeerRole.COORDINATOR, state.localPeerRole)
            assertEquals(1, environment.startWifiDirectDiscoveryCallCount)
            assertEquals(1, environment.connectToWifiDirectPeerCallCount)
            assertEquals(1, environment.startWifiDirectSocketServerCallCount)
            assertEquals(1, environment.setWifiDirectSendBridgeEnabledCallCount)
            assertEquals(1, environment.setWifiDirectReceiveBridgeEnabledCallCount)
            assertEquals("true", acceptStep.evidenceValue("Accept current-attempt match"))
            assertTrue((acceptStep.evidenceValue("Accept observed")?.toIntOrNull() ?: 0) >= 1)
            assertEquals("PASS", socketHintStep.evidenceValue("Remote Step16 state"))
            assertEquals("sent", socketHintStep.evidenceValue("Socket hint last send"))
            assertEquals(1, environment.hybridBootstrapManualOfferRequestCount)
            assertTrue(environment.hybridBootstrapManualSocketHintRequestCount >= 1)
            assertEquals(0, environment.hybridBootstrapManualTriggerRequestCount)
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun hybridBootstrapAcceptRefreshesAfterFirstDroppedDeliveryAndThenLetsBothSidesReachStepSixteenBarrier() = runBlocking {
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
        val coordinationTransport = requireNotNull(harness.coordinatorEnvironment.coordinationTransport)
        coordinationTransport.hybridBootstrapAcceptDropsRemaining = 1

        try {
            harness.startBothManualRuns()
            harness.advanceSteps(1)

            assertTrue(
                harness.advanceUntil(maxSteps = 1200) {
                    coordinationTransport.droppedHybridBootstrapAcceptCount == 1 &&
                        harness.participantEnvironment.hybridBootstrapManualAcceptRequestCount >= 1
                }
            )
            assertFalse(
                harness.participantStep(
                    AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_ACCEPT
                ).status == AutomatedDiagnosticStepStatus.PASS
            )
            assertFalse(
                harness.coordinatorStep(
                    AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_ACCEPT
                ).status == AutomatedDiagnosticStepStatus.PASS
            )

            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                harness.advanceUntil(maxSteps = 2400) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT
                        ).status == AutomatedDiagnosticStepStatus.PASS
                }
            )

            val participantAcceptStep = harness.participantStep(
                AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_ACCEPT
            )
            assertEquals(1, coordinationTransport.droppedHybridBootstrapAcceptCount)
            assertTrue(harness.participantEnvironment.hybridBootstrapManualAcceptRequestCount >= 2)
            assertTrue(
                (participantAcceptStep.evidenceValue("Accept send attempts")?.toIntOrNull() ?: 0) >= 2
            )
            assertEquals("PASS", participantAcceptStep.evidenceValue("Coordinator Step15 state"))
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun hybridBootstrapAcceptDoesNotFalsePassWhenAllDeliveriesAreDropped() = runBlocking {
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
        val coordinationTransport = requireNotNull(harness.coordinatorEnvironment.coordinationTransport)
        coordinationTransport.alwaysDropHybridBootstrapAccept = true

        try {
            harness.startBothManualRuns()
            harness.advanceSteps(1)

            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                harness.advanceUntil(maxSteps = 2400) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_ACCEPT
                    ).status == AutomatedDiagnosticStepStatus.BLOCKED &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_ACCEPT
                        ).status == AutomatedDiagnosticStepStatus.BLOCKED
                }
            )

            val participantAcceptStep = harness.participantStep(
                AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_ACCEPT
            )
            assertEquals(AutomatedDiagnosticsOverallStatus.BLOCKED, harness.coordinatorRunner.state.value.overallStatus)
            assertEquals(AutomatedDiagnosticsOverallStatus.BLOCKED, harness.participantRunner.state.value.overallStatus)
            assertEquals(AutomatedDiagnosticStepStatus.BLOCKED, participantAcceptStep.status)
            assertFalse(
                harness.coordinatorStep(
                    AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT
                ).status == AutomatedDiagnosticStepStatus.PASS
            )
            assertFalse(
                harness.participantStep(
                    AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT
                ).status == AutomatedDiagnosticStepStatus.PASS
            )
            assertEquals("sent", participantAcceptStep.evidenceValue("Accept status"))
            assertTrue((participantAcceptStep.evidenceValue("Accept sends ok")?.toIntOrNull() ?: 0) >= 1)
            assertTrue(coordinationTransport.droppedHybridBootstrapAcceptCount >= 1)
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun hybridBootstrapAcceptRejectsWrongPeerObservation() = runBlocking {
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
        val coordinationTransport = requireNotNull(harness.coordinatorEnvironment.coordinationTransport)
        coordinationTransport.alwaysDropHybridBootstrapAccept = true

        try {
            harness.startBothManualRuns()
            harness.advanceSteps(1)
            assertTrue(
                harness.advanceUntil(maxSteps = 1400) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_OFFER
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_OFFER
                        ).status == AutomatedDiagnosticStepStatus.PASS
                }
            )

            coordinationTransport.injectHybridBootstrapManualAccept(
                receiver = harness.coordinatorEnvironment,
                senderPeerId = "wrong-peer",
                sessionId = harness.coordinatorEnvironment.localPeerId,
                createdAtMillis = harness.participantEnvironment.currentWallClockMillis()
            )

            assertTrue(
                harness.advanceUntil(maxSteps = 120) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_ACCEPT
                    ).evidenceValue("Accept last rejection")?.contains("wrong-peer") == true
                }
            )
            assertFalse(
                harness.coordinatorStep(
                    AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_ACCEPT
                ).status == AutomatedDiagnosticStepStatus.PASS
            )
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun hybridBootstrapAcceptRejectsWrongSessionObservation() = runBlocking {
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
        val coordinationTransport = requireNotNull(harness.coordinatorEnvironment.coordinationTransport)
        coordinationTransport.alwaysDropHybridBootstrapAccept = true

        try {
            harness.startBothManualRuns()
            harness.advanceSteps(1)
            assertTrue(
                harness.advanceUntil(maxSteps = 1400) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_OFFER
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_OFFER
                        ).status == AutomatedDiagnosticStepStatus.PASS
                }
            )

            coordinationTransport.injectHybridBootstrapManualAccept(
                receiver = harness.coordinatorEnvironment,
                senderPeerId = harness.participantEnvironment.localPeerId,
                sessionId = "wrong-session",
                createdAtMillis = harness.participantEnvironment.currentWallClockMillis()
            )

            assertTrue(
                harness.advanceUntil(maxSteps = 120) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_ACCEPT
                    ).evidenceValue("Accept last rejection")?.contains("wrong-session") == true
                }
            )
            assertFalse(
                harness.coordinatorStep(
                    AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_ACCEPT
                ).status == AutomatedDiagnosticStepStatus.PASS
            )
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun hybridBootstrapAcceptRejectsObservationFromPreviousAttempt() = runBlocking {
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
        val coordinationTransport = requireNotNull(harness.coordinatorEnvironment.coordinationTransport)
        coordinationTransport.alwaysDropHybridBootstrapAccept = true

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

            coordinationTransport.injectHybridBootstrapManualAccept(
                receiver = harness.coordinatorEnvironment,
                senderPeerId = harness.participantEnvironment.localPeerId,
                sessionId = harness.coordinatorEnvironment.localPeerId,
                createdAtMillis = harness.participantEnvironment.currentWallClockMillis()
            )

            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                harness.advanceUntil(maxSteps = 2400) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_ACCEPT
                    ).status == AutomatedDiagnosticStepStatus.BLOCKED &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_ACCEPT
                        ).status == AutomatedDiagnosticStepStatus.BLOCKED
                }
            )

            val coordinatorAcceptStep = harness.coordinatorStep(
                AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_ACCEPT
            )
            assertEquals("false", coordinatorAcceptStep.evidenceValue("Accept current-attempt match"))
            assertTrue(
                coordinatorAcceptStep.evidenceValue("Accept last rejection")?.contains("stale") == true
            )
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun hybridBootstrapAcceptDuplicateDeliveryRemainsIdempotent() = runBlocking {
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
        val coordinationTransport = requireNotNull(harness.coordinatorEnvironment.coordinationTransport)

        try {
            harness.startBothManualRuns()
            harness.advanceSteps(1)
            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                harness.advanceUntil(maxSteps = 2200) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT
                        ).status == AutomatedDiagnosticStepStatus.PASS
                }
            )

            val candidateCountBefore = harness.coordinatorEnvironment.hybridBootstrapDecision.candidates.size
            coordinationTransport.injectHybridBootstrapManualAccept(
                receiver = harness.coordinatorEnvironment,
                senderPeerId = harness.participantEnvironment.localPeerId,
                sessionId = harness.coordinatorEnvironment.localPeerId,
                createdAtMillis = harness.participantEnvironment.currentWallClockMillis()
            )
            harness.advanceSteps(4)

            assertEquals(
                candidateCountBefore,
                harness.coordinatorEnvironment.hybridBootstrapDecision.candidates.size
            )
            assertEquals(
                AutomatedDiagnosticStepStatus.PASS,
                harness.coordinatorStep(
                    AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT
                ).status
            )
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun hybridBootstrapSocketHintSendsFromAcceptedCandidateBeforeSocketReadySelection() = runBlocking {
        val harness = createDefaultPhaseTwoHarness()
        val coordinationTransport = requireNotNull(harness.coordinatorEnvironment.coordinationTransport)
        coordinationTransport.hybridBootstrapSocketHintDropsRemaining = 1

        try {
            harness.startBothManualRuns()
            harness.advanceSteps(1)

            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                harness.awaitMutualStepFifteenPass()
            )

            assertTrue(
                harness.advanceUntil(maxSteps = 1200) {
                    coordinationTransport.droppedHybridBootstrapSocketHintCount == 1 &&
                        harness.coordinatorEnvironment.hybridBootstrapManualSocketHintRequestCount >= 1
                }
            )
            assertEquals(0, harness.participantEnvironment.hybridBootstrapDiagnostics.socketReadyCandidateCount)
            assertFalse(
                harness.participantStep(
                    AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT
                ).status == AutomatedDiagnosticStepStatus.PASS
            )

            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                harness.awaitMutualStepSixteenPassAndStepSeventeenStart()
            )

            assertTrue(harness.coordinatorEnvironment.hybridBootstrapManualSocketHintRequestCount >= 1)
            assertEquals(
                AutomatedDiagnosticStepStatus.PASS,
                harness.coordinatorStep(AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT).status
            )
            assertEquals(
                AutomatedDiagnosticStepStatus.PASS,
                harness.participantStep(AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT).status
            )
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun hybridBootstrapSocketHintRefreshesAfterFirstDroppedDeliveryAndThenPasses() = runBlocking {
        val harness = createDefaultPhaseTwoHarness()
        val coordinationTransport = requireNotNull(harness.coordinatorEnvironment.coordinationTransport)
        coordinationTransport.hybridBootstrapSocketHintDropsRemaining = 1

        try {
            harness.startBothManualRuns()
            harness.advanceSteps(1)
            assertTrue(harness.awaitMutualStepFifteenPass())

            assertTrue(
                harness.advanceUntil(maxSteps = 1200) {
                    coordinationTransport.droppedHybridBootstrapSocketHintCount == 1 &&
                        harness.coordinatorEnvironment.hybridBootstrapManualSocketHintRequestCount >= 1
                }
            )
            assertFalse(
                harness.coordinatorStep(
                    AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT
                ).status == AutomatedDiagnosticStepStatus.PASS
            )
            assertFalse(
                harness.participantStep(
                    AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT
                ).status == AutomatedDiagnosticStepStatus.PASS
            )

            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                harness.awaitMutualStepSixteenPassAndStepSeventeenStart(maxSteps = 2600)
            )
            assertEquals(1, coordinationTransport.droppedHybridBootstrapSocketHintCount)
            assertTrue(harness.coordinatorEnvironment.hybridBootstrapManualSocketHintRequestCount >= 2)
            assertTrue(
                (harness.participantStep(
                    AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT
                ).evidenceValue("Socket hint observed")?.toIntOrNull() ?: 0) >= 1
            )
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun hybridBootstrapSocketHintDoesNotFalsePassWhenAllDeliveriesAreDropped() = runBlocking {
        val harness = createDefaultPhaseTwoHarness()
        val coordinationTransport = requireNotNull(harness.coordinatorEnvironment.coordinationTransport)
        coordinationTransport.alwaysDropHybridBootstrapSocketHint = true

        try {
            harness.startBothManualRuns()
            harness.advanceSteps(1)

            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                harness.advanceUntil(maxSteps = 2800) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT
                    ).status == AutomatedDiagnosticStepStatus.BLOCKED &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT
                        ).status == AutomatedDiagnosticStepStatus.BLOCKED
                }
            )

            assertTrue(coordinationTransport.droppedHybridBootstrapSocketHintCount >= 1)
            assertTrue(harness.coordinatorEnvironment.hybridBootstrapManualSocketHintRequestCount >= 1)
            assertEquals(
                AutomatedDiagnosticStepStatus.BLOCKED,
                harness.coordinatorStep(
                    AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_TRIGGER
                ).status
            )
            assertEquals(
                AutomatedDiagnosticStepStatus.BLOCKED,
                harness.participantStep(
                    AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_TRIGGER
                ).status
            )
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun hybridBootstrapSocketHintDuplicateDeliveryRemainsIdempotent() = runBlocking {
        val harness = createDefaultPhaseTwoHarness()
        val coordinationTransport = requireNotNull(harness.coordinatorEnvironment.coordinationTransport)

        try {
            harness.startBothManualRuns()
            harness.advanceSteps(1)
            assertTrue(harness.awaitMutualStepSixteenPassAndStepSeventeenStart())

            val candidateCountBefore = harness.participantEnvironment.hybridBootstrapDecision.candidates.size
            coordinationTransport.injectHybridBootstrapManualSocketHint(
                receiver = harness.participantEnvironment,
                senderPeerId = harness.coordinatorEnvironment.localPeerId,
                sessionId = harness.coordinatorEnvironment.localPeerId,
                groupOwnerAddress = "192.168.49.1",
                socketPort = wifiDirectDebugSocketPort,
                createdAtMillis = harness.coordinatorEnvironment.currentWallClockMillis()
            )
            harness.advanceSteps(4)

            assertEquals(
                candidateCountBefore,
                harness.participantEnvironment.hybridBootstrapDecision.candidates.size
            )
            assertEquals(
                1,
                harness.participantEnvironment.hybridBootstrapDiagnostics.socketReadyCandidateCount
            )
            assertEquals(
                AutomatedDiagnosticStepStatus.PASS,
                harness.participantStep(
                    AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT
                ).status
            )
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun hybridBootstrapSocketHintRejectsWrongPeerObservation() = runBlocking {
        val harness = createDefaultPhaseTwoHarness()
        val coordinationTransport = requireNotNull(harness.coordinatorEnvironment.coordinationTransport)
        coordinationTransport.alwaysDropHybridBootstrapSocketHint = true

        try {
            harness.startBothManualRuns()
            harness.advanceSteps(1)
            assertTrue(harness.awaitMutualStepFifteenPass())

            coordinationTransport.injectHybridBootstrapManualSocketHint(
                receiver = harness.participantEnvironment,
                senderPeerId = "wrong-peer",
                sessionId = harness.coordinatorEnvironment.localPeerId,
                groupOwnerAddress = "192.168.49.1",
                socketPort = wifiDirectDebugSocketPort,
                createdAtMillis = harness.coordinatorEnvironment.currentWallClockMillis()
            )

            assertTrue(
                harness.advanceUntil(maxSteps = 2600) {
                    harness.participantStep(
                        AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT
                    ).status == AutomatedDiagnosticStepStatus.BLOCKED
                }
            )

            val participantSocketHintStep = harness.participantStep(
                AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT
            )
            assertEquals("false", participantSocketHintStep.evidenceValue("Hint current-attempt match"))
            assertTrue(
                participantSocketHintStep.evidenceValue("Hint last rejection")?.contains("wrong-peer") == true
            )
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun hybridBootstrapSocketHintRejectsWrongSessionObservation() = runBlocking {
        val harness = createDefaultPhaseTwoHarness()
        val coordinationTransport = requireNotNull(harness.coordinatorEnvironment.coordinationTransport)
        coordinationTransport.alwaysDropHybridBootstrapSocketHint = true

        try {
            harness.startBothManualRuns()
            harness.advanceSteps(1)
            assertTrue(harness.awaitMutualStepFifteenPass())

            coordinationTransport.injectHybridBootstrapManualSocketHint(
                receiver = harness.participantEnvironment,
                senderPeerId = harness.coordinatorEnvironment.localPeerId,
                sessionId = "wrong-session",
                groupOwnerAddress = "192.168.49.1",
                socketPort = wifiDirectDebugSocketPort,
                createdAtMillis = harness.coordinatorEnvironment.currentWallClockMillis()
            )

            assertTrue(
                harness.advanceUntil(maxSteps = 2600) {
                    harness.participantStep(
                        AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT
                    ).status == AutomatedDiagnosticStepStatus.BLOCKED
                }
            )

            assertTrue(
                harness.participantStep(
                    AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT
                ).evidenceValue("Hint last rejection")?.contains("wrong-session") == true
            )
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun hybridBootstrapSocketHintRejectsWrongEndpointObservation() = runBlocking {
        val harness = createDefaultPhaseTwoHarness()
        val coordinationTransport = requireNotNull(harness.coordinatorEnvironment.coordinationTransport)
        coordinationTransport.alwaysDropHybridBootstrapSocketHint = true

        try {
            harness.startBothManualRuns()
            harness.advanceSteps(1)
            assertTrue(harness.awaitMutualStepFifteenPass())

            coordinationTransport.injectHybridBootstrapManualSocketHint(
                receiver = harness.participantEnvironment,
                senderPeerId = harness.coordinatorEnvironment.localPeerId,
                sessionId = harness.coordinatorEnvironment.localPeerId,
                groupOwnerAddress = "192.168.49.77",
                socketPort = wifiDirectDebugSocketPort,
                createdAtMillis = harness.coordinatorEnvironment.currentWallClockMillis()
            )

            assertTrue(
                harness.advanceUntil(maxSteps = 2600) {
                    harness.participantStep(
                        AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT
                    ).status == AutomatedDiagnosticStepStatus.BLOCKED
                }
            )

            val rejection = harness.participantStep(
                AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT
            ).evidenceValue("Hint last rejection")
            assertTrue(rejection?.contains("invalid-payload") == true)
            assertTrue(rejection?.contains("observation.groupOwnerAddress") == true)
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun hybridBootstrapSocketHintRejectsPreviousAttemptObservationAsStale() = runBlocking {
        val harness = createDefaultPhaseTwoHarness()
        val coordinationTransport = requireNotNull(harness.coordinatorEnvironment.coordinationTransport)
        coordinationTransport.alwaysDropHybridBootstrapSocketHint = true

        try {
            harness.startBothManualRuns()
            harness.advanceSteps(1)
            assertTrue(
                harness.advanceUntil(maxSteps = 1600) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_OFFER
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_OFFER
                        ).status == AutomatedDiagnosticStepStatus.PASS
                }
            )

            coordinationTransport.injectHybridBootstrapManualSocketHint(
                receiver = harness.participantEnvironment,
                senderPeerId = harness.coordinatorEnvironment.localPeerId,
                sessionId = harness.coordinatorEnvironment.localPeerId,
                groupOwnerAddress = "192.168.49.1",
                socketPort = wifiDirectDebugSocketPort,
                createdAtMillis = harness.coordinatorEnvironment.currentWallClockMillis()
            )

            assertTrue(
                harness.advanceUntil(maxSteps = 2800) {
                    harness.participantStep(
                        AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT
                    ).status == AutomatedDiagnosticStepStatus.BLOCKED
                }
            )

            assertTrue(
                harness.participantStep(
                    AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT
                ).evidenceValue("Hint last rejection")?.contains("stale") == true
            )
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun hybridBootstrapTriggerAcceptsCurrentAttemptWhenParticipantWallClockIsAheadByTwoMinutes() = runBlocking {
        val harness = createDefaultPhaseTwoHarness()
        harness.advanceParticipantWallClock(120_000L)

        try {
            harness.startBothManualRuns()
            harness.advanceSteps(1)

            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                harness.awaitMutualStepSixteenPassAndStepSeventeenStart(maxSteps = 2600)
            )
            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                harness.awaitMutualStepSeventeenPass(maxSteps = 1200)
            )
            assertEquals(
                AutomatedDiagnosticStepStatus.PASS,
                harness.participantStep(
                    AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT
                ).status
            )
            assertEquals(
                AutomatedDiagnosticStepStatus.PASS,
                harness.participantStep(
                    AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_TRIGGER
                ).status
            )
            assertEquals(1, harness.participantEnvironment.hybridBootstrapManualTriggerRequestCount)
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun hybridBootstrapTriggerAcceptsCurrentAttemptWhenParticipantWallClockIsBehindByTwoMinutes() = runBlocking {
        val harness = createDefaultPhaseTwoHarness()
        harness.advanceCoordinatorWallClock(120_000L)

        try {
            harness.startBothManualRuns()
            harness.advanceSteps(1)

            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                harness.awaitMutualStepSixteenPassAndStepSeventeenStart(maxSteps = 2600)
            )
            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                harness.awaitMutualStepSeventeenPass(maxSteps = 1200)
            )
            assertEquals(
                AutomatedDiagnosticStepStatus.PASS,
                harness.participantStep(
                    AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_TRIGGER
                ).status
            )
            assertEquals(1, harness.participantEnvironment.hybridBootstrapManualTriggerRequestCount)
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun hybridBootstrapTriggerFailurePreservesDiagnosticsReportAndEarlierPasses() = runBlocking {
        val harness = createDefaultPhaseTwoHarness()
        harness.participantEnvironment.onHybridBootstrapManualTriggerRequested = {
            HybridBootstrapCommandTriggerResult.Executed(
                HybridBootstrapCommandExecutionResult.Rejected(
                    reason = "Hybrid bootstrap socket dial failed: ConnectException"
                )
            )
        }

        try {
            harness.startBothManualRuns()
            harness.advanceSteps(1)

            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                harness.awaitMutualStepSixteenPassAndStepSeventeenStart(maxSteps = 2600)
            )
            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                harness.advanceUntil(maxSteps = 1200) {
                    harness.participantStep(
                        AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_TRIGGER
                    ).status == AutomatedDiagnosticStepStatus.FAIL
                }
            )

            val participantState = harness.participantRunner.state.value
            val participantTriggerStep = harness.participantStep(
                AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_TRIGGER
            )

            assertEquals(AutomatedDiagnosticsOverallStatus.FAIL, participantState.overallStatus)
            assertStepsPassedThrough(
                participantState,
                AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT
            )
            assertEquals(AutomatedDiagnosticStepStatus.FAIL, participantTriggerStep.status)
            assertEquals(
                "Hybrid bootstrap socket dial failed: ConnectException",
                participantTriggerStep.blockerOrFailure
            )
            assertTrue(
                participantState.reportText.contains("Hybrid bootstrap trigger failed")
            )
            assertEquals(1, harness.participantEnvironment.hybridBootstrapManualTriggerRequestCount)
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun hybridBootstrapSocketHintUsesActualGroupOwnerWhenParticipantOwnsWifiDirectGroup() = runBlocking {
        val harness = createDefaultPhaseTwoHarness()
        val coordinationTransport = requireNotNull(harness.coordinatorEnvironment.coordinationTransport)
        coordinationTransport.forcedGroupOwnerPeerId = harness.participantEnvironment.localPeerId

        try {
            harness.startBothManualRuns()
            harness.advanceSteps(1)

            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                harness.awaitMutualStepSixteenPassAndStepSeventeenStart(maxSteps = 2600)
            )
            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                harness.awaitMutualStepSeventeenPass(maxSteps = 1200)
            )
            assertEquals(
                0,
                harness.coordinatorEnvironment.hybridBootstrapManualSocketHintRequestCount
            )
            assertTrue(harness.participantEnvironment.hybridBootstrapManualSocketHintRequestCount >= 1)
            assertEquals(
                AutomatedDiagnosticStepStatus.PASS,
                harness.coordinatorStep(
                    AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT
                ).status
            )
            assertEquals(
                AutomatedDiagnosticStepStatus.PASS,
                harness.participantStep(
                    AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT
                ).status
            )
            assertEquals(
                AutomatedDiagnosticStepStatus.PASS,
                harness.coordinatorStep(
                    AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_TRIGGER
                ).status
            )
            assertEquals(
                AutomatedDiagnosticStepStatus.PASS,
                harness.participantStep(
                    AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_TRIGGER
                ).status
            )
            assertEquals(1, harness.coordinatorEnvironment.hybridBootstrapManualTriggerRequestCount)
            assertEquals(0, harness.participantEnvironment.hybridBootstrapManualTriggerRequestCount)
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun phaseThreeMessagingProbesPassAndCleanupOnlyExactCapturedProbeIds() = runBlocking {
        val harness = createDefaultPhaseTwoHarness()

        try {
            harness.startBothManualRuns()
            harness.advanceSteps(1)

            val reachedStepSeventeenPass = harness.awaitMutualStepSeventeenPass(maxSteps = 3600)
            assertTrue(harness.reportText(), reachedStepSeventeenPass)
            val reachedStepTwentyOnePass = harness.awaitMutualStepTwentyOnePass(maxSteps = 2200)
            assertTrue(harness.reportText(), reachedStepTwentyOnePass)

            listOf(
                AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
                AutomatedDiagnosticStepId.PRIVATE_ENCRYPTED_MESSAGE_PROBE,
                AutomatedDiagnosticStepId.REVERSE_DIRECTION_MESSAGING_PROBE,
                AutomatedDiagnosticStepId.FINAL_END_TO_END_VALIDATION
            ).forEach { stepId ->
                assertEquals(AutomatedDiagnosticStepStatus.PASS, harness.coordinatorStep(stepId).status)
                assertEquals(AutomatedDiagnosticStepStatus.PASS, harness.participantStep(stepId).status)
            }

            assertEquals(1, harness.coordinatorEnvironment.sendGlobalChatMessageCallCount)
            assertEquals(1, harness.coordinatorEnvironment.sendPrivateChatMessageCallCount)
            assertEquals(1, harness.participantEnvironment.sendGlobalChatMessageCallCount)
            assertEquals(1, harness.participantEnvironment.sendPrivateChatMessageCallCount)

            assertEquals(1, harness.coordinatorEnvironment.removeMessagesByIdsCallCount)
            assertEquals(1, harness.participantEnvironment.removeMessagesByIdsCallCount)
            assertEquals(4, harness.coordinatorEnvironment.lastRemovedMessageIds.size)
            assertEquals(4, harness.participantEnvironment.lastRemovedMessageIds.size)
            assertTrue(
                harness.coordinatorEnvironment.lastRemovedMessageIds.all { messageId ->
                    messageId.startsWith("global-probe-") || messageId.startsWith("private-probe-")
                }
            )
            assertTrue(
                harness.participantEnvironment.lastRemovedMessageIds.all { messageId ->
                    messageId.startsWith("global-probe-") || messageId.startsWith("private-probe-")
                }
            )
            assertTrue(
                harness.coordinatorEnvironment.recentAutomatedDiagnosticsApplicationProbeObservations.isEmpty()
            )
            assertTrue(
                harness.participantEnvironment.recentAutomatedDiagnosticsApplicationProbeObservations.isEmpty()
            )
            assertEquals(
                "Phase 3 cleanup removed 4 exact automated diagnostics message id(s).",
                harness.coordinatorRunner.state.value.phaseTwoSummary
            )
            assertEquals(
                "Phase 3 cleanup removed 4 exact automated diagnostics message id(s).",
                harness.participantRunner.state.value.phaseTwoSummary
            )
            assertEquals(
                "4",
                harness.coordinatorStep(
                    AutomatedDiagnosticStepId.FINAL_END_TO_END_VALIDATION
                ).evidenceValue("Phase 3 captured ids")
            )
            assertEquals(
                "2",
                harness.coordinatorStep(
                    AutomatedDiagnosticStepId.FINAL_END_TO_END_VALIDATION
                ).evidenceValue("Phase 3 observations")
            )
            assertEquals(
                "4",
                harness.coordinatorStep(
                    AutomatedDiagnosticStepId.FINAL_END_TO_END_VALIDATION
                ).evidenceValue("Cleanup attempted ids")
            )
            assertEquals(
                "4",
                harness.coordinatorStep(
                    AutomatedDiagnosticStepId.FINAL_END_TO_END_VALIDATION
                ).evidenceValue("Cleaned message ids")
            )
            assertEquals(
                "0",
                harness.coordinatorStep(
                    AutomatedDiagnosticStepId.FINAL_END_TO_END_VALIDATION
                ).evidenceValue("Cleanup remaining ids")
            )
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun stepEighteenPassesWhenGlobalApplicationSenderIdDiffersFromStablePeerId() = runBlocking {
        val harness = createDefaultPhaseTwoHarness().apply {
            coordinatorEnvironment.globalApplicationSenderId = "Chris"
        }

        try {
            harness.startBothManualRuns()
            harness.advanceSteps(1)

            val reachedStepSeventeenPass = harness.awaitMutualStepSeventeenPass(maxSteps = 3600)
            assertTrue(harness.reportText(), reachedStepSeventeenPass)
            assertTrue(
                harness.advanceUntil(maxSteps = 1600) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
                        ).status == AutomatedDiagnosticStepStatus.PASS
                }
            )

            val observation = harness.participantEnvironment
                .recentAutomatedDiagnosticsApplicationProbeObservations
                .single { recorded ->
                    recorded.stepId == AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE &&
                        recorded.probeKind == AutomatedDiagnosticsApplicationProbeKind.GLOBAL &&
                        recorded.direction == AutomatedDiagnosticsApplicationProbeDirection.C2P
                }

            assertEquals(
                harness.coordinatorEnvironment.localPeerId,
                observation.senderPeerId
            )
            assertEquals("Chris", observation.applicationSenderId)
            assertEquals(
                "1",
                harness.participantStep(
                    AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
                ).evidenceValue("GLOBAL C2P observed")
            )
            val participantStep = harness.participantStep(
                AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
            )
            val senderMessageId = requireNotNull(
                participantStep.evidenceValue("GLOBAL C2P sender message id")
            )
            assertTrue(senderMessageId.startsWith("global-"))
            assertEquals(
                "queued-active:${harness.participantEnvironment.localPeerId}",
                participantStep.evidenceValue("GLOBAL C2P submit")
            )
            assertEquals("QueuedLocally", participantStep.evidenceValue("GLOBAL C2P send"))
            assertEquals("COMPLETE_FRAME_SEEN", participantStep.evidenceValue("GLOBAL C2P receiver frame"))
            assertEquals("Processed", participantStep.evidenceValue("GLOBAL C2P transport result"))
            assertEquals("Received", participantStep.evidenceValue("GLOBAL C2P processing"))
            assertEquals("Appended", participantStep.evidenceValue("GLOBAL C2P ingestion"))
            assertEquals("VALID", participantStep.evidenceValue("GLOBAL C2P marker"))
            assertEquals("GLOBAL_TEXT", participantStep.evidenceValue("GLOBAL C2P frame type"))
            assertEquals("true", participantStep.evidenceValue("GLOBAL C2P observation created"))
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun stepEighteenDroppedProbeShowsNotSeenBreadcrumbsUntilTimeout() = runBlocking {
        val harness = createDefaultPhaseTwoHarness()
        val coordinationTransport = requireNotNull(harness.coordinatorEnvironment.coordinationTransport)
        coordinationTransport.dropAutomatedDiagnosticsApplicationProbePredicate = { marker, _, _, _ ->
            marker.stepId == AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE &&
                marker.probeKind == AutomatedDiagnosticsApplicationProbeKind.GLOBAL &&
                marker.direction == AutomatedDiagnosticsApplicationProbeDirection.C2P
        }

        try {
            harness.startBothManualRuns()
            harness.advanceSteps(1)

            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                harness.awaitMutualStepSeventeenPass(maxSteps = 1200)
            )
            assertTrue(
                harness.advanceUntil(maxSteps = 1600) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
                    ).status == AutomatedDiagnosticStepStatus.BLOCKED &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
                        ).status == AutomatedDiagnosticStepStatus.BLOCKED
                }
            )

            val participantStep = harness.participantStep(
                AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
            )
            assertEquals("0", participantStep.evidenceValue("GLOBAL C2P observed"))
            assertTrue(
                requireNotNull(
                    participantStep.evidenceValue("GLOBAL C2P sender message id")
                ).startsWith("global-")
            )
            assertEquals(
                "queued-active:${harness.participantEnvironment.localPeerId}",
                participantStep.evidenceValue("GLOBAL C2P submit")
            )
            assertEquals("QueuedLocally", participantStep.evidenceValue("GLOBAL C2P send"))
            assertEquals("NO_MATCHING_CHUNK_SEEN", participantStep.evidenceValue("GLOBAL C2P receiver frame"))
            assertEquals("unavailable", participantStep.evidenceValue("GLOBAL C2P transport result"))
            assertEquals("NOT_SEEN", participantStep.evidenceValue("GLOBAL C2P processing"))
            assertEquals("not-attempted", participantStep.evidenceValue("GLOBAL C2P ingestion"))
            assertEquals("not-attempted", participantStep.evidenceValue("GLOBAL C2P marker"))
            assertEquals("unavailable", participantStep.evidenceValue("GLOBAL C2P frame type"))
            assertEquals(
                "false",
                participantStep.evidenceValue("GLOBAL C2P source resolution attempted")
            )
            assertEquals("false", participantStep.evidenceValue("GLOBAL C2P observation created"))
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun stepEighteenKeepsQuietRunningWindowWhileWaitingForRemoteProbeResult() = runBlocking {
        val harness = createDefaultPhaseTwoHarness()
        val coordinationTransport = requireNotNull(harness.coordinatorEnvironment.coordinationTransport)
        coordinationTransport.dropAutomatedDiagnosticsApplicationProbePredicate = { marker, _, _, _ ->
            marker.stepId == AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE &&
                marker.probeKind == AutomatedDiagnosticsApplicationProbeKind.GLOBAL &&
                marker.direction == AutomatedDiagnosticsApplicationProbeDirection.C2P
        }

        try {
            harness.startBothManualRuns()
            harness.advanceSteps(1)

            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                harness.awaitMutualStepSeventeenPass(maxSteps = 1200)
            )
            assertTrue(
                harness.advanceUntil(maxSteps = 400) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
                    ).status == AutomatedDiagnosticStepStatus.RUNNING &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
                        ).status == AutomatedDiagnosticStepStatus.RUNNING
                }
            )

            val coordinatorPhaseRequestCount =
                harness.coordinatorEnvironment.automatedDiagnosticsPhaseStateRequestCount
            val participantPhaseRequestCount =
                harness.participantEnvironment.automatedDiagnosticsPhaseStateRequestCount

            harness.advanceSteps(45)

            assertEquals(
                AutomatedDiagnosticStepStatus.RUNNING,
                harness.coordinatorStep(AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE).status
            )
            assertEquals(
                AutomatedDiagnosticStepStatus.RUNNING,
                harness.participantStep(AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE).status
            )
            assertEquals(
                coordinatorPhaseRequestCount,
                harness.coordinatorEnvironment.automatedDiagnosticsPhaseStateRequestCount
            )
            assertEquals(
                participantPhaseRequestCount,
                harness.participantEnvironment.automatedDiagnosticsPhaseStateRequestCount
            )
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun stepEighteenSameMessageIdDuplicateDeliveryStillPassesAsSingleLogicalObservation() = runBlocking {
        val harness = createDefaultPhaseTwoHarness().apply {
            coordinatorEnvironment.globalApplicationSenderId = "Chris"
        }
        val coordinationTransport = requireNotNull(harness.coordinatorEnvironment.coordinationTransport)
        coordinationTransport.duplicateAutomatedDiagnosticsApplicationProbePredicate = { marker, _, _, _ ->
            marker.stepId == AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE &&
                marker.probeKind == AutomatedDiagnosticsApplicationProbeKind.GLOBAL &&
                marker.direction == AutomatedDiagnosticsApplicationProbeDirection.C2P
        }

        try {
            harness.startBothManualRuns()
            harness.advanceSteps(1)

            val reachedStepSeventeenPass = harness.awaitMutualStepSeventeenPass(maxSteps = 3600)
            assertTrue(harness.reportText(), reachedStepSeventeenPass)
            assertTrue(
                harness.advanceUntil(maxSteps = 1600) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
                        ).status == AutomatedDiagnosticStepStatus.PASS
                }
            )

            assertEquals(1, coordinationTransport.duplicatedAutomatedDiagnosticsApplicationProbeCount)
            assertEquals(
                "1",
                harness.participantStep(
                    AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
                ).evidenceValue("GLOBAL C2P observed")
            )
            assertEquals(
                1,
                harness.participantEnvironment
                    .recentAutomatedDiagnosticsApplicationProbeObservations
                    .count { observation ->
                        observation.stepId == AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE &&
                            observation.probeKind == AutomatedDiagnosticsApplicationProbeKind.GLOBAL &&
                            observation.direction == AutomatedDiagnosticsApplicationProbeDirection.C2P
                    }
            )
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun stepEighteenParticipantTerminalPassWaitsForCoordinatorCompletionBeforeStepNineteen() = runBlocking {
        val harness = createDefaultPhaseTwoHarness()
        val coordinationTransport = requireNotNull(harness.coordinatorEnvironment.coordinationTransport)
        coordinationTransport.dropFirstPhaseStatePredicate = {
                from,
                stepId,
                phaseState,
                attemptNumber ->
            from === harness.coordinatorEnvironment &&
                stepId == AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE &&
                phaseState == AutomatedDiagnosticsPhaseState.PASS &&
                attemptNumber == 1
        }

        try {
            harness.startBothManualRuns()
            val reachedStepSeventeenPass = harness.awaitMutualStepSeventeenPass(maxSteps = 3600)
            assertTrue(harness.reportText(), reachedStepSeventeenPass)

            assertTrue(
                harness.reportText(),
                harness.advanceUntil(maxSteps = 240) {
                    val coordinatorStep = harness.coordinatorStep(
                        AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
                    )
                    val participantStep = harness.participantStep(
                        AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
                    )
                    coordinatorStep.status == AutomatedDiagnosticStepStatus.RUNNING &&
                        coordinatorStep.evidenceValue("Local phase state") == "PASS" &&
                        participantStep.status == AutomatedDiagnosticStepStatus.RUNNING &&
                        participantStep.evidenceValue("Local phase state") == "PASS" &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.PRIVATE_ENCRYPTED_MESSAGE_PROBE
                        ).status == AutomatedDiagnosticStepStatus.WAITING
                }
            )

            assertTrue(
                harness.reportText(),
                harness.advanceUntil(maxSteps = 1600) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.PRIVATE_ENCRYPTED_MESSAGE_PROBE
                    ).status != AutomatedDiagnosticStepStatus.WAITING &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.PRIVATE_ENCRYPTED_MESSAGE_PROBE
                        ).status != AutomatedDiagnosticStepStatus.WAITING
                }
            )

            val coordinatorStep = harness.coordinatorStep(
                AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
            )
            val participantStep = harness.participantStep(
                AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
            )
            assertEquals(AutomatedDiagnosticStepStatus.PASS, coordinatorStep.status)
            assertEquals(AutomatedDiagnosticStepStatus.PASS, participantStep.status)
            assertEquals(1, coordinationTransport.droppedPhaseStateCount)
            assertEquals(1, harness.coordinatorEnvironment.sendGlobalChatMessageCallCount)
            assertEquals(0, harness.participantEnvironment.sendGlobalChatMessageCallCount)
            assertTrue(
                (harness.coordinatorStep(
                    AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
                ).evidenceValue("Phase-state send count")?.toIntOrNull() ?: 0) >= 2
            )
            assertEquals("1", participantStep.evidenceValue("GLOBAL C2P observed"))
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun stepEighteenCoordinatorTerminalPassRecoveryHandlesMissedParticipantPass() = runBlocking {
        val harness = createDefaultPhaseTwoHarness()
        val coordinationTransport = requireNotNull(harness.coordinatorEnvironment.coordinationTransport)
        coordinationTransport.dropFirstPhaseStatePredicate = {
                from,
                stepId,
                phaseState,
                attemptNumber ->
            from === harness.participantEnvironment &&
                stepId == AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE &&
                phaseState == AutomatedDiagnosticsPhaseState.PASS &&
                attemptNumber == 1
        }

        try {
            harness.startBothManualRuns()
            val reachedStepSeventeenPass = harness.awaitMutualStepSeventeenPass(maxSteps = 3600)
            assertTrue(harness.reportText(), reachedStepSeventeenPass)

            assertTrue(
                harness.reportText(),
                harness.advanceUntil(maxSteps = 240) {
                    val coordinatorStep = harness.coordinatorStep(
                        AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
                    )
                    val participantStep = harness.participantStep(
                        AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
                    )
                    coordinatorStep.status == AutomatedDiagnosticStepStatus.RUNNING &&
                        participantStep.status == AutomatedDiagnosticStepStatus.RUNNING &&
                        participantStep.evidenceValue("Local phase state") == "PASS" &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.PRIVATE_ENCRYPTED_MESSAGE_PROBE
                        ).status == AutomatedDiagnosticStepStatus.WAITING &&
                        harness.coordinatorStep(
                            AutomatedDiagnosticStepId.PRIVATE_ENCRYPTED_MESSAGE_PROBE
                        ).status == AutomatedDiagnosticStepStatus.WAITING
                }
            )

            assertTrue(
                harness.reportText(),
                harness.advanceUntil(maxSteps = 1600) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.PRIVATE_ENCRYPTED_MESSAGE_PROBE
                    ).status != AutomatedDiagnosticStepStatus.WAITING &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.PRIVATE_ENCRYPTED_MESSAGE_PROBE
                        ).status != AutomatedDiagnosticStepStatus.WAITING
                }
            )

            val coordinatorStep = harness.coordinatorStep(
                AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
            )
            val participantStep = harness.participantStep(
                AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
            )
            assertEquals(AutomatedDiagnosticStepStatus.PASS, coordinatorStep.status)
            assertEquals(AutomatedDiagnosticStepStatus.PASS, participantStep.status)
            assertEquals(1, coordinationTransport.droppedPhaseStateCount)
            assertEquals(1, harness.coordinatorEnvironment.sendGlobalChatMessageCallCount)
            assertEquals(0, harness.participantEnvironment.sendGlobalChatMessageCallCount)
            assertTrue(
                (participantStep.evidenceValue("Phase-state send count")?.toIntOrNull() ?: 0) >= 2
            )
            assertEquals("1", participantStep.evidenceValue("GLOBAL C2P observed"))
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun stepEighteenDifferentMessageIdsForSameMarkerAreIgnoredUntilTimeout() {
        val marker = AutomatedDiagnosticsApplicationProbeMarker(
            sharedRunId = "diag-ed034fc06675f6a4113a3cc4",
            stepId = AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
            attemptNumber = 1,
            probeKind = AutomatedDiagnosticsApplicationProbeKind.GLOBAL,
            direction = AutomatedDiagnosticsApplicationProbeDirection.C2P
        )
        val observation = AutomatedDiagnosticsApplicationProbeObservation(
            sharedRunId = marker.sharedRunId,
            stepId = marker.stepId,
            attemptNumber = marker.attemptNumber,
            probeKind = marker.probeKind,
            direction = marker.direction,
            messageId = "step18-duplicate-2",
            senderPeerId = "26d73d63a65aa40a",
            applicationSenderId = "Chris",
            receiverPeerId = "3708ee1d5bfd9851",
            messageType = MessageFrameType.GLOBAL_TEXT,
            threadId = "global",
            privateChatId = null,
            marker = marker,
            observedAtMonotonicMillis = 1_500L
        )

        assertFalse(
            automatedDiagnosticsApplicationProbeMatchesExpected(
                observation = observation,
                expectedMarker = marker,
                expectedSenderPeerId = "26d73d63a65aa40a",
                expectedReceiverPeerId = "3708ee1d5bfd9851",
                expectedThreadId = "global",
                expectedPrivateChatId = null,
                expectedMessageId = "global-1787427367106-000000",
                minimumObservedAtMillis = 1_000L
            )
        )
    }

    @Test
    fun applicationProbeFreshnessUsesCurrentAttemptStartBeforeLaterBarrierTimestamp() {
        val marker = AutomatedDiagnosticsApplicationProbeMarker(
            sharedRunId = "run-step18",
            stepId = AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
            attemptNumber = 1,
            probeKind = AutomatedDiagnosticsApplicationProbeKind.GLOBAL,
            direction = AutomatedDiagnosticsApplicationProbeDirection.C2P
        )
        val observation = AutomatedDiagnosticsApplicationProbeObservation(
            sharedRunId = marker.sharedRunId,
            stepId = marker.stepId,
            attemptNumber = marker.attemptNumber,
            probeKind = marker.probeKind,
            direction = marker.direction,
            messageId = "global-before-barrier",
            senderPeerId = "peer-coordinator",
            applicationSenderId = "Chris",
            receiverPeerId = "peer-participant",
            messageType = MessageFrameType.GLOBAL_TEXT,
            threadId = "global",
            privateChatId = null,
            marker = marker,
            observedAtMonotonicMillis = 1_500L
        )

        val minimumObservedAtMillis = automatedDiagnosticsApplicationProbeMinimumObservedAtMillis(
            currentPhaseAttemptStartedAtMillis = 1_000L,
            currentPhaseBarrierEstablishedAtMillis = 2_000L,
            fallbackStartedAtMillis = 2_500L
        )

        assertEquals(1_000L, minimumObservedAtMillis)
        assertTrue(
            automatedDiagnosticsApplicationProbeMatchesExpected(
                observation = observation,
                expectedMarker = marker,
                expectedSenderPeerId = "peer-coordinator",
                expectedReceiverPeerId = "peer-participant",
                expectedThreadId = "global",
                expectedPrivateChatId = null,
                minimumObservedAtMillis = minimumObservedAtMillis
            )
        )
    }

    @Test
    fun applicationProbeMatchingRejectsPreviousAttemptObservation() {
        val observedMarker = AutomatedDiagnosticsApplicationProbeMarker(
            sharedRunId = "run-step18",
            stepId = AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
            attemptNumber = 1,
            probeKind = AutomatedDiagnosticsApplicationProbeKind.GLOBAL,
            direction = AutomatedDiagnosticsApplicationProbeDirection.C2P
        )
        val expectedMarker = observedMarker.copy(attemptNumber = 2)
        val observation = AutomatedDiagnosticsApplicationProbeObservation(
            sharedRunId = observedMarker.sharedRunId,
            stepId = observedMarker.stepId,
            attemptNumber = observedMarker.attemptNumber,
            probeKind = observedMarker.probeKind,
            direction = observedMarker.direction,
            messageId = "global-attempt-1",
            senderPeerId = "peer-coordinator",
            applicationSenderId = "Chris",
            receiverPeerId = "peer-participant",
            messageType = MessageFrameType.GLOBAL_TEXT,
            threadId = "global",
            privateChatId = null,
            marker = observedMarker,
            observedAtMonotonicMillis = 1_500L
        )

        assertFalse(
            automatedDiagnosticsApplicationProbeMatchesExpected(
                observation = observation,
                expectedMarker = expectedMarker,
                expectedSenderPeerId = "peer-coordinator",
                expectedReceiverPeerId = "peer-participant",
                expectedThreadId = "global",
                expectedPrivateChatId = null,
                minimumObservedAtMillis = 1_000L
            )
        )
    }

    @Test
    fun applicationProbeMatchingRejectsObservationBeforeCurrentAttemptStart() {
        val marker = AutomatedDiagnosticsApplicationProbeMarker(
            sharedRunId = "run-step18",
            stepId = AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
            attemptNumber = 1,
            probeKind = AutomatedDiagnosticsApplicationProbeKind.GLOBAL,
            direction = AutomatedDiagnosticsApplicationProbeDirection.C2P
        )
        val observation = AutomatedDiagnosticsApplicationProbeObservation(
            sharedRunId = marker.sharedRunId,
            stepId = marker.stepId,
            attemptNumber = marker.attemptNumber,
            probeKind = marker.probeKind,
            direction = marker.direction,
            messageId = "global-too-early",
            senderPeerId = "peer-coordinator",
            applicationSenderId = "Chris",
            receiverPeerId = "peer-participant",
            messageType = MessageFrameType.GLOBAL_TEXT,
            threadId = "global",
            privateChatId = null,
            marker = marker,
            observedAtMonotonicMillis = 999L
        )

        val minimumObservedAtMillis = automatedDiagnosticsApplicationProbeMinimumObservedAtMillis(
            currentPhaseAttemptStartedAtMillis = 1_000L,
            currentPhaseBarrierEstablishedAtMillis = 2_000L,
            fallbackStartedAtMillis = 2_500L
        )

        assertFalse(
            automatedDiagnosticsApplicationProbeMatchesExpected(
                observation = observation,
                expectedMarker = marker,
                expectedSenderPeerId = "peer-coordinator",
                expectedReceiverPeerId = "peer-participant",
                expectedThreadId = "global",
                expectedPrivateChatId = null,
                minimumObservedAtMillis = minimumObservedAtMillis
            )
        )
    }

    @Test
    fun applicationProbeReceiverFrameStatusUsesCorrelationUnavailableUntilExactGroupIsKnown() {
        val matchingEvents = listOf(
            AutomatedDiagnosticsApplicationProbeTransportReceiveEvent(
                groupId = 58_066,
                sourceDeviceAddress = null,
                observedAtMonotonicMillis = 1_500L,
                observedAtWallClockMillis = 1_716_400_900L,
                transportResultKind = "Buffered",
                receivedChunks = 3,
                expectedChunks = 18
            )
        )

        assertEquals(
            "CORRELATION_UNAVAILABLE",
            automatedDiagnosticsApplicationProbeReceiverFrameStatus(
                expectedGroupId = null,
                matchingEvents = matchingEvents
            )
        )
        assertEquals(
            listOf(
                AutomatedDiagnosticsRawBleGroupSummary(
                    groupId = 58_066,
                    receivedChunks = 3,
                    expectedChunks = 18,
                    latestTransportResultKind = "Buffered",
                    latestObservedAtMonotonicMillis = 1_500L
                )
            ),
            automatedDiagnosticsRawBleGroupSummaries(
                events = matchingEvents,
                minimumObservedAtMillis = 1_000L
            )
        )
        assertEquals(
            "58066: 3/18",
            automatedDiagnosticsRawBleGroupSummaryText(
                automatedDiagnosticsRawBleGroupSummaries(
                    events = matchingEvents,
                    minimumObservedAtMillis = 1_000L
                )
            )
        )
    }

    @Test
    fun applicationProbeMatchingTransportEventsIgnoreDifferentGroupIdsAndPreviousWindows() {
        val events = listOf(
            AutomatedDiagnosticsApplicationProbeTransportReceiveEvent(
                groupId = 58_066,
                sourceDeviceAddress = null,
                observedAtMonotonicMillis = 900L,
                observedAtWallClockMillis = 1_716_400_901L,
                transportResultKind = "Buffered",
                receivedChunks = 1,
                expectedChunks = 18
            ),
            AutomatedDiagnosticsApplicationProbeTransportReceiveEvent(
                groupId = 12_345,
                sourceDeviceAddress = null,
                observedAtMonotonicMillis = 1_300L,
                observedAtWallClockMillis = 1_716_400_902L,
                transportResultKind = "Buffered",
                receivedChunks = 7,
                expectedChunks = 77
            ),
            AutomatedDiagnosticsApplicationProbeTransportReceiveEvent(
                groupId = 58_066,
                sourceDeviceAddress = null,
                observedAtMonotonicMillis = 1_600L,
                observedAtWallClockMillis = 1_716_400_903L,
                transportResultKind = "Buffered",
                receivedChunks = 10,
                expectedChunks = 18
            )
        )

        val matchingEvents = automatedDiagnosticsApplicationProbeMatchingTransportEvents(
            events = events,
            minimumObservedAtMillis = 1_000L,
            expectedGroupId = 58_066
        )

        assertEquals(1, matchingEvents.size)
        assertEquals(58_066, matchingEvents.single().groupId)
        assertEquals(
            "PARTIAL_FRAME_10_OF_18",
            automatedDiagnosticsApplicationProbeReceiverFrameStatus(
                expectedGroupId = 58_066,
                matchingEvents = matchingEvents
            )
        )
        assertEquals(
            10,
            automatedDiagnosticsApplicationProbeMatchingChunkCount(
                matchingEvents = matchingEvents,
                expectedChunkCount = 18
            )
        )
    }

    @Test
    fun applicationProbeExactGroupStatusDistinguishesMissingAndCompleteMatches() {
        val processedEvent = AutomatedDiagnosticsApplicationProbeTransportReceiveEvent(
            groupId = 58_066,
            sourceDeviceAddress = null,
            observedAtMonotonicMillis = 1_700L,
            observedAtWallClockMillis = 1_716_400_904L,
            transportResultKind = "Processed",
            processingResultKind = "Received"
        )

        assertEquals(
            "NO_MATCHING_CHUNK_SEEN",
            automatedDiagnosticsApplicationProbeReceiverFrameStatus(
                expectedGroupId = 58_066,
                matchingEvents = emptyList()
            )
        )
        assertEquals(
            "COMPLETE_FRAME_SEEN",
            automatedDiagnosticsApplicationProbeReceiverFrameStatus(
                expectedGroupId = 58_066,
                matchingEvents = listOf(processedEvent)
            )
        )
        assertEquals(
            18,
            automatedDiagnosticsApplicationProbeMatchingChunkCount(
                matchingEvents = listOf(processedEvent),
                expectedChunkCount = 18
            )
        )
    }

    @Test
    fun rawGroupSummaryTextPrefersCompleteWhenProcessedEventFollowsBufferedSnapshot() {
        val groupEvents = listOf(
            AutomatedDiagnosticsApplicationProbeTransportReceiveEvent(
                groupId = 29_942,
                sourceDeviceAddress = null,
                observedAtMonotonicMillis = 1_100L,
                observedAtWallClockMillis = 1_716_400_901L,
                transportResultKind = "Buffered",
                receivedChunks = 83,
                expectedChunks = 84
            ),
            AutomatedDiagnosticsApplicationProbeTransportReceiveEvent(
                groupId = 29_942,
                sourceDeviceAddress = null,
                observedAtMonotonicMillis = 1_101L,
                observedAtWallClockMillis = 1_716_400_902L,
                transportResultKind = "Processed",
                processingResultKind = "Received",
                receivedChunks = 83,
                expectedChunks = 84
            )
        )

        val summaries = automatedDiagnosticsRawBleGroupSummaries(
            events = groupEvents,
            minimumObservedAtMillis = 1_000L
        )

        assertEquals(
            listOf(
                AutomatedDiagnosticsRawBleGroupSummary(
                    groupId = 29_942,
                    receivedChunks = 83,
                    expectedChunks = 84,
                    latestTransportResultKind = "Processed",
                    latestObservedAtMonotonicMillis = 1_101L,
                    completeFrameSeen = true
                )
            ),
            summaries
        )
        assertEquals("29942: COMPLETE", automatedDiagnosticsRawBleGroupSummaryText(summaries))
    }

    @Test
    fun receiverUsesAcceptedRemoteSenderDescriptorInsteadOfLocalTraceForStepEighteenGlobalProbe() {
        val sharedRun = AutomatedDiagnosticsSharedRun(
            runId = "diag-ed034fc06675f6a4113a3cc4",
            coordinatorPeerId = "26d73d63a65aa40a",
            participantPeerId = "3708ee1d5bfd9851",
            sessionAssociationId = "cd80c82be42ea2678519148b524644a3",
            createdAtMillis = 1_717_427_367_106L,
            expiresAtMillis = 1_717_427_427_106L
        )
        val marker = AutomatedDiagnosticsApplicationProbeMarker(
            sharedRunId = sharedRun.runId,
            stepId = AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
            attemptNumber = 1,
            probeKind = AutomatedDiagnosticsApplicationProbeKind.GLOBAL,
            direction = AutomatedDiagnosticsApplicationProbeDirection.C2P
        )
        val participantEnvironment = FakePhaseOneEnvironment().apply {
            localPeerId = sharedRun.participantPeerId
            latestAutomatedDiagnosticsPhaseSignalsByStep = mapOf(
                AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE to AutomatedDiagnosticsPhaseSignal(
                    sharedRun = sharedRun,
                    peerId = sharedRun.coordinatorPeerId,
                    expectedRemotePeerId = sharedRun.participantPeerId,
                    stepId = AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
                    phaseState = AutomatedDiagnosticsPhaseState.RUNNING,
                    attemptNumber = 1,
                    applicationProbeDescriptors = listOf(
                        AutomatedDiagnosticsPhaseApplicationProbeDescriptor(
                            probeKind = AutomatedDiagnosticsApplicationProbeKind.GLOBAL,
                            messageId = "global-1787433361222-000000",
                            transportStatus = "queued-active:${sharedRun.participantPeerId}",
                            localBleTransportResult = "QueuedLocally",
                            expectedTransportGroupId = 9_149,
                            expectedChunkCount = 18,
                            frameByteCount = 172,
                            senderChunksQueued = 18,
                            senderChunksWriteAttempted = 18,
                            senderLastLocalWriteResult = "QueuedLocally"
                        )
                    ),
                    createdAtMillis = 1_717_427_367_300L,
                    expiresAtMillis = 1_717_427_427_300L
                )
            )
            recordBleTransportLocalSendTrace(
                BleTransportLocalSendTrace(
                    messageId = "global-1787433361222-000000",
                    targetPeerId = "unrelated-target",
                    groupId = 13_446,
                    encodedPayloadByteCount = 171,
                    chunkCount = 18,
                    chunkPayloadSizes = List(18) { 9 },
                    frameEncodedSizes = List(18) { 12 },
                    chunksQueued = 0,
                    chunksWriteAttempted = 1,
                    lastLocalWriteResult = "NotAvailable"
                )
            )
        }

        val snapshot = participantEnvironment.createBindings(
            clock = FakeMonotonicClock(),
            scope = CoroutineScope(EmptyCoroutineContext)
        ).snapshot()

        val descriptor = automatedDiagnosticsAuthoritativePhaseApplicationProbeDescriptorOrNull(
            snapshot = snapshot,
            expectedMarker = marker,
            expectedSenderPeerId = sharedRun.coordinatorPeerId,
            expectedReceiverPeerId = sharedRun.participantPeerId,
            localPeerId = participantEnvironment.localPeerId
        )

        assertNotNull(descriptor)
        assertEquals("global-1787433361222-000000", descriptor?.messageId)
        assertEquals(9_149, descriptor?.expectedTransportGroupId)
        assertEquals(172, descriptor?.frameByteCount)
        assertEquals(18, descriptor?.expectedChunkCount)
        assertEquals(18, descriptor?.senderChunksQueued)
        assertEquals(18, descriptor?.senderChunksWriteAttempted)
        assertEquals("QueuedLocally", descriptor?.senderLastLocalWriteResult)
    }

    @Test
    fun receiverWithoutAcceptedRemoteDescriptorKeepsSenderFactsUnavailable() {
        val marker = AutomatedDiagnosticsApplicationProbeMarker(
            sharedRunId = "diag-ed034fc06675f6a4113a3cc4",
            stepId = AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
            attemptNumber = 1,
            probeKind = AutomatedDiagnosticsApplicationProbeKind.GLOBAL,
            direction = AutomatedDiagnosticsApplicationProbeDirection.C2P
        )
        val participantEnvironment = FakePhaseOneEnvironment().apply {
            localPeerId = "3708ee1d5bfd9851"
            recordBleTransportLocalSendTrace(
                BleTransportLocalSendTrace(
                    messageId = "global-1787427367106-000000",
                    targetPeerId = "26d73d63a65aa40a",
                    groupId = 13_446,
                    encodedPayloadByteCount = 171,
                    chunkCount = 18,
                    chunkPayloadSizes = List(18) { 9 },
                    frameEncodedSizes = List(18) { 12 },
                    chunksQueued = 0,
                    chunksWriteAttempted = 1,
                    lastLocalWriteResult = "NotAvailable"
                )
            )
        }
        val snapshot = participantEnvironment.createBindings(
            clock = FakeMonotonicClock(),
            scope = CoroutineScope(EmptyCoroutineContext)
        ).snapshot()

        val descriptor = automatedDiagnosticsAuthoritativePhaseApplicationProbeDescriptorOrNull(
            snapshot = snapshot,
            expectedMarker = marker,
            expectedSenderPeerId = "26d73d63a65aa40a",
            expectedReceiverPeerId = participantEnvironment.localPeerId,
            localPeerId = participantEnvironment.localPeerId
        )

        assertNull(descriptor)
        assertEquals(
            "CORRELATION_UNAVAILABLE",
            automatedDiagnosticsApplicationProbeReceiverFrameStatus(
                expectedGroupId = descriptor?.expectedTransportGroupId,
                matchingEvents = automatedDiagnosticsApplicationProbeMatchingTransportEvents(
                    events = emptyList(),
                    minimumObservedAtMillis = 0L,
                    expectedGroupId = descriptor?.expectedTransportGroupId
                )
            )
        )
    }

    @Test
    fun receiverCorrelationUsesAuthoritativeRemoteGroupIdAgainstRawGroups() {
        val rawEvents = listOf(
            AutomatedDiagnosticsApplicationProbeTransportReceiveEvent(
                groupId = 4_036,
                sourceDeviceAddress = null,
                observedAtMonotonicMillis = 1_000L,
                transportResultKind = "Buffered",
                receivedChunks = 62,
                expectedChunks = 80
            ),
            AutomatedDiagnosticsApplicationProbeTransportReceiveEvent(
                groupId = 33_166,
                sourceDeviceAddress = null,
                observedAtMonotonicMillis = 1_200L,
                transportResultKind = "Buffered",
                receivedChunks = 79,
                expectedChunks = 80
            )
        )

        val matchingEvents = automatedDiagnosticsApplicationProbeMatchingTransportEvents(
            events = rawEvents,
            minimumObservedAtMillis = 0L,
            expectedGroupId = 44_577
        )

        assertEquals(0, automatedDiagnosticsApplicationProbeMatchingChunkCount(matchingEvents, 18))
        assertEquals(
            "NO_MATCHING_CHUNK_SEEN",
            automatedDiagnosticsApplicationProbeReceiverFrameStatus(
                expectedGroupId = 44_577,
                matchingEvents = matchingEvents
            )
        )
    }

    @Test
    fun receiverCorrelationReportsPartialFrameForAuthoritativeRemoteGroup() {
        val matchingEvents = automatedDiagnosticsApplicationProbeMatchingTransportEvents(
            events = listOf(
                AutomatedDiagnosticsApplicationProbeTransportReceiveEvent(
                    groupId = 44_577,
                    sourceDeviceAddress = null,
                    observedAtMonotonicMillis = 1_000L,
                    transportResultKind = "Buffered",
                    receivedChunks = 7,
                    expectedChunks = 18
                )
            ),
            minimumObservedAtMillis = 0L,
            expectedGroupId = 44_577
        )

        assertEquals(
            "PARTIAL_FRAME_7_OF_18",
            automatedDiagnosticsApplicationProbeReceiverFrameStatus(
                expectedGroupId = 44_577,
                matchingEvents = matchingEvents
            )
        )
    }

    @Test
    fun receiverCorrelationReportsCompleteFrameForAuthoritativeRemoteGroup() {
        val matchingEvents = automatedDiagnosticsApplicationProbeMatchingTransportEvents(
            events = listOf(
                AutomatedDiagnosticsApplicationProbeTransportReceiveEvent(
                    groupId = 44_577,
                    sourceDeviceAddress = null,
                    observedAtMonotonicMillis = 1_000L,
                    transportResultKind = "Processed",
                    processingResultKind = "Received"
                )
            ),
            minimumObservedAtMillis = 0L,
            expectedGroupId = 44_577
        )

        assertEquals(
            "COMPLETE_FRAME_SEEN",
            automatedDiagnosticsApplicationProbeReceiverFrameStatus(
                expectedGroupId = 44_577,
                matchingEvents = matchingEvents
            )
        )
    }

    @Test
    fun receiveDiagnosticMatchingRejectsSameStepDifferentMessageId() {
        val marker = AutomatedDiagnosticsApplicationProbeMarker(
            sharedRunId = "diag-ed034fc06675f6a4113a3cc4",
            stepId = AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
            attemptNumber = 1,
            probeKind = AutomatedDiagnosticsApplicationProbeKind.GLOBAL,
            direction = AutomatedDiagnosticsApplicationProbeDirection.C2P
        )
        val diagnostic = AutomatedDiagnosticsApplicationProbeReceiveDiagnostic(
            sharedRunId = marker.sharedRunId,
            stepId = marker.stepId,
            attemptNumber = marker.attemptNumber,
            probeKind = marker.probeKind,
            direction = marker.direction,
            messageId = "global-other-message",
            applicationSenderId = "26d73d63a65aa40a",
            receiverPeerId = "3708ee1d5bfd9851",
            messageType = MessageFrameType.GLOBAL_TEXT,
            threadId = "global",
            privateChatId = null,
            marker = marker,
            sourceResolution = AutomatedDiagnosticsApplicationProbeSourceResolution(
                sourceDeviceAddress = "71:E5:92:2E:CB:CC",
                exactAddressSourcePeerId = null,
                diagnosticsAssociatedSourcePeerId = null,
                resolvedSourcePeerId = null,
                resolutionSource =
                    AutomatedDiagnosticsApplicationProbeSourceResolutionSource.UNRESOLVED,
                associationLookupHit = true,
                storedAssociationPeerId = "26d73d63a65aa40a",
                storedAssociationSharedRunId = marker.sharedRunId,
                storedAssociationStepId = AutomatedDiagnosticStepId.PRIVATE_ENCRYPTED_MESSAGE_PROBE,
                storedAssociationAttemptNumber = 1,
                storedAssociationExpectedRemotePeerId = "3708ee1d5bfd9851",
                selectedSecurePeerId = null,
                diagnosticsAssociationOutcome =
                    AutomatedDiagnosticsApplicationProbeAssociationOutcome.ASSOCIATION_WRONG_STEP,
                selectedSecurePeerGate =
                    AutomatedDiagnosticsApplicationProbeSelectedSecurePeerGate.MATCH
            ),
            observedAtMonotonicMillis = 1_500L
        )

        assertFalse(
            automatedDiagnosticsApplicationProbeReceiveDiagnosticMatchesExpected(
                diagnostic = diagnostic,
                expectedMarker = marker,
                expectedReceiverPeerId = "3708ee1d5bfd9851",
                expectedThreadId = "global",
                expectedPrivateChatId = null,
                expectedMessageId = "global-1787427367106-000000",
                minimumObservedAtMillis = 1_000L
            )
        )
    }

    @Test
    fun receiveDiagnosticMatchingRejectsSameStepDifferentTransportGroupId() {
        val marker = AutomatedDiagnosticsApplicationProbeMarker(
            sharedRunId = "diag-de348be1fa344e754383484e",
            stepId = AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
            attemptNumber = 1,
            probeKind = AutomatedDiagnosticsApplicationProbeKind.GLOBAL,
            direction = AutomatedDiagnosticsApplicationProbeDirection.C2P
        )
        val diagnostic = AutomatedDiagnosticsApplicationProbeReceiveDiagnostic(
            sharedRunId = marker.sharedRunId,
            stepId = marker.stepId,
            attemptNumber = marker.attemptNumber,
            probeKind = marker.probeKind,
            direction = marker.direction,
            messageId = "global-1787433361222-000000",
            applicationSenderId = "26d73d63a65aa40a",
            receiverPeerId = "3708ee1d5bfd9851",
            messageType = MessageFrameType.GLOBAL_TEXT,
            threadId = "global",
            privateChatId = null,
            transportGroupId = 29_942,
            marker = marker,
            sourceResolution = AutomatedDiagnosticsApplicationProbeSourceResolution(
                sourceDeviceAddress = "4F:43:99:C6:F7:2E",
                exactAddressSourcePeerId = null,
                diagnosticsAssociatedSourcePeerId = null,
                resolvedSourcePeerId = null,
                resolutionSource =
                    AutomatedDiagnosticsApplicationProbeSourceResolutionSource.UNRESOLVED,
                associationLookupHit = true,
                storedAssociationPeerId = "26d73d63a65aa40a",
                storedAssociationSharedRunId = marker.sharedRunId,
                storedAssociationStepId = AutomatedDiagnosticStepId.PRIVATE_ENCRYPTED_MESSAGE_PROBE,
                storedAssociationAttemptNumber = 1,
                storedAssociationExpectedRemotePeerId = "3708ee1d5bfd9851",
                selectedSecurePeerId = null,
                diagnosticsAssociationOutcome =
                    AutomatedDiagnosticsApplicationProbeAssociationOutcome.ASSOCIATION_WRONG_STEP,
                selectedSecurePeerGate =
                    AutomatedDiagnosticsApplicationProbeSelectedSecurePeerGate.MATCH
            ),
            observedAtMonotonicMillis = 1_500L
        )

        assertFalse(
            automatedDiagnosticsApplicationProbeReceiveDiagnosticMatchesExpected(
                diagnostic = diagnostic,
                expectedMarker = marker,
                expectedReceiverPeerId = "3708ee1d5bfd9851",
                expectedThreadId = "global",
                expectedPrivateChatId = null,
                expectedMessageId = "global-1787433361222-000000",
                expectedTransportGroupId = 9_149,
                minimumObservedAtMillis = 1_000L
            )
        )
    }

    @Test
    fun receiveDiagnosticMatchingRejectsWrongAttemptForSameMessageId() {
        val expectedMarker = AutomatedDiagnosticsApplicationProbeMarker(
            sharedRunId = "diag-de348be1fa344e754383484e",
            stepId = AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
            attemptNumber = 1,
            probeKind = AutomatedDiagnosticsApplicationProbeKind.GLOBAL,
            direction = AutomatedDiagnosticsApplicationProbeDirection.C2P
        )
        val diagnosticMarker = expectedMarker.copy(attemptNumber = 2)
        val diagnostic = AutomatedDiagnosticsApplicationProbeReceiveDiagnostic(
            sharedRunId = diagnosticMarker.sharedRunId,
            stepId = diagnosticMarker.stepId,
            attemptNumber = diagnosticMarker.attemptNumber,
            probeKind = diagnosticMarker.probeKind,
            direction = diagnosticMarker.direction,
            messageId = "global-1787433361222-000000",
            applicationSenderId = "26d73d63a65aa40a",
            receiverPeerId = "3708ee1d5bfd9851",
            messageType = MessageFrameType.GLOBAL_TEXT,
            threadId = "global",
            privateChatId = null,
            transportGroupId = 9_149,
            marker = diagnosticMarker,
            sourceResolution = AutomatedDiagnosticsApplicationProbeSourceResolution(
                sourceDeviceAddress = null,
                exactAddressSourcePeerId = null,
                diagnosticsAssociatedSourcePeerId = null,
                resolvedSourcePeerId = null,
                resolutionSource =
                    AutomatedDiagnosticsApplicationProbeSourceResolutionSource.UNRESOLVED,
                associationLookupHit = false,
                storedAssociationPeerId = null,
                storedAssociationSharedRunId = null,
                storedAssociationStepId = null,
                storedAssociationAttemptNumber = null,
                storedAssociationExpectedRemotePeerId = null,
                selectedSecurePeerId = null,
                diagnosticsAssociationOutcome = null,
                selectedSecurePeerGate =
                    AutomatedDiagnosticsApplicationProbeSelectedSecurePeerGate
                        .SELECTED_SECURE_PEER_UNAVAILABLE
            ),
            observedAtMonotonicMillis = 1_500L
        )

        assertFalse(
            automatedDiagnosticsApplicationProbeReceiveDiagnosticMatchesExpected(
                diagnostic = diagnostic,
                expectedMarker = expectedMarker,
                expectedReceiverPeerId = "3708ee1d5bfd9851",
                expectedThreadId = "global",
                expectedPrivateChatId = null,
                expectedMessageId = "global-1787433361222-000000",
                expectedTransportGroupId = 9_149,
                minimumObservedAtMillis = 1_000L
            )
        )
    }

    @Test
    fun receiveDiagnosticMatchingRejectsWrongDirectionForSameMessageId() {
        val expectedMarker = AutomatedDiagnosticsApplicationProbeMarker(
            sharedRunId = "diag-de348be1fa344e754383484e",
            stepId = AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
            attemptNumber = 1,
            probeKind = AutomatedDiagnosticsApplicationProbeKind.GLOBAL,
            direction = AutomatedDiagnosticsApplicationProbeDirection.C2P
        )
        val diagnosticMarker = expectedMarker.copy(
            direction = AutomatedDiagnosticsApplicationProbeDirection.P2C
        )
        val diagnostic = AutomatedDiagnosticsApplicationProbeReceiveDiagnostic(
            sharedRunId = diagnosticMarker.sharedRunId,
            stepId = diagnosticMarker.stepId,
            attemptNumber = diagnosticMarker.attemptNumber,
            probeKind = diagnosticMarker.probeKind,
            direction = diagnosticMarker.direction,
            messageId = "global-1787433361222-000000",
            applicationSenderId = "3708ee1d5bfd9851",
            receiverPeerId = "26d73d63a65aa40a",
            messageType = MessageFrameType.GLOBAL_TEXT,
            threadId = "global",
            privateChatId = null,
            transportGroupId = 9_149,
            marker = diagnosticMarker,
            sourceResolution = AutomatedDiagnosticsApplicationProbeSourceResolution(
                sourceDeviceAddress = null,
                exactAddressSourcePeerId = null,
                diagnosticsAssociatedSourcePeerId = null,
                resolvedSourcePeerId = null,
                resolutionSource =
                    AutomatedDiagnosticsApplicationProbeSourceResolutionSource.UNRESOLVED,
                associationLookupHit = false,
                storedAssociationPeerId = null,
                storedAssociationSharedRunId = null,
                storedAssociationStepId = null,
                storedAssociationAttemptNumber = null,
                storedAssociationExpectedRemotePeerId = null,
                selectedSecurePeerId = null,
                diagnosticsAssociationOutcome = null,
                selectedSecurePeerGate =
                    AutomatedDiagnosticsApplicationProbeSelectedSecurePeerGate
                        .SELECTED_SECURE_PEER_UNAVAILABLE
            ),
            observedAtMonotonicMillis = 1_500L
        )

        assertFalse(
            automatedDiagnosticsApplicationProbeReceiveDiagnosticMatchesExpected(
                diagnostic = diagnostic,
                expectedMarker = expectedMarker,
                expectedReceiverPeerId = "3708ee1d5bfd9851",
                expectedThreadId = "global",
                expectedPrivateChatId = null,
                expectedMessageId = "global-1787433361222-000000",
                expectedTransportGroupId = 9_149,
                minimumObservedAtMillis = 1_000L
            )
        )
    }

    @Test
    fun terminalRemotePhaseStatePreservesAuthoritativeSenderDescriptorOnReceiver() {
        val sharedRun = AutomatedDiagnosticsSharedRun(
            runId = "diag-ed034fc06675f6a4113a3cc4",
            coordinatorPeerId = "26d73d63a65aa40a",
            participantPeerId = "3708ee1d5bfd9851",
            sessionAssociationId = "cd80c82be42ea2678519148b524644a3",
            createdAtMillis = 1L,
            expiresAtMillis = 2L
        )
        val marker = AutomatedDiagnosticsApplicationProbeMarker(
            sharedRunId = sharedRun.runId,
            stepId = AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
            attemptNumber = 1,
            probeKind = AutomatedDiagnosticsApplicationProbeKind.GLOBAL,
            direction = AutomatedDiagnosticsApplicationProbeDirection.C2P
        )
        val participantEnvironment = FakePhaseOneEnvironment().apply {
            localPeerId = sharedRun.participantPeerId
            latestAutomatedDiagnosticsPhaseSignalsByStep = mapOf(
                AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE to AutomatedDiagnosticsPhaseSignal(
                    sharedRun = sharedRun,
                    peerId = sharedRun.coordinatorPeerId,
                    expectedRemotePeerId = sharedRun.participantPeerId,
                    stepId = AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
                    phaseState = AutomatedDiagnosticsPhaseState.BLOCKED,
                    attemptNumber = 1,
                    applicationProbeDescriptors = listOf(
                        AutomatedDiagnosticsPhaseApplicationProbeDescriptor(
                            probeKind = AutomatedDiagnosticsApplicationProbeKind.GLOBAL,
                            messageId = "global-1787427367106-000000",
                            transportStatus = "queued-active:${sharedRun.participantPeerId}",
                            localBleTransportResult = "QueuedLocally",
                            expectedTransportGroupId = 44_577,
                            expectedChunkCount = 18,
                            frameByteCount = 172,
                            senderChunksQueued = 18,
                            senderChunksWriteAttempted = 18,
                            senderLastLocalWriteResult = "QueuedLocally"
                        )
                    ),
                    createdAtMillis = 10L,
                    expiresAtMillis = 20L
                )
            )
            recordBleTransportLocalSendTrace(
                BleTransportLocalSendTrace(
                    messageId = "global-1787427367106-000000",
                    targetPeerId = "26d73d63a65aa40a",
                    groupId = 13_446,
                    encodedPayloadByteCount = 171,
                    chunkCount = 18,
                    chunkPayloadSizes = List(18) { 9 },
                    frameEncodedSizes = List(18) { 12 },
                    chunksQueued = 0,
                    chunksWriteAttempted = 1,
                    lastLocalWriteResult = "NotAvailable"
                )
            )
        }
        val snapshot = participantEnvironment.createBindings(
            clock = FakeMonotonicClock(),
            scope = CoroutineScope(EmptyCoroutineContext)
        ).snapshot()

        val descriptor = automatedDiagnosticsAuthoritativePhaseApplicationProbeDescriptorOrNull(
            snapshot = snapshot,
            expectedMarker = marker,
            expectedSenderPeerId = sharedRun.coordinatorPeerId,
            expectedReceiverPeerId = sharedRun.participantPeerId,
            localPeerId = participantEnvironment.localPeerId
        )

        assertEquals(44_577, descriptor?.expectedTransportGroupId)
        assertEquals(172, descriptor?.frameByteCount)
        assertEquals(18, descriptor?.senderChunksQueued)
        assertEquals("QueuedLocally", descriptor?.senderLastLocalWriteResult)
    }

    @Test
    fun reverseDirectionStepUsesParticipantLocalSenderTraceAsAuthoritative() {
        val participantEnvironment = FakePhaseOneEnvironment().apply {
            localPeerId = "3708ee1d5bfd9851"
            recordBleTransportLocalSendTrace(
                BleTransportLocalSendTrace(
                    messageId = "global-1787427368106-000000",
                    targetPeerId = "26d73d63a65aa40a",
                    groupId = 55_777,
                    encodedPayloadByteCount = 172,
                    chunkCount = 18,
                    chunkPayloadSizes = List(18) { 9 },
                    frameEncodedSizes = List(18) { 12 },
                    chunksQueued = 18,
                    chunksWriteAttempted = 18,
                    lastLocalWriteResult = "QueuedLocally"
                )
            )
        }
        val marker = AutomatedDiagnosticsApplicationProbeMarker(
            sharedRunId = "diag-ed034fc06675f6a4113a3cc4",
            stepId = AutomatedDiagnosticStepId.REVERSE_DIRECTION_MESSAGING_PROBE,
            attemptNumber = 1,
            probeKind = AutomatedDiagnosticsApplicationProbeKind.GLOBAL,
            direction = AutomatedDiagnosticsApplicationProbeDirection.P2C
        )
        val snapshot = participantEnvironment.createBindings(
            clock = FakeMonotonicClock(),
            scope = CoroutineScope(EmptyCoroutineContext)
        ).snapshot()

        val descriptor = automatedDiagnosticsAuthoritativePhaseApplicationProbeDescriptorOrNull(
            snapshot = snapshot,
            expectedMarker = marker,
            expectedSenderPeerId = participantEnvironment.localPeerId,
            expectedReceiverPeerId = "26d73d63a65aa40a",
            localPeerId = participantEnvironment.localPeerId,
            localSubmissionMessageId = "global-1787427368106-000000",
            localTransportStatus = "queued-active:26d73d63a65aa40a",
            localBleTransportResult = "QueuedLocally",
            localExpectedReceiverTransportGroupId = 55_777
        )

        assertNotNull(descriptor)
        assertEquals(55_777, descriptor?.expectedTransportGroupId)
        assertEquals(172, descriptor?.frameByteCount)
        assertEquals(18, descriptor?.senderChunksQueued)
        assertEquals("QueuedLocally", descriptor?.senderLastLocalWriteResult)
    }

    @Test
    fun privateProbeReceiverUsesAcceptedRemoteSenderDescriptorInsteadOfLocalTrace() {
        val sharedRun = AutomatedDiagnosticsSharedRun(
            runId = "diag-ed034fc06675f6a4113a3cc4",
            coordinatorPeerId = "26d73d63a65aa40a",
            participantPeerId = "3708ee1d5bfd9851",
            sessionAssociationId = "cd80c82be42ea2678519148b524644a3",
            createdAtMillis = 1L,
            expiresAtMillis = 2L
        )
        val marker = AutomatedDiagnosticsApplicationProbeMarker(
            sharedRunId = sharedRun.runId,
            stepId = AutomatedDiagnosticStepId.PRIVATE_ENCRYPTED_MESSAGE_PROBE,
            attemptNumber = 1,
            probeKind = AutomatedDiagnosticsApplicationProbeKind.PRIVATE,
            direction = AutomatedDiagnosticsApplicationProbeDirection.C2P
        )
        val participantEnvironment = FakePhaseOneEnvironment().apply {
            localPeerId = sharedRun.participantPeerId
            latestAutomatedDiagnosticsPhaseSignalsByStep = mapOf(
                AutomatedDiagnosticStepId.PRIVATE_ENCRYPTED_MESSAGE_PROBE to AutomatedDiagnosticsPhaseSignal(
                    sharedRun = sharedRun,
                    peerId = sharedRun.coordinatorPeerId,
                    expectedRemotePeerId = sharedRun.participantPeerId,
                    stepId = AutomatedDiagnosticStepId.PRIVATE_ENCRYPTED_MESSAGE_PROBE,
                    phaseState = AutomatedDiagnosticsPhaseState.RUNNING,
                    attemptNumber = 1,
                    applicationProbeDescriptors = listOf(
                        AutomatedDiagnosticsPhaseApplicationProbeDescriptor(
                            probeKind = AutomatedDiagnosticsApplicationProbeKind.PRIVATE,
                            messageId = "private-1787427367106-000000",
                            transportStatus = "submitted",
                            localBleTransportResult = "QueuedLocally",
                            expectedTransportGroupId = 44_877,
                            expectedChunkCount = 11,
                            frameByteCount = 196,
                            senderChunksQueued = 11,
                            senderChunksWriteAttempted = 11,
                            senderLastLocalWriteResult = "QueuedLocally"
                        )
                    ),
                    createdAtMillis = 10L,
                    expiresAtMillis = 20L
                )
            )
            recordBleTransportLocalSendTrace(
                BleTransportLocalSendTrace(
                    messageId = "private-1787427367106-000000",
                    targetPeerId = "26d73d63a65aa40a",
                    groupId = 14_446,
                    encodedPayloadByteCount = 181,
                    chunkCount = 11,
                    chunkPayloadSizes = List(11) { 13 },
                    frameEncodedSizes = List(11) { 16 },
                    chunksQueued = 0,
                    chunksWriteAttempted = 1,
                    lastLocalWriteResult = "NotAvailable"
                )
            )
        }
        val snapshot = participantEnvironment.createBindings(
            clock = FakeMonotonicClock(),
            scope = CoroutineScope(EmptyCoroutineContext)
        ).snapshot()

        val descriptor = automatedDiagnosticsAuthoritativePhaseApplicationProbeDescriptorOrNull(
            snapshot = snapshot,
            expectedMarker = marker,
            expectedSenderPeerId = sharedRun.coordinatorPeerId,
            expectedReceiverPeerId = sharedRun.participantPeerId,
            localPeerId = participantEnvironment.localPeerId
        )

        assertEquals(44_877, descriptor?.expectedTransportGroupId)
        assertEquals(196, descriptor?.frameByteCount)
        assertEquals(11, descriptor?.expectedChunkCount)
        assertEquals(11, descriptor?.senderChunksQueued)
        assertEquals(11, descriptor?.senderChunksWriteAttempted)
        assertEquals("QueuedLocally", descriptor?.senderLastLocalWriteResult)
    }

    @Test
    fun laterRunningPhaseStateDescriptorReplacesEarlierReadyDescriptorOnParticipant() {
        val harness = createDefaultPhaseTwoHarness()

        try {
            harness.startBothManualRuns()
            assertTrue(
                harness.advanceUntil(maxSteps = 120) {
                    harness.coordinatorEnvironment.latestAutomatedDiagnosticsRunAnnouncement != null ||
                        harness.coordinatorEnvironment.latestAutomatedDiagnosticsParticipantJoin != null ||
                        harness.participantEnvironment.latestAutomatedDiagnosticsRunAnnouncement != null ||
                        harness.participantEnvironment.latestAutomatedDiagnosticsParticipantJoin != null
                }
            )
            val coordinationTransport = requireNotNull(
                harness.coordinatorEnvironment.coordinationTransport
            )
            val sharedRun = harness.coordinatorSharedRun()
            val readyCreatedAtMillis = harness.coordinatorEnvironment.currentWallClockMillis()
            val readyResult = coordinationTransport.sendPhaseState(
                from = harness.coordinatorEnvironment,
                sharedRun = sharedRun,
                expectedRemotePeerId = harness.participantEnvironment.localPeerId,
                stepId = AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
                phaseState = AutomatedDiagnosticsPhaseState.READY,
                attemptNumber = 1,
                applicationProbeDescriptors = emptyList(),
                createdAtMillis = readyCreatedAtMillis
            )
            assertTrue(readyResult is AutomatedDiagnosticsPhaseStateSendResult.Sent)
            assertTrue(
                harness.participantEnvironment
                    .latestAutomatedDiagnosticsPhaseSignalsByStep[AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE]
                    ?.applicationProbeDescriptors
                    .isNullOrEmpty()
            )

            val updatedDescriptor = AutomatedDiagnosticsPhaseApplicationProbeDescriptor(
                probeKind = AutomatedDiagnosticsApplicationProbeKind.GLOBAL,
                messageId = "global-1786902668726-000000",
                transportStatus = "queued-active:${harness.participantEnvironment.localPeerId}",
                localBleTransportResult = "QueuedLocally",
                expectedTransportGroupId = 58_066,
                expectedChunkCount = 18,
                frameByteCount = 172
            )
            val runningResult = coordinationTransport.sendPhaseState(
                from = harness.coordinatorEnvironment,
                sharedRun = sharedRun,
                expectedRemotePeerId = harness.participantEnvironment.localPeerId,
                stepId = AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
                phaseState = AutomatedDiagnosticsPhaseState.RUNNING,
                attemptNumber = 1,
                applicationProbeDescriptors = listOf(updatedDescriptor),
                createdAtMillis = readyCreatedAtMillis + 1L
            )

            assertTrue(runningResult is AutomatedDiagnosticsPhaseStateSendResult.Sent)
            assertEquals(
                AutomatedDiagnosticsPhaseState.RUNNING,
                harness.participantEnvironment
                    .latestAutomatedDiagnosticsPhaseSignalsByStep[AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE]
                    ?.phaseState
            )
            assertEquals(
                listOf(updatedDescriptor),
                harness.participantEnvironment
                    .latestAutomatedDiagnosticsPhaseSignalsByStep[AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE]
                    ?.applicationProbeDescriptors
            )
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun richerRunningPhaseDescriptorMergesForwardForSameExactProbe() {
        val sharedRun = AutomatedDiagnosticsSharedRun(
            runId = "diag-de348be1fa344e754383484e",
            coordinatorPeerId = "26d73d63a65aa40a",
            participantPeerId = "3708ee1d5bfd9851",
            sessionAssociationId = "c2p-session",
            createdAtMillis = 1L,
            expiresAtMillis = 60_000L
        )
        val partialDescriptor = AutomatedDiagnosticsPhaseApplicationProbeDescriptor(
            probeKind = AutomatedDiagnosticsApplicationProbeKind.GLOBAL,
            messageId = "global-1787433361222-000000",
            transportStatus = "queued-active:${sharedRun.participantPeerId}",
            localBleTransportResult = "QueuedLocally",
            expectedTransportGroupId = 9_149
        )
        val richerDescriptor = partialDescriptor.copy(
            expectedChunkCount = 18,
            frameByteCount = 172,
            senderChunksQueued = 18,
            senderChunksWriteAttempted = 18,
            senderLastLocalWriteResult = "QueuedLocally"
        )
        val merged = mergeAutomatedDiagnosticsPhaseSignal(
            current = AutomatedDiagnosticsPhaseSignal(
                sharedRun = sharedRun,
                peerId = sharedRun.coordinatorPeerId,
                expectedRemotePeerId = sharedRun.participantPeerId,
                stepId = AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
                phaseState = AutomatedDiagnosticsPhaseState.RUNNING,
                attemptNumber = 1,
                applicationProbeDescriptors = listOf(partialDescriptor),
                createdAtMillis = 10L,
                expiresAtMillis = 20L
            ),
            incoming = AutomatedDiagnosticsPhaseSignal(
                sharedRun = sharedRun,
                peerId = sharedRun.coordinatorPeerId,
                expectedRemotePeerId = sharedRun.participantPeerId,
                stepId = AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
                phaseState = AutomatedDiagnosticsPhaseState.RUNNING,
                attemptNumber = 1,
                applicationProbeDescriptors = listOf(richerDescriptor),
                createdAtMillis = 11L,
                expiresAtMillis = 21L
            )
        )

        val mergedDescriptor = merged.applicationProbeDescriptors.single()
        assertEquals(AutomatedDiagnosticsPhaseState.RUNNING, merged.phaseState)
        assertEquals("global-1787433361222-000000", mergedDescriptor.messageId)
        assertEquals(9_149, mergedDescriptor.expectedTransportGroupId)
        assertEquals(172, mergedDescriptor.frameByteCount)
        assertEquals(18, mergedDescriptor.expectedChunkCount)
        assertEquals(18, mergedDescriptor.senderChunksQueued)
        assertEquals(18, mergedDescriptor.senderChunksWriteAttempted)
        assertEquals("QueuedLocally", mergedDescriptor.senderLastLocalWriteResult)
    }

    @Test
    fun olderPartialPhaseDescriptorDoesNotReplaceCurrentRicherDescriptor() {
        val sharedRun = AutomatedDiagnosticsSharedRun(
            runId = "diag-de348be1fa344e754383484e",
            coordinatorPeerId = "26d73d63a65aa40a",
            participantPeerId = "3708ee1d5bfd9851",
            sessionAssociationId = "c2p-session",
            createdAtMillis = 1L,
            expiresAtMillis = 60_000L
        )
        val richerDescriptor = AutomatedDiagnosticsPhaseApplicationProbeDescriptor(
            probeKind = AutomatedDiagnosticsApplicationProbeKind.GLOBAL,
            messageId = "global-1787433361222-000000",
            transportStatus = "queued-active:${sharedRun.participantPeerId}",
            localBleTransportResult = "QueuedLocally",
            expectedTransportGroupId = 9_149,
            expectedChunkCount = 18,
            frameByteCount = 172,
            senderChunksQueued = 18,
            senderChunksWriteAttempted = 18,
            senderLastLocalWriteResult = "QueuedLocally"
        )
        val olderPartialDescriptor = AutomatedDiagnosticsPhaseApplicationProbeDescriptor(
            probeKind = AutomatedDiagnosticsApplicationProbeKind.GLOBAL,
            messageId = "global-1787433361222-000000",
            transportStatus = "queued-active:${sharedRun.participantPeerId}",
            localBleTransportResult = "QueuedLocally",
            expectedTransportGroupId = 9_149
        )
        val merged = mergeAutomatedDiagnosticsPhaseSignal(
            current = AutomatedDiagnosticsPhaseSignal(
                sharedRun = sharedRun,
                peerId = sharedRun.coordinatorPeerId,
                expectedRemotePeerId = sharedRun.participantPeerId,
                stepId = AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
                phaseState = AutomatedDiagnosticsPhaseState.RUNNING,
                attemptNumber = 1,
                applicationProbeDescriptors = listOf(richerDescriptor),
                createdAtMillis = 11L,
                expiresAtMillis = 21L
            ),
            incoming = AutomatedDiagnosticsPhaseSignal(
                sharedRun = sharedRun,
                peerId = sharedRun.coordinatorPeerId,
                expectedRemotePeerId = sharedRun.participantPeerId,
                stepId = AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
                phaseState = AutomatedDiagnosticsPhaseState.RUNNING,
                attemptNumber = 1,
                applicationProbeDescriptors = listOf(olderPartialDescriptor),
                createdAtMillis = 10L,
                expiresAtMillis = 20L
            )
        )

        val mergedDescriptor = merged.applicationProbeDescriptors.single()
        assertEquals(11L, merged.createdAtMillis)
        assertEquals(172, mergedDescriptor.frameByteCount)
        assertEquals(18, mergedDescriptor.expectedChunkCount)
        assertEquals(18, mergedDescriptor.senderChunksQueued)
        assertEquals(18, mergedDescriptor.senderChunksWriteAttempted)
        assertEquals("QueuedLocally", mergedDescriptor.senderLastLocalWriteResult)
    }

    @Test
    fun terminalPhaseStateMergePreservesRicherSenderDescriptorForSameProbe() {
        val sharedRun = AutomatedDiagnosticsSharedRun(
            runId = "diag-de348be1fa344e754383484e",
            coordinatorPeerId = "26d73d63a65aa40a",
            participantPeerId = "3708ee1d5bfd9851",
            sessionAssociationId = "c2p-session",
            createdAtMillis = 1L,
            expiresAtMillis = 60_000L
        )
        val richerDescriptor = AutomatedDiagnosticsPhaseApplicationProbeDescriptor(
            probeKind = AutomatedDiagnosticsApplicationProbeKind.GLOBAL,
            messageId = "global-1787433361222-000000",
            transportStatus = "queued-active:${sharedRun.participantPeerId}",
            localBleTransportResult = "QueuedLocally",
            expectedTransportGroupId = 9_149,
            expectedChunkCount = 18,
            frameByteCount = 172,
            senderChunksQueued = 18,
            senderChunksWriteAttempted = 18,
            senderLastLocalWriteResult = "QueuedLocally"
        )
        val terminalPartialDescriptor = AutomatedDiagnosticsPhaseApplicationProbeDescriptor(
            probeKind = AutomatedDiagnosticsApplicationProbeKind.GLOBAL,
            messageId = "global-1787433361222-000000",
            transportStatus = "queued-active:${sharedRun.participantPeerId}",
            localBleTransportResult = "QueuedLocally",
            expectedTransportGroupId = 9_149
        )
        val merged = mergeAutomatedDiagnosticsPhaseSignal(
            current = AutomatedDiagnosticsPhaseSignal(
                sharedRun = sharedRun,
                peerId = sharedRun.coordinatorPeerId,
                expectedRemotePeerId = sharedRun.participantPeerId,
                stepId = AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
                phaseState = AutomatedDiagnosticsPhaseState.RUNNING,
                attemptNumber = 1,
                applicationProbeDescriptors = listOf(richerDescriptor),
                createdAtMillis = 10L,
                expiresAtMillis = 20L
            ),
            incoming = AutomatedDiagnosticsPhaseSignal(
                sharedRun = sharedRun,
                peerId = sharedRun.coordinatorPeerId,
                expectedRemotePeerId = sharedRun.participantPeerId,
                stepId = AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
                phaseState = AutomatedDiagnosticsPhaseState.BLOCKED,
                attemptNumber = 1,
                applicationProbeDescriptors = listOf(terminalPartialDescriptor),
                createdAtMillis = 11L,
                expiresAtMillis = 21L
            )
        )

        val mergedDescriptor = merged.applicationProbeDescriptors.single()
        assertEquals(AutomatedDiagnosticsPhaseState.BLOCKED, merged.phaseState)
        assertEquals(172, mergedDescriptor.frameByteCount)
        assertEquals(18, mergedDescriptor.expectedChunkCount)
        assertEquals(18, mergedDescriptor.senderChunksQueued)
        assertEquals(18, mergedDescriptor.senderChunksWriteAttempted)
        assertEquals("QueuedLocally", mergedDescriptor.senderLastLocalWriteResult)
    }

    @Test
    fun terminalPhaseStateMergeKeepsPassOverLaterRunningForSameAttempt() {
        val sharedRun = AutomatedDiagnosticsSharedRun(
            runId = "diag-de348be1fa344e754383484e",
            coordinatorPeerId = "26d73d63a65aa40a",
            participantPeerId = "3708ee1d5bfd9851",
            sessionAssociationId = "c2p-session",
            createdAtMillis = 1L,
            expiresAtMillis = 60_000L
        )
        val merged = mergeAutomatedDiagnosticsPhaseSignal(
            current = AutomatedDiagnosticsPhaseSignal(
                sharedRun = sharedRun,
                peerId = sharedRun.participantPeerId,
                expectedRemotePeerId = sharedRun.coordinatorPeerId,
                stepId = AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
                phaseState = AutomatedDiagnosticsPhaseState.PASS,
                attemptNumber = 1,
                createdAtMillis = 10L,
                expiresAtMillis = 20L
            ),
            incoming = AutomatedDiagnosticsPhaseSignal(
                sharedRun = sharedRun,
                peerId = sharedRun.participantPeerId,
                expectedRemotePeerId = sharedRun.coordinatorPeerId,
                stepId = AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
                phaseState = AutomatedDiagnosticsPhaseState.RUNNING,
                attemptNumber = 1,
                createdAtMillis = 11L,
                expiresAtMillis = 21L
            )
        )

        assertEquals(AutomatedDiagnosticsPhaseState.PASS, merged.phaseState)
        assertEquals(10L, merged.createdAtMillis)
    }

    @Test
    fun terminalPhaseStateMergeKeepsPassOverLaterReadyForSameAttempt() {
        val sharedRun = AutomatedDiagnosticsSharedRun(
            runId = "diag-de348be1fa344e754383484e",
            coordinatorPeerId = "26d73d63a65aa40a",
            participantPeerId = "3708ee1d5bfd9851",
            sessionAssociationId = "c2p-session",
            createdAtMillis = 1L,
            expiresAtMillis = 60_000L
        )
        val merged = mergeAutomatedDiagnosticsPhaseSignal(
            current = AutomatedDiagnosticsPhaseSignal(
                sharedRun = sharedRun,
                peerId = sharedRun.participantPeerId,
                expectedRemotePeerId = sharedRun.coordinatorPeerId,
                stepId = AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
                phaseState = AutomatedDiagnosticsPhaseState.PASS,
                attemptNumber = 1,
                createdAtMillis = 10L,
                expiresAtMillis = 20L
            ),
            incoming = AutomatedDiagnosticsPhaseSignal(
                sharedRun = sharedRun,
                peerId = sharedRun.participantPeerId,
                expectedRemotePeerId = sharedRun.coordinatorPeerId,
                stepId = AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
                phaseState = AutomatedDiagnosticsPhaseState.READY,
                attemptNumber = 1,
                createdAtMillis = 11L,
                expiresAtMillis = 21L
            )
        )

        assertEquals(AutomatedDiagnosticsPhaseState.PASS, merged.phaseState)
        assertEquals(10L, merged.createdAtMillis)
    }

    @Test
    fun exactPhysicalSenderDescriptorPayloadRoundTripsUnchanged() {
        val descriptor = AutomatedDiagnosticsPhaseApplicationProbeDescriptor(
            probeKind = AutomatedDiagnosticsApplicationProbeKind.GLOBAL,
            messageId = "global-1787433361222-000000",
            transportStatus = "queued-active:3708ee1d5bfd9851",
            localBleTransportResult = "QueuedLocally",
            expectedTransportGroupId = 9_149,
            expectedChunkCount = 18,
            frameByteCount = 172,
            senderChunksQueued = 18,
            senderChunksWriteAttempted = 18,
            senderLastLocalWriteResult = "QueuedLocally"
        )

        val payload = automatedDiagnosticsPhaseApplicationProbePayloadOrNull(listOf(descriptor))

        assertEquals(
            listOf(descriptor),
            automatedDiagnosticsPhaseApplicationProbeDescriptors(payload)
        )
    }

    @Test
    fun clearingAutomatedDiagnosticsCoordinationStateClearsRawTransportTrace() {
        val harness = createDefaultPhaseTwoHarness()

        try {
            harness.participantEnvironment.recordAutomatedDiagnosticsApplicationProbeTransportReceiveEvent(
                AutomatedDiagnosticsApplicationProbeTransportReceiveEvent(
                    groupId = 58_066,
                    sourceDeviceAddress = null,
                    observedAtMonotonicMillis = 1_500L,
                    observedAtWallClockMillis = 1_716_400_905L,
                    transportResultKind = "Buffered",
                    receivedChunks = 3,
                    expectedChunks = 18
                )
            )

            assertEquals(
                1,
                harness.participantEnvironment
                    .recentAutomatedDiagnosticsApplicationProbeTransportReceiveEvents
                    .size
            )

            harness.participantEnvironment.clearAutomatedDiagnosticsCoordinationState()

            assertTrue(
                harness.participantEnvironment
                    .recentAutomatedDiagnosticsApplicationProbeTransportReceiveEvents
                    .isEmpty()
            )
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun stepEighteenPassesWhenAppendedDiagnosticHasUnexpectedSenderTelemetry() = runBlocking {
        val harness = createDefaultPhaseTwoHarness()
        val coordinationTransport = requireNotNull(harness.coordinatorEnvironment.coordinationTransport)
        coordinationTransport.dropAutomatedDiagnosticsApplicationProbePredicate = { marker, _, _, _ ->
            marker.stepId == AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE &&
                marker.probeKind == AutomatedDiagnosticsApplicationProbeKind.GLOBAL &&
                marker.direction == AutomatedDiagnosticsApplicationProbeDirection.C2P
        }

        try {
            harness.startBothManualRuns()
            harness.advanceSteps(1)

            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                harness.awaitMutualStepSeventeenPass(maxSteps = 1200)
            )
            assertTrue(
                harness.advanceUntil(maxSteps = 400) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
                    ).status != AutomatedDiagnosticStepStatus.WAITING &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
                        ).status != AutomatedDiagnosticStepStatus.WAITING
                }
            )

            coordinationTransport.injectAutomatedDiagnosticsApplicationProbe(
                receiver = harness.participantEnvironment,
                messageId = "wrong-step18-global",
                marker = AutomatedDiagnosticsApplicationProbeMarker(
                    sharedRunId = harness.coordinatorSharedRun().runId,
                    stepId = AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
                    attemptNumber = 1,
                    probeKind = AutomatedDiagnosticsApplicationProbeKind.GLOBAL,
                    direction = AutomatedDiagnosticsApplicationProbeDirection.C2P
                ),
                senderPeerId = "wrong-peer",
                privateChatId = null,
                observedAtMonotonicMillis = harness.participantEnvironment.currentMonotonicMillis(),
                observedAtWallClockMillis = harness.participantEnvironment.currentWallClockMillis()
            )

            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                harness.advanceUntil(maxSteps = 1600) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
                        ).status == AutomatedDiagnosticStepStatus.PASS
                }
            )

            assertEquals(1, harness.coordinatorEnvironment.sendGlobalChatMessageCallCount)
            assertEquals(0, harness.participantEnvironment.sendGlobalChatMessageCallCount)
            val participantStep = harness.participantStep(
                AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
            )
            assertEquals("1", participantStep.evidenceValue("GLOBAL C2P observed"))
            assertEquals("COMPLETE_FRAME_SEEN", participantStep.evidenceValue("GLOBAL C2P receiver frame"))
            assertEquals("Processed", participantStep.evidenceValue("GLOBAL C2P transport result"))
            assertEquals("Received", participantStep.evidenceValue("GLOBAL C2P processing"))
            assertEquals("Appended", participantStep.evidenceValue("GLOBAL C2P ingestion"))
            assertEquals("VALID", participantStep.evidenceValue("GLOBAL C2P marker"))
            assertEquals("GLOBAL_TEXT", participantStep.evidenceValue("GLOBAL C2P frame type"))
            assertEquals("true", participantStep.evidenceValue("GLOBAL C2P source resolution attempted"))
            assertEquals("false", participantStep.evidenceValue("GLOBAL C2P observation created"))
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun stepEighteenPassesWhenAppendedDiagnosticHasNoMatchingGroupCorrelation() = runBlocking {
        val harness = createDefaultPhaseTwoHarness()
        val coordinationTransport = requireNotNull(harness.coordinatorEnvironment.coordinationTransport)
        coordinationTransport.dropAutomatedDiagnosticsApplicationProbePredicate = { marker, _, _, _ ->
            marker.stepId == AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE &&
                marker.probeKind == AutomatedDiagnosticsApplicationProbeKind.GLOBAL &&
                marker.direction == AutomatedDiagnosticsApplicationProbeDirection.C2P
        }

        try {
            harness.startBothManualRuns()
            harness.advanceSteps(1)

            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                harness.awaitMutualStepSeventeenPass(maxSteps = 1200)
            )
            assertTrue(
                harness.advanceUntil(maxSteps = 400) {
                    val participantSignal =
                        harness.participantEnvironment
                            .latestAutomatedDiagnosticsPhaseSignalsByStep[AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE]
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
                    ).status != AutomatedDiagnosticStepStatus.WAITING &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
                        ).status != AutomatedDiagnosticStepStatus.WAITING &&
                        participantSignal?.applicationProbeDescriptors?.singleOrNull()?.expectedTransportGroupId != null
                }
            )

            val phaseDescriptor =
                requireNotNull(
                    harness.participantEnvironment
                        .latestAutomatedDiagnosticsPhaseSignalsByStep[AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE]
                        ?.applicationProbeDescriptors
                        ?.singleOrNull()
                )
            val wrongGroupDiagnostic = AutomatedDiagnosticsApplicationProbeReceiveDiagnostic(
                sharedRunId = harness.coordinatorSharedRun().runId,
                stepId = AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
                attemptNumber = 1,
                probeKind = AutomatedDiagnosticsApplicationProbeKind.GLOBAL,
                direction = AutomatedDiagnosticsApplicationProbeDirection.C2P,
                messageId = phaseDescriptor.messageId,
                applicationSenderId = harness.coordinatorEnvironment.localPeerId,
                receiverPeerId = harness.participantEnvironment.localPeerId,
                messageType = MessageFrameType.GLOBAL_TEXT,
                threadId = "global",
                privateChatId = null,
                transportGroupId = requireNotNull(phaseDescriptor.expectedTransportGroupId) + 1,
                marker = AutomatedDiagnosticsApplicationProbeMarker(
                    sharedRunId = harness.coordinatorSharedRun().runId,
                    stepId = AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
                    attemptNumber = 1,
                    probeKind = AutomatedDiagnosticsApplicationProbeKind.GLOBAL,
                    direction = AutomatedDiagnosticsApplicationProbeDirection.C2P
                ),
                sourceResolution = AutomatedDiagnosticsApplicationProbeSourceResolution(
                    sourceDeviceAddress = "4F:43:99:C6:F7:2E",
                    exactAddressSourcePeerId = null,
                    diagnosticsAssociatedSourcePeerId = null,
                    resolvedSourcePeerId = null,
                    resolutionSource =
                        AutomatedDiagnosticsApplicationProbeSourceResolutionSource.UNRESOLVED,
                    associationLookupHit = true,
                    storedAssociationPeerId = harness.coordinatorEnvironment.localPeerId,
                    storedAssociationSharedRunId = harness.coordinatorSharedRun().runId,
                    storedAssociationStepId =
                        AutomatedDiagnosticStepId.PRIVATE_ENCRYPTED_MESSAGE_PROBE,
                    storedAssociationAttemptNumber = 1,
                    storedAssociationExpectedRemotePeerId =
                        harness.participantEnvironment.localPeerId,
                    selectedSecurePeerId = null,
                    diagnosticsAssociationOutcome =
                        AutomatedDiagnosticsApplicationProbeAssociationOutcome
                            .ASSOCIATION_WRONG_STEP,
                    selectedSecurePeerGate =
                        AutomatedDiagnosticsApplicationProbeSelectedSecurePeerGate.MATCH
                ),
                observedAtMonotonicMillis =
                    harness.participantEnvironment.currentMonotonicMillis()
            )
            harness.participantEnvironment.recordAutomatedDiagnosticsApplicationProbeReceiveDiagnostic(
                wrongGroupDiagnostic
            )

            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                harness.advanceUntil(maxSteps = 1600) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
                        ).status == AutomatedDiagnosticStepStatus.PASS
                }
            )

            val participantStep = harness.participantStep(
                AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
            )
            assertEquals("1", participantStep.evidenceValue("GLOBAL C2P observed"))
            assertEquals(phaseDescriptor.messageId, participantStep.evidenceValue("GLOBAL C2P sender message id"))
            assertEquals(
                phaseDescriptor.expectedTransportGroupId.toString(),
                participantStep.evidenceValue("GLOBAL C2P expected group id")
            )
            assertEquals("COMPLETE_FRAME_SEEN", participantStep.evidenceValue("GLOBAL C2P receiver frame"))
            assertEquals("0", participantStep.evidenceValue("GLOBAL C2P matching chunks seen"))
            assertEquals("Processed", participantStep.evidenceValue("GLOBAL C2P transport result"))
            assertEquals("Received", participantStep.evidenceValue("GLOBAL C2P processing"))
            assertEquals("Appended", participantStep.evidenceValue("GLOBAL C2P ingestion"))
            assertEquals("VALID", participantStep.evidenceValue("GLOBAL C2P marker"))
            assertEquals("true", participantStep.evidenceValue("GLOBAL C2P source resolution attempted"))
            assertEquals("false", participantStep.evidenceValue("GLOBAL C2P observation created"))
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun stepNineteenWrongPrivateChatIdObservationIsIgnoredUntilTimeout() = runBlocking {
        val harness = createDefaultPhaseTwoHarness()
        val coordinationTransport = requireNotNull(harness.coordinatorEnvironment.coordinationTransport)
        coordinationTransport.dropAutomatedDiagnosticsApplicationProbePredicate = { marker, _, _, _ ->
            marker.stepId == AutomatedDiagnosticStepId.PRIVATE_ENCRYPTED_MESSAGE_PROBE &&
                marker.probeKind == AutomatedDiagnosticsApplicationProbeKind.PRIVATE &&
                marker.direction == AutomatedDiagnosticsApplicationProbeDirection.C2P
        }

        try {
            harness.startBothManualRuns()
            harness.advanceSteps(1)

            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                harness.advanceUntil(maxSteps = 2400) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE
                        ).status == AutomatedDiagnosticStepStatus.PASS
                }
            )
            assertTrue(
                harness.advanceUntil(maxSteps = 400) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.PRIVATE_ENCRYPTED_MESSAGE_PROBE
                    ).status != AutomatedDiagnosticStepStatus.WAITING &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.PRIVATE_ENCRYPTED_MESSAGE_PROBE
                        ).status != AutomatedDiagnosticStepStatus.WAITING
                }
            )

            coordinationTransport.injectAutomatedDiagnosticsApplicationProbe(
                receiver = harness.participantEnvironment,
                messageId = "wrong-step19-private",
                marker = AutomatedDiagnosticsApplicationProbeMarker(
                    sharedRunId = harness.coordinatorSharedRun().runId,
                    stepId = AutomatedDiagnosticStepId.PRIVATE_ENCRYPTED_MESSAGE_PROBE,
                    attemptNumber = 1,
                    probeKind = AutomatedDiagnosticsApplicationProbeKind.PRIVATE,
                    direction = AutomatedDiagnosticsApplicationProbeDirection.C2P
                ),
                senderPeerId = harness.coordinatorEnvironment.localPeerId,
                privateChatId = "wrong-private-chat-id",
                observedAtMonotonicMillis = harness.participantEnvironment.currentMonotonicMillis(),
                observedAtWallClockMillis = harness.participantEnvironment.currentWallClockMillis()
            )

            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                harness.advanceUntil(maxSteps = 1600) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.PRIVATE_ENCRYPTED_MESSAGE_PROBE
                    ).status == AutomatedDiagnosticStepStatus.BLOCKED &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.PRIVATE_ENCRYPTED_MESSAGE_PROBE
                        ).status == AutomatedDiagnosticStepStatus.BLOCKED
                }
            )

            assertEquals(1, harness.coordinatorEnvironment.sendPrivateChatMessageCallCount)
            assertEquals(0, harness.participantEnvironment.sendPrivateChatMessageCallCount)
            assertEquals(
                "0",
                harness.participantStep(
                    AutomatedDiagnosticStepId.PRIVATE_ENCRYPTED_MESSAGE_PROBE
                ).evidenceValue("PRIVATE C2P observed")
            )
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun stepTwentyRequiresBothReverseGlobalAndReversePrivateProbes() = runBlocking {
        val harness = createDefaultPhaseTwoHarness()
        val coordinationTransport = requireNotNull(harness.coordinatorEnvironment.coordinationTransport)
        coordinationTransport.dropAutomatedDiagnosticsApplicationProbePredicate = { marker, _, _, _ ->
            marker.stepId == AutomatedDiagnosticStepId.REVERSE_DIRECTION_MESSAGING_PROBE &&
                marker.probeKind == AutomatedDiagnosticsApplicationProbeKind.PRIVATE &&
                marker.direction == AutomatedDiagnosticsApplicationProbeDirection.P2C
        }

        try {
            harness.startBothManualRuns()
            harness.advanceSteps(1)

            val reachedStepSeventeenPass = harness.awaitMutualStepSeventeenPass(maxSteps = 3600)
            assertTrue(harness.reportText(), reachedStepSeventeenPass)
            val reachedStepNineteenPass = harness.awaitMutualStepNineteenPass(maxSteps = 2000)
            assertTrue(harness.reportText(), reachedStepNineteenPass)

            assertTrue(
                "Coordinator report:\n${harness.coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${harness.participantRunner.state.value.reportText}",
                harness.advanceUntil(maxSteps = 2000) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.REVERSE_DIRECTION_MESSAGING_PROBE
                    ).status == AutomatedDiagnosticStepStatus.BLOCKED &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.REVERSE_DIRECTION_MESSAGING_PROBE
                        ).status == AutomatedDiagnosticStepStatus.BLOCKED
                }
            )

            assertEquals(1, harness.participantEnvironment.sendGlobalChatMessageCallCount)
            assertEquals(1, harness.participantEnvironment.sendPrivateChatMessageCallCount)
            assertEquals(
                "1",
                harness.coordinatorStep(
                    AutomatedDiagnosticStepId.REVERSE_DIRECTION_MESSAGING_PROBE
                ).evidenceValue("GLOBAL P2C observed")
            )
            assertEquals(
                "0",
                harness.coordinatorStep(
                    AutomatedDiagnosticStepId.REVERSE_DIRECTION_MESSAGING_PROBE
                ).evidenceValue("PRIVATE P2C observed")
            )
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun finalValidationFailsWhenCleanupRemovesOnlySubsetOfCapturedIdsAndResetRetriesRemainingExactId() = runBlocking {
        val harness = createDefaultPhaseTwoHarness().apply {
            coordinatorEnvironment.removeMessagesByIdsOverride = { removableIds ->
                removableIds.sorted().take(3).toSet()
            }
        }

        try {
            harness.startBothManualRuns()
            harness.advanceSteps(1)

            val reachedStepSeventeenPass = harness.awaitMutualStepSeventeenPass(maxSteps = 3600)
            assertTrue(harness.reportText(), reachedStepSeventeenPass)
            assertTrue(
                harness.advanceUntil(maxSteps = 2600) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.FINAL_END_TO_END_VALIDATION
                    ).status == AutomatedDiagnosticStepStatus.FAIL
                }
            )

            val finalStep = harness.coordinatorStep(
                AutomatedDiagnosticStepId.FINAL_END_TO_END_VALIDATION
            )
            assertEquals(AutomatedDiagnosticStepStatus.FAIL, finalStep.status)
            assertEquals("4", finalStep.evidenceValue("Cleanup attempted ids"))
            assertEquals("3", finalStep.evidenceValue("Cleaned message ids"))
            assertEquals("1", finalStep.evidenceValue("Cleanup remaining ids"))
            assertEquals(1, harness.coordinatorEnvironment.removeMessagesByIdsCallCount)
            assertEquals(4, harness.coordinatorEnvironment.lastRemovedMessageIds.size)
            assertEquals(3, harness.coordinatorEnvironment.lastActuallyRemovedMessageIds.size)
            assertEquals(
                "Phase 3 cleanup incomplete: removed 3 of 4 exact automated diagnostics message id(s).",
                harness.coordinatorRunner.state.value.phaseTwoSummary
            )

            harness.coordinatorEnvironment.removeMessagesByIdsOverride = null
            harness.coordinatorRunner.resetReport()

            assertEquals(2, harness.coordinatorEnvironment.removeMessagesByIdsCallCount)
            assertEquals(1, harness.coordinatorEnvironment.lastRemovedMessageIds.size)
            assertEquals(1, harness.coordinatorEnvironment.lastActuallyRemovedMessageIds.size)
        } finally {
            harness.cancel()
        }
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
        assertEquals(AutomatedDiagnosticsOverallStatus.BLOCKED, retriedState.overallStatus)
        assertStepsPassedThrough(
            retriedState,
            AutomatedDiagnosticStepId.BLE_STABILITY
        )
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
        assertEquals(
            AutomatedDiagnosticsRequiredActionKind.REQUEST_WIFI_DIRECT_PERMISSIONS,
            blockedState.steps[
                AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP.ordinal
            ].requiredAction?.kind
        )

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
            AutomatedDiagnosticsOverallStatus.BLOCKED,
            resumedState.overallStatus
        )
        assertEquals(preservedSharedRunId, resumedState.sharedRunId)
        val step = resumedState.steps[
            AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP.ordinal
        ]
        assertEquals(AutomatedDiagnosticStepStatus.BLOCKED, step.status)
        assertEquals(null, step.requiredAction)
        assertTrue(step.blockerOrFailure?.isNotBlank() == true)
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
        assertEquals(
            AutomatedDiagnosticStepStatus.PASS,
            state.steps[AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION.ordinal].status
        )
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
    fun preparationStateReportsMissingWifiDirectPermissionBeforeRunnerStart() {
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

        val preparationState = automatedDiagnosticsPreparationState(
            environment.createBindings(
                clock = FakeMonotonicClock(),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            ).snapshot()
        )

        assertFalse(preparationState.isReady)
        assertEquals(
            AutomatedDiagnosticsRequiredActionKind.REQUEST_WIFI_DIRECT_PERMISSIONS,
            preparationState.requiredAction?.kind
        )
        assertEquals(
            AutomatedDiagnosticsPreparationItemStatus.WAITING,
            preparationState.items.first { it.label == "Wi-Fi Direct permission" }.status
        )
    }

    @Test
    fun preparationCommandStartsRunnerOnlyWhenPendingAndReady() {
        val readyState = AutomatedDiagnosticsPreparationState(
            isReady = true,
            summary = "Ready to start automated diagnostics."
        )

        assertEquals(
            AutomatedDiagnosticsPreparationCommand.NONE,
            automatedDiagnosticsPreparationCommand(
                isPreparationPending = false,
                currentOverallStatus = AutomatedDiagnosticsOverallStatus.IDLE,
                preparationState = readyState,
                bluetoothPermissionRequestAttempted = false,
                wifiDirectPermissionRequestAttempted = false
            )
        )
        assertEquals(
            AutomatedDiagnosticsPreparationCommand.START_RUN,
            automatedDiagnosticsPreparationCommand(
                isPreparationPending = true,
                currentOverallStatus = AutomatedDiagnosticsOverallStatus.IDLE,
                preparationState = readyState,
                bluetoothPermissionRequestAttempted = false,
                wifiDirectPermissionRequestAttempted = false
            )
        )
    }

    @Test
    fun preparationCommandRequestsWifiPermissionOnlyOncePerAttempt() {
        val waitingState = AutomatedDiagnosticsPreparationState(
            isReady = false,
            summary = "Requesting Wi-Fi Direct permission before starting the test.",
            requiredAction = AutomatedDiagnosticsRequiredAction(
                kind = AutomatedDiagnosticsRequiredActionKind.REQUEST_WIFI_DIRECT_PERMISSIONS,
                title = "Wi-Fi Direct permission required",
                buttonLabel = "Grant Wi-Fi Direct permission"
            )
        )

        assertEquals(
            AutomatedDiagnosticsPreparationCommand.REQUEST_WIFI_DIRECT_PERMISSIONS,
            automatedDiagnosticsPreparationCommand(
                isPreparationPending = true,
                currentOverallStatus = AutomatedDiagnosticsOverallStatus.IDLE,
                preparationState = waitingState,
                bluetoothPermissionRequestAttempted = false,
                wifiDirectPermissionRequestAttempted = false
            )
        )
        assertEquals(
            AutomatedDiagnosticsPreparationCommand.NONE,
            automatedDiagnosticsPreparationCommand(
                isPreparationPending = true,
                currentOverallStatus = AutomatedDiagnosticsOverallStatus.IDLE,
                preparationState = waitingState,
                bluetoothPermissionRequestAttempted = false,
                wifiDirectPermissionRequestAttempted = true
            )
        )
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
    fun autoParticipantWithPreparationReadyStartsNormally() {
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
            "Auto-participant did not start after readiness was already satisfied.",
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
        assertEquals(1, diagnostics.participantStartInvocationCount)
        assertEquals(1, diagnostics.participantJobGeneration)
        assertEquals(1, environment.automatedDiagnosticsParticipantJoinRequestCount)
        assertEquals(coordinatorPeerId, state.selectedPeerId)
        assertFalse(runner.automaticPreparationPending())

        runner.setAutomaticParticipationEnabled(false)
        scope.cancel()
    }

    @Test
    fun autoParticipantMissingWifiDirectPermissionWaitsInPreparationAndPreservesSharedRun() {
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
            wifiDirectRuntimeStatus = wifiDirectRuntimeStatus.copy(
                permissionStatus = WifiDirectPermissionStatus(
                    requiredPermissions = setOf("android.permission.NEARBY_WIFI_DEVICES"),
                    missingPermissions = setOf("android.permission.NEARBY_WIFI_DEVICES"),
                    isWifiDirectSupported = true,
                    isWifiEnabled = true,
                    isWifiP2pEnabled = true
                )
            )
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
            "Auto-participant never entered the waiting preparation state.",
            advanceUntil(maxSteps = 80, advance = {
                delay.advanceSteps(1)
            }) {
                runner.automaticPreparationPending() &&
                    runner.listenerDiagnosticsForTest().pendingAnnouncementRunId != null
            }
        )
        delay.advanceSteps(20)

        val state = runner.state.value
        val diagnostics = runner.listenerDiagnosticsForTest()
        val preparationState = runner.currentPreparationState()
        val preservedRunId = requireNotNull(diagnostics.pendingAnnouncementRunId)

        assertEquals(AutomatedDiagnosticsOverallStatus.IDLE, state.overallStatus)
        assertEquals(null, state.startedAtMillis)
        assertEquals(preservedRunId, state.sharedRunId)
        assertEquals(
            AutomatedDiagnosticStepStatus.WAITING,
            state.steps[AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION.ordinal].status
        )
        assertEquals(
            AutomatedDiagnosticsRequiredActionKind.REQUEST_WIFI_DIRECT_PERMISSIONS,
            preparationState.requiredAction?.kind
        )
        assertTrue(runner.automaticPreparationPending())
        assertEquals(0, diagnostics.participantStartInvocationCount)
        assertEquals(0, diagnostics.participantJobGeneration)
        assertEquals(0, environment.automatedDiagnosticsParticipantJoinRequestCount)
        assertTrue(diagnostics.lastAutoJoinBlocker.contains("REQUEST_WIFI_DIRECT_PERMISSIONS"))

        runner.setAutomaticParticipationEnabled(false)
        scope.cancel()
    }

    @Test
    fun autoParticipantPermissionGrantResumesSameRunExactlyOnce() {
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
            wifiDirectRuntimeStatus = wifiDirectRuntimeStatus.copy(
                permissionStatus = WifiDirectPermissionStatus(
                    requiredPermissions = setOf("android.permission.NEARBY_WIFI_DEVICES"),
                    missingPermissions = setOf("android.permission.NEARBY_WIFI_DEVICES"),
                    isWifiDirectSupported = true,
                    isWifiEnabled = true,
                    isWifiP2pEnabled = true
                )
            )
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
            advanceUntil(maxSteps = 80, advance = {
                delay.advanceSteps(1)
            }) {
                runner.automaticPreparationPending() &&
                    runner.listenerDiagnosticsForTest().pendingAnnouncementRunId != null
            }
        )
        val preservedRunId = requireNotNull(runner.state.value.sharedRunId)

        environment.wifiDirectRuntimeStatus = environment.wifiDirectRuntimeStatus.copy(
            permissionStatus = WifiDirectPermissionStatus(
                requiredPermissions = emptySet(),
                missingPermissions = emptySet(),
                isWifiDirectSupported = true,
                isWifiEnabled = true,
                isWifiP2pEnabled = true
            )
        )

        assertTrue(
            "Auto-participant did not resume the preserved shared run after permission grant.",
            advanceUntil(maxSteps = 220, advance = {
                delay.advanceSteps(1)
            }) {
                runner.state.value.steps[
                    AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION.ordinal
                ].status == AutomatedDiagnosticStepStatus.PASS
            }
        )
        delay.advanceSteps(20)

        val state = runner.state.value
        val step = state.steps[AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION.ordinal]
        val diagnostics = runner.listenerDiagnosticsForTest()

        assertEquals(preservedRunId, state.sharedRunId)
        assertEquals("0", step.evidenceValue("Local shared run ids generated"))
        assertEquals("AUTOMATIC_PARTICIPANT_JOIN", step.evidenceValue("Run start cause"))
        assertEquals(1, diagnostics.participantStartInvocationCount)
        assertEquals(1, diagnostics.participantJobGeneration)
        assertEquals(1, environment.automatedDiagnosticsParticipantJoinRequestCount)
        assertEquals(null, diagnostics.pendingAnnouncementRunId)
        assertFalse(runner.automaticPreparationPending())

        runner.setAutomaticParticipationEnabled(false)
        scope.cancel()
    }

    @Test
    fun autoParticipantMissingWifiDirectPermissionStaysPreparingWithoutFailingDuringLease() {
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
            wifiDirectRuntimeStatus = wifiDirectRuntimeStatus.copy(
                permissionStatus = WifiDirectPermissionStatus(
                    requiredPermissions = setOf("android.permission.NEARBY_WIFI_DEVICES"),
                    missingPermissions = setOf("android.permission.NEARBY_WIFI_DEVICES"),
                    isWifiDirectSupported = true,
                    isWifiEnabled = true,
                    isWifiP2pEnabled = true
                )
            )
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
            advanceUntil(maxSteps = 60, advance = {
                delay.advanceSteps(1)
            }) {
                runner.automaticPreparationPending()
            }
        )
        delay.advanceSteps(20)

        val state = runner.state.value
        assertEquals(AutomatedDiagnosticsOverallStatus.IDLE, state.overallStatus)
        assertEquals(0, state.failedCount)
        assertEquals(0, state.blockedCount)
        assertTrue(runner.automaticPreparationPending())
        assertEquals(
            AutomatedDiagnosticsRequiredActionKind.REQUEST_WIFI_DIRECT_PERMISSIONS,
            runner.currentPreparationState().requiredAction?.kind
        )

        runner.setAutomaticParticipationEnabled(false)
        scope.cancel()
    }

    @Test
    fun coordinatorWaitsForAutoParticipantPreparationBeforeStepElevenTimeoutsStart() = runBlocking {
        val participantPhonePeer = wifiDirectPeer(
            name = "Participant Pixel",
            address = "aa:bb:cc:dd:ee:20"
        )
        val coordinatorPhonePeer = wifiDirectPeer(
            name = "Coordinator Pixel",
            address = "aa:bb:cc:dd:ee:10"
        )
        val timingPolicy = stepElevenTimingPolicy().copy(
            sharedRunCoordination = AutomatedDiagnosticsTimingWindow(
                stabilizationMillis = 300L,
                timeoutMillis = 5_000L
            )
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
            timingPolicy = timingPolicy
        )
        harness.coordinatorEnvironment.configureReadySecureSessionState()
        harness.participantEnvironment.configureReadySecureSessionState()
        harness.participantEnvironment.wifiDirectRuntimeStatus =
            harness.participantEnvironment.wifiDirectRuntimeStatus.copy(
                permissionStatus = WifiDirectPermissionStatus(
                    requiredPermissions = setOf("android.permission.NEARBY_WIFI_DEVICES"),
                    missingPermissions = setOf("android.permission.NEARBY_WIFI_DEVICES"),
                    isWifiDirectSupported = true,
                    isWifiEnabled = true,
                    isWifiP2pEnabled = true
                )
            )

        try {
            harness.participantRunner.setAutomaticParticipationEnabled(true)
            harness.coordinatorRunner.setAutomaticParticipationEnabled(true)
            harness.coordinatorRunner.start()

            assertTrue(
                harness.advanceUntil(maxSteps = 200) {
                    harness.participantRunner.automaticPreparationPending() &&
                        harness.coordinatorStep(
                            AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION
                        ).status == AutomatedDiagnosticStepStatus.RUNNING
                }
            )

            harness.advanceSteps(40)

            assertTrue(harness.participantRunner.automaticPreparationPending())
            assertEquals(
                AutomatedDiagnosticStepStatus.RUNNING,
                harness.coordinatorStep(AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION).status
            )
            assertEquals(
                AutomatedDiagnosticStepStatus.WAITING,
                harness.coordinatorStep(
                    AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                ).status
            )
            assertEquals(
                AutomatedDiagnosticStepStatus.WAITING,
                harness.participantStep(
                    AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                ).status
            )
            assertEquals(0, harness.coordinatorEnvironment.startWifiDirectDiscoveryCallCount)
            assertEquals(0, harness.participantEnvironment.startWifiDirectDiscoveryCallCount)
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun autoParticipantPreparationGrantStillReachesStepElevenBarrier() = runBlocking {
        val participantPhonePeer = wifiDirectPeer(
            name = "Participant Pixel",
            address = "aa:bb:cc:dd:ee:20"
        )
        val coordinatorPhonePeer = wifiDirectPeer(
            name = "Coordinator Pixel",
            address = "aa:bb:cc:dd:ee:10"
        )
        val timingPolicy = stepElevenTimingPolicy().copy(
            sharedRunCoordination = AutomatedDiagnosticsTimingWindow(
                stabilizationMillis = 300L,
                timeoutMillis = 8_000L
            )
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
            timingPolicy = timingPolicy
        )
        harness.coordinatorEnvironment.configureReadySecureSessionState()
        harness.participantEnvironment.configureReadySecureSessionState()
        harness.participantEnvironment.wifiDirectRuntimeStatus =
            harness.participantEnvironment.wifiDirectRuntimeStatus.copy(
                permissionStatus = WifiDirectPermissionStatus(
                    requiredPermissions = setOf("android.permission.NEARBY_WIFI_DEVICES"),
                    missingPermissions = setOf("android.permission.NEARBY_WIFI_DEVICES"),
                    isWifiDirectSupported = true,
                    isWifiEnabled = true,
                    isWifiP2pEnabled = true
                )
            )

        try {
            harness.participantRunner.setAutomaticParticipationEnabled(true)
            harness.coordinatorRunner.setAutomaticParticipationEnabled(true)
            harness.coordinatorRunner.start()

            assertTrue(
                harness.advanceUntil(maxSteps = 200) {
                    harness.participantRunner.automaticPreparationPending() &&
                        harness.coordinatorStep(
                            AutomatedDiagnosticStepId.REMOTE_PARTICIPANT_COORDINATION
                        ).status == AutomatedDiagnosticStepStatus.RUNNING
                }
            )
            val preservedRunId = requireNotNull(harness.participantRunner.state.value.sharedRunId)

            harness.participantEnvironment.wifiDirectRuntimeStatus =
                harness.participantEnvironment.wifiDirectRuntimeStatus.copy(
                    permissionStatus = WifiDirectPermissionStatus(
                        requiredPermissions = emptySet(),
                        missingPermissions = emptySet(),
                        isWifiDirectSupported = true,
                        isWifiEnabled = true,
                        isWifiP2pEnabled = true
                    )
                )

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

            assertEquals(preservedRunId, harness.coordinatorRunner.state.value.sharedRunId)
            assertEquals(preservedRunId, harness.participantRunner.state.value.sharedRunId)
            assertEquals(
                1,
                harness.participantRunner.listenerDiagnosticsForTest().participantStartInvocationCount
            )
            assertEquals(1, harness.participantEnvironment.automatedDiagnosticsParticipantJoinRequestCount)
            assertTrue(harness.coordinatorEnvironment.startWifiDirectDiscoveryCallCount >= 1)
            assertTrue(harness.participantEnvironment.startWifiDirectDiscoveryCallCount >= 1)
        } finally {
            harness.cancel()
        }
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
    fun serverReadyRoundTripPreservesDiagnosticsRolesWhenParticipantOwnsGroup() {
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
            harness.coordinatorEnvironment.configureReadySecureSessionState()
            harness.participantEnvironment.configureReadySecureSessionState()
            val sharedRun = harness.coordinatorEnvironment.sharedRunFor(
                coordinatorPeerId = harness.coordinatorEnvironment.localPeerId,
                participantPeerId = harness.participantEnvironment.localPeerId,
                createdAtMillis = 1_000L
            )
            val result = requireNotNull(
                harness.participantEnvironment.coordinationTransport
            ).sendServerReady(
                from = harness.participantEnvironment,
                sharedRun = sharedRun,
                expectedClientPeerId = harness.coordinatorEnvironment.localPeerId,
                groupOwnerAddress = "192.168.49.1",
                socketPort = wifiDirectDebugSocketPort,
                serverToken = 77L,
                createdAtMillis = 1_250L
            )

            assertTrue(result is AutomatedDiagnosticsServerReadySendResult.Sent)
            val receivedSignal =
                requireNotNull(harness.coordinatorEnvironment.latestAutomatedDiagnosticsServerReadySignal)
            assertEquals(sharedRun.coordinatorPeerId, receivedSignal.sharedRun.coordinatorPeerId)
            assertEquals(sharedRun.participantPeerId, receivedSignal.sharedRun.participantPeerId)
            assertEquals(harness.participantEnvironment.localPeerId, receivedSignal.peerId)
            assertEquals(harness.coordinatorEnvironment.localPeerId, receivedSignal.expectedClientPeerId)
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
            assertEquals(
                harness.coordinatorEnvironment.localPeerId,
                coordinatorStep.evidenceValue("Expected server-owner Aurora peer id")
            )
            assertEquals(
                harness.participantEnvironment.localPeerId,
                coordinatorStep.evidenceValue("Expected client Aurora peer id")
            )
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
            assertEquals("GROUP_OWNER", coordinatorStep.evidenceValue("Actual Wi-Fi role"))
            assertEquals("CONNECTED", coordinatorStep.evidenceValue("Socket state"))
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
            assertEquals(
                harness.coordinatorEnvironment.localPeerId,
                participantStep.evidenceValue("Expected server-owner Aurora peer id")
            )
            assertEquals(
                harness.participantEnvironment.localPeerId,
                participantStep.evidenceValue("Expected client Aurora peer id")
            )
            assertEquals("192.168.49.1", participantStep.evidenceValue("Client connect host"))
            assertEquals("CONNECTED", participantStep.evidenceValue("Socket state"))
            assertEquals("READY", participantStep.evidenceValue("Adapter state"))
            assertEquals(
                harness.coordinatorEnvironment.localPeerId,
                participantStep.evidenceValue("Latest received SERVER_READY transport sender peer id")
            )
            assertEquals(
                harness.coordinatorEnvironment.localPeerId,
                participantStep.evidenceValue("Latest received SERVER_READY coordinator peer id")
            )
            assertEquals(
                harness.participantEnvironment.localPeerId,
                participantStep.evidenceValue("Latest received SERVER_READY participant peer id")
            )
            assertEquals("remotely-received", participantStep.evidenceValue("Latest SERVER_READY observation source"))
            assertEquals("none", participantStep.evidenceValue("SERVER_READY rejection reason"))
            assertEquals("none", participantStep.evidenceValue("Last SERVER_READY rejection field"))
            assertEquals("none", participantStep.evidenceValue("Last SERVER_READY rejection expected"))
            assertEquals("none", participantStep.evidenceValue("Last SERVER_READY rejection observed"))
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun stepTwelveAcceptsServerReadyWhenParticipantOwnsGroupAndCoordinatorIsClient() {
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

            harness.participantEnvironment.completeWifiDirectGroupAsGroupOwner(coordinatorPhonePeer)
            harness.coordinatorEnvironment.completeWifiDirectGroupAsClient(participantPhonePeer)

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
            assertEquals(1, harness.participantEnvironment.startWifiDirectSocketServerCallCount)
            assertEquals(1, harness.coordinatorEnvironment.connectWifiDirectSocketClientCallCount)
            assertEquals(1, harness.participantEnvironment.automatedDiagnosticsServerReadyRequestCount)
            assertEquals(0, harness.coordinatorEnvironment.automatedDiagnosticsServerReadyRequestCount)
            assertEquals(
                harness.participantEnvironment.localPeerId,
                coordinatorStep.evidenceValue("Expected server-owner Aurora peer id")
            )
            assertEquals(
                harness.coordinatorEnvironment.localPeerId,
                coordinatorStep.evidenceValue("Expected client Aurora peer id")
            )
            assertEquals("CLIENT", coordinatorStep.evidenceValue("Actual Wi-Fi role"))
            assertEquals("1", coordinatorStep.evidenceValue("Client connect request count"))
            assertEquals("192.168.49.1", coordinatorStep.evidenceValue("Client connect host"))
            assertEquals("CONNECTED", coordinatorStep.evidenceValue("Socket state"))
            assertEquals("READY", coordinatorStep.evidenceValue("Adapter state"))
            assertEquals(
                harness.participantEnvironment.localPeerId,
                coordinatorStep.evidenceValue("Latest received SERVER_READY transport sender peer id")
            )
            assertEquals(
                harness.coordinatorEnvironment.localPeerId,
                coordinatorStep.evidenceValue("Latest received SERVER_READY coordinator peer id")
            )
            assertEquals(
                harness.participantEnvironment.localPeerId,
                coordinatorStep.evidenceValue("Latest received SERVER_READY participant peer id")
            )
            assertEquals("remotely-received", coordinatorStep.evidenceValue("Latest SERVER_READY observation source"))
            assertEquals("none", coordinatorStep.evidenceValue("SERVER_READY rejection reason"))
            assertEquals("none", coordinatorStep.evidenceValue("Last SERVER_READY rejection field"))
            assertEquals("none", coordinatorStep.evidenceValue("Last SERVER_READY rejection expected"))
            assertEquals("none", coordinatorStep.evidenceValue("Last SERVER_READY rejection observed"))
            assertTrue(
                (coordinatorStep.evidenceValue("Server-ready accepted/count")?.toIntOrNull() ?: 0) >= 1
            )
            assertEquals(
                harness.participantEnvironment.localPeerId,
                participantStep.evidenceValue("Expected server-owner Aurora peer id")
            )
            assertEquals(
                harness.coordinatorEnvironment.localPeerId,
                participantStep.evidenceValue("Expected client Aurora peer id")
            )
            assertEquals("GROUP_OWNER", participantStep.evidenceValue("Actual Wi-Fi role"))
            assertEquals("CONNECTED", participantStep.evidenceValue("Socket state"))
            assertEquals("locally-emitted", participantStep.evidenceValue("Latest SERVER_READY observation source"))
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun stepElevenRetainsLatchedDnsSdProofAfterLiveCleanupAndStillPassesSocketSetup() {
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
        var participantCleanupApplied = false
        var coordinatorCleanupApplied = false

        try {
            harness.startBothManualRuns()
            assertTrue(
                harness.advanceUntil(maxSteps = 960) {
                    val coordinatorStep = harness.coordinatorStep(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                    )
                    val participantStep = harness.participantStep(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                    )
                    if (
                        !participantCleanupApplied &&
                        !coordinatorCleanupApplied &&
                        coordinatorStep.evidenceValue("Current-run DNS-SD proof ready") == "true" &&
                        participantStep.evidenceValue("Current-run DNS-SD proof ready") == "true" &&
                        harness.coordinatorEnvironment.connectToWifiDirectPeerCallCount == 1
                    ) {
                        harness.participantEnvironment.wifiDirectRuntimeStatus =
                            harness.participantEnvironment.wifiDirectRuntimeStatus.copy(
                                dnsSdDiagnostics =
                                harness.participantEnvironment.wifiDirectRuntimeStatus
                                    .dnsSdDiagnostics.copy(
                                        localServiceRegistered = false
                                    )
                            )
                        harness.coordinatorEnvironment.wifiDirectRuntimeStatus =
                            harness.coordinatorEnvironment.wifiDirectRuntimeStatus.copy(
                                dnsSdDiagnostics =
                                harness.coordinatorEnvironment.wifiDirectRuntimeStatus
                                    .dnsSdDiagnostics.copy(
                                        serviceRequestRegistered = false,
                                        discoveryStarted = false,
                                        discoveredServices = emptyList(),
                                        cleanupCompleted = true
                                    )
                            )
                        participantCleanupApplied = true
                        coordinatorCleanupApplied = true
                        harness.coordinatorEnvironment.completeWifiDirectGroupAsGroupOwner(
                            participantPhonePeer
                        )
                        harness.participantEnvironment.completeWifiDirectGroupAsClient(
                            coordinatorPhonePeer
                        )
                    }
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_SOCKET
                    ).status == AutomatedDiagnosticStepStatus.PASS &&
                        harness.participantStep(
                            AutomatedDiagnosticStepId.WIFI_DIRECT_SOCKET
                        ).status == AutomatedDiagnosticStepStatus.PASS
                }
            )

            val coordinatorStep = harness.coordinatorStep(
                AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
            )
            val participantStep = harness.participantStep(
                AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
            )
            assertTrue(participantCleanupApplied)
            assertTrue(coordinatorCleanupApplied)
            assertEquals("false", participantStep.evidenceValue("DNS-SD local service registered"))
            assertEquals(
                "true",
                participantStep.evidenceValue("Current-run DNS-SD registration observed")
            )
            assertEquals("true", participantStep.evidenceValue("Current-run DNS-SD proof ready"))
            assertEquals("CURRENT_RUN_VALIDATED", participantStep.evidenceValue("Group provenance"))
            assertEquals("false", coordinatorStep.evidenceValue("DNS-SD request registered"))
            assertEquals("false", coordinatorStep.evidenceValue("DNS-SD discovery started"))
            assertEquals("0", coordinatorStep.evidenceValue("DNS-SD responses received"))
            assertEquals("true", coordinatorStep.evidenceValue("Current-run DNS-SD proof ready"))
            assertEquals("CURRENT_RUN_VALIDATED", coordinatorStep.evidenceValue("Group provenance"))
            assertEquals(1, harness.participantEnvironment.connectWifiDirectSocketClientCallCount)
            assertEquals(1, harness.coordinatorEnvironment.startWifiDirectSocketServerCallCount)
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun stepElevenTreatsFailedConnectionStateAsDirtyBaselineAndResetsOnce() {
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
        harness.coordinatorEnvironment.wifiDirectRuntimeStatus =
            harness.coordinatorEnvironment.wifiDirectRuntimeStatus.copy(
                connectionStatus = WifiDirectConnectionStatus(
                    state = WifiDirectConnectionState.FAILED,
                    targetPeer = participantPhonePeer,
                    groupFormed = WifiDirectGroupFormedState.UNKNOWN,
                    role = WifiDirectConnectionRole.UNKNOWN,
                    groupOwnerAddress = null,
                    lastError = "stale-failed-state"
                )
            )
        harness.participantEnvironment.wifiDirectRuntimeStatus =
            harness.participantEnvironment.wifiDirectRuntimeStatus.copy(
                connectionStatus = WifiDirectConnectionStatus(
                    state = WifiDirectConnectionState.FAILED,
                    targetPeer = coordinatorPhonePeer,
                    groupFormed = WifiDirectGroupFormedState.UNKNOWN,
                    role = WifiDirectConnectionRole.UNKNOWN,
                    groupOwnerAddress = null,
                    lastError = "stale-failed-state"
                )
            )

        try {
            harness.startBothManualRuns()
            assertTrue(
                harness.advanceUntil(maxSteps = 200) {
                    harness.coordinatorEnvironment.disconnectWifiDirectPeerCallCount == 1 &&
                        harness.participantEnvironment.disconnectWifiDirectPeerCallCount == 1
                }
            )
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

            val coordinatorStep = harness.coordinatorStep(
                AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
            )
            val participantStep = harness.participantStep(
                AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
            )
            assertEquals("1", coordinatorStep.evidenceValue("Fresh baseline disconnect request count"))
            assertEquals("true", coordinatorStep.evidenceValue("Fresh baseline established"))
            assertEquals("CURRENT_RUN_VALIDATED", coordinatorStep.evidenceValue("Group provenance"))
            assertEquals("1", participantStep.evidenceValue("Fresh baseline disconnect request count"))
            assertEquals("true", participantStep.evidenceValue("Fresh baseline established"))
            assertEquals("CURRENT_RUN_VALIDATED", participantStep.evidenceValue("Group provenance"))
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun stepElevenPhaseBarrierWaitsWhenCoordinatorArrivesFirst() {
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
            assertTrue(
                harness.advanceUntil(maxSteps = 360) {
                    harness.coordinatorStep(AutomatedDiagnosticStepId.BLE_STABILITY).status ==
                        AutomatedDiagnosticStepStatus.PASS &&
                        harness.participantStep(AutomatedDiagnosticStepId.BLE_STABILITY).status ==
                        AutomatedDiagnosticStepStatus.PASS
                }
            )
            assertTrue(
                harness.advanceCoordinatorUntil(maxSteps = 120) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                    ).status == AutomatedDiagnosticStepStatus.RUNNING
                }
            )

            val waitingStep = harness.coordinatorStep(
                AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
            )
            assertTrue(waitingStep.waitingProgressText != null)
            assertEquals(0, harness.coordinatorEnvironment.connectToWifiDirectPeerCallCount)

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
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun stepElevenPhaseBarrierWaitsWhenParticipantArrivesFirst() {
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
            assertTrue(
                harness.advanceUntil(maxSteps = 360) {
                    harness.coordinatorStep(AutomatedDiagnosticStepId.BLE_STABILITY).status ==
                        AutomatedDiagnosticStepStatus.PASS &&
                        harness.participantStep(AutomatedDiagnosticStepId.BLE_STABILITY).status ==
                        AutomatedDiagnosticStepStatus.PASS
                }
            )
            assertTrue(
                harness.advanceParticipantUntil(maxSteps = 120) {
                    harness.participantStep(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                    ).status == AutomatedDiagnosticStepStatus.RUNNING
                }
            )

            val waitingStep = harness.participantStep(
                AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
            )
            assertTrue(waitingStep.waitingProgressText != null)
            assertEquals(0, harness.coordinatorEnvironment.connectToWifiDirectPeerCallCount)

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
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun stepElevenPhaseBarrierRecoversWhenFirstParticipantReadySignalIsMissed() {
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
        val coordinationTransport = requireNotNull(harness.coordinatorEnvironment.coordinationTransport)
        coordinationTransport.dropFirstPhaseStatePredicate = {
                from,
                stepId,
                phaseState,
                _ ->
            from === harness.participantEnvironment &&
                stepId == AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP &&
                phaseState == AutomatedDiagnosticsPhaseState.READY
        }

        try {
            harness.startBothManualRuns()
            assertTrue(
                harness.advanceUntil(maxSteps = 960) {
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
            assertEquals(1, coordinationTransport.droppedPhaseStateCount)
            assertTrue(
                coordinatorStep.evidenceValue("Remote phase state") in setOf("RUNNING", "PASS")
            )
            assertTrue(
                (participantStep.evidenceValue("Phase-state send count")?.toIntOrNull() ?: 0) >= 2
            )
            assertEquals("true", coordinatorStep.evidenceValue("Barrier established"))
            assertEquals("true", participantStep.evidenceValue("Barrier established"))
        } finally {
            harness.cancel()
        }
    }

    @Test
    fun phaseSignalBarrierAcceptsSameStepAttemptReady() {
        val sharedRun = samplePhaseBarrierSharedRun()
        val failure = validateAutomatedDiagnosticsPhaseSignalForBarrier(
            signal = samplePhaseBarrierSignal(
                sharedRun = sharedRun,
                phaseState = AutomatedDiagnosticsPhaseState.READY
            ),
            expectedRun = sharedRun,
            expectedSenderPeerId = sharedRun.participantPeerId,
            expectedRecipientPeerId = sharedRun.coordinatorPeerId,
            expectedStepId = AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
            expectedAttemptNumber = 1,
            activeTransportPeerId = sharedRun.participantPeerId,
            localPeerId = sharedRun.coordinatorPeerId,
            observedAgeMillis = 0L,
            effectiveLeaseDurationMillis = 15_000L
        )

        assertEquals(null, failure)
    }

    @Test
    fun phaseSignalBarrierAcceptsSameStepAttemptRunning() {
        val sharedRun = samplePhaseBarrierSharedRun()
        val failure = validateAutomatedDiagnosticsPhaseSignalForBarrier(
            signal = samplePhaseBarrierSignal(
                sharedRun = sharedRun,
                phaseState = AutomatedDiagnosticsPhaseState.RUNNING
            ),
            expectedRun = sharedRun,
            expectedSenderPeerId = sharedRun.participantPeerId,
            expectedRecipientPeerId = sharedRun.coordinatorPeerId,
            expectedStepId = AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
            expectedAttemptNumber = 1,
            activeTransportPeerId = sharedRun.participantPeerId,
            localPeerId = sharedRun.coordinatorPeerId,
            observedAgeMillis = 0L,
            effectiveLeaseDurationMillis = 15_000L
        )

        assertEquals(null, failure)
    }

    @Test
    fun phaseSignalBarrierAcceptsSameStepAttemptPass() {
        val sharedRun = samplePhaseBarrierSharedRun()
        val failure = validateAutomatedDiagnosticsPhaseSignalForBarrier(
            signal = samplePhaseBarrierSignal(
                sharedRun = sharedRun,
                phaseState = AutomatedDiagnosticsPhaseState.PASS
            ),
            expectedRun = sharedRun,
            expectedSenderPeerId = sharedRun.participantPeerId,
            expectedRecipientPeerId = sharedRun.coordinatorPeerId,
            expectedStepId = AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
            expectedAttemptNumber = 1,
            activeTransportPeerId = sharedRun.participantPeerId,
            localPeerId = sharedRun.coordinatorPeerId,
            observedAgeMillis = 0L,
            effectiveLeaseDurationMillis = 15_000L
        )

        assertEquals(null, failure)
    }

    @Test
    fun phaseSignalBarrierRejectsWrongRun() {
        val sharedRun = samplePhaseBarrierSharedRun()
        val failure = requireNotNull(
            validateAutomatedDiagnosticsPhaseSignalForBarrier(
                signal = samplePhaseBarrierSignal(
                    sharedRun = sharedRun.copy(runId = "other-run")
                ),
                expectedRun = sharedRun,
                expectedSenderPeerId = sharedRun.participantPeerId,
                expectedRecipientPeerId = sharedRun.coordinatorPeerId,
                expectedStepId = AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                expectedAttemptNumber = 1,
                activeTransportPeerId = sharedRun.participantPeerId,
                localPeerId = sharedRun.coordinatorPeerId,
                observedAgeMillis = 0L,
                effectiveLeaseDurationMillis = 15_000L
            )
        )

        assertEquals(AutomatedDiagnosticsCoordinationRejectionReason.WRONG_RUN, failure.reason)
    }

    @Test
    fun phaseSignalBarrierRejectsWrongSession() {
        val sharedRun = samplePhaseBarrierSharedRun()
        val failure = requireNotNull(
            validateAutomatedDiagnosticsPhaseSignalForBarrier(
                signal = samplePhaseBarrierSignal(
                    sharedRun = sharedRun.copy(sessionAssociationId = "other-session")
                ),
                expectedRun = sharedRun,
                expectedSenderPeerId = sharedRun.participantPeerId,
                expectedRecipientPeerId = sharedRun.coordinatorPeerId,
                expectedStepId = AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                expectedAttemptNumber = 1,
                activeTransportPeerId = sharedRun.participantPeerId,
                localPeerId = sharedRun.coordinatorPeerId,
                observedAgeMillis = 0L,
                effectiveLeaseDurationMillis = 15_000L
            )
        )

        assertEquals(AutomatedDiagnosticsCoordinationRejectionReason.WRONG_SESSION, failure.reason)
    }

    @Test
    fun phaseSignalBarrierRejectsWrongSenderPeer() {
        val sharedRun = samplePhaseBarrierSharedRun()
        val failure = requireNotNull(
            validateAutomatedDiagnosticsPhaseSignalForBarrier(
                signal = samplePhaseBarrierSignal(
                    sharedRun = sharedRun,
                    peerId = "unexpected-sender"
                ),
                expectedRun = sharedRun,
                expectedSenderPeerId = sharedRun.participantPeerId,
                expectedRecipientPeerId = sharedRun.coordinatorPeerId,
                expectedStepId = AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                expectedAttemptNumber = 1,
                activeTransportPeerId = sharedRun.participantPeerId,
                localPeerId = sharedRun.coordinatorPeerId,
                observedAgeMillis = 0L,
                effectiveLeaseDurationMillis = 15_000L
            )
        )

        assertEquals(AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER, failure.reason)
    }

    @Test
    fun phaseSignalBarrierRejectsWrongRecipientPeer() {
        val sharedRun = samplePhaseBarrierSharedRun()
        val failure = requireNotNull(
            validateAutomatedDiagnosticsPhaseSignalForBarrier(
                signal = samplePhaseBarrierSignal(
                    sharedRun = sharedRun,
                    expectedRemotePeerId = "unexpected-recipient"
                ),
                expectedRun = sharedRun,
                expectedSenderPeerId = sharedRun.participantPeerId,
                expectedRecipientPeerId = sharedRun.coordinatorPeerId,
                expectedStepId = AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                expectedAttemptNumber = 1,
                activeTransportPeerId = sharedRun.participantPeerId,
                localPeerId = sharedRun.coordinatorPeerId,
                observedAgeMillis = 0L,
                effectiveLeaseDurationMillis = 15_000L
            )
        )

        assertEquals(AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER, failure.reason)
    }

    @Test
    fun phaseSignalBarrierRejectsPreviousAttempt() {
        val sharedRun = samplePhaseBarrierSharedRun()
        val failure = requireNotNull(
            validateAutomatedDiagnosticsPhaseSignalForBarrier(
                signal = samplePhaseBarrierSignal(
                    sharedRun = sharedRun,
                    attemptNumber = 1
                ),
                expectedRun = sharedRun,
                expectedSenderPeerId = sharedRun.participantPeerId,
                expectedRecipientPeerId = sharedRun.coordinatorPeerId,
                expectedStepId = AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                expectedAttemptNumber = 2,
                activeTransportPeerId = sharedRun.participantPeerId,
                localPeerId = sharedRun.coordinatorPeerId,
                observedAgeMillis = 0L,
                effectiveLeaseDurationMillis = 15_000L
            )
        )

        assertEquals(AutomatedDiagnosticsCoordinationRejectionReason.STALE, failure.reason)
    }

    @Test
    fun phaseSignalBarrierRejectsFutureAttempt() {
        val sharedRun = samplePhaseBarrierSharedRun()
        val failure = requireNotNull(
            validateAutomatedDiagnosticsPhaseSignalForBarrier(
                signal = samplePhaseBarrierSignal(
                    sharedRun = sharedRun,
                    attemptNumber = 3
                ),
                expectedRun = sharedRun,
                expectedSenderPeerId = sharedRun.participantPeerId,
                expectedRecipientPeerId = sharedRun.coordinatorPeerId,
                expectedStepId = AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                expectedAttemptNumber = 2,
                activeTransportPeerId = sharedRun.participantPeerId,
                localPeerId = sharedRun.coordinatorPeerId,
                observedAgeMillis = 0L,
                effectiveLeaseDurationMillis = 15_000L
            )
        )

        assertEquals(
            AutomatedDiagnosticsCoordinationRejectionReason.UNEXPECTED_PHASE,
            failure.reason
        )
    }

    @Test
    fun phaseSignalBarrierRejectsPreviousStep() {
        val sharedRun = samplePhaseBarrierSharedRun()
        val failure = requireNotNull(
            validateAutomatedDiagnosticsPhaseSignalForBarrier(
                signal = samplePhaseBarrierSignal(
                    sharedRun = sharedRun,
                    stepId = AutomatedDiagnosticStepId.BLE_STABILITY
                ),
                expectedRun = sharedRun,
                expectedSenderPeerId = sharedRun.participantPeerId,
                expectedRecipientPeerId = sharedRun.coordinatorPeerId,
                expectedStepId = AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                expectedAttemptNumber = 1,
                activeTransportPeerId = sharedRun.participantPeerId,
                localPeerId = sharedRun.coordinatorPeerId,
                observedAgeMillis = 0L,
                effectiveLeaseDurationMillis = 15_000L
            )
        )

        assertEquals(AutomatedDiagnosticsCoordinationRejectionReason.STALE, failure.reason)
    }

    @Test
    fun phaseSignalBarrierRejectsFutureStep() {
        val sharedRun = samplePhaseBarrierSharedRun()
        val failure = requireNotNull(
            validateAutomatedDiagnosticsPhaseSignalForBarrier(
                signal = samplePhaseBarrierSignal(
                    sharedRun = sharedRun,
                    stepId = AutomatedDiagnosticStepId.WIFI_DIRECT_SOCKET
                ),
                expectedRun = sharedRun,
                expectedSenderPeerId = sharedRun.participantPeerId,
                expectedRecipientPeerId = sharedRun.coordinatorPeerId,
                expectedStepId = AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                expectedAttemptNumber = 1,
                activeTransportPeerId = sharedRun.participantPeerId,
                localPeerId = sharedRun.coordinatorPeerId,
                observedAgeMillis = 0L,
                effectiveLeaseDurationMillis = 15_000L
            )
        )

        assertEquals(
            AutomatedDiagnosticsCoordinationRejectionReason.UNEXPECTED_PHASE,
            failure.reason
        )
    }

    @Test
    fun phaseSignalBarrierRejectsLocallyStaleObservation() {
        val sharedRun = samplePhaseBarrierSharedRun()
        val failure = requireNotNull(
            validateAutomatedDiagnosticsPhaseSignalForBarrier(
                signal = samplePhaseBarrierSignal(sharedRun = sharedRun),
                expectedRun = sharedRun,
                expectedSenderPeerId = sharedRun.participantPeerId,
                expectedRecipientPeerId = sharedRun.coordinatorPeerId,
                expectedStepId = AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                expectedAttemptNumber = 1,
                activeTransportPeerId = sharedRun.participantPeerId,
                localPeerId = sharedRun.coordinatorPeerId,
                observedAgeMillis = 15_001L,
                effectiveLeaseDurationMillis = 15_000L
            )
        )

        assertEquals(AutomatedDiagnosticsCoordinationRejectionReason.STALE, failure.reason)
    }

    @Test
    fun serverReadyValidationAcceptsRoleInvertedCurrentRunSignal() {
        val sharedRun = sampleServerReadySharedRun(
            coordinatorPeerId = "c32c4ccd3a15fe4a",
            participantPeerId = "c93a044154b4d770"
        )
        val signal = sampleServerReadySignal(
            sharedRun = sharedRun,
            peerId = sharedRun.participantPeerId,
            expectedClientPeerId = sharedRun.coordinatorPeerId
        )

        val failure = validateAutomatedDiagnosticsServerReadySignalForSocketStep(
            signal = signal,
            expectedRun = sharedRun,
            expectedOwnerPeerId = sharedRun.participantPeerId,
            expectedClientPeerId = sharedRun.coordinatorPeerId,
            activeTransportPeerId = sharedRun.participantPeerId,
            localPeerId = sharedRun.coordinatorPeerId,
            localWifiDirectRole = WifiDirectConnectionRole.CLIENT,
            observedAgeMillis = 0L,
            effectiveLeaseDurationMillis = signal.expiresAtMillis - signal.createdAtMillis,
            minimumCreatedAtMillis = signal.createdAtMillis,
            groupReady = true
        )

        assertEquals(null, failure)
    }

    @Test
    fun serverReadyValidationRejectsWrongRun() {
        val sharedRun = sampleServerReadySharedRun()
        val failure = requireNotNull(
            validateAutomatedDiagnosticsServerReadySignalForSocketStep(
                signal = sampleServerReadySignal(
                    sharedRun = sharedRun.copy(runId = "other-run")
                ),
                expectedRun = sharedRun,
                expectedOwnerPeerId = sharedRun.coordinatorPeerId,
                expectedClientPeerId = sharedRun.participantPeerId,
                activeTransportPeerId = sharedRun.coordinatorPeerId,
                localPeerId = sharedRun.participantPeerId,
                localWifiDirectRole = WifiDirectConnectionRole.CLIENT,
                observedAgeMillis = 0L,
                effectiveLeaseDurationMillis = 8_000L,
                minimumCreatedAtMillis = 0L,
                groupReady = true
            )
        )

        assertEquals(AutomatedDiagnosticsCoordinationRejectionReason.WRONG_RUN, failure.reason)
        assertEquals("signal.sharedRun.runId", failure.fieldName)
    }

    @Test
    fun serverReadyValidationRejectsWrongSession() {
        val sharedRun = sampleServerReadySharedRun()
        val failure = requireNotNull(
            validateAutomatedDiagnosticsServerReadySignalForSocketStep(
                signal = sampleServerReadySignal(
                    sharedRun = sharedRun.copy(sessionAssociationId = "other-session")
                ),
                expectedRun = sharedRun,
                expectedOwnerPeerId = sharedRun.coordinatorPeerId,
                expectedClientPeerId = sharedRun.participantPeerId,
                activeTransportPeerId = sharedRun.coordinatorPeerId,
                localPeerId = sharedRun.participantPeerId,
                localWifiDirectRole = WifiDirectConnectionRole.CLIENT,
                observedAgeMillis = 0L,
                effectiveLeaseDurationMillis = 8_000L,
                minimumCreatedAtMillis = 0L,
                groupReady = true
            )
        )

        assertEquals(AutomatedDiagnosticsCoordinationRejectionReason.WRONG_SESSION, failure.reason)
        assertEquals("signal.sharedRun.sessionAssociationId", failure.fieldName)
    }

    @Test
    fun serverReadyValidationRejectsWrongTransportSenderPeer() {
        val sharedRun = sampleServerReadySharedRun()
        val signal = sampleServerReadySignal(sharedRun = sharedRun)
        val failure = requireNotNull(
            validateAutomatedDiagnosticsServerReadySignalForSocketStep(
                signal = signal,
                expectedRun = sharedRun,
                expectedOwnerPeerId = sharedRun.coordinatorPeerId,
                expectedClientPeerId = sharedRun.participantPeerId,
                activeTransportPeerId = "unexpected-transport-peer",
                localPeerId = sharedRun.participantPeerId,
                localWifiDirectRole = WifiDirectConnectionRole.CLIENT,
                observedAgeMillis = 0L,
                effectiveLeaseDurationMillis = signal.expiresAtMillis - signal.createdAtMillis,
                minimumCreatedAtMillis = 0L,
                groupReady = true
            )
        )

        assertEquals(AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER, failure.reason)
        assertEquals("snapshot.activeTransportPeerId", failure.fieldName)
    }

    @Test
    fun serverReadyValidationRejectsWrongOwnerPeer() {
        val sharedRun = sampleServerReadySharedRun()
        val failure = requireNotNull(
            validateAutomatedDiagnosticsServerReadySignalForSocketStep(
                signal = sampleServerReadySignal(
                    sharedRun = sharedRun,
                    peerId = "unexpected-owner-peer"
                ),
                expectedRun = sharedRun,
                expectedOwnerPeerId = sharedRun.coordinatorPeerId,
                expectedClientPeerId = sharedRun.participantPeerId,
                activeTransportPeerId = sharedRun.coordinatorPeerId,
                localPeerId = sharedRun.participantPeerId,
                localWifiDirectRole = WifiDirectConnectionRole.CLIENT,
                observedAgeMillis = 0L,
                effectiveLeaseDurationMillis = 8_000L,
                minimumCreatedAtMillis = 0L,
                groupReady = true
            )
        )

        assertEquals(AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER, failure.reason)
        assertEquals("signal.peerId", failure.fieldName)
    }

    @Test
    fun serverReadyValidationRejectsWrongClientPeer() {
        val sharedRun = sampleServerReadySharedRun()
        val failure = requireNotNull(
            validateAutomatedDiagnosticsServerReadySignalForSocketStep(
                signal = sampleServerReadySignal(
                    sharedRun = sharedRun,
                    expectedClientPeerId = "unexpected-client-peer"
                ),
                expectedRun = sharedRun,
                expectedOwnerPeerId = sharedRun.coordinatorPeerId,
                expectedClientPeerId = sharedRun.participantPeerId,
                activeTransportPeerId = sharedRun.coordinatorPeerId,
                localPeerId = sharedRun.participantPeerId,
                localWifiDirectRole = WifiDirectConnectionRole.CLIENT,
                observedAgeMillis = 0L,
                effectiveLeaseDurationMillis = 8_000L,
                minimumCreatedAtMillis = 0L,
                groupReady = true
            )
        )

        assertEquals(AutomatedDiagnosticsCoordinationRejectionReason.WRONG_PEER, failure.reason)
        assertEquals("signal.expectedClientPeerId", failure.fieldName)
    }

    @Test
    fun serverReadyValidationRejectsLocallyStaleObservation() {
        val sharedRun = sampleServerReadySharedRun()
        val signal = sampleServerReadySignal(sharedRun = sharedRun)
        val failure = requireNotNull(
            validateAutomatedDiagnosticsServerReadySignalForSocketStep(
                signal = signal,
                expectedRun = sharedRun,
                expectedOwnerPeerId = sharedRun.coordinatorPeerId,
                expectedClientPeerId = sharedRun.participantPeerId,
                activeTransportPeerId = sharedRun.coordinatorPeerId,
                localPeerId = sharedRun.participantPeerId,
                localWifiDirectRole = WifiDirectConnectionRole.CLIENT,
                observedAgeMillis = 8_001L,
                effectiveLeaseDurationMillis = 8_000L,
                minimumCreatedAtMillis = 0L,
                groupReady = true
            )
        )

        assertEquals(AutomatedDiagnosticsCoordinationRejectionReason.STALE, failure.reason)
        assertEquals("signal.localMonotonicLeaseMillis", failure.fieldName)
    }

    @Test
    fun serverReadyValidationRejectsSignalCreatedBeforeCurrentAttemptWindow() {
        val sharedRun = sampleServerReadySharedRun()
        val signal = sampleServerReadySignal(sharedRun = sharedRun, createdAtMillis = 1_000L)
        val failure = requireNotNull(
            validateAutomatedDiagnosticsServerReadySignalForSocketStep(
                signal = signal,
                expectedRun = sharedRun,
                expectedOwnerPeerId = sharedRun.coordinatorPeerId,
                expectedClientPeerId = sharedRun.participantPeerId,
                activeTransportPeerId = sharedRun.coordinatorPeerId,
                localPeerId = sharedRun.participantPeerId,
                localWifiDirectRole = WifiDirectConnectionRole.CLIENT,
                observedAgeMillis = 0L,
                effectiveLeaseDurationMillis = signal.expiresAtMillis - signal.createdAtMillis,
                minimumCreatedAtMillis = 1_001L,
                groupReady = true
            )
        )

        assertEquals(AutomatedDiagnosticsCoordinationRejectionReason.STALE, failure.reason)
        assertEquals("signal.createdAtMillis", failure.fieldName)
    }

    @Test
    fun stepElevenPhaseObservationClearsOnStopResetAndNewRun() {
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
            assertTrue(
                harness.advanceUntil(maxSteps = 360) {
                    harness.coordinatorStep(AutomatedDiagnosticStepId.BLE_STABILITY).status ==
                        AutomatedDiagnosticStepStatus.PASS &&
                        harness.participantStep(AutomatedDiagnosticStepId.BLE_STABILITY).status ==
                        AutomatedDiagnosticStepStatus.PASS
                }
            )
            assertTrue(
                harness.advanceCoordinatorUntil(maxSteps = 120) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                    ).status == AutomatedDiagnosticStepStatus.RUNNING
                }
            )

            val waitingStep = harness.coordinatorStep(
                AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
            )
            val currentAttemptNumber =
                waitingStep.evidenceValue("Phase attempt number")?.toIntOrNull() ?: 1
            val sharedRun = harness.coordinatorSharedRun()
            val createdAtMillis = harness.coordinatorEnvironment.currentWallClockMillis()
            val priorObservedSignal =
                samplePhaseBarrierSignal(
                    sharedRun = sharedRun,
                    peerId = harness.participantEnvironment.localPeerId,
                    expectedRemotePeerId = harness.coordinatorEnvironment.localPeerId,
                    stepId = AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
                    attemptNumber = currentAttemptNumber,
                    createdAtMillis = createdAtMillis,
                    expiresAtMillis = createdAtMillis +
                        AutomatedDiagnosticsTimingPolicy.default()
                            .automatedDiagnosticsPhaseStateLeaseMillis
                )
            harness.coordinatorEnvironment.latestAutomatedDiagnosticsPhaseSignal =
                priorObservedSignal
            harness.coordinatorEnvironment.latestAutomatedDiagnosticsPhaseSignalsByStep =
                mapOf(
                    AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP to
                        priorObservedSignal
                )

            harness.coordinatorRunner.stop()
            harness.participantRunner.stop()
            assertEquals(null, harness.coordinatorEnvironment.latestAutomatedDiagnosticsPhaseSignal)
            assertTrue(harness.coordinatorEnvironment.latestAutomatedDiagnosticsPhaseSignalsByStep.isEmpty())

            harness.coordinatorRunner.resetReport()
            harness.participantRunner.resetReport()
            harness.startBothManualRuns()
            assertTrue(
                harness.advanceUntil(maxSteps = 360) {
                    harness.coordinatorStep(AutomatedDiagnosticStepId.BLE_STABILITY).status ==
                        AutomatedDiagnosticStepStatus.PASS &&
                        harness.participantStep(AutomatedDiagnosticStepId.BLE_STABILITY).status ==
                        AutomatedDiagnosticStepStatus.PASS
                }
            )
            assertTrue(
                harness.advanceCoordinatorUntil(maxSteps = 120) {
                    harness.coordinatorStep(
                        AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
                    ).status == AutomatedDiagnosticStepStatus.RUNNING
                }
            )

            val restartedWaitingStep = harness.coordinatorStep(
                AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP
            )
            assertTrue(
                restartedWaitingStep.evidenceValue("Last phase-state rejection") in
                    setOf(null, "none")
            )
            assertTrue(
                restartedWaitingStep.evidenceValue("Phase-state accepted count") in
                    setOf(null, "0")
            )
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

    private fun createDefaultPhaseTwoHarness(
        timingPolicy: AutomatedDiagnosticsTimingPolicy = AutomatedDiagnosticsTimingPolicy.default()
    ): StepElevenHarness {
        val participantPhonePeer = wifiDirectPeer(
            name = "Participant Pixel",
            address = "aa:bb:cc:dd:ee:20"
        )
        val coordinatorPhonePeer = wifiDirectPeer(
            name = "Coordinator Pixel",
            address = "aa:bb:cc:dd:ee:10"
        )
        return createStepElevenHarness(
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
            timingPolicy = timingPolicy
        )
    }

    private fun StepElevenHarness.awaitMutualStepFifteenPass(
        maxSteps: Int = 1800
    ): Boolean {
        return advanceUntil(maxSteps = maxSteps) {
            coordinatorStep(
                AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_ACCEPT
            ).status == AutomatedDiagnosticStepStatus.PASS &&
                participantStep(
                    AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_ACCEPT
                ).status == AutomatedDiagnosticStepStatus.PASS
        }
    }

    private fun StepElevenHarness.awaitMutualStepSixteenPassAndStepSeventeenStart(
        maxSteps: Int = 2400
    ): Boolean {
        return advanceUntil(maxSteps = maxSteps) {
            coordinatorStep(
                AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT
            ).status == AutomatedDiagnosticStepStatus.PASS &&
                participantStep(
                    AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_SOCKET_HINT
                ).status == AutomatedDiagnosticStepStatus.PASS &&
                coordinatorStep(
                    AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_TRIGGER
                ).status != AutomatedDiagnosticStepStatus.WAITING &&
                participantStep(
                    AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_TRIGGER
                ).status != AutomatedDiagnosticStepStatus.WAITING
        }
    }

    private fun StepElevenHarness.awaitMutualStepSeventeenPass(
        maxSteps: Int = 1200
    ): Boolean {
        return advanceUntil(maxSteps = maxSteps) {
            coordinatorStep(
                AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_TRIGGER
            ).status == AutomatedDiagnosticStepStatus.PASS &&
                participantStep(
                    AutomatedDiagnosticStepId.HYBRID_BOOTSTRAP_TRIGGER
                ).status == AutomatedDiagnosticStepStatus.PASS
        }
    }

    private fun StepElevenHarness.awaitMutualStepNineteenPass(
        maxSteps: Int = 2000
    ): Boolean {
        return advanceUntil(maxSteps = maxSteps) {
            coordinatorStep(
                AutomatedDiagnosticStepId.PRIVATE_ENCRYPTED_MESSAGE_PROBE
            ).status == AutomatedDiagnosticStepStatus.PASS &&
                participantStep(
                    AutomatedDiagnosticStepId.PRIVATE_ENCRYPTED_MESSAGE_PROBE
                ).status == AutomatedDiagnosticStepStatus.PASS
        }
    }

    private fun StepElevenHarness.awaitMutualStepTwentyOnePass(
        maxSteps: Int = 3600
    ): Boolean {
        return advanceUntil(maxSteps = maxSteps) {
            coordinatorStep(
                AutomatedDiagnosticStepId.FINAL_END_TO_END_VALIDATION
            ).status == AutomatedDiagnosticStepStatus.PASS &&
                participantStep(
                    AutomatedDiagnosticStepId.FINAL_END_TO_END_VALIDATION
                ).status == AutomatedDiagnosticStepStatus.PASS
        }
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

        fun advanceCoordinatorOnly(
            stepCount: Int
        ) {
            repeat(stepCount) {
                coordinatorWallClock.advanceMillis(stepAdvanceMillis)
                coordinatorDelay.advanceSteps(1)
            }
        }

        fun advanceParticipantOnly(
            stepCount: Int
        ) {
            repeat(stepCount) {
                participantWallClock.advanceMillis(stepAdvanceMillis)
                participantDelay.advanceSteps(1)
            }
        }

        fun advanceParticipantWallClock(
            millis: Long
        ) {
            participantWallClock.advanceMillis(millis)
        }

        fun advanceCoordinatorWallClock(
            millis: Long
        ) {
            coordinatorWallClock.advanceMillis(millis)
        }

        fun advanceCoordinatorUntil(
            maxSteps: Int,
            condition: () -> Boolean
        ): Boolean {
            repeat(maxSteps) {
                if (condition()) {
                    return true
                }
                advanceCoordinatorOnly(1)
            }
            return condition()
        }

        fun advanceParticipantUntil(
            maxSteps: Int,
            condition: () -> Boolean
        ): Boolean {
            repeat(maxSteps) {
                if (condition()) {
                    return true
                }
                advanceParticipantOnly(1)
            }
            return condition()
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

        fun coordinatorSharedRun(): AutomatedDiagnosticsSharedRun {
            return coordinatorEnvironment.latestAutomatedDiagnosticsParticipantJoin?.sharedRun
                ?: coordinatorEnvironment.latestAutomatedDiagnosticsRunAnnouncement?.sharedRun
                ?: participantEnvironment.latestAutomatedDiagnosticsParticipantJoin?.sharedRun
                ?: participantEnvironment.latestAutomatedDiagnosticsRunAnnouncement?.sharedRun
                ?: error("Expected an automated diagnostics shared run before Step 11.")
        }

        fun reportText(): String {
            return "Coordinator report:\n${coordinatorRunner.state.value.reportText}\n\nParticipant report:\n${participantRunner.state.value.reportText}"
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
        var globalApplicationSenderId: String = "diagnostics-user"
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
        var latestAutomatedDiagnosticsPhaseSignal: AutomatedDiagnosticsPhaseSignal? = null
        var latestAutomatedDiagnosticsPhaseSignalsByStep:
            Map<AutomatedDiagnosticStepId, AutomatedDiagnosticsPhaseSignal> = emptyMap()
        var latestAutomatedDiagnosticsServerReadySignal: AutomatedDiagnosticsServerReadySignal? = null
        var lastAutomatedDiagnosticsCoordinationStatus: String? = null
        var lastAutomatedDiagnosticsWifiDirectPeerReadyStatus: String? = null
        var lastAutomatedDiagnosticsPhaseStatus: String? = null
        var lastAutomatedDiagnosticsServerReadyStatus: String? = null
        var latestAutomatedDiagnosticsHybridAcceptObservation:
            AutomatedDiagnosticsHybridAcceptObservation? = null
        var latestAutomatedDiagnosticsHybridSocketHintObservation:
            AutomatedDiagnosticsHybridSocketHintObservation? = null
        var recentBleTransportLocalSendTraces: List<BleTransportLocalSendTrace> = emptyList()
        var recentAutomatedDiagnosticsApplicationProbeObservations:
            List<AutomatedDiagnosticsApplicationProbeObservation> = emptyList()
        var recentAutomatedDiagnosticsApplicationProbeTransportReceiveEvents:
            List<AutomatedDiagnosticsApplicationProbeTransportReceiveEvent> = emptyList()
        var recentAutomatedDiagnosticsApplicationProbeReceiveDiagnostics:
            List<AutomatedDiagnosticsApplicationProbeReceiveDiagnostic> = emptyList()
        var hybridBootstrapManualTriggerSnapshot: HybridBootstrapManualTriggerSnapshot =
            blockedHybridTriggerSnapshot()
        var hybridBootstrapManualAcceptAvailable: Boolean = true
        var hybridBootstrapManualAcceptBlockedReason: String? = null
        var lastHybridBootstrapManualAcceptStatus: String? = null
        var hybridBootstrapManualOfferAvailable: Boolean = true
        var hybridBootstrapManualOfferBlockedReason: String? = null
        var lastHybridBootstrapManualOfferStatus: String? = null
        var lastHybridBootstrapManualSocketHintStatus: String? = null
        var onHybridBootstrapManualTriggerRequested:
            (suspend () -> HybridBootstrapCommandTriggerResult)? = null
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
        var monotonicMillisProvider: () -> Long = { 0L }
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
        var automatedDiagnosticsPhaseStateRequestCount: Int = 0
        var automatedDiagnosticsServerReadyRequestCount: Int = 0
        var registerAutomatedDiagnosticsWifiDirectServiceCallCount: Int = 0
        var startAutomatedDiagnosticsWifiDirectServiceDiscoveryCallCount: Int = 0
        var clearAutomatedDiagnosticsWifiDirectServiceDiscoveryCallCount: Int = 0
        var clearSharedRunCoordinationStateCallCount: Int = 0
        var clearAutomatedDiagnosticsCoordinationStateCallCount: Int = 0
        var sendGlobalChatMessageCallCount: Int = 0
        var sendPrivateChatMessageCallCount: Int = 0
        var removeMessagesByIdsCallCount: Int = 0
        var lastRemovedMessageIds: Set<String> = emptySet()
        var lastActuallyRemovedMessageIds: Set<String> = emptySet()
        var removeMessagesByIdsOverride: ((Set<String>) -> Set<String>)? = null
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
        private val localPhaseThreeMessageIds = linkedSetOf<String>()
        private var automatedDiagnosticsGlobalMessageCounter: Long = 0L
        private var automatedDiagnosticsPrivateMessageCounter: Long = 0L
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
            latestAutomatedDiagnosticsPhaseSignal = null
            latestAutomatedDiagnosticsPhaseSignalsByStep = emptyMap()
            lastAutomatedDiagnosticsPhaseStatus = null
            latestAutomatedDiagnosticsServerReadySignal = null
            lastAutomatedDiagnosticsServerReadyStatus = null
            latestAutomatedDiagnosticsHybridAcceptObservation = null
            latestAutomatedDiagnosticsHybridSocketHintObservation = null
            recentBleTransportLocalSendTraces = emptyList()
            recentAutomatedDiagnosticsApplicationProbeObservations = emptyList()
            recentAutomatedDiagnosticsApplicationProbeTransportReceiveEvents = emptyList()
            recentAutomatedDiagnosticsApplicationProbeReceiveDiagnostics = emptyList()
            localPhaseThreeMessageIds.clear()
        }

        fun recordBleTransportLocalSendTrace(
            trace: BleTransportLocalSendTrace
        ) {
            recentBleTransportLocalSendTraces = recentBleTransportLocalSendTraces + trace
        }

        fun recordAutomatedDiagnosticsApplicationProbeObservation(
            observation: AutomatedDiagnosticsApplicationProbeObservation
        ) {
            if (observation.messageId in localPhaseThreeMessageIds) {
                return
            }
            localPhaseThreeMessageIds += observation.messageId
            recentAutomatedDiagnosticsApplicationProbeObservations =
                appendAutomatedDiagnosticsApplicationProbeObservation(
                    observations = recentAutomatedDiagnosticsApplicationProbeObservations,
                    observation = observation
                )
        }

        fun recordAutomatedDiagnosticsApplicationProbeTransportReceiveEvent(
            event: AutomatedDiagnosticsApplicationProbeTransportReceiveEvent
        ) {
            recentAutomatedDiagnosticsApplicationProbeTransportReceiveEvents =
                recentAutomatedDiagnosticsApplicationProbeTransportReceiveEvents + event
        }

        fun recordAutomatedDiagnosticsApplicationProbeReceiveDiagnostic(
            diagnostic: AutomatedDiagnosticsApplicationProbeReceiveDiagnostic
        ) {
            recentAutomatedDiagnosticsApplicationProbeReceiveDiagnostics =
                recentAutomatedDiagnosticsApplicationProbeReceiveDiagnostics + diagnostic
        }

        fun removeMessagesByIds(
            messageIds: Set<String>
        ): Set<String> {
            removeMessagesByIdsCallCount += 1
            val sanitizedMessageIds = messageIds
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet()
            lastRemovedMessageIds = sanitizedMessageIds
            val removableIds = sanitizedMessageIds.intersect(localPhaseThreeMessageIds)
            val removedIds = removeMessagesByIdsOverride?.invoke(removableIds)
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.toSet()
                ?.intersect(removableIds)
                ?: removableIds
            lastActuallyRemovedMessageIds = removedIds
            localPhaseThreeMessageIds.removeAll(removedIds)
            recentAutomatedDiagnosticsApplicationProbeObservations =
                recentAutomatedDiagnosticsApplicationProbeObservations.filterNot { observation ->
                    observation.messageId in removedIds
                }
            return removedIds
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

        fun currentMonotonicMillis(): Long {
            return monotonicMillisProvider()
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
        }

        fun applyReceivedHybridBootstrapResult(
            result: BleTransportReceiveResult,
            provider: HybridBootstrapDecisionProvider
        ) {
            automatedDiagnosticsHybridAcceptObservationAfterReceiveOrNull(
                result = result,
                observedAtMonotonicMillis = currentMonotonicMillis()
            )?.let { observation ->
                latestAutomatedDiagnosticsHybridAcceptObservation = observation
            }
            automatedDiagnosticsHybridSocketHintObservationAfterReceiveOrNull(
                result = result,
                observedAtMonotonicMillis = currentMonotonicMillis()
            )?.let { observation ->
                latestAutomatedDiagnosticsHybridSocketHintObservation = observation
            }
            hybridBootstrapDecisionAfterReceiveOrNull(
                result = result,
                provider = provider
            )?.let { decision ->
                applyCurrentHybridBootstrapDecision(decision)
            }
        }

        fun applyCurrentHybridBootstrapDecision(
            decision: HybridBootstrapDecision
        ) {
            hybridBootstrapDecision = decision
            hybridBootstrapDiagnostics = HybridBootstrapDiagnosticsFormatter.format(decision)
            val commandBuildResult = HybridBootstrapAttemptCommandBuilder.build(
                decision = HybridBootstrapAttemptPolicy.decide(
                    resolution = HybridBootstrapSocketEndpointResolver.resolve(
                        decision = decision,
                        socketHintObservation = currentHybridBootstrapSocketHintObservationOrNull()
                    ),
                    requestedAtMillis = currentWallClockMillis(),
                    currentMonotonicMillis = currentMonotonicMillis(),
                    maxEndpointAgeMillis =
                        HybridBootstrapAttemptPolicy.DEFAULT_MAX_ENDPOINT_AGE_MILLIS
                ),
                commandCreatedAtMillis = currentWallClockMillis()
            )
            hybridBootstrapManualTriggerSnapshot =
                currentHybridBootstrapManualTriggerSnapshot(
                    commandBuildResult = commandBuildResult,
                    latestTriggerResult = null
                )
        }

        private fun currentHybridBootstrapSocketHintObservationOrNull():
            HybridBootstrapSocketHintObservation? {
            val observation = latestAutomatedDiagnosticsHybridSocketHintObservation ?: return null
            return HybridBootstrapSocketHintObservation(
                peerId = observation.peerId,
                sessionId = observation.sessionId,
                groupOwnerAddress = observation.groupOwnerAddress,
                socketPort = observation.socketPort,
                createdAtMillis = observation.createdAtMillis,
                observedAtMonotonicMillis = observation.observedAtMonotonicMillis
            )
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
            monotonicMillisProvider = clock::nowMillis
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
                        latestAutomatedDiagnosticsPhaseSignal =
                            latestAutomatedDiagnosticsPhaseSignal,
                        latestAutomatedDiagnosticsPhaseSignalsByStep =
                            latestAutomatedDiagnosticsPhaseSignalsByStep,
                        latestAutomatedDiagnosticsServerReadySignal =
                            latestAutomatedDiagnosticsServerReadySignal,
                        latestAutomatedDiagnosticsHybridAcceptObservation =
                            latestAutomatedDiagnosticsHybridAcceptObservation,
                        latestAutomatedDiagnosticsHybridSocketHintObservation =
                            latestAutomatedDiagnosticsHybridSocketHintObservation,
                        recentBleTransportLocalSendTraces =
                            recentBleTransportLocalSendTraces,
                        recentAutomatedDiagnosticsApplicationProbeObservations =
                            recentAutomatedDiagnosticsApplicationProbeObservations,
                        recentAutomatedDiagnosticsApplicationProbeTransportReceiveEvents =
                            recentAutomatedDiagnosticsApplicationProbeTransportReceiveEvents,
                        recentAutomatedDiagnosticsApplicationProbeReceiveDiagnostics =
                            recentAutomatedDiagnosticsApplicationProbeReceiveDiagnostics,
                        lastAutomatedDiagnosticsCoordinationStatus =
                            lastAutomatedDiagnosticsCoordinationStatus,
                        lastAutomatedDiagnosticsWifiDirectPeerReadyStatus =
                            lastAutomatedDiagnosticsWifiDirectPeerReadyStatus
                                ?: snapshotWifiDirectPeerReadySignal?.let(
                                    ::automatedDiagnosticsWifiDirectPeerReadyStatusText
                                ),
                        lastAutomatedDiagnosticsPhaseStatus =
                            lastAutomatedDiagnosticsPhaseStatus,
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
                    requestAutomatedDiagnosticsPhaseState = {
                            sharedRun,
                            expectedRemotePeerId,
                            stepId,
                            phaseState,
                            attemptNumber,
                            applicationProbeDescriptors ->
                        automatedDiagnosticsPhaseStateRequestCount += 1
                        val createdAtMillis = currentWallClockMillis()
                        val result = coordinationTransport?.sendPhaseState(
                            from = this,
                            sharedRun = sharedRun,
                            expectedRemotePeerId = expectedRemotePeerId,
                            stepId = stepId,
                            phaseState = phaseState,
                            attemptNumber = attemptNumber,
                            applicationProbeDescriptors = applicationProbeDescriptors,
                            createdAtMillis = createdAtMillis
                        ) ?: runCatching {
                            val phaseStateLeaseMillis =
                                AutomatedDiagnosticsTimingPolicy.default()
                                    .automatedDiagnosticsPhaseStateLeaseMillis
                            AutomatedDiagnosticsPhaseSignal(
                                sharedRun = sharedRun,
                                peerId = localPeerId,
                                expectedRemotePeerId = expectedRemotePeerId,
                                stepId = stepId,
                                phaseState = phaseState,
                                attemptNumber = attemptNumber,
                                applicationProbeDescriptors = applicationProbeDescriptors,
                                createdAtMillis = createdAtMillis,
                                expiresAtMillis = createdAtMillis + phaseStateLeaseMillis
                            )
                        }.fold(
                            onSuccess = {
                                AutomatedDiagnosticsPhaseStateSendResult.Sent(it)
                            },
                            onFailure = { error ->
                                AutomatedDiagnosticsPhaseStateSendResult.InvalidSignal(
                                    error.message
                                        ?: "Automated diagnostics phase state is invalid."
                                )
                            }
                        )
                        lastAutomatedDiagnosticsPhaseStatus =
                            automatedDiagnosticsPhaseStateSendStatusText(result)
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
                        onHybridBootstrapManualTriggerRequested?.invoke()
                            ?: HybridBootstrapCommandTriggerResult.Executed(
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
                        val result = coordinationTransport?.sendHybridBootstrapManualOffer(
                            from = this
                        ) ?: HybridBootstrapManualOfferSendResult.Sent(
                            peerId = peerIdentityKey,
                            sessionId = localPeerId
                        )
                        lastHybridBootstrapManualOfferStatus = when (result) {
                            is HybridBootstrapManualOfferSendResult.Sent -> "sent"
                            HybridBootstrapManualOfferSendResult.NoActivePeer -> "no-active-peer"
                            HybridBootstrapManualOfferSendResult.NoActiveSession ->
                                "no-active-session"
                            HybridBootstrapManualOfferSendResult.WriterUnavailable ->
                                "writer-unavailable"
                            is HybridBootstrapManualOfferSendResult.InvalidOffer ->
                                "invalid:${result.reason}"
                            is HybridBootstrapManualOfferSendResult.SendFailed ->
                                "failed:${result.reason}"
                        }
                        result
                    },
                    requestHybridBootstrapManualAccept = {
                        hybridBootstrapManualAcceptRequestCount += 1
                        val result = coordinationTransport?.sendHybridBootstrapManualAccept(
                            from = this
                        ) ?: HybridBootstrapManualAcceptSendResult.Sent(
                            peerId = peerIdentityKey,
                            sessionId = peerIdentityKey
                        )
                        lastHybridBootstrapManualAcceptStatus = when (result) {
                            is HybridBootstrapManualAcceptSendResult.Sent -> "sent"
                            HybridBootstrapManualAcceptSendResult.NoOfferCandidate ->
                                "no-offer-candidate"
                            HybridBootstrapManualAcceptSendResult.NoActivePeer ->
                                "no-active-peer"
                            HybridBootstrapManualAcceptSendResult.NoActiveSession ->
                                "no-active-session"
                            HybridBootstrapManualAcceptSendResult.WriterUnavailable ->
                                "writer-unavailable"
                            is HybridBootstrapManualAcceptSendResult.InvalidAccept ->
                                "invalid:${result.reason}"
                            is HybridBootstrapManualAcceptSendResult.SendFailed ->
                                "failed:${result.reason}"
                        }
                        result
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
                    },
                    sendGlobalChatMessage = { text ->
                        sendGlobalChatMessageCallCount += 1
                        val marker = requireNotNull(
                            automatedDiagnosticsApplicationProbeMarkerOrNull(text)
                        ) {
                            "Expected an automated diagnostics application probe marker."
                        }
                        val targetPeerId = coordinationTransport?.otherPeerIdFor(this)
                            ?: activeTransportPeerId
                            ?: peerIdentityKey
                        val messageId =
                            "global-probe-${localPeerId.lowercase()}-${automatedDiagnosticsGlobalMessageCounter++}"
                        coordinationTransport?.deliverAutomatedDiagnosticsApplicationProbe(
                            from = this,
                            messageId = messageId,
                            marker = marker,
                            senderPeerId = localPeerId,
                            applicationSenderId = globalApplicationSenderId,
                            receiverPeerId = targetPeerId,
                            privateChatId = null,
                            observedAtMonotonicMillis = currentMonotonicMillis(),
                            observedAtWallClockMillis = currentWallClockMillis()
                        )
                        localPhaseThreeMessageIds += messageId
                        GlobalQueuedChatSubmissionResult(
                            queuedMessage = gr.hua.aurora.model.OutgoingChatMessage(
                                messageId = messageId,
                                threadId = "global",
                                userText = text,
                                createdAtMillis = currentWallClockMillis(),
                                status = gr.hua.aurora.model.MessageStatus.SENT
                            ),
                            transportResult = GlobalMeshDeliveryResult.QueuedToActivePeer(
                                peerId = targetPeerId
                            )
                        )
                    },
                    sendPrivateChatMessage = { peerId, text ->
                        sendPrivateChatMessageCallCount += 1
                        val privateChatId = privateChatIdentitiesByPeerId[peerId]?.privateChatId
                        if (privateChatId == null) {
                            PrivateQueuedChatSubmissionResult(
                                queuedMessage = gr.hua.aurora.model.OutgoingChatMessage(
                                    messageId = "private-probe-unavailable",
                                    threadId = "private:$peerId",
                                    userText = text,
                                    createdAtMillis = currentWallClockMillis(),
                                    status = gr.hua.aurora.model.MessageStatus.FAILED
                                ),
                                transportResult = PrivateChatMessageSendResult.KeysUnavailable
                            )
                        } else {
                            val marker = requireNotNull(
                                automatedDiagnosticsApplicationProbeMarkerOrNull(text)
                            ) {
                                "Expected an automated diagnostics application probe marker."
                            }
                            val messageId =
                                "private-probe-${localPeerId.lowercase()}-${automatedDiagnosticsPrivateMessageCounter++}"
                            coordinationTransport?.deliverAutomatedDiagnosticsApplicationProbe(
                                from = this,
                                messageId = messageId,
                                marker = marker,
                                senderPeerId = localPeerId,
                                applicationSenderId = localPeerId,
                                receiverPeerId = peerId,
                                privateChatId = privateChatId,
                                observedAtMonotonicMillis = currentMonotonicMillis(),
                                observedAtWallClockMillis = currentWallClockMillis()
                            )
                            localPhaseThreeMessageIds += messageId
                            PrivateQueuedChatSubmissionResult(
                                queuedMessage = gr.hua.aurora.model.OutgoingChatMessage(
                                    messageId = messageId,
                                    threadId = "private:$peerId",
                                    userText = text,
                                    createdAtMillis = currentWallClockMillis(),
                                    status = gr.hua.aurora.model.MessageStatus.SENT
                                ),
                                transportResult = PrivateChatMessageSendResult.SubmittedLocally
                            )
                        }
                    },
                    removeMessagesByIds = { messageIds ->
                        removeMessagesByIds(messageIds)
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
        var dropFirstPhaseStatePredicate:
            ((FakePhaseOneEnvironment, AutomatedDiagnosticStepId, AutomatedDiagnosticsPhaseState, Int) -> Boolean)? =
            null
        var droppedPhaseStateCount: Int = 0
        var hybridBootstrapAcceptDropsRemaining: Int = 0
        var alwaysDropHybridBootstrapAccept: Boolean = false
        var droppedHybridBootstrapAcceptCount: Int = 0
        var hybridBootstrapSocketHintDropsRemaining: Int = 0
        var alwaysDropHybridBootstrapSocketHint: Boolean = false
        var droppedHybridBootstrapSocketHintCount: Int = 0
        var dropAutomatedDiagnosticsApplicationProbePredicate:
            ((AutomatedDiagnosticsApplicationProbeMarker, String, String, String?) -> Boolean)? =
            null
        var duplicateAutomatedDiagnosticsApplicationProbePredicate:
            ((AutomatedDiagnosticsApplicationProbeMarker, String, String, String?) -> Boolean)? =
            null
        var droppedAutomatedDiagnosticsApplicationProbeCount: Int = 0
        var duplicatedAutomatedDiagnosticsApplicationProbeCount: Int = 0
        var forcedGroupOwnerPeerId: String? = null

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

        fun sendPhaseState(
            from: FakePhaseOneEnvironment,
            sharedRun: AutomatedDiagnosticsSharedRun,
            expectedRemotePeerId: String,
            stepId: AutomatedDiagnosticStepId,
            phaseState: AutomatedDiagnosticsPhaseState,
            attemptNumber: Int,
            applicationProbeDescriptors: List<AutomatedDiagnosticsPhaseApplicationProbeDescriptor>,
            createdAtMillis: Long
        ): AutomatedDiagnosticsPhaseStateSendResult {
            val sender = endpointFor(from)
            val receiver = otherEndpoint(from)
            val shouldDrop =
                dropFirstPhaseStatePredicate?.invoke(
                    from,
                    stepId,
                    phaseState,
                    attemptNumber
                ) == true
            if (shouldDrop) {
                droppedPhaseStateCount += 1
                dropFirstPhaseStatePredicate = null
            }
            val result = runSuspending {
                submitAutomatedDiagnosticsPhaseStateSignal(
                    bleConnectionStatus = sender.environment.bleConnectionStatus,
                    activeTransportPeerId = sender.environment.activeTransportPeerId,
                    transportSender = if (shouldDrop) {
                        sender.queuedLocallyTransportSender()
                    } else {
                        receiver.bridgeTransportSender()
                    },
                    localPeerId = sender.environment.localPeerId,
                    sharedRun = sharedRun,
                    expectedRemotePeerId = expectedRemotePeerId,
                    stepId = stepId,
                    phaseState = phaseState,
                    attemptNumber = attemptNumber,
                    applicationProbeDescriptors = applicationProbeDescriptors,
                    createdAtMillis = createdAtMillis
                )
            }
            sender.environment.lastAutomatedDiagnosticsPhaseStatus =
                automatedDiagnosticsPhaseStateSendStatusText(result)
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

        fun sendHybridBootstrapManualOffer(
            from: FakePhaseOneEnvironment
        ): HybridBootstrapManualOfferSendResult {
            val sender = endpointFor(from)
            val receiver = otherEndpoint(from)
            val createdAtMillis = sender.environment.currentWallClockMillis()
            val localPeerId = sender.environment.localPeerId
            return runSuspending {
                val result = submitHybridBootstrapManualOffer(
                    bleConnectionStatus = sender.environment.bleConnectionStatus,
                    activeTransportPeerId = sender.environment.activeTransportPeerId,
                    peerSessionDiagnostics = sender.environment.peerSessionDiagnostics,
                    transportSender = receiver.bridgeTransportSender(),
                    localPeerId = localPeerId,
                    createdAtMillis = createdAtMillis
                )
                if (result is HybridBootstrapManualOfferSendResult.Sent && localPeerId != null) {
                    sender.recordSentHybridBootstrapControlMessage(
                        targetPeerId = result.peerId,
                        message = createHybridBootstrapManualOfferMessage(
                            localPeerId = localPeerId,
                            createdAtMillis = createdAtMillis
                        )
                    )
                }
                result
            }
        }

        fun sendHybridBootstrapManualAccept(
            from: FakePhaseOneEnvironment
        ): HybridBootstrapManualAcceptSendResult {
            val sender = endpointFor(from)
            val receiver = otherEndpoint(from)
            val createdAtMillis = sender.environment.currentWallClockMillis()
            val localPeerId = sender.environment.localPeerId
            val shouldDrop = alwaysDropHybridBootstrapAccept ||
                hybridBootstrapAcceptDropsRemaining > 0
            if (hybridBootstrapAcceptDropsRemaining > 0) {
                hybridBootstrapAcceptDropsRemaining -= 1
            }
            if (shouldDrop) {
                droppedHybridBootstrapAcceptCount += 1
            }
            return runSuspending {
                val result = submitHybridBootstrapManualAccept(
                    decision = sender.environment.hybridBootstrapDecision,
                    bleConnectionStatus = sender.environment.bleConnectionStatus,
                    activeTransportPeerId = sender.environment.activeTransportPeerId,
                    peerSessionDiagnostics = sender.environment.peerSessionDiagnostics,
                    transportSender = if (shouldDrop) {
                        sender.queuedLocallyTransportSender()
                    } else {
                        receiver.bridgeTransportSender()
                    },
                    localPeerId = localPeerId,
                    createdAtMillis = createdAtMillis
                )
                if (result is HybridBootstrapManualAcceptSendResult.Sent && localPeerId != null) {
                    sender.recordSentHybridBootstrapControlMessage(
                        targetPeerId = result.peerId,
                        message = createHybridBootstrapManualAcceptMessage(
                            localPeerId = localPeerId,
                            sessionId = result.sessionId,
                            createdAtMillis = createdAtMillis
                        )
                    )
                }
                result
            }
        }

        fun injectHybridBootstrapManualAccept(
            receiver: FakePhaseOneEnvironment,
            senderPeerId: String,
            sessionId: String,
            createdAtMillis: Long
        ): BleTransportReceiveResult {
            val frame = createHybridBootstrapManualAcceptFrame(
                localPeerId = senderPeerId,
                targetPeerId = receiver.localPeerId,
                sessionId = sessionId,
                createdAtMillis = createdAtMillis
            )
            return endpointFor(receiver).receiveInjectedFrame(frame)
        }

        fun sendHybridBootstrapManualSocketHint(
            from: FakePhaseOneEnvironment
        ): HybridBootstrapManualSocketHintSendResult {
            val sender = endpointFor(from)
            val receiver = otherEndpoint(from)
            val createdAtMillis = sender.environment.currentWallClockMillis()
            val localPeerId = sender.environment.localPeerId
            val shouldDrop = alwaysDropHybridBootstrapSocketHint ||
                hybridBootstrapSocketHintDropsRemaining > 0
            if (hybridBootstrapSocketHintDropsRemaining > 0) {
                hybridBootstrapSocketHintDropsRemaining -= 1
            }
            if (shouldDrop) {
                droppedHybridBootstrapSocketHintCount += 1
            }
            return runSuspending {
                val result = submitHybridBootstrapManualSocketHint(
                    decision = sender.environment.hybridBootstrapDecision,
                    bleConnectionStatus = sender.environment.bleConnectionStatus,
                    activeTransportPeerId = sender.environment.activeTransportPeerId,
                    peerSessionDiagnostics = sender.environment.peerSessionDiagnostics,
                    transportSender = if (shouldDrop) {
                        sender.queuedLocallyTransportSender()
                    } else {
                        receiver.bridgeTransportSender()
                    },
                    localPeerId = localPeerId,
                    wifiDirectConnectionStatus =
                        sender.environment.wifiDirectRuntimeStatus.connectionStatus,
                    socketPort = wifiDirectDebugSocketPort,
                    createdAtMillis = createdAtMillis
                )
                if (result is HybridBootstrapManualSocketHintSendResult.Sent && localPeerId != null) {
                    sender.recordSentHybridBootstrapControlMessage(
                        targetPeerId = result.peerId,
                        message = createHybridBootstrapManualSocketHintMessage(
                            localPeerId = localPeerId,
                            sessionId = result.sessionId,
                            groupOwnerAddress = result.groupOwnerAddress,
                            socketPort = result.socketPort,
                            createdAtMillis = createdAtMillis
                        )
                    )
                }
                result
            }
        }

        fun injectHybridBootstrapManualSocketHint(
            receiver: FakePhaseOneEnvironment,
            senderPeerId: String,
            sessionId: String,
            groupOwnerAddress: String,
            socketPort: Int,
            createdAtMillis: Long
        ): BleTransportReceiveResult {
            val frame = createHybridBootstrapManualSocketHintFrame(
                localPeerId = senderPeerId,
                targetPeerId = receiver.localPeerId,
                sessionId = sessionId,
                groupOwnerAddress = groupOwnerAddress,
                socketPort = socketPort,
                createdAtMillis = createdAtMillis
            )
            return endpointFor(receiver).receiveInjectedFrame(frame)
        }

        fun otherPeerIdFor(
            environment: FakePhaseOneEnvironment
        ): String {
            return otherEndpoint(environment).environment.localPeerId
        }

        fun deliverAutomatedDiagnosticsApplicationProbe(
            from: FakePhaseOneEnvironment,
            messageId: String,
            marker: AutomatedDiagnosticsApplicationProbeMarker,
            senderPeerId: String,
            applicationSenderId: String = senderPeerId,
            receiverPeerId: String,
            privateChatId: String?,
            observedAtMonotonicMillis: Long,
            observedAtWallClockMillis: Long
        ) {
            val receiver = otherEndpoint(from).environment
            if (
                dropAutomatedDiagnosticsApplicationProbePredicate?.invoke(
                    marker,
                    senderPeerId,
                    receiverPeerId,
                    privateChatId
                ) == true
            ) {
                droppedAutomatedDiagnosticsApplicationProbeCount += 1
                return
            }
            val receiverObservedAtMonotonicMillis = receiver.currentMonotonicMillis()
            val receiverObservedAtWallClockMillis = receiver.currentWallClockMillis()
            val authoritativeTransportGroupId =
                from.recentBleTransportLocalSendTraces.lastOrNull { trace ->
                    trace.messageId == messageId &&
                        trace.targetPeerId == receiverPeerId
                }?.groupId
            endpointFor(receiver).receiveAutomatedDiagnosticsApplicationProbe(
                messageId = messageId,
                marker = marker,
                senderPeerId = senderPeerId,
                applicationSenderId = applicationSenderId,
                privateChatId = privateChatId,
                transportGroupId = authoritativeTransportGroupId,
                observedAtMonotonicMillis = receiverObservedAtMonotonicMillis,
                observedAtWallClockMillis = receiverObservedAtWallClockMillis
            )
            if (
                duplicateAutomatedDiagnosticsApplicationProbePredicate?.invoke(
                    marker,
                    senderPeerId,
                    receiverPeerId,
                    privateChatId
                ) == true
            ) {
                duplicatedAutomatedDiagnosticsApplicationProbeCount += 1
                endpointFor(receiver).receiveAutomatedDiagnosticsApplicationProbe(
                    messageId = messageId,
                    marker = marker,
                    senderPeerId = senderPeerId,
                    applicationSenderId = applicationSenderId,
                    privateChatId = privateChatId,
                    transportGroupId = authoritativeTransportGroupId,
                    observedAtMonotonicMillis = receiverObservedAtMonotonicMillis,
                    observedAtWallClockMillis = receiverObservedAtWallClockMillis
                )
            }
        }

        fun injectAutomatedDiagnosticsApplicationProbe(
            receiver: FakePhaseOneEnvironment,
            messageId: String,
            marker: AutomatedDiagnosticsApplicationProbeMarker,
            senderPeerId: String,
            applicationSenderId: String = senderPeerId,
            privateChatId: String?,
            transportGroupId: Int? = null,
            observedAtMonotonicMillis: Long,
            observedAtWallClockMillis: Long
        ) {
            endpointFor(receiver).receiveAutomatedDiagnosticsApplicationProbe(
                messageId = messageId,
                marker = marker,
                senderPeerId = senderPeerId,
                applicationSenderId = applicationSenderId,
                privateChatId = privateChatId,
                transportGroupId = transportGroupId,
                observedAtMonotonicMillis = observedAtMonotonicMillis,
                observedAtWallClockMillis = observedAtWallClockMillis
            )
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
            val forcedOwnerPeerId = forcedGroupOwnerPeerId
            val mirroredReceiverPeer =
                receiver.observedWifiDirectPeerByRemotePeerId[sender.localPeerId] ?: return
            if (forcedOwnerPeerId == receiver.localPeerId) {
                sender.completeWifiDirectGroupAsClient(selectedPeer)
                receiver.completeWifiDirectGroupAsGroupOwner(mirroredReceiverPeer)
            } else {
                sender.completeWifiDirectGroupAsGroupOwner(selectedPeer)
                receiver.completeWifiDirectGroupAsClient(mirroredReceiverPeer)
            }
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
        private val hybridControlStore = InMemoryHybridTransportControlStore()
        private val hybridDecisionProvider = HybridBootstrapDecisionProvider(hybridControlStore)
        private val receiver = createAuroraBleTransportFrameReceiver(
            stateHolder = stateHolder,
            hybridControlStore = hybridControlStore
        )

        fun receiveInjectedFrame(
            frame: MessageFrame
        ): BleTransportReceiveResult {
            val sendPlan = OutgoingBleTransportSendPlanBuilder.build(
                messageId = frame.id,
                targetPeerId = frame.recipientId,
                encryptedEnvelopeBytes = MessageFrameCodec.encode(frame).toByteArray(UTF_8),
                sourceCreatedAtMillis = frame.createdAtMillis
            )
            return receiveInjectedTransportFrames(sendPlan.framesInSendOrder())
        }

        fun recordSentHybridBootstrapControlMessage(
            targetPeerId: String,
            message: HybridTransportControlMessage
        ) {
            val decision = recordLocallySentHybridBootstrapControlMessage(
                targetPeerId = targetPeerId,
                message = message,
                hybridControlStore = hybridControlStore,
                provider = hybridDecisionProvider
            )
            environment.applyCurrentHybridBootstrapDecision(decision)
        }

        fun receiveAutomatedDiagnosticsApplicationProbe(
            messageId: String,
            marker: AutomatedDiagnosticsApplicationProbeMarker,
            senderPeerId: String,
            applicationSenderId: String,
            privateChatId: String?,
            transportGroupId: Int? = null,
            observedAtMonotonicMillis: Long,
            observedAtWallClockMillis: Long
        ) {
            val effectiveTransportGroupId =
                transportGroupId ?: automatedDiagnosticsApplicationProbeExpectedTransportGroupId(
                    messageId = messageId,
                    receiverPeerId = environment.localPeerId
                )
            environment.recordAutomatedDiagnosticsApplicationProbeTransportReceiveEvent(
                AutomatedDiagnosticsApplicationProbeTransportReceiveEvent(
                    groupId = effectiveTransportGroupId,
                    sourceDeviceAddress = null,
                    observedAtMonotonicMillis = observedAtMonotonicMillis,
                    observedAtWallClockMillis = observedAtWallClockMillis,
                    transportResultKind = "Processed",
                    processingResultKind = "Received",
                    ingestionResultKind = "Appended",
                    messageId = messageId,
                    messageType =
                        automatedDiagnosticsApplicationProbeExpectedMessageType(marker.probeKind),
                    marker = marker,
                    expectedMessageType =
                        automatedDiagnosticsApplicationProbeExpectedMessageType(marker.probeKind),
                    messageTypeMatchedExpectedProbe = true
                )
            )
            environment.recordAutomatedDiagnosticsApplicationProbeReceiveDiagnostic(
                AutomatedDiagnosticsApplicationProbeReceiveDiagnostic(
                    sharedRunId = marker.sharedRunId,
                    stepId = marker.stepId,
                    attemptNumber = marker.attemptNumber,
                    probeKind = marker.probeKind,
                    direction = marker.direction,
                    messageId = messageId,
                    applicationSenderId = applicationSenderId,
                    receiverPeerId = environment.localPeerId,
                    messageType =
                        automatedDiagnosticsApplicationProbeExpectedMessageType(marker.probeKind),
                    threadId = if (marker.probeKind == AutomatedDiagnosticsApplicationProbeKind.GLOBAL) {
                        "global"
                    } else {
                        "private:$senderPeerId"
                    },
                    privateChatId = privateChatId,
                    transportGroupId = effectiveTransportGroupId,
                    marker = marker,
                    sourceResolution = AutomatedDiagnosticsApplicationProbeSourceResolution(
                        sourceDeviceAddress = null,
                        exactAddressSourcePeerId = senderPeerId,
                        diagnosticsAssociatedSourcePeerId = senderPeerId,
                        resolvedSourcePeerId = senderPeerId,
                        resolutionSource =
                            AutomatedDiagnosticsApplicationProbeSourceResolutionSource
                                .CURRENT_RUN_DIAGNOSTICS_ASSOCIATION,
                        associationLookupHit = true,
                        storedAssociationPeerId = senderPeerId,
                        storedAssociationSharedRunId = marker.sharedRunId,
                        storedAssociationStepId = marker.stepId,
                        storedAssociationAttemptNumber = marker.attemptNumber,
                        storedAssociationExpectedRemotePeerId = environment.localPeerId,
                        selectedSecurePeerId = senderPeerId,
                        diagnosticsAssociationOutcome =
                            AutomatedDiagnosticsApplicationProbeAssociationOutcome.RESOLVED,
                        selectedSecurePeerGate =
                            AutomatedDiagnosticsApplicationProbeSelectedSecurePeerGate.MATCH
                    ),
                    observedAtMonotonicMillis = observedAtMonotonicMillis,
                    observedAtWallClockMillis = observedAtWallClockMillis
                )
            )
            environment.recordAutomatedDiagnosticsApplicationProbeObservation(
                AutomatedDiagnosticsApplicationProbeObservation(
                    sharedRunId = marker.sharedRunId,
                    stepId = marker.stepId,
                    attemptNumber = marker.attemptNumber,
                    probeKind = marker.probeKind,
                    direction = marker.direction,
                    messageId = messageId,
                    senderPeerId = senderPeerId,
                    applicationSenderId = applicationSenderId,
                    receiverPeerId = environment.localPeerId,
                    messageType =
                        automatedDiagnosticsApplicationProbeExpectedMessageType(marker.probeKind),
                    threadId = if (marker.probeKind == AutomatedDiagnosticsApplicationProbeKind.GLOBAL) {
                        "global"
                    } else {
                        "private:$senderPeerId"
                    },
                    privateChatId = privateChatId,
                    transportGroupId = effectiveTransportGroupId,
                    marker = marker,
                    observedAtMonotonicMillis = observedAtMonotonicMillis,
                    observedAtWallClockMillis = observedAtWallClockMillis
                )
            )
        }

        private fun receiveInjectedTransportFrame(
            frame: BleGattTransportFrame
        ): BleTransportReceiveResult {
            return receiveInjectedTransportFrames(listOf(frame))
        }

        private fun receiveInjectedTransportFrames(
            frames: List<BleGattTransportFrame>
        ): BleTransportReceiveResult {
            val receiveResult = receiveFrames(receiver, frames)
            environment.applyReceivedCoordinationResult(receiveResult)
            environment.applyReceivedHybridBootstrapResult(
                result = receiveResult,
                provider = hybridDecisionProvider
            )
            return receiveResult
        }

        fun bridgeTransportSender(): BleTransportSender {
            return object : BleTransportSender {
                override fun send(
                    plan: OutgoingBleTransportSendPlan,
                    listener: BleTransportSender.Listener
                ) {
                    plan.framesInSendOrder().forEach(::receiveInjectedTransportFrame)
                    listener.onSendResult(BleTransportSendResult.QueuedLocally)
                }
            }
        }

        fun queuedLocallyTransportSender(): BleTransportSender {
            return object : BleTransportSender {
                override fun send(
                    plan: OutgoingBleTransportSendPlan,
                    listener: BleTransportSender.Listener
                ) {
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

    private fun samplePhaseBarrierSharedRun(): AutomatedDiagnosticsSharedRun {
        return AutomatedDiagnosticsSharedRun(
            runId = "run-phase-barrier",
            coordinatorPeerId = "coordinator-peer",
            participantPeerId = "participant-peer",
            sessionAssociationId = "session-phase-barrier",
            createdAtMillis = 1_000L,
            expiresAtMillis = 61_000L
        )
    }

    private fun samplePhaseBarrierSignal(
        sharedRun: AutomatedDiagnosticsSharedRun,
        peerId: String = sharedRun.participantPeerId,
        expectedRemotePeerId: String = sharedRun.coordinatorPeerId,
        stepId: AutomatedDiagnosticStepId = AutomatedDiagnosticStepId.WIFI_DIRECT_DISCOVERY_AND_GROUP,
        phaseState: AutomatedDiagnosticsPhaseState = AutomatedDiagnosticsPhaseState.READY,
        attemptNumber: Int = 1,
        createdAtMillis: Long = 2_000L,
        expiresAtMillis: Long = 17_000L
    ): AutomatedDiagnosticsPhaseSignal {
        return AutomatedDiagnosticsPhaseSignal(
            sharedRun = sharedRun,
            peerId = peerId,
            expectedRemotePeerId = expectedRemotePeerId,
            stepId = stepId,
            phaseState = phaseState,
            attemptNumber = attemptNumber,
            createdAtMillis = createdAtMillis,
            expiresAtMillis = expiresAtMillis
        )
    }

    private fun sampleServerReadySharedRun(
        coordinatorPeerId: String = "peer-coordinator",
        participantPeerId: String = "peer-participant"
    ): AutomatedDiagnosticsSharedRun {
        return AutomatedDiagnosticsSharedRun(
            runId = "diag-$coordinatorPeerId-$participantPeerId",
            coordinatorPeerId = coordinatorPeerId,
            participantPeerId = participantPeerId,
            sessionAssociationId = "session-$coordinatorPeerId-$participantPeerId",
            createdAtMillis = 1_000L,
            expiresAtMillis = 61_000L
        )
    }

    private fun sampleServerReadySignal(
        sharedRun: AutomatedDiagnosticsSharedRun = sampleServerReadySharedRun(),
        peerId: String = sharedRun.coordinatorPeerId,
        expectedClientPeerId: String = sharedRun.participantPeerId,
        groupOwnerAddress: String = "192.168.49.1",
        socketPort: Int = wifiDirectDebugSocketPort,
        serverToken: Long = 7L,
        createdAtMillis: Long = 2_000L,
        expiresAtMillis: Long = createdAtMillis + 8_000L
    ): AutomatedDiagnosticsServerReadySignal {
        return AutomatedDiagnosticsServerReadySignal(
            sharedRun = sharedRun,
            peerId = peerId,
            expectedClientPeerId = expectedClientPeerId,
            groupOwnerAddress = groupOwnerAddress,
            socketPort = socketPort,
            serverToken = serverToken,
            createdAtMillis = createdAtMillis,
            expiresAtMillis = expiresAtMillis
        )
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
