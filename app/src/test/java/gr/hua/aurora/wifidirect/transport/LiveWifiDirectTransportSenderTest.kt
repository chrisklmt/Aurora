package gr.hua.aurora.wifidirect.transport

import gr.hua.aurora.wifidirect.frame.WifiDirectTransportAdapter
import gr.hua.aurora.wifidirect.frame.WifiDirectTransportFrame
import gr.hua.aurora.wifidirect.frame.WifiDirectTransportFrameCodec
import gr.hua.aurora.wifidirect.frame.WifiDirectTransportFrameSink
import gr.hua.aurora.wifidirect.frame.WifiDirectTransportFrameSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveWifiDirectTransportSenderTest {
    @Test
    fun returnsNotReadyWhenThereIsNoReadySocketTransport() = runBlocking {
        val transport = FakeWifiDirectTransport(
            isReady = false,
            readinessReason = "Waiting for a socket client."
        )
        val adapter = WifiDirectTransportAdapter(
            frameSink = transport,
            frameSource = transport,
            enabled = true
        )
        val sender = LiveWifiDirectTransportSender(adapter)

        try {
            val result = sender.send(
                WifiDirectTransportFrame.fromPayload("hello".toByteArray())
            )

            assertTrue(result is WifiDirectTransportSendResult.NotReady)
            assertEquals(
                "Waiting for a socket client.",
                (result as WifiDirectTransportSendResult.NotReady).reason
            )
            assertEquals(0, transport.submittedPayloads.size)
        } finally {
            adapter.dispose()
        }
    }

    @Test
    fun delegatesToExistingFrameTransportWhenReady() = runBlocking {
        val transport = FakeWifiDirectTransport(isReady = true)
        val adapter = WifiDirectTransportAdapter(
            frameSink = transport,
            frameSource = transport,
            enabled = true
        )
        val sender = LiveWifiDirectTransportSender(adapter)
        val codec = WifiDirectTransportFrameCodec()

        try {
            val result = sender.send(
                WifiDirectTransportFrame.fromPayload("debug-frame".toByteArray())
            )

            assertEquals(WifiDirectTransportSendResult.Success, result)
            assertEquals(1, transport.submittedPayloads.size)
            val decodedFrame = codec.decodeOrNull(
                transport.submittedPayloads.single()
            ).getOrThrow()
            assertArrayEquals(
                "debug-frame".toByteArray(),
                requireNotNull(decodedFrame).payloadBytes()
            )
        } finally {
            adapter.dispose()
        }
    }

    @Test
    fun mapsTransportFailureToFailedWithoutCrashing() = runBlocking {
        val transport = FakeWifiDirectTransport(
            isReady = true,
            submitFailure = IllegalStateException("writer unavailable")
        )
        val adapter = WifiDirectTransportAdapter(
            frameSink = transport,
            frameSource = transport,
            enabled = true
        )
        val sender = LiveWifiDirectTransportSender(adapter)

        try {
            val result = sender.send(
                WifiDirectTransportFrame.fromPayload("debug-frame".toByteArray())
            )

            assertTrue(result is WifiDirectTransportSendResult.Failed)
            val failed = result as WifiDirectTransportSendResult.Failed
            assertEquals("writer unavailable", failed.reason)
            assertTrue(failed.cause is IllegalStateException)
            assertEquals(0, transport.submittedPayloads.size)
        } finally {
            adapter.dispose()
        }
    }

    @Test
    fun doesNotModifyFrameContents() = runBlocking {
        val transport = FakeWifiDirectTransport(isReady = true)
        val adapter = WifiDirectTransportAdapter(
            frameSink = transport,
            frameSource = transport,
            enabled = true
        )
        val sender = LiveWifiDirectTransportSender(adapter)
        val codec = WifiDirectTransportFrameCodec()
        val payload = "stable-payload".toByteArray()
        val expectedPayload = payload.copyOf()
        val frame = WifiDirectTransportFrame.fromPayload(payload)
        payload[0] = 'X'.code.toByte()

        try {
            val result = sender.send(frame)

            assertEquals(WifiDirectTransportSendResult.Success, result)
            val decodedFrame = codec.decodeOrNull(
                transport.submittedPayloads.single()
            ).getOrThrow()
            assertArrayEquals(
                expectedPayload,
                requireNotNull(decodedFrame).payloadBytes()
            )
            assertArrayEquals(
                expectedPayload,
                frame.payloadBytes()
            )
        } finally {
            adapter.dispose()
        }
    }

    private class FakeWifiDirectTransport(
        private val isReady: Boolean,
        private val readinessReason: String? = null,
        private val submitFailure: Throwable? = null
    ) : WifiDirectTransportFrameSink, WifiDirectTransportFrameSource {
        val submittedPayloads = mutableListOf<ByteArray>()

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
        }

        override fun removeTransportFrameListener(
            listener: WifiDirectTransportFrameSource.Listener
        ) {
        }
    }
}
