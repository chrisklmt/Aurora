package gr.hua.aurora.wifidirect.frame

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDirectFrameCodecTest {
    @Test
    fun encodeAndDecodeRoundTripsSmallPayload() {
        val codec = WifiDirectFrameCodec()
        val payload = "debug".toByteArray()
        val encoded = codec.encode(WifiDirectFrame.fromPayload(payload))

        val decoded = codec.newDecoder()
            .append(encoded)
            .getOrThrow()
            .single()

        assertArrayEquals(payload, decoded.payloadBytes())
    }

    @Test
    fun decoderSupportsMultipleFramesInSequence() {
        val codec = WifiDirectFrameCodec()
        val firstPayload = "ping".toByteArray()
        val secondPayload = "pong".toByteArray()
        val encoded = codec.encode(WifiDirectFrame.fromPayload(firstPayload)) +
            codec.encode(WifiDirectFrame.fromPayload(secondPayload))

        val decoded = codec.newDecoder()
            .append(encoded)
            .getOrThrow()

        assertEquals(2, decoded.size)
        assertArrayEquals(firstPayload, decoded[0].payloadBytes())
        assertArrayEquals(secondPayload, decoded[1].payloadBytes())
    }

    @Test
    fun decoderWaitsForPartialFrameBeforeEmitting() {
        val codec = WifiDirectFrameCodec()
        val payload = "ping".toByteArray()
        val encoded = codec.encode(WifiDirectFrame.fromPayload(payload))
        val decoder = codec.newDecoder()

        assertTrue(
            decoder.append(encoded.copyOfRange(0, 3))
                .getOrThrow()
                .isEmpty()
        )
        assertTrue(
            decoder.append(encoded.copyOfRange(3, 6))
                .getOrThrow()
                .isEmpty()
        )

        val decoded = decoder.append(encoded.copyOfRange(6, encoded.size))
            .getOrThrow()
            .single()

        assertArrayEquals(payload, decoded.payloadBytes())
    }

    @Test
    fun decoderRejectsOversizedLengthHeader() {
        val codec = WifiDirectFrameCodec(maxPayloadSize = 8)
        val oversizedHeader = ByteBuffer.allocate(Int.SIZE_BYTES)
            .putInt(9)
            .array()

        val failure = codec.newDecoder()
            .append(oversizedHeader)
            .exceptionOrNull()

        assertTrue(failure is WifiDirectFrameDecodeException)
        assertEquals(
            "Frame payload exceeds 8 bytes.",
            failure?.message
        )
    }

    @Test
    fun decoderRejectsNegativeLengthHeader() {
        val codec = WifiDirectFrameCodec()
        val invalidHeader = ByteBuffer.allocate(Int.SIZE_BYTES)
            .putInt(-1)
            .array()

        val failure = codec.newDecoder()
            .append(invalidHeader)
            .exceptionOrNull()

        assertTrue(failure is WifiDirectFrameDecodeException)
        assertEquals(
            "Negative Wi-Fi Direct frame length.",
            failure?.message
        )
    }

    @Test
    fun decoderRejectsTruncatedFrameAtFinish() {
        val codec = WifiDirectFrameCodec()
        val payload = "ping".toByteArray()
        val encoded = codec.encode(WifiDirectFrame.fromPayload(payload))
        val decoder = codec.newDecoder()

        decoder.append(encoded.copyOfRange(0, encoded.size - 1)).getOrThrow()

        val failure = decoder.finish().exceptionOrNull()

        assertTrue(failure is WifiDirectFrameDecodeException)
        assertEquals(
            "Truncated Wi-Fi Direct frame.",
            failure?.message
        )
    }

    @Test
    fun encodeRejectsOversizedPayloadLocally() {
        val codec = WifiDirectFrameCodec(maxPayloadSize = 4)

        val failure = runCatching {
            codec.encode(WifiDirectFrame.fromPayload("12345".toByteArray()))
        }.exceptionOrNull()

        assertTrue(failure is WifiDirectFrameDecodeException)
        assertEquals(
            "Frame payload exceeds 4 bytes.",
            failure?.message
        )
    }
}
