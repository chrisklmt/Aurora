package gr.hua.aurora.wifidirect

import gr.hua.aurora.ble.transport.BleGattTransportFrame
import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.model.OutgoingChatMessage
import gr.hua.aurora.protocol.IncomingMessageReceiveUseCase
import gr.hua.aurora.protocol.IncomingTransportReceiveResult
import gr.hua.aurora.protocol.MessageFrameType
import gr.hua.aurora.protocol.NoOpIncomingSessionMaterialProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDirectGlobalDebugSendBridgeTest {
    @Test
    fun globalDebugSendDefaultsToDisabled() {
        val sender = WifiDirectGlobalDebugSendBridge(
            submitFrame = { _, _ -> error("submit should not be called") },
            sendBridgeDiagnostics = { WifiDirectSendBridgeDiagnostics() },
            transportAdapterDiagnostics = { WifiDirectTransportAdapterDiagnostics() }
        )

        assertEquals(false, sender.currentDiagnostics().enabled)
        assertEquals(0L, sender.currentDiagnostics().globalFramesSubmitted)
        assertEquals(0L, sender.currentDiagnostics().globalSubmitFailures)
    }

    @Test
    fun globalDebugSendIsBlockedWhenSendBridgeDisabled() {
        val submittedFrames = mutableListOf<WifiDirectTransportFrame>()
        val sender = WifiDirectGlobalDebugSendBridge(
            submitFrame = { frame, onResult ->
                submittedFrames += frame
                onResult(Result.success(Unit))
            },
            sendBridgeDiagnostics = { WifiDirectSendBridgeDiagnostics(enabled = false) },
            transportAdapterDiagnostics = {
                WifiDirectTransportAdapterDiagnostics(
                    state = WifiDirectTransportAdapterState.READY
                )
            }
        )
        sender.setEnabled(true)

        val failure = runCatching {
            sender.submitGlobalMessage(sampleOutgoingMessage(), "debug-user") { result ->
                result.getOrThrow()
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(
            "Wi-Fi Direct Global send requires the send bridge to be enabled.",
            failure?.message
        )
        assertEquals(emptyList<WifiDirectTransportFrame>(), submittedFrames)
        assertEquals(1L, sender.currentDiagnostics().globalSubmitFailures)
        assertEquals("blocked", sender.currentDiagnostics().lastGlobalSendResult)
    }

    @Test
    fun globalDebugSendBuildsValidGlobalTransportFrames() {
        val submittedFrames = mutableListOf<WifiDirectTransportFrame>()
        val sender = WifiDirectGlobalDebugSendBridge(
            submitFrame = { frame, onResult ->
                submittedFrames += frame
                onResult(Result.success(Unit))
            },
            sendBridgeDiagnostics = { WifiDirectSendBridgeDiagnostics(enabled = true) },
            transportAdapterDiagnostics = {
                WifiDirectTransportAdapterDiagnostics(
                    state = WifiDirectTransportAdapterState.READY
                )
            }
        )
        sender.setEnabled(true)
        val queuedMessage = sampleOutgoingMessage(
            messageId = "global-123",
            userText = "hello over wifi direct"
        )

        sender.submitGlobalMessage(queuedMessage, "debug-user")

        assertTrue(submittedFrames.isNotEmpty())
        val bleFrames = submittedFrames.map { frame ->
            requireNotNull(BleGattTransportFrame.parse(frame.payloadBytes()))
        }
        val receiveResult = IncomingMessageReceiveUseCase.receive(
            frames = bleFrames,
            sessionMaterialProvider = NoOpIncomingSessionMaterialProvider
        )

        assertTrue(receiveResult is IncomingTransportReceiveResult.Received)
        val receivedMessage = (receiveResult as IncomingTransportReceiveResult.Received).message
        assertEquals("global-123", receivedMessage.frame.id)
        assertEquals(MessageFrameType.GLOBAL_TEXT, receivedMessage.frame.type)
        assertEquals("debug-user", receivedMessage.frame.senderId)
        assertEquals("hello over wifi direct", receivedMessage.frame.payload)
        assertEquals(submittedFrames.size.toLong(), sender.currentDiagnostics().globalFramesSubmitted)
        assertEquals("submitted locally", sender.currentDiagnostics().lastGlobalSendResult)
    }

    @Test
    fun globalDebugSendDoesNotAllowPrivateThreads() {
        val sender = WifiDirectGlobalDebugSendBridge(
            submitFrame = { _, _ -> error("submit should not be called") },
            sendBridgeDiagnostics = { WifiDirectSendBridgeDiagnostics(enabled = true) },
            transportAdapterDiagnostics = {
                WifiDirectTransportAdapterDiagnostics(
                    state = WifiDirectTransportAdapterState.READY
                )
            }
        )
        sender.setEnabled(true)

        val failure = runCatching {
            sender.submitGlobalMessage(
                sampleOutgoingMessage(threadId = "private:alex"),
                "debug-user"
            ) { result ->
                result.getOrThrow()
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(
            "Wi-Fi Direct Global send only supports the global thread.",
            failure?.message
        )
    }

    private fun sampleOutgoingMessage(
        messageId: String = "global-msg-1",
        threadId: String = "global",
        userText: String = "hello"
    ): OutgoingChatMessage {
        return OutgoingChatMessage(
            messageId = messageId,
            threadId = threadId,
            userText = userText,
            createdAtMillis = 1_000L,
            status = MessageStatus.LOCAL_ONLY
        )
    }
}
