package gr.hua.aurora.wifidirect

import gr.hua.aurora.ble.transport.OutgoingBleTransportSendPlanBuilder
import gr.hua.aurora.model.MessageStatus
import gr.hua.aurora.model.OutgoingChatMessage
import gr.hua.aurora.protocol.MessageFrameCodec
import gr.hua.aurora.protocol.OutgoingMessageFrameBuilder
import gr.hua.aurora.protocol.OutgoingMessageFrameResolver
import java.nio.charset.StandardCharsets.UTF_8

private const val wifiDirectSmokeTestNote =
    "Debug-only Wi-Fi Direct smoke test. Normal chat sending still uses BLE."

private const val wifiDirectSmokeTestPayload =
    "[Wi-Fi Direct debug smoke test]"

internal data class WifiDirectSmokeTestDiagnostics(
    val ready: Boolean = false,
    val sendBridgeEnabled: Boolean = false,
    val adapterState: WifiDirectTransportAdapterState = WifiDirectTransportAdapterState.DISABLED,
    val smokeFramesSent: Long = 0L,
    val smokeSendFailures: Long = 0L,
    val lastSmokeFrameSize: Int? = null,
    val lastSmokeSendResult: String? = null,
    val lastSmokeError: String? = null,
    val note: String = wifiDirectSmokeTestNote
)

