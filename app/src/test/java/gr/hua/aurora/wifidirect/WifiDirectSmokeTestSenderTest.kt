package gr.hua.aurora.wifidirect

import gr.hua.aurora.ble.transport.BleGattTransportFrame
import gr.hua.aurora.protocol.IncomingMessageReceiveUseCase
import gr.hua.aurora.protocol.IncomingTransportReceiveResult
import gr.hua.aurora.protocol.MessageFrameType
import gr.hua.aurora.protocol.NoOpIncomingSessionMaterialProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDirectSmokeTestSenderTest {
    @Test
    fun smokeTestSenderDefaultsToDisabledNotReady() {
        val sender = WifiDirectSmokeTestSender(
            submitFrame = { _, _ -> error("submit should not be called") },
            sendBridgeDiagnostics = { WifiDirectSendBridgeDiagnostics() },
            transportAdapterDiagnostics = { WifiDirectTransportAdapterDiagnostics() }
        )

        assertEquals(false, sender.currentDiagnostics().ready)
        assertEquals(false, sender.currentDiagnostics().sendBridgeEnabled)
        assertEquals(WifiDirectTransportAdapterState.DISABLED, sender.currentDiagnostics().adapterState)
        assertEquals(0L, sender.currentDiagnostics().smokeFramesSent)
        assertEquals(0L, sender.currentDiagnostics().smokeSendFailures)
    }

    @Test
    fun smokeTestSendIsBlockedWhenSendBridgeDisabled() {
        val submittedFrames = mutableListOf<WifiDirectTransportFrame>()
        val sender = WifiDirectSmokeTestSender(
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

        val failure = runCatching {
            sender.sendPublicSmokeTest("debug-user") { result ->
                result.getOrThrow()
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(
            "Wi-Fi Direct smoke test requires the send bridge to be enabled.",
            failure?.message
        )
        assertEquals(emptyList<WifiDirectTransportFrame>(), submittedFrames)
        assertEquals(1L, sender.currentDiagnostics().smokeSendFailures)
        assertEquals("blocked", sender.currentDiagnostics().lastSmokeSendResult)
    }

    @Test
    fun smokeTestSendIsBlockedWhenTransportAdapterNotReady() {
        val submittedFrames = mutableListOf<WifiDirectTransportFrame>()
        val sender = WifiDirectSmokeTestSender(
            submitFrame = { frame, onResult ->
                submittedFrames += frame
                onResult(Result.success(Unit))
            },
            sendBridgeDiagnostics = { WifiDirectSendBridgeDiagnostics(enabled = true) },
            transportAdapterDiagnostics = {
                WifiDirectTransportAdapterDiagnostics(
                    state = WifiDirectTransportAdapterState.NOT_READY
                )
            }
        )

        val failure = runCatching {
            sender.sendPublicSmokeTest("debug-user") { result ->
                result.getOrThrow()
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(
            "Wi-Fi Direct smoke test requires a ready transport adapter.",
            failure?.message
        )
        assertEquals(emptyList<WifiDirectTransportFrame>(), submittedFrames)
        assertEquals(1L, sender.currentDiagnostics().smokeSendFailures)
        assertEquals("blocked", sender.currentDiagnostics().lastSmokeSendResult)
    }

    @Test
    fun smokeTestCreatesValidTransportBytesWhenEnabledAndReady() {
        val submittedFrames = mutableListOf<WifiDirectTransportFrame>()
        val sender = WifiDirectSmokeTestSender(
            submitFrame = { frame, onResult ->
                submittedFrames += frame
                onResult(Result.success(Unit))
            },
            sendBridgeDiagnostics = { WifiDirectSendBridgeDiagnostics(enabled = true) },
            transportAdapterDiagnostics = {
                WifiDirectTransportAdapterDiagnostics(
                    state = WifiDirectTransportAdapterState.READY
                )
            },
            nowMillis = { 1_717_000_001L }
        )

        sender.sendPublicSmokeTest("debug-user")

        assertTrue(submittedFrames.isNotEmpty())
        val bleFrames = submittedFrames.map { submittedFrame ->
            assertNotNull(BleGattTransportFrame.parse(submittedFrame.payloadBytes()))
            requireNotNull(BleGattTransportFrame.parse(submittedFrame.payloadBytes()))
        }
        val receiveResult = IncomingMessageReceiveUseCase.receive(
            frames = bleFrames,
            sessionMaterialProvider = NoOpIncomingSessionMaterialProvider
        )

        assertTrue(receiveResult is IncomingTransportReceiveResult.Received)
        val receivedMessage = (receiveResult as IncomingTransportReceiveResult.Received).message
        assertEquals(MessageFrameType.GLOBAL_TEXT, receivedMessage.frame.type)
        assertEquals("debug-user", receivedMessage.frame.senderId)
        assertEquals("[Wi-Fi Direct debug smoke test]", receivedMessage.frame.payload)
    }

    @Test
    fun smokeTestDiagnosticsUpdateOnSuccess() {
        val sender = WifiDirectSmokeTestSender(
            submitFrame = { _, onResult ->
                onResult(Result.success(Unit))
            },
            sendBridgeDiagnostics = { WifiDirectSendBridgeDiagnostics(enabled = true) },
            transportAdapterDiagnostics = {
                WifiDirectTransportAdapterDiagnostics(
                    state = WifiDirectTransportAdapterState.READY
                )
            }
        )

        sender.sendPublicSmokeTest("debug-user")

        assertEquals(1L, sender.currentDiagnostics().smokeFramesSent)
        assertEquals(0L, sender.currentDiagnostics().smokeSendFailures)
        assertEquals("submitted locally", sender.currentDiagnostics().lastSmokeSendResult)
        assertEquals(null, sender.currentDiagnostics().lastSmokeError)
        assertTrue((sender.currentDiagnostics().lastSmokeFrameSize ?: 0) > 0)
    }

    @Test
    fun smokeTestDiagnosticsUpdateOnFailure() {
        val sender = WifiDirectSmokeTestSender(
            submitFrame = { _, onResult ->
                onResult(Result.failure(IllegalStateException("socket closed")))
            },
            sendBridgeDiagnostics = { WifiDirectSendBridgeDiagnostics(enabled = true) },
            transportAdapterDiagnostics = {
                WifiDirectTransportAdapterDiagnostics(
                    state = WifiDirectTransportAdapterState.READY
                )
            }
        )

        val failure = runCatching {
            sender.sendPublicSmokeTest("debug-user") { result ->
                result.getOrThrow()
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("socket closed", failure?.message)
        assertEquals(0L, sender.currentDiagnostics().smokeFramesSent)
        assertEquals(1L, sender.currentDiagnostics().smokeSendFailures)
        assertEquals("failed", sender.currentDiagnostics().lastSmokeSendResult)
        assertEquals("socket closed", sender.currentDiagnostics().lastSmokeError)
    }

    @Test
    fun resetDiagnosticsClearsCountersWhileKeepingReadiness() {
        val sender = WifiDirectSmokeTestSender(
            submitFrame = { _, onResult -> onResult(Result.success(Unit)) },
            sendBridgeDiagnostics = { WifiDirectSendBridgeDiagnostics(enabled = true) },
            transportAdapterDiagnostics = {
                WifiDirectTransportAdapterDiagnostics(
                    state = WifiDirectTransportAdapterState.READY
                )
            }
        )

        sender.sendPublicSmokeTest("debug-user")
        sender.resetDiagnostics()

        assertTrue(sender.currentDiagnostics().ready)
        assertTrue(sender.currentDiagnostics().sendBridgeEnabled)
        assertEquals(WifiDirectTransportAdapterState.READY, sender.currentDiagnostics().adapterState)
        assertEquals(0L, sender.currentDiagnostics().smokeFramesSent)
        assertEquals(0L, sender.currentDiagnostics().smokeSendFailures)
        assertEquals(null, sender.currentDiagnostics().lastSmokeFrameSize)
        assertEquals(null, sender.currentDiagnostics().lastSmokeSendResult)
        assertEquals(null, sender.currentDiagnostics().lastSmokeError)
    }
}
