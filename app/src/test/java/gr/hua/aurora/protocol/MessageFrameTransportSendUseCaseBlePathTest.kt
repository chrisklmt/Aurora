package gr.hua.aurora.protocol

import gr.hua.aurora.ble.transport.BleTransportSendResult
import gr.hua.aurora.ble.transport.BleTransportSender
import gr.hua.aurora.ble.transport.OutgoingBleTransportSendPlan
import gr.hua.aurora.ble.transport.metrics
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticStepId
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsApplicationProbeDirection
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsApplicationProbeKind
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsApplicationProbeMarker
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsPhaseApplicationProbeDescriptor
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsPhaseSignal
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsPhaseState
import gr.hua.aurora.diagnostics.automated.AutomatedDiagnosticsSharedRun
import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.model.OutgoingChatMessage
import gr.hua.aurora.state.createAutomatedDiagnosticsPhaseStateFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageFrameTransportSendUseCaseBlePathTest {
    @Test
    fun phaseReadyAndGlobalTextUseSamePublicBleSendPath() {
        val sharedRun = AutomatedDiagnosticsSharedRun(
            runId = "diag-a16ac7f80be311cc65423515",
            coordinatorPeerId = "dc378a8e64e95e92",
            participantPeerId = "f9cb4f5e73aeaa54",
            sessionAssociationId = "assoc-dc378a8e64e95e92-f9cb4f5e73aeaa54",
            createdAtMillis = 1_786_898_700_000L,
            expiresAtMillis = 1_786_898_760_000L
        )
        val phaseReadyFrame = createAutomatedDiagnosticsPhaseStateFrame(
            signal = AutomatedDiagnosticsPhaseSignal(
                sharedRun = sharedRun,
                peerId = sharedRun.coordinatorPeerId,
                expectedRemotePeerId = sharedRun.participantPeerId,
                stepId = AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
                phaseState = AutomatedDiagnosticsPhaseState.RUNNING,
                attemptNumber = 1,
                applicationProbeDescriptors = listOf(
                    AutomatedDiagnosticsPhaseApplicationProbeDescriptor(
                        probeKind = AutomatedDiagnosticsApplicationProbeKind.GLOBAL,
                        messageId = "global-1786898714027-000000",
                        transportStatus = "queued-active:${sharedRun.participantPeerId}",
                        localBleTransportResult = "QueuedLocally"
                    )
                ),
                createdAtMillis = 1_786_898_714_000L,
                expiresAtMillis = 1_786_898_720_000L
            ),
            targetPeerId = sharedRun.participantPeerId
        )
        val globalProbeText = AutomatedDiagnosticsApplicationProbeMarker(
            sharedRunId = sharedRun.runId,
            stepId = AutomatedDiagnosticStepId.GLOBAL_MESSAGE_PROBE,
            attemptNumber = 1,
            probeKind = AutomatedDiagnosticsApplicationProbeKind.GLOBAL,
            direction = AutomatedDiagnosticsApplicationProbeDirection.C2P
        ).bodyText()
        val globalFrame = GlobalMeshDeliveryCoordinator().prepareLocalPublicFrame(
            message = OutgoingChatMessage(
                messageId = "global-1786898714027-000000",
                threadId = "global",
                userText = globalProbeText,
                createdAtMillis = 1_786_898_714_027L,
                status = MessageStatus.QUEUED
            ),
            senderId = "Chris"
        )
        val sender = RecordingTransportSender()

        val phaseReadyResult = suspendSendPublic(
            frame = phaseReadyFrame,
            sender = sender,
            targetPeerId = sharedRun.participantPeerId
        )
        val globalResult = suspendSendPublic(
            frame = requireNotNull(globalFrame),
            sender = sender,
            targetPeerId = sharedRun.participantPeerId
        )

        assertEquals(BleTransportSendResult.QueuedLocally, phaseReadyResult)
        assertEquals(BleTransportSendResult.QueuedLocally, globalResult)
        assertEquals(2, sender.plans.size)

        val phaseReadyPlan = sender.plans[0]
        val globalPlan = sender.plans[1]
        val phaseReadyMetrics = phaseReadyPlan.metrics()
        val globalMetrics = globalPlan.metrics()

        assertEquals(sharedRun.participantPeerId, phaseReadyPlan.targetPeerId)
        assertEquals(sharedRun.participantPeerId, globalPlan.targetPeerId)
        assertTrue(phaseReadyMetrics.chunkCount >= 1)
        assertTrue(globalMetrics.chunkCount >= 1)
        assertEquals(phaseReadyMetrics.chunkCount, phaseReadyPlan.framesInSendOrder().size)
        assertEquals(globalMetrics.chunkCount, globalPlan.framesInSendOrder().size)
        assertNotNull(phaseReadyMetrics.frameEncodedSizes)
        assertNotNull(globalMetrics.frameEncodedSizes)
    }

    private fun suspendSendPublic(
        frame: MessageFrame,
        sender: RecordingTransportSender,
        targetPeerId: String
    ): BleTransportSendResult {
        var result: BleTransportSendResult? = null
        kotlin.runCatching {
            kotlinx.coroutines.runBlocking {
                result = MessageFrameTransportSendUseCase.sendPublic(
                    frame = frame,
                    transportSender = sender,
                    targetPeerId = targetPeerId
                )
            }
        }.getOrThrow()
        return requireNotNull(result)
    }

    private class RecordingTransportSender : BleTransportSender {
        val plans = mutableListOf<OutgoingBleTransportSendPlan>()

        override fun send(
            plan: OutgoingBleTransportSendPlan,
            listener: BleTransportSender.Listener
        ) {
            plans += plan
            listener.onSendResult(BleTransportSendResult.QueuedLocally)
        }
    }
}
