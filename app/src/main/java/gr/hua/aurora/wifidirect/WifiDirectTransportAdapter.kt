package gr.hua.aurora.wifidirect

private const val wifiDirectTransportAdapterNote =
    "Wi-Fi Direct chat routing not wired yet."

internal enum class WifiDirectTransportAdapterState {
    DISABLED,
    NOT_READY,
    READY,
    FAILED
}

internal data class WifiDirectTransportAdapterDiagnostics(
    val state: WifiDirectTransportAdapterState = WifiDirectTransportAdapterState.DISABLED,
    val framesSubmitted: Long = 0L,
    val framesReceived: Long = 0L,
    val bytesSubmitted: Long = 0L,
    val bytesReceived: Long = 0L,
    val lastFrameSize: Int? = null,
    val lastError: String? = null,
    val note: String = wifiDirectTransportAdapterNote
)

internal interface WifiDirectTransportFrameSink {
    fun isTransportFrameReady(): Boolean
    fun submitTransportFramePayload(
        payload: ByteArray,
        onResult: (Result<Unit>) -> Unit = {}
    )
}

internal interface WifiDirectTransportFrameSource {
    interface Listener {
        fun onTransportFramePayloadReceived(
            payload: ByteArray,
            byteCount: Long
        )
    }

    fun addTransportFrameListener(listener: Listener)
    fun removeTransportFrameListener(listener: Listener)
}

