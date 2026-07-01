package gr.hua.aurora.wifidirect

import gr.hua.aurora.ble.transport.OutgoingBleTransportSendPlanBuilder
import gr.hua.aurora.protocol.EncryptedMessageEnvelopeCodec
import gr.hua.aurora.protocol.MessageFrameType
import gr.hua.aurora.protocol.PreparedPrivateChatTransportFrame
import java.nio.charset.StandardCharsets.UTF_8

private const val wifiDirectPrivateDebugSendNote =
    "Debug only. BLE remains the normal Private Chat path."

internal data class WifiDirectPrivateDebugSendDiagnostics(
    val enabled: Boolean = false,
    val bleRemainsPrimary: Boolean = true,
    val privateSubmissionAttempts: Long = 0L,
    val privateSubmissionSuccesses: Long = 0L,
    val privateSubmitFailures: Long = 0L,
    val lastPrivateMessageId: String? = null,
    val lastPrivateTargetPeerId: String? = null,
    val lastPrivateFrameSize: Int? = null,
    val lastPrivateSendResult: String? = null,
    val lastPrivateSendError: String? = null,
    val note: String = wifiDirectPrivateDebugSendNote
)

internal class WifiDirectPrivateDebugSendBridge(
    private val submitFrame: (WifiDirectTransportFrame, (Result<Unit>) -> Unit) -> Unit,
    private val sendBridgeDiagnostics: () -> WifiDirectSendBridgeDiagnostics,
    private val transportAdapterDiagnostics: () -> WifiDirectTransportAdapterDiagnostics
) {
    interface Listener {
        fun onPrivateDebugSendDiagnosticsChanged(
            diagnostics: WifiDirectPrivateDebugSendDiagnostics
        ) {}
    }

    private val listeners = linkedSetOf<Listener>()
    private val stateLock = Any()

    private var enabled = false
    private var privateSubmissionAttempts = 0L
    private var privateSubmissionSuccesses = 0L
    private var privateSubmitFailures = 0L
    private var lastPrivateMessageId: String? = null
    private var lastPrivateTargetPeerId: String? = null
    private var lastPrivateFrameSize: Int? = null
    private var lastPrivateSendResult: String? = null
    private var lastPrivateSendError: String? = null

    fun currentDiagnostics(): WifiDirectPrivateDebugSendDiagnostics {
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
                    lastPrivateSendError = null
                }
                currentDiagnosticsLocked()
            }
        )
    }

    fun disable() {
        setEnabled(false)
    }

    fun submitPrivateMessage(
        preparedTransportFrame: PreparedPrivateChatTransportFrame,
        onResult: (Result<Unit>) -> Unit = {}
    ) {
        val frame = preparedTransportFrame.frame
        if (frame.type != MessageFrameType.PRIVATE_TEXT) {
            val error = IllegalArgumentException(
                "Wi-Fi Direct Private send requires PRIVATE_TEXT frames."
            )
            emitFailure(
                messageId = frame.id,
                targetPeerId = preparedTransportFrame.targetPeerId,
                frameSize = null,
                resultLabel = "failed",
                reason = error.message ?: "Wi-Fi Direct Private send requires PRIVATE_TEXT frames."
            )
            onResult(Result.failure(error))
            return
        }
        recordSubmissionAttempt(
            messageId = frame.id,
            targetPeerId = preparedTransportFrame.targetPeerId
        )

        val readiness = readinessSnapshot()
        if (!readiness.enabled) {
            val error = IllegalStateException("Wi-Fi Direct Private send disabled.")
            emitFailure(
                messageId = frame.id,
                targetPeerId = preparedTransportFrame.targetPeerId,
                frameSize = null,
                resultLabel = "disabled",
                reason = error.message ?: "Wi-Fi Direct Private send disabled."
            )
            onResult(Result.failure(error))
            return
        }
        if (!readiness.sendBridgeEnabled) {
            val error = IllegalStateException(
                "Wi-Fi Direct Private send requires the send bridge to be enabled."
            )
            emitFailure(
                messageId = frame.id,
                targetPeerId = preparedTransportFrame.targetPeerId,
                frameSize = null,
                resultLabel = "blocked",
                reason = error.message
                    ?: "Wi-Fi Direct Private send requires the send bridge to be enabled."
            )
            onResult(Result.failure(error))
            return
        }
        if (readiness.adapterState != WifiDirectTransportAdapterState.READY) {
            val error = IllegalStateException(
                "Wi-Fi Direct Private send requires a ready transport adapter."
            )
            emitFailure(
                messageId = frame.id,
                targetPeerId = preparedTransportFrame.targetPeerId,
                frameSize = null,
                resultLabel = "blocked",
                reason = error.message
                    ?: "Wi-Fi Direct Private send requires a ready transport adapter."
            )
            onResult(Result.failure(error))
            return
        }

        val encodedEnvelopeBytes = EncryptedMessageEnvelopeCodec.encode(
            preparedTransportFrame.encryptedEnvelope
        ).toByteArray(UTF_8)
        val sendPlan = runCatching {
            OutgoingBleTransportSendPlanBuilder.build(
                messageId = frame.id,
                targetPeerId = preparedTransportFrame.targetPeerId,
                encryptedEnvelopeBytes = encodedEnvelopeBytes,
                sourceCreatedAtMillis = frame.createdAtMillis
            )
        }.getOrElse { error ->
            emitFailure(
                messageId = frame.id,
                targetPeerId = preparedTransportFrame.targetPeerId,
                frameSize = encodedEnvelopeBytes.size,
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
            targetPeerId = preparedTransportFrame.targetPeerId,
            encodedEnvelopeSize = encodedEnvelopeBytes.size,
            onResult = onResult
        )
    }

    private fun submitTransportFrameAtIndex(
        frames: List<WifiDirectTransportFrame>,
        frameIndex: Int,
        messageId: String,
        targetPeerId: String,
        encodedEnvelopeSize: Int,
        onResult: (Result<Unit>) -> Unit
    ) {
        if (frameIndex >= frames.size) {
            emit(
                synchronized(stateLock) {
                    privateSubmissionSuccesses += 1L
                    lastPrivateMessageId = messageId
                    lastPrivateTargetPeerId = targetPeerId
                    lastPrivateFrameSize = encodedEnvelopeSize
                    lastPrivateSendResult = "submitted locally"
                    lastPrivateSendError = null
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
                    targetPeerId = targetPeerId,
                    encodedEnvelopeSize = encodedEnvelopeSize,
                    onResult = onResult
                )
            }.onFailure { error ->
                emitFailure(
                    messageId = messageId,
                    targetPeerId = targetPeerId,
                    frameSize = encodedEnvelopeSize,
                    resultLabel = "failed",
                    reason = safeErrorDetail(error)
                )
                onResult(Result.failure(error))
            }
        }
    }

    private fun recordSubmissionAttempt(
        messageId: String,
        targetPeerId: String
    ) {
        synchronized(stateLock) {
            privateSubmissionAttempts += 1L
            lastPrivateMessageId = messageId
            lastPrivateTargetPeerId = targetPeerId
        }
    }

    private fun emitFailure(
        messageId: String,
        targetPeerId: String,
        frameSize: Int?,
        resultLabel: String,
        reason: String
    ) {
        emit(
            synchronized(stateLock) {
                privateSubmitFailures += 1L
                lastPrivateMessageId = messageId
                lastPrivateTargetPeerId = targetPeerId
                lastPrivateFrameSize = frameSize
                lastPrivateSendResult = resultLabel
                lastPrivateSendError = reason
                currentDiagnosticsLocked()
            }
        )
    }

    private fun emit(
        diagnostics: WifiDirectPrivateDebugSendDiagnostics
    ) {
        listeners.forEach { listener ->
            listener.onPrivateDebugSendDiagnosticsChanged(diagnostics)
        }
    }

    private fun currentDiagnosticsLocked(): WifiDirectPrivateDebugSendDiagnostics {
        return WifiDirectPrivateDebugSendDiagnostics(
            enabled = enabled,
            bleRemainsPrimary = true,
            privateSubmissionAttempts = privateSubmissionAttempts,
            privateSubmissionSuccesses = privateSubmissionSuccesses,
            privateSubmitFailures = privateSubmitFailures,
            lastPrivateMessageId = lastPrivateMessageId,
            lastPrivateTargetPeerId = lastPrivateTargetPeerId,
            lastPrivateFrameSize = lastPrivateFrameSize,
            lastPrivateSendResult = lastPrivateSendResult,
            lastPrivateSendError = lastPrivateSendError
        )
    }

    private fun readinessSnapshot(): WifiDirectPrivateDebugSendReadiness {
        val bridgeDiagnostics = sendBridgeDiagnostics()
        val adapterDiagnostics = transportAdapterDiagnostics()
        return WifiDirectPrivateDebugSendReadiness(
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

internal fun wifiDirectPrivateDebugSendModeSummary(
    diagnostics: WifiDirectPrivateDebugSendDiagnostics
): String {
    return if (diagnostics.bleRemainsPrimary) {
        "BLE primary + Wi-Fi Direct debug copy"
    } else {
        "Wi-Fi Direct debug only"
    }
}

internal fun wifiDirectPrivateDebugSendStateSummary(
    diagnostics: WifiDirectPrivateDebugSendDiagnostics
): String {
    return if (diagnostics.enabled) {
        "enabled"
    } else {
        "disabled"
    }
}

private data class WifiDirectPrivateDebugSendReadiness(
    val enabled: Boolean,
    val sendBridgeEnabled: Boolean,
    val adapterState: WifiDirectTransportAdapterState
)
