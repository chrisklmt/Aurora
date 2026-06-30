package gr.hua.aurora.wifidirect

import java.nio.ByteBuffer

internal class WifiDirectFrameCodec(
    val maxPayloadSize: Int = wifiDirectDebugFrameMaxPayloadBytes
) {
    fun encode(
        frame: WifiDirectFrame
    ): ByteArray {
        val payload = frame.payloadBytes()
        validatePayloadSize(payload.size)
        return ByteBuffer.allocate(Int.SIZE_BYTES + payload.size)
            .putInt(payload.size)
            .put(payload)
            .array()
    }

    fun newDecoder(): Decoder {
        return Decoder(maxPayloadSize = maxPayloadSize)
    }

    private fun validatePayloadSize(
        payloadSize: Int
    ) {
        if (payloadSize > maxPayloadSize) {
            throw WifiDirectFrameDecodeException(
                "Frame payload exceeds ${maxPayloadSize} bytes."
            )
        }
    }

    internal class Decoder(
        private val maxPayloadSize: Int
    ) {
        private var pending = ByteArray(0)

        fun append(
            bytes: ByteArray
        ): Result<List<WifiDirectFrame>> {
            return runCatching {
                if (bytes.isEmpty()) {
                    return@runCatching emptyList()
                }
                pending += bytes
                decodeAvailableFrames()
            }
        }

        fun finish(): Result<Unit> {
            return runCatching {
                if (pending.isNotEmpty()) {
                    pending = ByteArray(0)
                    throw WifiDirectFrameDecodeException(
                        "Truncated Wi-Fi Direct frame."
                    )
                }
            }
        }

        private fun decodeAvailableFrames(): List<WifiDirectFrame> {
            val frames = mutableListOf<WifiDirectFrame>()
            while (pending.size >= Int.SIZE_BYTES) {
                val payloadSize = ByteBuffer.wrap(
                    pending,
                    0,
                    Int.SIZE_BYTES
                ).int
                if (payloadSize < 0) {
                    pending = ByteArray(0)
                    throw WifiDirectFrameDecodeException(
                        "Negative Wi-Fi Direct frame length."
                    )
                }
                if (payloadSize > maxPayloadSize) {
                    pending = ByteArray(0)
                    throw WifiDirectFrameDecodeException(
                        "Frame payload exceeds ${maxPayloadSize} bytes."
                    )
                }
                val totalSize = Int.SIZE_BYTES + payloadSize
                if (pending.size < totalSize) {
                    break
                }
                frames += WifiDirectFrame.fromPayload(
                    pending.copyOfRange(Int.SIZE_BYTES, totalSize)
                )
                pending = pending.copyOfRange(totalSize, pending.size)
            }
            return frames
        }
    }
}

internal class WifiDirectFrameDecodeException(
    message: String
) : Exception(message)
