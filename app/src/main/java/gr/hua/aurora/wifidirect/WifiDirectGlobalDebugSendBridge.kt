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
    "Debug only. BLE remains the normal Global Chat path."

internal data class WifiDirectGlobalDebugSendDiagnostics(
    val enabled: Boolean = false,
    val bleRemainsPrimary: Boolean = true,
    val globalSubmissionAttempts: Long = 0L,
    val globalSubmissionSuccesses: Long = 0L,
    val globalSubmitFailures: Long = 0L,
    val lastGlobalMessageId: String? = null,
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
    private var globalSubmissionAttempts = 0L
    private var globalSubmissionSuccesses = 0L
    private var globalSubmitFailures = 0L
    private var lastGlobalMessageId: String? = null
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

    fun resetDiagnostics() {
        emit(
            synchronized(stateLock) {
                globalSubmissionAttempts = 0L
                globalSubmissionSuccesses = 0L
                globalSubmitFailures = 0L
                lastGlobalMessageId = null
                lastGlobalFrameSize = null
                lastGlobalSendResult = null
                lastGlobalSendError = null
                currentDiagnosticsLocked()
            }
        )
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
                messageId = message.messageId,
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
                messageId = message.messageId,
                frameSize = null,
                resultLabel = "failed",
                reason = error.message ?: "Wi-Fi Direct Global send only supports the global thread."
            )
            onResult(Result.failure(error))
            return
        }
        recordSubmissionAttempt(message.messageId)

        val readiness = readinessSnapshot()
        if (!readiness.enabled) {
            val error = IllegalStateException("Wi-Fi Direct Global send disabled.")
            emitFailure(
                messageId = message.messageId,
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
                messageId = message.messageId,
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
                messageId = message.messageId,
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
                messageId = message.messageId,
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
                messageId = frame.id,
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
            messageId = frame.id,
            encodedFrameSize = encodedFrameBytes.size,
            onResult = onResult
        )
    }

    private fun submitTransportFrameAtIndex(
        frames: List<WifiDirectTransportFrame>,
        frameIndex: Int,
        messageId: String,
        encodedFrameSize: Int,
        onResult: (Result<Unit>) -> Unit
    ) {
        if (frameIndex >= frames.size) {
            emit(
                synchronized(stateLock) {
                    globalSubmissionSuccesses += 1L
                    lastGlobalMessageId = messageId
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
                    messageId = messageId,
                    encodedFrameSize = encodedFrameSize,
                    onResult = onResult
                )
            }.onFailure { error ->
                emitFailure(
                    messageId = messageId,
                    frameSize = encodedFrameSize,
                    resultLabel = "failed",
                    reason = safeErrorDetail(error)
                )
                onResult(Result.failure(error))
            }
        }
    }

    private fun recordSubmissionAttempt(
        messageId: String
    ) {
        synchronized(stateLock) {
            globalSubmissionAttempts += 1L
            lastGlobalMessageId = messageId
        }
    }

    private fun emitFailure(
        messageId: String,
        frameSize: Int?,
        resultLabel: String,
        reason: String
    ) {
        emit(
            synchronized(stateLock) {
                globalSubmitFailures += 1L
                lastGlobalMessageId = messageId
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
            bleRemainsPrimary = true,
            globalSubmissionAttempts = globalSubmissionAttempts,
            globalSubmissionSuccesses = globalSubmissionSuccesses,
            globalSubmitFailures = globalSubmitFailures,
            lastGlobalMessageId = lastGlobalMessageId,
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

internal fun wifiDirectGlobalDebugSendModeSummary(
    diagnostics: WifiDirectGlobalDebugSendDiagnostics
): String {
    return if (diagnostics.bleRemainsPrimary) {
        "BLE primary + Wi-Fi Direct debug copy"
    } else {
        "Wi-Fi Direct debug only"
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
