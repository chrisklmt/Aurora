package gr.hua.aurora.wifidirect.frame

private val wifiDirectTransportMagic = byteArrayOf(
    0x41,
    0x57,
    0x44,
    0x54
)
private const val wifiDirectTransportVersion: Byte = 0x01

internal class WifiDirectTransportFrameCodec {
    fun encode(
        frame: WifiDirectTransportFrame
    ): ByteArray {
        val payload = frame.payloadBytes()
        if (payload.isEmpty()) {
            throw WifiDirectTransportFrameCodecException(
                "Wi-Fi Direct transport payload must not be empty."
            )
        }
        if (payload.size > wifiDirectTransportFrameMaxPayloadBytes) {
            throw WifiDirectTransportFrameCodecException(
                "Wi-Fi Direct transport payload exceeds $wifiDirectTransportFrameMaxPayloadBytes bytes."
            )
        }
        return ByteArray(wifiDirectTransportFrameHeaderSizeBytes + payload.size).also { encoded ->
            wifiDirectTransportMagic.copyInto(
                destination = encoded,
                destinationOffset = 0
            )
            encoded[wifiDirectTransportMagic.size] = wifiDirectTransportVersion
            payload.copyInto(
                destination = encoded,
                destinationOffset = wifiDirectTransportFrameHeaderSizeBytes
            )
        }
    }

    fun decodeOrNull(
        payload: ByteArray
    ): Result<WifiDirectTransportFrame?> {
        return runCatching {
            if (payload.size < wifiDirectTransportFrameHeaderSizeBytes) {
                return@runCatching null
            }
            if (!payload.copyOfRange(0, wifiDirectTransportMagic.size).contentEquals(wifiDirectTransportMagic)) {
                return@runCatching null
            }
            val version = payload[wifiDirectTransportMagic.size]
            if (version != wifiDirectTransportVersion) {
                throw WifiDirectTransportFrameCodecException(
                    "Unsupported Wi-Fi Direct transport frame version."
                )
            }
            val transportPayload = payload.copyOfRange(
                wifiDirectTransportFrameHeaderSizeBytes,
                payload.size
            )
            if (transportPayload.isEmpty()) {
                throw WifiDirectTransportFrameCodecException(
                    "Wi-Fi Direct transport payload must not be empty."
                )
            }
            if (transportPayload.size > wifiDirectTransportFrameMaxPayloadBytes) {
                throw WifiDirectTransportFrameCodecException(
                    "Wi-Fi Direct transport payload exceeds $wifiDirectTransportFrameMaxPayloadBytes bytes."
                )
            }
            WifiDirectTransportFrame.fromPayload(transportPayload)
        }
    }
}

internal class WifiDirectTransportFrameCodecException(
    message: String
) : Exception(message)
