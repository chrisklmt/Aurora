package gr.hua.aurora.wifidirect

import java.nio.charset.StandardCharsets

internal const val wifiDirectTransportFrameHeaderSizeBytes = 5

internal const val wifiDirectTransportFrameMaxPayloadBytes =
    wifiDirectDebugFrameMaxPayloadBytes - wifiDirectTransportFrameHeaderSizeBytes

private val wifiDirectSyntheticTransportPayload =
    "adapter-debug".toByteArray(StandardCharsets.UTF_8)

internal class WifiDirectTransportFrame private constructor(
    private val payload: ByteArray
) {
    val payloadSize: Int
        get() = payload.size

    fun payloadBytes(): ByteArray {
        return payload.copyOf()
    }

    companion object {
        fun fromPayload(
            payload: ByteArray
        ): WifiDirectTransportFrame {
            require(payload.isNotEmpty()) {
                "Wi-Fi Direct transport payload must not be empty."
            }
            require(payload.size <= wifiDirectTransportFrameMaxPayloadBytes) {
                "Wi-Fi Direct transport payload exceeds $wifiDirectTransportFrameMaxPayloadBytes bytes."
            }
            return WifiDirectTransportFrame(payload.copyOf())
        }
    }
}

internal fun wifiDirectSyntheticTransportFrame(): WifiDirectTransportFrame {
    return WifiDirectTransportFrame.fromPayload(wifiDirectSyntheticTransportPayload)
}
