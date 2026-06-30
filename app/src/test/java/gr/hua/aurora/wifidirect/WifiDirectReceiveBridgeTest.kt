package gr.hua.aurora.wifidirect

import gr.hua.aurora.ble.transport.BleGattTransportFrame
import gr.hua.aurora.ble.transport.BleTransportReceiveResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDirectReceiveBridgeTest {
    @Test
    fun receiveBridgeDefaultsToDisabledAndSkipsProcessing() {
        var processedCount = 0
        val bridge = WifiDirectReceiveBridge { frame ->
            processedCount += 1
            BleTransportReceiveResult.Buffered(
                groupId = 1,
                receivedChunks = 1,
                expectedChunks = 1
            )
        }

        bridge.onTransportFrameReceived(
            WifiDirectTransportFrame.fromPayload(validBleTransportFrameBytes())
        )

        assertEquals(0, processedCount)
        assertEquals(false, bridge.currentDiagnostics().enabled)
        assertEquals(0L, bridge.currentDiagnostics().framesBridged)
        assertEquals(0L, bridge.currentDiagnostics().bridgeFailures)
    }

    @Test
    fun receiveBridgeDisabledSkipsSmokeTransportFrames() {
        var processedCount = 0
        val bridge = WifiDirectReceiveBridge { _ ->
            processedCount += 1
            BleTransportReceiveResult.Buffered(
                groupId = 1,
                receivedChunks = 1,
                expectedChunks = 1
            )
        }

        smokeTransportFrames().forEach(bridge::onTransportFrameReceived)

        assertEquals(0, processedCount)
        assertEquals(0L, bridge.currentDiagnostics().framesBridged)
    }

    @Test
    fun enabledReceiveBridgePassesValidAuroraTransportFrameToProcessor() {
        val receivedFrames = mutableListOf<BleGattTransportFrame>()
        val bridge = WifiDirectReceiveBridge { frame ->
            receivedFrames += frame
            BleTransportReceiveResult.Buffered(
                groupId = 7,
                receivedChunks = 1,
                expectedChunks = 2
            )
        }

        bridge.setEnabled(true)
        val payload = validBleTransportFrameBytes()
        bridge.onTransportFrameReceived(
            WifiDirectTransportFrame.fromPayload(payload)
        )

        assertEquals(1, receivedFrames.size)
        assertEquals(1L, bridge.currentDiagnostics().framesBridged)
        assertEquals(0L, bridge.currentDiagnostics().bridgeFailures)
        assertEquals(payload.size, bridge.currentDiagnostics().lastBridgedFrameSize)
        assertNull(bridge.currentDiagnostics().lastBridgeError)
    }

    @Test
    fun enabledReceiveBridgePassesSmokeTransportFramesToProcessor() {
        val receivedFrames = mutableListOf<BleGattTransportFrame>()
        val smokeFrames = smokeTransportFrames()
        val bridge = WifiDirectReceiveBridge { frame ->
            receivedFrames += frame
            BleTransportReceiveResult.Buffered(
                groupId = 7,
                receivedChunks = receivedFrames.size,
                expectedChunks = smokeFrames.size
            )
        }

        bridge.setEnabled(true)
        smokeFrames.forEach(bridge::onTransportFrameReceived)

        assertEquals(smokeFrames.size, receivedFrames.size)
        assertEquals(smokeFrames.size.toLong(), bridge.currentDiagnostics().framesBridged)
        assertEquals(0L, bridge.currentDiagnostics().bridgeFailures)
        assertTrue(receivedFrames.all { frame -> frame.bodyToByteArray().isNotEmpty() })
    }

    @Test
    fun enabledReceiveBridgeFailsCleanlyForInvalidAuroraTransportPayload() {
        var processedCount = 0
        val bridge = WifiDirectReceiveBridge { _ ->
            processedCount += 1
            BleTransportReceiveResult.Buffered(
                groupId = 1,
                receivedChunks = 1,
                expectedChunks = 1
            )
        }

        bridge.setEnabled(true)
        bridge.onTransportFrameReceived(
            WifiDirectTransportFrame.fromPayload("hello".toByteArray())
        )

        assertEquals(0, processedCount)
        assertEquals(0L, bridge.currentDiagnostics().framesBridged)
        assertEquals(1L, bridge.currentDiagnostics().bridgeFailures)
        assertEquals(
            "Invalid Aurora transport frame payload.",
            bridge.currentDiagnostics().lastBridgeError
        )
    }

    @Test
    fun enabledReceiveBridgeRecordsProcessorFailures() {
        val bridge = WifiDirectReceiveBridge {
            BleTransportReceiveResult.InvalidChunk(
                reason = "Transport frame does not contain a valid chunk body."
            )
        }

        bridge.setEnabled(true)
        bridge.onTransportFrameReceived(
            WifiDirectTransportFrame.fromPayload(validBleTransportFrameBytes())
        )

        assertEquals(1L, bridge.currentDiagnostics().framesBridged)
        assertEquals(1L, bridge.currentDiagnostics().bridgeFailures)
        assertEquals(
            "Transport frame does not contain a valid chunk body.",
            bridge.currentDiagnostics().lastBridgeError
        )
    }

    @Test
    fun disablingReceiveBridgeClearsEnabledState() {
        val bridge = WifiDirectReceiveBridge {
            BleTransportReceiveResult.Buffered(
                groupId = 1,
                receivedChunks = 1,
                expectedChunks = 1
            )
        }

        bridge.setEnabled(true)
        bridge.disable()

        assertTrue(!bridge.currentDiagnostics().enabled)
    }

    private fun validBleTransportFrameBytes(): ByteArray {
        return requireNotNull(
            BleGattTransportFrame.create(
                body = byteArrayOf(0x01, 0x02, 0x03)
            )
        ).toByteArray()
    }

    private fun smokeTransportFrames(): List<WifiDirectTransportFrame> {
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
            nowMillis = { 1_717_000_002L }
        )

        sender.sendPublicSmokeTest("debug-user")

        return submittedFrames.toList()
    }
}