internal class WifiDirectSmokeTestSender(
    private val submitFrame: (WifiDirectTransportFrame, (Result<Unit>) -> Unit) -> Unit,
    private val sendBridgeDiagnostics: () -> WifiDirectSendBridgeDiagnostics,
    private val transportAdapterDiagnostics: () -> WifiDirectTransportAdapterDiagnostics,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    interface Listener {
        fun onSmokeTestDiagnosticsChanged(
            diagnostics: WifiDirectSmokeTestDiagnostics
        ) {}
    }

    private val listeners = linkedSetOf<Listener>()
    private val stateLock = Any()

    private var nextSequence = 0L
    private var smokeFramesSent = 0L
    private var smokeSendFailures = 0L
    private var lastSmokeFrameSize: Int? = null
    private var lastSmokeSendResult: String? = null
    private var lastSmokeError: String? = null

    fun currentDiagnostics(): WifiDirectSmokeTestDiagnostics {
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

    fun sendPublicSmokeTest(
        senderId: String,
        onResult: (Result<Unit>) -> Unit = {}
    ) {
        val sanitizedSenderId = senderId.trim()
        if (sanitizedSenderId.isEmpty()) {
            val error = IllegalArgumentException("Wi-Fi Direct smoke test sender id unavailable.")
            emitFailure(
                frameSize = null,
                resultLabel = "failed",
                reason = error.message ?: "Wi-Fi Direct smoke test sender id unavailable."
            )
            onResult(Result.failure(error))
            return
        }

        val readiness = readinessSnapshot()
        if (!readiness.sendBridgeEnabled) {
            val error = IllegalStateException(
                "Wi-Fi Direct smoke test requires the send bridge to be enabled."
            )
            emitFailure(
                frameSize = null,
                resultLabel = "blocked",
                reason = error.message ?: "Wi-Fi Direct smoke test requires the send bridge."
            )
            onResult(Result.failure(error))
            return
        }
        if (readiness.adapterState != WifiDirectTransportAdapterState.READY) {
            val error = IllegalStateException(
                "Wi-Fi Direct smoke test requires a ready transport adapter."
            )
            emitFailure(
                frameSize = null,
                resultLabel = "blocked",
                reason = error.message ?: "Wi-Fi Direct smoke test requires a ready transport adapter."
            )
            onResult(Result.failure(error))
            return
        }

        val frame = runCatching {
            buildSmokeMessageFrame(
                senderId = sanitizedSenderId,
                createdAtMillis = nowMillis(),
                sequence = nextMessageSequence()
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

        val transportFrames = sendPlan.framesInSendOrder()
        submitTransportFrames(
            frames = transportFrames.map { transportFrame ->
                WifiDirectTransportFrame.fromPayload(transportFrame.toByteArray())
            },
            frameSize = encodedFrameBytes.size,
            onResult = onResult
        )
    }

    private fun submitTransportFrames(
        frames: List<WifiDirectTransportFrame>,
        frameSize: Int,
        onResult: (Result<Unit>) -> Unit
    ) {
        submitTransportFrameAtIndex(
            frames = frames,
            frameIndex = 0,
            frameSize = frameSize,
            onResult = onResult
        )
    }

    private fun submitTransportFrameAtIndex(
        frames: List<WifiDirectTransportFrame>,
        frameIndex: Int,
        frameSize: Int,
        onResult: (Result<Unit>) -> Unit
    ) {
        if (frameIndex >= frames.size) {
            emit(
                synchronized(stateLock) {
                    smokeFramesSent += 1L
                    lastSmokeFrameSize = frameSize
                    lastSmokeSendResult = "submitted locally"
                    lastSmokeError = null
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
                    frameSize = frameSize,
                    onResult = onResult
                )
            }.onFailure { error ->
                emitFailure(
                    frameSize = frameSize,
                    resultLabel = "failed",
                    reason = safeErrorDetail(error)
                )
                onResult(Result.failure(error))
            }
        }
    }

    private fun nextMessageSequence(): Long {
        return synchronized(stateLock) {
            nextSequence += 1L
            nextSequence
        }
    }

    private fun emitFailure(
        frameSize: Int?,
        resultLabel: String,
        reason: String
    ) {
        emit(
            synchronized(stateLock) {
                smokeSendFailures += 1L
                lastSmokeFrameSize = frameSize
                lastSmokeSendResult = resultLabel
                lastSmokeError = reason
                currentDiagnosticsLocked()
            }
        )
    }

    private fun emit(
        diagnostics: WifiDirectSmokeTestDiagnostics
    ) {
        listeners.forEach { listener ->
            listener.onSmokeTestDiagnosticsChanged(diagnostics)
        }
    }

    private fun currentDiagnosticsLocked(): WifiDirectSmokeTestDiagnostics {
        val readiness = readinessSnapshot()
        return WifiDirectSmokeTestDiagnostics(
            ready = readiness.ready,
            sendBridgeEnabled = readiness.sendBridgeEnabled,
            adapterState = readiness.adapterState,
            smokeFramesSent = smokeFramesSent,
            smokeSendFailures = smokeSendFailures,
            lastSmokeFrameSize = lastSmokeFrameSize,
            lastSmokeSendResult = lastSmokeSendResult,
            lastSmokeError = lastSmokeError
        )
    }

    private fun readinessSnapshot(): WifiDirectSmokeTestReadiness {
        val bridgeDiagnostics = sendBridgeDiagnostics()
        val adapterDiagnostics = transportAdapterDiagnostics()
        return WifiDirectSmokeTestReadiness(
            sendBridgeEnabled = bridgeDiagnostics.enabled,
            adapterState = adapterDiagnostics.state
        )
    }

    private fun safeErrorDetail(
        error: Throwable
    ): String {
        return error.message?.trim()?.takeIf { it.isNotEmpty() }
            ?: error::class.java.simpleName
    }
}

internal fun wifiDirectSmokeTestStateSummary(
    diagnostics: WifiDirectSmokeTestDiagnostics
): String {
    return if (diagnostics.ready) {
        "ready"
    } else {
        "not ready"
    }
}

private data class WifiDirectSmokeTestReadiness(
    val sendBridgeEnabled: Boolean,
    val adapterState: WifiDirectTransportAdapterState
) {
    val ready: Boolean
        get() = sendBridgeEnabled && adapterState == WifiDirectTransportAdapterState.READY
}

private fun buildSmokeMessageFrame(
    senderId: String,
    createdAtMillis: Long,
    sequence: Long
): gr.hua.aurora.protocol.MessageFrame {
    val queuedMessage = OutgoingChatMessage(
        messageId = "wifi-direct-smoke-$createdAtMillis-$sequence",
        threadId = "global",
        userText = wifiDirectSmokeTestPayload,
        createdAtMillis = createdAtMillis,
        status = MessageStatus.LOCAL_ONLY
    )
    return OutgoingMessageFrameResolver.resolve(
        draft = OutgoingMessageFrameBuilder.build(queuedMessage),
        senderId = senderId
    ).copy(ttl = 1)
}
