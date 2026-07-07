package gr.hua.aurora.wifidirect.debug

import gr.hua.aurora.wifidirect.frame.WifiDirectTransportAdapter
import gr.hua.aurora.wifidirect.frame.WifiDirectTransportFrame

private const val wifiDirectSendBridgeNote =
    "Debug send bridge only; normal chat sending still uses BLE."

internal data class WifiDirectSendBridgeDiagnostics(
    val enabled: Boolean = false,
    val framesSubmitted: Long = 0L,
    val submitFailures: Long = 0L,
    val lastSubmittedFrameSize: Int? = null,
    val lastSendBridgeError: String? = null,
    val note: String = wifiDirectSendBridgeNote
)

internal class WifiDirectSendBridge(
    private val submitFrame: (WifiDirectTransportFrame, (Result<Unit>) -> Unit) -> Unit
) {
    constructor(
        transportAdapter: WifiDirectTransportAdapter
    ) : this(transportAdapter::submit)

    interface Listener {
        fun onSendBridgeDiagnosticsChanged(
            diagnostics: WifiDirectSendBridgeDiagnostics
        ) {}
    }

    private val listeners = linkedSetOf<Listener>()
    private val stateLock = Any()

    private var enabled = false
    private var framesSubmitted = 0L
    private var submitFailures = 0L
    private var lastSubmittedFrameSize: Int? = null
    private var lastSendBridgeError: String? = null

    fun currentDiagnostics(): WifiDirectSendBridgeDiagnostics {
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
                framesSubmitted = 0L
                submitFailures = 0L
                lastSubmittedFrameSize = null
                lastSendBridgeError = null
                currentDiagnosticsLocked()
            }
        )
    }

    fun setEnabled(
        enabled: Boolean
    ) {
        emit(
            synchronized(stateLock) {
                this.enabled = enabled
                if (!enabled) {
                    lastSendBridgeError = null
                }
                currentDiagnosticsLocked()
            }
        )
    }

    fun disable() {
        setEnabled(false)
    }

    fun submitPayload(
        payload: ByteArray,
        onResult: (Result<Unit>) -> Unit = {}
    ) {
        val frame = runCatching {
            WifiDirectTransportFrame.fromPayload(payload)
        }.getOrElse { error ->
            emitFailure(
                frameSize = payload.size.takeIf { it > 0 },
                reason = safeErrorDetail(error)
            )
            onResult(Result.failure(error))
            return
        }
        submit(frame, onResult)
    }

    fun submit(
        frame: WifiDirectTransportFrame,
        onResult: (Result<Unit>) -> Unit = {}
    ) {
        if (!currentDiagnostics().enabled) {
            val error = IllegalStateException("Wi-Fi Direct send bridge disabled.")
            emitFailure(
                frameSize = frame.payloadSize,
                reason = error.message ?: "Wi-Fi Direct send bridge disabled."
            )
            onResult(Result.failure(error))
            return
        }

        submitFrame(frame) { result ->
            result.onSuccess {
                emit(
                    synchronized(stateLock) {
                        framesSubmitted += 1L
                        lastSubmittedFrameSize = frame.payloadSize
                        lastSendBridgeError = null
                        currentDiagnosticsLocked()
                    }
                )
                onResult(Result.success(Unit))
            }.onFailure { error ->
                val reason = safeErrorDetail(error)
                emitFailure(
                    frameSize = frame.payloadSize,
                    reason = reason
                )
                onResult(Result.failure(error))
            }
        }
    }

    private fun emitFailure(
        frameSize: Int?,
        reason: String
    ) {
        emit(
            synchronized(stateLock) {
                submitFailures += 1L
                lastSubmittedFrameSize = frameSize
                lastSendBridgeError = reason
                currentDiagnosticsLocked()
            }
        )
    }

    private fun emit(
        diagnostics: WifiDirectSendBridgeDiagnostics
    ) {
        listeners.forEach { listener ->
            listener.onSendBridgeDiagnosticsChanged(diagnostics)
        }
    }

    private fun currentDiagnosticsLocked(): WifiDirectSendBridgeDiagnostics {
        return WifiDirectSendBridgeDiagnostics(
            enabled = enabled,
            framesSubmitted = framesSubmitted,
            submitFailures = submitFailures,
            lastSubmittedFrameSize = lastSubmittedFrameSize,
            lastSendBridgeError = lastSendBridgeError
        )
    }

    private fun safeErrorDetail(
        error: Throwable
    ): String {
        return error.message?.trim()?.takeIf { it.isNotEmpty() }
            ?: error::class.java.simpleName
    }
}

internal fun wifiDirectSendBridgeStateSummary(
    diagnostics: WifiDirectSendBridgeDiagnostics
): String {
    return if (diagnostics.enabled) {
        "enabled"
    } else {
        "disabled"
    }
}
