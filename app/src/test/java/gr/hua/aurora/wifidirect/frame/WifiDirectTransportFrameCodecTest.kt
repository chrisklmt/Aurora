package gr.hua.aurora.wifidirect.frame

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDirectTransportFrameCodecTest {
    private val codec = WifiDirectTransportFrameCodec()

    @Test
    fun encodeAndDecodeRoundTripsOpaquePayload() {
        val payload = "aurora-transport".toByteArray()

        val encoded = codec.encode(
            WifiDirectTransportFrame.fromPayload(payload)
        )
        val decoded = codec.decodeOrNull(encoded).getOrThrow()

        assertArrayEquals(payload, requireNotNull(decoded).payloadBytes())
    }

    @Test
    fun decodeIgnoresNonAdapterDebugPayload() {
        val decoded = codec.decodeOrNull("ping".toByteArray()).getOrThrow()

        assertNull(decoded)
    }

    @Test
    fun decodeRejectsUnsupportedVersion() {
        val encoded = codec.encode(
            WifiDirectTransportFrame.fromPayload("debug".toByteArray())
        ).copyOf().also { bytes ->
            bytes[4] = 0x02
        }

        val failure = codec.decodeOrNull(encoded).exceptionOrNull()

        assertTrue(failure is WifiDirectTransportFrameCodecException)
        assertEquals(
            "Unsupported Wi-Fi Direct transport frame version.",
            failure?.message
        )
    }

    @Test
    fun transportFrameRejectsEmptyPayload() {
        val failure = runCatching {
            WifiDirectTransportFrame.fromPayload(ByteArray(0))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(
            "Wi-Fi Direct transport payload must not be empty.",
            failure?.message
        )
    }

    @Test
    fun transportFrameRejectsOversizedPayload() {
        val failure = runCatching {
            WifiDirectTransportFrame.fromPayload(
                ByteArray(wifiDirectTransportFrameMaxPayloadBytes + 1)
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(
            "Wi-Fi Direct transport payload exceeds $wifiDirectTransportFrameMaxPayloadBytes bytes.",
            failure?.message
        )
    }
}
