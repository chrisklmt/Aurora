package gr.hua.aurora.protocol

import gr.hua.aurora.ble.transport.BleGattTransportFrameReassembler
import gr.hua.aurora.ble.transport.BleTransportSendResult
import gr.hua.aurora.ble.transport.BleTransportSender
import gr.hua.aurora.ble.transport.OutgoingBleTransportSendPlan
import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.model.OutgoingChatMessage
import gr.hua.aurora.state.IncomingMessageIngestionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets.UTF_8
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class GlobalMeshDeliveryCoordinatorTest {
    @Test
    fun localGlobalSendWithoutReachablePeerReturnsExplicitResult() {
        val coordinator = GlobalMeshDeliveryCoordinator()

        val result = runSuspending {
            coordinator.submitLocalMessage(
                message = globalOutgoingMessage("mesh-no-peer"),
                senderId = "sender-1",
                transportSender = RecordingTransportSender(BleTransportSendResult.QueuedLocally),
                activeTransportPeerId = null
            )
        }

        assertEquals(GlobalMeshDeliveryResult.NoReachablePeers, result)
        assertEquals(
            0,
            coordinator.diagnosticsSnapshot(
                reachablePeerIds = emptyList(),
                activeTransportPeerId = null
            ).reachablePeerCount
        )
    }

    @Test
    fun localGlobalSendWithActivePeerQueuesToThatPeer() {
        val coordinator = GlobalMeshDeliveryCoordinator()
        val sender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)
        val message = globalOutgoingMessage("mesh-active-peer")

        val result = runSuspending {
            coordinator.submitLocalMessage(
                message = message,
                senderId = "sender-1",
                transportSender = sender,
                activeTransportPeerId = "peer-123"
            )
        }

        assertEquals(
            GlobalMeshDeliveryResult.QueuedToActivePeer("peer-123"),
            result
        )
        val capturedPlan = requireNotNull(sender.capturedPlan)
        assertEquals(message.messageId, capturedPlan.messageId)
        assertEquals("peer-123", capturedPlan.targetPeerId)
        val decodedFrame = decodeCapturedFrame(capturedPlan)
        assertEquals(message.messageId, decodedFrame.id)
        assertEquals("sender-1", decodedFrame.senderId)
        assertEquals(10, decodedFrame.ttl)
        assertEquals(message.userText, decodedFrame.payload)
        assertEquals(MessageStatus.QUEUED, message.status)
    }

    @Test
    fun localGlobalSendWithNoTransportSenderReturnsExplicitResult() {
        val coordinator = GlobalMeshDeliveryCoordinator()

        val result = runSuspending {
            coordinator.submitLocalMessage(
                message = globalOutgoingMessage("mesh-no-sender"),
                senderId = "sender-2",
                transportSender = null,
                activeTransportPeerId = "peer-456"
            )
        }

        assertEquals(GlobalMeshDeliveryResult.SenderUnavailable, result)
    }

    @Test
    fun duplicateIncomingGlobalMessageIsNotRelayedTwice() {
        val coordinator = GlobalMeshDeliveryCoordinator()
        val sender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)
        val message = incomingGlobalMessage(
            id = "incoming-dup-1",
            senderId = "peer-source",
            ttl = 2
        )

        val firstResult = runSuspending {
            coordinator.relayReceivedMessage(
                message = message,
                ingestionResult = IncomingMessageIngestionResult.Appended(
                    message = incomingGlobalChatMessage(message.frame)
                ),
                transportSender = sender,
                activeTransportPeerId = "peer-target",
                immediateSourcePeerId = "peer-source"
            )
        }
        val secondResult = runSuspending {
            coordinator.relayReceivedMessage(
                message = message,
                ingestionResult = IncomingMessageIngestionResult.Duplicate(message.frame.id),
                transportSender = sender,
                activeTransportPeerId = "peer-target",
                immediateSourcePeerId = "peer-source"
            )
        }

        assertEquals(
            GlobalMeshDeliveryResult.QueuedToActivePeer("peer-target"),
            firstResult
        )
        assertEquals(
            GlobalMeshDeliveryResult.SkippedDuplicate("incoming-dup-1"),
            secondResult
        )
        assertEquals(1, sender.sendCallCount)
    }

    @Test
    fun incomingGlobalMessageDoesNotRelayBackToImmediateSource() {
        val coordinator = GlobalMeshDeliveryCoordinator()
        val sender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)
        val message = incomingGlobalMessage(
            id = "incoming-source-1",
            senderId = "peer-123",
            ttl = 2
        )

        val result = runSuspending {
            coordinator.relayReceivedMessage(
                message = message,
                ingestionResult = IncomingMessageIngestionResult.Appended(
                    message = incomingGlobalChatMessage(message.frame)
                ),
                transportSender = sender,
                activeTransportPeerId = "peer-123",
                immediateSourcePeerId = "peer-123"
            )
        }

        assertEquals(
            GlobalMeshDeliveryResult.SkippedSourcePeer("peer-123"),
            result
        )
        assertEquals(0, sender.sendCallCount)
    }

    @Test
    fun incomingGlobalMessageStopsRelayingWhenTtlExpires() {
        val coordinator = GlobalMeshDeliveryCoordinator()
        val sender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)
        val message = incomingGlobalMessage(
            id = "incoming-ttl-1",
            senderId = "peer-ttl",
            ttl = 1
        )

        val result = runSuspending {
            coordinator.relayReceivedMessage(
                message = message,
                ingestionResult = IncomingMessageIngestionResult.Appended(
                    message = incomingGlobalChatMessage(message.frame)
                ),
                transportSender = sender,
                activeTransportPeerId = "peer-target",
                immediateSourcePeerId = "peer-source"
            )
        }

        assertEquals(
            GlobalMeshDeliveryResult.SkippedTtlExpired("incoming-ttl-1"),
            result
        )
        assertEquals(0, sender.sendCallCount)
    }

    @Test
    fun publicGlobalSendDoesNotRequireSessionMaterialProvider() {
        val coordinator = GlobalMeshDeliveryCoordinator()
        val sender = RecordingTransportSender(BleTransportSendResult.QueuedLocally)

        val result = runSuspending {
            coordinator.submitLocalMessage(
                message = globalOutgoingMessage("mesh-public-no-session"),
                senderId = "sender-3",
                transportSender = sender,
                activeTransportPeerId = "peer-789"
            )
        }

        assertEquals(
            GlobalMeshDeliveryResult.QueuedToActivePeer("peer-789"),
            result
        )
        assertEquals(1, sender.sendCallCount)
    }

    private fun globalOutgoingMessage(
        messageId: String
    ): OutgoingChatMessage {
        return OutgoingChatMessage(
            messageId = messageId,
            threadId = "global",
            userText = "hello mesh",
            createdAtMillis = 1_716_500_001L,
            status = MessageStatus.QUEUED
        )
    }

    private fun incomingGlobalMessage(
        id: String,
        senderId: String,
        ttl: Int
    ): IncomingTransportMessage {
        return IncomingTransportMessage(
            frame = MessageFrame(
                id = id,
                type = MessageFrameType.GLOBAL_TEXT,
                senderId = senderId,
                createdAtMillis = 1_716_500_002L,
                ttl = ttl,
                payload = "relay me"
            )
        )
    }

    private fun incomingGlobalChatMessage(
        frame: MessageFrame
    ) = gr.hua.aurora.model.ChatMessage(
        id = frame.id,
        threadId = "global",
        senderId = frame.senderId,
        senderName = frame.senderId,
        text = frame.payload,
                createdAtMillis = frame.createdAtMillis,
                status = MessageStatus.RECEIVED,
                isOutgoing = false
    )

    private fun decodeCapturedFrame(
        capturedPlan: OutgoingBleTransportSendPlan
    ): MessageFrame {
        val reassembledFrameBytes = BleGattTransportFrameReassembler.reassemble(
            capturedPlan.framesInSendOrder()
        )
        return MessageFrameCodec.decode(String(reassembledFrameBytes, UTF_8))
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
            "Suspending mesh delivery did not complete synchronously in the test harness."
        }.getOrThrow()
    }
}
