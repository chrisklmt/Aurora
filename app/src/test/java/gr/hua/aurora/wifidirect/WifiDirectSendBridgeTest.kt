package gr.hua.aurora.wifidirect

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDirectSendBridgeTest {
    @Test
    fun sendBridgeDefaultsToDisabled() {
        val adapter = FakeTransportAdapter()
        val bridge = WifiDirectSendBridge(adapter::submit)

        assertEquals(false, bridge.currentDiagnostics().enabled)
        assertEquals(0L, bridge.currentDiagnostics().framesSubmitted)
        assertEquals(0L, bridge.currentDiagnostics().submitFailures)
    }

    @Test
    fun disabledSendBridgeRejectsSubmitAndDoesNotCallAdapter() {
        val adapter = FakeTransportAdapter()
        val bridge = WifiDirectSendBridge(adapter::submit)

        val failure = runCatching {
            bridge.submitPayload("hello".toByteArray()) { result ->
                result.getOrThrow()
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("Wi-Fi Direct send bridge disabled.", failure?.message)
        assertEquals(0, adapter.submittedFrames.size)
        assertEquals(0L, bridge.currentDiagnostics().framesSubmitted)
        assertEquals(1L, bridge.currentDiagnostics().submitFailures)
    }

    @Test
    fun enabledSendBridgeSubmitsValidPayloadToAdapter() {
        val adapter = FakeTransportAdapter()
        val bridge = WifiDirectSendBridge(adapter::submit)

        bridge.setEnabled(true)
        bridge.submitPayload("hello".toByteArray())

        assertEquals(1, adapter.submittedFrames.size)
        assertArrayEquals(
            "hello".toByteArray(),
            adapter.submittedFrames.single().payloadBytes()
        )
        assertEquals(1L, bridge.currentDiagnostics().framesSubmitted)
        assertEquals(0L, bridge.currentDiagnostics().submitFailures)
        assertEquals(5, bridge.currentDiagnostics().lastSubmittedFrameSize)
    }

    @Test
    fun sendBridgeRejectsEmptyPayloadSafely() {
        val adapter = FakeTransportAdapter()
        val bridge = WifiDirectSendBridge(adapter::submit)

        bridge.setEnabled(true)
        val failure = runCatching {
            bridge.submitPayload(ByteArray(0)) { result ->
                result.getOrThrow()
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(
            "Wi-Fi Direct transport payload must not be empty.",
            failure?.message
        )
        assertEquals(0, adapter.submittedFrames.size)
        assertEquals(1L, bridge.currentDiagnostics().submitFailures)
    }

    @Test
    fun sendBridgeRejectsOversizedPayloadSafely() {
        val adapter = FakeTransportAdapter()
        val bridge = WifiDirectSendBridge(adapter::submit)

        bridge.setEnabled(true)
        val failure = runCatching {
            bridge.submitPayload(
                ByteArray(wifiDirectTransportFrameMaxPayloadBytes + 1)
            ) { result ->
                result.getOrThrow()
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(
            "Wi-Fi Direct transport payload exceeds $wifiDirectTransportFrameMaxPayloadBytes bytes.",
            failure?.message
        )
        assertEquals(0, adapter.submittedFrames.size)
        assertEquals(1L, bridge.currentDiagnostics().submitFailures)
    }

    @Test
    fun sendBridgeDiagnosticsUpdateOnAdapterFailure() {
        val adapter = FakeTransportAdapter(
            submitFailure = IllegalStateException("adapter not ready")
        )
        val bridge = WifiDirectSendBridge(adapter::submit)

        bridge.setEnabled(true)
        val failure = runCatching {
            bridge.submitPayload("hello".toByteArray()) { result ->
                result.getOrThrow()
            }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("adapter not ready", failure?.message)
        assertEquals(1L, bridge.currentDiagnostics().submitFailures)
        assertEquals("adapter not ready", bridge.currentDiagnostics().lastSendBridgeError)
    }

    @Test
    fun sendAndReceiveBridgeTogglesRemainIndependent() {
        val adapter = FakeTransportAdapter()
        val sendBridge = WifiDirectSendBridge(adapter::submit)
        val receiveBridge = WifiDirectReceiveBridge {
            error("Receive bridge should not be called.")
        }

        sendBridge.setEnabled(true)

        assertEquals(true, sendBridge.currentDiagnostics().enabled)
        assertEquals(false, receiveBridge.currentDiagnostics().enabled)

        receiveBridge.setEnabled(true)
        sendBridge.disable()

        assertEquals(false, sendBridge.currentDiagnostics().enabled)
        assertEquals(true, receiveBridge.currentDiagnostics().enabled)
    }

    private class FakeTransportAdapter(
        private val submitFailure: Throwable? = null
    ) {
        val submittedFrames = mutableListOf<WifiDirectTransportFrame>()

        fun submit(
            frame: WifiDirectTransportFrame,
            onResult: (Result<Unit>) -> Unit
        ) {
            submitFailure?.let { error ->
                onResult(Result.failure(error))
                return
            }
            submittedFrames += frame
            onResult(Result.success(Unit))
        }
    }
}
