package gr.hua.aurora.wifidirect.frame

import java.nio.charset.StandardCharsets

internal const val wifiDirectDebugFrameMaxPayloadBytes = 4_096

private const val wifiDirectFrameTransportNote =
    "Wi-Fi Direct chat routing not wired yet."

private val wifiDirectDebugPingPayload = "ping".toByteArray(StandardCharsets.UTF_8)
private val wifiDirectDebugPongPayload = "pong".toByteArray(StandardCharsets.UTF_8)

internal enum class WifiDirectFrameTransportState {
    IDLE,
    READY,
    FAILED
}

internal class WifiDirectFrame private constructor(
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
        ): WifiDirectFrame {
            return WifiDirectFrame(payload.copyOf())
        }
    }
}

internal data class WifiDirectFrameDiagnostics(
    val state: WifiDirectFrameTransportState = WifiDirectFrameTransportState.IDLE,
    val framesSent: Long = 0L,
    val framesReceived: Long = 0L,
    val bytesSent: Long = 0L,
    val bytesReceived: Long = 0L,
    val lastFrameSize: Int? = null,
    val lastSentFrameSize: Int? = null,
    val lastReceivedFrameSize: Int? = null,
    val lastError: String? = null,
    val note: String = wifiDirectFrameTransportNote
)

internal fun wifiDirectFrameTransportStateSummary(
    state: WifiDirectFrameTransportState
): String {
    return when (state) {
        WifiDirectFrameTransportState.IDLE -> "idle"
        WifiDirectFrameTransportState.READY -> "ready"
        WifiDirectFrameTransportState.FAILED -> "failed"
    }
}

internal fun wifiDirectFrameCountSummary(
    diagnostics: WifiDirectFrameDiagnostics
): String {
    return "${diagnostics.framesSent}/${diagnostics.framesReceived}"
}

internal fun wifiDirectFrameByteSummary(
    diagnostics: WifiDirectFrameDiagnostics
): String {
    return "${diagnostics.bytesSent}/${diagnostics.bytesReceived}"
}

internal fun wifiDirectFrameSizeSummary(
    sizeBytes: Int?
): String {
    return sizeBytes?.let { "$it B" } ?: "none"
}

internal fun wifiDirectDebugPingFrame(): WifiDirectFrame {
    return WifiDirectFrame.fromPayload(wifiDirectDebugPingPayload)
}

internal fun wifiDirectDebugAutoReplyFrameOrNull(
    frame: WifiDirectFrame
): WifiDirectFrame? {
    return if (frame.payloadBytes().contentEquals(wifiDirectDebugPingPayload)) {
        WifiDirectFrame.fromPayload(wifiDirectDebugPongPayload)
    } else {
        null
    }
}

internal fun wifiDirectFrameDebugLabel(
    frame: WifiDirectFrame
): String {
    val payload = frame.payloadBytes()
    return when {
        payload.contentEquals(wifiDirectDebugPingPayload) -> "ping"
        payload.contentEquals(wifiDirectDebugPongPayload) -> "pong"
        else -> "frame ${payload.size} B"
    }
}
