package gr.hua.aurora.wifidirect

import gr.hua.aurora.ble.transport.BleGattTransportFrame
import gr.hua.aurora.ble.transport.BleTransportReceiveResult

private const val wifiDirectReceiveBridgeNote =
    "Debug bridge only; normal send path still uses BLE."

internal data class WifiDirectReceiveBridgeDiagnostics(
    val enabled: Boolean = false,
    val framesBridged: Long = 0L,
    val bridgeFailures: Long = 0L,
    val lastBridgedFrameSize: Int? = null,
    val lastBridgeError: String? = null,
    val note: String = wifiDirectReceiveBridgeNote
)

internal class WifiDirectReceiveBridge(
    private val processFrame: (BleGattTransportFrame) -> BleTransportReceiveResult
) {
    interface Listener {
        fun onReceiveBridgeDiagnosticsChanged(
            diagnostics: WifiDirectReceiveBridgeDiagnostics
        ) {}
    }

    private val listeners = linkedSetOf<Listener>()
    private val stateLock = Any()

    private var enabled = false
    private var framesBridged = 0L
    private var bridgeFailures = 0L
    private var lastBridgedFrameSize: Int? = null
    private var lastBridgeError: String? = null

    fun currentDiagnostics(): WifiDirectReceiveBridgeDiagnostics {
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
                framesBridged = 0L
                bridgeFailures = 0L
                lastBridgedFrameSize = null
                lastBridgeError = null
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
                    lastBridgeError = null
                }
                currentDiagnosticsLocked()
            }
        )
    }

    fun disable() {
        setEnabled(false)
    }

    fun onTransportFrameReceived(
        frame: WifiDirectTransportFrame
    ) {
        if (!currentDiagnostics().enabled) {
            return
        }

        val encodedTransportFrame = frame.payloadBytes()
        val bleTransportFrame = BleGattTransportFrame.parse(encodedTransportFrame)
        if (bleTransportFrame == null) {
            emitFailure(
                frameSize = frame.payloadSize,
                reason = "Invalid Aurora transport frame payload."
            )
            return
        }

        val receiveResult = runCatching {
            processFrame(bleTransportFrame)
        }.getOrElse { error ->
            emitFailure(
                frameSize = frame.payloadSize,
                reason = safeErrorDetail(error)
            )
            return
        }

        val failureReason = wifiDirectReceiveBridgeFailureReason(receiveResult)
        emit(
            synchronized(stateLock) {
                framesBridged += 1L
                lastBridgedFrameSize = frame.payloadSize
                if (failureReason != null) {
                    bridgeFailures += 1L
                    lastBridgeError = failureReason
                } else {
                    lastBridgeError = null
                }
                currentDiagnosticsLocked()
            }
        )
    }

    private fun emitFailure(
        frameSize: Int?,
        reason: String
    ) {
        emit(
            synchronized(stateLock) {
                bridgeFailures += 1L
                lastBridgedFrameSize = frameSize
                lastBridgeError = reason
                currentDiagnosticsLocked()
            }
        )
    }

    private fun emit(
        diagnostics: WifiDirectReceiveBridgeDiagnostics
    ) {
        listeners.forEach { listener ->
            listener.onReceiveBridgeDiagnosticsChanged(diagnostics)
        }
    }

    private fun currentDiagnosticsLocked(): WifiDirectReceiveBridgeDiagnostics {
        return WifiDirectReceiveBridgeDiagnostics(
            enabled = enabled,
            framesBridged = framesBridged,
            bridgeFailures = bridgeFailures,
            lastBridgedFrameSize = lastBridgedFrameSize,
            lastBridgeError = lastBridgeError
        )
    }

    private fun safeErrorDetail(
        error: Throwable
    ): String {
        return error.message?.trim()?.takeIf { it.isNotEmpty() }
            ?: error::class.java.simpleName
    }
}

internal fun wifiDirectReceiveBridgeStateSummary(
    diagnostics: WifiDirectReceiveBridgeDiagnostics
): String {
    return if (diagnostics.enabled) {
        "enabled"
    } else {
        "disabled"
    }
}

private fun wifiDirectReceiveBridgeFailureReason(
    result: BleTransportReceiveResult
): String? {
    return when (result) {
        is BleTransportReceiveResult.Buffered,
        is BleTransportReceiveResult.Processed,
        is BleTransportReceiveResult.DuplicateChunk -> null
        is BleTransportReceiveResult.InvalidChunk -> result.reason
        is BleTransportReceiveResult.BufferOverflow -> result.reason
        is BleTransportReceiveResult.ProcessorFailed -> {
            wifiDirectReceiveBridgeProcessorFailureReason(result)
        }
    }
}

private fun wifiDirectReceiveBridgeProcessorFailureReason(
    result: BleTransportReceiveResult.ProcessorFailed
): String {
    return when (val receiveResult = result.processingResult.receiveResult) {
        is gr.hua.aurora.protocol.IncomingTransportReceiveResult.IncompleteChunks ->
            receiveResult.reason
        is gr.hua.aurora.protocol.IncomingTransportReceiveResult.InvalidEnvelope ->
            receiveResult.reason
        is gr.hua.aurora.protocol.IncomingTransportReceiveResult.SessionMaterialUnavailable ->
            receiveResult.reason
        is gr.hua.aurora.protocol.IncomingTransportReceiveResult.UnsupportedSender ->
            receiveResult.reason
        is gr.hua.aurora.protocol.IncomingTransportReceiveResult.InvalidSenderIdentity ->
            receiveResult.reason
        is gr.hua.aurora.protocol.IncomingTransportReceiveResult.DecryptFailed ->
            receiveResult.reason
        is gr.hua.aurora.protocol.IncomingTransportReceiveResult.InvalidFrame ->
            receiveResult.reason
        is gr.hua.aurora.protocol.IncomingTransportReceiveResult.Received ->
            "Incoming transport processing failed."
        is gr.hua.aurora.protocol.IncomingTransportReceiveResult.RelayOnlyEncrypted ->
            "Incoming encrypted relay processing failed."
    }
}
