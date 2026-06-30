package gr.hua.aurora.wifidirect

import gr.hua.aurora.ble.transport.OutgoingBleTransportSendPlanBuilder
import gr.hua.aurora.model.OutgoingChatMessage
import gr.hua.aurora.protocol.MessageFrame
import gr.hua.aurora.protocol.MessageFrameCodec
import gr.hua.aurora.protocol.OutgoingMessageFrameBuilder
import gr.hua.aurora.protocol.OutgoingMessageFrameResolver
import java.nio.charset.StandardCharsets.UTF_8

private const val wifiDirectGlobalDebugSendTtl = 10

private const val wifiDirectGlobalDebugSendNote =
    "Experimental debug only. Normal chat still uses BLE unless Wi-Fi Direct global send is enabled. Private Chat still uses BLE."

internal data class WifiDirectGlobalDebugSendDiagnostics(
    val enabled: Boolean = false,
    val globalFramesSubmitted: Long = 0L,
    val globalSubmitFailures: Long = 0L,
    val lastGlobalFrameSize: Int? = null,
    val lastGlobalSendResult: String? = null,
    val lastGlobalSendError: String? = null,
    val note: String = wifiDirectGlobalDebugSendNote
)

internal class WifiDirectGlobalDebugSendBridge(
    private val submitFrame: (WifiDirectTransportFrame, (Result<Unit>) -> Unit) -> Unit,
    private val sendBridgeDiagnostics: () -> WifiDirectSendBridgeDiagnostics,
    private val transportAdapterDiagnostics: () -> WifiDirectTransportAdapterDiagnostics
) {
    interface Listener {
        fun onGlobalDebugSendDiagnosticsChanged(
            diagnostics: WifiDirectGlobalDebugSendDiagnostics
        ) {}
    }

    private val listeners = linkedSetOf<Listener>()
    private val stateLock = Any()

    private var enabled = false
    private var globalFramesSubmitted = 0L
    private var globalSubmitFailures = 0L
    private var lastGlobalFrameSize: Int? = null
    private var lastGlobalSendResult: String? = null
    private var lastGlobalSendError: String? = null

    fun currentDiagnostics(): WifiDirectGlobalDebugSendDiagnostics {
        return synchronized(stateLock) {
            currentDiagnosticsLocked()
        }
    }

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun setEnabled(enabled: Boolean) {
        emit(
            synchronized(stateLock) {
                this.enabled = enabled
                if (!enabled) {
                    lastGlobalSendError = null
                }
                currentDiagnosticsLocked()
            }
        )
    }

    fun disable() {
        setEnabled(false)
    }

    fun submitGlobalMessage(
        message: OutgoingChatMessage,
        senderId: String,
        onResult: (Result<Unit>) -> Unit = {}
    ) {
        val sanitizedSenderId = senderId.trim()
        if (sanitizedSenderId.isEmpty()) {
            val error = IllegalArgumentException("Wi-Fi Direct Global send sender id unavailable.")
            emitFailure(
                frameSize = null,
                resultLabel = "failed",
                reason = error.message ?: "Wi-Fi Direct Global send sender id unavailable."
            )
            onResult(Result.failure(error))
            return
        }

        if (message.threadId != "global") {
            val error = IllegalArgumentException(
                "Wi-Fi Direct Global send only supports the global thread."
            )
            emitFailure(
                frameSize = null,
                resultLabel = "failed",
                reason = error.message ?: "Wi-Fi Direct Global send only supports the global thread."
            )
            onResult(Result.failure(error))
            return
        }

        val readiness = readinessSnapshot()
        if (!readiness.enabled) {
            val error = IllegalStateException("Wi-Fi Direct Global send disabled.")
            emitFailure(
                frameSize = null,
                resultLabel = "disabled",
                reason = error.message ?: "Wi-Fi Direct Global send disabled."
            )
            onResult(Result.failure(error))
            return
        }
        if (!readiness.sendBridgeEnabled) {
            val error = IllegalStateException(
                "Wi-Fi Direct Global send requires the send bridge to be enabled."
            )
            emitFailure(
                frameSize = null,
                resultLabel = "blocked",
                reason = error.message
                    ?: "Wi-Fi Direct Global send requires the send bridge to be enabled."
            )
            onResult(Result.failure(error))
            return
        }
        if (readiness.adapterState != WifiDirectTransportAdapterState.READY) {
            val error = IllegalStateException(
                "Wi-Fi Direct Global send requires a ready transport adapter."
            )
            emitFailure(
                frameSize = null,
                resultLabel = "blocked",
                reason = error.message
                    ?: "Wi-Fi Direct Global send requires a ready transport adapter."
            )
            onResult(Result.failure(error))
            return
        }

        val frame = runCatching {
            buildWifiDirectGlobalMessageFrame(
                message = message,
                senderId = sanitizedSenderId
            )
        }.getOrElse { error ->
            emitFailure(
                frameSize = null,
                resultLabel = "failed",
                reason = safeErrorDetail(error)
            )
            onResult(Result.failure(error))
            return
        }
        val encodedFrameBytes = MessageFrameCodec.encode(frame).toByteArray(UTF_8)
        val sendPlan = runCatching {
            OutgoingBleTransportSendPlanBuilder.build(
                messageId = frame.id,
                targetPeerId = null,
                encryptedEnvelopeBytes = encodedFrameBytes,
                sourceCreatedAtMillis = frame.createdAtMillis
            )
        }.getOrElse { error ->
            emitFailure(
                frameSize = encodedFrameBytes.size,
                resultLabel = "failed",
                reason = safeErrorDetail(error)
            )
            onResult(Result.failure(error))
            return
        }

        val transportFrames = sendPlan.framesInSendOrder().map { transportFrame ->
            WifiDirectTransportFrame.fromPayload(transportFrame.toByteArray())
        }
        submitTransportFrameAtIndex(
            frames = transportFrames,
            frameIndex = 0,
            encodedFrameSize = encodedFrameBytes.size,
            totalFrames = transportFrames.size,
            onResult = onResult
        )
    }

    private fun submitTransportFrameAtIndex(
        frames: List<WifiDirectTransportFrame>,
        frameIndex: Int,
        encodedFrameSize: Int,
        totalFrames: Int,
        onResult: (Result<Unit>) -> Unit
    ) {
        if (frameIndex >= frames.size) {
            emit(
                synchronized(stateLock) {
                    globalFramesSubmitted += totalFrames.toLong()
                    lastGlobalFrameSize = encodedFrameSize
                    lastGlobalSendResult = "submitted locally"
                    lastGlobalSendError = null
                    currentDiagnosticsLocked()
                }
            )
            onResult(Result.success(Unit))
            return
        }

        submitFrame(frames[frameIndex]) { result ->
            result.onSuccess {
                submitTransportFrameAtIndex(
                    frames = frames,
                    frameIndex = frameIndex + 1,
                    encodedFrameSize = encodedFrameSize,
                    totalFrames = totalFrames,
                    onResult = onResult
                )
            }.onFailure { error ->
                emitFailure(
                    frameSize = encodedFrameSize,
                    resultLabel = "failed",
                    reason = safeErrorDetail(error)
                )
                onResult(Result.failure(error))
            }
        }
    }

    private fun emitFailure(
        frameSize: Int?,
        resultLabel: String,
        reason: String
    ) {
        emit(
            synchronized(stateLock) {
                globalSubmitFailures += 1L
                lastGlobalFrameSize = frameSize
                lastGlobalSendResult = resultLabel
                lastGlobalSendError = reason
                currentDiagnosticsLocked()
            }
        )
    }

    private fun emit(
        diagnostics: WifiDirectGlobalDebugSendDiagnostics
    ) {
        listeners.forEach { listener ->
            listener.onGlobalDebugSendDiagnosticsChanged(diagnostics)
        }
    }

    private fun currentDiagnosticsLocked(): WifiDirectGlobalDebugSendDiagnostics {
        return WifiDirectGlobalDebugSendDiagnostics(
            enabled = enabled,
            globalFramesSubmitted = globalFramesSubmitted,
            globalSubmitFailures = globalSubmitFailures,
            lastGlobalFrameSize = lastGlobalFrameSize,
            lastGlobalSendResult = lastGlobalSendResult,
            lastGlobalSendError = lastGlobalSendError
        )
    }

    private fun readinessSnapshot(): WifiDirectGlobalDebugSendReadiness {
        val bridgeDiagnostics = sendBridgeDiagnostics()
        val adapterDiagnostics = transportAdapterDiagnostics()
        return WifiDirectGlobalDebugSendReadiness(
            enabled = currentDiagnostics().enabled,
            sendBridgeEnabled = bridgeDiagnostics.enabled,
            adapterState = adapterDiagnostics.state
        )
    }

    private fun safeErrorDetail(error: Throwable): String {
        return error.message?.trim()?.takeIf { it.isNotEmpty() }
            ?: error::class.java.simpleName
    }
}

internal fun wifiDirectGlobalDebugSendStateSummary(
    diagnostics: WifiDirectGlobalDebugSendDiagnostics
): String {
    return if (diagnostics.enabled) {
        "enabled"
    } else {
        "disabled"
    }
}

private data class WifiDirectGlobalDebugSendReadiness(
    val enabled: Boolean,
    val sendBridgeEnabled: Boolean,
    val adapterState: WifiDirectTransportAdapterState
)

private fun buildWifiDirectGlobalMessageFrame(
    message: OutgoingChatMessage,
    senderId: String
): MessageFrame {
    return OutgoingMessageFrameResolver.resolve(
        draft = OutgoingMessageFrameBuilder.build(message),
        senderId = senderId
    ).copy(ttl = wifiDirectGlobalDebugSendTtl)
}