internal class WifiDirectTransportAdapter(
    private val frameSink: WifiDirectTransportFrameSink? = null,
    private val frameSource: WifiDirectTransportFrameSource? = null,
    private val frameCodec: WifiDirectTransportFrameCodec = WifiDirectTransportFrameCodec(),
    private val enabled: Boolean = false
) {
    interface Listener {
        fun onTransportAdapterDiagnosticsChanged(
            diagnostics: WifiDirectTransportAdapterDiagnostics
        ) {}

        fun onTransportFrameReceived(
            frame: WifiDirectTransportFrame
        ) {}
    }

    private val listeners = linkedSetOf<Listener>()
    private val stateLock = Any()
    private var framesSubmitted = 0L
    private var framesReceived = 0L
    private var bytesSubmitted = 0L
    private var bytesReceived = 0L
    private var lastFrameSize: Int? = null
    private var lastError: String? = null

    private val sourceListener = object : WifiDirectTransportFrameSource.Listener {
        override fun onTransportFramePayloadReceived(
            payload: ByteArray,
            byteCount: Long
        ) {
            handleIncomingPayload(payload, byteCount)
        }
    }

    init {
        frameSource?.addTransportFrameListener(sourceListener)
    }

    fun currentDiagnostics(): WifiDirectTransportAdapterDiagnostics {
        return synchronized(stateLock) {
            WifiDirectTransportAdapterDiagnostics(
                state = currentStateLocked(),
                framesSubmitted = framesSubmitted,
                framesReceived = framesReceived,
                bytesSubmitted = bytesSubmitted,
                bytesReceived = bytesReceived,
                lastFrameSize = lastFrameSize,
                lastError = lastError
            )
        }
    }

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun submitPayload(
        payload: ByteArray,
        onResult: (Result<Unit>) -> Unit = {}
    ) {
        val frame = runCatching {
            WifiDirectTransportFrame.fromPayload(payload)
        }.getOrElse { error ->
            emit(updateFailure(safeErrorDetail(error)))
            onResult(Result.failure(error))
            return
        }
        submit(frame, onResult)
    }

    fun submit(
        frame: WifiDirectTransportFrame,
        onResult: (Result<Unit>) -> Unit = {}
    ) {
        if (!enabled) {
            val error = IllegalStateException("Wi-Fi Direct transport adapter disabled.")
            emit(updateFailure(error.message))
            onResult(Result.failure(error))
            return
        }
        if (frameSink == null || !frameSink.isTransportFrameReady()) {
            val error = IllegalStateException("Wi-Fi Direct transport adapter not ready.")
            emit(updateFailure(error.message))
            onResult(Result.failure(error))
            return
        }

        val encodedPayload = runCatching {
            frameCodec.encode(frame)
        }.getOrElse { error ->
            emit(updateFailure(safeErrorDetail(error)))
            onResult(Result.failure(error))
            return
        }

        frameSink.submitTransportFramePayload(encodedPayload) { result ->
            result.onSuccess {
                emit(
                    synchronized(stateLock) {
                        framesSubmitted += 1L
                        bytesSubmitted += frame.payloadSize.toLong()
                        lastFrameSize = frame.payloadSize
                        lastError = null
                        currentDiagnosticsLocked()
                    }
                )
                onResult(Result.success(Unit))
            }.onFailure { error ->
                emit(updateFailure(safeErrorDetail(error)))
                onResult(Result.failure(error))
            }
        }
    }

    fun dispose() {
        frameSource?.removeTransportFrameListener(sourceListener)
        listeners.clear()
    }

    private fun handleIncomingPayload(
        payload: ByteArray,
        byteCount: Long
    ) {
        if (!enabled) {
            return
        }
        frameCodec.decodeOrNull(payload)
            .onSuccess { frame ->
                if (frame == null) {
                    return
                }
                val diagnostics = synchronized(stateLock) {
                    framesReceived += 1L
                    bytesReceived += frame.payloadSize.toLong()
                    lastFrameSize = frame.payloadSize
                    lastError = null
                    currentDiagnosticsLocked()
                }
                emit(diagnostics)
                listeners.forEach { listener ->
                    listener.onTransportFrameReceived(frame)
                }
            }
            .onFailure { error ->
                emit(updateFailure(safeErrorDetail(error)))
            }
    }

    private fun emit(
        diagnostics: WifiDirectTransportAdapterDiagnostics
    ) {
        listeners.forEach { listener ->
            listener.onTransportAdapterDiagnosticsChanged(diagnostics)
        }
    }

    private fun updateFailure(
        reason: String?
    ): WifiDirectTransportAdapterDiagnostics {
        return synchronized(stateLock) {
            lastError = reason
            currentDiagnosticsLocked()
        }
    }

    private fun currentDiagnosticsLocked(): WifiDirectTransportAdapterDiagnostics {
        return WifiDirectTransportAdapterDiagnostics(
            state = currentStateLocked(),
            framesSubmitted = framesSubmitted,
            framesReceived = framesReceived,
            bytesSubmitted = bytesSubmitted,
            bytesReceived = bytesReceived,
            lastFrameSize = lastFrameSize,
            lastError = lastError
        )
    }

    private fun currentStateLocked(): WifiDirectTransportAdapterState {
        return when {
            !enabled -> WifiDirectTransportAdapterState.DISABLED
            frameSink?.isTransportFrameReady() != true -> WifiDirectTransportAdapterState.NOT_READY
            lastError != null -> WifiDirectTransportAdapterState.FAILED
            frameSink.isTransportFrameReady() -> WifiDirectTransportAdapterState.READY
            else -> WifiDirectTransportAdapterState.NOT_READY
        }
    }

    private fun safeErrorDetail(
        error: Throwable
    ): String {
        return error.message?.trim()?.takeIf { it.isNotEmpty() }
            ?: error::class.java.simpleName
    }
}

internal fun wifiDirectTransportAdapterStateSummary(
    state: WifiDirectTransportAdapterState
): String {
    return when (state) {
        WifiDirectTransportAdapterState.DISABLED -> "disabled"
        WifiDirectTransportAdapterState.NOT_READY -> "not ready"
        WifiDirectTransportAdapterState.READY -> "ready"
        WifiDirectTransportAdapterState.FAILED -> "failed"
    }
}

internal fun wifiDirectTransportAdapterByteSummary(
    diagnostics: WifiDirectTransportAdapterDiagnostics
): String {
    return "${diagnostics.bytesSubmitted}/${diagnostics.bytesReceived}"
}
