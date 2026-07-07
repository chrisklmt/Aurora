package gr.hua.aurora.wifidirect.debug

import android.util.Log
import gr.hua.aurora.ble.transport.BleGattTransportFrame
import gr.hua.aurora.ble.transport.BleTransportReceiveResult
import gr.hua.aurora.state.IncomingMessageIngestionResult
import gr.hua.aurora.transport.processing.IncomingTransportFrameProcessingResult
import gr.hua.aurora.wifidirect.frame.WifiDirectTransportFrame

private const val wifiDirectReceiveBridgeNote =
    "Debug bridge only; normal send path still uses BLE."
private const val wifiDirectReceiveBridgeLogTag = "WifiDirectReceiveBridge"

internal data class WifiDirectReceiveBridgeDiagnostics(
    val enabled: Boolean = false,
    val transportFramesObserved: Long = 0L,
    val framesBridged: Long = 0L,
    val duplicateFramesDropped: Long = 0L,
    val bridgeFailures: Long = 0L,
    val lastTransportFrameSize: Int? = null,
    val lastToggleAction: String? = null,
    val lastToggleResult: String? = null,
    val lastToggleBlockedReason: String? = null,
    val lastBridgeResult: String? = null,
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
    private var transportFramesObserved = 0L
    private var framesBridged = 0L
    private var duplicateFramesDropped = 0L
    private var bridgeFailures = 0L
    private var lastTransportFrameSize: Int? = null
    private var lastToggleAction: String? = null
    private var lastToggleResult: String? = null
    private var lastToggleBlockedReason: String? = null
    private var lastBridgeResult: String? = null
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
                transportFramesObserved = 0L
                framesBridged = 0L
                duplicateFramesDropped = 0L
                bridgeFailures = 0L
                lastTransportFrameSize = null
                lastToggleAction = null
                lastToggleResult = null
                lastToggleBlockedReason = null
                lastBridgeResult = null
                lastBridgeError = null
                currentDiagnosticsLocked()
            }
        )
    }

    fun recordBlockedToggle(
        enabled: Boolean,
        reason: String
    ) {
        safeWifiDirectReceiveBridgeLogDebug(
            "recordBlockedToggle: enabled=$enabled reason=$reason"
        )
        emit(
            synchronized(stateLock) {
                lastToggleAction = wifiDirectReceiveBridgeToggleActionLabel(enabled)
                lastToggleResult = "blocked"
                lastToggleBlockedReason = reason
                currentDiagnosticsLocked()
            }
        )
    }

    fun setEnabled(
        enabled: Boolean
    ) {
        safeWifiDirectReceiveBridgeLogDebug(
            "setEnabled: enabled=$enabled"
        )
        emit(
            synchronized(stateLock) {
                this.enabled = enabled
                lastToggleAction = wifiDirectReceiveBridgeToggleActionLabel(enabled)
                lastToggleResult = if (enabled) "enabled" else "disabled"
                lastToggleBlockedReason = null
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
            safeWifiDirectReceiveBridgeLogDebug(
                "receive ignored: bridge disabled frameSize=${frame.payloadSize}"
            )
            return
        }

        val encodedTransportFrame = frame.payloadBytes()
        val bleTransportFrame = BleGattTransportFrame.parse(encodedTransportFrame)
        if (bleTransportFrame == null) {
            safeWifiDirectReceiveBridgeLogDebug(
                "receive rejected: invalid Aurora transport payload frameSize=${frame.payloadSize}"
            )
            emitFailure(
                frameSize = frame.payloadSize,
                reason = "Invalid Aurora transport frame payload."
            )
            return
        }

        val receiveResult = runCatching {
            processFrame(bleTransportFrame)
        }.getOrElse { error ->
            safeWifiDirectReceiveBridgeLogWarning(
                "receive processing failure: frameSize=${frame.payloadSize}",
                error
            )
            emitFailure(
                frameSize = frame.payloadSize,
                reason = safeErrorDetail(error)
            )
            return
        }
        safeWifiDirectReceiveBridgeLogDebug(
            "receive processed: frameSize=${frame.payloadSize} result=${wifiDirectReceiveBridgeResultLabel(receiveResult)}"
        )

        val failureReason = wifiDirectReceiveBridgeFailureReason(receiveResult)
        emit(
            synchronized(stateLock) {
                transportFramesObserved += 1L
                lastTransportFrameSize = frame.payloadSize
                lastBridgeResult = wifiDirectReceiveBridgeResultLabel(receiveResult)
                when {
                    wifiDirectReceiveBridgeCountsAsLogicalDelivery(receiveResult) -> {
                        framesBridged += 1L
                        lastBridgeError = null
                    }
                    wifiDirectReceiveBridgeCountsAsDuplicate(receiveResult) -> {
                        duplicateFramesDropped += 1L
                        lastBridgeError = null
                    }
                    failureReason != null -> {
                        bridgeFailures += 1L
                        lastBridgeError = failureReason
                    }
                    else -> {
                        lastBridgeError = null
                    }
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
                lastTransportFrameSize = frameSize
                lastBridgeResult = "failed"
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
            transportFramesObserved = transportFramesObserved,
            framesBridged = framesBridged,
            duplicateFramesDropped = duplicateFramesDropped,
            bridgeFailures = bridgeFailures,
            lastTransportFrameSize = lastTransportFrameSize,
            lastToggleAction = lastToggleAction,
            lastToggleResult = lastToggleResult,
            lastToggleBlockedReason = lastToggleBlockedReason,
            lastBridgeResult = lastBridgeResult,
            lastBridgeError = lastBridgeError
        )
    }

    private fun safeErrorDetail(
        error: Throwable
    ): String {
        return error.message?.trim()?.takeIf { it.isNotEmpty() }
            ?: error::class.java.simpleName
    }

    private fun safeWifiDirectReceiveBridgeLogDebug(
        message: String
    ) {
        runCatching {
            Log.d(
                wifiDirectReceiveBridgeLogTag,
                message
            )
        }
    }

    private fun safeWifiDirectReceiveBridgeLogWarning(
        message: String,
        error: Throwable
    ) {
        runCatching {
            Log.w(
                wifiDirectReceiveBridgeLogTag,
                message,
                error
            )
        }
    }
}

private fun wifiDirectReceiveBridgeToggleActionLabel(
    enabled: Boolean
): String {
    return if (enabled) {
        "Enable receive bridge"
    } else {
        "Disable receive bridge"
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

private fun wifiDirectReceiveBridgeCountsAsLogicalDelivery(
    result: BleTransportReceiveResult
): Boolean {
    return when (result) {
        is BleTransportReceiveResult.Processed -> {
            when (val processingResult = result.processingResult) {
                is IncomingTransportFrameProcessingResult.Received ->
                    processingResult.ingestionResult !is IncomingMessageIngestionResult.Duplicate
                else -> true
            }
        }
        else -> false
    }
}

private fun wifiDirectReceiveBridgeCountsAsDuplicate(
    result: BleTransportReceiveResult
): Boolean {
    return when (result) {
        is BleTransportReceiveResult.DuplicateChunk -> true
        is BleTransportReceiveResult.Processed -> {
            val processingResult = result.processingResult
            processingResult is IncomingTransportFrameProcessingResult.Received &&
                processingResult.ingestionResult is IncomingMessageIngestionResult.Duplicate
        }
        else -> false
    }
}

private fun wifiDirectReceiveBridgeResultLabel(
    result: BleTransportReceiveResult
): String {
    return when (result) {
        is BleTransportReceiveResult.Buffered -> "buffered"
        is BleTransportReceiveResult.Processed -> {
            when (val processingResult = result.processingResult) {
                is IncomingTransportFrameProcessingResult.Received -> {
                    when (processingResult.ingestionResult) {
                        is IncomingMessageIngestionResult.Appended -> "processed"
                        is IncomingMessageIngestionResult.Duplicate -> "duplicate message"
                        is IncomingMessageIngestionResult.UnsupportedThread -> "unsupported thread"
                        is IncomingMessageIngestionResult.UnsupportedType -> "unsupported type"
                    }
                }
                is IncomingTransportFrameProcessingResult.IdentityHandled -> "identity handled"
                is IncomingTransportFrameProcessingResult.IdentityHandlingUnavailable ->
                    "identity unavailable"
                is IncomingTransportFrameProcessingResult.RelayOnlyEncrypted -> "relay only"
            }
        }
        is BleTransportReceiveResult.DuplicateChunk -> "duplicate chunk"
        is BleTransportReceiveResult.InvalidChunk,
        is BleTransportReceiveResult.BufferOverflow,
        is BleTransportReceiveResult.ProcessorFailed -> "failed"
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
