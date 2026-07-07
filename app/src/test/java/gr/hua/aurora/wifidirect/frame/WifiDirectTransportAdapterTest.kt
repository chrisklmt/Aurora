package gr.hua.aurora.wifidirect.frame

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDirectTransportAdapterTest {
    @Test
    fun defaultAdapterStateIsDisabled() {
        val adapter = WifiDirectTransportAdapter()

        try {
            assertEquals(
                WifiDirectTransportAdapterState.DISABLED,
                adapter.currentDiagnostics().state
            )
        } finally {
            adapter.dispose()
        }
    }

    @Test
    fun enabledAdapterReportsNotReadyWithoutConnectedFrameTransport() {
        val transport = FakeWifiDirectTransport()
        val adapter = WifiDirectTransportAdapter(
            frameSink = transport,
            frameSource = transport,
            enabled = true
        )

        try {
            assertEquals(
                WifiDirectTransportAdapterState.NOT_READY,
                adapter.currentDiagnostics().state
            )
            assertEquals(
                "Wi-Fi Direct transport frame sink not ready.",
                adapter.currentDiagnostics().notReadyReason
            )
        } finally {
            adapter.dispose()
        }
    }

    @Test
    fun enabledAdapterSurfacesTransportReadinessReasonWhenBlocked() {
        val transport = FakeWifiDirectTransport(
            readinessReason = "Waiting for a socket client."
        )
        val adapter = WifiDirectTransportAdapter(
            frameSink = transport,
            frameSource = transport,
            enabled = true
        )

        try {
            assertEquals(
                WifiDirectTransportAdapterState.NOT_READY,
                adapter.currentDiagnostics().state
            )
            assertEquals(
                "Waiting for a socket client.",
                adapter.currentDiagnostics().notReadyReason
            )
        } finally {
            adapter.dispose()
        }
    }

    @Test
    fun adapterCanSubmitSyntheticPayloadWhenTransportReady() {
        val transport = FakeWifiDirectTransport(isReady = true)
        val adapter = WifiDirectTransportAdapter(
            frameSink = transport,
            frameSource = transport,
            enabled = true
        )
        val codec = WifiDirectTransportFrameCodec()

        try {
            adapter.submitPayload("debug-adapter".toByteArray())

            val submitted = transport.submittedPayloads.single()
            val decoded = codec.decodeOrNull(submitted).getOrThrow()

            assertArrayEquals(
                "debug-adapter".toByteArray(),
                requireNotNull(decoded).payloadBytes()
            )
            assertEquals(1L, adapter.currentDiagnostics().framesSubmitted)
            assertEquals(13L, adapter.currentDiagnostics().bytesSubmitted)
            assertEquals(13, adapter.currentDiagnostics().lastSubmittedFrameSize)
            assertEquals(
                WifiDirectTransportAdapterState.READY,
                adapter.currentDiagnostics().state
            )
        } finally {
            adapter.dispose()
        }
    }

    @Test
    fun adapterRejectsOversizedPayload() {
        val transport = FakeWifiDirectTransport(isReady = true)
        val adapter = WifiDirectTransportAdapter(
            frameSink = transport,
            frameSource = transport,
            enabled = true
        )

        try {
            val failure = captureFailure {
                adapter.submitPayload(
                    ByteArray(wifiDirectTransportFrameMaxPayloadBytes + 1)
                ) { result ->
                    result.getOrThrow()
                }
            }

            assertTrue(failure is IllegalArgumentException)
            assertEquals(
                "Wi-Fi Direct transport payload exceeds $wifiDirectTransportFrameMaxPayloadBytes bytes.",
                failure?.message
            )
            assertEquals(
                WifiDirectTransportAdapterState.FAILED,
                adapter.currentDiagnostics().state
            )
        } finally {
            adapter.dispose()
        }
    }

    @Test
    fun adapterRejectsEmptyPayload() {
        val transport = FakeWifiDirectTransport(isReady = true)
        val adapter = WifiDirectTransportAdapter(
            frameSink = transport,
            frameSource = transport,
            enabled = true
        )

        try {
            val failure = captureFailure {
                adapter.submitPayload(ByteArray(0)) { result ->
                    result.getOrThrow()
                }
            }

            assertTrue(failure is IllegalArgumentException)
            assertEquals(
                "Wi-Fi Direct transport payload must not be empty.",
                failure?.message
            )
        } finally {
            adapter.dispose()
        }
    }

    @Test
    fun adapterReceiveCallbackExposesSyntheticPayload() {
        val transport = FakeWifiDirectTransport(isReady = true)
        val adapter = WifiDirectTransportAdapter(
            frameSink = transport,
            frameSource = transport,
            enabled = true
        )
        val receivedFrames = mutableListOf<WifiDirectTransportFrame>()
        val listener = object : WifiDirectTransportAdapter.Listener {
            override fun onTransportFrameReceived(frame: WifiDirectTransportFrame) {
                receivedFrames += frame
            }
        }
        val codec = WifiDirectTransportFrameCodec()

        try {
            adapter.addListener(listener)

            transport.emitIncoming(
                codec.encode(
                    WifiDirectTransportFrame.fromPayload("hello".toByteArray())
                )
            )

            assertEquals(1, receivedFrames.size)
            assertArrayEquals("hello".toByteArray(), receivedFrames.single().payloadBytes())
            assertEquals(1L, adapter.currentDiagnostics().framesReceived)
            assertEquals(5L, adapter.currentDiagnostics().bytesReceived)
            assertEquals(5, adapter.currentDiagnostics().lastReceivedFrameSize)
        } finally {
            adapter.removeListener(listener)
            adapter.dispose()
        }
    }

    @Test
    fun adapterReceiveIgnoresNonAdapterDebugPayload() {
        val transport = FakeWifiDirectTransport(isReady = true)
        val adapter = WifiDirectTransportAdapter(
            frameSink = transport,
            frameSource = transport,
            enabled = true
        )

        try {
            transport.emitIncoming("ping".toByteArray())

            assertEquals(0L, adapter.currentDiagnostics().framesReceived)
            assertEquals(
                WifiDirectTransportAdapterState.READY,
                adapter.currentDiagnostics().state
            )
        } finally {
            adapter.dispose()
        }
    }

    @Test
    fun adapterPropagatesSafeSinkFailure() {
        val transport = FakeWifiDirectTransport(
            isReady = true,
            submitFailure = IllegalStateException("writer unavailable")
        )
        val adapter = WifiDirectTransportAdapter(
            frameSink = transport,
            frameSource = transport,
            enabled = true
        )

        try {
            val failure = captureFailure {
                adapter.submitPayload("debug".toByteArray()) { result ->
                    result.getOrThrow()
                }
            }

            assertTrue(failure is IllegalStateException)
            assertEquals("writer unavailable", failure?.message)
            assertEquals(
                "writer unavailable",
                adapter.currentDiagnostics().lastError
            )
        } finally {
            adapter.dispose()
        }
    }

    @Test
    fun resetDiagnosticsClearsCountersAndPreservesReadyState() {
        val transport = FakeWifiDirectTransport(isReady = true)
        val adapter = WifiDirectTransportAdapter(
            frameSink = transport,
            frameSource = transport,
            enabled = true
        )
        val codec = WifiDirectTransportFrameCodec()

        try {
            adapter.submitPayload("debug-adapter".toByteArray())
            transport.emitIncoming(
                codec.encode(
                    WifiDirectTransportFrame.fromPayload("hello".toByteArray())
                )
            )

            adapter.resetDiagnostics()

            assertEquals(
                WifiDirectTransportAdapterState.READY,
                adapter.currentDiagnostics().state
            )
            assertEquals(0L, adapter.currentDiagnostics().framesSubmitted)
            assertEquals(0L, adapter.currentDiagnostics().framesReceived)
            assertEquals(0L, adapter.currentDiagnostics().bytesSubmitted)
            assertEquals(0L, adapter.currentDiagnostics().bytesReceived)
            assertEquals(null, adapter.currentDiagnostics().lastFrameSize)
            assertEquals(null, adapter.currentDiagnostics().lastError)
        } finally {
            adapter.dispose()
        }
    }

    private fun captureFailure(
        block: () -> Unit
    ): Throwable? {
        return runCatching(block).exceptionOrNull()
    }

    private class FakeWifiDirectTransport(
        var isReady: Boolean = false,
        private val readinessReason: String? = null,
        private val submitFailure: Throwable? = null
    ) : WifiDirectTransportFrameSink, WifiDirectTransportFrameSource {
        val submittedPayloads = mutableListOf<ByteArray>()
        private val listeners = linkedSetOf<WifiDirectTransportFrameSource.Listener>()

        override fun isTransportFrameReady(): Boolean {
            return isReady
        }

        override fun transportFrameReadinessReason(): String? {
            return readinessReason
        }

        override fun submitTransportFramePayload(
            payload: ByteArray,
            onResult: (Result<Unit>) -> Unit
        ) {
            submitFailure?.let { error ->
                onResult(Result.failure(error))
                return
            }
            submittedPayloads += payload.copyOf()
            onResult(Result.success(Unit))
        }

        override fun addTransportFrameListener(listener: WifiDirectTransportFrameSource.Listener) {
            listeners += listener
        }

        override fun removeTransportFrameListener(listener: WifiDirectTransportFrameSource.Listener) {
            listeners -= listener
        }

        fun emitIncoming(payload: ByteArray) {
            listeners.forEach { listener ->
                listener.onTransportFramePayloadReceived(
                    payload = payload.copyOf(),
                    byteCount = payload.size.toLong()
                )
            }
        }
    }
}
